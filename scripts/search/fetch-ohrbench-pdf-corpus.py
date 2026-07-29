#!/usr/bin/env python3
"""Materialize `mixed/ohr-bench-pdf-live` — the OHR-Bench pages as REAL PDF BYTES.

Why this exists (tempdoc 790 §G): `mixed/ohr-bench-tika-pdf` ships *pre-extracted
text* (`corpus.jsonl`), so ingesting it never touches the Worker's extraction chain.
The 790 dropout fallback re-extracts *bytes*; measuring its recovery needs the source
PDFs. This script rebuilds the same 1000 documents (identical `_id` space, identical
queries/qrels) as single-page PDF files, ingested through the live extraction path via
jseval's `raw_files` corpus mode (`ingest.py:_raw_corpus_dir`, tempdoc 686).

Source: HuggingFace `opendatalab/OHR-Bench` -> `pdfs.zip` (1.52 GB, 1261 PDFs).
License: CC-BY-4.0 (research use). Attribution: "OCR Hinders RAG", arXiv 2412.02592.

Doc-id -> PDF mapping (verified, not assumed):
  corpus `_id` = "<domain>/<doc_name>_p<page_idx>", lowercased by the corpus writer
  (`retriever._filename_to_doc_id` lowercases structurally on Windows).
  zip member  = "<domain>/<DocName>.pdf" (case-preserving) -> matched case-insensitively.
  `page_idx` is 0-BASED (probed against `ohr-bench-clean` ground truth: page `page_idx`
  is always the best text match among {p-1, p, p+1}, and `page_idx < page_count` for all
  1000 documents).

Output layout (jseval raw-files dataset):
  datasets/mixed/ohr-bench-pdf-live/
    metadata.json         {"raw_files": true, ...}   <- routes ingest at the real files
    corpus-dir/<urlquoted_doc_id>.pdf                <- 1000 single-page PDFs
    queries.jsonl                                    <- byte-identical copy of the source arm
    qrels/test.tsv                                   <- byte-identical copy of the source arm

Usage:
  python scripts/search/fetch-ohrbench-pdf-corpus.py            # build
  python scripts/search/fetch-ohrbench-pdf-corpus.py --verify   # hash against committed manifest
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import urllib.parse
import urllib.request
import zipfile
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

DATASET_NAME = "mixed/ohr-bench-pdf-live"
DATASET_DIR = REPO_ROOT / "datasets" / "mixed" / "ohr-bench-pdf-live"
CORPUS_DIR = DATASET_DIR / "corpus-dir"
_ID_SOURCE_REL = Path("datasets") / "mixed" / "ohr-bench-tika-pdf"

MANIFEST_DIR = REPO_ROOT / "scripts" / "jseval" / "666-corpora" / "ohr-bench-pdf-live"
MANIFEST_PATH = MANIFEST_DIR / "manifest.json"
RECIPE_PATH = MANIFEST_DIR / "recipe.json"

ZIP_URL = "https://huggingface.co/datasets/opendatalab/OHR-Bench/resolve/main/pdfs.zip"
ZIP_SHA256 = "f9bc65f383172c4ea47940c47dfab01dd36c03a120bc0450d7a962917098c783"
ZIP_BYTES = 1516951813

_ID_RE = re.compile(r"^(?P<doc>.+)_p(?P<page>\d+)$")


def _main_repo_root() -> Path:
    """Main checkout root — the shared dataset caches live there, not in a worktree."""
    try:
        sys.path.insert(0, str(REPO_ROOT / "scripts" / "jseval"))
        from jseval._paths import main_repo_root  # type: ignore

        return Path(main_repo_root())
    except Exception:
        return REPO_ROOT


def cache_path() -> Path:
    """pdfs.zip cache location — the repo's shared dataset-fetch cache (tempdoc 709)."""
    rel = Path("scripts") / "jseval" / "tmp" / "dataset-fetch-cache" / "ohr-bench" / "pdfs.zip"
    shared = _main_repo_root() / rel
    if shared.is_file():
        return shared
    local = REPO_ROOT / rel
    return local if local.is_file() else shared


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_zip(verify_hash: bool) -> Path:
    dest = cache_path()
    if dest.is_file() and dest.stat().st_size == ZIP_BYTES:
        print(f"Using cached {dest} ({dest.stat().st_size / 1e9:.2f} GB)")
    else:
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"Downloading {ZIP_URL} -> {dest} ({ZIP_BYTES / 1e9:.2f} GB)")
        tmp = dest.with_suffix(".part")
        with urllib.request.urlopen(ZIP_URL) as resp, tmp.open("wb") as out:
            shutil.copyfileobj(resp, out, length=1 << 22)
        tmp.replace(dest)
    if verify_hash:
        actual = sha256_file(dest)
        if actual != ZIP_SHA256:
            raise SystemExit(f"pdfs.zip sha256 mismatch: {actual} != {ZIP_SHA256}")
        print("pdfs.zip sha256 OK")
    return dest


