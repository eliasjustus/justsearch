#!/usr/bin/env python3
"""
Build SPLADE baked-PRESPARSE models (FP32 CPU + FP16 GPU) from a HuggingFace source.

Produces:
  model.onnx      — FP32 with baked PRESPARSE ops (CPU variant)
  model_fp16.onnx  — FP16 with baked PRESPARSE ops (GPU variant)
  build.json       — provenance metadata

The FP16 pipeline is order-dependent and was the cause of 4 failed attempts
in tempdoc 334. The correct order is:
  1. ORT transformer optimize (on the base FP32 MLM model)
  2. Convert entire graph to FP16 (keep_io_types=False)
  3. Append PRESPARSE ops (in FP16, matching internal tensor types)
  4. Cast output_weights FP16 -> FP32 (for Java ORT getFloatBuffer() compat)

DO NOT append PRESPARSE first then FP16-convert — this creates type boundary
mismatches at Cast nodes that ORT rejects.

Usage:
    pip install -r scripts/models/requirements.txt
    python scripts/models/build-splade.py \\
        --hf-model opensearch-project/opensearch-neural-sparse-encoding-doc-v3-distill \\
        --output-dir models/splade/naver-splade-v3
"""

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

from _common import get_tool_versions, posix_relpath, resolve_hf_commit, sha256_file, verify_model


def export_onnx_mlm(hf_model_id: str, output_path: Path):
    """Export HuggingFace MLM model to ONNX via torch.onnx.export (legacy TorchScript).

    Uses AutoModelForMaskedLM directly instead of optimum CLI, because optimum
    classifies some SPLADE models as SentenceTransformer (task=feature-extraction)
    rather than fill-mask, producing embedding output instead of MLM logits.
    """
    import torch
    from transformers import AutoModelForMaskedLM

    print(f"Exporting {hf_model_id} to ONNX (torch.onnx.export, legacy TorchScript)...")
    output_path.mkdir(parents=True, exist_ok=True)
    model = AutoModelForMaskedLM.from_pretrained(hf_model_id)
    model.eval()
    print(f"  vocab_size={model.config.vocab_size}, hidden={model.config.hidden_size}, "
          f"layers={model.config.num_hidden_layers}")

    dummy = (
        torch.ones(1, 8, dtype=torch.long),
        torch.ones(1, 8, dtype=torch.long),
        torch.zeros(1, 8, dtype=torch.long),
    )
    onnx_path = output_path / "model.onnx"
    torch.onnx.export(
        model, dummy, str(onnx_path),
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "seq"},
            "attention_mask": {0: "batch", 1: "seq"},
            "token_type_ids": {0: "batch", 1: "seq"},
            "logits": {0: "batch", 1: "seq"},
        },
        opset_version=18,
        do_constant_folding=True,
        dynamo=False,
    )
    size_mb = onnx_path.stat().st_size / (1024 * 1024)
    print(f"  Exported: {onnx_path} ({size_mb:.1f} MB)")
    return onnx_path


def bake_presparse_fp32(input_path: str, output_path: Path,
                        double_log: bool, top_k: int):
    """Append PRESPARSE ops to an FP32 MLM model."""
    print(f"Baking PRESPARSE (FP32) into {input_path}...")
    model = onnx.load(str(input_path))

    assert len(model.graph.output) >= 1
    logits_name = model.graph.output[0].name

    while len(model.graph.output) > 0:
        model.graph.output.pop()

    np_dtype = np.float32
    one = numpy_helper.from_array(np.array(1.0, dtype=np_dtype), name="const_one")
    model.graph.initializer.append(one)
    k = numpy_helper.from_array(np.array([top_k], dtype=np.int64), name="topk_k")
    model.graph.initializer.append(k)
    axes = numpy_helper.from_array(np.array([1], dtype=np.int64), name="reduce_axes")
    model.graph.initializer.append(axes)

    nodes = []
    cur = logits_name

    nodes.append(helper.make_node("Relu", [cur], ["ps_relu"], name="ps_relu"))
    cur = "ps_relu"
    nodes.append(helper.make_node("Add", [cur, "const_one"], ["ps_add1"], name="ps_add1"))
    cur = "ps_add1"
    nodes.append(helper.make_node("Log", [cur], ["ps_log1"], name="ps_log1"))
    cur = "ps_log1"

    if double_log:
        nodes.append(helper.make_node("Add", [cur, "const_one"], ["ps_add2"], name="ps_add2"))
        cur = "ps_add2"
        nodes.append(helper.make_node("Log", [cur], ["ps_log2"], name="ps_log2"))
        cur = "ps_log2"

    nodes.append(helper.make_node("ReduceMax", [cur, "reduce_axes"], ["ps_rmax"],
                                  name="ps_rmax", keepdims=0))
    cur = "ps_rmax"
    nodes.append(helper.make_node("TopK", [cur, "topk_k"],
                                  ["output_weights", "output_idx"],
                                  name="ps_topk", axis=-1))
    model.graph.node.extend(nodes)

    model.graph.output.append(
        helper.make_tensor_value_info("output_idx", TensorProto.INT64, [None, top_k]))
    model.graph.output.append(
        helper.make_tensor_value_info("output_weights", TensorProto.FLOAT, [None, top_k]))

    onnx.save(model, str(output_path))
    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"  Saved FP32 baked-PRESPARSE: {output_path} ({size_mb:.1f} MB)")


