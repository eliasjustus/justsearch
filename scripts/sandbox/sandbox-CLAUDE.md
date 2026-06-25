# Claude Code Instructions for JustSearch Sandbox Validation

You are running inside a **Windows Sandbox** — an ephemeral, clean Windows
environment with no development tools, no source code, and no pre-existing
models.

**Read `sandbox-environment.md` first.** It describes the directory layout,
what is staged in the mapped folder, and what to validate.

Also read `validation-mode.md` before launching JustSearch. It is generated
for this sandbox instance and overrides static wording about whether host
models are mapped.

## Current mission (alpha.27+)

The alpha.27 fresh-install sandbox pass proved the core path: installer boot,
Install AI full download, cuda12 activation, chat throughput, SciFact ingest,
HYBRID/reranker search, RAG/chat, and restart persistence. Do not spend a
targeted round re-proving those internals with large evidence bundles unless a
sanity check fails or the user explicitly asks for full release-candidate
certification.

Run one cheap end-to-end sanity ladder, then validate the actual Tauri shell
experience where user trust can still regress.

After the sanity ladder passes, organize your work by **user journey**, not by
backend subsystem. The highest-value findings are places where a normal user is
confused, blocked, misled, or scared during first-run or long-running local
processing. Prefer evidence about:

- Fresh launch: is the next step obvious?
- Install AI: terms, download size/progress, completion, failure, and alternate
  routes such as command palette entries. On the Brain surface, check the
  Simple↔Advanced toggle — Simple must be enough for a first-run user, and
  Advanced must not surface scary internals (raw model paths, GPU layers,
  embed/VDU queue depths) as the default view.
- Library ingest: can the user add data, see progress, and keep working?
- Search/Chat while idle: do labels, citations, result counts, and mode text
  match the actual response? Walk the full escalation ladder — Search →
  Documents (grounded, cited answer) → Structured (schema extraction) → Agent
  (delegate a task). Each rung's label/affordance must be honest; AI-requiring
  rungs must disable with a clear reason when AI is offline (not a dead click);
  and the Agent "delegate a task" rung must make obvious what it will do before
  it runs.
- Search/Chat while indexing: does the UI stay usable and explain contention
  instead of looking broken?
- Restart/return: is installed AI, runtime selection, and library state
  presented clearly after reopening?
- Security & Privacy: does the encryption panel's at-rest status match reality
  (disk-encryption state, "Unknown — needs admin", per-scope index/conversation
  rows)? Does the chat-encryption passphrase flow make the irreversible "forget
  passphrase → chat history lost for good" consequence unmissable before the
  user commits, and surface a recovery key?
- Memory ("what it knows"): can the user see what the AI has learned and forget
  a fact? An empty state should read as private-by-default, not broken.
- Appearance/Skins: applying a built-in skin, using the Editor, and importing a
  skin JSON must never leave an illegible/broken surface, and the choice must
  survive a restart.

Report findings by journey with screenshot filenames and raw API/log evidence.
Do not produce a long package/DLL/status inventory when the sanity ladder is
green.

Prioritize these current risks:

1. **Installer/security friction** — record SmartScreen, Defender, Smart App
   Control, unsigned-publisher warnings, blocked child processes, or any manual
   wizard step that prevents automation. Save screenshots or exact prompt text.
2. **Fresh Install AI sanity** — if `validation-mode.md` says `fresh-install`,
   keep `JUSTSEARCH_MODELS_DIR` unset. Verify `installedFully: true`,
   `activeVariantId: cuda12`, `gpuLayers: 99`, one idle chat completion at
   >= 40 tok/s, one HYBRID/reranker query, and one post-restart status check.
   If those pass, stop collecting package-by-package or DLL-by-DLL evidence.
3. **Lit frontend truthfulness** — focus on Health/System during Install AI and
   SciFact ingest, command-palette Install AI discoverability, Library/Add
   Folder toast occlusion, Search/Chat mode labels, and any unexpected theme
   shift. UI/API disagreement is a finding even when APIs pass.
