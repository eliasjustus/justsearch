#!/usr/bin/env python3
"""
CPU-only ONNX model format check.

Loads an ONNX model on CPU with default ORT options and runs a dummy inference
to verify the file is valid ONNX. Reports input/output names, shapes, and dtypes.

This script does NOT validate production GPU compatibility. Use the Gradle task
for that:

    ./gradlew.bat :modules:worker-core:verifyModel -Pmodel=<path> -Pgpu=true

Usage:
    python scripts/models/verify-model.py <model_path> [--seq-len N]
"""

import argparse
import sys
import time
from pathlib import Path

try:
    import onnxruntime as ort
except ImportError:
    print("ERROR: onnxruntime not installed. Install with: pip install onnxruntime", file=sys.stderr)
    sys.exit(1)

import numpy as np


def main():
    parser = argparse.ArgumentParser(
        description="CPU-only ONNX model format check (NOT a production GPU validation tool)"
    )
    parser.add_argument("model_path", type=Path, help="Path to .onnx model file")
    parser.add_argument("--seq-len", type=int, default=3, help="Sequence length for dummy input (default: 3)")
    args = parser.parse_args()

    model_path = args.model_path.resolve()
    if not model_path.exists():
        print(f"ERROR: Model file not found: {model_path}", file=sys.stderr)
        sys.exit(1)

    print(f"Model: {model_path}")
    print(f"File size: {model_path.stat().st_size / (1024 * 1024):.1f} MB")

    # Load model (CPU only)
    t0 = time.perf_counter()
    try:
        session = ort.InferenceSession(
            str(model_path),
            providers=["CPUExecutionProvider"],
        )
    except Exception as e:
        print(f"FAILED to load model: {e}", file=sys.stderr)
        sys.exit(2)
    load_ms = (time.perf_counter() - t0) * 1000
    print(f"Session created in {load_ms:.0f}ms")

    # Print input metadata
    print("\nInputs:")
    for inp in session.get_inputs():
        print(f"  {inp.name}: shape={inp.shape}, dtype={inp.type}")

    print("\nOutputs:")
    for out in session.get_outputs():
        print(f"  {out.name}: shape={out.shape}, dtype={out.type}")

    # Build dummy input
    seq_len = args.seq_len
    input_names = {inp.name for inp in session.get_inputs()}
    feeds = {}

    # All JustSearch models use int64 input_ids and attention_mask
    feeds["input_ids"] = np.array([[101, 2023, 102][:seq_len]], dtype=np.int64)
    feeds["attention_mask"] = np.ones((1, seq_len), dtype=np.int64)
    if "token_type_ids" in input_names:
        feeds["token_type_ids"] = np.zeros((1, seq_len), dtype=np.int64)

    # Pad if seq_len > 3
    if seq_len > 3:
        pad_len = seq_len - 3
        feeds["input_ids"] = np.pad(feeds["input_ids"], ((0, 0), (0, pad_len)), constant_values=0)
        feeds["attention_mask"] = np.pad(feeds["attention_mask"], ((0, 0), (0, pad_len)), constant_values=0)
        if "token_type_ids" in feeds:
            feeds["token_type_ids"] = np.pad(feeds["token_type_ids"], ((0, 0), (0, pad_len)), constant_values=0)

    # Run inference
    t1 = time.perf_counter()
    try:
        outputs = session.run(None, feeds)
    except Exception as e:
        print(f"\nFAILED to run inference: {e}", file=sys.stderr)
        sys.exit(3)
    infer_ms = (time.perf_counter() - t1) * 1000
    print(f"\nInference completed in {infer_ms:.0f}ms")

    # Report output shapes
    output_names = [out.name for out in session.get_outputs()]
    for name, arr in zip(output_names, outputs):
        print(f"  {name}: shape={arr.shape}, dtype={arr.dtype}")

    print("\nOK")


if __name__ == "__main__":
    main()
