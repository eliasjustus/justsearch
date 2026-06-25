---
description: Orient to the sandbox and the project via documentation
---

You are running inside a **Windows Sandbox** for validating the JustSearch
installer. You do **not** have access to the developer environment.

## What is NOT here

- No source code, no `modules/`, no `gradlew.bat`, no JDK or Node.js
- No `jseval`, no MCP dev tools, no worktrees, no agent telemetry

Anything in the documentation that references those tools is for the dev
environment, not this sandbox. Do not attempt to run them.

## What IS here

- `docs/` — full project documentation (`explanation/`, `reference/`,
  `how-to/`, `decisions/`, `tempdocs/`, plus `llms.txt`)
- `CLAUDE.md` — sandbox-specific mission
- `sandbox-environment.md` — directory layout, API endpoints, port-announce file
- `validation-mode.md` — generated authority for this sandbox instance
  (`fresh-install` vs `pre-staged-models`)
- `tools/` — the Git installer pre-staged from the host
- The JustSearch `*-setup.exe` in this folder
- `scifact/` — SciFact corpus for ingest validation

After installation (per ADR-0024, per-user): JustSearch at
`%LOCALAPPDATA%\JustSearch\JustSearch.exe`, user data at
`%APPDATA%\io.justsearch.shell\`. The backend's bound port is published
in the runtime manifest (tempdoc 501) at
`%APPDATA%\io.justsearch.shell\runtime\manifest.json` — read
`head.apiPort` from that JSON file instead of probing with netstat.

Logs (rotated per launch / spawn):

- `…\logs\headless-backend.log` — current head log
- `…\logs\headless-backend.log.1` / `.log.2` — older head boots
- `…\logs\worker.log` — current worker log
- `…\logs\worker.log.1` / `.log.2` — older worker boots

## GPU / CUDA in the sandbox

Windows Sandbox passes the host's NVIDIA card through for DirectX/vGPU
rendering. The relevant probes:

- **NVML** (`nvml.dll`, System32): sees the card, reports VRAM and driver version.
- **`nvcuda.dll`** (System32): loadable; `cuInit(0)` + `cuDeviceGetCount` succeed.
- **`nvidia-smi.exe`**: **NOT** on PATH in the sandbox.

The host has experimentally proven the GPU is reachable. **JustSearch should
default to GPU mode end-to-end on this sandbox** — chat on cuda12 llama-server
with `-ngl 99` at 40+ tok/s, ONNX encoders on CUDA EP at 10-100× CPU speeds.
If JustSearch falls back to CPU on this host, that is a regression, not a
sandbox limitation.

The cuda12 GPU runtime is not bundled in the NSIS installer (size limit) —
it ships as a 7th Install AI package called `cuda-runtime` that downloads
~1.85 GB across 4 zips (llama.cpp cuda12 binary, cuBLAS suite, ORT cuda
runtime, cuDNN 9). After Install AI completes, the cuda12 dir at
`%APPDATA%\io.justsearch.shell\native-bin\llama-server\variants\cuda12\`
should contain ~18 DLLs and a `llama-server.exe`.

## What to do at session start

Read **only the documentation** to understand the project:

1. `validation-mode.md` — actual model mapping / fresh-install mode
2. `CLAUDE.md` — the validation mission
3. `sandbox-environment.md` — directory layout and API endpoints
4. `docs/llms.txt` — project overview

Summarize what JustSearch does (1-2 sentences) and the validation tasks
listed in `CLAUDE.md`. Then wait for the user's instruction. Do not start
running validation steps unprompted.

## Current high-risk areas for this round

The installer and app had a fresh alpha.27 sandbox pass that proved the core
installer path, Install AI download, cuda12 runtime activation, SciFact ingest,
HYBRID search/reranker, chat, and restart persistence. Treat later rounds as
targeted regression validation unless `validation-mode.md` or the user says
this is a full release-candidate certification.

Do **not** spend the round re-proving already-confirmed internals with large
evidence bundles. First run the cheap sanity checks listed below, then focus on
remaining first-run trust/UI issues:

- Capture Windows security and trust prompts: SmartScreen, Defender, Smart App
  Control, unsigned-publisher warnings, or blocked child processes.
- Validate the actual Tauri/Lit UI where the previous round still found risk:
  Health/System activity truthfulness during Install AI and ingest, command
  palette Install AI discoverability, Library/Add Folder toast occlusion,
  Search/Chat mode labels, and any unexpected theme/appearance shift.
- Validate the trust surfaces that shipped after the alpha.27 mission and have
  never been sandbox-checked: Security & Privacy (encryption status + the
  irreversible chat-passphrase flow), the Agent "delegate a task" rung of the
  chat escalation ladder, Memory (inspect/forget), and Appearance/Skins — see
  the journeys list in "What to focus on after sanity passes" below.
- Stress the UI under active ingest: direct REST endpoints can stay healthy
  while the frontend reports "Reconnecting..." or stale cards. Capture that
  mismatch if it still happens.
- Compare UI state with raw API snapshots. A green API with misleading UI is a
  finding; a green API plus truthful UI is enough, do not keep collecting
  redundant screenshots.
- If `validation-mode.md` says `fresh-install`, do not set
  `JUSTSEARCH_MODELS_DIR`; the production Install AI download/extraction path
  is the thing being tested.

Cheap sanity checks before targeted UI work:

1. Installer launches and backend reaches `/api/health` without HTTP 500.
2. Rule 4 jar uniqueness passes.
3. Install AI reaches `installedFully: true`.
4. `/api/ai/runtime/status.active.activeVariantId == "cuda12"` and
   `gpuLayers == 99`.
5. One idle chat completion is >= 40 tok/s.
6. One HYBRID search shows cross-encoder/reranker execution.
7. One post-restart `/api/status` still shows cuda12/READY after activation.

If all seven pass, stop broad backend validation and move to the focused UI
checks. Expand only if a sanity check fails.

## What to focus on after sanity passes

Your main job is now to test **user trust during first-run and long-running
local processing**. Think like a first-time user, not like a backend release
engineer. The core question is:

> Does the app explain what is happening, what the user can do next, and whether
> local AI/search is ready, busy, degraded, or blocked?

Spend most of the round on these user journeys:

1. **Fresh launch / first decision** — can a user tell whether the app is ready,
   whether local AI is installed, and where to start without knowing the API?
2. **Install AI** — does the UI explain terms, download size, progress,
   completion, failure, and next step? Are there alternate routes (command
   palette, buttons, banners) that fail confusingly or bypass the proper terms
   flow? On the Brain surface, check the Simple↔Advanced toggle: Simple must
   suffice for a first-run user; Advanced must not present raw model paths, GPU
   layers, or embed/VDU queue depths as the default first-run view.
3. **Add library / ingest SciFact** — can a user add data through the UI, see
   progress, understand indexing/enrichment, and continue using the app while
   work runs?
4. **Search/Chat while idle (full escalation ladder)** — walk Search →
   Documents → Structured → Agent. Do labels, tabs, CTAs, result counts,
   citations, and "why this result" match the actual backend mode and response?
   Do AI-requiring rungs disable with an honest reason when AI is offline
   instead of dead-clicking? Does Documents return a grounded, cited answer and
   Structured a schema extraction — and does the **Agent "delegate a task"** rung
   make clear what it will do before it runs (the highest-autonomy, highest-trust
   moment)?
5. **Search/Chat while indexing** — does the app stay usable under GPU/CPU
   contention, and does it describe slower throughput as busy contention rather
   than failure?
6. **Restart / return** — after closing and reopening, does the app preserve the
   installed AI/runtime/library state and present a clear ready/busy state?
7. **Security & Privacy** — does the encryption panel's at-rest status match the
   sandbox's real state (disk encryption, "Unknown — needs admin", per-scope
   index/conversation rows)? Does the chat-encryption passphrase flow make the
   irreversible "forget passphrase → chat history lost for good" consequence
   unmissable before commit, and surface a recovery key? Encryption claims here
   are published privacy/threat-model claims — a false "encrypted" / "not
   encrypted" reading is high-severity.
8. **Memory (inspect / forget)** — can a user see what the AI has learned and
   forget a specific fact? An empty memory should read as private-by-default,
   not broken or missing.
9. **Appearance / Skins** — applying a built-in skin, using the Editor, and
   importing a skin JSON must never leave an illegible or broken surface, and the
   selection must persist across a restart.

High-value findings are the places where a normal user would be confused,
blocked, misled, or scared:

- UI says "idle", "ready", or "reconnecting" while APIs show active healthy
  work.
- A visible path to a valid workflow fails with raw operation IDs, internal
  reason codes, package IDs, path hashes, Java/ORT details, or no action.
- Toasts, reconnect banners, loading overlays, or status cards obstruct primary
  controls such as Add Folder, Install AI, Search input, or Chat.
- User impatience scenarios fail poorly: switching tabs during install/ingest,
  searching before enrichment completes, chatting before AI is ready, closing
  and reopening while work is pending.
- Failure messages do not explain what happened and what the user can do next.

Report findings by journey, not by subsystem. Prefer this report structure:

1. `Sanity ladder` — one short pass/fail list with the raw evidence filenames.
2. `Fresh launch`
3. `Install AI`
4. `Library ingest`
5. `Search/Chat idle (escalation ladder)`
6. `Search/Chat during indexing`
7. `Restart/return`
8. `Security & Privacy`
9. `Memory (inspect/forget)`
10. `Appearance/Skins`
11. `Windows trust/security`

For each issue, include: user-visible symptom, exact action sequence, screenshot
filename, raw API/log evidence filename, severity, and whether it reproduces
without a workaround.

## Validation rhythm — durable rules

These are general rules learned across sandbox rounds 7-10. Apply on every run.

### Rule 1: Don't apply workarounds without first documenting failure

NTFS junctions, manual config edits, env-var overrides, copying model files
between dirs — these are **not** acceptable steps to take on your own initiative.
If the default flow fails, log the exact failure mode in your findings and stop.
Workarounds are only acceptable AFTER you have captured evidence of the
bare-bones failure, AND you should clearly mark which evidence is "with
workaround" vs "without."

This rule exists because round 8 worked around a path-resolution bug with
junctions, which masked the very thing the round was meant to validate.

### Rule 2: Verify positively, not by absence of failure

A flag being `false` and a feature being `working` can look identical from the
outside. For each subsystem, verify it works by exercising it, not just by
checking status flags.

- Don't trust `OrtCuda.configured=false` as proof of anything — it could be
  "lazy-init didn't fire" OR "init crashed silently" OR "feature is CPU-only
  by design."
- Issue a real query against the affected feature (search, chat, citation,
  reranker) and verify results are returned with the expected shape.

### Rule 3: When ANY endpoint returns 500, harvest the stack trace before classifying severity

Round 9 labeled a `/api/status` 500 as "cosmetic" without checking the head
log. The actual cause was a `NoSuchMethodError` indicating stale jars in the
install — a critical build-hygiene failure.

On any HTTP 500 from a JustSearch endpoint, before classifying severity:

1. Grep the head log for `ERROR\|Caused by:\|java\.lang\.\|Exception` near
   the request timestamp.
2. Save the full stack trace (top 30 lines) to `evidence/NN-stacktrace-<endpoint>.txt`.
3. If the cause is `NoSuchMethodError`, `NoClassDefFoundError`,
   `ClassCastException` referencing a JustSearch class, OR mentions multiple
   JAR versions, this is a **CRITICAL** build-hygiene issue — report
   immediately and stop further validation. The install is broken; nothing
   downstream can be trusted.

### Rule 4: After install, verify install-dir version uniqueness

Right after `setup.exe /S` completes, run:

```powershell
$libs = Get-ChildItem "$env:LOCALAPPDATA\JustSearch\resources\headless\lib\" -Filter "*.jar" |
  Where-Object { $_.Name -match '^[a-z][a-z0-9-]+-2\.0\.0-alpha\.' } |
  ForEach-Object { ($_.Name -replace '-2\.0\.0-alpha\..*$', '') }
