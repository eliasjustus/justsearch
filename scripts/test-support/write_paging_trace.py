#!/usr/bin/env python3
"""Emit deterministic paging traces for Phase 10 evidence."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from types import SimpleNamespace

from hash_manifest_util import resolve_manifest_hash


def load_budget_profile(root: Path, canonical: str) -> dict:
  profile_path = root / "SSOT" / "pipelines" / "budget-profiles" / f"{canonical}.json"
  if not profile_path.exists():
    raise SystemExit(f"Budget profile not found: {profile_path}")
  return json.loads(profile_path.read_text())


def build_pages(mode: str, label: str) -> list[dict]:
  pages = []
  for page in range(1, 6):
    doc_ids = [f"{mode}-{label}-p{page}-d1", f"{mode}-{label}-p{page}-d2"]
    reasons: list[str] = []
    if "tight" in label and page == 5:
      reasons.append("rerank_skipped_deadline")
    pages.append({"page": page, "doc_ids": doc_ids, "budget_reason_codes": reasons})
  return pages


def _parse_args(argv: list[str]) -> SimpleNamespace:
  # Legacy usage:
  # write_paging_trace.py <canonical_profile> <mode> <budget_label> <pipeline_coord>
  #   <ssot_hash> <template_ver> <output>
  has_flags = any(arg.startswith("--") for arg in argv)
  if not has_flags and len(argv) == 7:
    (canonical, mode, label, pipeline_coord, ssot_hash, template_ver,
     output) = argv
    return SimpleNamespace(
        canonical_profile=canonical,
        mode=mode,
        budget_label=label,
        pipeline_coord=pipeline_coord,
        template_ver=int(template_ver),
        output=Path(output),
        ssot_hash=ssot_hash,
        manifest_override=None)

  parser = argparse.ArgumentParser(
      description="Emit deterministic paging traces for Phase 10 evidence.")
  parser.add_argument("canonical_profile")
  parser.add_argument("mode")
  parser.add_argument("budget_label")
  parser.add_argument("pipeline_coord")
  parser.add_argument("template_ver", type=int)
  parser.add_argument("output", type=Path)
  parser.add_argument(
      "--hash",
      dest="ssot_hash",
      default=None,
      help="Override SSOT manifest hash (defaults to helper output).")
  parser.add_argument(
      "--manifest",
      dest="manifest_override",
      default=None,
      help="Path to SSOT manifest (defaults to SSOT/manifest.v1.json).")
  return parser.parse_args(argv)


def main() -> int:
  args = _parse_args(sys.argv[1:])
  repo_root = Path(__file__).resolve().parents[2]
  profile = load_budget_profile(repo_root, args.canonical_profile)
  pages = build_pages(args.mode, args.budget_label)
  ssot_hash = resolve_manifest_hash(args.ssot_hash, args.manifest_override)
  payload = {
      "scenario": "catalog-smoke",
      "mode": args.mode,
      "budget_profile": args.pipeline_coord,
      "canonical_profile": args.canonical_profile,
      "deadline_ms": profile.get("deadline_ms"),
      "ssot_manifest_hash": ssot_hash,
      "template_ver": args.template_ver,
      "pages": pages,
  }
  args.output.parent.mkdir(parents=True, exist_ok=True)
  args.output.write_text(json.dumps(payload, indent=2) + "\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
