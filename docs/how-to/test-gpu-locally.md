---
title: Test GPU Inference Locally
type: how-to
status: stable
description: "How to run llama-server with GPU offload for local development and testing."
---

# Test GPU Inference Locally

## Prerequisites

- NVIDIA GPU with CUDA compute capability 7.0+ (e.g., RTX 2060 or newer)
- CUDA variant present at `modules/ui/native-bin/llama-server/variants/cuda12/`

## Preflight Check (Recommended)

Run the repo preflight before GPU testing:

```bash
node scripts/verify-prerequisites.mjs
```

This verifies model files, native runtime layout, and GPU visibility expected by the AI workflows.

## Method 1: Standalone llama-server (quick validation)

Launch the CUDA variant directly to verify GPU offload works:

```bash
modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe \
  -m models/<your-model>.gguf \
  --jinja -ngl 99 --host 127.0.0.1 --port 8086
```

Check stderr for these lines to confirm GPU offload:
- `ggml_cuda_init: found N CUDA devices` — CUDA backend loaded
- `offloaded N/N layers to GPU` — model layers on GPU

## Method 2: System property override (full app)

Pass the CUDA variant path as a system property when launching the app:

```text
-Djustsearch.server.exe=modules/ui/native-bin/llama-server/variants/cuda12/llama-server.exe
-Djustsearch.gpu.layers=99
```

This overrides `InferenceConfig.findServerExecutable()` without needing the UI activation flow. See `docs/explanation/13-ai-setup-and-verification.md` section 3.3 for how variant selection works.

## Troubleshooting

- **No CUDA devices found:** Verify your NVIDIA driver supports CUDA 12.4+ (`nvidia-smi` shows driver version)
- **Missing DLL errors (exit code 0xC0000135):** The CUDA variant bundles its own runtime DLLs. If they're missing, re-extract or re-download the variant.
- **Layers show as `CPU_Mapped`:** You're running the CPU variant, not the CUDA variant. Check which exe is being used in the logs.
- **ORT session creation fails after NVIDIA driver upgrade:** The worker caches an ONNX Runtime graph-optimized model on disk per encoder (`<model>.cuda.optimized` plus a `<model>.cuda.opt-meta` sidecar alongside each `.onnx` under `models/`). The sidecar invalidates on model-file change or ORT version change but does **not** track CUDA driver version (intentional — ORT graph-level optimizations are not driver-sensitive in practice, so adding the field would invalidate caches on every driver bump with no benefit). In the rare case a driver upgrade triggers a loud `createSession` exception on next backend start, delete the cached files and restart:

  ```bash
  # From the repo root, or wherever models/ lives:
  rm models/**/*.cuda.optimized models/**/*.cuda.opt-meta
  ```

  The worker rebuilds the optimized graph on next session creation (~5-10 s per encoder). Cache files are always safe to delete — they are pure optimization artifacts, never authoritative.

## Further reading

- GPU Booster Pack architecture: `docs/explanation/16-gpu-booster-pack.md`
- Runtime variant selection: `docs/explanation/13-ai-setup-and-verification.md` section 3.3
- Environment variables: `docs/reference/configuration/environment-variables.md`