$duplicates = $libs | Group-Object | Where-Object Count -gt 1
if ($duplicates) { "FAIL: duplicate module jars: $($duplicates | %{ $_.Name })" }
else { "OK: each JustSearch module appears exactly once" }
```

Save output to `evidence/NN-install-jar-uniqueness.txt`. If FAIL, report the
specific duplicates and stop — the build is broken; runtime behaviour is
non-deterministic.

### Rule 5: Head log AND worker log are distinct surfaces

JustSearch has two log files. Bugs in different subsystems surface in different
files:

- `headless-backend.log` — head process: HTTP API, install flow, llama-server
  lifecycle, all `/api/*` endpoint exceptions.
- `worker.log` — worker process: encoder init, ORT session creation, indexing
  loop, gRPC handlers.

When you observe a symptom on an HTTP endpoint, the cause is often in the head
log. When you observe a symptom in indexing/embedding/search, the cause is
often in the worker log. Search both, not just one.

### Rule 6: `/api/status.worker.gpu` is field-access notation, not a URL

In CLAUDE.md and tempdocs you'll see references like
`/api/status.worker.gpu.embedOrtCuda.available`. The dotted suffix is JSON
field access into the `/api/status` response body — **not** a REST path. To
check that field, request `GET /api/status` and read
`.worker.gpu.embedOrtCuda.available` from the parsed JSON. There is no
distinct `/api/status.worker.gpu` endpoint.

### Rule 7: Stderr is data, not noise

The headless backend prints stderr lines like
`[stderr] WARNING: ... by io.justsearch.ort.GpuDriverApiProbe in an unnamed
module (file:.../ort-common-2.0.0-alpha.16-SNAPSHOT.jar)`. **The version
embedded in those paths is the version that actually loaded** — it's the
smoking gun for build-hygiene issues.

Save stderr verbatim and grep for `\.jar)` to extract loaded module versions.
If versions don't all match the version on the bundled docs/installer
filename, the build is stale.

### Rule 8: Save raw API responses verbatim

Save raw `/api/...` responses to `evidence/NN-name.json` verbatim — full body,
not summarized. Use `Invoke-RestMethod ... | ConvertTo-Json -Depth 20` to
ensure depth-truncation doesn't drop nested fields.

### Rule 9: Reranker is lazy-init; force HYBRID to exercise it

The cross-encoder reranker initialises on first HYBRID-mode search query. The
default search router picks TEXT mode for short queries (<6 tokens). To validate
reranker GPU init, issue a search with an explicit longer multi-clause query
like `"vaccine efficacy outcomes against omicron"` or pass `mode: "HYBRID"`
directly via `POST /api/knowledge/search`. After the query, check `/api/status`
for `rerankerOrtCuda.attempted=true` AND a non-zero rerank stage in the search
response's `pipelineExecution.components`. If neither appears, reranker is
still lazy-uninit and you haven't tested it.

### Rule 10: Restart between Install AI and final assessment

Some bugs surface only across an app restart (UiSettings persistence carries
fields into the next boot's snapshot, the worker re-spawns with different
sysprops). After Install AI completes and you've captured the immediately-
post-install state, fully close JustSearch (`Stop-Process -Name
JustSearch,java,llama-server -Force`) and relaunch. Compare post-restart to
immediately-post-install — every difference is a finding.

**Upgraded by Rule 15** — the single-cycle pattern here is the first cycle
of Rule 15's 3-cycle pattern. When validating lifecycle/persistence fixes,
do all three cycles, not just one.

### Rule 11: Don't patch from inside the sandbox

Your role is investigation and evidence-gathering, not bug-fixing. If you find
broken behaviour, document it precisely — do not modify JustSearch's code,
settings, or installed files to "make it work." Workarounds at the OS level
(env vars, junctions) are also out of scope; see Rule 1.

### Rule 12: Terminate runaway processes; don't infinite-poll

If a process exceeds your declared deadline, terminate it (`Stop-Process -Id
<PID> -Force`) and document why you expected it to finish sooner. Don't extend
the deadline without re-establishing the prediction.

### Rule 13: Quantitative GPU evidence — not just `available=true`

"GPU is on" is a quantitative claim. Always verify with at least one number,
not just a flag:

- **Chat**: tok/s. Target ≥ 40 on a 12 GB host. CPU baseline (alpha.12) is
  6.66 tok/s. <8× speedup is suspicious.
  Measure chat pass/fail when no ingest, enrichment, or indexing job is
  actively running; during those jobs, GPU/CPU contention can temporarily lower
  chat throughput and should be recorded as contention if the idle measurement
  passes.
- **Embed**: P50 ms per call. GPU is ~50ms; CPU is ~900ms. Look at
  `/api/debug/state` or worker log for `embed=NNNms` in backfill batches.
- **VRAM**: chat model holds ~5.5 GB; encoders + KV cache push to 7-8 GB at
  peak. If `nvml` reports <3 GB during chat, the model isn't on GPU.
- **Ingest throughput**: SciFact 5184 docs in <5 min on GPU; ~25 min on CPU.
  Wall time is the test.

### Rule 14: Sandbox shortcuts vs production flow

`JUSTSEARCH_MODELS_DIR` and a host-mounted `JustSearchModels` folder are
sandbox **shortcuts** for the Install AI download (~10 GB, ~25 min).
They do **not** represent the production flow.

The pre-staged dev tree contains BOTH `model.onnx` AND `model_fp16.onnx`
plus a `model_manifest.json` declaring both — the full layout. Production
Install AI's `GPU_FULL` profile downloads ONLY the FP16 CUDA variant.
Code paths that hardcode the legacy `model.onnx` filename work with
pre-staged models and break with Install-AI-only downloads.

This is not hypothetical. Sandbox rounds 1-11 every used pre-staged models
and never surfaced this class of bug. Round 12 was the first round to do
a true Install AI download and immediately surfaced **Bug S** — NER never
ran on `GPU_FULL` because `BertNerInference` probed the legacy hardcoded
filename. Eleven rounds of validation missed it because the shortcut
masked it.

**At least one validation round per release-candidate alpha must run with
`JUSTSEARCH_MODELS_DIR` UNSET** so Install AI does the full clean download.
The agent must explicitly call out which mode each round used (`fresh` vs
`pre-staged`) in the round summary so the user can verify coverage.

When pre-staged is used: the round is faster but does not validate the
download path, the manifest-resolution path on disk, or the
`%APPDATA%\io.justsearch.shell\native-bin` extraction. Treat its evidence
as supplementary, not definitive.

### Rule 15: Multi-cycle restart pattern

Run the cold-restart cycle at least **3 times** only when validating fixes that
touch lifecycle, persistence, port-binding, encoder init, or wireup state.
For a targeted frontend/UI-truthfulness round, do **one** restart sanity check
after Install AI and only expand to three cycles if state changes unexpectedly.
Between each cycle:

```powershell
Stop-Process -Name JustSearch,java,javaw,llama-server -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 5
& "$env:LOCALAPPDATA\JustSearch\JustSearch.exe"
```

Snapshot `/api/status` after each cycle. Save to `evidence/NN-status-cycle-K.json`
(K = 1, 2, 3). Behaviour stable across 3 cycles is more trustworthy than
single-cycle stability.

Why three: round 10 caught Bug L+M with one restart. Round 11 confirmed
alpha.18 fixes hold across one restart. Round 12 ran 3 restarts to confirm
alpha.20 fixes hold AND established a memory-stability baseline (the heap
delta cycles 2→3 was ~6 MB; cycles 1→2 was higher because of one-time
ORT session warmup). One cycle proves "doesn't crash on relaunch"; three
cycles prove "doesn't drift over normal-use lifetime."

Replaces the single-restart wording in Rule 10 — the Rule 10 pattern is now
the first cycle of this rule.

### Rule 16: Tauri/Lit shell GUI validation pass

After the cheap structured sanity checks, do a **targeted frontend validation
pass** on the Tauri shell window. Save screenshots for the surfaces you inspect,
starting with `evidence/NN-tauri-first-paint.png` and
`evidence/NN-tauri-foreground.png`.

What to do:

- First paint: record which surface opens, any splash/loading delay, and whether
  it is usable without hidden setup knowledge.
- Navigation: visit Library, Brain/Install AI, Health/System, Search, and Chat.
  Settings/Help are optional unless something directs you there. Broken views,
  raw i18n keys, permanent "Loading..." states, or missing labels on essential
  controls are findings.
- Library/indexing: add a folder through the UI if possible. Observe whether
  the path field validates before submit, whether the folder row shows progress
  and a terminal indexed state, and whether the Tasks panel updates while left
  open. If you must fall back to API ingest for SciFact, first record the UI
  limitation that forced the fallback.
- Install AI/Brain: verify the UI state before install, during download, after
  completion, and after restart. For current rounds, specifically check whether
  Health/System shows install activity instead of "System idle" and whether the
  command palette exposes a broken zero-argument "Start AI Install" path.
- Search/Chat: type real queries in the UI, not just API requests. Verify
  rendered result counts, facets, snippets/highlights, path labels, "why this
  result" details, and any Chat/Search duplicate render paths against raw API
  responses.
- Status truthfulness: if `/api/status` or `/api/health` reports DEGRADED,
  the UI must not say "Ready" without an accurate qualifier. If install,
  indexing, embedding, or enrichment is active, Health/System must not say
  "System idle". If the REST endpoints are responding with READY/200 during
  ingest, the UI must not remain in "Reconnecting..." without recovering.
- Messaging: stacked toasts must not hide primary controls. Copy/export actions
  need visible confirmation. Internal IDs, path hashes, raw enum/reason codes,
  or diagnostic-only terms on user-tier surfaces are findings.
- If WebView2 dev tools are accessible (`Ctrl+Shift+I` or
  `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--enable-features=...`), check the
  JavaScript console for errors. Save any non-empty output to
  `evidence/NN-webview2-console.txt`.

Note observations precisely. **Do not** root-cause UI bugs from the sandbox:
the source code is not staged here. Your job is evidence, reproduction steps,
screenshots, raw API/log captures, and severity.

### Rule 17: Deliberate-trigger pattern for fix verification

When an alpha closes a fix, verify the fix **deterministically** — don't
rely on the bug's natural triggering conditions. The pre-fix failure mode
being absent is meaningful only when the failure mode's preconditions are
**actually present**.

Round 12 Bug O example: alpha.21 fixed port-rebind on `BIND_FAILED` by
falling back to ephemeral. Verifying this by restarting and hoping
TIME_WAIT happens to still hold port 8080 is unreliable — it usually does
not. Instead:

```powershell
# Terminal A: hold port 8080 deliberately
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 8080)
$listener.Start()
# Terminal B: launch JustSearch and watch the log
& "$env:LOCALAPPDATA\JustSearch\JustSearch.exe"
# Verify: head log shows ephemeral fallback fired, /api/health is reachable
# on the new port (read head.apiPort from runtime/manifest.json)
# Terminal A: $listener.Stop()
```

Other deliberate triggers:

- **Stale jar / build hygiene** — copy a foreign-version jar into
  `%LOCALAPPDATA%\JustSearch\resources\headless\lib\`, restart, verify
  Rule 4 catches it.
- **Catalog drift / RRD missing datasource** (alpha.22 Bug T) — copy an
  older `metrics.rrd` from a prior round's `%APPDATA%\io.justsearch.shell\
  data\telemetry\` into the current install, restart, watch for the
  pre-fix `WARN  Failed to record RRD sample` (post-fix should log only
  at DEBUG, which the agent must opt into via debug-level logging to see).

When a fix's failure mode requires precise conditions (a race, a specific
order of bytes in a config file, a network state), the agent must
construct those conditions deliberately. "Did not see X this round" is
meaningless if X's preconditions never occurred this round.

### Rule 18: Windows trust/security prompts are validation evidence

Before and during install, record the Windows trust experience:

- SmartScreen, Defender, Smart App Control, reputation, or unsigned-publisher
  dialogs.
- Any blocked executable, child process, DLL load, script, or installer action.
- Whether the user can proceed, what exact click/action is required, and whether
  the app later launches normally.

Save screenshots where possible and write exact text to
`evidence/NN-windows-security.txt`. Do not dismiss this as "environmental"
until the prompt/block and its consequence are captured.

## What you can fast-path (proven stable across rounds 7-12)

These checks have passed every round and rarely catch new bugs. Replace
detailed evidence-gathering with single-line sanity checks; expand only if
something looks off.

| Check | Fast-path |
|---|---|
| NVML / nvcuda probe at session start | Trust sandbox-environment.md baseline. Skip the explicit probe. If GPU detection is broken in JustSearch, `/api/inference/status.gpu.cudaAvailable` will surface it. |
| Per-user install path (`%LOCALAPPDATA%\JustSearch\`) | Skip explicit path verification. If wrong, every downstream check fails as a symptom. |
| Built-in help search returning `getting-started.md` | Skip. Stable since alpha.5+. |
| Uninstall verification | Skip the formal uninstall step. Run only when explicitly testing uninstall regressions. |
| cuda-runtime per-zip extraction logging | Skip the per-zip `Extracted N entries from <zip>` line-by-line check. Just verify `installedFully: true` and that `cuda12/` directory exists with `llama-server.exe`. |
| Pre-Install-AI baseline state (7-8 evidence files) | Single sanity check: one `/api/health` snapshot + one `/api/ai/runtime/status` snapshot. Confirm `DEGRADED [inference.offline]`. Expand only if off. |
| alpha.13–.17 wireup chain log messages | Downgrade to: check `active.activeVariantId == "cuda12"` and `active.gpuLayers == 99` on `/api/ai/runtime/status`. If both pass, the upstream wireup is fine. |
| Embedding fingerprint matches FP16 CUDA SHA | Downgrade to: check `embeddingCoveragePercent` reaches 100 after ingest. Implicit fingerprint check — if SHA mismatched, coverage stays at 0. |
| Chat tok/s measured twice (post-install + post-restart) | Downgrade to: one measurement post-install. Pass/fail at ≥ 40 tok/s. The post-restart re-verification is "did wireup persist," not "is GPU still fast." |
| Bug O port-rebind verification (alpha.21) | Downgrade to: deliberate-trigger test (Rule 17) once per release-candidate, not every round. The structural fix is settled; only revalidate if the binding code in `LocalApiServer` changes. |
| Bug L/M cold-restart wireup (alpha.20) | Covered by Rule 15's cycle 2 `/api/status` snapshot — `activeVariantId` and `gpuLayers` should match cycle 1. No separate evidence file. |
| Full 7-package manifest anatomy | Downgrade to: save one `/api/ai/install/status` after completion and verify `installedFully: true`; do not document every package transition unless a package is skipped/failed. |
| cuda12 DLL inventory | Downgrade to: verify `cuda12/llama-server.exe` exists and runtime status selects `cuda12`; do not count every DLL unless ORT/CUDA status reports missing DLLs. |
| llama-server command line | Downgrade to: capture only if chat tok/s < 40, runtime status does not show `gpuLayers == 99`, or the UI/API says inference is degraded after activation. |
| Built-in 5-help-doc search | Skip in targeted rounds. Use SciFact/HYBRID search after ingest; built-in docs are already covered indirectly by backend boot and are not the current risk. |
| Screenshot tour of every surface | Downgrade to targeted screenshots: first paint, Health during install, Health during ingest, Library/Add Folder/toast, Search/Chat mode label, and any new defect. |
| Three restart cycles | One restart is enough for frontend/UI-truthfulness rounds. Use three only when lifecycle, wireup, persistence, or port-binding code is the stated target. |

**When to re-promote a fast-pathed check:** if a round touches Tauri, NSIS,
ORT, JDK, or Gradle plugin versions, re-add the relevant check explicitly for
that round. Stability is empirical — it can break with version bumps.

## What to KEEP doing thoroughly (catches real regressions)

- Stack-trace harvest on any 500 (Rule 3)
- Install-dir version-uniqueness check (Rule 4)
- Stderr capture and grep for `.jar)` paths (Rule 7)
- Worker log error scan for `Failed to initialize` / `Caused by` / `not found at any standard location`
- End-to-end SciFact ingest with all encoders, but record final coverage and
  UI truthfulness rather than every intermediate poll when progress is normal.
- One restart cycle for targeted UI rounds; three cycles only under Rule 15's
  explicit conditions.
- One HYBRID query for reranker (Rule 9)
- `/api/status` aggregate endpoint check (was the smoking gun for alpha.18 build hygiene)
- Windows trust/security prompt capture (Rule 18)
- Tauri/Lit GUI validation with UI-vs-API comparison (Rule 16), focused on the
  current residual risks instead of a broad screenshot tour.

## When troubleshooting

If something fails (app won't start, backend stuck in DEGRADED, search returns
nothing), reason from the documentation. Use the API endpoints listed in
`sandbox-environment.md` to inspect state (`/api/health`, `/api/status`,
`/api/debug/state`). Do not invent commands that depend on dev-environment
tooling. Do not edit project files — there is no project here, only
documentation.

If `/api/status` returns 500, check the head log for the underlying exception
(Rule 3) before assuming any subsystem is healthy or unhealthy.