def bake_presparse_fp16(input_path: str, output_path: Path,
                        double_log: bool, top_k: int):
    """ORT optimize + FP16 convert + append PRESPARSE ops (correct order)."""
    from onnxruntime.transformers import optimizer

    print(f"Baking PRESPARSE (FP16) into {input_path}...")
    print("  Step 1: ORT transformer optimize...")
    opt_model = optimizer.optimize_model(
        str(input_path),
        model_type="bert",
        num_heads=0,
        hidden_size=0,
        opt_level=0,
        use_gpu=True,
        only_onnxruntime=False,
    )

    print("  Step 2: Convert to FP16 (keep_io_types=False)...")
    opt_model.convert_float_to_float16(keep_io_types=False)
    model = opt_model.model

    assert len(model.graph.output) >= 1
    logits_name = model.graph.output[0].name
    logits_type = model.graph.output[0].type.tensor_type.elem_type
    is_fp16 = (logits_type == TensorProto.FLOAT16)

    while len(model.graph.output) > 0:
        model.graph.output.pop()

    np_dtype = np.float16 if is_fp16 else np.float32
    one = numpy_helper.from_array(np.array(1.0, dtype=np_dtype), name="const_one")
    model.graph.initializer.append(one)
    k = numpy_helper.from_array(np.array([top_k], dtype=np.int64), name="topk_k")
    model.graph.initializer.append(k)
    axes = numpy_helper.from_array(np.array([1], dtype=np.int64), name="reduce_axes")
    model.graph.initializer.append(axes)

    nodes = []
    cur = logits_name

    nodes.append(helper.make_node("Relu", [cur], ["ps_relu"], name="ps_relu"))
    cur = "ps_relu"
    nodes.append(helper.make_node("Add", [cur, "const_one"], ["ps_add1"], name="ps_add1"))
    cur = "ps_add1"
    nodes.append(helper.make_node("Log", [cur], ["ps_log1"], name="ps_log1"))
    cur = "ps_log1"

    if double_log:
        nodes.append(helper.make_node("Add", [cur, "const_one"], ["ps_add2"], name="ps_add2"))
        cur = "ps_add2"
        nodes.append(helper.make_node("Log", [cur], ["ps_log2"], name="ps_log2"))
        cur = "ps_log2"

    nodes.append(helper.make_node("ReduceMax", [cur, "reduce_axes"], ["ps_rmax"],
                                  name="ps_rmax", keepdims=0))
    cur = "ps_rmax"

    topk_weights_name = "topk_weights_raw" if is_fp16 else "output_weights"
    nodes.append(helper.make_node("TopK", [cur, "topk_k"],
                                  [topk_weights_name, "output_idx"],
                                  name="ps_topk", axis=-1))

    if is_fp16:
        nodes.append(helper.make_node("Cast", [topk_weights_name], ["output_weights"],
                                      name="ps_cast_to_fp32", to=TensorProto.FLOAT))

    model.graph.node.extend(nodes)
    model.graph.output.append(
        helper.make_tensor_value_info("output_idx", TensorProto.INT64, [None, top_k]))
    model.graph.output.append(
        helper.make_tensor_value_info("output_weights", TensorProto.FLOAT, [None, top_k]))

    onnx.save(model, str(output_path))
    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"  Saved FP16 baked-PRESPARSE: {output_path} ({size_mb:.1f} MB)")


