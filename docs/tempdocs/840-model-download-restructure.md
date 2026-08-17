# 840 — Model download: component-level install, staged acquisition, and the backend restructure it needs

    status:  DESIGN — findings recorded, direction decided by owner, not yet planned or implemented
    created: 2026-08-18
    updated: 2026-08-18
    follows: 824 (repair remedy / terminal transport), 805 (observed-vs-declared capability),
             804 (registry additions vs completeness), 798 (resumable cancel), 657 (capability
             tiers + plan preview), 562 (install state is a function of disk)

## 1. Subject

The AI model download path, end to end: `AiInstallService` → `ResumableFetch` → `DownloadExecutor`
→ `GET /api/ai/install/status` → `aiInstallPoll` → `BrainSurface`. Plus the adjacent Tauri
self-update download, which shares nothing with it.

Two workstreams that turn out to be the same work from opposite ends:

- **W1 — user-visible.** The install is one undifferentiated ~7 GB wait with no rate, no ETA, and
  no per-component identity. Owner direction (2026-08-18): split it per component, categorise by
  necessity, and explain what each component does.
- **W2 — backend structure.** `AiInstallService` has no stage boundary, so `repair()` can only mean
  "run everything again", and nothing owns the *set* of fetches. W1 is blocked on exactly that.

## 2. Current shape (verified against `main`, 2026-08-18)

```
modules/ui            AiInstallController ──┐  (+ owns ai/model-registry.v2.json as a resource)
modules/app-api       AiInstallService (interface — bypassed) · AiInstallStatus (mutable DTO)
modules/app-services  AiInstallService (impl, 1803 L) ─┬─ ResumableFetch  (decide→transfer→verify→retry)
                                                       ├─ DownloadExecutor (BITS/PowerShell + curl.exe)
                                                       ├─ DownloadResume  (sidecar identity)
                                                       ├─ TransportRetryPolicy / InstallAttemptMemory
                                                       └─ InstallCompleteness
modules/configuration InstallPlanner · InstallPlan · ModelRegistry · InstallContract · HardwareProfile
```

Transfer is sequential, one file at a time, over BITS (Windows) with a `curl.exe` fallback and a
tier-escalating retry. Status is a single session-ephemeral mutable object polled at 1 Hz.

### What is already good, and must survive any restructure

Recorded so the rework does not regress what earlier rounds bought:

- **`InstallPlanner` is a genuinely pure function** (no IO beyond `Files.exists`). This is the
  load-bearing good decision — it is why the consent screen can be honest, why `/plan-preview`
  exists, and why completeness can be recomputed after the fact. Preserve purity.
- **Two correct test seams**: `ResumableFetch.Transfer` and `DownloadExecutor.BitsControl` make the
  two hardest loops testable without a network or Windows.
- **Fail-closed integrity**: `DownloadResume.decide` returns `FRESH` on every branch that cannot
  prove identity; `read()` treats absent/unreadable/malformed alike; `verify()` throws. No path
  moves unverified bytes to target.
- **Install state is a function of disk, not a remembered event** (562).
- Operation-lease integration so a multi-hour download does not deadlock an app update.

## 3. W2 findings — backend architecture

Ordered by severity. All source-verified.

