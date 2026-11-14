"""
Generator for Fault Record JSON files (1–10 MB) and Drone Inspection MP4 videos
(10 MB – 1 GB+), partitioned and tagged by region (MUNTENIA, BANAT, DOBROGEA).
"""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import math
import os
import random
import shutil
import struct
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .catalog import (
    DISPATCH_OPERATORS_BY_REGION,
    DRONES_BY_REGION,
    NORMAL_FAULT_CAUSES,
    POWER_LINES_BY_REGION,
    REGIONS,
    STORM_FAULT_CAUSES,
    SUBSTATIONS_BY_REGION,
    DroneUnit,
    PowerLine,
    Substation,
)


def sha256_file(path: Path, chunk_size: int = 8 * 1024 * 1024) -> Tuple[str, int]:
    """Stream-compute hex SHA-256 and byte size of a file with O(1) memory."""
    h = hashlib.sha256()
    total = 0
    with path.open("rb") as f:
        while True:
            buf = f.read(chunk_size)
            if not buf:
                break
            h.update(buf)
            total += len(buf)
    return h.hexdigest(), total


def _generate_waveform_samples(
    rng: random.Random,
    nominal_kv: float,
    severity: str,
    target_size_bytes: int,
) -> List[Dict[str, float]]:
    """
    Generate realistic 50 Hz 3-phase substation DFR (Digital Fault Recorder) samples
    (pre-fault, fault transient with phase voltage sag & fault current spike, and post-trip)
    sized to reach `target_size_bytes` (1–10 MB).
    """
    # Each serialized sample object in indented JSON (indent=2) is ~208 bytes.
    bytes_per_sample = 208
    num_samples = max(4800, int((target_size_bytes - 1600) / bytes_per_sample))

    phase_v_peak = (nominal_kv / math.sqrt(3.0)) * math.sqrt(2.0)
    base_current_a = 180.0 if nominal_kv >= 110 else 320.0
    fault_mult = {
        "LOW": 2.4,
        "MEDIUM": 4.8,
        "HIGH": 8.5,
        "CRITICAL": 14.2,
    }.get(severity, 4.0)
    sag_factor = {
        "LOW": 0.78,
        "MEDIUM": 0.52,
        "HIGH": 0.28,
        "CRITICAL": 0.12,
    }.get(severity, 0.5)

    fault_start_idx = int(num_samples * 0.15)
    fault_clear_idx = int(num_samples * 0.78)
    dt_ms = 0.2  # 5 kHz DFR sampling rate (0.2 ms step)
    omega = 2.0 * math.pi * 50.0  # 50 Hz grid frequency

    samples: List[Dict[str, float]] = []
    for i in range(num_samples):
        t_ms = round(i * dt_ms, 2)
        t_s = i * (dt_ms / 1000.0)
        angle_a = omega * t_s
        angle_b = angle_a - (2.0 * math.pi / 3.0)
        angle_c = angle_a + (2.0 * math.pi / 3.0)

        in_fault = fault_start_idx <= i <= fault_clear_idx
        post_trip = i > fault_clear_idx

        noise_v = rng.uniform(-0.35, 0.35)
        noise_i = rng.uniform(-3.5, 3.5)

        if in_fault:
            # Phase A-B or A-G fault transient with decaying DC offset
            dc_decay = math.exp(-(i - fault_start_idx) * 0.0015)
            va = round(phase_v_peak * sag_factor * math.sin(angle_a) + noise_v, 3)
            vb = round(phase_v_peak * (sag_factor + 0.15) * math.sin(angle_b) + noise_v, 3)
            vc = round(phase_v_peak * 0.96 * math.sin(angle_c) + noise_v, 3)

            ia = round(base_current_a * fault_mult * (math.sin(angle_a - 1.1) + 0.65 * dc_decay) + noise_i, 2)
            ib = round(base_current_a * (fault_mult * 0.55) * math.sin(angle_b - 0.9) + noise_i, 2)
            ic = round(base_current_a * 1.05 * math.sin(angle_c - 0.3) + noise_i, 2)
            freq = round(50.0 - 0.08 * fault_mult / 10.0 + rng.uniform(-0.03, 0.03), 3)
        elif post_trip:
            # Breaker open / auto-reclose recovery
            rec = 0.05 if severity in ("HIGH", "CRITICAL") else 0.98
            va = round(phase_v_peak * rec * math.sin(angle_a) + noise_v, 3)
            vb = round(phase_v_peak * rec * math.sin(angle_b) + noise_v, 3)
            vc = round(phase_v_peak * rec * math.sin(angle_c) + noise_v, 3)
            ia = round(base_current_a * rec * math.sin(angle_a - 0.25) + noise_i, 2)
            ib = round(base_current_a * rec * math.sin(angle_b - 0.25) + noise_i, 2)
            ic = round(base_current_a * rec * math.sin(angle_c - 0.25) + noise_i, 2)
            freq = round(50.0 + rng.uniform(-0.02, 0.02), 3)
        else:
            # Normal pre-fault steady state
            va = round(phase_v_peak * math.sin(angle_a) + noise_v, 3)
            vb = round(phase_v_peak * math.sin(angle_b) + noise_v, 3)
            vc = round(phase_v_peak * math.sin(angle_c) + noise_v, 3)
            ia = round(base_current_a * math.sin(angle_a - 0.25) + noise_i, 2)
            ib = round(base_current_a * math.sin(angle_b - 0.25) + noise_i, 2)
            ic = round(base_current_a * math.sin(angle_c - 0.25) + noise_i, 2)
            freq = round(50.0 + rng.uniform(-0.015, 0.015), 3)

        in_neutral = round(ia + ib + ic, 2)
        samples.append(
            {
                "t_ms": t_ms,
                "va_kv": va,
                "vb_kv": vb,
                "vc_kv": vc,
                "ia_a": ia,
                "ib_a": ib,
                "ic_a": ic,
                "in_a": in_neutral,
                "freq_hz": freq,
            }
        )
    return samples


