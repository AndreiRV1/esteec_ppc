#!/usr/bin/env python3
"""
CLI entrypoint for FileGrid PPC Fault Records (JSON) & Drone Inspection Videos (MP4)
data generation, regional tagging, and `make dev` storage ingestion.

Usage examples:
  python3 backend/fault_videos/fault_videos.py generate
  python3 backend/fault_videos/fault_videos.py generate --profile storm --regions MUNTENIA
  python3 backend/fault_videos/fault_videos.py generate --large-video --large-video-mb 1050
  python3 backend/fault_videos/fault_videos.py ingest
  python3 backend/fault_videos/fault_videos.py verify
  python3 backend/fault_videos/fault_videos.py status
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

# Allow running both as `python3 backend/fault_videos/fault_videos.py` and as module
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from backend.fault_videos.catalog import REGIONS
from backend.fault_videos.generator import generate_dataset, rebuild_manifest
from backend.fault_videos.storage_client import FileGridAcquisitionService

DEFAULT_DATA_DIR = SCRIPT_DIR / "data"


def _print_manifest_summary(manifest: Dict[str, Any], data_dir: Path) -> None:
    print(f"\n=== Generated Dataset Summary ({data_dir.relative_to(REPO_ROOT)}) ===")
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


def cmd_ingest(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    manifest_path = data_dir / "manifest.json"
    if not manifest_path.exists():
        print("No manifest.json found; generating default dataset first...")
        manifest = generate_dataset(output_dir=data_dir)
    else:
        manifest = rebuild_manifest(data_dir)

    items: List[Dict[str, Any]] = manifest.get("items", [])
    if args.region:
        reg_filter = args.region.upper()
        items = [it for it in items if it.get("region") == reg_filter]
    if args.category:
        cat_filter = args.category.upper()
        items = [it for it in items if it.get("category") == cat_filter]

    if not items:
        print("No matching files to ingest.")
        return 0

    svc = FileGridAcquisitionService()
    print(f"Ingesting {len(items)} file(s) into FileGrid storages (`make dev` stack)...")
    print(
        f"{'REGION':<10} {'CATEGORY':<15} {'BACKEND':<10} {'SIZE (MB)':<11} {'STATUS':<15} {'FILENAME'}"
    )
    print("-" * 95)

    for entry in items:
        res = svc.ingest_entry(data_dir, entry)
        sz_mb = res["size_bytes"] / (1024 * 1024)
        fname = entry["file"]["original_filename"]
        print(
            f"{res['region']:<10} {res['category']:<15} {res['backend']:<10} "
            f"{sz_mb:<11.2f} {res['status']:<15} {fname}"
        )

    print("-" * 95)
    print("Ingestion complete.")
    return 0


def cmd_verify(args: argparse.Namespace) -> int:
    svc = FileGridAcquisitionService()
    results = svc.verify_all_stored(region_filter=args.region)
    if not results:
        print("No stored FAULT_RECORD or DRONE_VIDEO files found in database.")
        return 0

    print(
        f"\n{'REGION':<10} {'CATEGORY':<15} {'BACKEND':<10} {'SIZE (MB)':<11} {'INTEGRITY':<12} {'SHA-256 (prefix)'}"
    )
    print("-" * 88)
    ok_count = 0
    for r in results:
        sz_mb = int(r.get("size_bytes", 0)) / (1024 * 1024)
        verified = bool(r.get("verified"))
        if verified:
            ok_count += 1
        status_str = "OK (MATCH)" if verified else "CORRUPTED"
        sha_prefix = (r.get("actual_sha256") or r.get("error") or "")[:20]
        print(
            f"{r['region']:<10} {r['category']:<15} {r['backend']:<10} "
            f"{sz_mb:<11.2f} {status_str:<12} {sha_prefix}..."
        )
    print("-" * 88)
    print(f"Verified {ok_count}/{len(results)} stored file(s) bit-for-bit.\n")
    return 0 if ok_count == len(results) else 1


def cmd_status(args: argparse.Namespace) -> int:
    data_dir = Path(args.data_dir).resolve()
    if data_dir.exists():
        manifest = rebuild_manifest(data_dir)
        _print_manifest_summary(manifest, data_dir)
    else:
        print(f"Local data directory ({data_dir}) does not exist yet. Run `generate` first.")

    svc = FileGridAcquisitionService()
    try:
        with svc._pg() as pg:
            policies = pg.query(
                "SELECT region, category, backend, target, encrypt_at_rest "
                "FROM storage_policy "
                "WHERE category IN ('FAULT_RECORD', 'DRONE_VIDEO') "
                "ORDER BY region, category;"
            )
            counts = pg.query(
                "SELECT region, category, backend, status, count(*) AS cnt, "
                "coalesce(round(sum(size_bytes) / 1048576.0, 2), 0) AS total_mb "
                "FROM stored_file "
                "WHERE category IN ('FAULT_RECORD', 'DRONE_VIDEO') "
                "GROUP BY region, category, backend, status "
                "ORDER BY region, category;"
            )
        print("=== Active Storage Policies in PostgreSQL (`filegrid.storage_policy`) ===")
        for p in policies:
            print(
                f"  {p['region']:<10} | {p['category']:<14} -> {p['backend']:<8} "
                f"(target={p['target']}, encrypt={p['encrypt_at_rest']})"
            )
        print("\n=== Stored Files in PostgreSQL (`filegrid.stored_file`) ===")
        if not counts:
            print("  (no files ingested yet)")
        for c in counts:
            print(
                f"  {c['region']:<10} | {c['category']:<14} | {c['backend']:<8} | "
                f"status={c['status']:<9} | count={c['cnt']} | {c['total_mb']} MB"
            )
        print()
    except Exception as exc:
        print(f"(Database not reachable on localhost:{svc.pg_port} — start with `make dev`: {exc})")
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
        description="FileGrid PPC — Fault Records (JSON) & Drone Videos (MP4) generator and ingestion tool."
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

    p_ing = sub.add_parser("ingest", help="Ingest generated files into `make dev` storages per policy")
    p_ing.add_argument("--region", choices=REGIONS, default=None, help="Filter by region")
    p_ing.add_argument(
        "--category",
        choices=["FAULT_RECORD", "DRONE_VIDEO"],
        default=None,
        help="Filter by category",
    )
    p_ing.set_defaults(func=cmd_ingest)

    p_ver = sub.add_parser("verify", help="Verify SHA-256 integrity of all stored files in `make dev`")
    p_ver.add_argument("--region", choices=REGIONS, default=None, help="Filter by region")
    p_ver.set_defaults(func=cmd_verify)

    p_stat = sub.add_parser("status", help="Show local dataset summary and `make dev` storage status")
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
