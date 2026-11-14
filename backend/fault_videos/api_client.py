"""
FileGrid API client for the test-data tool (standard library only).

FileGrid is the only entry point for field data, so this module never talks to
PostgreSQL, FTP or S3: it logs in to Keycloak and uploads / downloads through
the HTTP API. Storage policy, encryption, checksums, metadata rows and the
audit trail are all the backend's job.

  - fault records:  POST /api/v1/fault-records           (multipart, streamed from disk)
  - drone videos:   POST /api/v1/drone-videos/uploads     (resumable, ordered parts)
  - verification:   GET  /api/v1/<category>/{id}/file     (streamed, SHA-256 compared)

Everything is streamed with constant memory, retried with backoff on network
errors / 408 / 429 / 5xx, and every request carries a fresh bearer token.
"""

from __future__ import annotations

import datetime as dt
import hashlib
import http.client
import json
import os
import random
import ssl
import sys
import threading
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
IO_CHUNK = 1024 * 1024  # 1 MB: the most this module ever holds of a file in memory

FAULT_RECORD = "FAULT_RECORD"
DRONE_VIDEO = "DRONE_VIDEO"
RESOURCE = {FAULT_RECORD: "fault-records", DRONE_VIDEO: "drone-videos"}

RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}
# 400 answers to a part that only mean "send this part again".
RESEND_PART_CODES = {"part_checksum_mismatch", "part_interrupted", "invalid_part_size"}
# Keys this tool adds to a file's .meta.json to remember what it uploaded.
UPLOADED_KEY = "filegrid"          # {"api", "id", "fileId", "sha256", "uploadedAt"}
IN_PROGRESS_KEY = "filegridUpload"  # {"api", "uploadId", "partSizeBytes", "startedAt"}

_print_lock = threading.Lock()


def log(message: str) -> None:
    with _print_lock:
        print(message, flush=True)


def load_env(env_path: Optional[Path] = None) -> Dict[str, str]:
    """Variables from the process environment, completed with the repo's `.env`."""
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


class ApiError(RuntimeError):
    """A final (non-retryable) answer from the API: an RFC 9457 problem document."""

    def __init__(self, status: int, code: Optional[str], detail: str, url: str):
        super().__init__(f"HTTP {status}{f' [{code}]' if code else ''}: {detail} ({url})")
        self.status = status
        self.code = code
        self.detail = detail


class Unavailable(RuntimeError):
    """The API could not be reached, or kept failing, after all retries."""


class Settings:
    """
    Where FileGrid is and who uploads. Environment / `.env`:

      FILEGRID_PUBLIC_URL   public entry point (default https://localhost:8443):
                            API at <url>/api/v1, Keycloak at <url>/auth
      FILEGRID_API_URL      backend base URL, overrides the above (e.g. http://localhost:8080)
      FILEGRID_AUTH_URL     Keycloak base URL, overrides the above (e.g. http://localhost:8180)
      FILEGRID_CA_FILE      CA bundle for the proxy's certificate
      FILEGRID_TLS_INSECURE 1 = do not verify TLS certificates (local self-signed proxy only)
      FILEGRID_UPLOAD_USER / FILEGRID_UPLOAD_PASSWORD
                            account used for uploads; default employee.demo@filegrid.local
                            with FILEGRID_DEMO_EMPLOYEE_PASSWORD
    """

    def __init__(self, env: Optional[Dict[str, str]] = None):
        env = env if env is not None else load_env()
        public = env.get("FILEGRID_PUBLIC_URL", "https://localhost:8443").rstrip("/")
        self.api_base = env.get("FILEGRID_API_URL", public).rstrip("/") + "/api/v1"
        self.auth_base = env.get("FILEGRID_AUTH_URL", public + "/auth").rstrip("/")
        self.realm = env.get("FILEGRID_REALM", "filegrid")
        self.client_id = env.get("FILEGRID_CLI_CLIENT_ID", "filegrid-cli")
        self.username = env.get("FILEGRID_UPLOAD_USER", "employee.demo@filegrid.local")
        self.password = env.get("FILEGRID_UPLOAD_PASSWORD") or env.get("FILEGRID_DEMO_EMPLOYEE_PASSWORD", "")
        self.ca_file = env.get("FILEGRID_CA_FILE") or None
        self.tls_insecure = env.get("FILEGRID_TLS_INSECURE", "").lower() in ("1", "true", "yes")
        self.timeout = float(env.get("FILEGRID_HTTP_TIMEOUT", "60"))
        self.max_attempts = int(env.get("FILEGRID_HTTP_ATTEMPTS", "6"))

    @property
    def token_url(self) -> str:
        return f"{self.auth_base}/realms/{self.realm}/protocol/openid-connect/token"