def generate_fault_record_file(
    output_dir: Path,
    region: str,
    substation: Substation,
    rng: random.Random,
    profile: str = "normal",
    size_mb: Optional[float] = None,
    occurred_at: Optional[dt.datetime] = None,
) -> Dict[str, Any]:
    """
    Generate a single 1–10 MB JSON fault record file in `output_dir/faults/<REGION>/`.
    Returns its manifest entry.
    """
    if region not in REGIONS:
        raise ValueError(f"Unsupported region: {region}")

    causes_pool = STORM_FAULT_CAUSES if profile == "storm" else NORMAL_FAULT_CAUSES
    severity, cause_code, description_ro = rng.choice(causes_pool)

    if size_mb is None:
        # PDF §2.1: 1–10 MB typical size for fault records
        size_mb = rng.uniform(2.0, 6.5) if profile == "storm" else rng.uniform(1.2, 3.2)
    target_bytes = int(max(1.05, min(10.0, size_mb)) * 1024 * 1024)

    now_utc = dt.datetime.now(dt.timezone.utc)
    if occurred_at is None:
        hours_ago = rng.uniform(0.5, 18.0) if profile == "storm" else rng.uniform(1.0, 120.0)
        occurred_at = now_utc - dt.timedelta(hours=hours_ago)

    # Restore time depends on severity (CRITICAL faults may still be active or take hours)
    if severity == "CRITICAL" and rng.random() < 0.35:
        restored_at = None
    else:
        outage_minutes = {
            "LOW": rng.randint(2, 18),
            "MEDIUM": rng.randint(15, 75),
            "HIGH": rng.randint(45, 210),
            "CRITICAL": rng.randint(120, 480),
        }[severity]
        restored_at = occurred_at + dt.timedelta(minutes=outage_minutes)
        if restored_at > now_utc:
            restored_at = now_utc

    affected_ratio = {
        "LOW": rng.uniform(0.02, 0.08),
        "MEDIUM": rng.uniform(0.08, 0.25),
        "HIGH": rng.uniform(0.25, 0.60),
        "CRITICAL": rng.uniform(0.55, 0.95),
    }[severity]
    affected_customers = int(substation.customers_served * affected_ratio)

    reported_by = rng.choice(DISPATCH_OPERATORS_BY_REGION[region])
    fault_id = str(uuid.uuid4())
    file_id = str(uuid.uuid4())
    ts_slug = occurred_at.strftime("%Y%m%dT%H%M%SZ")
    filename = f"fault_{region}_{substation.code}_{ts_slug}_{fault_id[:8]}.json"

    region_dir = output_dir / "faults" / region
    region_dir.mkdir(parents=True, exist_ok=True)
    file_path = region_dir / filename

    samples = _generate_waveform_samples(
        rng=rng,
        nominal_kv=float(substation.voltage_kv),
        severity=severity,
        target_size_bytes=target_bytes,
    )

    payload = {
        "schema_version": "1.0",
        "category": "FAULT_RECORD",
        "region": region,
        "profile": profile,
        "fault_record": {
            "id": fault_id,
            "file_id": file_id,
            "region": region,
            "county": substation.county,
            "locality": substation.locality,
            "substation_code": substation.code,
            "substation_name": substation.name,
            "occurred_at": occurred_at.isoformat(),
            "restored_at": restored_at.isoformat() if restored_at else None,
            "severity": severity,
            "cause": cause_code,
            "affected_customers": affected_customers,
            "description": f"[{region} / {substation.name}] {description_ro}",
            "reported_by": reported_by,
        },
        "protection_relay": {
            "ied_device": f"ABB-REL670-{substation.code}",
            "nominal_voltage_kv": substation.voltage_kv,
            "substation_coordinates": {"lat": substation.lat, "lon": substation.lon},
            "sampling_rate_hz": 5000,
            "triggered_ansi_codes": (
                ["21 (Distance Stage 1)", "50N (Earth Fault Instantaneous)", "87L (Line Differential)"]
                if severity in ("HIGH", "CRITICAL")
                else ["50/51 (Phase Overcurrent)", "79 (Auto-Reclose)"]
            ),
            "breaker_trip_time_ms": round(rng.uniform(28.0, 64.0), 1),
            "auto_reclose_RAR": "SUCCESS" if severity == "LOW" else "LOCKOUT",
            "estimated_fault_distance_km": round(rng.uniform(1.2, 24.8), 2),
            "weather_condition": "STORM_GUSTS_LIGHTNING" if profile == "storm" else "NORMAL",
        },
        "oscillogram_samples_count": len(samples),
        "oscillogram_samples": samples,
    }

    with file_path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    digest, size_bytes = sha256_file(file_path)

    meta_entry = {
        "category": "FAULT_RECORD",
        "region": region,
        "profile": profile,
        "file": {
            "id": file_id,
            "relative_path": str(file_path.relative_to(output_dir)),
            "original_filename": filename,
            "content_type": "application/json",
            "size_bytes": size_bytes,
            "size_mb": round(size_bytes / (1024 * 1024), 2),
            "sha256": digest,
            "uploaded_by": reported_by,
        },
        "fault_record": payload["fault_record"],
    }

    meta_path = file_path.with_suffix(".meta.json")
    with meta_path.open("w", encoding="utf-8") as mf:
        json.dump(meta_entry, mf, ensure_ascii=False, indent=2)

    return meta_entry


