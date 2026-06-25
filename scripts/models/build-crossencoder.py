#!/usr/bin/env python3
"""
Build cross-encoder models (reranker, citation-scorer) from HuggingFace.

These are single-variant CPU-only models downloaded as pre-built INT8 ONNX.
No local transformation beyond downloading.

Produces:
  model.onnx   — INT8 model
  build.json   — provenance metadata

Usage:
    pip install -r scripts/models/requirements.txt

    # Reranker (GTE-ModernBERT)
    python scripts/models/build-crossencoder.py \\
        --hf-model Alibaba-NLP/gte-reranker-modernbert-base \\
        --output-dir models/onnx/reranker

    # Citation scorer (MiniLM-L2)
    python scripts/models/build-crossencoder.py \\
        --hf-model Xenova/ms-marco-MiniLM-L-2-v2 \\
        --output-dir models/onnx/citation-scorer
"""

import argparse
import json
import shutil
import sys
from pathlib import Path

from _common import get_tool_versions, posix_relpath, resolve_hf_commit, sha256_file, verify_model


def download_hf_file(repo_id: str, remote_path: str, dest_path: Path, tmp_dir: Path):
    """Download a file from HuggingFace repo to a specific destination."""
    from huggingface_hub import hf_hub_download

    print(f"  Downloading {remote_path} -> {dest_path.name}...")
    downloaded = hf_hub_download(
        repo_id=repo_id,
        filename=remote_path,
        local_dir=tmp_dir,
    )
    src = Path(downloaded)
    if src != dest_path:
        shutil.move(str(src), str(dest_path))


def main():
    parser = argparse.ArgumentParser(description="Build cross-encoder model (reranker or citation-scorer)")
    parser.add_argument("--hf-model", required=True,
                        help="HuggingFace model/ONNX repo ID")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="Output directory (e.g., models/onnx/reranker)")
    parser.add_argument("--model-file", default="onnx/model_quantized.onnx",
                        help="Path to INT8 model within HF repo (default: onnx/model_quantized.onnx)")
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    tmp_dir = output_dir / "_build_tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    # Resolve HF commit hash
    hf_commit = resolve_hf_commit(args.hf_model)

    # Download model
    print(f"Downloading model from {args.hf_model}...")
    model_path = output_dir / "model.onnx"
    download_hf_file(args.hf_model, args.model_file, model_path, tmp_dir)

    # Download tokenizer
    for name in ["tokenizer.json", "tokenizer_config.json", "config.json",
                 "special_tokens_map.json", "vocab.txt"]:
        try:
            download_hf_file(args.hf_model, name, output_dir / name, tmp_dir)
        except Exception:
            print(f"  {name} not found in repo, skipping")

    # Verify
    verify_model(model_path)

    # Compute hash and write build.json
    model_sha = sha256_file(model_path)
    tool_versions = get_tool_versions()

    build_json = {
        "source": {
            "hf_model_id": args.hf_model,
            "hf_commit": hf_commit,
        },
        "variants": {
            "model.onnx": {
                "description": "CPU — INT8 (single variant, no GPU-specific model)",
                "transformations": [
                    f"Pre-built INT8 ONNX downloaded from {args.hf_model}",
                ],
                "output_sha256": model_sha,
            },
        },
        "build_command": f"python scripts/models/build-crossencoder.py --hf-model {args.hf_model} --output-dir {posix_relpath(args.output_dir)}",
        "tool_versions": tool_versions,
    }

    build_path = output_dir / "build.json"
    with open(build_path, "w") as f:
        json.dump(build_json, f, indent=2)
    print(f"\nWrote {build_path}")

    # Cleanup
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)

    print("\nDone.")


if __name__ == "__main__":
    main()