| # | Finding | Evidence |
|---|---|---|
| **B1** | **`AiInstallService` is a system-mutation orchestrator, not a download service.** 1803 lines, ~14 collaborators; the download is ~120 of them. It also probes GPU hardware, mutates `UiSettings` across four separate load/save round-trips, sets four global JVM system properties, rebuilds the global `ConfigStore` from four call sites, applies llama-server runtime overrides, restarts the **Worker OS process** + reconnects gRPC + resets its circuit breaker, brackets a reconciler procedure, runs a 60 s live LLM smoke test, writes the install contract, unzips archives. Four responsibilities — *acquire · place · configure · validate* — welded into one 265-line method with no stage boundary and no rollback. **This is why `repair()` is literally `startInstall()`, and why `InstallAttemptMemory` had to be invented** to give repeated whole-plan re-runs the memory a staged design would not need. | `AiInstallService.java:640-904`, `:632-634`, `:1214-1503` |
| **B2** | **Config phase mutates global state with no enforced ordering and no rollback.** `applyCudaServerExe()` must precede `applySettings()`; the enforcement is a comment. `setSysPropIfBlank` makes first-writer-wins a permanent latch for the JVM lifetime. Four `settingsStore.load()`→mutate→`save()` cycles with no CAS: a concurrent UI settings write is a lost update. A throw midway leaves saved settings + rebuilt ConfigStore + no runtime override, silently. | `:881-889`, `:1226-1242`, `:1345-1348` |
| **B3** | **The `app-api` interface is decorative and its javadoc describes a layout that no longer exists.** `AiInstallController` imports the **concrete** impl, not the interface, because it needs `setKnowledgeServer()` / `previewInstallPlan()`. The interface javadoc justifies itself with "the impl lives in `modules/ui/.../ai/install/` … so app-services need not import ui" — the impl has since moved *into* app-services. Also `repair()`'s contract says "re-verify hashes, re-apply settings"; the impl re-derives and re-installs the whole plan. | `AiInstallController.java:11`, `app-api/AiInstallService.java:11-13,64-68` |
| **B4** | **The model registry is an unenforced cross-module runtime contract.** `AiInstallService` loads `"ai/model-registry.v2.json"` through `Thread.currentThread().getContextClassLoader()`. The only production copy lives in **`modules/ui/src/main/resources/`** — a module `app-services` neither depends on nor may (cycle). The service's most important input arrives by classpath coincidence with no compile-time or gate-time check; it fails at runtime as `MANIFEST_UNAVAILABLE`. A second copy in `modules/configuration/src/test/resources/` can drift independently, and nothing compares them. | `AiInstallService.java:70`, `ModelRegistryLoader.java:32-34` |
| **B5** | **Transport observation is one PowerShell process per sample.** `getBitsSnapshot()` spawns `powershell.exe -NoProfile -ExecutionPolicy Bypass -Command <script>` **every 750 ms** for the whole download — ~3 600 process launches for a 45-minute transfer. Observing the transfer costs more than its control plane. Scripts are assembled by string concatenation with `psEscape()` (single-quote doubling) as the only defence — safe today only because URLs come from a shipped registry, and the app already has a user-supplied pack-import path. | `DownloadExecutor.java:722-751`, `:780-799`, `:807-810` |
| **B6** | **Failure classification is round-tripped through English prose.** `TransportFailure` is a typed record, correctly populated — then `ResumableFetch.Outcome` drops it and keeps only `String error`, and `InstallAttemptMemory.isTransportFailure()` recovers the classification with `error.startsWith("Download failed for")`. Whether transport escalation engages at all depends on the wording of a user-facing message. The javadoc pins it with a test; pinning a defect is not fixing it. | `ResumableFetch.java:98-103`, `InstallAttemptMemory.java:60,104-106` |
| **B7** | **Orphaned BITS jobs on abnormal termination.** `runAttempt` writes the sidecar with `bitsJobId: null` before *every* transfer; the id is written back only by `persistSuspendedJob` on a **graceful** failed transfer. A crash mid-BITS-transfer leaves a live job in the system queue (90-day default lifetime) that nothing reclaims — `abandonResumeHandle` fires only when a sidecar *records* an id. `-DisplayName 'JustSearch AI'` is set and **there is no sweep by DisplayName anywhere**: the one hook that would make reclamation possible is set and unused. | `ResumableFetch.java:266,349-358`, `DownloadExecutor.java:701` |
| **B8** | **`getStatus()` returns the live mutable object across a "stable API contract" boundary.** `return status;` inside `synchronized(lock)`, then `ctx.json(...)` serialises it with **no lock** while the install thread mutates. Torn reads are the mild case; `status.packages.clear()` + repopulate while Jackson iterates that `ArrayList` is a `ConcurrentModificationException` window — narrow (once per run) but exactly when the user is watching. The interface javadoc *documents* the hazard instead of eliminating it. | `AiInstallService.java:290-304,686-687`, `AiInstallController.java:64` |
| **B9** | **Cancellation has a blind tail.** `cancelFlag` is checked per download iteration and once after the loop. After that — contract write, four config applications, worker restart, and a **60 s** `askQuestion(...).get(60, SECONDS)` — there is **no cancel check at all**, and the run proceeds to `applyCompletionState()`. The op-lease drain callback is `this::cancel`, so during the smoke test an app update's drain request is a 60-second no-op and the run then reports *completed* with `cancelRequested` true. | `:859-903`, `:1489`, `:560-571` |
| **B10** | **Nothing owns the set of fetches.** The fetch-one-file primitive is well-factored; the *collection* is a raw `for` loop inline in `runInstallInternal` with loop-local mutable accumulators. Parallelism, ordering (small files first), or resuming the *plan* across a process restart each require rewriting the loop rather than swapping a component. **This is the direct blocker for W1.** | `:723-857` |
| **B11** | **Domain logic placed in the lowest module.** `InstallPlanner` / `InstallPlan` / `ModelRegistry` / `InstallContract` / `HardwareProfile` live in `modules/configuration`, which nearly everything depends on — model-install domain logic is now on every module's compile classpath. | `modules/configuration/.../model/` |
| **B12** | **One number computed in three places.** `plan.totalBytes()` → `status.totalBytes`; `populateStatusPackages` re-sums the same plan per package; the FE ignores both and re-sums `packages[]`. The arithmetic *is* currently consistent including on resume (`InstallPlan` javadoc reasons about it) — the defect is that consistency is hand-maintained across three sites and a process boundary. | `:684`, `:1592-1595`, `BrainSurface.ts:1451-1455` |