# Without ffmpeg the generator still produces upload-test payloads: a structurally
# valid ISO-BMFF (.mp4) container whose media data is pseudo-random bytes. It is
# not playable, but it has the right size, type and (incompressible) content for
# transfer, resume and integrity tests.
_SYNTHETIC_BYTES_PER_SECOND = 1_150_000  # bitrate of the real ffmpeg clips
_ffmpeg_warning_printed = False


def _warn_no_ffmpeg() -> None:
    global _ffmpeg_warning_printed
    if not _ffmpeg_warning_printed:
        _ffmpeg_warning_printed = True
        print(
            "WARNING: ffmpeg not found - writing synthetic (non-playable) .mp4 containers. "
            "Install ffmpeg for real H.264 clips with the HUD overlay.",
            file=sys.stderr,
        )


def _write_synthetic_mp4(file_path: Path, target_bytes: int, comment: str, rng: random.Random) -> int:
    """Write `ftyp` + `free` (metadata comment) + one large `mdat` box; O(1) memory. Returns seconds."""
    ftyp = struct.pack(">I4s4sI4s4s4s4s", 32, b"ftyp", b"isom", 0x200, b"isom", b"iso2", b"avc1", b"mp41")
    comment_bytes = comment.encode("utf-8")
    free = struct.pack(">I4s", 8 + len(comment_bytes), b"free") + comment_bytes
    payload = max(1024, target_bytes - len(ftyp) - len(free) - 16)
    block_size = 1024 * 1024
    block = rng.getrandbits(8 * block_size).to_bytes(block_size, "little")
    body = memoryview(block)[8:]
    with file_path.open("wb") as f:
        f.write(ftyp)
        f.write(free)
        f.write(struct.pack(">I4sQ", 1, b"mdat", 16 + payload))  # size 1 = 64-bit largesize follows
        written = 0
        counter = 0
        while written < payload:
            # A counter in front of every block keeps blocks distinct (no dedup/compression shortcut).
            chunk = struct.pack(">Q", counter) + bytes(body[: max(0, min(block_size, payload - written) - 8)])
            chunk = chunk[: payload - written]
            f.write(chunk)
            written += len(chunk)
            counter += 1
    return max(4, round(payload / _SYNTHETIC_BYTES_PER_SECOND))