def read_doc_ids(id_source: Path) -> list[str]:
    corpus = id_source / "corpus.jsonl"
    if not corpus.is_file():
        raise SystemExit(
            f"Doc-id source not found: {corpus}\n"
            "Rebuild it first with scripts/search/convert-ohrbench-to-beir.py."
        )
    ids = []
    for line in corpus.read_text(encoding="utf-8").splitlines():
        if line.strip():
            ids.append(json.loads(line)["_id"])
    return ids


def build(args: argparse.Namespace) -> int:
    try:
        import fitz  # PyMuPDF
    except ImportError:
        raise SystemExit("PyMuPDF (fitz) is required: it splits the source PDFs into pages.")

    zip_path = ensure_zip(verify_hash=not args.skip_hash)
    doc_ids = read_doc_ids(args.id_source)
    print(f"{len(doc_ids)} document ids from {args.id_source}")

    zf = zipfile.ZipFile(zip_path)
    members = {n.lower(): n for n in zf.namelist() if n.lower().endswith(".pdf")}

    # Group the requested pages by source PDF so each source is opened exactly once.
    wanted: dict[str, list[tuple[str, int]]] = {}
    unmapped: list[str] = []
    for doc_id in doc_ids:
        m = _ID_RE.match(doc_id)
        if not m:
            unmapped.append(doc_id)
            continue
        member = members.get(m.group("doc").lower() + ".pdf")
        if member is None:
            unmapped.append(doc_id)
            continue
        wanted.setdefault(member, []).append((doc_id, int(m.group("page"))))
    if unmapped:
        print(f"ERROR: {len(unmapped)} doc ids have no PDF in pdfs.zip", file=sys.stderr)
        for d in unmapped[:20]:
            print(f"  {d}", file=sys.stderr)
        return 1
    print(f"{len(wanted)} source PDFs carry the {len(doc_ids)} requested pages")

    CORPUS_DIR.mkdir(parents=True, exist_ok=True)
    entries: list[dict] = []
    out_of_range: list[str] = []
    for i, (member, pages) in enumerate(sorted(wanted.items())):
        raw = zf.read(member)
        src = fitz.open(stream=raw, filetype="pdf")
        for doc_id, page_idx in pages:
            if page_idx >= src.page_count:
                out_of_range.append(f"{doc_id} (page {page_idx} >= {src.page_count})")
                continue
            out_name = urllib.parse.quote(doc_id, safe="") + ".pdf"
            out_path = CORPUS_DIR / out_name
            single = fitz.open()
            single.insert_pdf(src, from_page=page_idx, to_page=page_idx)
            single.save(str(out_path), garbage=4, deflate=True)
            single.close()
            entries.append({
                "doc_id": doc_id,
                "file": out_name,
                "source_pdf": member,
                "page_idx": page_idx,
                "size_bytes": out_path.stat().st_size,
                "sha256": sha256_file(out_path),
            })
        src.close()
        if (i + 1) % 50 == 0:
            print(f"  [{i + 1}/{len(wanted)}] {len(entries)} pages written")
    if out_of_range:
        print(f"ERROR: {len(out_of_range)} pages out of range", file=sys.stderr)
        for d in out_of_range[:20]:
            print(f"  {d}", file=sys.stderr)
        return 1

    entries.sort(key=lambda e: e["doc_id"])

    # queries + qrels are reused BYTE-IDENTICALLY from the paired text arm: the
    # retrieval comparison is only meaningful if the question set is the same object.
    (DATASET_DIR / "qrels").mkdir(parents=True, exist_ok=True)
    for rel in ("queries.jsonl", "qrels/test.tsv"):
        shutil.copyfile(args.id_source / rel, DATASET_DIR / rel)

    manifest = {
        "dataset": DATASET_NAME,
        "version": "1.0",
        "created": str(date.today()),
        "tempdoc": "790",
        "source": {
            "repo": "opendatalab/OHR-Bench",
            "file": "pdfs.zip",
            "sha256": ZIP_SHA256,
            "size_bytes": ZIP_BYTES,
            "license": "CC-BY-4.0",
            "paper": "OCR Hinders RAG, arXiv 2412.02592",
        },
        "id_source": _ID_SOURCE_REL.as_posix(),
        "page_index_base": 0,
        "splitter": "PyMuPDF insert_pdf(from_page=to_page=page_idx), garbage=4 deflate=True",
        "files": entries,
    }

    prior = json.loads(MANIFEST_PATH.read_text(encoding="utf-8")) if MANIFEST_PATH.is_file() else None
    if args.check_manifest and prior is not None:
        drift = _diff_manifests(prior, manifest)
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
            "method": "fetch-ohrbench-pdf-corpus",
            "script": "scripts/search/fetch-ohrbench-pdf-corpus.py",
            "source": "opendatalab/OHR-Bench pdfs.zip (CC-BY-4.0) split to single pages",
            "id_source": "datasets/mixed/ohr-bench-tika-pdf (same _id space, byte-identical queries/qrels)",
            "n_docs": len(entries),
            "n_queries": 962,
            "tempdoc": "790",
        }, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote manifest ({len(entries)} files) + recipe to {MANIFEST_DIR}")

    total = sum(e["size_bytes"] for e in entries)
    (DATASET_DIR / "metadata.json").write_text(json.dumps({
        "name": DATASET_NAME,
        "raw_files": True,
        "doc_count": len(entries),
        "total_bytes": total,
        "by_extension": {".pdf": len(entries)},
        "source": "OHR-Bench (opendatalab/OHR-Bench) — original single-page PDF BYTES",
        "license": "CC-BY-4.0 (research use; attribution: OCR Hinders RAG, arXiv 2412.02592)",
        "variant": "pdf-live (real bytes through the live extraction chain)",
        "notes": (
            "Same 1000 _ids and byte-identical queries/qrels as mixed/ohr-bench-tika-pdf, "
            "but the documents are the source PDFs, so ingest exercises the Worker's real "
            "extraction chain (tempdoc 790 dropout fallback) instead of replaying "
            "pre-extracted text."
        ),
        "manifest": str(MANIFEST_PATH.relative_to(REPO_ROOT)).replace("\\", "/"),
        "created": str(date.today()),
    }, indent=2) + "\n", encoding="utf-8")
    print(f"Corpus ready: {len(entries)} PDFs, {total / 1e6:.0f} MB, {CORPUS_DIR}")
    return 0


