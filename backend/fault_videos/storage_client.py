"""
Policy-driven storage acquisition & verification client for FileGrid PPC.
Connects to the `make dev` stack (PostgreSQL 18, MinIO S3, vsftpd FTP) using
only the Python standard library (zero external pip packages required).

Features:
  - Reads `.env` automatically from the repository root
  - Queries `filegrid.storage_policy` per `(category, region)` dynamically
  - Routes files to FTP, S3 (single PUT or resumable Multipart Upload for 1GB+ videos),
    or PostgreSQL (`stored_file_blob`)
  - Constant O(1) memory streaming for SHA-256 calculation and transfers
  - Records metadata in `stored_file`, `fault_record` / `drone_video`, and `audit_log`
  - Verifies stored files end-to-end by streaming them back and comparing SHA-256
"""

from __future__ import annotations

import base64
import datetime as dt
import ftplib
import hashlib
import hmac
import http.client
import json
import os
import secrets
import socket
import struct
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple

from .generator import sha256_file


REPO_ROOT = Path(__file__).resolve().parents[2]
STREAM_CHUNK_SIZE = 8 * 1024 * 1024       # 8 MB general streaming chunk
S3_MULTIPART_THRESHOLD = 16 * 1024 * 1024  # 16 MB threshold for S3 multipart
S3_PART_SIZE = 16 * 1024 * 1024            # 16 MB per S3 part


def load_env(env_path: Optional[Path] = None) -> Dict[str, str]:
    """Load key=value variables from `.env` (falling back to `os.environ`)."""
    path = env_path or (REPO_ROOT / ".env")
    env: Dict[str, str] = dict(os.environ)
    if path.exists():
        with path.open("r", encoding="utf-8") as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip())
    return env


# =============================================================================
# Pure-stdlib PostgreSQL v3 Wire Protocol Client (with SCRAM-SHA-256)
# =============================================================================


class PgError(RuntimeError):
    pass


