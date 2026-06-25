#!/usr/bin/env python3
"""
Verify SHA-256 integrity and schema of model build.json files.

Reads build.json from each model directory and:
1. Validates the schema (required fields, types)
2. Checks that the actual file hash matches the recorded output_sha256

Catches corruption, accidental overwrites, LFS pointer issues, and
malformed provenance metadata.

This is NOT a reproducibility check — ONNX builds are not deterministic
across environments. This only verifies that committed files haven't
drifted from their recorded hashes.

Usage:
    python scripts/models/check-integrity.py [--models-dir models/]

Exit codes:
    0 — all checks pass
    1 — one or more failures
    2 — missing files or fatal error
"""

import argparse
import json
import sys
from pathlib import Path

from _common import sha256_file

# Required top-level keys and their expected types
REQUIRED_TOP_KEYS = {
    "source": dict,
    "variants": dict,
    "tool_versions": dict,
}

# Required keys within source
REQUIRED_SOURCE_KEYS = {"hf_model_id": str}

# Required keys within each variant
REQUIRED_VARIANT_KEYS = {
    "description": str,
    "transformations": list,
    "output_sha256": str,
}


def validate_schema(build: dict, model_dir: Path) -> list[str]:
    """Validate build.json schema. Returns list of error messages."""
    errors = []
    rel = model_dir.name

    # Top-level keys
    for key, expected_type in REQUIRED_TOP_KEYS.items():
        if key not in build:
            errors.append(f"  SCHEMA: {rel}/build.json missing required key '{key}'")
        elif not isinstance(build[key], expected_type):
            errors.append(f"  SCHEMA: {rel}/build.json '{key}' should be {expected_type.__name__}, got {type(build[key]).__name__}")

    # Source keys
    source = build.get("source", {})
    if isinstance(source, dict):
        for key, expected_type in REQUIRED_SOURCE_KEYS.items():
            if key not in source:
                errors.append(f"  SCHEMA: {rel}/build.json source missing '{key}'")
            elif not isinstance(source[key], expected_type):
                errors.append(f"  SCHEMA: {rel}/build.json source.{key} should be {expected_type.__name__}")

    # Variant keys
    variants = build.get("variants", {})
    if isinstance(variants, dict):
        for filename, info in variants.items():
            if not isinstance(info, dict):
                errors.append(f"  SCHEMA: {rel}/build.json variant '{filename}' should be dict")
                continue
            for key, expected_type in REQUIRED_VARIANT_KEYS.items():
                if key not in info:
                    errors.append(f"  SCHEMA: {rel}/build.json variant '{filename}' missing '{key}'")
                elif not isinstance(info[key], expected_type):
                    errors.append(f"  SCHEMA: {rel}/build.json variant '{filename}'.{key} should be {expected_type.__name__}")

    return errors


def check_hashes(build: dict, model_dir: Path) -> list[str]:
    """Check SHA-256 hashes of model files. Returns list of error messages."""
    variants = build.get("variants", {})
    errors = []

    for filename, info in variants.items():
        expected_sha = info.get("output_sha256")
        if not expected_sha:
            continue

        file_path = model_dir / filename
        if not file_path.exists():
            errors.append(f"  MISSING: {file_path}")
            continue

        # Check for LFS pointer (small text file instead of binary)
        size = file_path.stat().st_size
        if size < 200:
            with open(file_path, "rb") as f:
                header = f.read(50)
            if b"version https://git-lfs.github.com" in header:
                errors.append(f"  LFS POINTER: {file_path} (run 'git lfs pull')")
                continue

        actual_sha = sha256_file(file_path)
        if actual_sha != expected_sha:
            errors.append(
                f"  MISMATCH: {filename}\n"
                f"    expected: {expected_sha}\n"
                f"    actual:   {actual_sha}"
            )
        else:
            print(f"  OK: {filename} ({size / (1024*1024):.1f} MB)")

    return errors


def check_directory(model_dir: Path) -> list[str]:
    """Check one model directory. Returns list of error messages."""
    build_json_path = model_dir / "build.json"
    if not build_json_path.exists():
        return []

    try:
        with open(build_json_path) as f:
            build = json.load(f)
    except (json.JSONDecodeError, OSError) as e:
        return [f"{model_dir}: invalid build.json: {e}"]

    errors = []
    errors.extend(validate_schema(build, model_dir))
    errors.extend(check_hashes(build, model_dir))
    return errors


def find_model_dirs(root: Path) -> list[Path]:
    """Find all directories containing build.json under root."""
    return sorted(d.parent for d in root.rglob("build.json"))


def main():
    parser = argparse.ArgumentParser(description="Verify model file integrity and build.json schema")
    parser.add_argument("--models-dir", type=Path, default=Path("models"),
                        help="Root models directory (default: models/)")
    args = parser.parse_args()

    models_dir = args.models_dir.resolve()
    if not models_dir.exists():
        print(f"ERROR: {models_dir} does not exist", file=sys.stderr)
        sys.exit(2)

    model_dirs = find_model_dirs(models_dir)
    if not model_dirs:
        print(f"No build.json files found under {models_dir}")
        sys.exit(0)

    all_errors = []
    for model_dir in model_dirs:
        rel = model_dir.relative_to(models_dir)
        print(f"\n{rel}/")
        errors = check_directory(model_dir)
        all_errors.extend(errors)

    if all_errors:
        print(f"\n{'='*60}")
        print(f"INTEGRITY CHECK FAILED — {len(all_errors)} issue(s):\n")
        for err in all_errors:
            print(err)
        sys.exit(1)
    else:
        print(f"\nAll model files match their build.json hashes.")
        sys.exit(0)


if __name__ == "__main__":
    main()