def generate_drone_video_file(
    output_dir: Path,
    region: str,
    power_line: PowerLine,
    drone: DroneUnit,
    rng: random.Random,
    profile: str = "normal",
    target_size_mb: float = 15.0,
    flight_started_at: Optional[dt.datetime] = None,
) -> Dict[str, Any]:
    """
    Generate a valid H.264 `.mp4` drone inspection video with regional HUD overlay
    and companion `.meta.json` in `output_dir/videos/<REGION>/`.
    Uses a base encoded segment + `-stream_loop` stream copy so even 1 GB+ videos
    are produced in a few seconds with constant CPU/memory usage.
    Without ffmpeg a synthetic (non-playable) container of the same size is written.
    """
    if region not in REGIONS:
        raise ValueError(f"Unsupported region: {region}")
    have_ffmpeg = shutil.which("ffmpeg") is not None

    now_utc = dt.datetime.now(dt.timezone.utc)
    if flight_started_at is None:
        hours_ago = rng.uniform(1.0, 24.0) if profile == "storm" else rng.uniform(2.0, 96.0)
        flight_started_at = now_utc - dt.timedelta(hours=hours_ago)

    video_id = str(uuid.uuid4())
    file_id = str(uuid.uuid4())
    ts_slug = flight_started_at.strftime("%Y%m%dT%H%M%SZ")
    scale_tag = "LARGE1GB" if target_size_mb >= 1000 else ("STORM" if profile == "storm" else "INSP")
    filename = f"video_{region}_{power_line.code}_{scale_tag}_{ts_slug}_{video_id[:8]}.mp4"

    region_dir = output_dir / "videos" / region
    region_dir.mkdir(parents=True, exist_ok=True)
    file_path = region_dir / filename

    uploaded_by = rng.choice(DISPATCH_OPERATORS_BY_REGION[region])
    notes = (
        f"Inspecție post-furtună cu drona {drone.drone_id} ({drone.model}) pe linia {power_line.name} "
        f"[{region} / jud. {power_line.county}]. Verificare izolatori, coronament și stâlpi."
        if profile == "storm"
        else f"Inspecție periodică preventivă cu drona {drone.drone_id} pe linia {power_line.name} "
        f"[{region} / jud. {power_line.county}]."
    )

    metadata_comment = (
        f"region={region};category=DRONE_VIDEO;county={power_line.county};"
        f"line_code={power_line.code};drone_id={drone.drone_id};"
        f"flight_started_at={flight_started_at.strftime('%Y-%m-%dT%H:%M:%SZ')}"
    )
    target_bytes = int(max(1.0, target_size_mb) * 1024 * 1024)
    if not have_ffmpeg:
        _warn_no_ffmpeg()
        duration_seconds = _write_synthetic_mp4(file_path, target_bytes, metadata_comment, rng)
    else:
        # Encode a 4-second high-detail base clip (~4-6 MB), then stream-loop with -c copy
        # to reach the requested target_size_mb cleanly and rapidly.
        base_seconds = 4
        with tempfile.TemporaryDirectory(prefix="filegrid_video_") as tmpdir:
            seg_path = Path(tmpdir) / "segment.mp4"

            line1 = f"RETELE ELECTRICE ROMANIA  |  REGION\\: {region}  |  JUDET\\: {power_line.county}"
            line2 = f"LINE\\: {power_line.code} ({power_line.voltage_kv} kV)  |  DRONE\\: {drone.drone_id}"
            line3 = (
                f"GPS\\: {power_line.start_lat:.4f}N {power_line.start_lon:.4f}E  |  "
                f"FLIGHT\\: {flight_started_at.strftime('%Y-%m-%d %H\\:%M UTC')}  |  T\\: %{{pts\\:hms}}"
            )

            # Adding light sensor noise ensures realistic bitrate (~1–1.2 MB/s -> ~4.5 MB per 4s segment)
            vf_filter = (
                "noise=alls=6:allf=t,"
                "drawbox=x=0:y=0:w=iw:h=115:color=black@0.72:t=fill,"
                f"drawtext=text='{line1}':x=24:y=18:fontsize=22:fontcolor=white,"
                f"drawtext=text='{line2}':x=24:y=50:fontsize=19:fontcolor=cyan,"
                f"drawtext=text='{line3}':x=24:y=80:fontsize=17:fontcolor=yellow"
            )

            cmd_base = [
                "ffmpeg",
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                f"testsrc2=size=1280x720:rate=25:duration={base_seconds}",
                "-vf",
                vf_filter,
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-crf",
                "21",
                "-pix_fmt",
                "yuv420p",
                str(seg_path),
            ]
            subprocess.run(cmd_base, check=True)

            seg_size = seg_path.stat().st_size
            target_bytes = int(max(1.0, target_size_mb) * 1024 * 1024)
            loops = max(0, math.ceil(target_bytes / max(1, seg_size)) - 1)
            duration_seconds = base_seconds * (loops + 1)

            cmd_loop = [
                "ffmpeg",
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-stream_loop",
                str(loops),
                "-i",
                str(seg_path),
                "-c",
                "copy",
                "-metadata",
                f"title=FileGrid Drone Inspection {region} - {power_line.code}",
                "-metadata",
                f"comment={metadata_comment}",
                # mvhd creation time: any player (and the FileGrid form) shows when the flight started.
                "-metadata",
                f"creation_time={flight_started_at.strftime('%Y-%m-%dT%H:%M:%SZ')}",
                "-movflags",
                "+faststart",
                str(file_path),
            ]
            subprocess.run(cmd_loop, check=True)

    digest, size_bytes = sha256_file(file_path)

    drone_record = {
        "id": video_id,
        "file_id": file_id,
        "region": region,
        "county": power_line.county,
        "line_code": power_line.code,
        "line_name": power_line.name,
        "drone_id": drone.drone_id,
        "drone_model": drone.model,
        "flight_started_at": flight_started_at.isoformat(),
        "duration_seconds": duration_seconds,
        "notes": notes,
        "uploaded_by": uploaded_by,
    }

    meta_entry = {
        "category": "DRONE_VIDEO",
        "region": region,
        "profile": profile,
        "file": {
            "id": file_id,
            "relative_path": str(file_path.relative_to(output_dir)),
            "original_filename": filename,
            "content_type": "video/mp4",
            "size_bytes": size_bytes,
            "size_mb": round(size_bytes / (1024 * 1024), 2),
            "sha256": digest,
            "uploaded_by": uploaded_by,
            "encoder": "ffmpeg" if have_ffmpeg else "synthetic",
        },
        "drone_video": drone_record,
    }

    meta_path = file_path.with_suffix(".meta.json")
    with meta_path.open("w", encoding="utf-8") as mf:
        json.dump(meta_entry, mf, ensure_ascii=False, indent=2)

    return meta_entry


