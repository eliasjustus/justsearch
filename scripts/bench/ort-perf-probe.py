"""P2 probe — equivalent of onnxruntime_perf_test.exe via Python onnxruntime-gpu.

Measures inference latency (avg, p50, p90, p95, p99) for a given ONNX model
on the CUDA Execution Provider, with synthetic random-tensor inputs matching
the model's declared input shapes.

Usage:
  python scripts/bench/ort-perf-probe.py <model.onnx> [--runs N] [--warmup N] [--provider cuda|cpu|tensorrt]

Example (from F:\\JustSearch):
  python scripts/bench/ort-perf-probe.py F:/JustSearch/models/bge-m3/model.onnx --runs 100 --warmup 10

Output: single JSON line to stdout with inference latency statistics + provider info.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort


PROVIDER_MAP = {
    "cuda": "CUDAExecutionProvider",
    "cpu": "CPUExecutionProvider",
    "tensorrt": "TensorrtExecutionProvider",
}

GRAPH_OPT_LEVELS = {
    "all":      ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
    "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
    "basic":    ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
    "disable":  ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
}


def synth_input(name, shape, dtype, seq_len=128, batch=1):
    """Generate a random tensor matching (shape, dtype). Symbolic dims substituted:
    sequence_length=seq_len, batch_size=batch, others default to 1.

    Tailors ranges by input name: token_type_ids -> {0,1}, attention_mask -> {0,1}, input_ids -> [0, 30000).
    """
    concrete = []
    for d in shape:
        if d is None or isinstance(d, str):
            if isinstance(d, str) and d.lower() in ("sequence_length", "seq_len", "length"):
                concrete.append(seq_len)
            elif isinstance(d, str) and d.lower() in ("batch_size", "batch"):
                concrete.append(batch)
            else:
                concrete.append(1)
        else:
            concrete.append(int(d))
    nm = (name or "").lower()
    if dtype in (np.int64, np.int32):
        if "token_type" in nm or "segment" in nm:
            return np.random.randint(0, 2, size=concrete, dtype=dtype)
        if "attention_mask" in nm or "mask" in nm:
            return np.ones(concrete, dtype=dtype)
        if "input_ids" in nm or "ids" in nm:
            return np.random.randint(0, 30000, size=concrete, dtype=dtype)
        return np.random.randint(0, 2, size=concrete, dtype=dtype)
    if dtype == np.float16:
        return np.random.rand(*concrete).astype(np.float16)
    return np.random.rand(*concrete).astype(np.float32)


def dtype_from_onnx(t):
    m = {
        "tensor(float)": np.float32,
        "tensor(float16)": np.float16,
        "tensor(int64)": np.int64,
        "tensor(int32)": np.int32,
        "tensor(bool)": np.bool_,
    }
    return m.get(t, np.float32)


def run_one(sess, inputs, runs, warmup):
    for _ in range(warmup):
        sess.run(None, inputs)
    latencies_ms = []
    for _ in range(runs):
        t0 = time.perf_counter()
        sess.run(None, inputs)
        latencies_ms.append((time.perf_counter() - t0) * 1000)
    latencies_ms.sort()

    def pct(p):
        i = max(0, min(len(latencies_ms) - 1, int(round(p / 100 * (len(latencies_ms) - 1)))))
        return latencies_ms[i]

    avg = sum(latencies_ms) / len(latencies_ms)
    return {
        "latency_ms": {
            "avg": round(avg, 3),
            "stddev": round(statistics.stdev(latencies_ms), 3) if len(latencies_ms) > 1 else 0.0,
            "min": round(latencies_ms[0], 3),
            "p50": round(pct(50), 3),
            "p90": round(pct(90), 3),
            "p95": round(pct(95), 3),
            "p99": round(pct(99), 3),
            "max": round(latencies_ms[-1], 3),
        },
        "throughput_qps": round(1000 / avg, 2),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--runs", type=int, default=100)
    ap.add_argument("--warmup", type=int, default=10)
    ap.add_argument("--provider", default="cuda", choices=PROVIDER_MAP.keys())
    ap.add_argument("--batch", type=int, default=1, help="Batch size for symbolic batch_size dims")
    ap.add_argument("--batch-sweep", default=None, help="Comma-separated batch sizes (e.g. '1,4,16,64'). Overrides --batch; emits an array of results.")
    ap.add_argument("--seq-len", type=int, default=128, help="Sequence length for symbolic seq_len dims")
    ap.add_argument("--graph-opt", default="all", choices=GRAPH_OPT_LEVELS.keys(),
                    help="ORT graph optimization level. Use 'extended' or lower for models that hit SimplifiedLayerNormFusion bug at 'all'.")
    args = ap.parse_args()

    mp = Path(args.model).resolve()
    if not mp.exists():
        print(json.dumps({"error": f"model not found: {mp}"}), file=sys.stderr)
        sys.exit(2)

    provider = PROVIDER_MAP[args.provider]
    if provider not in ort.get_available_providers():
        print(json.dumps({
            "error": f"provider {provider} not available",
            "available": ort.get_available_providers(),
        }), file=sys.stderr)
        sys.exit(3)

    so = ort.SessionOptions()
    so.graph_optimization_level = GRAPH_OPT_LEVELS[args.graph_opt]
    sess = ort.InferenceSession(str(mp), sess_options=so, providers=[provider, "CPUExecutionProvider"])

    if args.batch_sweep:
        batches = [int(b.strip()) for b in args.batch_sweep.split(",") if b.strip()]
    else:
        batches = [args.batch]

    results = []
    for b in batches:
        inputs = {}
        for inp in sess.get_inputs():
            inputs[inp.name] = synth_input(inp.name, inp.shape, dtype_from_onnx(inp.type), seq_len=args.seq_len, batch=b)
        stats = run_one(sess, inputs, args.runs, args.warmup)
        results.append({
            "batch": b,
            "seq_len": args.seq_len,
            **stats,
        })

    envelope = {
        "model": str(mp),
        "model_size_mb": round(mp.stat().st_size / 1024 / 1024, 2),
        "provider": provider,
        "provider_actually_used": sess.get_providers()[0],
        "graph_opt": args.graph_opt,
        "runs": args.runs,
        "warmup": args.warmup,
        "inputs": [{"name": i.name, "shape": i.shape, "type": i.type} for i in sess.get_inputs()],
        "outputs_count": len(sess.get_outputs()),
        "batch_results": results,
    }
    print(json.dumps(envelope, indent=2))


if __name__ == "__main__":
    main()
