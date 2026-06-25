#!/usr/bin/env python3
"""
Build NER models (INT8 CPU + FP16 GPU) from a HuggingFace source.

Produces:
  model.onnx      — INT8 dynamic quantization (CPU variant)
  model_fp16.onnx  — FP16 (GPU variant)
  build.json       — provenance metadata

Both variants are typically available as pre-built ONNX from Xenova or
onnx-community repos. This script downloads them directly rather than
re-exporting from PyTorch.

Usage:
    pip install -r scripts/models/requirements.txt
    python scripts/models/build-ner.py \\
        --hf-onnx-repo Xenova/distilbert-base-multilingual-cased-ner-hrl \\
        --output-dir models/onnx/ner
"""

import argparse
import json
import shutil
import sys
from pathlib import Path

from _common import get_tool_versions, posix_relpath, resolve_hf_commit, sha256_file, verify_model


def download_hf_file(repo_id: str, remote_path: str, dest_path: Path, tmp_dir: Path):
    """Download a file from HuggingFace repo to a specific destination.

    Downloads to tmp_dir first (to avoid HF creating subdirectories in the
    output dir), then moves to dest_path.
    """
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
    parser = argparse.ArgumentParser(description="Build NER INT8 + FP16 models")
    parser.add_argument("--hf-model", default="Davlan/distilbert-base-multilingual-cased-ner-hrl",
                        help="Source HuggingFace model ID (for provenance)")
    parser.add_argument("--hf-onnx-repo", required=True,
                        help="HuggingFace ONNX repo (e.g., onnx-community/distilbert-NER-ONNX)")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="Output directory (e.g., models/onnx/ner)")
    parser.add_argument("--int8-path", default="onnx/model_quantized.onnx",
                        help="Path to INT8 model within HF repo")
    parser.add_argument("--fp16-path", default="onnx/model_fp16.onnx",
                        help="Path to FP16 model within HF repo")
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    tmp_dir = output_dir / "_build_tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    # Resolve HF commit hashes
    hf_commit = resolve_hf_commit(args.hf_model)
    onnx_commit = resolve_hf_commit(args.hf_onnx_repo)

    # Download pre-built ONNX variants
    print(f"Downloading models from {args.hf_onnx_repo}...")
    download_hf_file(args.hf_onnx_repo, args.int8_path, output_dir / "model.onnx", tmp_dir)
    download_hf_file(args.hf_onnx_repo, args.fp16_path, output_dir / "model_fp16.onnx", tmp_dir)

    # Download tokenizer and config
    for name in ["tokenizer.json", "tokenizer_config.json", "config.json",
                 "special_tokens_map.json", "vocab.txt"]:
        try:
            download_hf_file(args.hf_onnx_repo, name, output_dir / name, tmp_dir)
        except Exception:
            print(f"  {name} not found in repo, skipping")

    cpu_path = output_dir / "model.onnx"
    gpu_path = output_dir / "model_fp16.onnx"

    # Verify CPU model (FP16 models with ORT fusion nodes cannot load on
    # CPUExecutionProvider — use Gradle verifyModel -Pgpu=true for GPU models)
    verify_model(cpu_path)

    # Compute hashes and write build.json
    cpu_sha = sha256_file(cpu_path)
    gpu_sha = sha256_file(gpu_path)
    tool_versions = get_tool_versions()

    build_json = {
        "source": {
            "hf_model_id": args.hf_model,
            "hf_onnx_repo": args.hf_onnx_repo,
            "hf_commit": hf_commit,
            "hf_onnx_commit": onnx_commit,
        },
        "variants": {
            "model.onnx": {
                "description": "CPU — INT8 dynamic quantization",
                "transformations": [
                    f"Pre-built INT8 ONNX downloaded from {args.hf_onnx_repo}",
                ],
                "output_sha256": cpu_sha,
            },
            "model_fp16.onnx": {
                "description": "GPU — FP16 cast",
                "transformations": [
                    f"Pre-built FP16 ONNX downloaded from {args.hf_onnx_repo}",
                ],
                "output_sha256": gpu_sha,
            },
        },
        "build_command": f"python scripts/models/build-ner.py --hf-onnx-repo {args.hf_onnx_repo} --output-dir {posix_relpath(args.output_dir)}",
        "tool_versions": tool_versions,
    }

    build_path = output_dir / "build.json"
    with open(build_path, "w") as f:
        json.dump(build_json, f, indent=2)
    print(f"\nWrote {build_path}")

    # Cleanup temp dir
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)

    print("\nDone. Verify GPU compatibility with:")
    print(f"  ./gradlew.bat :modules:worker-core:verifyModel -Pmodel={gpu_path} -Pgpu=true")


if __name__ == "__main__":
    main()
