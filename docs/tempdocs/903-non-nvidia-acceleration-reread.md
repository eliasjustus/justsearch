---
title: "Non-NVIDIA acceleration, 2026 re-read: Vulkan for chat, WebGPU plugin EP for encoders, measured against the CPU tier"
type: tempdocs
status: RESEARCH COMPLETE (2026-09-02) — founder decision input; §6 is the opus chunk for the rank-1 option, not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L18 (1.1 re-read)
model: fable (research) → opus (§6 takeover)
parent: 887-improvement-landscape-register
related:
  - 311-runtime-alternatives-research     # the 2026-03-16 rejection this re-reads (§2 DirectML, §10 ROCm)
  - 761-linux-build-cost-estimate         # raised the llama.cpp Vulkan pivot as an open supply-chain/ADR decision
  - 772-installer-payload-composition     # how runtime packs are distributed; signed-mirror wiring
  - 760-installer-distribution-readiness  # SHA256SUMS, sign-once mirrors
  - 840-model-download-restructure        # install matrix; "Not supported here" row
  - 883-decision-review-lane-a-config-and-context-budget  # ADR-0047 ladder this must survive
  - 898-inference-runtime-residuals       # names non-NVIDIA backends as its non-goal (owner re-read = this doc)
---

# 903 — Non-NVIDIA acceleration, 2026 re-read

## 0. Headline

**The premise 311 rejected DirectML on has expired, and the cheapest non-NVIDIA path is now
measured, packaged upstream, and attested.** Three facts decide this re-read:

1. **A non-NVIDIA owner gets no chat today, not slow chat.** `InstallPlanner` skips every GGUF
   package unless the download profile `includesGguf()`, which only `GPU_FULL` (CUDA functional
   + ≥7.5 GB VRAM) does; the skip reason literally says "CPU chat is not supported in this build"
   (`modules/configuration/.../InstallPlanner.java:207-231`, `DownloadProfile.java:16-25`). The
   8k CPU rung in ADR-0047 exists, but no install path ever downloads the model that would use it.
   So for AMD/Intel machines the comparison is not "GPU vs CPU chat"; it is "chat vs no chat".
