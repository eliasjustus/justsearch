# JustSearch Sandbox Validation Environment

This document describes the environment inside the Windows Sandbox and how
to validate the JustSearch installer.

## What is staged

The host-side launcher (`scripts/sandbox/sandbox-launch.py`) stages the
mapped folder at `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\`
with:

- The newest `*-setup.exe` (JustSearch installer)
- `CLAUDE.md` — sandbox-specific mission for Claude Code
- `sandbox-environment.md` — this file
- `validation-mode.md` — generated authority for this sandbox's model mode
  (`fresh-install` vs `pre-staged-models`)
- `docs/` — `explanation/`, `reference/`, `how-to/`, `decisions/`,
  `tempdocs/`, plus `llms.txt`
- `.claude/rules/` — agent lessons + context efficiency notes
- `.claude/skills/start/` — sandbox-aware orientation skill
- `.claude/settings.json` — sanitized (no plugins, no MCP, bypassPermissions)
- `tools/` — any installers you copied here on the host (e.g. Git)
- `scifact/` — SciFact eval corpus (~5,184 .txt docs, ~11 MB) for
  ingest-and-quality validation

Model mapping is instance-specific. Read `validation-mode.md` before launching
JustSearch:

- In `fresh-install` mode, no host models are mapped and `JUSTSEARCH_MODELS_DIR`
  must remain unset. This validates the production first-run Install AI path.
- In `pre-staged-models` mode, host models are mapped read-write at
  `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\`. Set
  `JUSTSEARCH_MODELS_DIR` only when intentionally using this shortcut.

## What you install manually

Nothing runs automatically on sandbox startup besides Explorer opening at
the mapped folder. You install everything yourself:

- **Git for Windows** — drop the installer in `tools/` on the host first,
  then run `tools\Git-Setup.exe /VERYSILENT /NORESTART /NOCANCEL /SP-`
  inside the sandbox (or download Git for Windows if you forgot).
- **Claude Code** — `irm https://claude.ai/install.ps1 | iex` then add
  `$env:USERPROFILE\.local\bin` to your User PATH (the install script
  prints a warning if it isn't, the launcher's CLAUDE.md has a one-liner).
