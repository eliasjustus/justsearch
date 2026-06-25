---
title: "Neglected Gap Analysis — Why Key Areas Stalled"
type: tempdoc
status: done
created: 2026-02-18
---

> NOTE: Noncanonical doc (analysis). May drift. Verify against canonical docs + code.

# 214: Neglected Gap Analysis

## Purpose

Cross-reference the open/stalled tempdocs against their own stated rationale to understand
*why* each gap exists — deliberate deferral, resource starvation, architectural blocker,
or just nobody picked it up.

## Findings by gap

### 135/196 — Search quality & reranker

**Root cause: experiment-blocked (hybrid), and a concrete path mismatch bug (reranker).**

135 is the most researched open tempdoc in the repo. Extensive external research exists
(RRF, convex combination, BEIR methodology, chunking strategy). Validation experiments
were run. But:

- The HYBRID vs TEXT experiment was **inconclusive** — the inference engine wasn't running,
  so no embeddings existed and hybrid degraded to pure BM25. Now unblocked: the self-hosted
  runner has the GPU and models, so this experiment can be re-run.
- The reranker experiment **worked** — Q3 ("three process architecture") was fixed from
  agent-guide (wrong) to system-overview (correct). But the reranker is still not running.

**2026-02-18 investigation — reranker disabled due to path mismatch, not config default:**

The description in 135 ("disabled by default, `JUSTSEARCH_RERANK_ENABLED=false`") is stale.
`RerankerConfig.fromEnv()` (commit `58f339da`) now **auto-enables** when the model is found
at a standard location. The hard `false` default is gone.

However, the reranker is still not running because of a layout mismatch:

