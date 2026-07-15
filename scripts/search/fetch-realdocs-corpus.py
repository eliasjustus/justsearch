#!/usr/bin/env python3
"""Fetch + curate the real-PDF/office-document corpus `mixed/realdocs-v1` (tempdoc 686).

Every existing "document" dataset in the eval harness is pre-extracted text; this script
builds the first corpus of REAL binary documents so live Tika/PDFBox/POI parse pressure
and real extraction behaviour are exercised (tempdoc 686; predecessor gap named by 682
item 1). The corpus is NOT committed (datasets/ is gitignored); what gets committed is
the pinned manifest + recipe under scripts/jseval/666-corpora/realdocs-v1/, conforming
to tempdoc 666's reproducibility conventions.

Sources (license check recorded in tempdoc 686):
  - govdocs1 (digitalcorpora.org): ~1M files crawled from .gov websites, published for
    research and "may be freely redistributed"; zip bundles of 1000 files each. We pin
    zipfiles 000.zip + 001.zip. Realistic size spread incl. large files and genuine
    scanned PDFs; legacy office formats (.doc/.xls/.ppt) exercise POI's HWPF/HSSF/HSLF.
  - NapierOne (napierone.com, Edinburgh Napier University License: free use with
    attribution): modern mixed-file dataset (2021); per-type "tiny" bundles give us
    modern OOXML (.docx/.xlsx/.pptx) and web-era PDFs, which govdocs1 (2010) lacks.

Deterministic selection (seed pinned below): keep ALL large files (the long tail 686
asks for), sample the rest per-extension to fixed caps, sorted-name order before
sampling so re-runs on the same zips pick identical files.

Usage:
  python scripts/search/fetch-realdocs-corpus.py            # build into datasets/mixed/realdocs-v1
  python scripts/search/fetch-realdocs-corpus.py --verify   # verify an existing build against the manifest

First build writes the manifest; when a committed manifest already exists the build
verifies every selected file's sha256 against it and fails on drift.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import random
import re
import sys
import urllib.request
import zipfile
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DATASET_NAME = "mixed/realdocs-v1"
DATASET_DIR = REPO_ROOT / "datasets" / DATASET_NAME
CORPUS_DIR = DATASET_DIR / "corpus-dir"


def _resolve_download_cache_dir() -> Path:
    """Resolve the pinned-archive download cache dir (tempdoc 709).

    `download()` below already treats this directory as a persistent on-disk cache (skips
    re-downloading a zip that's already present) -- but by default that cache lives under
    *this checkout's* `datasets/`, so it doesn't survive worktree teardown and re-downloads
    once per worktree, same as the jseval corpus fetchers this mirrors. Honors the same
    `JUSTSEARCH_DATASET_CACHE` convention `jseval.dataset_cache` uses (empty/"0" disables
    sharing; any other value is used verbatim), defaulting to a directory under the MAIN
    checkout when this is a linked worktree (same gitdir-file walk `_paths.main_repo_root()`
    does — duplicated here in ~10 lines rather than importing the jseval package, since this
    is a standalone script with no other jseval dependency). Falls back to this checkout's
    own `datasets/.download-cache` (today's behavior) when disabled or unresolvable -- fail
    OPEN, never fail closed on cache trouble.
    """
    override = os.environ.get("JUSTSEARCH_DATASET_CACHE")
    if override is not None:
        if override.strip() in ("", "0"):
            return REPO_ROOT / "datasets" / ".download-cache"
        return Path(override) / "realdocs-raw"
    git_path = REPO_ROOT / ".git"
    try:
        if git_path.is_file():
            content = git_path.read_text(encoding="utf-8").strip()
            match = re.match(r"^gitdir:\s*(.+)$", content)
            if match:
                git_dir = (REPO_ROOT / match.group(1)).resolve()
                main_root = git_dir.parents[2]
                if main_root.is_dir():
                    return main_root / "scripts" / "jseval" / "tmp" / "dataset-fetch-cache" / "realdocs-raw"
    except (OSError, IndexError):
        pass
    return REPO_ROOT / "datasets" / ".download-cache"


CACHE_DIR = _resolve_download_cache_dir()
MANIFEST_DIR = REPO_ROOT / "scripts" / "jseval" / "666-corpora" / "realdocs-v1"
MANIFEST_PATH = MANIFEST_DIR / "manifest.json"
RECIPE_PATH = MANIFEST_DIR / "recipe.json"

SEED = 686  # tempdoc number; selection determinism

# Pinned source archives. ETags recorded from the public buckets on 2026-07-10; the
# archive sha256 is computed on first download and pinned in the manifest thereafter.
SOURCES = [
    {
        "id": "govdocs1-000",
        "url": "https://digitalcorpora.s3.amazonaws.com/corpora/files/govdocs1/zipfiles/000.zip",
        "etag_2026_07_10": "3ed534731006c0d91e53d031115ddc9f-59",
        "license": "govdocs1: published for research, freely redistributable (digitalcorpora.org/corpora/file-corpora/files)",
    },
    {
        "id": "govdocs1-001",
        "url": "https://digitalcorpora.s3.amazonaws.com/corpora/files/govdocs1/zipfiles/001.zip",
        "etag_2026_07_10": "d49377eb7d8a4c2ba8cbbfa2f190ad7c-35",
        "license": "govdocs1: published for research, freely redistributable (digitalcorpora.org/corpora/file-corpora/files)",
    },
    {
        "id": "napierone-pdf-tiny",
        "url": "https://s3.eu-north-1.amazonaws.com/napierone.com/NapierOne/Data/PDF/PDF-tiny.zip",
        "etag_2026_07_10": None,
        "license": "NapierOne: Edinburgh Napier University License — free use/redistribution with attribution (napierone.com)",
    },
    {
        "id": "napierone-docx-tiny",
        "url": "https://s3.eu-north-1.amazonaws.com/napierone.com/NapierOne/Data/DOCX/DOCX-tiny.zip",
        "etag_2026_07_10": None,
        "license": "NapierOne: Edinburgh Napier University License — free use/redistribution with attribution (napierone.com)",
    },
    {
        "id": "napierone-xlsx-tiny",
        "url": "https://s3.eu-north-1.amazonaws.com/napierone.com/NapierOne/Data/XLSX/XLSX-tiny.zip",
        "etag_2026_07_10": None,
        "license": "NapierOne: Edinburgh Napier University License — free use/redistribution with attribution (napierone.com)",
    },
    {
        "id": "napierone-pptx-tiny",
        "url": "https://s3.eu-north-1.amazonaws.com/napierone.com/NapierOne/Data/PPTX/PPTX-tiny.zip",
        "etag_2026_07_10": None,
        "license": "NapierOne: Edinburgh Napier University License — free use/redistribution with attribution (napierone.com)",
    },
]

# Selection policy. Large files are always kept (686: "a long tail of large files");
# the rest are sampled per extension. Caps chosen to land ~600 files total, which is
# 686's "hundreds of files" at a size a single instrumented ingest can chew through.
LARGE_FILE_FLOOR_BYTES = 5 * 1024 * 1024
GOVDOCS_CAPS = {".pdf": 250, ".doc": 80, ".xls": 60, ".ppt": 40,
                ".docx": 20, ".xlsx": 20, ".pptx": 20}
NAPIERONE_CAPS = {"napierone-pdf-tiny": 60, "napierone-docx-tiny": 50,
                  "napierone-xlsx-tiny": 50, "napierone-pptx-tiny": 30}
NAPIERONE_EXTS = {".pdf", ".docx", ".xlsx", ".pptx"}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path) -> None:
    if dest.exists():
        print(f"  cached: {dest.name} ({dest.stat().st_size / 1e6:.0f} MB)")
        return
    print(f"  downloading {url} -> {dest.name}")
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(url) as resp, tmp.open("wb") as out:
        while True:
            chunk = resp.read(1 << 22)
            if not chunk:
                break
            out.write(chunk)
    tmp.rename(dest)
    print(f"  done: {dest.name} ({dest.stat().st_size / 1e6:.0f} MB)")


def select_members(zf: zipfile.ZipFile, source_id: str) -> list[zipfile.ZipInfo]:
    """Deterministic selection of zip members for one source archive."""
    rng = random.Random(f"{SEED}:{source_id}")
    members = [m for m in zf.infolist() if not m.is_dir()]
    if source_id.startswith("govdocs1"):
        by_ext: dict[str, list[zipfile.ZipInfo]] = {}
        for m in members:
            ext = Path(m.filename).suffix.lower()
            if ext in GOVDOCS_CAPS:
                by_ext.setdefault(ext, []).append(m)
        selected: list[zipfile.ZipInfo] = []
        for ext, cap in GOVDOCS_CAPS.items():
            pool = sorted(by_ext.get(ext, []), key=lambda m: m.filename)
            large = [m for m in pool if m.file_size >= LARGE_FILE_FLOOR_BYTES]
            small = [m for m in pool if m.file_size < LARGE_FILE_FLOOR_BYTES]
            # Split each source's cap across the two govdocs zips evenly (cap // 2).
            take = max(cap // 2 - len(large), 0)
            selected.extend(large)
            selected.extend(rng.sample(small, min(take, len(small))))
        return selected
    # NapierOne: single-type bundles; flat deterministic sample.
    pool = sorted(
        (m for m in members if Path(m.filename).suffix.lower() in NAPIERONE_EXTS),
        key=lambda m: m.filename,
    )
    cap = NAPIERONE_CAPS[source_id]
    large = [m for m in pool if m.file_size >= LARGE_FILE_FLOOR_BYTES]
    small = [m for m in pool if m.file_size < LARGE_FILE_FLOOR_BYTES]
    take = max(cap - len(large), 0)
    return large + rng.sample(small, min(take, len(small)))


def flatten_name(source_id: str, member_name: str) -> str:
    stem = member_name.replace("\\", "/").split("/")[-1]
    return f"{source_id}--{stem}"


def build(verify_only: bool) -> int:
    prior_manifest = None
    if MANIFEST_PATH.is_file():
        prior_manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        print(f"Found committed manifest ({len(prior_manifest['files'])} files) — will verify against it.")

    if verify_only and prior_manifest is None:
        print("--verify requires a committed manifest; none found.", file=sys.stderr)
        return 2

    if not verify_only:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        CORPUS_DIR.mkdir(parents=True, exist_ok=True)

        archives: dict[str, dict] = {}
        entries: list[dict] = []
        for src in SOURCES:
            dest = CACHE_DIR / f"{src['id']}.zip"
            print(f"[{src['id']}]")
            download(src["url"], dest)
            archives[src["id"]] = {
                "url": src["url"],
                "sha256": sha256_file(dest),
                "size_bytes": dest.stat().st_size,
                "etag_at_pin": src["etag_2026_07_10"],
                "license": src["license"],
            }
            with zipfile.ZipFile(dest) as zf:
                selected = select_members(zf, src["id"])
                print(f"  selected {len(selected)} members")
                for m in selected:
                    out_name = flatten_name(src["id"], m.filename)
                    out_path = CORPUS_DIR / out_name
                    if not out_path.exists():
                        with zf.open(m) as inp, out_path.open("wb") as out:
                            out.write(inp.read())
                    entries.append({
                        "file": out_name,
                        "source": src["id"],
                        "archive_member": m.filename,
                        "size_bytes": out_path.stat().st_size,
                        "sha256": sha256_file(out_path),
                    })

        entries.sort(key=lambda e: e["file"])
        manifest = {
            "dataset": DATASET_NAME,
            "version": 1,
            "seed": SEED,
            "created": str(date.today()),
            "tempdoc": "686-real-pdf-corpus-and-tika-pressure-measurement",
            "selection_policy": {
                "large_file_floor_bytes": LARGE_FILE_FLOOR_BYTES,
                "govdocs_caps": GOVDOCS_CAPS,
                "napierone_caps": NAPIERONE_CAPS,
            },
            "archives": archives,
            "files": entries,
        }

        if prior_manifest is not None:
            drift = _diff_manifests(prior_manifest, manifest)
            if drift:
                print("MANIFEST DRIFT — build does not match committed manifest:", file=sys.stderr)
                for line in drift[:20]:
                    print(f"  {line}", file=sys.stderr)
                return 1
            print("Build matches committed manifest.")
        else:
            MANIFEST_DIR.mkdir(parents=True, exist_ok=True)
            MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            RECIPE_PATH.write_text(json.dumps({
                "method": "fetch-realdocs-corpus",
                "script": "scripts/search/fetch-realdocs-corpus.py",
                "source": "govdocs1(000,001) + NapierOne tiny(PDF,DOCX,XLSX,PPTX)",
                "seed": SEED,
                "n_docs": len(entries),
                "tempdoc": "686",
            }, indent=2) + "\n", encoding="utf-8")
            print(f"Wrote manifest ({len(entries)} files) + recipe to {MANIFEST_DIR}")

        # metadata.json marks this dataset as raw binary files for jseval ingest
        # (no corpus.jsonl, no materialization projection — the files ARE the corpus).
        by_ext: dict[str, int] = {}
        total = 0
        for e in entries:
            ext = Path(e["file"]).suffix.lower()
            by_ext[ext] = by_ext.get(ext, 0) + 1
            total += e["size_bytes"]
        (DATASET_DIR / "metadata.json").write_text(json.dumps({
            "name": DATASET_NAME,
            "raw_files": True,
            "doc_count": len(entries),
            "total_bytes": total,
            "by_extension": dict(sorted(by_ext.items())),
            "manifest": str(MANIFEST_PATH.relative_to(REPO_ROOT)).replace("\\", "/"),
            "created": str(date.today()),
        }, indent=2) + "\n", encoding="utf-8")
        print(f"Corpus ready: {len(entries)} files, {total / 1e6:.0f} MB, {CORPUS_DIR}")
        print(json.dumps(dict(sorted(by_ext.items())), indent=2))
        return 0

    # --verify: hash existing corpus files against the committed manifest.
    missing, bad = [], []
    for e in prior_manifest["files"]:
        p = CORPUS_DIR / e["file"]
        if not p.is_file():
            missing.append(e["file"])
        elif sha256_file(p) != e["sha256"]:
            bad.append(e["file"])
    if missing or bad:
        print(f"VERIFY FAILED: {len(missing)} missing, {len(bad)} hash-mismatched", file=sys.stderr)
        for name in (missing + bad)[:20]:
            print(f"  {name}", file=sys.stderr)
        return 1
    print(f"VERIFY OK: {len(prior_manifest['files'])} files match the committed manifest.")
    return 0


def _diff_manifests(prior: dict, current: dict) -> list[str]:
    diffs = []
    prior_files = {e["file"]: e["sha256"] for e in prior["files"]}
    current_files = {e["file"]: e["sha256"] for e in current["files"]}
    for name in sorted(set(prior_files) - set(current_files)):
        diffs.append(f"missing from build: {name}")
    for name in sorted(set(current_files) - set(prior_files)):
        diffs.append(f"not in manifest: {name}")
    for name in sorted(set(prior_files) & set(current_files)):
        if prior_files[name] != current_files[name]:
            diffs.append(f"hash mismatch: {name}")
    return diffs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verify", action="store_true",
                        help="verify existing corpus files against the committed manifest")
    args = parser.parse_args()
    return build(verify_only=args.verify)


if __name__ == "__main__":
    sys.exit(main())
