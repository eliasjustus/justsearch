#!/usr/bin/env python3
"""Update reports/phase10/goldens/manifest.json with SSOT metadata."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
import sys
from types import SimpleNamespace

from hash_manifest_util import resolve_manifest_hash


def _parse_args(argv: list[str]) -> SimpleNamespace:
  # Legacy usage: update_manifest.py <manifest_path> <canonical_profile> <ssot_hash> <template_ver>
  has_flags = any(arg.startswith("--") for arg in argv)
  if not has_flags and len(argv) == 4:
    manifest_path, canonical_profile, ssot_hash, template_ver = argv
    return SimpleNamespace(
        manifest_path=Path(manifest_path),
        canonical_profile=canonical_profile,
        template_ver=int(template_ver),
        ssot_hash=ssot_hash,
        manifest_override=None)

  parser = argparse.ArgumentParser(
      description="Update reports/phase10/goldens/manifest.json with SSOT metadata.")
  parser.add_argument("manifest_path", type=Path)
  parser.add_argument("canonical_profile")
  parser.add_argument("template_ver", type=int)
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
  manifest_path: Path = args.manifest_path
  canonical_profile: str = args.canonical_profile
  template_ver: int = args.template_ver
  ssot_hash = resolve_manifest_hash(args.ssot_hash, args.manifest_override)

  data = {
      "budget_profiles": [],
  }
  if manifest_path.exists():
    data.update(json.loads(manifest_path.read_text()))

  profiles = set(data.get("budget_profiles") or [])
  profiles.add(canonical_profile)
  data["budget_profiles"] = sorted(profiles)
  data["template_ver"] = template_ver
  data["ssot_manifest_hash"] = ssot_hash
  data["generated_at"] = datetime.now(tz=timezone.utc).isoformat().replace("+00:00", "Z")

  manifest_path.parent.mkdir(parents=True, exist_ok=True)
  manifest_path.write_text(json.dumps(data, indent=2) + "\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
