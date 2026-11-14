#!/usr/bin/env python3
"""
CLI for FileGrid PPC test data: fault records (JSON) and drone inspection videos (MP4).

It generates files locally and sends them to FileGrid through its HTTP API, the
only entry point for field data. It never touches PostgreSQL, FTP or S3.

Usage examples:
  python3 backend/fault_videos/fault_videos.py generate
  python3 backend/fault_videos/fault_videos.py generate --profile storm --regions MUNTENIA
  python3 backend/fault_videos/fault_videos.py generate --large-video --large-video-mb 1050
  python3 backend/fault_videos/fault_videos.py ingest --workers 8
  python3 backend/fault_videos/fault_videos.py verify
  python3 backend/fault_videos/fault_videos.py status
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

# Allow running both as `python3 backend/fault_videos/fault_videos.py` and as module
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from backend.fault_videos.catalog import REGIONS
from backend.fault_videos.generator import generate_dataset, rebuild_manifest
from backend.fault_videos import api_client
from backend.fault_videos.api_client import DRONE_VIDEO, FAULT_RECORD, FileGridApi, Unavailable

DEFAULT_DATA_DIR = SCRIPT_DIR / "data"


def _print_manifest_summary(manifest: Dict[str, Any], data_dir: Path) -> None:
    shown = data_dir.relative_to(REPO_ROOT) if data_dir.is_relative_to(REPO_ROOT) else data_dir
    print(f"\n=== Generated Dataset Summary ({shown}) ===")
    print(f"{'REGION':<12} {'FAULT_RECORD (JSON)':<22} {'DRONE_VIDEO (MP4)':<20} {'TOTAL SIZE (MB)':<16}")
    print("-" * 72)
    summary = manifest.get("summary_by_region", {})
    for reg in REGIONS:
        s = summary.get(reg, {"fault_records": 0, "drone_videos": 0, "total_mb": 0.0})
        print(
            f"{reg:<12} {s['fault_records']:<22} {s['drone_videos']:<20} {s['total_mb']:<16.2f}"
        )
    print("-" * 72)
    print(f"Total files indexed in manifest.json: {manifest.get('total_files', 0)}\n")


def cmd_generate(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    regions = [r.strip().upper() for r in args.regions.split(",") if r.strip()]

    profile = args.profile
    faults_per_region = args.faults_per_region
    if faults_per_region is None:
        # Storm profile defaults to 10x normal volume in the target region(s)
        faults_per_region = 10 if profile == "storm" else 2

    videos_per_region = args.videos_per_region
    if videos_per_region is None:
        videos_per_region = 2 if profile == "storm" else 1

    print(
        f"Generating FileGrid dataset [profile={profile}, regions={regions}, "
        f"faults/region={faults_per_region}, videos/region={videos_per_region}, "
        f"video_size={args.video_size_mb} MB, large_video={args.large_video}]..."
    )

    manifest = generate_dataset(
        output_dir=data_dir,
        regions=regions,
        faults_per_region=faults_per_region,
        videos_per_region=videos_per_region,
        fault_min_mb=args.fault_min_mb,
        fault_max_mb=args.fault_max_mb,
        video_size_mb=args.video_size_mb,
        profile=profile,
        include_large_video=args.large_video,
        large_video_size_mb=args.large_video_mb,
        large_video_region=args.large_video_region,
        seed=args.seed,
    )
    _print_manifest_summary(manifest, data_dir)
    return 0


def _select(items: List[Dict[str, Any]], args: argparse.Namespace) -> List[Dict[str, Any]]:
    region = getattr(args, "region", None)
    category = getattr(args, "category", None)
    return [
        it for it in items
        if (not region or it.get("region") == region.upper())
        and (not category or it.get("category") == category.upper())
    ]


def _percentile(values: List[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(q * len(ordered)))] if ordered else 0.0


def cmd_ingest(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    if not (data_dir / "manifest.json").exists():
        print("No manifest.json found; generating default dataset first...")
        manifest = generate_dataset(output_dir=data_dir)
    else:
        manifest = rebuild_manifest(data_dir)

    items = _select(manifest.get("items", []), args)
    if not items:
        print("No matching files to ingest.")
        return 0

    api = FileGridApi()
    print(f"Uploading {len(items)} file(s) to FileGrid at {api.settings.api_base} "
          f"as {api.settings.username} ({args.workers} worker(s))...")
    print(f"{'REGION':<10} {'CATEGORY':<13} {'SIZE':>12}  {'STATUS':<9} {'TIME':>8}  FILENAME")
    print("-" * 100)
    started = time.monotonic()
    try:
        api.login()
        stats = api_client.ingest(api, data_dir, items, workers=args.workers, force=args.force)
    except Unavailable as exc:
        print(f"FileGrid is not reachable: {exc}", file=sys.stderr)
        return 2
    elapsed = time.monotonic() - started
    rebuild_manifest(data_dir)

    print("-" * 100)
    print(f"{'CATEGORY':<13} {'UPLOADED':>8} {'RESUMED':>8} {'SKIPPED':>8} {'FAILED':>7} {'MB':>10} "
          f"{'ELAPSED':>9} {'MB/s':>8} {'files/s':>8} {'avg s':>7} {'p95 s':>7}")
    failed = 0
    for cat in (FAULT_RECORD, DRONE_VIDEO):
        s = stats[cat]
        failed += s["failed"]
        done = s["uploaded"] + s["resumed"]
        wall = (s["last"] - s["first"]) if done else 0.0
        mb = s["bytes"] / 1048576
        avg = sum(s["seconds"]) / len(s["seconds"]) if s["seconds"] else 0.0
        print(f"{cat:<13} {s['uploaded']:>8} {s['resumed']:>8} {s['skipped']:>8} {s['failed']:>7} {mb:>10.2f} "
              f"{wall:>8.2f}s {mb / wall if wall > 0 else 0:>8.2f} {done / wall if wall > 0 else 0:>8.2f} "
              f"{avg:>7.2f} {_percentile(s['seconds'], 0.95):>7.2f}")
    print(f"Total time {elapsed:.2f}s, {api.retries} request(s) retried after a transient failure.")
    if failed:
        print(f"{failed} file(s) FAILED - run the command again to retry them.", file=sys.stderr)
    return 1 if failed else 0


def cmd_verify(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    if not data_dir.exists():
        print(f"Local data directory ({data_dir}) does not exist yet. Run `generate` and `ingest` first.")
        return 0
    items = _select(rebuild_manifest(data_dir).get("items", []), args)
    api = FileGridApi()
    try:
        api.login()
        results = api_client.verify(api, data_dir, items, workers=args.workers)
    except Unavailable as exc:
        print(f"FileGrid is not reachable: {exc}", file=sys.stderr)
        return 2

    checked = [r for r in results if r["status"] != "NOT_UPLOADED"]
    if not checked:
        print(f"Nothing uploaded to {api.settings.api_base} yet. Run `ingest` first.")
        return 0

    print(f"\n{'REGION':<10} {'CATEGORY':<13} {'SIZE':>12}  {'INTEGRITY':<13} {'SHA-256 (prefix)':<22} FILENAME")
    print("-" * 110)
    ok = 0
    for r in checked:
        good = r["status"] in ("OK", "OK_NO_HEADER")
        ok += good
        print(f"{r['region']:<10} {r['category']:<13} {r['size_bytes'] / 1048576:>9.2f} MB  "
              f"{r['status']:<13} {(r['actual'] or '-')[:20]:<22} {r['name']}")
        if not good:
            print(f"    expected {r['expected']}" + (f"; {r['error']}" if r.get("error") else ""))
    print("-" * 110)
    skipped = len(results) - len(checked)
    print(f"Verified {ok}/{len(checked)} file(s) bit-for-bit through the API"
          + (f" ({skipped} local file(s) not uploaded yet)." if skipped else ".") + "\n")
    return 0 if ok == len(checked) else 1


def cmd_status(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    if data_dir.exists():
        manifest = rebuild_manifest(data_dir)
        _print_manifest_summary(manifest, data_dir)
    else:
        manifest = {"items": []}
        print(f"Local data directory ({data_dir}) does not exist yet. Run `generate` first.")

    api = FileGridApi()
    uploaded = {r: {FAULT_RECORD: 0, DRONE_VIDEO: 0} for r in REGIONS}
    for it in manifest.get("items", []):
        if api_client.uploaded_record(it, api.settings.api_base) and it.get("region") in uploaded:
            uploaded[it["region"]][it["category"]] += 1
    try:
        totals = api_client.remote_totals(api, REGIONS)
    except (Unavailable, api_client.ApiError) as exc:
        print(f"(FileGrid not reachable at {api.settings.api_base} - start it with `make up`: {exc})")
        return 0

    print(f"=== FileGrid ({api.settings.api_base}) ===")
    print(f"{'REGION':<12} {'FAULT_RECORD total':<20} {'(from this dataset)':<21} "
          f"{'DRONE_VIDEO total':<19} {'(from this dataset)'}")
    print("-" * 95)
    for reg in REGIONS:
        print(f"{reg:<12} {totals[reg][FAULT_RECORD]:<20} {uploaded[reg][FAULT_RECORD]:<21} "
              f"{totals[reg][DRONE_VIDEO]:<19} {uploaded[reg][DRONE_VIDEO]}")
    print()
    return 0


def cmd_clean(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    if data_dir.exists():
        shutil.rmtree(data_dir)
        print(f"Removed {data_dir}")
    else:
        print(f"{data_dir} is already clean.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="FileGrid PPC - fault record (JSON) and drone video (MP4) test-data generator and API uploader."
    )
    parser.add_argument(
        "--data-dir",
        default=str(DEFAULT_DATA_DIR),
        help="Directory where generated files and manifest.json are stored.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_gen = sub.add_parser("generate", help="Generate regional fault JSONs and drone MP4 videos")
    p_gen.add_argument(
        "--regions",
        default="MUNTENIA,BANAT,DOBROGEA",
        help="Comma-separated list of regions (MUNTENIA,BANAT,DOBROGEA)",
    )
    p_gen.add_argument(
        "--profile",
        choices=["normal", "storm"],
        default="normal",
        help="Generation profile: 'normal' day or 'storm' spike (10x fault volume)",
    )
    p_gen.add_argument(
        "--faults-per-region",
        type=int,
        default=None,
        help="Number of JSON fault records per region (default: 2 for normal, 10 for storm)",
    )
    p_gen.add_argument(
        "--videos-per-region",
        type=int,
        default=None,
        help="Number of MP4 drone videos per region (default: 1 for normal, 2 for storm)",
    )
    p_gen.add_argument(
        "--fault-min-mb",
        type=float,
        default=1.2,
        help="Minimum size in MB for generated JSON fault records (PDF: 1-10 MB)",
    )
    p_gen.add_argument(
        "--fault-max-mb",
        type=float,
        default=3.5,
        help="Maximum size in MB for generated JSON fault records (PDF: 1-10 MB)",
    )
    p_gen.add_argument(
        "--video-size-mb",
        type=float,
        default=12.0,
        help="Target size in MB for each standard MP4 drone video (use 100+ for full scale)",
    )
    p_gen.add_argument(
        "--large-video",
        action="store_true",
        help="Also generate a >1 GB MP4 drone video for scalability testing (PDF §4)",
    )
    p_gen.add_argument(
        "--large-video-mb",
        type=float,
        default=1050.0,
        help="Size in MB for the --large-video file (default: 1050 MB = ~1.03 GB)",
    )
    p_gen.add_argument(
        "--large-video-region",
        choices=REGIONS,
        default="MUNTENIA",
        help="Region tag for the >1 GB drone inspection video",
    )
    p_gen.add_argument(
        "--seed",
        type=int,
        default=None,
        help="Optional random seed for reproducible generation",
    )
    p_gen.set_defaults(func=cmd_generate)

    categories = [FAULT_RECORD, DRONE_VIDEO]

    p_ing = sub.add_parser("ingest", help="Upload generated files through the FileGrid API (resumable, idempotent)")
    p_ing.add_argument("--region", choices=REGIONS, default=None, help="Filter by region")
    p_ing.add_argument("--category", choices=categories, default=None, help="Filter by category")
    p_ing.add_argument("--workers", type=int, default=4, help="Files uploaded in parallel (default: 4)")
    p_ing.add_argument(
        "--force",
        action="store_true",
        help="Upload again even if the local record says the file is already in FileGrid",
    )
    p_ing.set_defaults(func=cmd_ingest)

    p_ver = sub.add_parser("verify", help="Download uploaded files through the API and compare SHA-256")
    p_ver.add_argument("--region", choices=REGIONS, default=None, help="Filter by region")
    p_ver.add_argument("--category", choices=categories, default=None, help="Filter by category")
    p_ver.add_argument("--workers", type=int, default=4, help="Files verified in parallel (default: 4)")
    p_ver.set_defaults(func=cmd_verify)

    p_stat = sub.add_parser("status", help="Show the local dataset and what FileGrid holds per region")
    p_stat.set_defaults(func=cmd_status)

    p_clean = sub.add_parser("clean", help="Delete generated files in backend/fault_videos/data")
    p_clean.set_defaults(func=cmd_clean)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