def verify() -> int:
    if not MANIFEST_PATH.is_file():
        print(f"No committed manifest at {MANIFEST_PATH}", file=sys.stderr)
        return 1
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    missing, bad = [], []
    for e in manifest["files"]:
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
    print(f"VERIFY OK: {len(manifest['files'])} files match the committed manifest.")
    return 0


def _diff_manifests(prior: dict, current: dict) -> list[str]:
    diffs = []
    prior_files = {e["file"]: e["sha256"] for e in prior["files"]}
    current_files = {e["file"]: e["sha256"] for e in current["files"]}
    for name in sorted(set(prior_files) - set(current_files)):
        diffs.append(f"missing in build: {name}")
    for name in sorted(set(current_files) - set(prior_files)):
        diffs.append(f"unexpected in build: {name}")
    for name in sorted(set(prior_files) & set(current_files)):
        if prior_files[name] != current_files[name]:
            diffs.append(f"hash differs: {name}")
    return diffs


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--id-source", type=Path, default=None,
                    help="Dataset dir supplying the doc-id space + queries/qrels")
    ap.add_argument("--verify", action="store_true", help="Hash the built corpus against the committed manifest")
    ap.add_argument("--check-manifest", action="store_true", help="Build and fail on drift instead of rewriting the manifest")
    ap.add_argument("--skip-hash", action="store_true", help="Skip the 1.5 GB pdfs.zip sha256 check")
    args = ap.parse_args()
    if args.id_source is None:
        local = REPO_ROOT / _ID_SOURCE_REL
        args.id_source = local if local.is_dir() else _main_repo_root() / _ID_SOURCE_REL
    if args.verify:
        return verify()
    return build(args)


if __name__ == "__main__":
    sys.exit(main())