def main():
    parser = argparse.ArgumentParser(description="Build SPLADE baked-PRESPARSE models")
    parser.add_argument("--hf-model", required=True,
                        help="HuggingFace model ID (e.g., opensearch-project/opensearch-neural-sparse-encoding-doc-v3-distill)")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="Output directory (e.g., models/splade/naver-splade-v3)")
    parser.add_argument("--double-log", action="store_true", default=True,
                        help="Use double-log activation (default: True)")
    parser.add_argument("--no-double-log", action="store_false", dest="double_log")
    parser.add_argument("--top-k", type=int, default=256)
    parser.add_argument("--skip-export", action="store_true",
                        help="Skip ONNX export, use existing base model in output dir")
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    # Resolve HF commit hash before any downloads
    hf_commit = resolve_hf_commit(args.hf_model)

    # Stage base model separately from output dir. bake_presparse_fp32 writes
    # to output_dir/model.onnx, which would clobber the input before
    # bake_presparse_fp16 reads it if they shared the same path.
    staging_dir = output_dir / "_build_staging"
    staging_dir.mkdir(parents=True, exist_ok=True)

    if args.skip_export:
        base_candidate = output_dir / "model.onnx"
        if not base_candidate.exists():
            print(f"ERROR: --skip-export but {base_candidate} does not exist", file=sys.stderr)
            sys.exit(1)
        staged_base = staging_dir / "base_mlm.onnx"
        print(f"Staging base model to {staged_base}...")
        shutil.copy2(str(base_candidate), str(staged_base))
        base_model_path = str(staged_base)
    else:
        export_dir = staging_dir / "export"
        base_onnx = export_onnx_mlm(args.hf_model, export_dir)
        base_model_path = str(base_onnx)

    # Build FP32 baked-PRESPARSE (CPU variant)
    cpu_path = output_dir / "model.onnx"
    bake_presparse_fp32(base_model_path, cpu_path, args.double_log, args.top_k)

    # Build FP16 baked-PRESPARSE (GPU variant) — reads from staged base
    gpu_path = output_dir / "model_fp16.onnx"
    bake_presparse_fp16(base_model_path, gpu_path, args.double_log, args.top_k)

    # Verify both models
    verify_model(cpu_path)
    verify_model(gpu_path)

    # Compute hashes and write build.json
    cpu_sha = sha256_file(cpu_path)
    gpu_sha = sha256_file(gpu_path)
    tool_versions = get_tool_versions()

    log_desc = "ReLU, Add(1), Log, Add(1), Log [double-log]" if args.double_log else "ReLU, Add(1), Log"
    presparse_desc = f"{log_desc}, ReduceMax(axis=1, keepdims=0), TopK(k={args.top_k})"

    build_json = {
        "source": {
            "hf_model_id": args.hf_model,
            "hf_commit": hf_commit,
        },
        "variants": {
            "model.onnx": {
                "description": "CPU — FP32 baked-PRESPARSE",
                "transformations": [
                    f"ONNX MLM_LOGITS export of {args.hf_model}",
                    f"Append PRESPARSE ops (FP32): {presparse_desc}",
                    f"Declare outputs: output_idx (INT64, [B,{args.top_k}]), output_weights (FLOAT32, [B,{args.top_k}])",
                ],
                "output_sha256": cpu_sha,
            },
            "model_fp16.onnx": {
                "description": "GPU — FP16 baked-PRESPARSE",
                "transformations": [
                    f"Load FP32 MLM base model ({args.hf_model})",
                    "ORT transformer optimize (model_type=bert, num_heads=0, hidden_size=0, opt_level=0, use_gpu=True)",
                    "Convert entire graph to FP16 (keep_io_types=False)",
                    f"Append PRESPARSE ops (FP16 constants): {presparse_desc}",
                    "Cast output_weights FP16 -> FP32 for Java ORT getFloatBuffer() compatibility",
                    f"Declare outputs: output_idx (INT64, [B,{args.top_k}]), output_weights (FLOAT32, [B,{args.top_k}])",
                ],
                "output_sha256": gpu_sha,
            },
        },
        "build_command": f"python scripts/models/build-splade.py --hf-model {args.hf_model} --output-dir {posix_relpath(args.output_dir)}",
        "tool_versions": tool_versions,
    }

    build_path = output_dir / "build.json"
    with open(build_path, "w") as f:
        json.dump(build_json, f, indent=2)
    print(f"\nWrote {build_path}")

    # Cleanup staging dir
    if staging_dir.exists():
        shutil.rmtree(staging_dir)
        print(f"Cleaned up {staging_dir}")

    print("\nDone. Verify GPU compatibility with:")
    print(f"  ./gradlew.bat :modules:worker-core:verifyModel -Pmodel={gpu_path} -Pgpu=true")


if __name__ == "__main__":
    main()