2. **llama.cpp's Vulkan backend is at parity with CUDA for the packaged model on this box.**
   Same binary tag the app pins (`b8571`), same RTX 4070, Qwen3.5-9B-Q4_K_M: Vulkan pp512
   2808 tok/s vs CUDA 2935-3007, tg128 **63.0 vs 55.1-55.3** (§2.1). The CPU tier on the same
   12-core desktop is 46 / 4.5 tok/s — an 8k RAG prefill is ~3 minutes on CPU and ~3 seconds on
   either GPU backend. The Vulkan Windows zip is 56 MB at b8571 (35 MB at today's b10760) against
   233 MB + 391 MB for the CUDA pair, and ggml-org publishes SLSA provenance for every asset.
3. **For the encoders, the ORT WebGPU plugin EP loads from the stock Maven Java jar and runs
   our fp16 models on the GPU — from ORT 1.24.4 up.** Probed here (§2.2): the pinned 1.24.3
   refuses with `ORT runtime version "1.24.3" is below the minimum required version "1.24.4"`;
   with 1.29.0 the plugin registers, enumerates the GPU through DXGI, and runs the embedder at
   14.8 ms vs 113 ms on the shipped FP32 CPU path (batch 1, seq 256). DirectML — the option 311
   evaluated — is in maintenance mode with no Java binding and is not the path.

Ranking (§5): **(b) Vulkan for chat** first, **(c) full path** second as a staged follow-on
whose encoder half is now de-risked to a specific mechanism, **(a) stay CUDA + CPU** third. The
opus chunk in §6 is written for (b). Nothing here picks for the founder; the numbers are the
input, the ranking is the argument.

Honest limit up front: **no AMD or Intel GPU was available.** Every measurement is
Vulkan-on-NVIDIA and WebGPU-on-NVIDIA. That bounds *backend overhead* (how much the portable
backend loses against the vendor one on the same silicon), not *vendor performance*. §2.3 is the
exact plan for the first AMD/Intel box.

## 1. State of the options, 2026-09-02

All URLs accessed 2026-09-02. Codebase citations are to this worktree at `67ee6052`.

### 1.1 What 311 decided, and why the comparison was wrong

311 §2 (2026-03-16) rejected the ORT DirectML EP as "worse than CUDA EP for this use case" —
~2× slower for transformers, `ORT_SEQUENTIAL` + no mem-pattern, one `Run()` thread per session —
and closed with "the only advantage is AMD/Intel GPU support, which is not relevant for
JustSearch's NVIDIA target" (`docs/tempdocs/311-runtime-alternatives-research.md:80-119`); §10
opens with "not directly applicable to JustSearch (NVIDIA-only)" (`:486-488`). Both comparisons
were against CUDA. Since then the project shipped the CPU tier (ADR-0019 FP32 encoder variants;
ADR-0047's `CPU_TOP_RUNG = 8192`, `ContextWindowPolicy.java:50`), went public, and 761 (2026-07-21)
raised a llama.cpp Vulkan pivot as an open decision (`761-linux-build-cost-estimate.md:86-91`).
887 §S row 1.1 marks the premise expired and routes the re-read here
(`887-improvement-landscape-register.md:125,143,290,325`). 311's DirectML *facts* were right;
they were the wrong facts to decide on, and DirectML is no longer the candidate anyway (§1.3).

### 1.2 llama.cpp Vulkan backend (chat/RAG workload)

**Prebuilt availability (primary, GitHub releases API).** The pinned `b8571` (2026-03-28) ships
`llama-b8571-bin-win-vulkan-x64.zip` (55,885,298 B), alongside `-cuda-12.4-x64` (232,957,511 B),
`cudart-…-cuda-12.4` (391,443,627 B), `-hip-radeon-x64` (353,979,107 B), `-sycl-x64`
(134,640,652 B) and `-cpu-x64` (38,708,753 B) —
`gh api repos/ggml-org/llama.cpp/releases/tags/b8571`. Today's nightly `b10760` (2026-09-02)
ships `-vulkan-x64` at 35.2 MB, `-cuda-12.4-x64` 254.3 MB, plus new `-rocm-7.14-x64` (244 MB),
`-openvino-2026.3.1-x64` (80 MB) and `-sycl-x64` (120 MB) assets
([tag API](https://api.github.com/repos/ggml-org/llama.cpp/releases/tags/b10760)). Do not pin
against `releases/latest`: it is a pointer release whose only asset is `nightly-tag.txt`
([releases/latest](https://api.github.com/repos/ggml-org/llama.cpp/releases/latest)).

**Op coverage for the packaged model (primary, raw `docs/ops/Vulkan.csv`).** The Qwen3.5 hybrid
needs `GATED_DELTA_NET`, `SSM_CONV`, `SOLVE_TRI` and q8_0-KV flash attention (ADR-0047 §3 and
D-010 launch line). In the raw CSV (19,443 rows, 15,708 supported / 3,735 not): `GATED_DELTA_NET`
36/36, `SSM_CONV` 45/45, `SOLVE_TRI` 24/24, `FLASH_ATTN_EXT` with `type_K=q8_0,type_V=q8_0`
349/349 supported; `MUL_MAT` with `type_a=q4_K` 42/76 (the 34 unsupported are shape/permutation
variants, not the quant). Unsupported ops are dominated by training-side `OUT_PROD`, `CONV_2D`,
`SET_ROWS`/`CPY` type combos, `LIGHTNING_INDEXER` and `DSV4_*` (DeepSeek-V4) —
[Vulkan.csv](https://raw.githubusercontent.com/ggml-org/llama.cpp/master/docs/ops/Vulkan.csv).
The measurement in §2.1 confirms the model runs fully offloaded (33/33 layers) on Vulkan with the
exact argv flags the app uses (`-fa on`, q8_0 KV).

**Matrix-core paths.** `VK_KHR_cooperative_matrix` is vendor-neutral, added for AMD RDNA3 and
Intel Arc ([PR #10597](https://github.com/ggml-org/llama.cpp/pull/10597));
`VK_NV_cooperative_matrix2` is NVIDIA-only ([PR #10206](https://github.com/ggml-org/llama.cpp/pull/10206))
and is what this box hit (`matrix cores: NV_coopmat2` in the bench stderr). Runtime kill switch
`GGML_VK_DISABLE_COOPMAT=1`.

**Windows driver situation.**
- **No Vulkan SDK on the user's machine.** `vulkan-1.dll` is the loader, shipped by the OS and
  IHV driver packages into `System32`
  ([Khronos Vulkan-Loader](https://github.com/KhronosGroup/Vulkan-Loader/blob/main/docs/LoaderApplicationInterface.md));
  Ollama documents the same: "On Windows most GPU vendors drivers come bundled with Vulkan
  support and require no additional setup steps" ([docs.ollama.com/gpu](https://docs.ollama.com/gpu)).
  Confirmed locally: `C:\Windows\System32\vulkan-1.dll` present, `vulkaninfo` reports instance
  1.4.341, driver 610.88.
- **AMD:** Adrenalin is the enabling path; AMD's Variable Graphics Memory (Adrenalin 25.8.1) is
  how iGPUs get large allocations for llama.cpp Vulkan on Windows
  ([AMD blog](https://www.amd.com/en/blogs/2025/amd-ryzen-ai-max-upgraded-run-up-to-128-billion-parameter-llms-lm-studio.html)).
  ROCm on Windows remains a subset (HIP SDK 7.2; "the entire ROCm stack is not yet supported on
  Windows", [AMD release notes](https://www.amd.com/en/resources/support-articles/release-notes/RN-AMDGPU-WINDOWS-PYTORCH-7-1-1.html)).
- **Intel is the risk.** Three 2026 issues, all closed stale without maintainer response:
  KHR-coopmat GPU TDR on Arc 140V (Lunar Lake) with drivers 101.8509/101.8531, workaround
  `GGML_VK_DISABLE_COOPMAT=1` ([#20554](https://github.com/ggml-org/llama.cpp/issues/20554));
  all 8-bit quants 3-4× slower than Q6_K on Arc A770/Windows
  ([#24002](https://github.com/ggml-org/llama.cpp/issues/24002)); Arrow Lake iGPU crashes with
  larger models ([#19327](https://github.com/ggml-org/llama.cpp/issues/19327)).
- **Memory reporting on iGPUs is unreliable.** Vulkan free/total comes from
  `vkGetPhysicalDeviceMemoryProperties`; a Radeon 8060S with 96 GB UMA reported 32 GB
  ([#16832](https://github.com/ggml-org/llama.cpp/issues/16832)); a downstream app's free-VRAM
  gate blocked models that fit ([unsloth #9454](https://github.com/unslothai/unsloth/issues/9454)).
  This matters for §4.2.

**Performance relative to CUDA, external.** A100, CUDA 12.4 vs Vulkan (2025-11): Llama-8B Q4_K
pp512 4462 vs 2972 (1.50×), tg128 151 vs 116 (1.30×)
([#17273](https://github.com/ggml-org/llama.cpp/issues/17273)); RTX 3080 in 2024-12 was 2.6×/2.1×
([#10879](https://github.com/ggml-org/llama.cpp/discussions/10879)). The gap has been closing;
on this RTX 4070 at b8571 it is gone for the packaged model (§2.1). AMD/Intel points from the
same scoreboard (Llama-2 7B Q4_0, 2025-01): RX 7900 XTX pp 2062 / tg 144; Arc A770 (Windows) pp
314 / tg 45; Tiger Lake Xe iGPU pp 42 / tg 7.3. **No primary same-machine Vulkan-vs-CPU
comparison exists for AMD/Intel** — that is what §2.3 measures.

**Who ships it.** Ollama (default, non-experimental, AMD+Intel on Windows —
[docs](https://docs.ollama.com/gpu)); LM Studio ("Vulkan llama.cpp" runtime, AMD co-marketed);
KoboldCpp (`--usevulkan`, recommended for AMD/Intel — [koboldai.com](https://koboldai.com/KoboldCpp/)).

**Supply chain.** No `SHA256SUMS` asset on any release; ggml-org publishes GitHub artifact
attestations with SLSA provenance v1 over all 27 assets of each release, verifiable offline with
`gh attestation verify <file> --repo ggml-org/llama.cpp`
([attestations](https://github.com/ggml-org/llama.cpp/attestations)). The project's SHA pin
(`modules/ui/build.gradle.kts:367-372`; registry `sha256` per supporting file,
`model-registry.v2.json:311-347`) stays the primitive; attestation is an additional check.
Local SHA-256 of the b8571 Vulkan zip downloaded for §2:
`759abe1088601c6ec982fa315ba878e4fe040caf590b6cd8ce38ba68e2e93047` (CPU zip matched the pinned
`40D8C4B9…EA31C`).

### 1.3 ONNX Runtime execution providers that are not CUDA, on Windows x64 (encoder workload)

**DirectML: dead end.** "DirectML is in maintenance mode … no new functionality or feature
updates are planned" ([microsoft/DirectML README](https://github.com/microsoft/DirectML)); the ORT
EP page says "sustained engineering"; Windows ML labels it "DirectML (legacy)". Last DirectML
redistributable 1.15.4 (2024-10-28). Constraints 311 listed are still documented as current
(`ORT_SEQUENTIAL`, `enable_mem_pattern=false`, one `Run` thread per session)
([ORT DirectML EP](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html)).
**No Java binding**: bindings are C and C#; the only Java artifacts are `onnxruntime` (CPU) and
`onnxruntime_gpu` (CUDA/TensorRT) ([ORT install](https://onnxruntime.ai/docs/install/)) — a
DirectML Java path means a custom ORT build with `--use_dml --build_java`
([build docs](https://onnxruntime.ai/docs/build/eps.html)).

**Windows ML (2025+): the platform answer, but not reachable from Java.** ORT-based, downloads
vendor EPs (Intel OpenVINO, AMD MIGraphX, Qualcomm QNN, NVIDIA TensorRT-RTX, Microsoft WebGPU
*experimental*) as serviced MSIX components on Windows 11 24H2+; languages C#, C++/WinRT, C,
Python — no Java
([overview](https://learn.microsoft.com/en-us/windows/ai/new-windows-ml/overview),
[supported EPs](https://learn.microsoft.com/en-us/windows/ai/new-windows-ml/supported-execution-providers),
[install EPs](https://learn.microsoft.com/en-us/windows/ai/new-windows-ml/initialize-execution-providers)).
MIGraphX-on-Windows exists only through this catalog, pinned to one exact driver version and
"not supported for GenAI scenarios"; each EP carries a vendor licence. The C-API route hands the
EP DLL path to ORT's own `RegisterExecutionProviderLibrary`, which is the same mechanism §2.2
exercises from Java — so WinML-distributed EPs are a *possible* future source of DLLs, unproven.

**OpenVINO EP (Intel only).** Supports Intel CPU/GPU/NPU, FP16 on GPU; version-paired (ORT 1.24.1
↔ OV 2025.4.1); prebuilt only as pip / Intel NuGet; Java = custom build
([ORT OpenVINO EP](https://onnxruntime.ai/docs/execution-providers/OpenVINO-ExecutionProvider.html)).
Vendor-specific, so it cannot be the one non-NVIDIA path.

**ROCm / MIGraphX EP in mainline ORT: Linux only**
([ORT MIGraphX EP](https://onnxruntime.ai/docs/execution-providers/MIGraphX-ExecutionProvider.html)).
**Vulkan EP: never built.** [#21917](https://github.com/microsoft/onnxruntime/issues/21917) is still
open; [#22077](https://github.com/microsoft/onnxruntime/issues/22077) proposed a native WebGPU EP
instead, and that shipped.

**WebGPU plugin EP: the "ORT on any DX12/Vulkan GPU" path.** Native (non-browser), via Dawn;
Windows backends D3D12 and Vulkan; ships as a plugin library (`onnxruntime_providers_webgpu.dll`;
pip `onnxruntime-ep-webgpu`, .NET `Microsoft.ML.OnnxRuntime.EP.WebGpu`)
([ORT WebGPU EP](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html)).
Plugin v0.3.0 released 2026-08-24; requires ORT ≥ 1.24.4 (wheel README, quoted in §2.2). ORT
1.23 introduced the plugin-EP ABI and the Java bindings expose it: `javap` on the pinned
`onnxruntime-1.24.3.jar` shows `OrtEnvironment.registerExecutionProviderLibrary(String,String)`,
`getEpDevices()`, and `SessionOptions.addExecutionProvider(List<OrtEpDevice>, Map)` — plus the
`addDirectML(int)`, `addOpenVINO(String)`, `addROCM`, `addWebGPU(Map)` methods whose binaries the
jar does not ship (`unzip -l`: win-x64 natives are `onnxruntime.dll`, `onnxruntime4j_jni.dll`,
`onnxruntime_providers_shared.dll`; the GPU jar adds `_cuda.dll` and `_tensorrt.dll` only).
"API exists" ≠ "binary ships" — except for the plugin route, which §2.2 proves.

**llama.cpp as an encoder host (the other route to encoders on Vulkan).** `llama-server` has
`/v1/embeddings` and `/rerank` (`--embedding --pooling rank`; bge-reranker-v2-m3 class)
([server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md));
XLM-RoBERTa encoders convert ([#8789](https://github.com/ggml-org/llama.cpp/issues/8789) closed).
But BGE-M3 sparse/ColBERT heads are fork-only ([#25109](https://github.com/ggml-org/llama.cpp/issues/25109)),
and there is no SPLADE MLM head or token-classification (NER) head support. It would cover the
dense embedder and cross-encoder and leave SPLADE + NER on CPU, and would fork the one ORT
session pipeline (D-007). Not recommended; recorded so nobody re-derives it.

## 2. Measurements (this box) and the plan for a non-NVIDIA box

Box: i7-12700K (12C/20T, 31.7 GB), RTX 4070 12,282 MiB, driver 610.88, Windows 11 10.0.26200. The
i7's Xe iGPU is not enumerated (`Get-PnpDevice -Class Display` shows only the RTX + a virtual
display driver), so no Intel path could be measured here. **Background load caveat:** a game
client and browsers held 2.8 GB and 22-40 % GPU utilisation throughout; both backends were
measured under the same load, and the CUDA 9B row was re-run last to rule out order effects
(agrees within noise). Artifacts under `tmp/903-bench/` (gitignored `/tmp/`); numbers reproduced
below in full.

### 2.1 Chat: llama-bench, b8571, same box, `-p 512 -n 128 -r 3 -fa 1` (`-ngl 99` on GPU)

| Model | Backend | pp512 tok/s | tg128 tok/s |
|---|---|---:|---:|
| Qwen3.5-9B Q4_K_M (packaged standard) | CUDA 12.4 (run 1 / re-run) | 2935 ± 241 / 3007 ± 339 | 55.08 ± 0.22 / 55.31 ± 0.40 |
| | **Vulkan** (NV_coopmat2) | **2808 ± 11** | **63.01 ± 0.71** |
| | CPU (12 threads) | 45.8 ± 2.3 | 4.52 ± 0.29 |
| Qwen3.5-4B Q4_K_M (compact profile) | CUDA 12.4 | 4616 ± 182 | 86.9 ± 3.2 |
| | **Vulkan** | **4264 ± 149** | **98.0 ± 1.3** |
| | CPU (12 threads) | 80.0 ± 3.1 | 9.22 ± 0.39 |

Reading: Vulkan is 0.93-0.96× CUDA on prefill and 1.13-1.14× on generation for both models on
this card. The CPU tier is 58-65× slower on prefill and 9-14× slower on generation. At the CPU
rung (8192) a full RAG prefill is 179 s on CPU; at the GPU rung (32768) it is 11-12 s on either
GPU backend. The tg advantage of Vulkan over CUDA is repeatable here (three reps each, two CUDA
runs) but is a property of this driver/build pair, not a general claim.

Command (one row): `tmp/903-bench/vulkan/llama-bench.exe -m F:\justsearch-public\models\Qwen_Qwen3.5-9B-Q4_K_M.gguf -p 512 -n 128 -r 3 -fa 1 -ngl 99 -o json`.
Binaries: the CUDA pair from `modules/ui/build/llama-server/prebuilt/` (already pinned), the
Vulkan and CPU zips via `gh release download b8571 -R ggml-org/llama.cpp -p 'llama-b8571-bin-win-{vulkan,cpu}-x64.zip'`.

### 2.2 Encoders: ORT WebGPU plugin EP from Java, same box

Probe: `tmp/903-bench/webgpu/WebGpuProbe.java` (source in Appendix A) run with Temurin 25 against
the stock Maven `onnxruntime` (CPU) jar, plugin DLL from the `onnxruntime-ep-webgpu==0.3.0` wheel
(3 DLLs: `onnxruntime_providers_webgpu.dll`, `dxcompiler.dll`, `dxil.dll`; 13 MB). One run =
`session.run` on a synthetic `[1, seq]` input after 5 warm-ups, mean of 50.

| ORT jar | Result |
|---|---|
| 1.24.3 (pinned, `gradle/libs.versions.toml:28`) | `registerExecutionProviderLibrary` throws `ORT_FAIL … ORT runtime version "1.24.3" is below the minimum required version "1.24.4"` |
| 1.29.0 (current Maven) | registers; `getEpDevices()` lists `WebGpuExecutionProvider` on `NVIDIA GeForce RTX 4070` (DXGI metadata, `Discrete=1`, 12010 MB); sessions create and run |

| Model (file) | seq | WebGPU ms/run | CPU ms/run | Note |
|---|---:|---:|---:|---|
| NER `model_fp16.onnx` | 64 | **6.21** | 46.79 | CPU fp16 = the ADR-0019 cast-overhead path |
| NER `model.onnx` (INT8, shipped CPU variant) | 64 | 31.84 | **7.02** | INT8 QOperator nodes fall back — the F-013 shape; WebGPU must load the fp16 variant |
| Embedding gte-multilingual-base `model_fp16.onnx` | 256 | **14.76** | 1437 | |
| Embedding `model.onnx` (FP32, shipped CPU tier) | 256 | 18.35 | **113.2** | fp16-on-WebGPU vs fp32-on-CPU = **7.7×** |
| Reranker gte-multilingual-reranker-base `model_fp16.onnx` | 256 | **14.76** | 1421 | |
| Reranker `model.onnx` (shipped CPU variant) | 256 | 132.1 | **56.95** | fp16-on-WebGPU vs shipped CPU = **3.9×** |

Outputs agree to fp16 precision (embedding `out[0..3]` -0.8064/1.1678/-0.7894/-0.0275 on FP32
both EPs; -0.8296/1.1348/-0.7759/0.0007 on fp16 WebGPU). Batch 1 understates the GPU side —
production embeds at batch 8 and reranks top-20 in one batch, where CPU does not scale. Session
creation on WebGPU was 0.5-3.6 s (shader compile); ORT's `VerifyEachNodeIsAssignedToAnEp`
warning fired on the fp16 models (shape ops kept on CPU by design), no fallback of compute nodes.

What this proves: the mechanism (stock jar + plugin DLL + `registerExecutionProviderLibrary`)
works from Java on Windows with no custom ORT build, after an ORT bump past 1.24.3. What it does
not prove: AMD/Intel behaviour, the Vulkan (vs D3D12) Dawn backend, multi-session arena behaviour
under the Worker's GPU lease, or throughput at production batch sizes — §2.3 and §6-follow-on.

### 2.3 Plan for the first AMD or Intel machine (exact commands)

Prerequisites: a Windows 11 box with an AMD RDNA2+/Radeon 780M+ or Intel Arc/Xe (Alder Lake+)
GPU and current vendor driver; the repo checked out; `models/` populated (`Qwen_Qwen3.5-9B-Q4_K_M.gguf`,
`compact/Qwen3.5-4B-Q4_K_M.gguf`, `models/onnx/{gte-multilingual-base,reranker,ner}`).

1. **Inventory (record in the run log):**
   `vulkaninfo --summary`, `Get-CimInstance Win32_VideoController | Select Name,DriverVersion`,
   `Get-CimInstance Win32_Processor | Select Name,NumberOfCores`, RAM.
2. **Chat, standalone (backend overhead + CPU baseline):**
   ```
   gh release download b8571 -R ggml-org/llama.cpp -p 'llama-b8571-bin-win-vulkan-x64.zip' -p 'llama-b8571-bin-win-cpu-x64.zip'
   unzip each into tmp/903-bench/{vulkan,cpu}
   tmp/903-bench/vulkan/llama-bench.exe -m models/Qwen_Qwen3.5-9B-Q4_K_M.gguf -p 512 -n 128 -r 3 -fa 1 -ngl 99 -o json > tmp/903-bench/results/vulkan-9b.json
   tmp/903-bench/cpu/llama-bench.exe    -m models/Qwen_Qwen3.5-9B-Q4_K_M.gguf -p 512 -n 128 -r 3 -fa 1 -o json > tmp/903-bench/results/cpu-9b.json
   (repeat both for models/compact/Qwen3.5-4B-Q4_K_M.gguf)
   Intel only: repeat the Vulkan rows with GGML_VK_DISABLE_COOPMAT=1 (issue #20554), and with a Q6_K quant if #24002 reproduces.
   ```
   Also run `tmp/903-bench/vulkan/llama-server.exe --list-devices` and record total/free —
   compare against the OS-reported VRAM/UMA to size the §4.2 detection problem on that vendor.
3. **Chat, end-to-end through the app** (uses the project harness; needs the dev stack):
   ```
   ./gradlew.bat build -x test
   # start the stack with the Vulkan variant forced as the server binary and full offload:
   #   -Djustsearch.server.exe=<abs path>\tmp\903-bench\vulkan\llama-server.exe -Djustsearch.gpu.layers=99
   #   (ai_activate {chatProfile:"standard"} once /api/health is ready)
   cd scripts/jseval && python -m jseval llm-bench --profile regression --output-dir tmp/903-bench/results/llm-bench-vulkan
   # repeat with -Djustsearch.gpu.layers=0 (CPU rung) → tmp/903-bench/results/llm-bench-cpu
   ```
   `llm-bench` reports TTFT, e2e summarize and tokens/s medians (F-012;
   `scripts/jseval/jseval/llm_bench.py:15-19` profiles). Confirm on `/api/inference/status` that
   `contextWindow.rung` is 32768 on Vulkan and 8192 on CPU — the ladder keys off `gpuLayers > 0`,
   not the backend (`ContextWindowPolicy.java:127`).
4. **Encoders, standalone:**
   ```
   python -m pip download onnxruntime-ep-webgpu --no-deps -d tmp/903-bench/webgpu --only-binary=:all:   # then unzip the wheel
   curl -LO https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.29.0/onnxruntime-1.29.0.jar
   javac -cp onnxruntime-1.29.0.jar WebGpuProbe.java     # Appendix A
   java --enable-native-access=ALL-UNNAMED -cp "onnxruntime-1.29.0.jar;." WebGpuProbe <abs>\onnxruntime_providers_webgpu.dll models/onnx/gte-multilingual-base/model_fp16.onnx 256
   (repeat for model.onnx, reranker/model_fp16.onnx, reranker/model.onnx, ner/model_fp16.onnx, ner/model.onnx)
   ```
   Record `getEpDevices()` output (vendor, `Discrete`, `DxgiVideoMemory`) — on an iGPU this is the
   only place the shared-memory size shows up.
5. **Encoders, production batch sizes** (after the ORT bump in §6-follow-on lands; until then
   the bench is CUDA-only): `./gradlew.bat :modules:benchmarks:encoderBatchSweepBench
   -PbenchEmbedBatches=8,16 -PbenchSpladeBatches=4,8` (`modules/benchmarks/build.gradle.kts:291-317`).
6. **Record** everything under `docs/reference/inference-runtime-register.md` Q-003 (added by
   this tempdoc) with the hardware inventory as the row key.

## 3. Packaging and supply-chain shape

**Today.** Chat GPU support is one `cuda-runtime` package (`model-registry.v2.json:298-347`):
five `supportingFiles` totalling 1.99 GB — the llama.cpp CUDA zip (233 MB) + cudart (391 MB) from
ggml-org, and `ort-cuda-runtime-12.4.zip` (563 MB), `cudnn-9-runtime-12.4.zip` (634 MB),
`ort-native-cuda12-v1.24.3.zip` (168 MB) from `justsearch-releases`. `requiresCuda: true`;
`necessity: infrastructure` so it is not declinable (`Necessity.java:27-35`); it installs into
`native-bin/llama-server/variants/cuda12` and every FP16 ONNX package declares
`dependsOn: ["cuda-runtime"]`. Build-time staging mirrors the same pair into `variants/cuda12`
(`modules/ui/build.gradle.kts:396-407, 694-779`), gated by `-PincludeCuda`. The signed-mirror
override is a paired URL+SHA gradle property (`:375-394`, 760/772 §K); CUDA/cudart zips have no
build-time pin, only the registry pin (760 observation, still true at `:578-640`).

**Option (b) adds one small pack, not a universal binary.**
- New registry package `vulkan-runtime`: one supporting file, the ggml-org Vulkan zip at the
  pinned tag (56 MB at b8571; 35 MB if the pin moves), `extract: true` into
  `variants/vulkan`, `sha256` pinned, licence MIT (ggml). `requiresCuda: false`; a new
  `requiresAccelerator: "vulkan"` (or a generalised `requires: {accelerator}`) so the planner's
  hardware gate is not CUDA-shaped (`InstallPlanner.java:189-205, 312`).
- Chat model packages depend on *an* accelerator runtime, not on `cuda-runtime` specifically
  (`ModelPackage.java:51-54` "the only edge is … → cuda-runtime" must become
  `→ cuda-runtime | vulkan-runtime`).
- Universal binary considered and set aside: the Vulkan zip already contains a full CPU backend
  (`ggml-cpu-*.dll`) and measured parity with CUDA here, so *technically* one Vulkan zip could
  serve NVIDIA too and retire 624 MB of CUDA payload for chat. Not recommended in this chunk:
  the CUDA lane is field-proven, ORT CUDA (encoders) still needs cudart/cuDNN regardless, and
  the Intel driver issues argue for keeping vendor-native where it exists. Recorded as a
  reassess trigger in §5(b).
- Installer size unchanged (runtime packs are post-install downloads, 772/840). Download for a
  non-NVIDIA user: 56 MB runtime + 5.9 GB GGUF + 0.67 GB mmproj instead of nothing today.
- Sign-once mirror (760 §K): the Vulkan zip has 2 EXEs the app uses (`llama-server.exe`,
  optionally none else) plus ~20 DLLs, all unsigned upstream — same shape as the CPU zip the
  mirror producer already handles (`scripts/release/sign-vendored-payload.ps1`). SLSA
  attestation verification (`gh attestation verify`) can be added to the registry publish check
  next to `check-ort-native-asset.mjs`.

**Option (c) adds a second small pack and an ORT bump.**
- `webgpu-ep` package: the three DLLs from the `onnxruntime-ep-webgpu` wheel (13 MB), MIT.
  Source: PyPI wheel today; the .NET NuGet carries the same DLL. Pin the wheel SHA; there is no
  standalone GitHub asset yet.
- ORT `1.24.3 → ≥1.24.4` (Maven has 1.25.0 … 1.29.0; no 1.24.4). This is the expensive line:
  the CUDA pack is version-coupled (`ort-native-cuda12-v<ver>.zip`, `scripts/release/check-ort-native-asset.mjs:6`;
  cut-a-release ORT-bump checklist), the `.optimized` graph caches are ORT-version-tied
  (ADR-0019 alternatives), and the `onnxruntime_gpu` jar is re-trimmed at build time
  (`build.gradle.kts:1475-1518`). One bump, done once, for both CUDA and WebGPU.

## 4. Invariant check

| Invariant / surface | Today (file:line) | Under (b) Vulkan chat | Under (c) + WebGPU encoders |
|---|---|---|---|
| **ADR-0004 single-tenant GPU lease** | Head sets `main_gpu_active`; Worker releases encoder GPU sessions, keeps a CPU query-embed (ADR-0004:96-114); D-008 reconciler is the only mode switcher | **Unchanged.** llama-server on Vulkan is still one GPU tenant; encoders are CPU on non-NVIDIA so there is nothing to evict. The flag is backend-agnostic. | Unchanged in shape; the Worker's `releaseGpu()` must close WebGPU sessions the same way it closes CUDA ones (`NativeSessionHandle.java:674-703` closes CUDA run options + session). Dawn's device memory is not a BFC arena — `arenaCapBytes > 0 ⇔ GPU` (`ModelSessionPolicyResolver.java:200-214`) needs a second self-describing signal (EP kind), and `gpu_mem_limit`/`arena_extend_strategy` (`SessionOptionsApplier.java:85-98`) do not apply. Co-residency budgets (F-010) are unmeasured on WebGPU. |
| **VRAM tiering** | NVML-first, nvidia-smi fallback, else `"none"` (`GpuCapabilitiesService.java:108-167`); `detectVramTier` thresholds (`VramFlagsUtil.java:125-139`); `HardwareProfile.vramBytes` from nvidia-smi (`HardwareProfile.java:18-24`); gpu.layers=99 iff NVML VRAM ≥ 7.5 GB (`HeadlessApp.java:153-190`) | **Needs a Vulkan VRAM source.** Add a `vulkan` axis to `GpuCapabilities` from `vkEnumeratePhysicalDevices` + memory properties (FFM, like `GpuDriverApiProbe.java:46-51` does for `nvcuda.dll`) or from `llama-server --list-devices`. Discrete: device-local heap is a usable "total". UMA/iGPU: heap reporting is wrong on Windows (#16832) — use host RAM with a UMA rule instead of the 7.5 GB VRAM floor. | Same source serves ORT; `DxgiVideoMemory` from `getEpDevices()` is a third opinion (12,010 MB here vs NVML 12,282 MiB). |
| **ADR-0047 ladder** | `gpuBacked = gpuLayers > 0` selects 32768 vs 8192 (`ContextWindowPolicy.java:127`); `-fit off`, step-down on `PROCESS_EXITED` (`LlamaServerOps.java:249-262`); free VRAM recorded, never an input | **Survives as designed.** The ladder is exactly the "try, fail loud, step down" mechanism an unreliable-memory backend needs. Verify that a Vulkan OOM at a rung is a hard nonzero exit like CUDA's `exit 127` (D-010) — the §6 live check. `freeVramBytes` on the activation record becomes vendor-labelled. | Unchanged. |
| **`GPU_TOP_RUNG` on an iGPU** | 32768 assumes prefill at GPU speed | An 8060S-class iGPU is ~5-10× slower than a 4070 on prefill (§1.2 scoreboard); 32k prefill ≈ 1-2 min. Consider a `UMA_TOP_RUNG` (16384) as a third rung selector — a reassess item, not a blocker; ADR-0047 §Reassess already names "a supported card cannot fit the top rung". | — |
| **Runtime variant selection** | `findServerExecutable` prefers `variants/cuda12` when `gpuLayers > 0` (`InferenceConfig.java:150-154, 505-520`); warning when GPU layers requested on the CPU variant (`LlamaServerOps.java:274-284`); `RuntimeActivationService.startActivate(variantId)` (`:669-700`) | Add `variants/vulkan` as the second preference; `variantId = "vulkan"` activation; the "CPU variant" warning text stays true. | — |
| **Install matrix** | `DownloadProfile {GPU_FULL, GPU_LITE, CPU}` (`DownloadProfile.java:16-25`); `HardwareProfile.downloadProfile()` (`:27-35`); 840 §5.1 "Not supported here: cuda-runtime on non-NVIDIA, GGUF under the VRAM floor" (`840:107`) | New profile `VULKAN_FULL` (GGUF yes, FP32/INT8 ONNX); the 840 row splits into "cuda-runtime on non-NVIDIA (still not supported)" and "vulkan-runtime on CUDA machines (not offered, CUDA wins)". `AiInstallService.java:1053,1796` gate chat on `includesGguf()` — unchanged semantics. | `VULKAN_FULL` also downloads FP16 ONNX + `webgpu-ep`. |
| **Chip labels** | `chip-facts.v1.json:9-10` has `accelerator-cuda` and `accelerator-rocm` fact-shapes; `core.gpu.accel` reads `gpu.cudaFunctional ? 'CUDA' : ''` (`facts.ts:125-137`); `GpuStatusView` carries `available/cudaFunctional/source/confidence` (`GpuStatusView.java:21-36`) | Add `accelerator-vulkan` fact-shape (`\bvulkan\b`) so a hand-typed "Vulkan" chip fails the gate; `GpuStatusView` gains `accelerator: "cuda12" | "vulkan" | null`; `core.gpu.accel` projects it. The `rocm` regex (887's "only residue") stays: ROCm is now a real upstream Windows asset. | `EncoderRuntimeExplainer` / `OrtCudaStatus` (D-009) are CUDA-named; the observed `executionProvider` field already exists and would read `WebGpuExecutionProvider`. |
| **Fail-closed model integrity / SHA pins** | Registry `sha256` per file; build-time pin on the CPU zip; signed-mirror pairing | One more pinned asset + optional SLSA verify. No new trust root. | Wheel-sourced DLLs need a pinned SHA and a mirror on `justsearch-releases` (PyPI is not a release host the project pins today). |
| **`ExecutionProvider` enum** | `CPU, CUDA, LLAMA_SERVER` (`ExecutionProvider.java:5-14`); `VariantSelection` EP is "CPU or CUDA" (`VariantSelection.java:14`) | Unchanged (encoders stay CPU). | Add `WEBGPU`; `deriveCpuOptLevel` (`ModelSessionPolicyResolver.java:85-86`) and `gpuActuallyUsed` (`:102`) generalise from `== CUDA` to "is GPU EP". |
| **Enterprise policy** | `gpuAccelerationEnabled=false` clamps `-ngl 0` (`LlamaServerOps.java:243-247`) | Applies to Vulkan identically (same `gpuLayers` path). | Same policy gates WebGPU sessions. |

Net: (b) touches detection, install planning, variant selection and labels — all Head-side and
all in code that already has the NVIDIA-shaped seam; it does not touch the lease, the ladder, the
Worker or ORT. (c) touches the Worker's session pipeline at its one apply site (D-007), which is
the *right* place but is also where the closure property lives.

## 5. Options, ranked

### Rank 1 — (b) llama.cpp Vulkan for chat; encoders stay CPU on non-NVIDIA

- **Reach:** every Windows machine with a Vulkan-capable AMD or Intel GPU and ≥ ~8 GB of usable
  device memory (discrete RDNA2+, Radeon 780M/890M/8060S with VGM, Arc A/B, Xe on Alder Lake+
  with caveats). Today these users get **no chat**. With (b) they get chat, RAG, summarization,
  agent and VDU at GPU speed on the same model files, with the same ladder and lease.
- **Benefit, measured:** on the one card available, Vulkan ≈ CUDA (§2.1). External data puts
  RDNA3 discrete at ~70 % of a 4070 on prefill and Arc A770 at ~10 %; even the A770 is 7× the
  CPU tier on prefill and 10× on generation (§1.2 scoreboard vs §2.1 CPU rows).
- **Cost:** one pinned 35-56 MB asset; a Vulkan device probe; one download profile; variant
  preference order; label plumbing; docs + ADR; live verification on NVIDIA (forcing the Vulkan
  variant) plus the §2.3 plan on the first AMD/Intel box. Estimate M (one opus lane, §6).
  Ongoing: a second llama.cpp variant to bump per pin move (same tag, same release, one more
  SHA), and Intel driver-regression triage that upstream is not doing.
- **Risk:** Intel (§1.2) — mitigated by shipping `GGML_VK_DISABLE_COOPMAT` as a config knob and
  by the ladder; iGPU memory misreporting — mitigated by not gating on Vulkan-reported free
  memory (ADR-0047 already forbids VRAM arithmetic).
- **Reassess triggers:** (i) after the first AMD/Intel measurement, whether `GPU_TOP_RUNG`
  needs a UMA rung; (ii) if a later pin shows Vulkan ≥ CUDA on NVIDIA across models, the
  universal-binary question (§3) becomes a payload-size win worth an ADR; (iii) if Windows ML
  ships a GenAI-capable AMD path, revisit vendor-native.

### Rank 2 — (c) full non-NVIDIA path: (b) plus WebGPU plugin EP for the encoders

- **Reach:** same machines; adds GPU-speed enrichment (embedding backfill, SPLADE, NER) and
  reranking. On this card fp16-on-WebGPU is 7.7× the shipped FP32 CPU embedder and 3.9× the
  shipped CPU reranker at batch 1 (§2.2); production batches widen it.
- **Benefit:** the CPU tier's real pain is enrichment throughput and reranker latency
  (FW-002: CPU cross-encoder is "borderline for interactive search"), not search itself.
- **Cost:** everything in (b), plus: ORT bump `1.24.3 → 1.29.0` with the CUDA-pack re-cut
  (§3); a `WEBGPU` EP through the D-007 pipeline (new `ModelSessionPolicy` fields for a
  non-arena backend, `SessionOptionsApplier` second branch, `NativeSessionHandle` plugin
  registration once per process, `OrtCudaStatus` generalised); FP16 variants downloaded on a
  new profile; the D-013-class trap (INT8 model on a GPU EP, §2.2 row 2) guarded by
  `VariantSelector`; co-residency and lease behaviour measured. Estimate L (two opus lanes:
  the ORT bump is its own PR with its own live check, then the EP).
- **Risk:** WebGPU EP is labelled experimental by Windows ML and is on a 0.x release train;
  Dawn backend choice (D3D12 vs Vulkan) is not controlled from the Java API surface probed;
  operator coverage for SPLADE's MLM head and the ModernBERT-class reranker at long sequence is
  unmeasured. ORT bumps have bitten before (F-004 5.7× regression class).
- **Why second, not first:** (b) is where the user-visible cliff is (no chat → chat) and it is
  independent of the ORT bump; (c)'s encoder half is now de-risked to a mechanism but not to
  production behaviour, and it should ride the next ORT bump rather than force one.

### Rank 3 — (a) stay CUDA + CPU, restate the decision with today's grounds

- **Restated grounds (if chosen):** "Non-NVIDIA users get CPU search without chat. The CPU tier
  is the supported experience there. Vulkan chat is measured viable (903 §2) but the project
  does not ship or support a second GPU vendor path in 2026." Review date: 2027-03 or the next
  llama.cpp pin bump, whichever first.
- **What this buys:** zero maintenance surface; no Intel driver triage; no second variant.
- **What it costs:** the CPU tier stays "search only" for a large share of laptops, and the
  README/matrix should say so honestly ("no chat without an NVIDIA GPU") — today `840:107`
  says "not supported here" for the runtime, which reads as a limitation rather than a choice.
- **Why third:** the 311 rejection was cheap because no alternative existed at the time; today
  the alternative is a 35-56 MB attested upstream asset with measured parity and three shipping
  desktop products behind it. Not doing it is a product decision, not an engineering one — which
  is exactly why it is listed and not dismissed.

## 6. Opus implementation chunk — option (b): llama.cpp Vulkan runtime for chat

### Briefing for the agent picking this up

Fresh start. Read this file (§1.2, §3, §4, this section), then 887 A1 §1.1-1.2, ADR-0047,
`docs/explanation/05-ai-architecture.md` §llama-server and §context window,
`docs/explanation/16-gpu-booster-pack.md`, tempdoc 772 §K and 840 §5. Load `/inference-runtime`
(register — update D-/Q-003 before closing), `/installer` (registry + packs) and `/dev-stack`.
Work in a worktree. Code homes: `modules/gpu-bridge` (capabilities), `modules/ort-common`
(`GpuDriverApiProbe` pattern), `modules/app-services/.../gpu/GpuCapabilityResolver.java`,
`modules/configuration/.../model/{HardwareProfile,DownloadProfile,InstallPlanner,ModelPackage}.java`
+ `resources/ai/model-registry.v2.json`, `modules/app-inference/.../InferenceConfig.java` +
`LlamaServerOps.java`, `modules/ui/.../HeadlessApp.java` (Phase F), `modules/ui/build.gradle.kts`
(staging), `modules/app-api/.../status/GpuStatusView.java`, `modules/ui-web/src/shell-v0/display/facts.ts`,
`governance/chip-facts.v1.json`. Verify live (`use-every-verification-tier`): the NVIDIA dev box
can run the Vulkan variant end to end; that is the acceptance floor. Four PRs.

### Thesis

Every seam this needs already exists and is NVIDIA-shaped: detection (`nvcuda.dll` probe, NVML),
the install matrix (`requiresCuda`, `cudaFunctional`), variant selection (`variants/cuda12`),
labels (`cudaFunctional`). The work is to give each seam a second value, not a second shape.
Nothing in the GPU lease, the reconciler, the ladder or the Worker changes.

### Decisions made for you

- **Detection:** add `GpuCapabilities.Vulkan` (`available, deviceName, vendorId, deviceType
  DISCRETE|INTEGRATED, deviceLocalHeapBytes, uma`) probed by FFM against `vulkan-1.dll`
  (`vkCreateInstance` → `vkEnumeratePhysicalDevices` → `vkGetPhysicalDeviceProperties2` +
  `vkGetPhysicalDeviceMemoryProperties`), mirroring `GpuDriverApiProbe`'s fail-safe shape (never
  throws; returns a reason). Prefer the first discrete non-NVIDIA device; ignore NVIDIA devices
  on the Vulkan axis (CUDA wins there). Do **not** spawn `llama-server --list-devices` for
  detection — it needs the pack downloaded first.
- **Accelerator precedence:** `HardwareProfile` gains `accelerator ∈ {NONE, CUDA, VULKAN}`;
  `CUDA` iff `cudaFunctional`, else `VULKAN` iff the Vulkan axis is available, else `NONE`.
  `DownloadProfile` gains `VULKAN_FULL(cuda=false, gguf=true)`; `downloadProfile()` returns it
  when `accelerator == VULKAN` and the memory rule passes.
- **Memory rule for the GGUF floor:** discrete Vulkan → `deviceLocalHeapBytes ≥ MINIMUM_VRAM_FOR_GGUF`
  (unchanged 7.5 GB); UMA/integrated → total physical RAM ≥ 16 GiB (the Vulkan heap number is
  unreliable there, #16832). Below the rule: `GPU_LITE`-equivalent behaviour (search only),
  with the skip reason naming the actual constraint like `InstallPlanner.java:210-233` does.
- **Registry:** package `vulkan-runtime` — `necessity: infrastructure`, `tier: runtime`,
  `installRoot: native-bin/llama-server/variants`, `targetDir: vulkan`, `requiresAccelerator:
  "vulkan"` (new optional field, default absent; `requiresCuda` stays for `cuda-runtime` and is
  read as `requiresAccelerator: "cuda"` internally), one `supportingFiles` entry pinned to the
  ggml-org `b8571` Vulkan zip with the SHA in §1.2. Chat packages' `dependsOn` becomes
  `["cuda-runtime"]` **or** `["vulkan-runtime"]` resolved by accelerator (`ModelPackage` gains
  `dependsOnFor(accelerator)`), so a `VULKAN_FULL` plan never pulls `cuda-runtime`.
- **Build staging:** `stageLlamaVulkanVariant` next to `stageLlamaCudaVariant`
  (`build.gradle.kts:694-779`), gated `-PincludeVulkan` (default true), writing
  `runtime-version.txt` (`llama.cpp b8571 win-vulkan-x64`) and a `NOTICE-LLAMA-CPP.txt`; the
  paired URL+SHA override idiom (`:375-394`) applies. No mirror into `native-bin` (618 §3).
- **Variant selection:** `InferenceConfig.findServerExecutable` preference when `gpuLayers > 0`:
  `variants/cuda12` if `accelerator == CUDA`, else `variants/vulkan`; `RuntimeActivationService`
  accepts `variantId = "vulkan"`. `HeadlessApp` Phase F auto-populates `gpu.layers = 99` from the
  same accelerator/memory rule (today NVML-only, `HeadlessApp.java:153-190`).
- **Vulkan env pass-through:** `justsearch.llm.vulkan.coopmat` (`auto|off`; `off` sets
  `GGML_VK_DISABLE_COOPMAT=1` in the llama-server environment) and `justsearch.llm.vulkan.device`
  (`GGML_VK_VISIBLE_DEVICES`). Default `auto`. Register both in `EnvRegistry` and the runtime
  config matrix; no other new knobs.
- **Labels:** `GpuStatusView.accelerator` (`"cuda12" | "vulkan" | null`), `source` gains
  `"vulkan"`; `core.gpu.accel` projects `CUDA` / `Vulkan` / `''`; `chip-facts.v1.json` gains
  `accelerator-vulkan`. `/api/inference/status.contextWindow.freeVramBytes` stays; add
  `vramSource` next to it so a Vulkan number is not read as NVML.
- **Docs + decision record:** new ADR "Non-NVIDIA chat acceleration via llama.cpp Vulkan"
  (status proposed until the founder accepts; probes: registry has `vulkan-runtime`; Vulkan
  zip pinned; `ContextWindowPolicy` unchanged), 05-ai-architecture §llama-server variants,
  16-gpu-booster-pack retitled to accelerator packs, 840 §5.1 matrix row, model-inventory, the
  register (D-011 + Q-003).
- **Non-goals here:** ORT/encoder GPU on non-NVIDIA (option (c); its own chunk after the ORT
  bump), ROCm/SYCL/OpenVINO llama.cpp variants, ARM64, Linux (761), a UMA top rung (reassess).

### Scope (one PR each)

1. **Vulkan capability axis.** `GpuCapabilities.Vulkan` + FFM probe in `gpu-bridge` (or
   `ort-common` beside `GpuDriverApiProbe` if `vulkan-1.dll` binding wants to share the FFM
   helpers — pick one and say why); `GpuCapabilityResolver.snapshot()` folds it; `HardwareProfile`
   accelerator + memory rule; `/api/gpu/capabilities` and `GpuStatusView` fields; `chip-facts` +
   `facts.ts`. Tests: probe-result merge matrix (NVIDIA-only box, AMD discrete, Intel UMA, no
   GPU), `HardwareProfileTest` accelerator × memory cases, ui-web fact test.
2. **Install matrix + registry.** `DownloadProfile.VULKAN_FULL`; `requiresAccelerator`;
   `vulkan-runtime` package; `dependsOnFor`; `InstallPlanner` gate generalisation; 840 §5.1
   "Not supported here" split. Tests: planner matrix over the four hardware cases × three
   intents; registry loader compat (pre-903 registry without the field behaves as before —
   the `requiresCuda` default trap in `ModelRegistryLoader.java:99-104` is the pattern to copy).
3. **Runtime selection + launch.** Gradle staging task; `findServerExecutable` preference;
   `startActivate("vulkan")`; Phase F auto-populate; env pass-through; `LlamaServerOps` warning
   text + `vramSource`. Tests: `LlamaServerOpsTest` argv unchanged for CUDA; variant resolution
   table; activation with `variants/vulkan` present/absent.
4. **Docs, ADR, register, live verification record** (may fold into 3 if small).

### Acceptance criteria

- `./gradlew.bat build -x test`; `./gradlew.bat :modules:gpu-bridge:test :modules:configuration:test
  :modules:app-inference:test :modules:app-services:test :modules:ui:test`;
  `cd modules/ui-web && npm run typecheck && npm run test:unit:run`;
  `node scripts/governance/run.mjs --gate adr-coverage --mode gate` (new ADR has probes);
  `node scripts/ci/check-runtime-manifest-closure.mjs` if the manifest shape changes;
  `node scripts/ci/check-update-preserves-models.mjs` (new `variants/vulkan` dir must survive
  update — 772/840 rule).
- **Live, NVIDIA dev box (floor):** start the stack with `-Djustsearch.server.exe=<…>\variants\vulkan\llama-server.exe`
  (or activate `variantId=vulkan`), `ai_activate {chatProfile:"standard"}`; `/api/inference/status`
  shows `contextWindow.rung=32768, reason=top-rung`, `vramSource=vulkan`; the llama-server log
  shows `ggml_vulkan: … NVIDIA GeForce RTX 4070` and 33/33 layers; `jseval llm-bench --profile
  regression` tokens/s within 15 % of the CUDA baseline (F-012: ~25.5 tok/s summarization on the
  9B; §2.1 predicts Vulkan ≥ CUDA on tg). Force a too-large rung (`-Djustsearch.context.size=1000000`)
  and confirm the launch fails loud with a nonzero exit and the override reason, i.e. ADR-0047's
  step-down signal is intact on Vulkan.
- **Live, planner:** with `JUSTSEARCH_GPU_ENABLED=false`-style CUDA suppression *and* a stubbed
  Vulkan axis (test seam, not a real AMD box), `POST /api/ai/install` plan lists `vulkan-runtime`
  + GGUF + FP32/INT8 ONNX and omits `cuda-runtime` and FP16 ONNX.
- **Register:** D-011 (Vulkan chat runtime) written; Q-003 keeps the AMD/Intel measurement open
  with §2.3's commands; `node scripts/docs/skills-sync.mjs --check` green.
- **Blocked item, stated as such:** the AMD/Intel measurement (§2.3). Record in §Status which
  hardware was and was not available; do not declare vendor performance from NVIDIA numbers.

### Constraints

- Do not touch `ContextWindowPolicy` rung values or the budget fractions (ADR-0047 probes).
- Do not change `SessionOptionsApplier`, `NativeSessionHandle` or any ORT path (that is (c)).
- Do not spawn processes for detection; FFM probe only, fail-safe.
- Keep the CUDA path byte-identical: `LlamaServerOpsTest` argv assertions and the
  `cuda-runtime` registry entry must not change in PRs 1-3.
- No new always-loaded rules; the Intel driver caveats go in 05-ai-architecture and the ADR.

### Status

(unstarted)

## 7. Register update and routed findings

- `docs/reference/inference-runtime-register.md`: added **F-015** (this box's CUDA/Vulkan/CPU
  llama-bench table and the WebGPU-EP-from-Java probe, with the ORT ≥ 1.24.4 requirement) and
  **Q-003** (AMD/Intel same-box measurement; WebGPU under the Worker's lease/arena semantics;
  UMA top rung). Skill regenerated with `node scripts/docs/skills-sync.mjs`.
- Routed, not fixed here (rule `log-pre-existing-issues`): `docs/explanation/16-gpu-booster-pack.md:27`
  still says the CPU runtime is pinned to `b8157`; the build pins `b8571`
  (`build.gradle.kts:367`). One-line doc fix — rides along with §6 PR 4, which rewrites that page.
- 898's non-goal line ("non-NVIDIA backends (owner re-read, 887 §S)") now resolves to this doc.

## Appendix A — `WebGpuProbe.java` (as run, condensed)

```java
import ai.onnxruntime.*; import java.nio.LongBuffer; import java.util.*;
public class WebGpuProbe {
  public static void main(String[] a) throws Exception {
    String dll = a[0], model = a[1]; int seq = a.length > 2 ? Integer.parseInt(a[2]) : 64;
    OrtEnvironment env = OrtEnvironment.getEnvironment();
    System.out.println("ORT version: " + env.getVersion());
    env.registerExecutionProviderLibrary("WebGPU", dll);          // 1.24.3: throws "below the minimum required version 1.24.4"
    List<OrtEpDevice> webgpu = new ArrayList<>();
    for (OrtEpDevice d : env.getEpDevices()) {                    // 1.29.0: CPUExecutionProvider + WebGpuExecutionProvider(NVIDIA RTX 4070, DXGI)
      System.out.printf("ep=%s vendor=%s device=%s%n", d.getEpName(), d.getEpVendor(), d.getDevice());
      if (d.getEpName().toLowerCase(Locale.ROOT).contains("webgpu")) webgpu.add(d);
    }
    long[] shape = {1, seq}; long[] ids = new long[seq], mask = new long[seq];
    for (int i = 0; i < seq; i++) { ids[i] = 100 + i; mask[i] = 1; }
    for (String mode : new String[] {"webgpu", "cpu"}) {
      try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
        if (mode.equals("webgpu")) opts.addExecutionProvider(webgpu, new HashMap<>());
        try (OrtSession s = env.createSession(model, opts)) {
          Map<String, OnnxTensor> in = new LinkedHashMap<>();
          for (String n : s.getInputNames())
            in.put(n, OnnxTensor.createTensor(env, LongBuffer.wrap(n.contains("mask") ? mask : n.contains("type") ? new long[seq] : ids), shape));
          for (int i = 0; i < 5; i++) s.run(in).close();
          long t1 = System.nanoTime(); int iters = 50;
          for (int i = 0; i < iters; i++) s.run(in).close();
          System.out.printf("[%s] run mean %.2f ms (batch 1, seq %d)%n", mode, (System.nanoTime() - t1) / 1e6 / iters, seq);
        }
      }
    }
  }
}
```
Run: `java --enable-native-access=ALL-UNNAMED -cp "onnxruntime-1.29.0.jar;." WebGpuProbe <abs>\onnxruntime_providers_webgpu.dll <model.onnx> [seq]`
(the DLL path must be absolute — a relative one resolves against ORT's temp extraction dir).

## Appendix B — raw llama-bench rows (b8571, `-o json`, `avg_ts ± stddev_ts`)

```
cpu-qwen3.5-4b-q4km      CPU     pp512     80.00 +/- 3.05   threads=12
cpu-qwen3.5-4b-q4km      CPU     tg128      9.22 +/- 0.39
cpu-qwen3.5-9b-q4km      CPU     pp512     45.78 +/- 2.28
cpu-qwen3.5-9b-q4km      CPU     tg128      4.52 +/- 0.29
cuda-qwen3.5-4b-q4km     CUDA    pp512   4615.83 +/- 181.89 ngl=99 fa=true
cuda-qwen3.5-4b-q4km     CUDA    tg128     86.90 +/- 3.20
cuda-qwen3.5-9b-q4km     CUDA    pp512   2935.46 +/- 241.00
cuda-qwen3.5-9b-q4km     CUDA    tg128     55.08 +/- 0.22
cuda-qwen3.5-9b-rerun    CUDA    pp512   3007.08 +/- 338.85
cuda-qwen3.5-9b-rerun    CUDA    tg128     55.31 +/- 0.40
vulkan-qwen3.5-4b-q4km   Vulkan  pp512   4263.83 +/- 148.77 ngl=99 fa=true
vulkan-qwen3.5-4b-q4km   Vulkan  tg128     98.04 +/- 1.32
vulkan-qwen3.5-9b-q4km   Vulkan  pp512   2807.87 +/- 11.27
vulkan-qwen3.5-9b-q4km   Vulkan  tg128     63.01 +/- 0.71
ggml_vulkan: 0 = NVIDIA GeForce RTX 4070 (NVIDIA) | uma: 0 | fp16: 1 | bf16: 1 | warp size: 32 | shared memory: 49152 | int dot: 1 | matrix cores: NV_coopmat2
GPU before first row: 2833 MiB used, 40 % util (foreign processes; see §2 caveat)
```
