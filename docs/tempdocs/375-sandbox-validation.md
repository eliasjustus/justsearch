---
title: "Sandbox Validation"
status: done
created: 2026-04-02
updated: 2026-05-30
parent: 374
related: [374, 226]
---

# 375. Sandbox Validation

End-to-end validation of the JustSearch installer inside a Windows Sandbox
on a clean machine. Parent: tempdoc 374 (App Packaging & Distribution).

> **Update (2026-05-30) — post-rewrite FE re-validation.** The two FE/UX
> findings here were re-checked against the rewritten Lit `shell-v0` frontend.
> **A** (install-AI critical-vs-optional failure) is **resolved**. **B** (empty
> first-run search state) is **also resolved — but by the backend, not the FE**:
> 5 built-in help docs are now bundled and auto-ingested on first run, so the
> corpus is non-empty and the "empty first-run / ships no content" observation
> below is **stale/closed** (recorded by-design as UIX-015 in
> `docs/reference/issues/decisions.md`). Details in
> [§ Post-rewrite FE re-validation](#post-rewrite-fe-re-validation-2026-05-30)
> at the foot of this doc. Backend bugs G27–G31 were out of scope for the FE
> re-check.

## Environment

| Property | Value |
|----------|-------|
| Host GPU | NVIDIA GeForce RTX 4070 (12 GB VRAM, driver 595.79) |
| Sandbox OS | Windows 11 Enterprise 10.0.26100 |
| Java | OpenJDK Temurin 25.0.1+8 (jlink stripped runtime) |
| Bootstrap | All OK (git, Chrome, Claude Code 2.1.90, JustSearch at `C:\JustSearch`) |
| Validation runs | 2 (initial + independent re-validation 2026-04-02T19:14Z) |

## GPU Availability in Sandbox

The RTX 4070 is visible inside the sandbox via Windows vGPU (WDDM/DirectX
passthrough), and the backend's own GPU detection reports it as available
(`"gpu":{"available":true,"totalVramBytes":12878610432,"driverVersion":"595.79"}`).
However, **CUDA inference is not functional**.

| Check | Result |
|-------|--------|
| GPU enumerated (`Win32_VideoController`) | Yes — NVIDIA GeForce RTX 4070 |
| Backend GPU detection (`/api/status` → `.gpu`) | `available: true`, 12 GB VRAM |
| `nvcuda.dll` (driver API) | Present in System32 |
| `nvidia-smi` | Not found |
| `cudart64_*.dll` (CUDA runtime) | **Missing** |
| `cublas64_*.dll` | **Missing** |
| CUDA/ORT DLLs in JustSearch bundle | **None** (per 374-G16, not bundled) |
| ORT CUDA provider | Cannot load — no runtime DLLs |

**Root cause:** Windows Sandbox passes through the GPU for DirectX rendering
but does not expose the CUDA runtime toolkit. The NVIDIA driver API (`nvcuda.dll`)
is present, but without `cudart64_12.dll`, `cublas64_12.dll`, and
`cublasLt64_12.dll`, neither ONNX Runtime CUDA EP nor llama-server CUDA can
initialize GPU sessions.

**To enable GPU in the sandbox, two things are needed:**

1. **CUDA runtime DLLs in the sandbox** — either install CUDA Toolkit 12.x
   (~2 GB download per sandbox launch) or copy the specific runtime DLLs
   from the host into the sandbox via the mapped folder.
2. **ORT CUDA DLLs** — `onnxruntime_providers_cuda.dll` etc. (~500 MB).
   These are not bundled in the installer (374-G16). Would need to be
   staged into `dataDir/native-bin/ort-cuda/` or pointed at via
   `JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH`.

**Implication:** All ONNX inference (embedding, SPLADE, NER, reranker) and
LLM inference will run CPU-only in this validation. This is representative
of the experience on machines without NVIDIA GPUs.

---

## Bug: Worker Spawn Fails on Installed App (G27)

**Severity:** Critical (blocks all indexing and search on a fresh install)

**Symptom:** `/api/health` reports `worker.spawn.failed`. The Head process
starts and serves the API, but the Worker never spawns. Error:

```
java.lang.IllegalStateException: Worker lib directory not found.
  Build with: ./gradlew :modules:indexer-worker:installDist
  at io.justsearch.app.services.worker.KnowledgeServerConfig.resolveWorkerLibDir(KnowledgeServerConfig.java:213)
```

**The directory exists:** `C:\JustSearch\resources\headless\lib\worker\` contains
176 JARs including `indexer-worker-2.0.0-SNAPSHOT.jar`. The bundling is correct.

**Root cause:** `lib.rs:567` sets `-Djustsearch.prod=false` (disabled for alpha
browser testing). This causes `KnowledgeServerConfig.resolveWorkerLibDir(isProd=false)`
to skip the production resolution path (`libDir.resolve("worker")`) entirely,
falling through to the Gradle dev layout path
(`modules/indexer-worker/build/install/indexer-worker/lib/`) which doesn't exist
in the installed app.

The `\\?\` prefix from Tauri's `resource_dir()` is a secondary bug — it causes
`Files.isDirectory()` to return false — but the primary cause is prod=false
preventing the correct path from ever being tried.

**Fix:** Make `resolveWorkerLibDir()` check the prod path (`lib/worker/`) as a
fallback regardless of `isProd`, before throwing. This is more defensive than
changing prod=true (which affects CORS, session tokens, etc.).

---

## Bug: nvidia-smi Polling Spam (G28)

**Severity:** Low (performance/log noise)

The `VramDetector` polls `nvidia-smi` every 5-10 seconds even when it's not
installed. Each poll logs a DEBUG-level `CreateProcess error=2` exception.

**Root cause:** `VramDetector.getTotalVramBytes()` has a 60s success cache but
returns `-1` uncached on failure. `getAvailableVramBytes()` and `getDriverVersion()`
have no cache at all. `GpuCapabilitiesService.snapshot()` calls all three every
5s (status API TTL), so all three respawn `nvidia-smi` on every poll.

**Fix:** Cache the failure result with a permanent TTL so subsequent calls
short-circuit without spawning a process.

---

## Bug: ORT ONNX Session Hangs on CPU (G29)

**Severity:** High (blocks all enrichment on CPU-only machines)

After model download + backend restart, the Worker discovers all ONNX models:
- Embedding (gte-multilingual-base) — found, loading initiated
- SPLADE (naver-splade-v3) — found
- Reranker — found
- Citation-scorer — found
- NER — NOT discovered (no log at all, model at `models/onnx/ner/`)

However, the ORT session creation for embedding hangs indefinitely.
Worker log shows `"Creating ONNX embedding backend: ...gpuEnabled=false"` at
17:50:56, but after 15+ minutes:
- Worker CPU time: 8.2 seconds total (essentially idle)
- Working set: stable at 312 MB
- No completion or error log messages
- `/api/status` still reports `embedding_not_ready`

**Root cause:** Not a true hang — extremely slow graph optimization. The model
manifest declares `"cpu": "model_fp16.onnx"`. ORT CPU EP doesn't natively
support FP16 ops, so it must insert Cast (FP16->FP32) nodes before every
operation. At `EXTENDED_OPT` level (`OnnxSessionCache.optimizeAndCache()`),
ORT then runs extensive graph rewriting (fusion, constant folding, shape
inference) over the now-much-larger graph with thousands of injected Cast nodes.
This is single-threaded and could take 30-60+ minutes.

Contributing factors:
- No FP32 model shipped in distribution (registry only downloads FP16)
- `OnnxSessionCache` uses `EXTENDED_OPT` unconditionally
- No timeout on `env.createSession()` — the call is synchronous and unbounded
- On first run, no `.optimized` cache file exists

**Fix:** Use `NO_OPT` for CPU sessions loading FP16 models (the `.optimized`
cache won't help since CPU needs FP32 anyway). Long-term: ship FP32 CPU model
or pre-optimize offline.

**Impact:** On CPU-only machines (no CUDA), users who Install AI will never
get enrichment (embedding, SPLADE, NER, chunks). Search works in TEXT-only
mode but hybrid/semantic search is permanently unavailable.

---

## Bug: FP16 Reranker Download Fails (G30)

**Severity:** Low (FP32 reranker works, FP16 is optional GPU variant)

`gte-multilingual-reranker-base-model_fp16.onnx` download fails with
`curl.exe exit code 22` (HTTP error, likely 404 or auth issue). Re-validation
confirmed: 0 bytes downloaded, state `failed`. The FP32 reranker downloads
fine. Per tempdoc 374 G25, FP16 vs FP32 produces identical nDCG (p=0.76),
so this is cosmetic.

The install flow reports `state: failed` even though 23/24 assets succeeded
and the one failure is non-critical. Additionally, `citation-config.json`
(824 bytes) is left in `pending` state — the download pipeline appears to
abort remaining assets after the first failure. The install UX should
distinguish between critical and optional asset failures.

---

## Bug: NER Model Not Discovered (G31)

**Severity:** Medium (NER enrichment unavailable)

**Root cause:** Downstream consequence of G27. NER discovery works correctly
(naming `onnx/ner/` is right, code path in `NerModelDiscovery` is correct),
but discovery logging is DEBUG-only so silence at INFO level is expected.
The actual issue is that with `prod=false`, `resolveModelRoots()` uses wrong
base paths, so the Worker never finds `models/onnx/ner/`.

Once G27 is fixed (prod path resolution works), NER should auto-enable via
the standard discovery chain in `NerConfig.from()`.

**No separate fix needed** — resolves with G27.

---

## Validation Checklist

| # | Test | Status | Notes |
|---|------|--------|-------|
| 1 | App launch | PASS | `JustSearch.exe` starts, Tauri window renders, backend spawned on port 8080 |
| 2 | Backend health | PASS (with workaround) | Head: READY. Worker: fails without `-Djustsearch.worker.lib.dir` override (G27). With override: READY. Inference: DEGRADED (expected — no models yet). |
| 3 | Built-in search ("getting started") | PASS | Returns 1 result (getting-started-guide.txt) with score 2.76. TEXT mode only (no embeddings). |
| 4 | File indexing | PASS | Created 3 test files, added via `POST /api/indexing/roots`. All 3 indexed. Searches for "sourdough" (score 4.49), "quantum entanglement" (score 3.59) return correct docs. |
| 5 | Install AI (model download) | PARTIAL | 23/24 assets downloaded (8.49 / 9.08 GB). FP16 reranker failed (G30 — 0 bytes, likely 404). `citation-config.json` left in `pending` state. All critical models present on disk in correct directory structure. Overall state: `failed` despite 23/24 success. |
| 6 | Enrichment (embed/SPLADE/NER on CPU) | FAIL | ORT session creation hangs indefinitely on CPU (G29). After 15+ min, Worker CPU time is 8s (idle). No enrichment runs. NER not even discovered (G31). |
| 7 | Uninstall | PASS | Silent uninstall (`/S`) removes `C:\JustSearch` completely (1236 files). No residual processes. User data (AppData Roaming/Local) preserved as expected (374-G5), totaling ~11.2 GB. |

### Independent Re-Validation (2026-04-02T19:14Z)

A second independent validation run confirmed all findings from the initial run.

**Procedure:**
1. Launched `JustSearch.exe` from clean sandbox — Tauri window rendered, backend on port 8080
2. Confirmed `worker.spawn.failed` (G27) — same error message
3. Manually launched headless backend with workaround:
   ```
   cd C:/JustSearch/resources/headless
   ./runtime/bin/java \
     -Djustsearch.worker.lib.dir="C:/JustSearch/resources/headless/lib/worker" \
     -Djustsearch.api.port=8080 \
     -cp "ui-headless.jar;lib/*" \
     io.justsearch.ui.HeadlessApp
   ```
   Main class is `io.justsearch.ui.HeadlessApp` (not `io.justsearch.ui.headless.HeadlessApp`).
4. Created 3 test files, added via `/api/indexing/roots`, all 3 indexed within 5s
5. Search results: "getting started" score 2.76, "sourdough bread recipe" score 4.56, "quantum entanglement" score 3.55 — consistent with initial run
6. Started Install AI — 24 assets, 9.08 GB total. Download completed in ~12 min (~12.6 MB/s). 23/24 succeeded, FP16 reranker failed (0 bytes — G30). `citation-config.json` stuck in `pending`.
7. Restarted backend with models available. Worker discovered embedding (gte-multilingual-base via manifest fallback), SPLADE, reranker, citation-scorer. NER model files exist but no discovery log (G31).
8. ORT session creation hung on CPU for embedding (FP16 model) — after 5 min, Worker CPU time was 4.8s total, working set stable at 197 MB (G29 confirmed)
9. Confirmed G28: `nvidia-smi detection failed: CreateProcess error=2` logged every 10s, 3 attempts per cycle
10. Silent uninstall removed `C:\JustSearch` completely (1236 files). User data preserved: 11.2 GB in AppData (models, index, jobs.db, entity-clusters.db, config, logs)

**New observations from re-validation:**
- Download reports overall `state: failed` even though 23/24 assets succeeded — install UX should distinguish critical vs optional failures
- `citation-config.json` (824 bytes) stuck at `pending` state after FP16 reranker failure — download pipeline appears to abort remaining assets on first failure
- Search latency: 1-2ms after initial warm-up (TEXT mode)
- Model file count: 23 ONNX/GGUF assets installed to `AppData/Local/JustSearch/models/` with correct directory structure (`onnx/`, `splade/` subdirs)
- `/api/debug/state` shows Worker PID, port, signal bus activity — useful for headless debugging

### Additional Observations

- **Correct API endpoint:** `/api/indexing/roots` (not `/api/roots` which returns empty 200 OK — misleading)
- **Path format:** JSON body requires forward slashes (`C:/path`) — backslashes cause Jackson parse errors
- **Index pipeline in TEXT mode:** keyword matching works correctly without AI models
- **Pipeline components skipped (expected):** lambdamart (MODEL_NOT_LOADED), expansion (AI_UNAVAILABLE), cross_encoder (DISABLED), chunk_merge (NO_CHUNK_DOCS)
- **Model discovery works for:** embedding (gte-multilingual-base), SPLADE (naver-splade-v3), reranker, citation-scorer
- **Model discovery fails for:** NER (no log at all despite correct directory structure)
- **No bundled help docs:** The app does not ship pre-indexed content; library roots must be added manually via `/api/indexing/roots`
- **Download performance:** Sequential asset downloads (~12.6 MB/s sustained), GGUF model (4.68 GB) downloaded first

---

## Post-Validation Analysis

### Fixes Implemented (this session)

| Bug | Fix | File |
|-----|-----|------|
| G27 | `resolveLibDir()` and `resolveWorkerLibDir()` check bundled layout unconditionally (not gated on `isProd`) | `KnowledgeServerConfig.java` |
| G28 | `nvidiaSmiUnavailable` sticky flag — caches failure permanently, resets on `invalidateCache()` | `VramDetector.java` |
| G29 | `BASIC_OPT` for FP16 models on CPU instead of `EXTENDED_OPT` (reduces optimization time, but see tempdoc 376 for deeper fix) | `OnnxSessionCache.java` |
| G31 | No separate fix — resolves with G27 (correct model root resolution) | — |

### Remaining Issues

| # | Issue | Severity | Status |
|---|-------|----------|--------|
| G30 | FP16 reranker download 404 | Low | Needs asset upload to GitHub Releases |
| Download abort | Pipeline aborts remaining assets on first failure | Medium | Needs fix in `AiInstallService` |
| `\\?\` prefix | Tauri `resource_dir()` returns extended-length paths; `Files.isDirectory()` fails | Medium | Needs normalization in `lib.rs` |
| Empty first-run | App ships no content; search is empty on first launch | Medium | Needs bundled help docs |
| Uninstall residual | 11.2 GB in AppData after uninstall, no cleanup option | Low | Future NSIS enhancement |
| `prod=false` security | CORS/session-token disabled in distributed installer | Medium | Future — audit all `isProd` gates |
| CPU inference story | FP16 on CPU is architecturally wrong — see tempdoc 376 | High | Separate tempdoc |

### Sandbox Infrastructure

Model pre-staging via mapped folder eliminates the 8.5 GB download bottleneck.
`JUSTSEARCH_MODELS_DIR` env var set by bootstrap points JustSearch at host models.
`.optimized` cache files persist on host across sandbox sessions.

Note: pre-staged testing is more favorable than real first-run UX (dev models
include FP32 variants; `.optimized` caches carry over). Use `--models-dir none`
to test the download flow.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Sandbox-validation procedures with model pre-staging via `JUSTSEARCH_MODELS_DIR`. Procedures documented; ongoing sandbox testing is operational work, not tempdoc work.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

---

## Post-rewrite FE re-validation (2026-05-30)

The frontend has since been rewritten onto a new stack — the Lit `shell-v0`
shell (the `frontend-framework-kernel` / `frontend-rewrite-slice-decomposition`
lineage), replacing the old React/`.tsx` UI. This validation's two FE/UX-surfaced
findings were re-checked verbatim against the rewritten code to determine whether
they survived the rewrite. (The backend bugs G27/G28/G29/G31 were out of scope
for this FE re-check; they were addressed in the `app-packaging-and-distribution`
alpha series.)

### A. Install-AI critical-vs-optional failure (G30 + "Download abort") — RESOLVED

Original complaint: a single **optional** asset failure (the FP16 reranker 404)
drove the whole install to `state: failed` even though 23/24 assets succeeded,
and the pipeline aborted the remaining assets (`citation-config.json` left
`pending`). The validation asked for the install UX to distinguish critical from
optional failures. Both halves are now fixed across backend **and** the rewritten FE:

- **Backend** `AiInstallService.java`: a partial failure now ends in
  `state: "completed"` with `installedFully = false` and an
  `"AI installed (N/total packages; K failed)"` message (`:402-407`).
  `state: "failed"` fires **only** when *every* download fails
  (`failedCount == totalCount`, `:363-366`). The download loop uses
  `failPackage(...); continue;` on every failure path (download / verify / move /
  extract, `:302-352`), so it runs to completion — no asset is stranded `pending`.
- **FE** `BrainSurface.ts`: consumes `installedFully === false` and renders
  **"Installed with limitations"** (`:1099`); the hard **"Install failed"** banner
  (`:1241-1245`) now renders only on a genuine total failure.

**Residual (minor, not the original bug):** the FE's local `InstallStatus` type
(`BrainSurface.ts:26-39`) drops the canonical `assets[]` array
(`api/domains/packs.ts`), so it shows *that* an install was partial but not
*which* asset was skipped/failed, and the backend's descriptive `message` is not
surfaced. Worth a follow-up; does not re-open the original finding.

### B. Empty first-run search state — CLOSED (by the backend, not a rewrite regression)

This validation's "Empty first-run / No bundled help docs" observation
(2026-04-02) was **real at the time** but has since been closed by help-seeding
added in the `app-packaging-and-distribution` alpha series — *after* this
validation ran. The mechanism (verified 2026-05-30):

- **5 built-in help docs** live at `SSOT/docs/help/*.md` (`ai-features`,
  `getting-started`, `keyboard-shortcuts`, `search-syntax`, `troubleshooting`;
  created 2026-04-06).
- They are **staged into the production bundle**
  (`modules/shell/src-tauri/resources/headless/SSOT/docs/help/`, 2026-05-02) and
  **shipped** — `tauri.conf.json` bundles `resources/headless/**/*`.
- They are **auto-ingested on first worker connect** into the `justsearch-help`
  collection (`KnowledgeServerBootstrap.tryIngestHelpFiles`, `:211` / recovery
  hook `:231`), guarded by a `.help-ingested-version` marker and skipped only in
  eval mode.
- They are **not excluded from search** (no collection filter), so the first-run
  corpus is non-empty (`documentCount ≈ 5`) and searchable.

So the rewritten `SearchSurface.ts` empty-state message "Type to search across
all indexed files" is **accurate** on a fresh install — there are indexed files.
Recorded by-design as **UIX-015** in `docs/reference/issues/decisions.md`.

> **Process note (honest correction).** An earlier 2026-05-30 pass concluded B
> was "still present" and opened UIX-015 from a *static FE-only* read —
> `SearchSurface` has no `documentCount === 0` branch — without checking the
> backend help-seeding path. That reversed once the seeding chain was verified.
> This is the `verify-don't-guess` / `interrogate-results` trap: the FE code fact
> was true, but the conclusion drawn from it wasn't. The definitive confirmation
> is a live first-run smoke (fresh non-eval index → `documentCount ≈ 5`, search
> "getting started" returns the bundled guide), matching this doc's original
> Test #3.

