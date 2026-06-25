#!/usr/bin/env python3
"""Emit reports/phase10/goldens/<mode>-<budget>/current.json."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
import sys
from types import SimpleNamespace

from hash_manifest_util import resolve_manifest_hash


def _parse_args(argv: list[str]) -> SimpleNamespace:
  # Legacy usage:
  # write_current_json.py <mode> <pipeline_coord> <canonical_profile> <ssot_hash>
  #   <template_ver> <simulate_rel> <paging_rel> <output>
  has_flags = any(arg.startswith("--") for arg in argv)
  if not has_flags and len(argv) == 8:
    (mode, pipeline_coord, canonical_profile, ssot_hash, template_ver, simulate_rel,
     paging_rel, output) = argv
    return SimpleNamespace(
        mode=mode,
        pipeline_coord=pipeline_coord,
        canonical_profile=canonical_profile,
        template_ver=int(template_ver),
        simulate_rel=simulate_rel,
        paging_rel=paging_rel,
        output=Path(output),
        ssot_hash=ssot_hash,
        manifest_override=None)

  parser = argparse.ArgumentParser(
      description="Emit reports/phase10/goldens/<mode>-<budget>/current.json.")
  parser.add_argument("mode")
  parser.add_argument("pipeline_coord")
  parser.add_argument("canonical_profile")
  parser.add_argument("template_ver", type=int)
  parser.add_argument("simulate_rel")
  parser.add_argument("paging_rel")
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
  ssot_hash = resolve_manifest_hash(args.ssot_hash, args.manifest_override)

  payload = {
      "mode": args.mode,
      "budget_profile": args.pipeline_coord,
      "profile_artifact": f"{args.canonical_profile}.json",
      "ssot_manifest_hash": ssot_hash,
      "template_ver": args.template_ver,
      "simulate_log": args.simulate_rel,
      "paging_trace": args.paging_rel,
      "status": "passed",
      "generated_at": datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z"),
  }
  args.output.parent.mkdir(parents=True, exist_ok=True)
  args.output.write_text(json.dumps(payload, indent=2) + "\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