class _Transient(Exception):
    def __init__(self, reason: str, retry_after: Optional[float] = None):
        super().__init__(reason)
        self.retry_after = retry_after


class FileGridApi:
    """Authenticated, retrying HTTP access to the FileGrid API. Thread-safe."""

    def __init__(self, settings: Optional[Settings] = None):
        self.settings = settings or Settings()
        self.retries = 0  # transient failures that were retried (for the summary)
        self._lock = threading.Lock()
        self._access_token: Optional[str] = None
        self._access_expires = 0.0
        self._refresh_token: Optional[str] = None
        self._refresh_expires = 0.0
        self._ssl: Optional[ssl.SSLContext] = None
        s = self.settings
        if s.api_base.startswith("https") or s.auth_base.startswith("https"):
            if s.tls_insecure:
                self._ssl = ssl.create_default_context()
                self._ssl.check_hostname = False
                self._ssl.verify_mode = ssl.CERT_NONE
                print("WARNING: FILEGRID_TLS_INSECURE=1 - TLS certificates are NOT verified.", file=sys.stderr)
            else:
                self._ssl = ssl.create_default_context(cafile=s.ca_file)

    # ------------------------------------------------------------------ transport

    def _exchange(self, method: str, url: str, headers: Dict[str, str],
                  body: Optional[Callable[[], Iterator[bytes]]], length: Optional[int]
                  ) -> Tuple[http.client.HTTPConnection, http.client.HTTPResponse]:
        """One HTTP exchange; the body is streamed. The caller reads the response and closes the connection."""
        u = urllib.parse.urlsplit(url)
        if u.scheme == "https":
            conn: http.client.HTTPConnection = http.client.HTTPSConnection(
                u.hostname, u.port or 443, timeout=self.settings.timeout, context=self._ssl)
        else:
            conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=self.settings.timeout)
        try:
            conn.putrequest(method, (u.path or "/") + (f"?{u.query}" if u.query else ""))
            for k, v in headers.items():
                conn.putheader(k, v)
            if length is not None:
                conn.putheader("Content-Length", str(length))
            conn.endheaders()
            if body is not None:
                try:
                    for chunk in body():
                        conn.send(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    # The server answered before reading everything (e.g. 409/413): read that answer.
                    pass
            return conn, conn.getresponse()
        except BaseException:
            conn.close()
            raise

    def _backoff(self, attempt: int, retry_after: Optional[float]) -> None:
        delay = min(30.0, 0.5 * (2 ** (attempt - 1)))
        delay = delay / 2 + random.uniform(0, delay / 2)
        if retry_after is not None:
            delay = max(delay, min(retry_after, 60.0))
        time.sleep(delay)

    @staticmethod
    def _problem(data: bytes) -> Tuple[Optional[str], str]:
        try:
            doc = json.loads(data.decode("utf-8"))
            return doc.get("code"), str(doc.get("detail") or doc.get("title") or doc)
        except (ValueError, AttributeError):
            return None, data[:300].decode("utf-8", "replace")

    def request(self, method: str, path: str, *, headers: Optional[Dict[str, str]] = None,
                json_body: Any = None, body: Optional[Callable[[], Iterator[bytes]]] = None,
                length: Optional[int] = None, sink: Optional[Callable[[bytes], None]] = None,
                on_restart: Optional[Callable[[], None]] = None
                ) -> Tuple[int, Dict[str, str], bytes]:
        """
        Calls the API with a bearer token. Retries transient failures with backoff;
        a 401 triggers one fresh login. With `sink`, a successful response body is
        streamed to it (`on_restart` is called before a retry so the sink can reset).
        Raises ApiError for a final 4xx/5xx answer, Unavailable when retries run out.
        """
        url = path if path.startswith("http") else self.settings.api_base + path
        hdrs = {"Accept": "application/json, application/problem+json, */*",
                "User-Agent": "filegrid-fault-videos/2.0"}
        hdrs.update(headers or {})
        if json_body is not None:
            payload = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
            body, length = (lambda: iter((payload,))), len(payload)
        if body is None and method in ("POST", "PUT"):
            length = 0

        relogged = False
        attempt = 0
        last = "no attempt made"
        while attempt < self.settings.max_attempts:
            attempt += 1
            try:
                hdrs["Authorization"] = "Bearer " + self._token()
                conn, resp = self._exchange(method, url, hdrs, body, length)
                try:
                    status = resp.status
                    rh = {k.lower(): v for k, v in resp.getheaders()}
                    if status == 401 and not relogged:
                        resp.read()
                        relogged = True
                        attempt -= 1
                        self._forget_token()
                        continue
                    if status < 300 and sink is not None:
                        while True:
                            chunk = resp.read(IO_CHUNK)
                            if not chunk:
                                break
                            sink(chunk)
                        return status, rh, b""
                    data = resp.read()
                finally:
                    conn.close()
                if status < 300:
                    return status, rh, data
                code, detail = self._problem(data)
                # A detected corruption is the server's final word, not an outage.
                if status in RETRYABLE_STATUS and code != "integrity_check_failed":
                    retry_after = None
                    try:
                        retry_after = float(rh.get("retry-after", ""))
                    except ValueError:
                        pass
                    raise _Transient(f"HTTP {status}: {detail}", retry_after)
                raise ApiError(status, code, detail, url)
            except (_Transient, OSError, http.client.HTTPException) as e:
                last = f"{type(e).__name__}: {e}"
                if attempt >= self.settings.max_attempts:
                    break
                with self._lock:
                    self.retries += 1
                if on_restart is not None:
                    on_restart()
                self._backoff(attempt, getattr(e, "retry_after", None))
        raise Unavailable(f"{method} {url} failed after {attempt} attempts ({last})")

    def get_json(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        if params:
            path += "?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        _, _, data = self.request("GET", path)
        return json.loads(data.decode("utf-8")) if data else None

    # ------------------------------------------------------------------ authentication

    def login(self) -> None:
        """Fails fast (Unavailable) when Keycloak is down or the credentials are wrong."""
        self._token()

    def _forget_token(self) -> None:
        with self._lock:
            self._access_token = None

    def _token(self) -> str:
        """Access token valid for at least 30 more seconds (they live 5 minutes)."""
        with self._lock:
            now = time.monotonic()
            if self._access_token and now < self._access_expires - 30:
                return self._access_token
            grants: List[Dict[str, str]] = []
            if self._refresh_token and now < self._refresh_expires - 10:
                grants.append({"grant_type": "refresh_token", "refresh_token": self._refresh_token})
            grants.append({"grant_type": "password", "username": self.settings.username,
                           "password": self.settings.password})
            error = ""
            for grant in grants:
                status, doc = self._token_request(dict(grant, client_id=self.settings.client_id))
                if status == 200 and "access_token" in doc:
                    now = time.monotonic()
                    self._access_token = doc["access_token"]
                    self._access_expires = now + float(doc.get("expires_in", 60))
                    self._refresh_token = doc.get("refresh_token")
                    self._refresh_expires = now + float(doc.get("refresh_expires_in", 0))
                    return self._access_token
                error = f"HTTP {status}: {doc.get('error_description') or doc.get('error') or doc}"
            raise Unavailable(
                f"Login failed for {self.settings.username} at {self.settings.token_url} ({error}). "
                "Check FILEGRID_DEMO_EMPLOYEE_PASSWORD in .env and that the stack is up (`make up`); "
                "the realm users are imported only when Keycloak starts on an empty database.")

    def _token_request(self, form: Dict[str, str]) -> Tuple[int, Dict[str, Any]]:
        if not self.settings.password and form.get("grant_type") == "password":
            raise Unavailable("No upload password: set FILEGRID_DEMO_EMPLOYEE_PASSWORD (run `make env`).")
        payload = urllib.parse.urlencode(form).encode("ascii")
        headers = {"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"}
        last = ""
        for attempt in range(1, self.settings.max_attempts + 1):
            try:
                conn, resp = self._exchange("POST", self.settings.token_url, headers,
                                            lambda: iter((payload,)), len(payload))
                try:
                    status, data = resp.status, resp.read()
                finally:
                    conn.close()
                if status in RETRYABLE_STATUS:
                    raise _Transient(f"HTTP {status}")
                try:
                    return status, json.loads(data.decode("utf-8"))
                except ValueError:
                    return status, {"error": data[:200].decode("utf-8", "replace")}
            except (_Transient, OSError, http.client.HTTPException) as e:
                last = f"{type(e).__name__}: {e}"
                if attempt < self.settings.max_attempts:
                    self._backoff(attempt, None)
        raise Unavailable(f"Keycloak not reachable at {self.settings.token_url} ({last})")


# =============================================================================
# Local bookkeeping: what has been uploaded lives next to each generated file
# =============================================================================


def meta_path_of(data_dir: Path, entry: Dict[str, Any]) -> Path:
    return (data_dir / entry["file"]["relative_path"]).with_suffix(".meta.json")


def _update_meta(meta_path: Path, changes: Dict[str, Any]) -> None:
    """Sets (or, for None, removes) top-level keys of a .meta.json, atomically."""
    with meta_path.open("r", encoding="utf-8") as f:
        doc = json.load(f)
    for k, v in changes.items():
        if v is None:
            doc.pop(k, None)
        else:
            doc[k] = v
    tmp = meta_path.with_name(meta_path.name + f".{uuid.uuid4().hex[:8]}.tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    os.replace(tmp, meta_path)


def uploaded_record(entry: Dict[str, Any], api_base: str) -> Optional[Dict[str, Any]]:
    """The upload record of an entry, if it was uploaded to this FileGrid instance."""
    rec = entry.get(UPLOADED_KEY)
    return rec if rec and rec.get("api") == api_base and rec.get("id") else None


def _utc(value: Optional[str]) -> Optional[str]:
    """Normalises the generator's ISO timestamps to UTC with a `Z` suffix."""
    if not value:
        return None
    parsed = dt.datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _file_chunks(path: Path, offset: int = 0, length: Optional[int] = None) -> Iterator[bytes]:
    remaining = length
    with path.open("rb") as f:
        f.seek(offset)
        while remaining is None or remaining > 0:
            buf = f.read(IO_CHUNK if remaining is None else min(IO_CHUNK, remaining))
            if not buf:
                break
            if remaining is not None:
                remaining -= len(buf)
            yield buf


# =============================================================================
# Uploads
# =============================================================================


def upload_fault_record(api: FileGridApi, data_dir: Path, entry: Dict[str, Any]) -> Dict[str, Any]:
    """POST /fault-records: JSON part `metadata` + part `file`, streamed from disk."""
    file_info = entry["file"]
    fr = entry["fault_record"]
    path = data_dir / file_info["relative_path"]
    size = path.stat().st_size
    metadata = {
        "region": entry["region"],
        "county": fr["county"],
        "locality": fr.get("locality"),
        "substationCode": fr["substation_code"],
        "occurredAt": _utc(fr["occurred_at"]),
        "restoredAt": _utc(fr.get("restored_at")),
        "severity": fr["severity"],
        "cause": fr.get("cause"),
        "affectedCustomers": fr.get("affected_customers"),
        "description": fr.get("description"),
    }
    metadata = {k: v for k, v in metadata.items() if v is not None}

    boundary = "filegrid-" + uuid.uuid4().hex
    filename = file_info["original_filename"].replace('"', "")
    head = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
        f"Content-Type: application/json\r\n\r\n"
    ).encode("utf-8") + json.dumps(metadata, ensure_ascii=False).encode("utf-8") + (
        f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        f'Content-Type: {file_info.get("content_type", "application/json")}\r\n\r\n'
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("ascii")

    def body() -> Iterator[bytes]:
        yield head
        yield from _file_chunks(path)
        yield tail

    _, _, data = api.request(
        "POST", "/" + RESOURCE[FAULT_RECORD],
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 # Lets a server that supports it recognise a retried upload.
                 "Idempotency-Key": file_info["id"]},
        body=body, length=len(head) + size + len(tail))
    created = json.loads(data.decode("utf-8"))
    if created.get("sha256") and created["sha256"] != file_info["sha256"]:
        raise RuntimeError(f"server stored sha256 {created['sha256']}, local file is {file_info['sha256']}")
    return created


def upload_drone_video(api: FileGridApi, data_dir: Path, entry: Dict[str, Any],
                       progress: bool = True) -> Tuple[Dict[str, Any], bool]:
    """
    Resumable upload in ordered parts. The upload id is saved in the .meta.json
    before the first byte is sent, so a later run asks the server what it already
    has and continues from there. Returns (created, resumed).
    """
    file_info = entry["file"]
    dv = entry["drone_video"]
    path = data_dir / file_info["relative_path"]
    meta_path = meta_path_of(data_dir, entry)
    size = path.stat().st_size
    base = "/" + RESOURCE[DRONE_VIDEO] + "/uploads"

    upload_id: Optional[str] = None
    part_size = 0
    received = 0  # parts the server already has (contiguous from 1)
    resumed = False
    pending = entry.get(IN_PROGRESS_KEY)
    if pending and pending.get("api") == api.settings.api_base and pending.get("uploadId"):
        try:
            state = api.get_json(f"{base}/{pending['uploadId']}")
            # STORED = every part is there; `complete` is idempotent and returns the ids again.
            if str(state.get("status", "")).upper() in ("UPLOADING", "STORED"):
                upload_id = pending["uploadId"]
                part_size = int(state.get("partSizeBytes") or pending["partSizeBytes"])
                received = _received_parts(state, part_size)
                resumed = True
        except ApiError as e:
            if e.status not in (404, 410):
                raise

    if upload_id is None:
        init = {
            "region": entry["region"],
            "county": dv["county"],
            "lineCode": dv["line_code"],
            "droneId": dv.get("drone_id"),
            "flightStartedAt": _utc(dv["flight_started_at"]),
            "durationSeconds": dv.get("duration_seconds"),
            "notes": dv.get("notes"),
            "filename": file_info["original_filename"],
            "contentType": file_info.get("content_type", "video/mp4"),
            "sizeBytes": size,
            "sha256": file_info["sha256"],
        }
        _, _, data = api.request("POST", base, json_body={k: v for k, v in init.items() if v is not None})
        started = json.loads(data.decode("utf-8"))
        upload_id = started["uploadId"]
        part_size = int(started["partSizeBytes"])
        _update_meta(meta_path, {IN_PROGRESS_KEY: {
            "api": api.settings.api_base, "uploadId": upload_id, "partSizeBytes": part_size, "startedAt": _now()}})

    total_parts = max(1, -(-size // part_size))
    if resumed:
        log(f"    resuming {file_info['original_filename']}: server has {received}/{total_parts} parts")
    next_report = 0.1
    resends = 0
    part = received + 1
    while part <= total_parts:
        offset = (part - 1) * part_size
        length = min(part_size, size - offset)
        digest = hashlib.sha256()
        for chunk in _file_chunks(path, offset, length):
            digest.update(chunk)
        try:
            api.request("PUT", f"{base}/{upload_id}/parts/{part}",
                        headers={"Content-Type": "application/octet-stream",
                                 "X-Checksum-SHA256": digest.hexdigest()},
                        body=lambda o=offset, n=length: _file_chunks(path, o, n), length=length)
        except ApiError as e:
            if e.code in RESEND_PART_CODES and resends < 3:
                resends += 1  # damaged in transit: the server kept nothing of it
                continue
            if e.code != "part_out_of_order":
                if e.code == "upload_not_active":
                    _update_meta(meta_path, {IN_PROGRESS_KEY: None})  # next run starts a new upload
                raise
            # Out of step with the server (e.g. a part whose answer was lost): ask and continue.
            expected = _received_parts(api.get_json(f"{base}/{upload_id}"), part_size) + 1
            if expected == part:
                raise
            part = expected
            continue
        resends = 0
        part += 1
        if progress and total_parts >= 20 and (part - 1) / total_parts >= next_report:
            log(f"    {file_info['original_filename']}: {100 * (part - 1) // total_parts}% "
                f"({min(size, (part - 1) * part_size) / 1048576:.0f} MB)")
            next_report += 0.1

    try:
        _, _, data = api.request("POST", f"{base}/{upload_id}/complete")
    except ApiError as e:
        if e.code == "checksum_mismatch":
            # What the server holds is not this file: forget the upload so the next run starts over.
            _update_meta(meta_path, {IN_PROGRESS_KEY: None})
        raise
    created = json.loads(data.decode("utf-8"))
    if created.get("sha256") and created["sha256"] != file_info["sha256"]:
        raise RuntimeError(f"server stored sha256 {created['sha256']}, local file is {file_info['sha256']}")
    return created, resumed


def _received_parts(state: Dict[str, Any], part_size: int) -> int:
    """Number of parts the server holds, counted as a contiguous run from part 1."""
    parts = state.get("receivedParts")
    if isinstance(parts, list):
        have = set(int(p) for p in parts)
        n = 0
        while n + 1 in have:
            n += 1
        return n
    return int(state.get("bytesReceived", 0)) // part_size


def ingest(api: FileGridApi, data_dir: Path, items: List[Dict[str, Any]], workers: int = 4,
           force: bool = False) -> Dict[str, Any]:
    """Uploads every item not yet uploaded to this FileGrid instance. Returns per-category statistics."""
    api_base = api.settings.api_base
    stats: Dict[str, Dict[str, Any]] = {
        c: {"uploaded": 0, "resumed": 0, "skipped": 0, "failed": 0, "bytes": 0,
            "first": None, "last": None, "seconds": []} for c in (FAULT_RECORD, DRONE_VIDEO)}
    lock = threading.Lock()

    def one(entry: Dict[str, Any]) -> None:
        cat = entry["category"]
        info = entry["file"]
        name = info["original_filename"]
        row = f"{entry['region']:<10} {cat:<13} {info['size_bytes'] / 1048576:>9.2f} MB"
        if not force and uploaded_record(entry, api_base):
            with lock:
                stats[cat]["skipped"] += 1
            log(f"{row}  {'SKIPPED':<9} {'':>8}  {name}")
            return
        started = time.monotonic()
        try:
            if cat == FAULT_RECORD:
                created, resumed = upload_fault_record(api, data_dir, entry), False
            else:
                created, resumed = upload_drone_video(api, data_dir, entry)
            _update_meta(meta_path_of(data_dir, entry), {
                UPLOADED_KEY: {"api": api_base, "id": created["id"], "fileId": created.get("fileId"),
                               "sha256": created.get("sha256"), "uploadedAt": _now()},
                IN_PROGRESS_KEY: None})
            ended = time.monotonic()
            with lock:
                s = stats[cat]
                s["resumed" if resumed else "uploaded"] += 1
                s["bytes"] += info["size_bytes"]
                s["seconds"].append(ended - started)
                s["first"] = started if s["first"] is None else min(s["first"], started)
                s["last"] = ended if s["last"] is None else max(s["last"], ended)
            log(f"{row}  {'RESUMED' if resumed else 'UPLOADED':<9} {ended - started:>7.2f}s  {name}")
        except Exception as e:  # one bad file must not stop the batch
            with lock:
                stats[cat]["failed"] += 1
            log(f"{row}  {'FAILED':<9} {time.monotonic() - started:>7.2f}s  {name}\n    {type(e).__name__}: {e}")

    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        for f in as_completed([pool.submit(one, entry) for entry in items]):
            f.result()
    return stats


# =============================================================================
# Verification and status
# =============================================================================


def verify(api: FileGridApi, data_dir: Path, items: List[Dict[str, Any]], workers: int = 4) -> List[Dict[str, Any]]:
    """Downloads every uploaded item through the API and compares SHA-256 with the local file."""
    api_base = api.settings.api_base

    def one(entry: Dict[str, Any]) -> Dict[str, Any]:
        info = entry["file"]
        result = {"region": entry["region"], "category": entry["category"], "name": info["original_filename"],
                  "size_bytes": info["size_bytes"], "expected": info["sha256"], "actual": None, "status": ""}
        rec = uploaded_record(entry, api_base)
        if not rec:
            result["status"] = "NOT_UPLOADED"
            return result
        digest = [hashlib.sha256()]
        count = [0]

        def sink(chunk: bytes) -> None:
            digest[0].update(chunk)
            count[0] += len(chunk)

        def restart() -> None:
            digest[0] = hashlib.sha256()
            count[0] = 0

        try:
            _, headers, _ = api.request("GET", f"/{RESOURCE[entry['category']]}/{rec['id']}/file",
                                        headers={"Accept": "*/*"}, sink=sink, on_restart=restart)
        except ApiError as e:
            result["status"] = "CORRUPTED" if e.code == "integrity_check_failed" else (
                "MISSING" if e.status == 404 else "ERROR")
            result["error"] = str(e)
            return result
        except Unavailable as e:
            result["status"] = "ERROR"
            result["error"] = str(e)
            return result
        actual = digest[0].hexdigest()
        result["actual"] = actual
        header = (headers.get("x-checksum-sha256") or "").lower()
        local_path = data_dir / info["relative_path"]
        if actual != info["sha256"] or count[0] != info["size_bytes"]:
            result["status"] = "MISMATCH"
        elif header and header != actual:
            result["status"] = "BAD_HEADER"
            result["error"] = f"X-Checksum-SHA256 says {header}"
        elif local_path.exists() and local_path.stat().st_size != count[0]:
            result["status"] = "MISMATCH"
        else:
            result["status"] = "OK" if header else "OK_NO_HEADER"
        return result

    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        return list(pool.map(one, items))


def remote_totals(api: FileGridApi, regions: List[str]) -> Dict[str, Dict[str, int]]:
    """Number of records FileGrid holds per region and category (from the list endpoints)."""
    totals: Dict[str, Dict[str, int]] = {}
    for region in regions:
        totals[region] = {}
        for category, resource in RESOURCE.items():
            page = api.get_json("/" + resource, {"region": region, "size": 1})
            totals[region][category] = int(page.get("total", len(page.get("items", []))))
    return totals