## 4. W1 findings — user-visible

| # | Finding |
|---|---|
| **U1** | **No speed, no ETA, no time remaining.** ~7 GB shown as a percentage and a byte counter with no rate. No way to distinguish a slow link from a stalled one until the `stalled` badge fires. The app *has* an ETA concept — `formatRestartEtaSub`, for a ~10 s LLM restart. The hour-long operation does not. |
| **U2** | **No free-disk-space precondition.** Nothing checks usable space before committing to a multi-GB download. `INDEX_DISK_FULL` exists for indexing; there is no install equivalent. Failure is a late `INSTALL_IO_ERROR` after a long wait. |
| **U3** | **Strictly sequential, single-stream.** No parallelism across packages, none within a file. BITS partly mitigates; the curl fallback does not. |
| **U4** | **`ModelPackage.description` exists in the registry and is rendered nowhere.** "Vector embeddings for multilingual semantic search", "Named entity recognition", "Conversational AI with reasoning for document Q&A" — written for exactly this purpose, dead data today. The only `.description` the UI reads is the LLM *variant* one. Same for `CapabilityTier`'s human labels. |
| **U5** | **The phase message names a filesystem path** (`Downloading onnx/gte-multilingual-base/model.onnx...`) while the package list beside it has friendly labels. |
| **U6** | **Discovery.** The download lives on the Brain surface and in the command palette; no onboarding surface drives a new user there. |
| **U7** | **The app-updater download has no progress at all.** `update.download(\|_, _\| {}, \|\| {})` — the progress callback is a literal no-op and the whole installer is buffered in memory before staging. `appUpdateState.ts:36-37` declares `bytesDownloaded`/`bytesTotal`; **grep confirms zero producers anywhere in `modules/shell`**. The repo's own `wire-emitter-elision` handle, sitting in the update path. |
| **U8** | Two independent pollers on one endpoint with different cadences and projections — `aiInstallPoll` (1 Hz → `aiStateStore` → `aiVerdict`) and `aiInstallBridge` (2.5 s → `isAiInstallLive` → SystemSelfView). Liveness *predicate* is shared; fetch, retention and state projection are not. Surfaces can disagree by ~2.5 s, and the endpoint is hit ~1.4×/s forever whether or not anything is installing. |
| **U9** | Comment drift: `BrainSurface.ts:966` says the shared poller runs "on a fixed 3s cadence"; `aiInstallPoll.ts:182` is `INTERVAL_MS = 1000`. |

## 5. Owner direction (2026-08-18)

> "the downloads shouldn't all be merged into one. instead each component should be split and it
> could be sorted into optional/necessary categories or similar with a short explanation of what
> each component actually does."

Accepted, with one refinement agreed in the same exchange.

