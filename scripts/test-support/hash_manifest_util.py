#!/usr/bin/env python3
"""Helper to compute SSOT manifest hashes via the shared Node helper."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import Optional

REPO_ROOT = Path(__file__).resolve().parents[2]
HELPER = REPO_ROOT / "SSOT" / "tools" / "hash-manifest.mjs"
_ENV_MANIFEST = os.environ.get("SSOT_MANIFEST_PATH")
DEFAULT_MANIFEST = Path(_ENV_MANIFEST) if _ENV_MANIFEST else REPO_ROOT / "SSOT" / "manifest.v1.json"


def _run_helper(manifest_path: Path, short: bool) -> str:
  cmd = ["node", str(HELPER)]
  if short:
    cmd.append("--short")
  cmd.append(str(manifest_path))
  try:
    result = subprocess.run(
        cmd, check=True, capture_output=True, text=True)
  except FileNotFoundError as exc:
    raise RuntimeError("`node` is required to run SSOT/tools/hash-manifest.mjs") from exc
  except subprocess.CalledProcessError as exc:
    raise RuntimeError(
        f"hash-manifest helper failed with exit code {exc.returncode}: {exc.stderr}") from exc

  value = (result.stdout or "").strip()
  if not value:
    raise RuntimeError("hash-manifest helper produced no output")
  return value


def resolve_manifest_hash(
    override: Optional[str] = None,
    manifest_override: Optional[str | Path] = None,
    *,
    short: bool = True) -> str:
  """Return the SSOT manifest hash, reusing the Node helper for consistency."""
  if override:
    return override.strip()
  manifest = Path(manifest_override) if manifest_override else DEFAULT_MANIFEST
  if not manifest.exists():
    raise RuntimeError(f"Manifest not found: {manifest}")
  return _run_helper(manifest, short=short)
