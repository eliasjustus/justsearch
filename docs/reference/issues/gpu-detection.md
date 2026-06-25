---
title: GPU Detection Issues
type: reference
status: stable
updated: 2026-02-02
description: "NVIDIA detection, VRAM, multi-GPU support limitations."
---

# GPU Detection Issues

Issues related to GPU detection, VRAM monitoring, and hardware acceleration.

**Key Files:**
- `modules/gpu-bridge/src/main/java/io/justsearch/gpu/NvmlService.java`
- `modules/gpu-bridge/src/main/java/io/justsearch/gpu/GpuCapabilitiesService.java`
- `modules/gpu-bridge/src/main/java/io/justsearch/gpu/VramDetector.java`

---

## Open Issues

### GPU-001: NVIDIA-Only Support
- **Severity:** P2
- **Status:** open
- **Found:** 2026-01-23
- **Component:** `modules/gpu-bridge/src/main/java/io/justsearch/gpu/`

**Description:** Only NVIDIA GPUs are detected. AMD and Intel GPUs are completely ignored, showing "No GPU detected" even if capable hardware is present.

**Impact:** Users with AMD/Intel GPUs cannot use GPU acceleration and receive misleading "No GPU" messages.

**Code Evidence:**

```java
// NvmlService.java - NVIDIA-specific (path now dynamic via SystemRoot, but vendor-locked)
// VramDetector.java - nvidia-smi only
private static final String NVIDIA_SMI = "nvidia-smi";
```

**Recommendation:**
- Add AMD ROCm/HIP detection (via `rocm-smi` or `hipInfo`)
- Add Intel oneAPI detection
- Or clearly communicate "NVIDIA required" in UI rather than generic "No GPU"

---

### GPU-002: Windows-Only Platform Support
- **Severity:** P3
- **Status:** open
- **Found:** 2026-01-23
- **Component:** `modules/gpu-bridge/src/main/java/io/justsearch/gpu/NvmlService.java`

**Description:** GPU detection explicitly checks for Windows and returns unknown for other platforms.

**Impact:** Linux/macOS users cannot use GPU acceleration even with NVIDIA GPUs.

**Code Evidence:**

```java
private static boolean isWindows() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    return os.contains("win");
}
```

**Recommendation:**
- Linux: NVML available at `/usr/lib/libnvidia-ml.so`
- macOS: Would require Metal support (different approach)

---

### GPU-004: Single GPU Assumption
- **Severity:** P3
- **Status:** open
- **Found:** 2026-01-23
- **Component:** `modules/gpu-bridge/src/main/java/io/justsearch/gpu/NvmlService.java`

**Description:** Code queries `deviceCount` but only uses the first device (index 0).

**Impact:**
- Multi-GPU systems only report first GPU's VRAM
- Cannot select which GPU to use for inference
- SLI/NVLink configurations not handled

**Code Evidence:**

```java
// Always uses index 0
int handleRc = (int) getHandle.invokeExact(arena, handle, 0);
```

**Recommendation:**
- Enumerate all GPUs and sum VRAM (for total available)
- Or allow user to select which GPU to use
- Report all GPUs in capabilities response

---

### GPU-006: Fixed Self-Test Timing (250ms)
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-23
- **Updated:** 2026-02-02
- **Component:** `modules/ui/src/main/java/io/justsearch/ui/ai/runtime/RuntimeActivationService.java`

**Description:** Self-test waits exactly 250ms after health check and chat completion before measuring VRAM delta.

**Context:** The self-test sequence is: start llama-server → poll `/health` (proper 500ms polling loop, 30s timeout) → send tiny chat request → `Thread.sleep(250)` → take VRAM snapshot. The 250ms sleep is **not** a readiness wait — the server is already healthy and has processed a request. It exists because CUDA allocates some memory pools lazily; they may not appear in `nvidia-smi` for a few hundred milliseconds after first use.

**Code Evidence:**

```java
// RuntimeActivationService.java line ~448
Thread.sleep(250);  // Fixed delay for GPU buffer allocation to settle
GpuCapabilities afterSnap = gpuCapabilitiesService.snapshot();
```

**Impact:**
- Slow GPU initialization may undercount VRAM usage
- Fast GPUs waste 250ms
- No defined completion signal for "GPU buffers fully allocated"