### 5.1 Necessity is four categories, not a binary

"Optional / necessary" collapses states that need different words:

| Category | Components | What the user reads |
|---|---|---|
| **Required** | embedding | Search does not work without this |
| **Improves results** | SPLADE, reranker, NER, citation-scorer | Search works; results are worse |
| **Adds a feature** | chat LLM | Search unaffected; no chat or summaries |
| **Not supported here** | cuda-runtime on non-NVIDIA, GGUF under the VRAM floor | Your machine cannot run it — not a choice |

*Improves results* is the risky one: label the reranker "optional" and people uncheck it to save
340 MB, get measurably worse search, and never connect cause to effect. *Not supported here* is not
optionality at all — an unchecked box implies a choice the user does not have.

### 5.2 Interaction: opt-out, not opt-in

Default = everything this machine supports, pre-selected; an expandable list gives name +
one-line description + size + category, with the ability to uncheck. Opt-in would leave most users
on a degraded retrieval stack they did not understand they were choosing. `InstallIntent`
(full-desktop / headless / mcp-lite) stays the coarse preset above per-component control.

Granularity stays at the **package** level. File-level is noise, and `required: false` metadata
sidecars stay invisible — round 16 proved that surfacing an absent 872-byte config file reads as
alarm.

### 5.3 Two hard parts this creates

- **H1 — dependency edges do not exist.** `cuda-runtime` is not a capability; it is a prerequisite
  for the FP16/CUDA variant of *every* ONNX model plus GPU chat. Deselecting it silently drops each
  encoder to CPU — **literally the round-11 defect** (805 G.3: `installedFully:true`, everything
  green, every ONNX session on CPU, no field able to say so). The product spent two tempdocs
  learning to *detect* that state; per-component selection without a dependency model lets a user
  deliberately construct it. The registry has `requiresCuda` on a package but no package→package
  edge.
- **H2 — a deliberate decline must survive the completeness model.** `repairNeeded` /
  `installedFully` are computed against what the *registry* declares. Someone who declines the
  reranker on purpose must not spend the rest of the app's life being told "a required component is
  missing" and offered Repair. The choice has to be persisted — `InstallContract` is the natural
  home, it is already the bill of materials that recorded the installation — and
  `InstallCompleteness` must reconcile against *chosen*, not *declared*. Same shape as 804's
  `pendingRegistryAdditions`.

## 6. Decisions taken

| # | Decision | Rationale |
|---|---|---|
| **D1** | **Staged / progressive acquisition.** Install the required core first (~1.3 GB), let search work immediately, then pull enrichment and the LLM in the background with pause/resume. | Today a user waits for ~7 GB before *anything* works. This is a larger win than a selection list, and it is the natural consumer of the scheduler B10 says is missing. Costs: background-vs-foreground progress, and runtime reconciliation as capabilities arrive one at a time. |
| **D2** | **Backend stages first**, then the user-visible work on top. | The `acquire → place → configure → validate` split, typed failure classification, and the registry-resource fix give W1 real seams to sit on; B1/B9/B10 stop blocking it. Slower to visible progress, but the UX work does not get built twice. |

## 7. Scope

Everything in §3 (B1–B12), §4 (U1–U9), and §5 (component split, four categories, descriptions,
H1 dependency model, H2 chosen-vs-declared completeness), sequenced per D1/D2.

Per `tempdoc-is-your-contract`: every item above is work the owner has judged necessary. Items are
not dropped for difficulty or diminishing returns — if one proves infeasible, say why and ask.

## 8. Not yet decided

- Where the staged scheduler lives (new class in `app-services` vs a `configuration`-side plan
  executor) — a planning question, see §D2 sequencing.
- Whether the dependency model (H1) is expressed in the registry JSON or derived from
  `requiresCuda` + variant `targetEP`.
- Whether U7 (updater progress) rides along here or splits to its own change — it shares no code
  with the model path.

## 9. Log

- **2026-08-18** — Backend + frontend read end to end; findings B1–B12 / U1–U9 recorded above.
  Owner added the component-split direction (§5); four-category refinement and opt-out interaction
  agreed. D1 (staged) and D2 (backend-first) taken. Next: `/plan`.