class PgWireClient:
    """
    Minimal PostgreSQL v3 wire-protocol client supporting SCRAM-SHA-256 and cleartext/MD5
    over TCP (`127.0.0.1:5432` from `make dev`), with search_path = filegrid.
    """

    def __init__(
        self,
        host: str,
        port: int,
        user: str,
        password: str,
        database: str,
        schema: str = "filegrid",
        timeout: float = 15.0,
    ) -> None:
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.database = database
        self.schema = schema
        self.timeout = timeout
        self.sock: Optional[socket.socket] = None

    def __enter__(self) -> "PgWireClient":
        self.connect()
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.close()

    def connect(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        params = (
            f"user\x00{self.user}\x00"
            f"database\x00{self.database}\x00"
            f"options\x00-c search_path={self.schema}\x00"
            f"client_encoding\x00UTF8\x00\x00"
        ).encode("utf-8")
        startup = struct.pack("!II", len(params) + 8, 196608) + params
        self.sock.sendall(startup)

        while True:
            msg_type, body = self._read_msg()
            if msg_type == b"R":
                auth_code = struct.unpack("!I", body[:4])[0]
                if auth_code == 0:
                    continue
                elif auth_code == 3:
                    # Cleartext password
                    pw = self.password.encode("utf-8") + b"\x00"
                    self._send_msg(b"p", pw)
                elif auth_code == 5:
                    # MD5 password
                    salt = body[4:8]
                    inner = hashlib.md5((self.password + self.user).encode("utf-8")).hexdigest().encode("ascii")
                    outer = b"md5" + hashlib.md5(inner + salt).hexdigest().encode("ascii") + b"\x00"
                    self._send_msg(b"p", outer)
                elif auth_code == 10:
                    # SASL (SCRAM-SHA-256)
                    self._handle_scram()
                else:
                    raise PgError(f"Unsupported PostgreSQL auth code: {auth_code}")
            elif msg_type == b"E":
                raise PgError(self._parse_error(body))
            elif msg_type == b"Z":
                break

    def _handle_scram(self) -> None:
        client_nonce = base64.b64encode(secrets.token_bytes(18)).decode("ascii")
        client_first_bare = f"n=,r={client_nonce}"
        client_first = f"n,,{client_first_bare}".encode("utf-8")
        mech = b"SCRAM-SHA-256\x00" + struct.pack("!I", len(client_first)) + client_first
        self._send_msg(b"p", mech)

        msg_type, body = self._read_msg()
        if msg_type == b"E":
            raise PgError(self._parse_error(body))
        auth_code = struct.unpack("!I", body[:4])[0]
        if auth_code != 11:
            raise PgError(f"Expected SASLContinue (11), got {auth_code}")

        server_first = body[4:].decode("utf-8")
        parts = dict(item.split("=", 1) for item in server_first.split(","))
        combined_nonce = parts["r"]
        if not combined_nonce.startswith(client_nonce):
            raise PgError("SCRAM server nonce does not start with client nonce")
        salt = base64.b64decode(parts["s"])
        iterations = int(parts["i"])

        salted_pw = hashlib.pbkdf2_hmac("sha256", self.password.encode("utf-8"), salt, iterations)
        client_key = hmac.new(salted_pw, b"Client Key", hashlib.sha256).digest()
        stored_key = hashlib.sha256(client_key).digest()
        client_final_no_proof = f"c=biws,r={combined_nonce}"
        auth_msg = f"{client_first_bare},{server_first},{client_final_no_proof}".encode("utf-8")
        client_sig = hmac.new(stored_key, auth_msg, hashlib.sha256).digest()
        proof = bytes(a ^ b for a, b in zip(client_key, client_sig))
        client_final = f"{client_final_no_proof},p={base64.b64encode(proof).decode('ascii')}".encode("utf-8")
        self._send_msg(b"p", client_final)

        msg_type, body = self._read_msg()
        if msg_type == b"E":
            raise PgError(self._parse_error(body))
        auth_code = struct.unpack("!I", body[:4])[0]
        if auth_code == 12:
            # Next message is AuthenticationOk (0)
            msg_type, body = self._read_msg()
            if msg_type == b"E":
                raise PgError(self._parse_error(body))

    def query(self, sql: str) -> List[Dict[str, Optional[str]]]:
        sql_bytes = sql.encode("utf-8") + b"\x00"
        self._send_msg(b"Q", sql_bytes)

        columns: List[str] = []
        rows: List[Dict[str, Optional[str]]] = []
        error_msg: Optional[str] = None

        while True:
            msg_type, body = self._read_msg()
            if msg_type == b"T":
                num_fields = struct.unpack("!H", body[:2])[0]
                offset = 2
                columns = []
                for _ in range(num_fields):
                    end = body.index(b"\x00", offset)
                    col_name = body[offset:end].decode("utf-8")
                    columns.append(col_name)
                    offset = end + 1 + 18
            elif msg_type == b"D":
                num_cols = struct.unpack("!H", body[:2])[0]
                offset = 2
                row: Dict[str, Optional[str]] = {}
                for idx in range(num_cols):
                    col_len = struct.unpack("!i", body[offset : offset + 4])[0]
                    offset += 4
                    if col_len == -1:
                        row[columns[idx]] = None
                    else:
                        val = body[offset : offset + col_len].decode("utf-8", errors="replace")
                        row[columns[idx]] = val
                        offset += col_len
                rows.append(row)
            elif msg_type == b"E":
                error_msg = self._parse_error(body)
            elif msg_type == b"Z":
                break

        if error_msg is not None:
            raise PgError(error_msg)
        return rows

    def close(self) -> None:
        if self.sock is not None:
            try:
                self._send_msg(b"X", b"")
            except Exception:
                pass
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    def _send_msg(self, msg_type: bytes, payload: bytes) -> None:
        assert self.sock is not None
        header = msg_type + struct.pack("!I", len(payload) + 4)
        self.sock.sendall(header + payload)

    def _read_msg(self) -> Tuple[bytes, bytes]:
        msg_type = self._recv_exact(1)
        length = struct.unpack("!I", self._recv_exact(4))[0]
        body = self._recv_exact(length - 4) if length > 4 else b""
        return msg_type, body

    def _recv_exact(self, n: int) -> bytes:
        assert self.sock is not None
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise PgError("Unexpected EOF from PostgreSQL server")
            buf.extend(chunk)
        return bytes(buf)

    @staticmethod
    def _parse_error(body: bytes) -> str:
        parts = body.split(b"\x00")
        fields = {p[:1]: p[1:].decode("utf-8", errors="replace") for p in parts if len(p) > 1}
        return f"{fields.get(b'S', 'ERROR')}: {fields.get(b'M', 'Unknown error')} ({fields.get(b'D', '')})"


def sql_literal(val: Any) -> str:
    """Safely format a Python scalar as a PostgreSQL literal."""
    if val is None:
        return "NULL"
    if isinstance(val, bool):
        return "true" if val else "false"
    if isinstance(val, (int, float)):
        return str(val)
    if isinstance(val, bytes):
        return f"'\\x{val.hex()}'::bytea"
    s = str(val).replace("'", "''")
    return f"'{s}'"


# =============================================================================
# Pure-stdlib S3 Client (AWS Signature Version 4 + Resumable Multipart Upload)
# =============================================================================


class S3SigV4Client:
    """
    Minimal S3 client (AWS Signature v4) for MinIO (`http://127.0.0.1:9000`).
    Supports single PUT, streaming GET, and multi-part uploads with per-part retries
    for 1 GB+ drone inspection videos with constant memory footprint.
    """

    def __init__(
        self,
        endpoint_host: str,
        endpoint_port: int,
        access_key: str,
        secret_key: str,
        region: str = "us-east-1",
    ) -> None:
        self.host = endpoint_host
        self.port = endpoint_port
        self.access_key = access_key
        self.secret_key = secret_key
        self.region = region

    def _sign_headers(
        self,
        method: str,
        canonical_uri: str,
        canonical_query: str,
        payload_hash: str,
        extra_headers: Optional[Dict[str, str]] = None,
    ) -> Dict[str, str]:
        now = dt.datetime.now(dt.timezone.utc)
        amz_date = now.strftime("%Y%m%dT%H%M%SZ")
        datestamp = now.strftime("%Y%m%d")
        host_header = f"{self.host}:{self.port}"

        headers: Dict[str, str] = {
            "Host": host_header,
            "x-amz-content-sha256": payload_hash,
            "x-amz-date": amz_date,
        }
        if extra_headers:
            headers.update(extra_headers)

        sorted_lower = sorted((k.lower(), v.strip()) for k, v in headers.items())
        canonical_headers = "".join(f"{k}:{v}\n" for k, v in sorted_lower)
        signed_headers = ";".join(k for k, _ in sorted_lower)

        canonical_request = "\n".join(
            [
                method,
                canonical_uri,
                canonical_query,
                canonical_headers,
                signed_headers,
                payload_hash,
            ]
        )
        credential_scope = f"{datestamp}/{self.region}/s3/aws4_request"
        string_to_sign = "\n".join(
            [
                "AWS4-HMAC-SHA256",
                amz_date,
                credential_scope,
                hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
            ]
        )

        def _sign(key: bytes, msg: str) -> bytes:
            return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()

        k_date = _sign(("AWS4" + self.secret_key).encode("utf-8"), datestamp)
        k_region = _sign(k_date, self.region)
        k_service = _sign(k_region, "s3")
        k_signing = _sign(k_service, "aws4_request")
        signature = hmac.new(k_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()

        headers["Authorization"] = (
            f"AWS4-HMAC-SHA256 Credential={self.access_key}/{credential_scope}, "
            f"SignedHeaders={signed_headers}, Signature={signature}"
        )
        return headers

    def _request(
        self,
        method: str,
        bucket: str,
        key: str,
        query_params: Optional[Dict[str, str]] = None,
        body: bytes = b"",
        content_type: Optional[str] = None,
    ) -> Tuple[int, Dict[str, str], bytes]:
        canonical_uri = "/" + "/".join(
            urllib.parse.quote(part, safe="-_.~") for part in f"{bucket}/{key}".split("/")
        )
        qp = query_params or {}
        canonical_query = "&".join(
            f"{urllib.parse.quote(k, safe='-_.~')}={urllib.parse.quote(v, safe='-_.~')}"
            for k, v in sorted(qp.items())
        )
        payload_hash = hashlib.sha256(body).hexdigest()
        extra: Dict[str, str] = {"Content-Length": str(len(body))}
        if content_type:
            extra["Content-Type"] = content_type

        headers = self._sign_headers(method, canonical_uri, canonical_query, payload_hash, extra)
        path_with_query = canonical_uri + (f"?{canonical_query}" if canonical_query else "")

        conn = http.client.HTTPConnection(self.host, self.port, timeout=60)
        try:
            conn.request(method, path_with_query, body=body, headers=headers)
            resp = conn.getresponse()
            resp_body = resp.read()
            resp_headers = {k.lower(): v for k, v in resp.getheaders()}
            if resp.status >= 400:
                raise RuntimeError(
                    f"S3 {method} {path_with_query} failed ({resp.status}): "
                    f"{resp_body.decode('utf-8', errors='replace')}"
                )
            return resp.status, resp_headers, resp_body
        finally:
            conn.close()

    def upload_file(
        self,
        bucket: str,
        key: str,
        file_path: Path,
        content_type: str,
        on_progress: Optional[Callable[[int, Optional[str]], None]] = None,
    ) -> Tuple[str, int]:
        """
        Upload `file_path` to S3 `bucket/key`.
        Uses single PUT for small files and S3 Multipart Upload with per-part retry
        for files > 16 MB, keeping memory usage constant even for 1 GB+ videos.
        Returns `(stored_sha256, size_bytes)`.
        """
        file_size = file_path.stat().st_size
        if file_size <= S3_MULTIPART_THRESHOLD:
            data = file_path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            self._request("PUT", bucket, key, body=data, content_type=content_type)
            if on_progress:
                on_progress(len(data), None)
            return digest, len(data)

        # Initiate S3 Multipart Upload
        _, _, init_xml = self._request(
            "POST", bucket, key, query_params={"uploads": ""}, content_type=content_type
        )
        root = ET.fromstring(init_xml)
        upload_id = ""
        for elem in root.iter():
            if elem.tag.endswith("UploadId") and elem.text:
                upload_id = elem.text
                break
        if not upload_id:
            raise RuntimeError(f"Failed to parse UploadId from S3 response: {init_xml!r}")

        if on_progress:
            on_progress(0, upload_id)

        parts_etags: List[Tuple[int, str]] = []
        hasher = hashlib.sha256()
        bytes_sent = 0

        try:
            with file_path.open("rb") as f:
                part_num = 1
                while True:
                    chunk = f.read(S3_PART_SIZE)
                    if not chunk:
                        break
                    hasher.update(chunk)

                    # Retry individual part up to 3 times (resilience over degraded links)
                    last_err: Optional[Exception] = None
                    etag = ""
                    for attempt in range(1, 4):
                        try:
                            _, r_headers, _ = self._request(
                                "PUT",
                                bucket,
                                key,
                                query_params={"partNumber": str(part_num), "uploadId": upload_id},
                                body=chunk,
                            )
                            etag = r_headers.get("etag", "")
                            break
                        except Exception as exc:
                            last_err = exc
                            time.sleep(0.5 * attempt)
                    if not etag and last_err is not None:
                        raise last_err

                    parts_etags.append((part_num, etag))
                    bytes_sent += len(chunk)
                    if on_progress:
                        on_progress(bytes_sent, upload_id)
                    part_num += 1

            # Complete Multipart Upload
            parts_xml = "".join(
                f"<Part><PartNumber>{pnum}</PartNumber><ETag>{etag}</ETag></Part>"
                for pnum, etag in parts_etags
            )
            complete_body = f"<CompleteMultipartUpload>{parts_xml}</CompleteMultipartUpload>".encode("utf-8")
            self._request(
                "POST",
                bucket,
                key,
                query_params={"uploadId": upload_id},
                body=complete_body,
                content_type="application/xml",
            )
            return hasher.hexdigest(), bytes_sent
        except Exception:
            try:
                self._request("DELETE", bucket, key, query_params={"uploadId": upload_id})
            except Exception:
                pass
            raise

    def stream_verify(self, bucket: str, key: str) -> Tuple[str, int]:
        """Stream an S3 object and compute its `(sha256, size_bytes)` in O(1) memory."""
        canonical_uri = "/" + "/".join(
            urllib.parse.quote(part, safe="-_.~") for part in f"{bucket}/{key}".split("/")
        )
        payload_hash = hashlib.sha256(b"").hexdigest()
        headers = self._sign_headers("GET", canonical_uri, "", payload_hash)

        conn = http.client.HTTPConnection(self.host, self.port, timeout=60)
        try:
            conn.request("GET", canonical_uri, headers=headers)
            resp = conn.getresponse()
            if resp.status >= 400:
                err_body = resp.read().decode("utf-8", errors="replace")
                raise RuntimeError(f"S3 GET {canonical_uri} failed ({resp.status}): {err_body}")
            h = hashlib.sha256()
            total = 0
            while True:
                buf = resp.read(STREAM_CHUNK_SIZE)
                if not buf:
                    break
                h.update(buf)
                total += len(buf)
            return h.hexdigest(), total
        finally:
            conn.close()


# =============================================================================
# FTP Storage Client (vsftpd passive mode)
# =============================================================================


class FtpStorageClient:
    """Passive-mode FTP client for vsftpd (`127.0.0.1:2121`)."""

    def __init__(self, host: str, port: int, user: str, password: str) -> None:
        self.host = host
        self.port = port
        self.user = user
        self.password = password

    def _connect(self) -> ftplib.FTP:
        ftp = ftplib.FTP()
        ftp.connect(self.host, self.port, timeout=30)
        ftp.login(self.user, self.password)
        ftp.set_pasv(True)
        return ftp

    @staticmethod
    def _mkdir_p(ftp: ftplib.FTP, remote_dir: str) -> None:
        parts = [p for p in remote_dir.strip("/").split("/") if p]
        curr = ""
        for p in parts:
            curr = f"{curr}/{p}"
            try:
                ftp.mkd(curr)
            except ftplib.error_perm:
                pass

    def upload_file(self, remote_path: str, file_path: Path) -> Tuple[str, int]:
        """Stream upload `file_path` to FTP `remote_path` and return `(sha256, size_bytes)`."""
        digest, size_bytes = sha256_file(file_path)
        parent_dir = "/" + "/".join(remote_path.strip("/").split("/")[:-1])
        ftp = self._connect()
        try:
            self._mkdir_p(ftp, parent_dir)
            with file_path.open("rb") as f:
                ftp.storbinary(f"STOR {remote_path}", f, blocksize=256 * 1024)
            return digest, size_bytes
        finally:
            try:
                ftp.quit()
            except Exception:
                ftp.close()

    def stream_verify(self, remote_path: str) -> Tuple[str, int]:
        """Stream download FTP `remote_path` and return `(sha256, size_bytes)` in O(1) memory."""
        ftp = self._connect()
        try:
            h = hashlib.sha256()
            total = 0

            def _cb(chunk: bytes) -> None:
                nonlocal total
                h.update(chunk)
                total += len(chunk)

            ftp.retrbinary(f"RETR {remote_path}", _cb, blocksize=256 * 1024)
            return h.hexdigest(), total
        finally:
            try:
                ftp.quit()
            except Exception:
                ftp.close()


# =============================================================================
# High-Level FileGrid Acquisition & Verification Coordinator
# =============================================================================


class FileGridAcquisitionService:
    """
    Coordinates policy-driven ingestion and verification of Fault Records (`FAULT_RECORD`)
    and Drone Inspection Videos (`DRONE_VIDEO`) against the `make dev` stack.
    """

    def __init__(self, env: Optional[Dict[str, str]] = None) -> None:
        self.env = env or load_env()
        self.pg_host = self.env.get("POSTGRES_HOST", "127.0.0.1")
        self.pg_port = int(self.env.get("POSTGRES_HOST_PORT", "5432"))
        self.pg_db = self.env.get("POSTGRES_DB", "filegrid")
        self.pg_user = self.env.get("FILEGRID_DB_APP_USER", "filegrid_app")
        self.pg_password = self.env.get("FILEGRID_DB_APP_PASSWORD", "")

        self.s3 = S3SigV4Client(
            endpoint_host=self.env.get("MINIO_HOST", "127.0.0.1"),
            endpoint_port=int(self.env.get("MINIO_HOST_PORT", "9000")),
            access_key=self.env.get("FILEGRID_S3_ACCESS_KEY", "filegrid-app"),
            secret_key=self.env.get("FILEGRID_S3_SECRET_KEY", ""),
        )
        self.ftp = FtpStorageClient(
            host=self.env.get("FTP_HOST", "127.0.0.1"),
            port=int(self.env.get("FTP_HOST_PORT", "2121")),
            user=self.env.get("FTP_USER", "filegrid"),
            password=self.env.get("FTP_PASSWORD", ""),
        )

    def _pg(self) -> PgWireClient:
        if not self.pg_password:
            raise RuntimeError(
                "Missing FILEGRID_DB_APP_PASSWORD in .env. Run `make dev` first."
            )
        return PgWireClient(
            host=self.pg_host,
            port=self.pg_port,
            user=self.pg_user,
            password=self.pg_password,
            database=self.pg_db,
        )

    def get_storage_policy(self, pg: PgWireClient, category: str, region: str) -> Dict[str, Any]:
        rows = pg.query(
            f"SELECT category, region, backend, target, encrypt_at_rest, max_size_bytes "
            f"FROM storage_policy "
            f"WHERE category = {sql_literal(category)} AND region = {sql_literal(region)};"
        )
        if not rows:
            raise RuntimeError(f"No storage_policy found for ({category}, {region})")
        r = rows[0]
        return {
            "category": r["category"],
            "region": r["region"],
            "backend": r["backend"],
            "target": r["target"],
            "encrypt_at_rest": r["encrypt_at_rest"] in ("t", "true", "TRUE", "1"),
            "max_size_bytes": int(r["max_size_bytes"] or 0),
        }

    def ingest_entry(self, data_dir: Path, meta_entry: Dict[str, Any]) -> Dict[str, Any]:
        """
        Ingest a single generated item (described by its `.meta.json` entry) into
        the storage backend configured in `filegrid.storage_policy` for `(category, region)`.
        Idempotent: if `stored_file.id` already exists with status `STORED`, skips re-upload.
        """
        category = meta_entry["category"]
        region = meta_entry["region"]
        file_info = meta_entry["file"]
        file_id = file_info["id"]
        file_path = data_dir / file_info["relative_path"]
        original_filename = file_info["original_filename"]
        content_type = file_info["content_type"]
        uploaded_by = file_info["uploaded_by"]
        trace_id = secrets.token_hex(16)

        if not file_path.exists():
            raise FileNotFoundError(f"Data file not found: {file_path}")

        actual_sha256, actual_size = sha256_file(file_path)

        with self._pg() as pg:
            existing = pg.query(
                f"SELECT id, status, backend, location FROM stored_file "
                f"WHERE id = {sql_literal(file_id)} AND region = {sql_literal(region)};"
            )
            if existing and existing[0]["status"] == "STORED":
                return {
                    "file_id": file_id,
                    "category": category,
                    "region": region,
                    "backend": existing[0]["backend"],
                    "location": existing[0]["location"],
                    "size_bytes": actual_size,
                    "sha256": actual_sha256,
                    "status": "ALREADY_STORED",
                }

            policy = self.get_storage_policy(pg, category, region)
            if actual_size > policy["max_size_bytes"]:
                raise ValueError(
                    f"File {original_filename} ({actual_size} B) exceeds policy max_size_bytes "
                    f"({policy['max_size_bytes']} B) for ({category}, {region})"
                )

            backend = policy["backend"]
            target = policy["target"]
            date_prefix = dt.datetime.now(dt.timezone.utc).strftime("%Y/%m")

            if backend == "FTP":
                location = f"{target.rstrip('/')}/{region}/{date_prefix}/{file_id}_{original_filename}"
            elif backend == "S3":
                object_key = f"{region}/{date_prefix}/{file_id}_{original_filename}"
                location = f"{target}/{object_key}"
            else:
                location = None

            # 1. Insert initial UPLOADING row in stored_file
            pg.query(
                f"INSERT INTO stored_file ("
                f"id, region, category, backend, location, original_filename, content_type, "
                f"size_bytes, sha256, encryption, status, bytes_received, uploaded_by, trace_id"
                f") VALUES ("
                f"{sql_literal(file_id)}, {sql_literal(region)}, {sql_literal(category)}, "
                f"{sql_literal(backend)}, {sql_literal(location)}, {sql_literal(original_filename)}, "
                f"{sql_literal(content_type)}, {sql_literal(actual_size)}, {sql_literal(actual_sha256)}, "
                f"'NONE', 'UPLOADING', 0, {sql_literal(uploaded_by)}, {sql_literal(trace_id)}"
                f") ON CONFLICT (id) DO UPDATE SET status = 'UPLOADING', updated_at = now();"
            )

            # 2. Stream to configured storage backend
            try:
                if backend == "FTP":
                    assert location is not None
                    stored_sha256, stored_size = self.ftp.upload_file(location, file_path)
                elif backend == "S3":
                    assert target is not None
                    object_key = f"{region}/{date_prefix}/{file_id}_{original_filename}"

                    def _progress(bytes_rcvd: int, upload_id: Optional[str]) -> None:
                        pg.query(
                            f"UPDATE stored_file SET bytes_received = {sql_literal(bytes_rcvd)}, "
                            f"upload_id = {sql_literal(upload_id)} "
                            f"WHERE id = {sql_literal(file_id)} AND region = {sql_literal(region)};"
                        )

                    stored_sha256, stored_size = self.s3.upload_file(
                        bucket=target,
                        key=object_key,
                        file_path=file_path,
                        content_type=content_type,
                        on_progress=_progress,
                    )
                elif backend == "DATABASE":
                    raw_bytes = file_path.read_bytes()
                    stored_sha256 = hashlib.sha256(raw_bytes).hexdigest()
                    stored_size = len(raw_bytes)
                    pg.query(
                        f"INSERT INTO stored_file_blob (file_id, region, content) VALUES ("
                        f"{sql_literal(file_id)}, {sql_literal(region)}, {sql_literal(raw_bytes)}"
                        f") ON CONFLICT (file_id) DO UPDATE SET content = EXCLUDED.content;"
                    )
                else:
                    raise RuntimeError(f"Unsupported storage backend: {backend}")
            except Exception as exc:
                pg.query(
                    f"UPDATE stored_file SET status = 'FAILED' "
                    f"WHERE id = {sql_literal(file_id)} AND region = {sql_literal(region)};"
                )
                raise RuntimeError(f"Failed uploading {original_filename} to {backend}: {exc}") from exc

            # 3. Mark stored_file as STORED and insert domain record + audit_log
            pg.query(
                f"UPDATE stored_file SET "
                f"status = 'STORED', "
                f"size_bytes = {sql_literal(stored_size)}, "
                f"bytes_received = {sql_literal(stored_size)}, "
                f"sha256 = {sql_literal(actual_sha256)}, "
                f"stored_sha256 = {sql_literal(stored_sha256)}, "
                f"upload_id = NULL, "
                f"stored_at = now(), "
                f"last_verified_at = now() "
                f"WHERE id = {sql_literal(file_id)} AND region = {sql_literal(region)};"
            )

            if category == "FAULT_RECORD":
                fr = meta_entry["fault_record"]
                pg.query(
                    f"INSERT INTO fault_record ("
                    f"id, region, county, locality, substation_code, occurred_at, restored_at, "
                    f"severity, cause, affected_customers, description, file_id, reported_by"
                    f") VALUES ("
                    f"{sql_literal(fr['id'])}, {sql_literal(region)}, {sql_literal(fr['county'])}, "
                    f"{sql_literal(fr['locality'])}, {sql_literal(fr['substation_code'])}, "
                    f"{sql_literal(fr['occurred_at'])}, {sql_literal(fr['restored_at'])}, "
                    f"{sql_literal(fr['severity'])}, {sql_literal(fr['cause'])}, "
                    f"{sql_literal(fr['affected_customers'])}, {sql_literal(fr['description'])}, "
                    f"{sql_literal(file_id)}, {sql_literal(fr['reported_by'])}"
                    f") ON CONFLICT (id) DO NOTHING;"
                )
                entity_id = fr["id"]
                action = "FAULT_RECORD_UPLOADED"
            elif category == "DRONE_VIDEO":
                dv = meta_entry["drone_video"]
                pg.query(
                    f"INSERT INTO drone_video ("
                    f"id, region, county, line_code, drone_id, flight_started_at, "
                    f"duration_seconds, notes, file_id, uploaded_by"
                    f") VALUES ("
                    f"{sql_literal(dv['id'])}, {sql_literal(region)}, {sql_literal(dv['county'])}, "
                    f"{sql_literal(dv['line_code'])}, {sql_literal(dv['drone_id'])}, "
                    f"{sql_literal(dv['flight_started_at'])}, {sql_literal(dv['duration_seconds'])}, "
                    f"{sql_literal(dv['notes'])}, {sql_literal(file_id)}, {sql_literal(dv['uploaded_by'])}"
                    f") ON CONFLICT (id) DO NOTHING;"
                )
                entity_id = dv["id"]
                action = "DRONE_VIDEO_UPLOADED"
            else:
                entity_id = file_id
                action = "FILE_UPLOADED"

            details_json = json.dumps(
                {
                    "file_id": file_id,
                    "original_filename": original_filename,
                    "backend": backend,
                    "location": location,
                    "size_bytes": stored_size,
                    "sha256": actual_sha256,
                }
            )
            pg.query(
                f"INSERT INTO audit_log (region, actor, action, entity_type, entity_id, trace_id, details) "
                f"VALUES ({sql_literal(region)}, {sql_literal(uploaded_by)}, {sql_literal(action)}, "
                f"{sql_literal(category)}, {sql_literal(entity_id)}, {sql_literal(trace_id)}, "
                f"{sql_literal(details_json)}::jsonb);"
            )

            return {
                "file_id": file_id,
                "category": category,
                "region": region,
                "backend": backend,
                "location": location,
                "size_bytes": stored_size,
                "sha256": actual_sha256,
                "trace_id": trace_id,
                "status": "STORED",
            }

    def verify_all_stored(self, region_filter: Optional[str] = None) -> List[Dict[str, Any]]:
        """
        Fetch all `FAULT_RECORD` and `DRONE_VIDEO` rows from `stored_file`, stream each
        object back from its physical storage (`FTP`, `S3`, or `DATABASE`), recompute
        SHA-256, and verify bit-for-bit integrity (PDF §3 & §4 Resilience).
        """
        where_clauses = ["category IN ('FAULT_RECORD', 'DRONE_VIDEO')", "status = 'STORED'"]
        if region_filter:
            where_clauses.append(f"region = {sql_literal(region_filter.upper())}")
        where_sql = " AND ".join(where_clauses)

        results: List[Dict[str, Any]] = []
        with self._pg() as pg:
            rows = pg.query(
                f"SELECT id, region, category, backend, location, original_filename, "
                f"size_bytes, sha256, stored_sha256 "
                f"FROM stored_file WHERE {where_sql} ORDER BY region, category, created_at;"
            )
            for row in rows:
                file_id = row["id"] or ""
                region = row["region"] or ""
                category = row["category"] or ""
                backend = row["backend"] or ""
                location = row["location"]
                expected_sha = row["stored_sha256"] or row["sha256"] or ""
                expected_size = int(row["size_bytes"] or 0)

                try:
                    if backend == "FTP":
                        assert location is not None
                        actual_sha, actual_size = self.ftp.stream_verify(location)
                    elif backend == "S3":
                        assert location is not None
                        bucket, key = location.split("/", 1)
                        actual_sha, actual_size = self.s3.stream_verify(bucket, key)
                    elif backend == "DATABASE":
                        blob_rows = pg.query(
                            f"SELECT encode(sha256(content), 'hex') AS sha, octet_length(content) AS sz "
                            f"FROM stored_file_blob WHERE file_id = {sql_literal(file_id)};"
                        )
                        if not blob_rows:
                            raise RuntimeError("Missing row in stored_file_blob")
                        actual_sha = blob_rows[0]["sha"] or ""
                        actual_size = int(blob_rows[0]["sz"] or 0)
                    else:
                        raise RuntimeError(f"Unknown backend {backend}")

                    verified = (actual_sha == expected_sha) and (actual_size == expected_size)
                    if verified:
                        pg.query(
                            f"UPDATE stored_file SET last_verified_at = now() "
                            f"WHERE id = {sql_literal(file_id)} AND region = {sql_literal(region)};"
                        )
                    else:
                        pg.query(
                            f"UPDATE stored_file SET status = 'CORRUPTED' "
                            f"WHERE id = {sql_literal(file_id)} AND region = {sql_literal(region)};"
                        )
                    results.append(
                        {
                            "file_id": file_id,
                            "region": region,
                            "category": category,
                            "backend": backend,
                            "original_filename": row["original_filename"],
                            "size_bytes": actual_size,
                            "expected_sha256": expected_sha,
                            "actual_sha256": actual_sha,
                            "verified": verified,
                        }
                    )
                except Exception as exc:
                    results.append(
                        {
                            "file_id": file_id,
                            "region": region,
                            "category": category,
                            "backend": backend,
                            "original_filename": row["original_filename"],
                            "verified": False,
                            "error": str(exc),
                        }
                    )
        return results