- **JustSearch** — run the `*-setup.exe` from the mapped folder. Per
  ADR-0024, the per-user NSIS installer lands at `%LOCALAPPDATA%\JustSearch\`
  (e.g. `C:\Users\WDAGUtilityAccount\AppData\Local\JustSearch\`), NOT
  `C:\JustSearch\`. User data goes to `%APPDATA%\io.justsearch.shell\`.

## Directory layout

| Path | Contents |
|------|----------|
| `C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\` | Mapped folder. Persists on the host at `tmp/sandbox/share/`. |
| `C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels\` | Present only in `pre-staged-models` mode. Set `JUSTSEARCH_MODELS_DIR` to this path only when intentionally using the shortcut. |
| `%LOCALAPPDATA%\JustSearch\` | Installed JustSearch app (per-user, ADR-0024). `JustSearch.exe` is the Tauri shell. |
| `%LOCALAPPDATA%\JustSearch\resources\headless\` | Bundled Java backend: JRE, JARs, llama-server, SSOT. **Alpha.15: no cuda12 GPU runtime bundled** — installer is ~770 MB (was 1.4 GB on alpha.13). |
| `%APPDATA%\io.justsearch.shell\` | User data: downloaded models, Lucene index, logs, runtime state. Survives uninstall by design. |
| `%APPDATA%\io.justsearch.shell\models\` | ONNX model files (per-package subdirs) + chat GGUF. Created by Install AI. |
| `%APPDATA%\io.justsearch.shell\native-bin\llama-server\variants\cuda12\` | **Alpha.15:** populated by Install AI's `cuda-runtime` package — llama-server.exe (cuda12 variant) + ggml-cuda + full CUDA 12.4 runtime + the 3 source zips kept for re-install detection. ~3 GB total. Empty/missing on CPU/GPU_LITE profiles. |

## Environment characteristics

- **Clean Windows** — no dev tools, no git repo, no pre-existing models.
- **GPU passthrough — partially usable for compute (alpha.11+).**
  Windows Sandbox passes the host's NVIDIA card through for DirectX/vGPU
  rendering. The probes inside the sandbox:
  - **NVML** (`nvml.dll`, System32): works. Reports VRAM and driver
    version. `/api/ai/runtime/status` shows `vramDetectionSource: "nvml"`,
    `vramTotalBytes` non-zero, `vramTierDetected` populated.
  - **`nvcuda.dll`** (CUDA driver API, System32): loadable.
    `cuInit(0)` + `cuDeviceGetCount` succeed. **The alpha.11 install
    gate uses this probe** (`GpuDriverApiProbe`) so chat installs.
  - **`nvidia-smi.exe`**: NOT on PATH. The legacy `VramDetector`
    (nvidia-smi shell-out) returns -1 here.
  - **ORT CUDA EP DLLs at the dev-tree filesystem path**: not present.
    Pre-alpha.11 the install gate used this filesystem search and skipped
    chat as a result; alpha.11+ falls through to the driver-API probe.
- The bundled `cuda12` llama-server variant ships its own runtime
  (`cudart64_12.dll`, `cublas64_12.dll`, `ggml-cuda.dll`) so chat runs
  at GPU speed without the system CUDA Toolkit installed.
- **`/api/status` GPU block** has known drift (OBS-1 in the alpha.10
  validation): top-level `gpu.available: true` may report empty `vendor`
  / `name` / `vramTotalBytes`. Use `/api/ai/runtime/status` for
  authoritative GPU metadata.
- **Internet available** — needed for OAuth, Claude install, model
  downloads.
- **`prod=false`** — the backend accepts CORS from any loopback origin
  and does not enforce session tokens. This enables browser-based API
  testing.
- **Backend log rotation (alpha.12)**: every JustSearch launch rotates
  `%APPDATA%\io.justsearch.shell\logs\headless-backend.log` →
  `headless-backend.log.1`, then `.log.1` → `.log.2` (one extra
  generation kept, older discarded). When investigating a crash, check
  both `.log.1` (the previous boot) and `.log.2` (the boot before
  that). Pre-alpha.12 only rotated when the file exceeded 10 MB, so
  prior-session evidence was usually lost.
- **Worker log rotation (alpha.13)**: every Worker subprocess spawn
  rotates `worker.log` → `worker.log.1` → `worker.log.2`, mirroring the
  alpha.12 head-side behaviour. Pre-alpha.13 the worker log was
  append-only, so a Worker crash was silently overwritten by the next
  boot's startup banner.
- **GPU runtime paths (alpha.13)**: the runtime LLM (`InferenceConfig`,
  `HeadlessApp.maybeAutoSelectCuda12Variant`,
  `OnlineAiServiceImpl.applyOverrides`) and ONNX (`AiInstallService`
  writes `justsearch.onnxruntime.native_path` to the bundled `cuda12/`
  dir) no longer default to CPU on hosts where `nvidia-smi.exe` is
  absent. On alpha.12 the install gate detected CUDA correctly but the
  runtime paths each fell back independently — chat ran the default
  variant with `-ngl 0` and all 4 ONNX encoders failed with
  `cublasLt64_12.dll which is missing`. See the alpha.13 GPU-runtime
  section in `CLAUDE.md` for the full set of post-fix expectations.

## Port discovery

The backend publishes its bound port in the runtime manifest at
`%APPDATA%\io.justsearch.shell\runtime\manifest.json` (tempdoc 501).
Read `head.apiPort` from that JSON file; don't probe with netstat:

```powershell
$manifest = Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" | ConvertFrom-Json
$port = $manifest.head.apiPort
$base = "http://127.0.0.1:$port"
Invoke-WebRequest "$base/api/health" -UseBasicParsing | Select-Object -ExpandProperty Content
```

The manifest also carries `instanceId`, `pid` (for liveness checks),
`lifecycle`, `worker.state`, and `ai.phase` — see `docs/explanation/23-runtime-manifest.md`.

## Current validation focus

Recent alpha.27 sandbox evidence proved the main installer/runtime path. For
targeted follow-up rounds, do not repeat broad backend certification after the
cheap sanity ladder passes. Use the backend/API checks to anchor the state, then
spend the round on frontend truthfulness and user-visible regressions.

- Installer execution and Windows security prompts: SmartScreen, Defender,
  Smart App Control, unsigned-publisher warnings, or blocked child processes.
  Save screenshots or exact text for any prompt/block.
- Cheap end-to-end sanity: Install AI reaches `installedFully: true`, runtime
  status selects `cuda12` with `gpuLayers: 99`, one idle chat completion is
  >= 40 tok/s, one HYBRID query exercises reranker, and one post-restart
  `/api/status` still shows the activated runtime.
- First-run Tauri/Lit UX: first paint, Library/Add Folder, Install AI/Brain,
  Health/System, Search, Chat, toasts, status badges, and WebView console
  errors. Settings/Help are optional unless a visible symptom points there.
- Cross-surface truthfulness: UI state must match `/api/health`,
  `/api/status`, `/api/knowledge/status`, `/api/ai/install/status`, and
  `/api/ai/runtime/status`. Treat mismatches as findings even when APIs pass.
  Current high-value checks are Health/System during Install AI, Health/System
  during SciFact ingest, command-palette Install AI discoverability, Library
  toast occlusion, Search/Chat mode labels, and any "Reconnecting..." UI while
  REST endpoints are healthy.

## What to validate

The goal is to verify that the installed app works on a clean machine **and**
that a first-time user can understand it during setup and heavy local work.
After the cheap sanity ladder passes, prefer this journey order:

1. **Fresh launch / first decision** — does
   `%LOCALAPPDATA%\JustSearch\JustSearch.exe` start and render, and is the next
   step obvious without API knowledge?
2. **Backend health sanity** — `/api/health` returns READY (lifecycle becomes
   READY once Install AI completes; before that, expect DEGRADED with
   `inference.offline`).
3. **Install AI journey** — can the user discover the correct Install AI path,
   understand terms/download size/progress, and see a clear completion or
   failure state? Check alternate visible paths such as command palette entries;
   broken generic paths are findings.
4. **Library and file indexing journey** — add a folder through the UI if possible,
   then use the SciFact corpus staged at `Desktop\JustSearchTest\scifact\`
   for the full ingest run. If the UI cannot ingest that corpus, record the
   UI blocker and use `POST /api/knowledge/ingest` as evidence-backed fallback.
5. **Install AI technical sanity** — does the full download flow complete?
   - **Alpha.15:** expect **7 packages** in the manifest: chat,
     embedding, reranker, ner, citation-scorer, splade, **cuda-runtime**
     (new). The 7th package downloads ~1.16 GB of GPU runtime DLLs
     (cuda12 llama-server binary + cuBLAS suite + cuFFT + cuRand +
     cuSparse + cuSolver + nvJitLink) and extracts them into
     `%APPDATA%\io.justsearch.shell\native-bin\llama-server\variants\cuda12\`.
     On GPU_FULL profile (12 GB+ VRAM), all 7 should reach `installed`.
   - `installedFully: true` confirms clean completion.
   - `installedFully: false` with `chat` AND `cuda-runtime` both in
     `state: "skipped"` is normal on CPU/GPU_LITE profiles. With either
     in `state: "failed"` on a 12 GB+ host, that's a regression — record
     the api-snapshots.
   - For targeted follow-up rounds, do not trace every package transition or
     every cuda12 DLL if `installedFully: true`, runtime status selects
     `cuda12`, and chat/reranker sanity checks pass. Expand only on skip/fail,
     missing DLLs, low throughput, or degraded runtime status.
   - **alpha.12 CDN fixes** (still relevant): `mmproj-F16.gguf` and
     `reranker-model_fp16.onnx` both download successfully now (alpha.11
     had a SHA mismatch on mmproj and a 404 on the reranker FP16 — both
     fixed by manifest + CDN updates).
   - **alpha.12 honesty**: multi-file packages cannot transition
     `failed` → `installed` once any shard fails; partial success is
     reported as `failed` rather than silently flipped to `installed`.
   - **alpha.12 wireup**: `/api/ai/runtime/status.onnxFeatures[*].reason:
     "explicit_path"` for installed ONNX features.
6. **Enrichment and UI-under-load journey** — after models install, does
   embedding/SPLADE/NER run, and does the UI stay truthful while work is active?
   - **Alpha.15:** with cuda-runtime installed, ONNX encoders run on
     GPU. Enrichment of the 5184-doc SciFact corpus should complete in
     ~3 minutes (vs ~25 min on CPU baseline pre-alpha.15).
   - Chat throughput can be lower while ingest, enrichment, or indexing is
     actively running because the encoder workload contends for GPU/CPU
     resources. Treat that as contention if an idle chat measurement passes.
   - `/api/status.worker.gpu.{embed,splade,reranker,ner}OrtCuda.available`
     should be `true` for all four.
   - If `*OrtCuda.failureReason` mentions `cufft64_11.dll` or other CUDA
     DLLs, that's a regression — the cuda-runtime extract failed or the
     `applyOrtNativePath` precondition tripped.
7. **Search/Chat journeys** — run Search and Chat once while idle and once while
   indexing/enrichment is active. Verify labels, active mode, result counts,
   snippets, citations, and "why this result" match the raw API response. Lower
   chat throughput during active ingest is contention if the idle measurement
   passes; the UI should not present it as a mysterious failure.
8. **Post-rewrite UI reliability** — verify the Tasks/Health activity surface
   updates while work is active, folder rows reach a terminal indexed state,
   result counts/facets look coherent, toasts do not occlude primary controls,
   and Health/Brain/Search surfaces do not show stale loading or contradictory
   status.
9. **Restart/return journey** — after one cold restart, verify installed AI,
   cuda12 runtime selection, library/index state, and Health/Search/Chat state
   are presented clearly. Expand to three cycles only for lifecycle/wireup
   validation.
10. **Uninstall** — skip in targeted follow-up rounds. Run only when the user
   explicitly asks for uninstall validation or the installer/uninstaller code
   changed.

Write findings to the mapped folder so they persist on the host.

## Key API endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/health` | GET | Lifecycle state with `reason_code` (e.g., `inference.offline`). Schema-versioned. |
| `/api/status` | GET | Full system status. Note: `gpu` block has OBS-1 drift; use `/api/ai/runtime/status` for authoritative GPU metadata. |
| `/api/knowledge/search` | POST | Search (`{"query":"...","limit":5}`). Response includes `pipelineExecution.components[*].{status, reason}` per pipeline stage. |
| `/api/knowledge/ingest` | POST | Ingest paths (`{"paths":["..."]}`). Directory inputs return `scanId` for live progress. |
| `/api/knowledge/status` | GET | Index state, `embeddingCoveragePercent`, `spladeCoveragePercent`, `pendingNerCount`, `embeddingCompatState`. |
| `/api/ai/install/start` | POST | Start model download (`{"acceptTerms":true}`). |
| `/api/ai/install/status` | GET | Download progress. Top-level `state: "completed"` does NOT imply all packages installed; check `installedFully: bool` for that. Per-package shape: `{packageId, label, state, bytesDownloaded, bytesTotal, skipReason, error}`. `state` ∈ {`pending`, `downloading`, `verifying`, `installed`, `skipped`, `failed`}. |
| `/api/ai/runtime/status` | GET | NVML VRAM (`vramDetectionSource`, `vramTotalBytes`, `vramTierDetected`), `installedVariants[]` (default + cuda12), per-feature ONNX state with `modelActive` and `reason` (`explicit_path` / `auto_discovered` / `not_found`). |
| `/api/inference/status` | GET | LLM runtime state. |

No session token needed (`prod=false`).