4. **UI under load** — during ingest, compare the UI against live REST
   snapshots. If `/api/health`, `/api/status`, or `/api/knowledge/status` are
   HTTP 200/READY while the shell says "Reconnecting..." or shows stale cards,
   capture screenshots and raw API responses.
5. **Post-install persistence sanity** — one cold restart is enough for a
   frontend-focused round. Run three cycles only when the user says the target
   is lifecycle, persistence, port binding, encoder init, or wireup.
6. **Newly-shipped trust surfaces (never sandbox-validated)** — Security &
   Privacy (encryption-status truthfulness + the irreversible chat-passphrase
   flow), the Agent "delegate a task" rung of the chat escalation ladder,
   Memory (inspect/forget), and Appearance/Skins. These landed after the
   alpha.27 mission was written and map to the privacy/threat-model claims being
   published in the go-public work, so a misleading or scary surface here is
   high-severity. Prioritize at least the Security & Privacy and Agent-delegate
   journeys this round.

Historical alpha notes below are background. The current mission above and
`validation-mode.md` are the authority for this round.

## What's available

- **Mapped folder** at `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\`
  — contains the JustSearch installer, this CLAUDE.md, `docs/`, `.claude/`,
  and a `tools/` directory for any installers staged from the host.
- **Models** may be mapped at
  `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\` only in
  `pre-staged-models` mode. Read `validation-mode.md`; never set
  `JUSTSEARCH_MODELS_DIR` during a `fresh-install` round.
- **PowerShell** and standard Windows tools.
- **Internet access** (for model downloads, Claude OAuth, Git/Chrome
  installs if not pre-staged in `tools/`).

## What's NOT available

- No source code, no Gradle, no JDK, no Node.js
- No `jseval`, no MCP dev tools, no worktrees, no agent telemetry
- `nvidia-smi.exe` is NOT on PATH in the sandbox (don't use it as a CUDA probe)
- No automatic install. You install Git, Claude Code, JustSearch yourself.

## GPU expectations (alpha.11+)

The sandbox vGPU passthrough means the host's NVIDIA card *is* reachable.
NVML works (12 GB VRAM visible) and `nvcuda.dll` loads from System32 with
`cuInit` + `cuDeviceGetCount` succeeding. The alpha.11 install gate uses
the driver-API probe, so:

- The chat package should install (5.9 GB chat GGUF + 918 MB mmproj
  download — slow on residential bandwidth but it should run).
- `installedFully: true` after Install AI completes.
- Chat runs via the bundled `cuda12` llama-server variant at GPU speed
  (~67-85 tok/s on a host RTX 4070).

If chat is skipped with "no CUDA detected", that's a **regression** —
record the api-snapshots and report.

## GPU-runtime expectations (alpha.15+)

Alpha.15 closes the ONNX-GPU gap from alpha.13/14: the cuda12 runtime
DLLs (cuFFT + cuRand + cuSparse + cuSolver + nvJitLink, plus llama.cpp's
cuda12 binary) are now downloaded post-install via the Install AI flow
instead of bundled into the NSIS payload (which hit the 32-bit installer
size limit, G21). The installer drops back to ~770 MB; first-launch
Install AI adds ~1.2 GB of CUDA runtime + the existing model packages.

Sandbox round 5 (alpha.13) verified chat at **64 tok/s** (vs alpha.12
baseline 6.66 — 9.7× speedup) but found ONNX encoders still on CPU due
to two issues alpha.14 closes:

- **Chat (alpha.13 + E + F)**: `InferenceConfig` derives `cudaAvailable`
  from `rc.ai().gpuLayers()`. `HeadlessApp.augmentGpuAutoDetectionAndMirror`
  sysprop-mirrors auto-detected GPU values + auto-populates `gpu_layers`
  from VRAM tier when ≥ 7.5 GB. `OnlineAiServiceImpl.applyOverrides` no
  longer treats `UiSettings.gpuLayers == 0` as an explicit override.
- **ONNX (alpha.14 fix B)**: `AiInstallService.writeOrtNativePathSysprop`
  validates the cuda12 dir's CUDA *runtime* DLLs (cudart, cublas, cublasLt)
  — the alpha.13 guard mistakenly checked for ORT EP DLLs that live in
  the JAR, never in cuda12/. The `cuda12/` variant now also bundles
  cuFFT + cuRand + cuSparse + cuSolver (the additional CUDA libs ORT's
  CUDA EP needs beyond llama.cpp's subset).
- **Status endpoints (alpha.14 P1)**:
  - `/api/inference/status.gpu.cudaAvailable` and `vramDescription` now
    derive from the NVML-first `GpuCapabilitiesService.snapshot()`
    instead of the legacy nvidia-smi shell-out.
  - `/api/ai/runtime/status.active.gpuLayers` reads from the resolved
    config (`rc.ai().gpuLayers()`) not the UiSettings explicit-override
    field (which defaults 0).

Behaviour to verify on a 12 GB+ host:

- `tasklist /v` shows `…\variants\cuda12\llama-server.exe` (was the
  default `…\llama-server.exe` on alpha.12) with `-ngl 99` in the
  cmdline (was `-ngl 0`).
- `GET /api/inference/status.gpu.cudaAvailable: true` (was `false` on
  alpha.12 — driven by the legacy nvidia-smi probe).
- `GET /api/status.worker.gpu.{embed,splade,reranker,ner}OrtCuda.available: true`
  (was all `false` with `cublasLt64_12.dll which is missing` on alpha.12).
- A chat completion measured against `/v1/chat/completions` returns at
  >40 tok/s (was 6.66 on alpha.12). NVML used VRAM jumps to 6+ GB during
  inference (was 1.6 GB host noise — no JustSearch contribution).
  Measure chat pass/fail when no ingest, enrichment, or indexing job is
  actively running; during those jobs, GPU/CPU contention can temporarily lower
  chat throughput and should be recorded as contention if the idle measurement
  passes.
- `%APPDATA%\io.justsearch.shell\logs\worker.log.1` exists after a
  worker restart (alpha.12 only rotated `headless-backend.log`).

- `GET /api/inference/status.gpu.vramDescription: "12.0 GB"` (alpha.14
  P1 — was `"Unknown (nvidia-smi not available)"` on alpha.13 even
  though chat was running on GPU).
- `GET /api/ai/runtime/status.active.gpuLayers: 99` (alpha.14 P1 —
  was `0` on alpha.13 reading UiSettings instead of resolved config).
- ONNX failureReasons mentioning `cufft64_11.dll`, `curand64_10.dll`,
  `cusparse64_12.dll`, `cusolver64_11.dll`, or `cudnn*.dll` indicate the
  alpha.16 cuda-runtime Install AI package failed to download or extract.
  Check the Install AI status — the package should appear as
  `cuda-runtime` and reach `installed` (downloads ~1.85 GB across 4 zips:
  llama-bin, cudart-bin, ort-cuda-runtime, cudnn-9-runtime).
- Worker logs `Embedding compatibility: UNAVAILABLE` after Install AI on
  GPU_FULL profile is an alpha.16 regression — `EmbeddingFingerprint`
  should compute against `model_fp16.onnx` via `ModelManifest`.
- Worker logs `EmbeddingService created … gpuEnabled=false` after
  Install AI is an alpha.16 regression — boot-time per-encoder
  gpu.enabled mirror should set the explicit value.

If chat lands on the default `llama-server.exe` again or any
`*OrtCuda.failureReason` mentions `cublasLt64_12.dll`, that's an
alpha.13 regression — record the api-snapshots and report.

## Install AI expectations (alpha.16)

Alpha.16 closes the ONNX-on-GPU gap from alpha.15 sandbox round 6. The
cuda-runtime package now also bundles **cuDNN 9** (~605 MB compressed) —
ORT 1.24's `onnxruntime_providers_cuda.dll` has a hard load-time
dependency on cuDNN regardless of model ops, so the alpha.15 "cuDNN
deliberately omitted" decision was wrong. Total cuda-runtime download
grows from 1.16 GB → 1.85 GB; Install AI grand total: ~10.3 GB on
GPU_FULL profile.

Alpha.16 also fixes 4 other bugs that compounded around alpha.15 ONNX
breakage:

- **Bug A** (cosmetic): activation API recognizes `auto_selected_cuda12`
  as system-owned source; previously rejected as operator override.
- **Bug B**: `HeadlessApp.maybeMirrorOrtNativePath` writes the ORT
  native_path sysprop at boot when cuda12 dir exists. Pre-alpha.16 this
  was set only during Install AI; restarts blew it away.
- **Bug C**: `EmbeddingFingerprint` uses
  `ModelManifest.resolveExistingModelFile` — handles GPU_FULL profile
  where only `model_fp16.onnx` is on disk (no `model.onnx`). 6th encoder
  site to align with alpha.12 multi-encoder fix.
- **Bug D** (defensive backstop): `HeadlessApp` boot mirrors per-encoder
  `*.gpu.enabled` sysprops when master is true and no user override
  exists. Root-cause investigation of why per-encoder fallback drops at
  the worker is alpha.17 work; this backstop unblocks ONNX immediately.

## Install AI expectations (alpha.15 mechanism, still applies)

Alpha.15 introduced a **7th package** — the `cuda-runtime` package — that
ships the GPU runtime DLLs (cuda12 llama-server binary + ggml-cuda + cuBLAS
suite + cuFFT + cuRand + cuSparse + cuSolver + nvJitLink + alpha.16: cuDNN 9)
via Install AI download instead of bundling into the NSIS installer. This is the alpha.13
"NSIS 32-bit size limit (G21)" workaround — fresh-install installer is now
**~770 MB** (was 1.4 GB on alpha.13) and the GPU runtime arrives in the
Install AI cycle.

Behaviour to verify on a 12 GB+ host (GPU_FULL profile):

- `GET /api/ai/install/manifest` shows **7 packages**: chat, embedding,
  reranker, ner, citation-scorer, splade, **cuda-runtime** (new). The new
  package has `label: "GPU runtime libraries"`,
  `installRoot: "native-bin/llama-server/variants"`, three
  `supportingFiles` with `extract: true`. Total size: ~1.16 GB.
- All 7 packages reach `state: "installed"`, `installedFully: true`.
- `cuda-runtime` package state transitions: `pending` → `downloading` × 3
  zips → `verifying` × 3 → `installed`. (UI flicker between zips is normal;
  end state is `installed`.)
- After cuda-runtime extracts, the directory
  `%APPDATA%\io.justsearch.shell\native-bin\llama-server\variants\cuda12\`
  contains:
  - `llama-server.exe` (cuda12 variant, ~10 MB)
  - `ggml-cuda.dll` (~470 MB)
  - `cublasLt64_12.dll` (~451 MB)
  - `cublas64_12.dll` (~96 MB)
  - `cudart64_12.dll` (~0.5 MB)
  - `cufft64_11.dll` (~279 MB)
  - `curand64_10.dll` (~61 MB)
  - `cusparse64_12.dll` (~263 MB)
  - `cusolver64_11.dll` (~110 MB)
  - `nvJitLink_120_0.dll` (~37 MB)
  - The 3 source zips (kept on disk so re-install skips re-download via
    the planner's `isAlreadyInstalled` filename check)
- After Install AI completes (single restart cycle):
  - Chat runs on the cuda12 binary at GPU speed (`tasklist` shows
    `…\variants\cuda12\llama-server.exe -ngl 99`).
  - All 4 ONNX encoders report `OrtCuda.available: true` in
    `/api/status.worker.gpu`.
  - `headless-backend.log` shows the alpha.15 wireup lines:
    - `Extracted N new entries from llama-b8571-bin-win-cuda-12.4-x64.zip`
    - `alpha.15: server.exe set to cuda12 variant: …\cuda12\llama-server.exe`
    - `alpha.14 fix B: ORT native path set to …\cuda12`
- On CPU/GPU_LITE profiles (< 7.5 GB VRAM), cuda-runtime is **skipped**
  with reason matching the chat package's skip reason ("Insufficient VRAM
  for GPU runtime libraries (X MB available, 7500 MB required)" or
  "requires a CUDA-capable GPU"). Chat skipped too. ONNX encoders run on
  CPU (existing behaviour).

Failure modes to flag:

- cuda-runtime in `state: "failed"` — `error` field shows whether it was
  download (network), verification (SHA mismatch), or extract (disk full,
  corrupt zip). Both chat AND ONNX fall back to CPU on this failure.
- cuda12 dir present but `headless-backend.log` doesn't show the
  `alpha.15: server.exe set to cuda12 variant` line — `applyCudaServerExe`
  precondition may have tripped (existing user override on
  `justsearch.server.exe`). Check `/api/effective-config` for the source.
- ONNX `*OrtCuda.failureReason` mentions a CUDA DLL not in the bundled
  list above (e.g., `cudnn64_9.dll`) — a model in the encoder set is using
  ops that need cuDNN. cuDNN was deliberately omitted from alpha.15
  (~700 MB; current encoder set is BERT-style, no conv ops). File this
  finding so a future package adds the cuDNN suite.

### Pre-alpha.15 history (still relevant for upgrade-from-prior-alpha tests)

Alpha.12 fixed two CDN bugs that broke alpha.11:

- **`mmproj-F16.gguf`** SHA + sizeBytes corrected to match bartowski
  upstream (was 918,166,080 / `F70DC350...`; CDN serves 918,165,952 /
  `97F42024...`).
- **`reranker-model_fp16.onnx`** uploaded to the `models-v1` release (was
  a 404 in alpha.11).

Alpha.12 also introduced multi-file integrity:

- Multi-file packages now refuse to transition `failed` → `installed` so
  a partial success is reported honestly (alpha.11 could lie).
- `GET /api/ai/runtime/status` reports `onnxFeatures[*].reason:
  "explicit_path"` for installed ONNX features (was `"not_found"` in
  alpha.11).

## Setup (manual)

1. **Git** — run `tools\Git-Setup.exe /VERYSILENT /NORESTART /NOCANCEL /SP-`
   (or download Git for Windows if not pre-staged).
2. **Claude Code** — single command:
   ```powershell
   irm https://claude.ai/install.ps1 | iex; $bin = "$env:USERPROFILE\.local\bin"; $u = [System.Environment]::GetEnvironmentVariable("Path","User"); if ($u -notlike "*$bin*") { [System.Environment]::SetEnvironmentVariable("Path","$u;$bin","User") }; $env:Path += ";$bin"
   ```
   Run `claude` from the mapped folder. The staged
   `.claude/settings.json` sets `permissions.defaultMode = "bypassPermissions"`,
   so Claude Code starts in bypass mode automatically (no per-tool prompts).
   If the setting is ignored for any reason, launch with
   `claude --dangerously-skip-permissions` instead.
3. **JustSearch** — run the `*-setup.exe` in the mapped folder. Per ADR-0024,
   the NSIS installer is **per-user** and lands at `%LOCALAPPDATA%\JustSearch\`
   (e.g. `C:\Users\WDAGUtilityAccount\AppData\Local\JustSearch\JustSearch.exe`),
   NOT `C:\JustSearch\`. User data (downloaded models, index, logs, runtime
   state) lives separately at `%APPDATA%\io.justsearch.shell\`.

## Required validation phases

Validate that JustSearch works correctly on a clean machine:

1. **Installer launch and security prompts** — run the installer from the
   mapped folder. Capture any SmartScreen/Defender/SAC/unsigned-publisher UI,
   any failure to honor `/S`, and the exact action needed to continue.
2. **First app launch** — does `%LOCALAPPDATA%\JustSearch\JustSearch.exe`
   start and render? Save `evidence/NN-first-paint.png`.
3. **Backend health** — read the runtime manifest, then save raw
   `/api/health`, `/api/status`, `/api/ai/runtime/status`, and
   `/api/inference/status` snapshots.
4. **Install-dir hygiene** — run the jar-uniqueness check from `/start` Rule 4
   before trusting runtime behavior.
5. **Pre-Install-AI UI sanity** — built-in help search for "getting started",
   Library surface, Health/System surface, Brain/Install AI surface, status
   badges, and console errors.
6. **Install AI** — in `fresh-install` mode, validate the full model and
   cuda-runtime download through the UI and back it with
   `/api/ai/install/status` snapshots. In `pre-staged-models` mode, label the
   evidence as shortcut-only.
7. **Library/indexing journey** — add a folder through the UI when possible.
   For the full corpus, ingest `Desktop\JustSearchTest\scifact\`. Verify
   per-folder row state, Tasks panel live updates while open, status-bar queue,
   and `/api/knowledge/status` agree.
8. **Search/Chat/Brain/Health UI** — run real searches from the UI and compare
   rendered results with API responses. Check result counts/facets, raw reason
   codes, stuck loading states, misleading "Ready"/"Offline" labels, and toasts
   occluding controls.
9. **Enrichment and GPU** — verify embedding/SPLADE/NER/reranker behavior by
   exercising the features. Record quantitative GPU evidence: chat tok/s,
   ingest/enrichment wall time, and runtime/status fields.
10. **Restart cycles** — complete the 3-cycle restart pattern from `/start`
    Rule 15 and compare API plus UI state after each cycle.
11. **Uninstall** — run only after all evidence is saved, unless the user says
    to defer it. Verify program files are removed and user data behavior matches
    ADR-0024.

## How to test

The backend binds to `127.0.0.1` on a port disclosed two ways:

1. **Read the runtime manifest** (canonical, fastest; tempdoc 501):
   ```powershell
   (Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json).head.apiPort
   ```
2. Fallback if the file is missing for any reason:
   ```powershell
   Get-NetTCPConnection -State Listen | Where-Object { $_.LocalAddress -eq '127.0.0.1' }
   ```

Key API endpoints (no auth needed, `prod=false`):

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/health` | GET | Lifecycle state |
| `/api/status` | GET | Full system status |
| `/api/knowledge/search` | POST | Search (`{"query":"...","limit":5}`) |
| `/api/knowledge/ingest` | POST | Ingest (`{"paths":["..."]}` — directory inputs return `scanId`) |
| `/api/knowledge/status` | GET | Index/enrichment progress |
| `/api/ai/install/start` | POST | Start model download (`{"acceptTerms":true}`) |
| `/api/ai/install/status` | GET | Download progress + per-package state. Top-level `state: "completed"` does NOT mean "all packages installed"; check `installedFully: true` for that, and inspect `packages[]` for per-package `state` (`installed` / `skipped` / `failed`). |
| `/api/ai/runtime/status` | GET | Per-feature runtime status (NVML VRAM, ONNX feature `modelActive` flags) |
| `/api/inference/status` | GET | LLM runtime state |

## Writing results

Files written to the mapped folder
(`C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest`) persist on the host
after the sandbox closes. Anywhere else (`C:\`, `C:\JustSearch\`, the user
profile) is wiped on shutdown.