def rebuild_manifest(output_dir: Path) -> Dict[str, Any]:
    """
    Scan `output_dir` for all `*.meta.json` files and build `output_dir/manifest.json`
    grouped by region and category.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    meta_files = sorted(output_dir.rglob("*.meta.json"))

    items: List[Dict[str, Any]] = []
    by_region: Dict[str, Dict[str, int]] = {
        r: {"FAULT_RECORD": 0, "DRONE_VIDEO": 0, "total_bytes": 0} for r in REGIONS
    }

    for mp in meta_files:
        with mp.open("r", encoding="utf-8") as f:
            entry = json.load(f)
        reg = entry.get("region")
        cat = entry.get("category")
        sz = int(entry.get("file", {}).get("size_bytes", 0))
        if reg in by_region and cat in ("FAULT_RECORD", "DRONE_VIDEO"):
            by_region[reg][cat] += 1
            by_region[reg]["total_bytes"] += sz
        items.append(entry)

    manifest = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "regions": REGIONS,
        "summary_by_region": {
            r: {
                "fault_records": stats["FAULT_RECORD"],
                "drone_videos": stats["DRONE_VIDEO"],
                "total_mb": round(stats["total_bytes"] / (1024 * 1024), 2),
            }
            for r, stats in by_region.items()
        },
        "total_files": len(items),
        "items": items,
    }

    manifest_path = output_dir / "manifest.json"
    with manifest_path.open("w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    return manifest


def generate_dataset(
    output_dir: Path,
    regions: Optional[List[str]] = None,
    faults_per_region: int = 2,
    videos_per_region: int = 1,
    fault_min_mb: float = 1.2,
    fault_max_mb: float = 3.5,
    video_size_mb: float = 12.0,
    profile: str = "normal",
    include_large_video: bool = False,
    large_video_size_mb: float = 1050.0,
    large_video_region: str = "MUNTENIA",
    seed: Optional[int] = 42,
) -> Dict[str, Any]:
    """
    Generate a full regional dataset of fault records (JSON) and drone videos (MP4).
    """
    rng = random.Random(seed)
    target_regions = [r.upper() for r in (regions or REGIONS)]
    for r in target_regions:
        if r not in REGIONS:
            raise ValueError(f"Invalid region '{r}'. Must be one of {REGIONS}.")

    for region in target_regions:
        substations = SUBSTATIONS_BY_REGION[region]
        lines = POWER_LINES_BY_REGION[region]
        drones = DRONES_BY_REGION[region]

        for i in range(faults_per_region):
            sub = substations[i % len(substations)]
            target_mb = rng.uniform(fault_min_mb, fault_max_mb)
            generate_fault_record_file(
                output_dir=output_dir,
                region=region,
                substation=sub,
                rng=rng,
                profile=profile,
                size_mb=target_mb,
            )

        for j in range(videos_per_region):
            line = lines[j % len(lines)]
            drone = drones[j % len(drones)]
            generate_drone_video_file(
                output_dir=output_dir,
                region=region,
                power_line=line,
                drone=drone,
                rng=rng,
                profile=profile,
                target_size_mb=video_size_mb,
            )

    if include_large_video:
        reg = large_video_region.upper()
        line = POWER_LINES_BY_REGION[reg][0]
        drone = DRONES_BY_REGION[reg][0]
        generate_drone_video_file(
            output_dir=output_dir,
            region=reg,
            power_line=line,
            drone=drone,
            rng=rng,
            profile="storm",
            target_size_mb=large_video_size_mb,
        )

    return rebuild_manifest(output_dir)
