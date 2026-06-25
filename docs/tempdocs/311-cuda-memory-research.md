---
title: "311: CUDA-Native GPU Memory Management Research"
type: research
status: done
created: 2026-03-16
parent: 311-gpu-memory-partitioning
---

> NOTE: Research artifact. Findings only -- no implementation decisions.

# CUDA-Native GPU Memory Management Research

Research into CUDA-native solutions for managing GPU memory when multiple ORT
sessions and a separate llama-server process share a single consumer GPU
(8-12 GB VRAM) on Windows 11.

## Context

JustSearch runs 3 ORT CUDA sessions (embedding, SPLADE, cross-encoder) in
the Worker process plus a separate llama-server process (7B LLM). On an 8 GB
consumer GPU, these compete for ~7.2 GB usable VRAM. The ORT sessions
currently claim 5.6 GB via arena pre-allocation, leaving insufficient room
for the LLM. See `311-gpu-memory-partitioning.md` for the full problem
description.

---

## 1. CUDA MPS (Multi-Process Service)

**What it is:** An alternative CUDA API implementation that allows multiple
processes to share GPU compute resources (SMs, scheduling) through a single
CUDA context, reducing per-context overhead.

**Windows support: NO.** MPS is only supported on Linux and QNX. This is
explicitly stated in NVIDIA's documentation. Not available on Windows at all.

**Intra-process relevance: NONE.** MPS is designed for inter-process GPU
sharing (multiple OS processes submitting CUDA work). It does not help with
multiple ORT sessions within the same JVM process -- those already share a
single CUDA context. MPS reduces the overhead of *separate* CUDA contexts
across processes; within a single process, sessions already multiplex on the
same context.

**Consumer GPU support:** Requires compute capability 3.5+. GeForce GPUs
meet this, but irrelevant since Windows is not supported.

**Verdict: Not applicable.** Two disqualifiers: no Windows support, and no
intra-process benefit.

