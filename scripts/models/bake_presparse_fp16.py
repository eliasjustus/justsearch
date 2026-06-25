#!/usr/bin/env python3
"""
Bake PRESPARSE pooling into SPLADE DistilBERT - V2 approach.

1. Optimize base MLM model + convert to FP16 (keep_io_types=False)
2. Remove the FP16 output declaration from the converted model
3. Append PRESPARSE ops in FP16 (matching the internal FP16 tensors)
4. Declare new outputs

This avoids type annotation inconsistencies from the converter.
"""

import argparse
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--double-log", action="store_true")
    parser.add_argument("--top-k", type=int, default=256)
    args = parser.parse_args()

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Step 1: Optimize + FP16 the base MLM model
    from onnxruntime.transformers import optimizer

    print(f"Step 1: Optimizing {args.input}...")
    opt_model = optimizer.optimize_model(
        args.input,
        model_type="bert",
        num_heads=0,
        hidden_size=0,
        opt_level=0,
        use_gpu=True,
        only_onnxruntime=False,
    )

    print("Converting to FP16 (keep_io_types=False)...")
    opt_model.convert_float_to_float16(keep_io_types=False)

    model = opt_model.model

    # Inspect original output
    assert len(model.graph.output) >= 1
    orig_out = model.graph.output[0]
    logits_name = orig_out.name
    logits_type = orig_out.type.tensor_type.elem_type
    print(f"Original output: {logits_name}, type={logits_type} (10=FP16, 1=FP32)")

    # Step 2: Remove original outputs and value_info for our new tensors
    while len(model.graph.output) > 0:
        model.graph.output.pop()

    # Step 3: Append PRESPARSE ops
    # Determine the constant type to match the logits type
    is_fp16 = (logits_type == TensorProto.FLOAT16)
    np_dtype = np.float16 if is_fp16 else np.float32
    onnx_dtype = TensorProto.FLOAT16 if is_fp16 else TensorProto.FLOAT
    print(f"Appending PRESPARSE ops with {'FP16' if is_fp16 else 'FP32'} constants")

    one_tensor = numpy_helper.from_array(np.array(1.0, dtype=np_dtype), name="const_one")
    model.graph.initializer.append(one_tensor)

    k_tensor = numpy_helper.from_array(np.array([args.top_k], dtype=np.int64), name="topk_k")
    model.graph.initializer.append(k_tensor)

    axes_tensor = numpy_helper.from_array(np.array([1], dtype=np.int64), name="reduce_axes")
    model.graph.initializer.append(axes_tensor)

    nodes = []
    current = logits_name

    # ReLU
    nodes.append(helper.make_node("Relu", [current], ["ps_relu"], name="ps_relu"))
    current = "ps_relu"

    # Add 1
    nodes.append(helper.make_node("Add", [current, "const_one"], ["ps_add1"], name="ps_add1"))
    current = "ps_add1"

    # Log
    nodes.append(helper.make_node("Log", [current], ["ps_log1"], name="ps_log1"))
    current = "ps_log1"

    if args.double_log:
        nodes.append(helper.make_node("Add", [current, "const_one"], ["ps_add2"], name="ps_add2"))
        current = "ps_add2"
        nodes.append(helper.make_node("Log", [current], ["ps_log2"], name="ps_log2"))
        current = "ps_log2"

    # ReduceMax axis=1, keepdims=0: [B,S,V] -> [B,V]
    nodes.append(helper.make_node("ReduceMax", [current, "reduce_axes"], ["ps_rmax"],
                                  name="ps_rmax", keepdims=0))
    current = "ps_rmax"

    # TopK
    topk_weights_name = "topk_weights_raw" if is_fp16 else "output_weights"
    nodes.append(helper.make_node("TopK", [current, "topk_k"],
                                  [topk_weights_name, "output_idx"],
                                  name="ps_topk", axis=-1))

    # If FP16: cast TopK weights output to FP32 for Java ORT compatibility
    # (Java OnnxTensor.getFloatBuffer() requires FP32; only the 256-element
    # sparse output is cast, not the full 30K-dim tensor — negligible cost)
    if is_fp16:
        nodes.append(helper.make_node("Cast", [topk_weights_name], ["output_weights"],
                                      name="ps_cast_to_fp32", to=TensorProto.FLOAT))

    model.graph.node.extend(nodes)

    # Step 4: Declare outputs (weights always FP32 for Java compat)
    model.graph.output.append(
        helper.make_tensor_value_info("output_idx", TensorProto.INT64, [None, args.top_k]))
    model.graph.output.append(
        helper.make_tensor_value_info("output_weights", TensorProto.FLOAT, [None, args.top_k]))

    print(f"Saving to {args.output}...")
    onnx.save(model, str(output_path))
    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"Done. Model size: {size_mb:.1f} MB")


if __name__ == "__main__":
    main()