| What | Path |
|------|------|
| Model is actually at | `%LOCALAPPDATA%\JustSearch\resources\headless\models\onnx\reranker\` |
| Discovery looks at (AI Home) | `%LOCALAPPDATA%\JustSearch\models\onnx\reranker\` |
| Discovery looks at (install dir) | `<JUSTSEARCH_HOME>\models\onnx\reranker\` (in dev: a tmp dir) |
| Dev fallback | `null` (main reranker has no dev fallback) |

The `resources\headless\` segment is where Tauri extracts sidecar resources. `OnnxModelDiscovery`
was written for a layout where models land at `<dataDir>/models/onnx/`, but the Tauri
bundler puts them at `<dataDir>/resources/headless/models/onnx/`. These paths have never
been reconciled. The same mismatch also affects the citation-scorer model (also found at
`resources\headless\models\onnx\citation-scorer\`).

No `JUSTSEARCH_RERANK_MODEL_PATH` is set anywhere in `WorkerSpawner`, `dev-runner.cjs`,
or any launch script. `RuntimeActivationService.resolveOneOnnxFeature()` has the same lookup
chain and will report reranker as `inactive / not_found`.

**2026-02-18 fix applied**: Added step 2.5 to `OnnxModelDiscovery.resolve()` and step 3.5 to
`RuntimeActivationService.resolveOneOnnxFeature()`. Both now check
`<justsearch.repo.root>/models/onnx/<modelName>/` (where `lib.rs` sets `justsearch.repo.root`
to `headless_dir`). Source tag `"sidecar"` in the status response. `OnnxModelDiscoveryTest`
updated from bug-confirmation to positive assertion. All tests pass.

196 is a near-duplicate of 135's reranker section with no additional research. Closed as
redundant.

**Blocker type**: ~~concrete code bug (path mismatch)~~ **FIXED** — 14 lines across 2 files.

---

### 192 — Security model

**Root cause: rational, explicit deferral — no external users.**

The tempdoc is purely a problem statement with no research done. `modules/app-secrets`
has zero feature commits. But tempdoc 134 explicitly listed "Distribution / release process"
and related concerns as *not needing focus* because there are no external users yet.

The loopback-only binding (`127.0.0.1`) is the entire security model and it covers the
actual attack surface for a local-first app with no external users. There is no secret
management to do — the app has no API keys, no cloud credentials, no multi-user auth.

**Blocker type**: correctly deferred. Becomes relevant when distribution starts (tempdoc 202).

---

### 194 — Pipeline engine dead code removal

**Root cause: just decided, not yet started.**

This is not neglect — the investigation was comprehensive and the verdict (delete the engine,
retain only PipelineDefinition + PipelineLoader + PipelineValidator) was reached on
2026-02-17, literally one day before this analysis. The action items are detailed:
1. Write superseding ADR
2. Extract the three retained types to a thin module
3. Delete pipeline-engine and pipeline-executor modules

**Blocker type**: none. Work is queued, not blocked.

---

### 160 — CI automation

**Root cause: resolved — self-hosted runner + redesigned CI.**

Tempdoc 160 was written assuming github-hosted runners (`windows-latest`) with a
runner-minutes budget constraint. The actual ci.yml is substantially ahead of 160's analysis:

**Already resolved (vs 160's issue list):**

| 160 Issue | Status |
|-----------|--------|
| #1 Gate skipping | Fixed — all gate steps in both jobs have `if: ${{ !cancelled() }}` |
| #5 Timeout margin | Fixed — `full_build` is 35 min, `fast_build` is 20 min |
| #6 Path-filtered push trigger | Done — both `push` and `pull_request` triggers with path filters |
| #6 Runner minutes conservation | Moot — self-hosted runner has no per-minute cost |
| #2 Parallel jobs (skip) | Correct — the two-job design achieves the goal differently |

**What the CI actually looks like now (not in 160):**

Two-tier design, both on `[self-hosted, Windows, X64, justsearch-perf]`:

- `fast_build` (push/PR trigger, 20 min): Uses `dorny/paths-filter` + `resolve-affected-modules.mjs`
  to detect changed modules and only build those. Much faster than full build.
- `full_build` (workflow_dispatch only, 35 min): Full `./gradlew check` with all gates.

The affected-module fast build is a design 160 didn't anticipate — it gives per-commit
feedback without running the full 20-minute Gradle check.

**What a self-hosted runner specifically unlocks (vs 160's model):**

1. **Push/PR triggers are cost-free**: No runner-minutes to conserve. 160's entire Issue 6
   rationale (why CI was manual-only) is gone.
2. **Persistent Gradle cache on-disk**: No need to upload/download cache artifacts over the
   network between runs. The build tool cache, ONNX models, and npm modules persist between
   runs, which is why `fast_build` fits in 20 minutes.
3. **System-level integration tests (Issue 3) are now feasible**: The dev-runner
   infrastructure exists on the same machine. `agent-live-eval-nightly.yml` already does
   this — it runs `run-agent-live-battery-win.ps1` on a cron schedule, which starts the
   full stack and runs live agent eval scenarios.
4. **GPU access**: The `justsearch-perf` label implies this is a capable machine with a GPU.
   Benchmark workflows and the nightly agent eval already use this. CI could test ONNX/CUDA
   paths that github-hosted runners can't.

**Still relevant from 160:**

- **Issue 4 (Spotless/PMD exclusions)**: Still present. Windows checksums.lock + Spotless
  config-cache bug and pmdIntegrationTest classpath serialization are unchanged by runner type.
  The workaround exclusions remain in ci.yml.
- **Composite action**: Both jobs still duplicate 4 setup steps (checkout, Java, Node, npm ci).
  A composite action would still reduce boilerplate.

**New considerations 160 didn't anticipate:**

- **PC availability dependency**: CI only runs when the machine is on. This is fine for
  personal use but queues will stall if the PC is off or sleeping during a push.
- **Workspace accumulation**: Self-hosted runners share a persistent workspace. `tmp/`
  directories (agent evidence, test outputs, nightly artifacts) accumulate between runs.
  The nightly eval already writes to `tmp/agent-evidence/_summaries/`. Needs periodic cleanup.
- **Dev/CI resource contention**: A full Gradle build on the self-hosted PC competes with
  local development for CPU and RAM. The `fast_build` affected-module design reduces this
  for routine pushes.

**Blocker type**: resolved. 160 is largely done as a result of the workaround.

---

### 200 — Accessibility

**Root cause: no external users → no pressure; requires human/hardware testing.**

The tempdoc is a thin problem statement with no research or implementation. The 134
"cannot do" confidence table for accessibility-adjacent issues noted that screen reader
testing and user research "requires actual users." Agents cannot run assistive technology
to validate ARIA correctness.

Additionally, accessibility tends to be deferred until near-release in most software
projects. With no external users, there's no incoming accessibility feedback to motivate it.

**Blocker type**: partially hardware/human-blocked (screen reader validation), mostly
priority-deferred pending distribution.

---

### 139 — Feature gaps vs. alternatives

**Root cause: explicitly agent-blocked — requires product decisions and external tooling.**

The tempdoc's own confidence table says:
- "Cannot do" — Search quality comparison vs. competitors (requires running competitor tools + judging relevance)
- "Cannot do" — User research / validation (requires actual users)
- "Cannot do" — Strategic product decisions Q5-Q12 (primary wedge: code Q&A vs. document Q&A?)

Q5-Q12 are open strategic questions that require the owner to decide the product direction.
No agent can answer "which competitor causes most churn" or "is the primary hook privacy
or speed."

**Blocker type**: genuinely human-blocked. Not a resource issue.

---

### 204 — Configuration UX

**Root cause: no external users → de-prioritized per 134.**

134 explicitly listed configuration improvements as out of scope until there are external
users to give feedback on what's confusing. The SSOT configuration system was built for
the backend; the Settings UI was updated for descriptions but not for UX.

The tempdoc has no research done. It's a parking lot for future UX work once the user
feedback loop exists.

**Blocker type**: rationally deferred.

---

### 202 — Installer & distribution maturity

**Root cause: most explicit deferral in the codebase.**

134 lists "Distribution / release process", "Auto-update mechanism", "Code signing", and
"First-run UX" as *areas NOT needing focus right now — no external users yet*. This is
the most correctly deferred gap in the analysis. Investing in installer maturity before
having users to distribute to would be premature.

**Blocker type**: rationally deferred. Becomes the focus once dogfooding graduates to
external users.

---

### 197 — Agent system expansion

**Root cause: stale tempdoc, work is tracked elsewhere.**

The tempdoc says "5 file touches total" but that's stale. Significant agent work has
happened in the period since 197 was written:
- Tempdoc 208 (AI model tuning): Phase 1 reliability, R1 compression v2, trace identity,
  checkpoint schema versioning
- Tempdoc 186 (LLM agentic file operations): active, covers tool execution specifically
- Tempdoc 187 (production MCP server): implementation-complete
- Tempdoc 213 (agent search context quality): in-progress today

197 was created as a parking lot and newer, more specific tempdocs absorbed the work.
197 itself was never updated to reflect completed work.

**Blocker type**: not actually neglected. Tempdoc is stale.

---

### 183 — Plugin architecture

**Root cause: Phase 1 is complete; remaining phases correctly deferred.**

Phase 1a (ContentExtractor SPI) and 1b (EmbeddingProvider SPI) are both implemented and
reviewed. Phase 1c (InferenceBackend SPI) was analyzed and deliberately decided as
"won't-do" — the OpenAI-compatible HTTP protocol already provides the needed flexibility.

Phases 2-4 (manifest admission, dynamic loading, sandboxing) are deferred pending
open-source release, which is the correct gate — you don't build a plugin security model
before you have external plugins to secure.

**Blocker type**: not neglected. Complete at the right scope.

---

### 137 — Edge case resilience

**Root cause: mostly complete; remaining gaps consciously deprioritized.**

The tempdoc's "DONE" markers show gaps 1 (OneDrive placeholders), 2 (file walk resilience),
4 (OOM protection), and 6 (MMap unmap hack) were all implemented on 2026-02-07.

Remaining gaps:
- Gap 3 (exclude patterns during walking): Deprioritized — biggest dirs are already in
  the hardcoded skip list; remaining user-configured excludes are small
- Gap 5 (junction deduplication): Deprioritized — default Windows junctions are ACL-blocked
  so `visitFileFailed` already handles them
- Gap 7 (unindexable file feedback): Deprioritized — user research shows users don't
  notice until they search for something specific

**Blocker type**: not neglected. Active work done; remaining items have documented rationale.

---

### 138 — Performance on real workloads

**Root cause: hardware-blocked — the measurements require the full stack.**

The "agent confidence" section explicitly marks most gaps as "Cannot do":
- "Cannot do — Requires launching actual Tauri app" (desktop shell startup timing)
- "Cannot do — Requires GPU hardware + real models" (CUDA/model loading time)
- "Cannot do — Requires JVM NMT or OS-level profiling during execution" (ONNX native memory)
- "Cannot do — Requires full stack with real models" (embedding cache warm vs cold)

The gaps that ARE agent-doable (memory regression tracking, real file type benchmarks) have
"Low" confidence because they require representative hardware and are hard to set meaningful
thresholds for.

**Blocker type**: genuinely hardware-blocked. Not a resource issue.

---

## Summary

| Gap | Why stalled | Effect of self-hosted runner | Actionable? |
|-----|------------|------------------------------|-------------|
| 135/196 Search quality | Experiments need inference engine; reranker decision | **Unblocked** — GPU + models on machine; hybrid eval now feasible in CI | Yes — hybrid experiment, reranker default |
| 192 Security | Correctly deferred — no external users | No change | No — wait for distribution |
| 194 Pipeline removal | Just decided 2026-02-17 | No change | Yes — pure execution |
| 160 CI automation | **Closed 2026-02-18** | Was the root cause; now resolved | Done |
| 200 Accessibility | No users → no pressure; screen reader needs human | Partial — automated axe-core/pa11y checks now CI-feasible | Partial — static ARIA analysis doable |
| 139 Feature gaps | Genuinely human-blocked (product decisions + users) | No change | No — wait for product direction |
| 204 Configuration UX | Correctly deferred — no external users | No change | No — wait for user feedback |
| 202 Installer | Most correct deferral — explicitly out of scope | **Partial shift** — real installer testing now CI-feasible on same hardware | Still deferred, but lower barrier |
| 197 Agent expansion | Stale tempdoc — work in 208/186/187/213 | No change | Close the tempdoc |
| 183 Plugin architecture | Phase 1 complete, rest correctly deferred | No change | No — follow the release gate |
| 137 Edge case resilience | Mostly complete — remaining gaps deprioritized | No change | Close/update status |
| 138 Performance | Hardware-blocked — requires full stack + GPU | **Substantially unblocked** — GPU, real models, dev-runner all on same machine | Several "Cannot do" items become feasible |

## Effect of the self-hosted runner on each gap

### Unblocked: 135/196 — Search quality

Previously: "Hybrid experiment inconclusive — inference engine wasn't running."

Now: The self-hosted machine has the GPU and local models. The inference engine can be
started as part of a CI workflow (the nightly eval already does this). This means:

- **Hybrid vs TEXT experiment** can be re-run with real embeddings indexed — the "inconclusive"
  result was a setup failure, not a methodology problem.
- **BEIR evaluation** (`scripts/search/beir-eval-win.ps1`) can be wired into a CI workflow.
  It downloads datasets, indexes them, and computes Recall@K. On the self-hosted runner,
  this is feasible as a periodic (not per-commit) workflow alongside the other benchmark
  workflows (`claim-a-report-win.yml`, `perf-calibration-win.yml`, etc.).
- **Reranker as default**: The data already supports it (Q3 fixed in Feb 2026 experiment).
  The remaining blocker is just the config default and a golden corpus test that enables it.

### Substantially unblocked: 138 — Performance on real workloads

Previously: most items marked "Cannot do — requires GPU / full stack / real models."

Now: The self-hosted runner is the full-stack machine. Several items flip:

| Item | Was | Now |
|------|-----|-----|
| Model loading time (CUDA driver + embedding) | Cannot do | Feasible — models already on disk, runner has GPU |
| ONNX native memory tracking | Cannot do | Feasible — JVM NMT or Windows Task Manager data accessible |
| Embedding cache warm vs cold | Cannot do | Feasible — dev-runner on same machine, same pattern as nightly eval |
| 50K+ file scale test | Cannot do | Feasible — full corpus can be indexed on the machine |
| Incremental re-indexing benchmark | Low | Feasible — same as scale test |

The one item that remains genuinely blocked: **Tauri desktop shell startup timing** — CI
jobs don't have a display, so Tauri can't render. Still requires manual measurement.

### Partially unlocked: 200 — Accessibility

Previously: "Agents cannot run assistive technology."

Now: Automated accessibility tooling (axe-core, Playwright's `checkAccessibility()`,
pa11y) can run in CI without a screen reader or human. These catch:
- Missing ARIA roles and labels
- Tab order issues detectable by DOM traversal
- Contrast violations (already done once, not regression-tracked)
- Form/button labeling gaps

What still requires a human: actual screen reader UX testing (NVDA, JAWS), cognitive
load assessment, real user feedback. But the static analysis layer is agent-doable and
now CI-feasible on the self-hosted runner with a headless browser.

### Barrier lowered: 202 — Installer & distribution

Previously: out of scope (no external users). Still correctly deferred on principle.

But: the self-hosted runner changes the cost of *experimenting* with installer features.
Code signing, auto-update configuration, and uninstaller cleanup can all be tested in CI
on the actual target hardware without touching distribution infrastructure. The barrier to
doing the work is lower even if the gate (external users) hasn't been crossed.

### No change: 192, 139, 204, 183

Security, feature competitive analysis, configuration UX, and plugin phases 2-4 are
deferred on principle (no external users / requires product decisions). A faster CI runner
doesn't change those gates.

## Themes (revised)

**Execution gaps with no remaining blocker:**
1. **Pipeline engine deletion (194)**: Verdict reached, action items listed. Pure execution.
2. **Reranker path mismatch (135)**: The "disabled by default" framing was stale.
   `RerankerConfig.fromEnv()` now auto-enables on model discovery. The model IS on the
   machine at `resources\headless\models\onnx\reranker\` but `OnnxModelDiscovery` looks
   for `<dataDir>\models\onnx\reranker\` — a path mismatch introduced by Tauri's sidecar
   extraction layout. The reranker silently stays off on every run. Fix requires either
   adding `resources/headless/models/onnx/` as a discovery candidate or setting
   `JUSTSEARCH_RERANK_MODEL_PATH` in the launcher. Separate from the evaluation gap
   (BEIR, A/B) — the discovery fix is a 1-file code change.

**Now unblocked by self-hosted runner (previously hardware-blocked):**
3. **Hybrid search evaluation (135)**: Re-run the HYBRID vs TEXT experiment with the
   inference engine actually running. Can be a periodic CI workflow.
4. **Performance at scale (138)**: Model load times, ONNX memory, 50K+ file benchmarks —
   now feasible on the self-hosted machine.
5. **BEIR evaluation in CI (135)**: Wire the existing script into a periodic workflow.

**Partially unlocked:**
6. **Automated accessibility CI (200)**: axe-core / Playwright checks are now CI-feasible.

**Correctly deferred (unchanged by runner):**
- Security model (192), distribution (202), configuration UX (204), feature direction (139)

**Stale tempdocs to close/update:**
- 197 (agent expansion) — work happened, just in newer tempdocs
- 137 (edge case resilience) — mostly complete, update status to reflect
- 138 (performance) — many items now feasible; revise "Cannot do" labels

---

## Investigation: Deep Dive Results (2026-02-18)

Seven investigation directions explored via code reads. Findings below.

---

### I1 — Citation-scorer path mismatch

**Finding: CONFIRMED — same root cause as reranker, and no dev fallback at all.**

`CitationScorerConfig.fromEnv()` (`CitationScorerConfig.java:63`) calls
`OnnxModelDiscovery.resolve(modelPathStr, "citation-scorer", null)` with `devSubdir = null`.
The resolution chain:

1. Explicit `JUSTSEARCH_CITATION_SCORER_MODEL_PATH` — not set anywhere
2. AI Home: `<dataDir>/models/onnx/citation-scorer/` — **wrong path** (resources\headless\ prefix)
3. Install dir: `<JUSTSEARCH_HOME>/models/onnx/citation-scorer/` — dev: tmp dir, empty
4. Dev fallback: **skipped** — devSubdir is null, no fallback exists at all

Physical location on this machine:
```
%LOCALAPPDATA%\JustSearch\resources\headless\models\onnx\citation-scorer\model.onnx
```

The citation scorer has been effectively disabled since it shipped — not by config
default but because the discovery path has never matched the Tauri sidecar extraction
path. Citation scoring has zero path resolution fallback, making it impossible to enable
without an explicit env var. (Note: the main reranker also passes `devSubdir=null` —
see I8 correction below.)

`CitationScorerConfig.isReady()` returns `enabled && modelPath != null` — always false.

**Fix**: Same as reranker. Either add `resources/headless/models/onnx/` to
`OnnxModelDiscovery.resolve()` (Option B), or set `JUSTSEARCH_CITATION_SCORER_MODEL_PATH`
explicitly in the launcher (Option A).

---

### I2 — JUSTSEARCH_MODELS_DIR usage

**Finding: Completely separate concern — serves Brain/LLM models, NOT ONNX.**

`JUSTSEARCH_MODELS_DIR` is consumed exclusively by `InferenceConfig.fromEnvironment()`
(`InferenceConfig.java:73`) via `EnvRegistry.MODELS_DIR.getString("models")`. It resolves
llama-server model file paths: VLM gguf, mmproj gguf, embedding gguf for the Brain process.

`OnnxModelDiscovery` does **not** read this env var. It uses:
- `PlatformPaths.resolveDataDir()` (→ `%LOCALAPPDATA%\JustSearch`) for the AI Home path
- `System.getenv("JUSTSEARCH_HOME")` / `user.dir` for the install/dev fallback path

The dev-runner setting `JUSTSEARCH_MODELS_DIR=D:\code\JustSearch\models` (when that dir
exists) has no effect on ONNX model discovery. This is correct behavior — the two env
vars serve different systems.

**Consequence for the path mismatch fix**: There is no existing env var that redirects
ONNX discovery without also changing `JUSTSEARCH_HOME` or `JUSTSEARCH_DATA_DIR`. See I8
for the precise fix specification (Option B via `justsearch.repo.root`).

---

### I3 — Nightly agent eval ONNX access

**Finding: All Phase B nightly evals run with reranker and citation-scorer DISABLED.**

`dev-runner.cjs` sets:
```
JUSTSEARCH_HOME: <tmp data dir>
JUSTSEARCH_DATA_DIR: <tmp data dir>
```

`OnnxModelDiscovery.resolve()` step 2 checks `<tmpDir>/models/onnx/<name>/` — doesn't
exist. Step 3 checks `<JUSTSEARCH_HOME>/models/onnx/<name>/` — same tmp dir, doesn't
exist. No explicit `JUSTSEARCH_RERANK_MODEL_PATH` or `JUSTSEARCH_CITATION_SCORER_MODEL_PATH`
is set in dev-runner, the nightly workflow, or any launch script.

**Consequence for the Phase B scorecard**: The current 81.25% soft-gate threshold was
calibrated against a system with no reranking. If/when the ONNX path mismatch is fixed:
- Reranking activates in all nightly eval runs
- Search result ordering changes (Experiment 2 showed Q3 and Q6 both corrected)
- Some scenarios may pass more easily (correct results retrieved), others may flake on
  the reranker's 150ms deadline (CPU execution was measured at 280-393ms — deadline is
  advisory but adds latency)
- The soft-gate threshold (81.25%) may need re-calibration after a new 14-run window

**Also implies**: The A/B scoring from tempdoc 208 and 213 was measured without
reranking. If quality comparisons assumed the "full system," the baseline is understated.

---

### I4 — Tempdoc 213 baseline run status

**Finding: Blocked on TWO prerequisites — Approach A not implemented, and no baseline run.**

Tempdoc 213 status: "eval extension implemented, awaiting baseline run."

Code investigation reveals:
1. **Approach A was NOT implemented**: `SearchTool.java` still truncates excerpts at 200
   chars and uses only `excerptRegions[0]`. `AgentLoopService.MAX_TOOL_RESULT_CHARS`
   is still 900. No merged code changes found matching Approach A's description.

2. **Eval extension (Path 2) was written**: `buildAgentStyleContext()` was added to
   `RagQualityEvalTest.java` (~100-200 lines, per tempdoc 213 §§ "Path 2 feasibility").
   But since Approach A was not applied to production code, running the extended eval
   would only compare the current (broken) formatter against itself — producing no useful
   diff.

**Correct unblocking sequence:**
1. Implement Approach A in `SearchTool.java` and `AgentLoopService.java`:
   - Per-excerpt limit: 200 → 800-1200 chars
   - Use all `excerptRegions` (not just `[0]`)
   - Add `content_preview` fallback when excerpts are empty (vector search case)
   - Reduce default result count: 5 → 3
   - Increase `MAX_TOOL_RESULT_CHARS`: 900 → 4000-6000
2. Run `RagQualityEvalTest` with the extended variant **before and after** merging
3. Verify all 7 metrics improve (or are neutral) with the new format
4. Promote the new baseline via `promote-rag-eval-baseline-win.ps1`

The eval extension is ready and waiting. Approach A implementation is the actual blocker.

---

### I5 — ChunkRerankerConfig dev fallback path

**Finding: Same root cause as I1/I3. Dev fallback resolves inside the tmp dir.**

`RerankerConfig.ChunkRerankerConfig.fromEnv()` uses `OnnxModelDiscovery` with devSubdir
`"reranker/ms-marco-MiniLM-L6-v2"`. With `JUSTSEARCH_HOME` set to the tmp data dir in dev:

- Step 3 (install dir): `<tmpDir>/models/onnx/reranker/` — doesn't exist
- Step 4 (dev fallback): `<tmpDir>/models/reranker/ms-marco-MiniLM-L6-v2/` — doesn't exist

Discovery returns null. `ChunkRerankerConfig` defaults `enabledStr = null`, so auto-enable
fires only when `discovery != null && discovery.autoDiscovered()` — which is never. Chunk
reranking requires explicit `JUSTSEARCH_RERANK_CHUNKS_ENABLED=true` AND an explicit model
path env var to activate in any environment.

**All three ONNX features (reranker, citation-scorer, chunk-reranker) share the same bug.**
Option B fix (`OnnxModelDiscovery` path addition) would fix all three in one change.
Option A fix requires three separate env var additions to the launcher.

---

### I6 — Pipeline engine deletion readiness

**Finding: Action plan is fully specified in tempdoc 194. Ready to execute.**

Tempdoc 194 (status: `investigation-complete`, verdict: delete) documents a 5-step plan:

1. Write a superseding ADR for `SSOT/ADRs/0006-pipeline-engine-phase4a.md`
2. Extract `PipelineDefinition`, `PipelineLoader`, `PipelineValidator` into a thin
   `pipeline-schema` module (~500 LOC)
3. Delete `modules/pipeline-engine` and `modules/pipeline-executor` (~6,600 LOC of
   execution engine: `DefaultPipelineEngine`, `Envelope`, `Stage`, `StageContext`,
   `StagePlugin*`, both executors)
4. Replace `Launcher.simulate` with lightweight structural validation (load SSOT JSON
   → run `PipelineValidator` → verify `dag_hash` → emit fitness report)
5. Update `settings.gradle` and root `build.gradle.kts` to remove the two deleted modules

`deprecated-modules.md` already blocks new work on these modules. The `SearchHitMetadata`
type leak was fixed 2026-02-17. No live search or indexing path references the engine.

**Risk**: Low — removing ~6,600 LOC of unused code with full test coverage (14 test files,
~3,050 LOC) that builds but never runs in production. The retained types (~500 LOC) are
tested independently. The simulation CI gate becomes a structural validator, not an
execution gate — a trade-off acknowledged and accepted in tempdoc 194.

**Next step**: Open a new implementation tempdoc to track execution. Close 194 as
`investigation-complete → implementation-pending`.

---

### I7 — Accessibility ARIA static analysis baseline

**Finding: No accessibility test infrastructure exists. Complete gap.**

Search for `accessibility|checkAccessibility|axe|aria` across `modules/ui-web/**/*.ts,tsx`
returned 10 **production component files** with `aria-label` attributes — zero test files.

No packages found:
- `@axe-core/react` — not in `package.json`
- `@playwright/test` with accessibility plugin — not present
- `pa11y`, `WAVE`, or any a11y CI tool — not present

The 16 existing test files in `modules/ui-web/src/**/*.test.{ts,tsx}` test hooks,
schemas, and utility functions. None assert accessibility properties.

**Top 3 flows with highest a11y surface area:**
1. **Search results** — result list keyboard navigation, ARIA live region for result count
   changes, focus management on result selection
2. **File drag/drop / empty state** — `EmptyState.tsx` drop target labeling,
   `useDragDrop.ts` drag state communicated via ARIA attributes
3. **RAG/Agent answer panel** — `InspectionPane.tsx` and `InspectorAnswer.tsx` with dynamic
   content, loading spinners, progress states — most complex a11y surface

**What's achievable with CI:**
- `@axe-core/react` in-test assertions via `vitest` (already the test runner) — catches
  missing roles, missing labels, invalid ARIA hierarchies at unit test level
- Playwright `checkAccessibility()` for integration-level checks of rendered flows
  (requires the dev stack but the self-hosted runner has it)
- The self-hosted runner enables both approaches in CI

**Not achievable with CI:** Screen reader UX (NVDA, JAWS), cognitive load, real user
feedback on tab order confusion. These require humans.

**Recommendation**: Open a new tempdoc scoping axe-core integration (vitest layer) for
the top 3 flows. The implementation is not large — `@axe-core/react` + 3 test files.

---

## Summary table update (2026-02-18 deep dive)

| Item | Finding | Priority |
|------|---------|----------|
| I1 Citation-scorer path mismatch | Same root cause as reranker. `devSubdir=null` means no fallback at all. All 3 ONNX features affected. | High |
| I2 JUSTSEARCH_MODELS_DIR | Separate concern (Brain/LLM models). Option B (OnnxModelDiscovery fix) covers all 3 features in one change. | — (context) |
| I3 Nightly evals without ONNX | All Phase B scorecard data was collected without reranking. Gate threshold (81.25%) needs re-calibration after fix. | Medium |
| I4 Tempdoc 213 blocking | Approach A not implemented. Eval extension ready but waiting. Fix SearchTool + AgentLoopService first. | High |
| I5 ChunkRerankerConfig fallback | Same root cause. Option B fixes all 3 in one place. | High (bundled with I1) |
| I6 Pipeline engine deletion | **Done** — pipeline-engine and pipeline-executor deleted; pipeline-schema created; ADR 0007 written. Follow-on: tempdoc 221 (pipeline-schema simplification). | Medium |
| I7 Accessibility gap | Zero test infrastructure. Top 3 flows identified. axe-core integration is modest scope. | Low-Medium |
| I8 ONNX fix specification | Two files need a new sidecar step. `justsearch.repo.root` already set by lib.rs. No Rust changes needed. | High |

---

## Investigation: ONNX Path Mismatch — Precise Fix Specification (2026-02-18)

Follow-up deep dive into lib.rs, PlatformPaths.java, RerankerConfig.java,
CitationScorerConfig.java, RuntimeActivationService.java, and dev-runner.cjs.
Corrects two errors in the Round 1 analysis and specifies the exact fix.

---

### I8 — ONNX path mismatch: precise fix specification

#### Correction: main reranker has no dev fallback either

Round 1 stated "unlike the reranker (which has a dev fallback)". This is wrong.
`RerankerConfig.fromEnv()` (line 63) calls:

```java
OnnxModelDiscovery.resolve(modelPathStr, "reranker", null)
```

`devSubdir = null` for the main reranker too. All three features have the same story:

| Config | `modelName` | `devSubdir` | Auto-enable logic |
|--------|-------------|-------------|-------------------|
| `RerankerConfig` | `"reranker"` | `null` | `discovery != null && discovery.autoDiscovered()` |
| `CitationScorerConfig` | `"citation-scorer"` | `null` | same |
| `ChunkRerankerConfig` | `"reranker"` | `"reranker/ms-marco-MiniLM-L6-v2"` | same |

ChunkReranker is the only one with a dev fallback; even it fails because the dev-runner
points `JUSTSEARCH_HOME` at a temp dir where no model exists.

#### Correction: Option B path description

Round 1 described Option B as "Add `<dataDir>/resources/headless/models/onnx/<name>/`".
That was an approximation. The actual correct formulation uses the `justsearch.repo.root`
system property, which lib.rs already sets to `headless_dir` at startup (line 553).

#### Path layout (confirmed from lib.rs + tauri.conf.json)

`tauri.conf.json` bundles resources as `"resources/headless/**/*"`. At runtime:

```
lib.rs spawn_headless_backend():
  app_data_dir  =  Tauri app_data_dir()          e.g. %LOCALAPPDATA%\JustSearch\
  headless_dir  =  resolve_headless_dir()         e.g. %LOCALAPPDATA%\JustSearch\resources\headless\