**Analysis:** This is a measurement timing problem, not a polling problem. Replacing with exponential backoff polling would require defining a stabilization criterion (e.g., "VRAM delta unchanged between consecutive readings"), but VRAM can fluctuate from background processes and driver activity, making a clean "stable" signal unreliable. The fixed 250ms works adequately in practice.

**Recommendation (if pursued):**
- Poll VRAM in a loop: take reading, wait, take another; stop when delta between readings < threshold
- Use exponential backoff: 50ms, 100ms, 200ms... up to ~2s max
- Accept that this adds complexity for marginal benefit

---

### GPU-008: No Temperature/Throttling Detection
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-23
- **Component:** `modules/gpu-bridge/src/main/java/io/justsearch/gpu/NvmlService.java`

**Description:** Only VRAM is monitored; temperature and throttling state are ignored despite NVML supporting these queries.

**Available NVML Functions (Not Used):**
- `nvmlDeviceGetTemperature`
- `nvmlDeviceGetPowerState`
- `nvmlDeviceGetPerformanceState`

**Impact:**
- Thermal throttling causes silent performance degradation
- No warning when GPU is overheating
- Cannot explain sudden slowdowns

**Recommendation:**
- Add temperature monitoring
- Warn user if GPU is throttling
- Include in health endpoint

---

## Frontend Display Issues

### GPU-011: GPU Layers Slider Misleading
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-23
- **Component:** `modules/ui-web/src/components/views/BrainView.tsx`

**Description:** GPU layers slider is adjustable even in CPU-only mode, with small disclaimer text.

**Impact:** Users may think GPU acceleration is active when it's not.

**Recommendation:** Disable slider when no GPU runtime is active, or make the disclaimer more prominent.

---

### GPU-013: CUDA runtime warning doesn't scan full PATH
- **Severity:** P3
- **Status:** open
- **Found:** 2026-01-30
- **Component:** `modules/app-inference/src/main/java/io/justsearch/app/inference/CudaRuntimeDetection.java`

**Description:** `warnIfCudaRuntimeMissing()` checks the server directory and System32 for CUDA DLLs but does not scan the full PATH environment variable. CUDA installations in non-standard locations (e.g., custom CUDA toolkit paths) are missed.

**Impact:** Users with valid CUDA installations outside standard paths may see incorrect "CUDA runtime not found" warnings.

**Recommendation:** Iterate over `System.getenv("PATH").split(File.pathSeparator)` and check each directory for the expected CUDA DLLs.

---

### GPU-014: CUDA detection is Windows-only
- **Severity:** P3
- **Status:** open
- **Found:** 2026-01-30
- **Component:** `modules/app-inference/src/main/java/io/justsearch/app/inference/CudaRuntimeDetection.java`, `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`

**Description:** All CUDA auto-selection and runtime warning logic looks for Windows DLLs (`ggml-cuda.dll`, `cudart64_*.dll`). Linux and macOS have no equivalent detection for `.so`/`.dylib` files.

**Impact:** GPU acceleration cannot be auto-detected on non-Windows platforms.

**Recommendation:** Add platform-specific detection for `libcudart.so` (Linux) and `libcudart.dylib` (macOS). Gate behind platform checks.

---

### GPU-015: Single-tenant GPU mutual exclusion is a fundamental architectural constraint
- **Severity:** P3
- **Status:** accepted-trade-off
- **Found:** 2026-02-03
- **Component:** `modules/app-inference/`, `modules/indexer-worker/`

**Description:** Mutual exclusion between GPU workloads ensures VRAM safety on consumer 8GB GPUs. With GPU embeddings configured, the embedding model is unloaded during chat sessions, forcing vector search to fall back to BM25. With CPU-only embeddings (the default), hybrid search continues at reduced speed.

**Impact:** When GPU embeddings are configured, entering Online Mode degrades search from hybrid to BM25-only (`NO_EMBEDDING_SERVICE`). With the default CPU-only embeddings, the practical impact is minor (slight CPU contention).

**Rationale:** Conscious trade-off — single-tenant GPU policy keeps the system safe on 8GB consumer GPUs without requiring cross-process VRAM coordination. See [ADR-0004](../../decisions/0004-single-tenant-gpu-policy.md).

**Reassess when:** Target GPU VRAM exceeds 16GB, or CUDA adds reliable cross-process VRAM reservation.