Sources:
- [MPS Documentation](https://docs.nvidia.com/deploy/mps/index.html)
- [When to Use MPS](https://docs.nvidia.com/deploy/mps/when-to-use-mps.html)

---

## 2. CUDA Unified Memory / Managed Memory (cudaMallocManaged)

**What it is:** Allocates memory accessible from both CPU and GPU with
automatic page migration. On Pascal+ GPUs (compute capability 6.0+), the
hardware Page Migration Engine supports demand-paging: GPU pages can be
evicted to system RAM when VRAM is full, enabling memory oversubscription.

**Windows support: SEVERELY LIMITED.**

- On Windows, CUDA unified memory falls back to the **pre-Pascal behavior**
  regardless of GPU architecture. Fine-grained page fault and migration are
  not supported.
- Memory oversubscription via `cudaMallocManaged` does **not** work on
  Windows. Applications cannot allocate more managed memory than physical
  GPU memory.
- The `concurrentManagedAccess` device property returns 0 on Windows,
  meaning the advanced features (concurrent CPU+GPU access, page migration,
  oversubscription) are disabled.
- This limitation is attributed to the WDDM driver model and has been
  confirmed as the status quo since at least CUDA 11.2 (2021) with no
  announced changes through CUDA 13.x.

**ORT integration: NOT AVAILABLE.** ORT's CUDA Execution Provider uses
`cudaMalloc` internally through its BFC Arena allocator. There is no
configuration option to switch ORT to `cudaMallocManaged`. ORT does support
external allocators (`gpu_external_alloc`/`gpu_external_free`) through the
C API, which could theoretically wrap `cudaMallocManaged`, but:
(a) this is C API only, not exposed in the Java API;
(b) on Windows, managed memory provides no oversubscription benefit anyway.

**Verdict: Not viable on Windows.** The core benefit (transparent VRAM
oversubscription via page migration) is Linux-only.

Sources:
- [Unified Memory Programming Guide](https://docs.nvidia.com/cuda/cuda-programming-guide/04-special-topics/unified-memory.html)
- [NVIDIA Forum: UM Oversubscription on Windows](https://forums.developer.nvidia.com/t/cuda-unified-memory-oversubscription-in-windows-systems/58391)
- [NVIDIA Blog: Improving GPU Memory Oversubscription](https://developer.nvidia.com/blog/improving-gpu-memory-oversubscription-performance/)

---

## 3. CUDA Memory Pools (cudaMemPool, Stream-Ordered Allocation)

**What it is:** Introduced in CUDA 11.2. Provides stream-ordered
`cudaMallocAsync`/`cudaFreeAsync` that draw from memory pools. Pools can be
configured with maximum sizes, release thresholds, and reuse policies. Pools
can be shared across streams within a process, and even across processes via
IPC handles.

**Windows support: YES (with caveats).** Stream-ordered allocation works on
Windows. However, IPC pool sharing uses POSIX file descriptors, which limits
cross-process sharing on Windows. The basic intra-process pool functionality
(allocation, freeing, pool sizing) works.

**Pool size control:** `cudaMemPoolSetAttribute` can set:
- `cudaMemPoolAttrReleaseThreshold` -- controls when freed memory is
  returned to the OS vs. retained in the pool.
- Maximum pool size via pool creation attributes.

**ORT integration: NONE.** ORT's BFC Arena allocator uses traditional
`cudaMalloc`, not `cudaMallocAsync`. There is no ORT configuration option to
switch to stream-ordered allocation or to use a shared `cudaMemPool` across
sessions. The BFC Arena is ORT's own caching allocator that sits on top of
`cudaMalloc`.

To use CUDA memory pools with ORT, one would need to:
1. Implement a custom allocator using `gpu_external_alloc`/`gpu_external_free`
   (C API only, not Java).
2. Have the external allocator draw from a shared `cudaMemPool`.
3. Configure all ORT sessions to use this external allocator.

This is technically possible but requires C/C++ native code and is not
exposed through ORT's Java bindings.

**Verdict: Promising technology but no ORT integration path from Java.**

Sources:
- [CUDA Stream-Ordered Memory Allocator](https://docs.nvidia.com/cuda/cuda-programming-guide/04-special-topics/stream-ordered-memory-allocation.html)
- [NVIDIA Blog: Stream-Ordered Allocator Part 1](https://developer.nvidia.com/blog/using-cuda-stream-ordered-memory-allocator-part-1/)
- [NVIDIA Blog: CUDA 11.2 Memory Features](https://developer.nvidia.com/blog/enhancing-memory-allocation-with-new-cuda-11-2-features/)

---

## 4. CUDA Virtual Memory Management (cuMemAddressReserve / cuMemMap)

**What it is:** Low-level driver API for fine-grained GPU virtual memory
control. Applications reserve virtual address ranges, allocate physical
memory chunks, and map/unmap them explicitly. Enables:
- Growing allocations without fragmentation (reserve large VA upfront, map
  physical pages incrementally).
- Cross-device memory mapping.
- Precise control over which physical memory backs which virtual range.

**Windows support: YES.** The VMM APIs work on Windows. They are driver-level
APIs (cuMem* prefix) available on all platforms that support CUDA.

**ML framework adoption:**
- **PyTorch** uses VMM for "expandable segments" (`PYTORCH_CUDA_ALLOC_CONF=
  expandable_segments:True`), which reduces memory fragmentation by growing
  virtual segments instead of allocating new `cudaMalloc` blocks.
- **vLLM** uses VMM (`cuMemAddressReserve`/`cuMemCreate`/`cuMemMap`) for
  KV-cache management (vAttention), enabling demand-paged GPU memory for
  LLM serving.

**ORT integration: NONE.** ORT does not use CUDA VMM APIs. Its BFC Arena
uses `cudaMalloc`. There is no configuration or plugin mechanism to switch
ORT to VMM-based allocation. The external allocator API could theoretically
wrap VMM operations, but this would require significant native code.

**Verdict: Powerful but requires deep native integration. No ORT path.**

Sources:
- [CUDA VMM Programming Guide](https://docs.nvidia.com/cuda/cuda-programming-guide/04-special-topics/virtual-memory-management.html)
- [NVIDIA Blog: GPU Virtual Memory Management](https://developer.nvidia.com/blog/introducing-low-level-gpu-virtual-memory-management/)
- [PyTorch RFC: Expandable Segments + VMM](https://github.com/pytorch/pytorch/issues/165419)
- [vLLM vAttention Feature Request](https://github.com/vllm-project/vllm/issues/17612)

---

## 5. PyTorch GPU Memory Management -- Lessons for ORT

**What PyTorch does:**

PyTorch's caching allocator (`CUDACachingAllocator`) sits between PyTorch
and `cudaMalloc`. Key features:
- **Caching**: Retains freed blocks for reuse, reaching steady state where
  no new `cudaMalloc` calls are needed.
- **Expandable segments**: Uses CUDA VMM to grow allocations without
  fragmentation (CUDA 11.4+).
- **Configurable via `PYTORCH_CUDA_ALLOC_CONF`**:
  - `backend:native` (default) or `backend:cudaMallocAsync` (CUDA 11.2+
    stream-ordered allocator).
  - `expandable_segments:True` -- VMM-based segment growth.
  - `max_split_size_mb` -- controls block splitting to reduce fragmentation.
  - `garbage_collection_threshold` -- triggers cache cleanup at a threshold.
- **Pluggable allocator API**: `CUDAPluggableAllocator` allows replacing
  the entire allocation backend.

**Lessons applicable to ORT:**

1. **ORT's BFC Arena is functionally similar to PyTorch's caching
   allocator** -- both cache GPU memory and sub-allocate from large blocks.
   The key difference: PyTorch's allocator is more configurable.

2. **ORT lacks an equivalent to `expandable_segments`** -- ORT arenas have
   a fixed maximum size set at session creation. They cannot grow using VMM.

3. **ORT lacks `cudaMallocAsync` backend support** -- PyTorch can switch to
   CUDA's native stream-ordered allocator; ORT cannot.

4. **ORT's `gpu_mem_limit` is the primary knob** -- equivalent to setting a
   memory cap, but cruder than PyTorch's fine-grained controls.

5. **ORT does have arena shrinkage** (`memory.enable_memory_arena_shrinkage`)
   which has no direct PyTorch equivalent (PyTorch retains cache by default
   but provides `torch.cuda.empty_cache()` for explicit release).

**Verdict: PyTorch's advantages come from deep C++ integration with CUDA
memory APIs. ORT's Java API exposes only arena size and shrinkage -- no
pluggable allocator, no VMM, no `cudaMallocAsync`.**

Sources:
- [PyTorch CUDA Semantics](https://docs.pytorch.org/docs/stable/notes/cuda.html)
- [PyTorch CUDA Caching Allocator Guide](https://zdevito.github.io/2022/08/04/cuda-caching-allocator.html)
- [GPU Memory Fragmentation Analysis](https://dasroot.net/posts/2026/02/gpu-memory-fragmentation-cuda-pytorch/)

---

## 6. nvidia-smi / CUDA Compute Mode -- Per-Process GPU Memory Limits

**Per-process VRAM limits: NOT AVAILABLE.** nvidia-smi does not provide any
mechanism to set per-process GPU memory limits or caps. It can:
- Monitor per-process GPU memory usage.
- Set compute mode (Default, Exclusive Process, Prohibited).
- Set power and clock limits.
- Set GPU application clocks.

**Compute modes:**
- `DEFAULT` -- multiple CUDA contexts per device (standard).
- `EXCLUSIVE_PROCESS` -- one CUDA context per device, usable from multiple
  threads. Used with MPS.
- `PROHIBITED` -- no CUDA contexts allowed.

None of these modes limit how much VRAM a process can consume.

**CUDA environment variables:** There is no standard CUDA environment
variable for per-process memory limits. `CUDA_VISIBLE_DEVICES` controls
device visibility, not memory. `CUDA_MANAGED_FORCE_DEVICE_ALLOC` affects
unified memory placement but not limits.

**Framework-level limits are the only option:**
- ORT: `gpu_mem_limit` provider option.
- PyTorch: `torch.cuda.set_per_process_memory_fraction()`.
- TensorFlow: `per_process_gpu_memory_fraction`.
- CuPy: `CUPY_GPU_MEMORY_LIMIT` env var.

**Verdict: No OS-level or driver-level per-process VRAM limits exist.
Memory budgeting must happen at the application/framework level.** For
JustSearch, this means setting `gpu_mem_limit` on each ORT session and
controlling llama-server's `--n-gpu-layers` to limit its VRAM consumption.

Sources:
- [nvidia-smi Manual](https://www.mankier.com/1/nvidia-smi)
- [NVIDIA Deploy nvidia-smi](https://docs.nvidia.com/deploy/nvidia-smi/)
- [CUDA Environment Variables](https://docs.nvidia.com/cuda/cuda-programming-guide/05-appendices/environment-variables.html)

---

## 7. WDDM vs TCC Mode on Windows

**WDDM (Windows Display Driver Model):**
- Required for all consumer GeForce GPUs on Windows. GeForce GPUs cannot
  switch to TCC mode.
- Manages GPU memory through a virtual memory system with demand paging.
- Adds overhead: RAM-to-GPU transfers are significantly slower under WDDM
  compared to TCC.
- WDDM reserves ~800 MB - 1 GB of VRAM for the Windows desktop compositor
  (DWM).
- Does not support CUDA unified memory page fault/migration features.
- WDDM intercedes in all GPU memory operations, adding latency.

**TCC (Tesla Compute Cluster):**
- Only available on professional GPUs (Quadro, Tesla, A-series, L-series).
- **Not available on GeForce GPUs.** Cannot be enabled via any workaround.
- Bypasses the Windows graphics stack entirely.
- Lower latency for GPU memory operations.
- Supports longer-running kernels (no 2-second TDR timeout).
- Better pinned memory performance.

**Impact on JustSearch (GeForce consumer GPUs):**
- Stuck with WDDM. No option to switch.
- ~800 MB - 1 GB VRAM overhead from DWM reduces usable VRAM.
- CUDA unified memory oversubscription is unavailable.
- GPU memory transfers have WDDM overhead (~20-50% slower than TCC for
  host-to-device copies, per benchmarks in NVIDIA forums).
- The WDDM 2-second TDR timeout applies to GPU kernels. Long-running
  inference should not hit this (ORT operations are typically <100ms), but
  it is a consideration for very large batches.

**Verdict: Consumer GPUs are locked to WDDM. This eliminates unified memory
oversubscription and adds memory overhead. Factor in ~1 GB VRAM loss for
DWM when budgeting.**

Sources:
- [TCC Documentation](https://docs.nvidia.com/gameworks/content/developertools/desktop/tesla_compute_cluster.htm)
- [LeaderGPU: Switch GPU Modes](https://www.leadergpu.com/articles/505-switch-gpu-modes-in-windows)
- [Microsoft: GPU Virtual Memory in WDDM 2.0](https://learn.microsoft.com/en-us/windows-hardware/drivers/display/gpu-virtual-memory-in-wddm-2-0)
- [WDDM vs TCC Transfer Speed](https://github.com/microsoft/graphics-driver-samples/issues/103)

---

## 8. GPU Memory Overcommit on Windows (WDDM + Sysmem Fallback)

### WDDM Native Memory Management

WDDM implements its own virtual memory system for GPU resources:
- Allocations are virtualized. WDDM can page GPU resources between dedicated
  VRAM, shared system RAM, and disk-backed storage.
- When VRAM pressure is high, WDDM evicts less-used resources to system RAM
  ("shared GPU memory" visible in Task Manager).
- This paging is managed at the WDDM driver level, transparent to CUDA.
- On discrete GPUs, shared memory is a fallback -- dedicated VRAM is always
  preferred.

**Critical distinction:** WDDM paging works at the resource/allocation
granularity, not at the fine-grained page level that CUDA unified memory
uses on Linux. It is more like bulk eviction of entire allocations.

### NVIDIA CUDA Sysmem Fallback Policy (Driver 536.40+)

Starting with driver 536.40 (2023), NVIDIA added a "CUDA - Sysmem Fallback
Policy" setting:
- When enabled, `cudaMalloc` calls that would fail due to insufficient VRAM
  transparently allocate in system RAM instead.
- The GPU accesses this system RAM over PCIe, which is much slower than VRAM
  but prevents OOM crashes.
- Configurable per-application via NVIDIA Control Panel:
  `3D Settings > Manage 3D settings > Program Settings > [executable] >
   CUDA - Sysmem Fallback Policy`
- Options: `Driver Default`, `Prefer No Sysmem Fallback`,
  `Prefer Sysmem Fallback`.
- Enhanced in driver 546.01+ with the option to disable it.

**How it interacts with CUDA:**
- On Windows WDDM, `cudaMalloc` does not directly allocate GPU physical
  memory. WDDM manages the physical backing. The "sysmem fallback" setting
  controls whether WDDM is allowed to place CUDA allocations in system RAM
  when VRAM is full.
- This happens transparently -- the CUDA application sees a successful
  `cudaMalloc` and a valid device pointer, but the backing memory may be in
  system RAM.
- Performance degrades because GPU must access system RAM over PCIe (15-30
  GB/s) instead of VRAM (400-900 GB/s depending on GPU).

**Programmatic control:** No known environment variable or registry key for
programmatic control. The setting is per-executable in the NVIDIA Control
Panel profile system. Cannot be set from within the application at runtime.

**Relevance to JustSearch:**
- This is effectively Windows' answer to CUDA unified memory oversubscription
  on Linux -- but coarser-grained and with worse performance characteristics.
- For 8 GB consumer GPUs, enabling Sysmem Fallback for the Worker process
  (Java) could prevent ORT arena allocation failures when total VRAM demand
  exceeds physical VRAM.
- **However:** The performance penalty is severe. Inference on system RAM
  backing can be 10-50x slower than VRAM for bandwidth-bound operations.
  For the ORT sessions, this would negate the GPU advantage entirely.
- The better strategy is to size ORT arenas correctly and use arena
  shrinkage so that VRAM demand stays within physical limits.

Sources:
- [NVIDIA: System Memory Fallback for Stable Diffusion](https://nvidia.custhelp.com/app/answers/detail/a_id/5490/~/system-memory-fallback-for-stable-diffusion)
- [NVIDIA Forum: cudaMalloc with Sysmem Fallback](https://forums.developer.nvidia.com/t/cudamalloc-with-sysmem-fallback/347791)
- [NVIDIA Forum: Virtual Memory Paging on WDDM 2.0](https://forums.developer.nvidia.com/t/virtual-memory-paging-support-for-pascal-gpus-on-windows-10-with-wddm-2-0-model/56347)
- [VideoCardz: NVIDIA Sysmem Fallback](https://videocardz.com/newz/nvidia-introduces-system-memory-fallback-feature-for-stable-diffusion)
- [Microsoft: GPU Virtual Memory in WDDM 2.0](https://learn.microsoft.com/en-us/windows-hardware/drivers/display/gpu-virtual-memory-in-wddm-2-0)

---

## Summary Table

| Mechanism | Works on Windows? | Works with ORT Java? | Helps with intra-process? | VRAM oversubscription? |
|-----------|:-:|:-:|:-:|:-:|
| CUDA MPS | No (Linux only) | N/A | No (inter-process only) | No |
| Unified Memory (cudaMallocManaged) | Degraded (no oversubscription) | No (no ORT option) | Yes | No (Linux only) |
| CUDA Memory Pools (cudaMemPool) | Yes | No (no ORT integration) | Yes | No |
| CUDA VMM (cuMemAddressReserve) | Yes | No (no ORT integration) | Yes | No |
| PyTorch-style allocator | N/A (different framework) | No | N/A | No |
| nvidia-smi per-process limits | No (does not exist) | N/A | N/A | No |
| TCC mode | No (GeForce excluded) | N/A | N/A | N/A |
| WDDM Sysmem Fallback | Yes (driver 536.40+) | Yes (transparent) | Yes | Yes (with severe perf penalty) |

## Actionable Levers for JustSearch (ORT Java on Windows)

Given the research, the available mechanisms for GPU memory management on
Windows with ORT Java sessions are:

### Directly available (no code changes to ORT):

1. **`gpu_mem_limit`** -- Set per-session arena size limits. The primary
   knob for VRAM budgeting.

2. **`arena_extend_strategy = kSameAsRequested`** -- Already used. Prevents
   arena from doubling in size on each extension.

3. **`memory.enable_memory_arena_shrinkage`** -- Release unused arena memory
   between inference calls. Available as a session or run config option.
   Tradeoff: slightly slower next inference due to reallocation.

4. **NVIDIA Sysmem Fallback Policy** -- Can be enabled per-executable via
   NVIDIA Control Panel. Acts as a safety net to prevent OOM crashes, but
   with severe performance penalties. Suitable as a last-resort fallback,
   not a primary strategy.

5. **Session lifecycle management** -- Destroy and recreate ORT sessions
   to fully release arena memory. Most effective way to free VRAM for
   other workloads (e.g., releasing embedding GPU session during SPLADE
   backfill).

6. **llama-server `--n-gpu-layers`** -- Control how many LLM layers are
   offloaded to GPU. Reducing GPU layers frees VRAM for ORT sessions.

### Require native code (C/C++ JNI):

7. **ORT external allocator** -- Implement a custom allocator via
   `gpu_external_alloc`/`gpu_external_free` that uses `cudaMallocAsync`
   (CUDA memory pools) or `cuMemMap` (VMM). Would require a JNI bridge.
   High complexity, significant engineering effort.

8. **Shared memory pool** -- Implement a shared `cudaMemPool` used by all
   ORT sessions via external allocators. Would enable pool-level memory
   reuse across sessions. Same JNI complexity as above.

### Not available on this platform:

9. CUDA MPS, TCC mode, unified memory oversubscription, per-process VRAM
   limits via nvidia-smi -- all blocked on Windows consumer GPUs.