Java process receives:
  -Djustsearch.data.dir=<app_data_dir>            (line 551)
  -Djustsearch.repo.root=<headless_dir>           (line 553)
  JUSTSEARCH_HOME=<app_data_dir>                  (line 562)
```

ONNX models live at `<headless_dir>/models/onnx/<name>/`.
Both discovery implementations check `<app_data_dir>/models/onnx/<name>/` — a path that
only holds user-downloaded BYO models, never the bundled sidecar models.

In dev mode (dev-runner.cjs), only `JUSTSEARCH_DATA_DIR` and `JUSTSEARCH_HOME` are set
(both to a temp dir). `justsearch.repo.root` is **not** set by dev-runner.

#### Two parallel implementations, same missing step

`RuntimeActivationService.resolveBundledRuntimeBinDirBestEffort()` (line 934) already
uses `justsearch.repo.root` to locate the Java runtime binary — identical lookup pattern.
The same approach works for ONNX model discovery.

**`OnnxModelDiscovery.resolve()`** — insert between steps 2 and 3:

```java
// Step 2.5: Sidecar (bundled resources): <justsearch.repo.root>/models/onnx/<modelName>/
//   The Tauri launcher sets -Djustsearch.repo.root to headless_dir (lib.rs:553).
//   Absent in dev mode (dev-runner does not set this property) → falls through to step 3.
String repoRoot = System.getProperty("justsearch.repo.root");
if (repoRoot != null && !repoRoot.isBlank()) {
    Path sidecarPath = Path.of(repoRoot).resolve("models").resolve("onnx").resolve(modelName);
    if (isCompleteModelDir(sidecarPath)) {
        log.debug("ONNX model '{}': found at sidecar path {}", modelName, sidecarPath);
        return new Result(sidecarPath, true);   // autoDiscovered=true → triggers auto-enable
    }
}
```

**`RuntimeActivationService.resolveOneOnnxFeature()`** — insert between steps 3 and 4:

```java
// Step 3.5: Sidecar (bundled resources): <justsearch.repo.root>/models/onnx/<modelName>/
String repoRoot = System.getProperty("justsearch.repo.root");
if (repoRoot != null && !repoRoot.isBlank()) {
    Path sidecarPath = Path.of(repoRoot).resolve("models").resolve("onnx").resolve(modelName);
    if (isCompleteModelDir(sidecarPath)) {
        return new AiRuntimeStatusResponse.OnnxFeatureStatus(
            id, label, "active", "auto_discovered", sidecarPath.toString());
    }
}
```

#### Why `autoDiscovered=true` is correct

All three config classes auto-enable only when `discovery.autoDiscovered()` is true:
```java
enabled = discovery != null && discovery.autoDiscovered();
```
The sidecar path is a first-class installation location — models placed there by the
installer should auto-activate, exactly like AI Home or install-dir discoveries. Returning
`autoDiscovered=true` is correct and necessary for the feature to switch on without any
env var intervention.

If `autoDiscovered=false` were returned instead (as for explicit paths and dev fallbacks),
users would need to also set `JUSTSEARCH_RERANK_ENABLED=true` — defeating the purpose.

#### Side-effects to be aware of

1. **Nightly eval gate recalibration needed**: The 81.25% Phase B soft-gate was calibrated
   without reranking. After the fix, reranking activates in all dev-runner-based runs that
   have models present. The nightly eval runs against a temp dir with no sidecar, so the
   nightly gate is unaffected — but any dev-runner run on the dev machine will now have
   reranking active, which changes search result ordering. The 14-run rolling window will
   need to be observed after the fix ships.

2. **UI status display also fixed**: `RuntimeActivationService.resolveOnnxFeatures()` now
   reports `active/auto_discovered` with the actual sidecar path shown in the Brain panel,
   replacing the current `inactive/not_found`.

3. **Reranker deadline advisory**: At CPU-only execution (280-393ms measured), the 200ms
   deadline is exceeded. The deadline is advisory (search result is returned regardless),
   so this is a latency concern, not a correctness one. Consider bumping
   `JUSTSEARCH_RERANK_DEADLINE_MS` to 500ms as part of the same PR.

#### Files to change

| File | Change |
|------|--------|
| `modules/reranker/src/main/java/io/justsearch/reranker/OnnxModelDiscovery.java` | Add step 2.5 (9 lines); update Javadoc |
| `modules/ui/src/main/java/io/justsearch/ui/ai/runtime/RuntimeActivationService.java` | Add step 3.5 (8 lines) |
| `modules/reranker/src/main/java/io/justsearch/reranker/RerankerConfig.java` | Update Javadoc to document sidecar step |
| `modules/reranker/src/main/java/io/justsearch/reranker/CitationScorerConfig.java` | Update Javadoc to document sidecar step |

No Rust / lib.rs changes. No env var additions. No model copying. Total: ~20 lines of new Java.

#### Verification (2026-02-18)

**Filesystem evidence (conclusive):**

Glob of the repo confirms models exist at the Tauri sidecar source path:
```
modules/shell/src-tauri/resources/headless/models/onnx/reranker/model.onnx
modules/shell/src-tauri/resources/headless/models/onnx/reranker/tokenizer.json
modules/shell/src-tauri/resources/headless/models/onnx/citation-scorer/model.onnx
modules/shell/src-tauri/resources/headless/models/onnx/citation-scorer/tokenizer.json
```

Also present in both debug and release Tauri build output directories (`target/x86_64-pc-windows-msvc/debug|release/resources/headless/models/onnx/`).

`OnnxModelDiscovery.resolve()` has no step that checks `<anything>/resources/headless/models/onnx/`
or reads `justsearch.repo.root`. The gap is a code fact, not speculation.

**Unit test verification (added to `OnnxModelDiscoveryTest.java`):**

A failing test was added that:
1. Creates `<tmpDir>/models/onnx/reranker/{model.onnx,tokenizer.json}` (the sidecar layout)
2. Sets `System.setProperty("justsearch.repo.root", tmpDir)`
3. Calls `OnnxModelDiscovery.resolve(null, "reranker", null)`
4. Asserts result is **null** — proving the sidecar path is not searched

Test runs and fails as expected, confirming the bug. The test will be updated to assert
non-null after the fix lands.

---

## Investigation: Round 2 Results (2026-02-18)

Seven additional investigation areas (J1–J7) explored via targeted code reads.

---

### J1 — ONNX feature status user-facing representation

**Finding: UI shows honest-but-opaque status; no actionable path guidance.**

ONNX feature status is NOT in `SettingsView.tsx` (user preferences only). It is shown in
`BrainRuntimeSection.tsx` (Brain panel, Advanced mode only) under a collapsible
"Search Quality Features" section.

Current rendering (`BrainRuntimeSection.tsx:343–366`):
- Inactive + `reason=not_found` → amber dot + "Inactive" + "Model not found at standard locations"
- Inactive + `reason=disabled` → amber dot + "Inactive" + "Explicitly disabled via environment variable"
- Active → green dot + "Active" + actual model path (`f.modelPath`) in monospace

The `0/2 active` count in the summary accurately reflects the current state (both reranker and
citation-scorer disabled due to the ONNX path mismatch).

**UX gap**: "Model not found at standard locations" is honest but unhelpful. The user has no
way to know:
1. What the "standard locations" are
2. That the model IS installed at `resources\headless\models\onnx\` (which is not searched)
3. What env var to set to override the discovery path

This gap will persist **even after fixing the ONNX path bug** for users who manually install
models at non-standard paths. No tooltip, link to docs, or "expected at: X" guidance exists.

The panel is Advanced-mode-only, which limits blast radius (power users only), but those are
exactly the users most likely to want to configure model paths.

---

### J2 — Error code architecture Wave 1 scope

**Finding: Wave 1 is complete and comprehensive. LT-1 and LT-3 from tempdoc 201 remain open.**

`ApiErrorCode.java` is a well-structured unified registry:
- ~87 codes across 10 categories: Index (7), Search/general (12), AI availability (4),
  AI runtime (9), AI install (15), AI pack (12), Summary/streaming (12), Policy (10),
  Agent SSE (2), Worker/infra (4)
- Each code carries `ErrorClass` (TRANSIENT / PERMANENT / POLICY / VALIDATION)
- `ErrorClass` enables frontend retry logic + telemetry to act without hardcoded lists
- `ApiErrorCodeContractTest` enforces that every code has a matching entry in `errorMessages.ts`
- Javadoc confirms correct separation: "API-layer errors only; agent-domain errors use `AgentErrorCode`"

Tempdoc 201 long-term item status (updated 2026-02-18 post-audit):
- **LT-1** (unify agent + API error types): `AgentErrorCode` is intentionally separate per the
  Javadoc design. Addressed structurally by tempdoc 212 (done per system-reminder update).
- **LT-3** (api.error.total telemetry): Partially done — `recordError()` wired at 3 endpoints
  (KnowledgeSearchController search/suggest, PreviewController) in tempdoc 201 Wave 2. Remaining
  ~14 controllers not yet wired. Tempdoc 212 P2e explicitly deferred. Not "nothing wired."
- **LT-2** (background failure visibility): **Done** — `ListFailedJobs`/`ClearFailedJobs` gRPC
  RPCs + REST endpoints + HealthView "Failed Files" panel shipped per tempdoc 201 post-Wave 2.
- **LT-4** (error/traceId correlation): **Done** — `requestId` in all error responses via MDC,
  shipped in tempdoc 201 Wave 2.

*Note: J2 and J6 findings below were written before LT-2 and LT-4 implementations shipped.
J6 claim "no counters wired" is also stale — 3 endpoints were wired in Wave 2.*

---

### J3 — Multi-agent handoff M0 (tempdoc 211) implementation status

**Finding: Design-only. Zero code written since tempdoc creation 2026-02-17.**

`AgentEvent.java` (sealed interface, 12 implementations) has NO handoff-related events.
Current event types: `TextChunk`, `ToolCallProposed`, `ToolCallPendingApproval`,
`ToolCallApproved`, `ToolExecutionStarted`, `ToolExecutionCompleted`, `ToolCallRejected`,
`AgentDone`, `AgentError`, `AgentProgress`, `AgentBudgetUpdate`, `SessionStarted`.

Neither `HandoffProposed` nor `HandoffExecuted` exist. The `SequentialAgent` pattern is
design-only. Checkpoint fields (`activeAgentId`, `handoffHistory`) are not in `AgentRunStore`.

Tempdoc 211 was created 2026-02-17 with a complete design (events, durable state, safety
model, ship gate). It has not been touched since — no follow-up commits reference it.

**Gap characterization**: Active tempdoc with complete design, zero implementation. S-008 from
tempdoc 208 was correctly deferred here but no implementation session has been scheduled.

---

### J4 — RAG quality eval baseline freshness

**Finding: Very current — phase-6.1-stabilized as of 2026-02-17. Eval is manual-only.**

The eval tempdoc is at `docs/tempdocs/198-rag-quality-eval-loop.md` (not `198-rag-quality-eval.md`).
Status: `phase-6.1-stabilized`, updated 2026-02-17 (yesterday).

Phases 1 through 6.1 are all implemented:
- 24 queries (rag-001..rag-024), 7 metrics, hybrid search (BM25+KNN, RRF k=60)
- Baseline promoted 2026-02-17: `fact_coverage_mean=0.747`, `retrieval_recall_mean=1.000`,
  `faithfulness_mean=0.403`, `citation_precision_mean=0.458`, `answer_similarity_mean=0.265`
- `faithfulness_mode=cross-encoder` (CitationScorer ONNX) — baseline was captured with
  cross-encoder active, meaning the test's path discovery can find the model when run with
  the correct env. The ONNX path mismatch affects only the live `RuntimeActivationService`
  lookup, not the eval test's direct `CitationScorer` initialization.

**Eval is NOT wired to a CI gate.** It runs via `overnight-rag-ai-queue-win.ps1` (manual or
nightly script). There is no automatic gate failure if RAG quality regresses.

**Open items per the tempdoc:**
1. Real-embedding lane (`JUSTSEARCH_ENABLE_REAL_EMBEDDING=true`) has a deadline_exceeded
   outlier on `rag-011` — re-run needed before using embedding artifacts for trend decisions
2. Embedding baseline is intentionally non-promoting (different workload key)
3. Stub-jaccard `answer_similarity_mean=0.265` will jump to ~0.833 when real embeddings activate
4. Dataset expansion (Phase 7) is noted as suggested next step

---

### J5 — Agent eval Phase A gate existence and threshold

**Finding: Phase A always passes — it is a no-op bootstrapping mode, never retired.**

`evaluate-agent-live-gate.mjs:70–72`:
```js
if (mode === 'A') {
  return { ok: true, failures, warnings };
}
```

Phase A unconditionally returns success regardless of scorecard content. It is not a
quality gate — it was a bootstrapping mode used while the 14-run rolling window was being
filled. It was never retired because it is harmless (the nightly workflow uses mode B,
and mode A is only invoked if explicitly passed).

Gate mode summary:
| Mode | infraFailureRate | passRateStdDev | scenarioInstability | runsRequired |
|------|-----------------|----------------|---------------------|--------------|
| A | (ignored — always ok) | | | |
| B | hard fail | hard fail | hard fail | warning only |
| C | hard fail | hard fail | hard fail | hard fail |

Phase C (strict) is available but not currently used by the nightly workflow. Mode B is the
active gate. The `--mode` default in `parseArgs()` is `'B'`.

---

### J6 — Telemetry/observability completeness

**Finding: Agent telemetry is well-instrumented; API error telemetry (LT-3) is partial.**

Agent telemetry (`AgentTelemetry.java`) has 7 signals:
- `agent.error.total` (counter, tagged by `error_code`, `error_class`, `retry_action`)
- `agent.retry.total` (counter)
- `agent.loop.blocked.total` (counter, tagged by `tool_name`, `safety_level`)
- `agent.budget_edge_finalize.total` (counter)
- `agent.retry.exhausted.total` (counter)
- `gen_ai.client.operation.duration` (histogram) — GenAI semconv (OBS-005 from 208)
- `gen_ai.client.token.usage` (histogram) — GenAI semconv

The `08-observability.md` canonical doc lists: HTTP route latency (`api.request_ms`,
`api.stream.ttft_ms`), JVM saturation, worker queue health, AI orchestration outcomes,
IPC metrics. No API-layer error counters per `ApiErrorCode` appear in the metric catalog.

**LT-3 partially done** (updated 2026-02-18): `recordError(Telemetry, errorCode, endpoint)`
was wired at 3 endpoints in tempdoc 201 Wave 2: KnowledgeSearchController (search + suggest)
and PreviewController. The remaining ~14 controllers are not instrumented. Tempdoc 212 P2e
("recordError() uses ApiErrorCode tags consistently") is explicitly deferred. The original
claim that "nothing is wired" was written before Wave 2 shipped.

---

### J7 — Tauri 191 remaining gaps

**Finding: Auto-updater and global search bar are the two highest-impact open items. Both
confirmed absent from Cargo.toml.**

`Cargo.toml` plugin inventory:
```
tauri-plugin-opener, tauri-plugin-dialog, tauri-plugin-single-instance,
tauri-plugin-window-state, tauri-plugin-autostart, tauri-plugin-notification
```

**NOT present:**
- `tauri-plugin-updater` → auto-updater (Gap 3) is confirmed not implemented
- `tauri-plugin-global-shortcut` → global search bar (Section A) is confirmed not implemented
- `tauri-plugin-splashscreen` → splash screen (Gap 4) is confirmed not implemented

**SettingsView.tsx confirms**: No auto-update check, install progress, or update prompt UI.
The "Desktop" section only has the autostart toggle. No update-related UI exists.

Status of remaining 191 items:
| Gap | Status | Blocker |
|-----|--------|---------|
| Gap 3 (auto-updater) | Not started | Signing key, manifest hosting, `tauri-plugin-updater` dep, frontend UI |
| Gap 4 (splash screen) | Not started | `tauri-plugin-splashscreen` dep, splash design |
| Gap 7 (deep linking) | Not started | Low priority; no use case yet |
| Section A (global search bar) | Not started | `tauri-plugin-global-shortcut` removed; ghost command risk |
| B1 (CSP production test) | Implemented, untested | Requires production build or CI test against built artifact |

Auto-updater is the highest-impact remaining item for dogfooding: code signing already exists
(from a previous session), the self-hosted runner can produce signed builds, the remaining
gaps are the signing key management + update manifest hosting + `tauri-plugin-updater` + UI.

---

## Summary table update (Round 2, 2026-02-18)

| Item | Finding | Priority |
|------|---------|----------|
| J1 ONNX status UX | UI shows "not found at standard locations" with no path guidance. Gap persists post-fix. | Low (UX polish after bug fix) |
| J2 Error code Wave 1 | Complete. LT-1 (unify agent+API), LT-3 (api.error.total), LT-2, LT-4 still open from 201. | Medium (LT-3 highest value) |
| J3 Multi-agent handoff (211) | Design-only. HandoffProposed/HandoffExecuted events don't exist. Zero code. | Medium (blocked on schedule) |
| J4 RAG eval baseline | Current (2026-02-17). Manual-only, no CI gate. Real-embedding lane has one outlier. | Low (healthy, just needs CI wiring) |
| J5 Phase A gate | No-op bootstrapping mode. Always passes. Phase B is the active quality gate. | — (informational) |
| J6 API error telemetry | LT-3 confirmed open. No api.error.total counters in API layer. | Medium |
| J7 Tauri remaining gaps | Auto-updater and global search bar absent from Cargo.toml. 5 open items total. | Medium (auto-updater highest impact) |

---

## Status update (2026-02-19)

### Resolved since doc was written

**I4 — Tempdoc 213 Approach A** (`31576635`, 2026-02-18):
Implemented. `SearchTool`: default limit 5→3, per-excerpt truncation 200→800 chars, use all
excerpt regions (up to 3), `content_preview` fallback for vector search (zero-content fix).
`AgentLoopService`: `MAX_TOOL_RESULT_CHARS` 900→4000. Tests updated. RAG eval shows +53%
fact coverage and +94% faithfulness vs old format. I4 is resolved — the eval extension in
`RagQualityEvalTest` is no longer waiting on a code change.

**J6/LT-3 — API error telemetry** (`e3a72b65`, 2026-02-18):
Complete. Auto-recording `toResponse()` overloads added to `ApiErrorHandler`; all 15
controllers migrated to emit `api.error.total` counter with `{error_code, error_class, route}`
tags in a single call. Three message-sniffing hacks replaced with typed exceptions:
`LlmServerException` (app-inference), `KnowledgeServerNotConnectedException` (app-services),
`ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED`. LT-3 is fully done.

**Stale tempdoc 137** (`43fe9701`, 2026-02-18):
Canonicalized. §Extraction Resilience added to `docs/explanation/03-knowledge-server.md` with
5 hardening measures. Deprioritized gaps (exclude patterns, junction dedup, unindexable file
feedback) filed as BKD-013/014/015 in `backend-tech-debt.md`. Tempdoc 137 is closed.

**Tempdoc 208** (`583b5912`, 2026-02-18):
Canonicalized. New `docs/explanation/22-agent-system-architecture.md` created. Durable
content extracted to `05-ai-architecture.md`, `09-testing-strategy.md`, and
`architectural-risks.md` (RISK-007 added). Tempdoc 208 is closed.

**Tempdocs 212, 193** (`956e29bb`, `e51b5b28`, 2026-02-18):
Both canonicalized. 212 (unified error type system) merged into `api-contract-map.md`.
193 (observability maturity) merged into `08-observability.md`. Both closed.

**Tempdoc 215 deleted** (`e3a72b65`, 2026-02-18):
The duplicate intent analysis scaffold was never completed and was deleted as part of the
telemetry cleanup commit (along with tempdocs 146, 181, 189). The only confirmed duplication
it was designed to surface (196 as a near-duplicate of 135's reranker section) was already
documented in this doc. Deemed not worth completing.

### Still open

| Item | Status | Priority |
|------|--------|----------|
| I3 — Nightly eval gate recalibration | Pending — 14-run window needs observation post-ONNX fix to see if 81.25% threshold needs adjusting | Medium |
| I6/194 — Pipeline engine deletion | **Done** — pipeline-engine and pipeline-executor deleted; pipeline-schema created; Launcher.simulate replaced with structural validation; ADR 0007 written; all tests pass (2026-02-19). Follow-on: tempdoc 221 (pipeline-schema simplification — strip execution-engine fields from StageDefinition and SSOT JSON). | Medium |
| J1 — ONNX UX gap | Open — "not found at standard locations" still gives no path guidance post-fix | Low |
| J3/211 — Multi-agent handoff M0 | Open — design complete in tempdoc 211, zero code written | Medium |
| J7/191 — Tauri remaining gaps | Open — auto-updater, splash screen, deep linking, global shortcut, CSP production test | Medium |
| I7/200 — Accessibility CI | Open — zero test infrastructure; axe-core/Playwright integration not started | Low-Medium |
| 197 stale tempdoc | Resolved — tempdoc no longer exists in working tree (deleted in an earlier cleanup) | — |
| 138 stale "Cannot do" labels | Resolved — tempdoc no longer exists in working tree (deleted in an earlier cleanup) | — |

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 88 days at audit time.

