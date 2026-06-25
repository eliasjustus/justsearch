#!/usr/bin/env python3
"""
Build embedding models (Q4 GPU + INT8 CPU) from a HuggingFace source.

Produces:
  model.onnx      — Q4 4-bit quantization (GPU variant)
  model_int8.onnx  — INT8 dynamic quantization (CPU variant)
  build.json       — provenance metadata

The Q4 variant is downloaded as split external-data files (model_q4.onnx +
model_q4.onnx_data) from onnx-community, then merged into a single self-
contained file via onnx.save(). OnnxSessionCache does not support external
data files.

The INT8 variant is produced by downloading the FP32 base and applying ORT
dynamic INT8 quantization locally.

Usage:
    pip install -r scripts/models/requirements.txt
    python scripts/models/build-embedding.py \\
        --hf-onnx-repo onnx-community/embeddinggemma-300m-ONNX \\
        --output-dir models/onnx/embeddinggemma-300m
"""

import argparse
import json
import shutil
import sys
from pathlib import Path

from _common import get_tool_versions, posix_relpath, resolve_hf_commit, sha256_file, verify_model


def download_hf_file(repo_id: str, filename: str, output_dir: Path) -> Path:
    """Download a single file from HuggingFace to a directory."""
    from huggingface_hub import hf_hub_download

    print(f"  Downloading {filename}...")
    downloaded = hf_hub_download(
        repo_id=repo_id,
        filename=filename,
        local_dir=output_dir,
    )
    return Path(downloaded)


def main():
    parser = argparse.ArgumentParser(description="Build embedding Q4 + INT8 models")
    parser.add_argument("--hf-model", default="google/embeddinggemma-300m",
                        help="Source HuggingFace model ID (for provenance)")
    parser.add_argument("--hf-onnx-repo", required=True,
                        help="HuggingFace ONNX repo (e.g., onnx-community/embeddinggemma-300m-ONNX)")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="Output directory (e.g., models/onnx/embeddinggemma-300m)")
    parser.add_argument("--q4-file", default="onnx/model_q4.onnx",
                        help="Q4 model path in HF repo")
    parser.add_argument("--q4-data-file", default="onnx/model_q4.onnx_data",
                        help="Q4 external data path in HF repo (set to empty to skip merge)")
    args = parser.parse_args()

    import onnx

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    tmp_dir = output_dir / "_build_tmp"
    tmp_dir.mkdir(parents=True, exist_ok=True)

    # Resolve HF commit hashes
    hf_commit = resolve_hf_commit(args.hf_model)
    onnx_commit = resolve_hf_commit(args.hf_onnx_repo)

    # Step 1: Download and merge Q4 variant
    print(f"Building Q4 variant from {args.hf_onnx_repo}...")
    q4_path = download_hf_file(args.hf_onnx_repo, args.q4_file, tmp_dir)
    if args.q4_data_file:
        download_hf_file(args.hf_onnx_repo, args.q4_data_file, tmp_dir)

    print("  Merging external data into single file...")
    model = onnx.load(str(q4_path))
    gpu_path = output_dir / "model.onnx"
    onnx.save(model, str(gpu_path))
    size_mb = gpu_path.stat().st_size / (1024 * 1024)
    print(f"  Saved Q4 (merged): {gpu_path} ({size_mb:.1f} MB)")
    del model

    # Step 2: Build INT8 variant from FP32 base
    print(f"\nBuilding INT8 variant...")
    fp32_path = download_hf_file(args.hf_onnx_repo, "onnx/model.onnx", tmp_dir)
    try:
        download_hf_file(args.hf_onnx_repo, "onnx/model.onnx_data", tmp_dir)
    except Exception:
        pass

    from onnxruntime.quantization import quantize_dynamic, QuantType

    cpu_path = output_dir / "model_int8.onnx"
    print("  Applying ORT dynamic INT8 quantization...")
    quantize_dynamic(
        str(fp32_path),
        str(cpu_path),
        weight_type=QuantType.QInt8,
    )
    size_mb = cpu_path.stat().st_size / (1024 * 1024)
    print(f"  Saved INT8: {cpu_path} ({size_mb:.1f} MB)")

    # Step 3: Download tokenizer and config files
    print("\nDownloading config files...")
    for name in ["tokenizer.json", "tokenizer_config.json", "config.json",
                 "special_tokens_map.json", "added_tokens.json",
                 "generation_config.json"]:
        dst = output_dir / name
        if dst.exists():
            print(f"  {name} already exists, skipping")
            continue
        try:
            src = download_hf_file(args.hf_onnx_repo, name, tmp_dir)
            shutil.move(str(src), str(dst))
        except Exception:
            print(f"  {name} not found in repo, skipping")

    # Verify both models
    verify_model(gpu_path)
    verify_model(cpu_path)

    # Compute hashes and write build.json
    gpu_sha = sha256_file(gpu_path)
    cpu_sha = sha256_file(cpu_path)
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
                "description": "GPU — Q4 (4-bit quantization)",
                "transformations": [
                    f"Downloaded {args.q4_file} + {args.q4_data_file} from {args.hf_onnx_repo}",
                    "Merged split external-data files into single self-contained model.onnx via onnx.save()",
                ],
                "output_sha256": gpu_sha,
            },
            "model_int8.onnx": {
                "description": "CPU — INT8 dynamic quantization",
                "transformations": [
                    f"Downloaded FP32 base from {args.hf_onnx_repo}",
                    "Applied ORT dynamic INT8 quantization (onnxruntime.quantization.quantize_dynamic, weight_type=QInt8)",
                ],
                "output_sha256": cpu_sha,
            },
        },
        "build_command": f"python scripts/models/build-embedding.py --hf-onnx-repo {args.hf_onnx_repo} --output-dir {posix_relpath(args.output_dir)}",
        "tool_versions": tool_versions,
    }

    build_path = output_dir / "build.json"
    with open(build_path, "w") as f:
        json.dump(build_json, f, indent=2)
    print(f"\nWrote {build_path}")

    # Cleanup temp dir
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
        print(f"Cleaned up {tmp_dir}")

    print("\nDone. Verify GPU compatibility with:")
    print(f"  ./gradlew.bat :modules:worker-core:verifyModel -Pmodel={gpu_path} -Pgpu=true")


if __name__ == "__main__":
    main()
