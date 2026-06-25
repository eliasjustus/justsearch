---
title: "263: Agent Workflow Reliability: Lifecycle, Eval, and Shell Friction"
type: tempdoc
status: done
created: 2026-03-06
updated: 2026-03-07
---

> NOTE: Child tempdoc split out of tempdoc 262. This is the implementation-facing document for repo workflow reliability issues.

# 263: Agent Workflow Reliability: Lifecycle, Eval, and Shell Friction

## Purpose

Capture the tactical, repo-specific workflow failures that make agent sessions expensive or brittle,
especially around lifecycle management, eval isolation, shell invocation, and diagnosis.

This doc preserves the workflow-specific improvement material that originally accumulated inside
tempdoc 262.

## Relationship to tempdoc 262

- Tempdoc `262-agent-workflow-quality-architecture.md` is the umbrella architecture/index doc.
- This tempdoc is the tactical reliability child doc.

## Closure update (2026-03-07)

The scoped reliability work described here is now implemented:

- `scripts/dev/dev-runner.cjs` now prunes legacy run-state on `start`, retaining all runs newer
  than 14 days plus the newest 200 run directories overall
- `scripts/search/beir-eval-win.ps1` now resolves `BaseUrl` in this order:
  - explicit `-BaseUrl`
  - `JUSTSEARCH_BASE_URL`
  - runtime `api-port.txt` for the targeted data dir
  - clear failure pointing users to `scripts/search/run-search-workflow.mjs`
- `scripts/search/run-ranking-experiments.ps1` now defaults managed launches to `ApiPort=0` and
  live managed flows consume `Handle.BaseUrl`
- lifecycle-family ownership is now covered by
  `scripts/bench/test-workflow-lifecycle-ownership.mjs`
- one representative direct pair and one representative legacy pair were executed successfully and
  saved under:
  - `tmp/workflow-quality-closure/2026-03-07T02-06-23-016Z/`

## Promoted canonical docs (2026-03-07)

The stable operator-facing outputs from this tempdoc now live in:

- `docs/reference/contributing/dag-runner-operations.md`
- `docs/how-to/validate-workflow-quality.md`
- `docs/decisions/0010-local-first-workflow-quality-observability.md`

This tempdoc remains historical implementation rationale for the scoped reliability program.

## Source session

Primary trigger for this doc: a Claude Code CLI session on 2026-03-06 that attempted to:

1. build the repo
2. start the dev stack
3. run BEIR evaluation against the local backend
4. iterate on failures in-place

The session spent a disproportionate amount of time on lifecycle recovery, shell quoting,
port/process forensics, and harness fragility rather than on the intended evaluation work.

## Problem inventory

### A. Dev stack lifecycle drift

- [x] Lifecycle wrapper can time out even when backend subprocesses were actually spawned.
- [x] Supervisor state and runtime state can diverge (`NO_ACTIVE_RUN`, missing task/run identity, stale run directories).
- [x] Port discovery is not a single reliable source of truth.
- [x] Process cleanup is unreliable enough that old Head/Worker/Gradle processes survive restarts.
- [x] Tooling and scripts assume different default ports.
- [x] Reused data directories preserve runtime artifacts that interfere with fresh starts.

### B. Workflow contamination from preserved state

- [x] `--clean none` preserves more than just expensive assets; it also preserves stale runtime state.
- [x] Old indexing / chunk backfill work can leak into new eval runs.
- [x] Status payloads can be hard to interpret when preserved state and current state disagree.
- [x] Old logs and crash artifacts increase diagnosis noise during fresh investigations.

### C. Eval harness fragility

- [x] BEIR eval script hit an empty-relevance-array failure in the session; current tree already contains a guard.
- [x] Eval scripts have brittle parameter surfaces for cross-shell invocation.
- [x] Long-running eval commands provide weak intermediate progress / liveness information.
- [x] Shared worker resources allow unrelated background work to degrade controlled eval runs.
- [x] Errors such as circuit-breaker failures are surfaced without enough workflow-level context.

### D. Shell and script ergonomics

- [x] Bash -> PowerShell -> script invocation is too brittle for structured arguments such as JSON.
- [x] Parameter ordering and binding behavior turn quoting mistakes into misleading errors.
- [x] Ad hoc inspection helpers are fragile across shell boundaries.
- [x] Repo scripts are optimized for direct human invocation more than agent-safe invocation.

### E. Observability and diagnosis gaps

- [x] Large logs bury the actionable failure signal.
- [x] Background task output often appears empty for long stretches.
- [x] Repo tooling does not always distinguish "buffered", "hung", and "running but silent".
- [x] Health/readiness endpoints exist, but wrappers and scripts do not consistently use them as the source of truth.

### F. Agent CLI friction affecting repo work

- [x] Background task tracking can lose task identity.
- [x] Timed-out commands can leave live subprocesses behind.
- [x] Background output capture is inconsistent enough to force manual process/port forensics.

## Detailed local findings

### 1. Legacy dev-runner lifecycle drift is structural, not incidental

- `scripts/dev/dev-runner.cjs` is still a long-lived supervisor that launches Gradle, waits for
  backend port discovery/readiness, then writes `active.json` and `run.json`.
- That ordering means `HeadlessApp` can already be alive and serving HTTP while `status --active`
  still returns `NO_ACTIVE_RUN`.
- The file itself documents a Windows-specific fallback: it first parses
  `JUSTSEARCH_API_PORT=<n>` from stdout, then falls back to `<dataDir>/runtime/api-port.txt`
  because detached + `windowsHide` pipe behavior can delay stdout delivery.
- `scripts/lib/bench/dev-runner-lifecycle.mjs` also contains explicit logic to treat repeated
  `NO_ACTIVE_RUN` as a likely supervisor stall and then probe the port file + HTTP directly.
- `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java` writes `runtime/api-port.txt`
  best-effort and cleans it up in `finally`; any hard kill can therefore leave stale port state.
- `scripts/dev/dev-runner.cjs` uses `taskkill /T /F` against recorded root PIDs. If the run record
  was never written, stop/cleanup has to guess instead of using durable canonical state.

### 2. Preserved state is broader than "keep expensive assets"

- `scripts/dev/dev-runner.cjs` returns immediately for `--clean none`; it preserves the entire
  data dir, not just models or datasets.
- Current `.dev-data` contents include runtime-adjacent state such as `jobs.db`,
  `watched_roots.json`, `logs/`, `crashes/`, `runtime/`, `app.lock`, and prior GPL artifacts.
- Current repo state also has `1377` directories under `tmp/dev-runner/runs`, which matches the
  "stale run directories accumulate over time" failure mode from the observed session.
- This makes preserved-state workflows ambiguous: an agent trying to "reuse data" also reuses
  queue state, logs, crash debris, runtime snapshots, and watcher metadata unless it explicitly
  cleans them.

### 3. A more reliable lifecycle path already exists in the repo

- `scripts/lib/bench/backend-launcher.mjs` launches Java directly from the install distribution,
  tracks only `backend.pid`, `api-port.txt`, and `backend-run.json`, and uses HTTP probing against
  `/api/status` for readiness.
- Its `soft` clean mode removes only runtime files, which is much closer to "preserve expensive
  assets but clear stale lifecycle state" than `dev-runner.cjs --clean none`.
- `scripts/lib/bench/eval-backend-lifecycle.mjs` already defaults to `--engine direct` and routes
  to `backend-launcher.mjs`; it only falls back to the old path for `--engine legacy`.
- `scripts/ci/dag-runner-agent-battery.mjs` now routes through `eval-backend-lifecycle.mjs` with
  `--engine direct`.
- The remaining repo pain is therefore less about missing adapter adoption in DAG runners and more
  about:
  - manual or PowerShell-owned lifecycle paths
  - preserved-state ambiguity
  - wrapper identity, cleanup, and progress semantics

### 4. Eval harness surfaces are still brittle for agent use

- The older fixed-port mismatch around `9001` has now been removed from the main BEIR automation
  surface: `beir-eval-win.ps1` resolves explicit, env-provided, and runtime-port-file URLs before
  failing.
- `run-ranking-experiments.ps1` managed launches now default to dynamic ports (`ApiPort=0`) and
  consume `Handle.BaseUrl` for live sessions rather than reconstructing URLs from a fixed default.
- In that same script, `Split` appears before `Pipeline`, so a quoting failure can shift a JSON
  string into the `Split` slot and produce a misleading `ValidateSet` error.
- `Pipeline` is accepted as a raw JSON string and parsed via `ConvertFrom-Json`; that is workable
  for direct PowerShell invocation but brittle across Bash -> PowerShell -> script boundaries.
- The repo already contains a safer invocation pattern in `scripts/search/run-ranking-experiments.ps1`,
  which builds a hashtable and splats it into `beir-eval-win.ps1` instead of string-assembling
  the command line. That is a strong in-repo precedent for agent-safe PowerShell invocation.
- The empty-array nDCG failure seen in the source session is already guarded in the current tree,
  so that specific issue appears fixed. The surrounding invocation surface remains fragile.
- `scripts/search/lib/BeirEval.Indexing.psm1` still reports queue depth from `pendingJobs`, while
  `scripts/eval/EvalSession.psm1` looks at a wider set of counters
  (`pendingJobsCount`, `processingJobsCount`, `pendingReadyJobsCount`,
  `pendingBackoffJobsCount`, `buildingIndexedDocuments`). Agents looking only at shallow progress
  output can miss other active background work.

### 5. Controlled evals can still be contaminated by unrelated background work

- The observed session's BM25 baseline collided with old chunk-embedding work and hit circuit
  breaker failures.
- The code explains why that is plausible: eval readiness is based on `/api/status`, preserved
  state retains `jobs.db` and queue-related artifacts, and retrieval can legitimately remain
  `DEGRADED` for unrelated reasons while indexing progresses.
- The repo therefore has a real workflow isolation problem for agent-driven evaluation, even when
  the search harness itself is functioning correctly.

### 6. The repo currently has a real split between backend-only and full-stack workflows

- `scripts/ci/dag-runner-beir-gate.mjs` already uses `eval-backend-lifecycle.mjs` with default
  engine `direct`.
- `scripts/ci/dag-runner-search-eval-rank-report.mjs` also routes through the eval lifecycle
  adapter, not directly through `dev-runner-lifecycle.mjs`.
- `scripts/ci/dag-runner-local-agent-gate.mjs` now routes through the adapter with
  `--engine legacy`.
- `scripts/perf/dag-runner-perf-suite.mjs` now routes through the adapter with `--engine legacy`.
- This split is not entirely accidental: some of the holdout workflows are full-stack/UI-aware,
  while the direct launcher is backend-only. Any workflow improvement plan needs to treat those as
  separate problem classes rather than trying to force all flows onto a single launcher shape.

### 7. PowerShell EvalSession is a third lifecycle family still active in the repo

- `scripts/eval/EvalSession.psm1` still owns its own server lifecycle abstraction with backends
  `installDist`, `gradle`, and `devRunner`.
- `scripts/search/run-ranking-experiments.ps1` now routes through the managed `Invoke-EvalSession`
  / `installDist` path, with backend-only lifecycle delegation to the adapter where appropriate.
- That means the repo does not merely have "legacy vs direct" launch paths; it also has an older
  PowerShell-native lifecycle family that overlaps them.
- This matters for implementation sequencing:
  - backend-only DAG runners can often converge on the direct launcher quickly
  - interactive full-stack flows may need the legacy path longer
  - older PowerShell eval tooling needs a migration or adapter plan, not just a doc update

### 8. Lifecycle components already have focused mock-based tests

- `scripts/lib/bench/test-backend-launcher.mjs` exercises `start`, `status`, `restart`, and
  `stop` for the direct launcher.
- `scripts/lib/bench/test-dev-runner-lifecycle.mjs` exercises the legacy lifecycle wrapper.
- `scripts/lib/bench/test-eval-backend-lifecycle.mjs` exercises the adapter in both `direct` and
  `legacy` modes.
- That means workflow hardening can build on an existing test harness. The main gap is behavioral
  coverage for stale runtime files, preserved-state contamination, ambiguous progress reporting,
  and agent-facing wrapper invocation failures.

## Prior repo findings relevant to this doc

- Tempdoc `257-backend-cold-start-investigation.md` already concluded that the detached
  dev-runner chain is the wrong lifecycle for battery/eval workflows and introduced
  `backend-launcher.mjs` specifically to avoid that stack.
- Tempdoc `216-eval-harness-consolidation.md` is relevant because it centralizes eval behavior,
  which makes lifecycle/default-port mismatches more visible when wrapper scripts diverge.
- Tempdoc `254-mcp-dev-tools-issues.md` overlaps on MCP/dev-runner ergonomics, but this doc is
  broader: lifecycle, eval isolation, shell ergonomics, and observability for agent work.
- Tempdoc `118-agent-efficiency-research.md` is highly relevant as a behavioral telemetry source,
  but it must be used carefully. Its own conclusion is that the Process Hygiene Index is not a
  predictor of task outcomes; its strongest value is intervention design, cost tracking, boolean
  outlier flags, and first-person friction findings.

## External research notes

### Official sources reviewed

- Node.js `child_process` docs: <https://nodejs.org/api/child_process.html>
- PowerShell `about_Parsing`: <https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_parsing?view=powershell-7.5>
- PowerShell `about_Splatting`: <https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_splatting?view=powershell-7.5>
- PowerShell `Start-Process`: <https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/start-process?view=powershell-7.5>
- Windows `taskkill`: <https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/taskkill>
- Gradle Daemon docs: <https://docs.gradle.org/current/userguide/gradle_daemon.html>

### Takeaways that map directly to repo issues

- Node's official docs explicitly call out that Windows `.bat` / `.cmd` files need a shell-based
  launch path. That matches why `dev-runner.cjs` has to keep Gradle in a more awkward spawn mode.
- The same Node docs recommend `stdio: 'ignore'` and detaching/unref'ing long-running background
  children when the parent should exit. That aligns with the direct launcher approach and argues
  against pipe-heavy detached supervisor chains.
- Node also documents Windows-specific `overlapped` stdio handles. That may be relevant for
  experiments, but it is still a pipe-based model and does not remove the core supervisor-state
  complexity.
- PowerShell's own parsing docs explain the native-command parsing split that makes JSON-valued
  arguments fragile across shell boundaries.
- PowerShell's `about_Splatting` guidance reinforces that structured argument passing should use
  named parameters rather than stringly assembled command lines wherever possible.
- `Start-Process` docs explicitly note that an `ArgumentList` array is joined back into a single
  string and recommend passing a single fully quoted argument string when exact quoting matters.
  That supports moving agent-facing wrappers away from ad hoc inline JSON passing.
- Microsoft documents `taskkill /T` as killing the specified process and any child processes
  started by it. That validates the tree-kill strategy itself; the repo problem is identity/state
  tracking, not the existence of `taskkill /T`.
- Gradle documents that even with `--no-daemon`, a single-use daemon can still be forked to honor
  JVM settings. That adds more process indirection to an already fragile lifecycle path.

## Cross-cutting implementation decisions

### Canonical readiness vocabulary and wrapper contract

- Runtime truth should come from the existing contract surfaces:
  - `/api/health` for lifecycle state
  - `/api/status.readiness` for typed readiness dimensions and composites
- Wrappers should report two separate things:
  - launch phases before HTTP truth exists
  - canonical runtime readiness after HTTP truth exists
- Concretely, wrappers may emit phase fields such as:
  - `spawned_process`
  - `pid_recorded`
  - `port_discovered`
  - `http_reachable`
  - `workload_quiescent`
- But once `/api/status` is reachable, wrappers should treat the following as canonical:
  - lifecycle state from `LifecycleSnapshotV1`
  - readiness states from `health-readiness-contract.v1.md`
  - retrieval and AI composite states from `readiness.composites`
- The implementation rule should be:
  - wrappers may add phase and provenance data
  - wrappers must not invent a second meaning for `READY`, `DEGRADED`, `NOT_READY`,
    `NOT_CONFIGURED`, or `UNKNOWN`

### Artifact taxonomy and cleanup invariants

- The repo should explicitly classify workflow artifacts into at least these buckets:
  - runtime:
    - `api-port.txt`
    - PID files
    - run records
    - lock files
  - queue and work-in-progress:
    - `jobs.db`
    - pending embeddings
    - backfill progress
    - migration state
  - logs and crash artifacts:
    - `logs/`
    - `crashes/`
    - supervisor stdout and stderr captures
  - watcher metadata:
    - watched roots
    - enumerator state
  - index assets:
    - active and building index generations
    - chunk vectors
  - datasets and corpora:
    - extracted BEIR corpora
    - local benchmark caches
  - models and training artifacts:
    - GPL triples
    - LambdaMART model outputs
    - embedding and rerank model assets
  - user settings:
    - local defaults and preferences
- Cleanup invariants should then follow:
  - runtime artifacts must never survive a mode intended to produce a fresh runnable state
  - queue and work-in-progress state must not be preserved for controlled eval baselines unless the
    caller explicitly asked for resume semantics
  - logs and crash artifacts should be preserved only when diagnostics were requested
  - expensive assets such as datasets, models, and index generations may be preserved independently
    of runtime and queue state

### Ownership boundary

- Repo-owned and fixable here:
  - lifecycle wrapper semantics
  - cleanup semantics
  - default ports and base-URL selection
  - PowerShell and Node invocation surfaces
  - progress and liveness envelopes
  - regression coverage
- Partly repo-owned, partly platform-shaped:
  - Windows process-tree cleanup behavior
  - shell quoting boundaries
  - detached-process log capture behavior
- Not primarily repo-owned:
  - external agent CLI background task identity loss
  - model reasoning quality
  - third-party platform outages
- This boundary matters because the implementation plan should optimize for:
  - eliminating repo-caused ambiguity first
  - detecting external-runtime failure modes second
  - not promising fixes for behaviors the repo cannot actually control

### Migration and deprecation policy

- The repo now has three lifecycle families:
  - direct Node launcher
  - legacy dev-runner lifecycle
  - PowerShell `EvalSession`
- The long-term migration rule should be:
  - backend-only automation converges on the direct family
  - full-stack workflows stay explicit on the legacy family until a real replacement exists
  - PowerShell eval tooling becomes an adapter or compatibility layer rather than an independent
    lifecycle owner
- That implies a staged policy:
  1. define parity and evidence requirements
  2. migrate backend-only call sites first
  3. downgrade old paths to explicit compatibility status in docs and flags
  4. remove only after both workflow coverage and evidence parity exist

### Agent-safe invocation principle

- Human-friendly CLI ergonomics and agent-safe invocation are not the same requirement.
- For agent-facing surfaces, structured inputs should prefer:
  - files
  - named parameters
  - object or hashtable delegation
- They should avoid raw inline JSON crossing Bash -> PowerShell boundaries when a wrapper can own
  the structured invocation instead.
- It is acceptable for the repo to keep both:
  - human-friendly compatibility wrappers
  - stricter agent-safe entry points
- The important rule is that docs and automation must name which one is canonical for which
  workflow class.

## Candidate improvement directions

## Implemented reliability baseline in repo head

The main no-regret reliability changes described in this doc now exist in code:

- runtime cleanup is centralized in `scripts/lib/bench/lifecycle-cleanup.mjs`
- direct and legacy adapter envelopes now include cleanup and phase metadata
- stale runtime artifacts are detected and emitted into workflow telemetry
- the main DAG runners now emit workflow run events at lifecycle and step boundaries
- agent-safe BEIR/ranking automation now exists in `scripts/search/run-search-workflow.mjs`
- BEIR file-based pipeline input is implemented via `-PipelineFile`
- backend-only PowerShell lifecycle ownership now delegates through `EvalSession.psm1` to the
  adapter
- ranking experiments now use the managed `EvalSession` / `installDist` lifecycle path as the only
  live server lifecycle path
- the dead manual launch and tree-kill branch has been removed from active ranking execution
- ranking automation now has deterministic output routing via `-OutBaseDir` plus final structured
  summaries via `-JsonSummary`
- focused regression coverage now exists for:
  - lifecycle cleanup semantics
  - workflow-run telemetry storage
  - `-PipelineFile` validation
  - adapter behavior and DAG migration paths
  - ranking lifecycle contract expectations

## Status for this pass

- Closed in this pass:
  - remaining active ranking lifecycle cutover to `EvalSession` / adapter ownership
  - removal of the live manual launch fallback from ranking execution
  - agent-safe deterministic artifact outputs for ranking workflows
  - `tmp/dev-runner/runs` pruning and retention policy
  - base-URL / default-port cleanup for the main BEIR and ranking surfaces
  - lifecycle-family ownership checks for the important runners
- Still intentionally not in scope:
  - broad full-stack legacy-path retirement beyond the named workflow families in this program

### Historical implementation directions now largely realized

- Standardize agent/eval/backend-only workflows on `backend-launcher.mjs` or
  `eval-backend-lifecycle.mjs --engine direct`; reserve `dev-runner.cjs` for interactive full-stack
  work where the frontend is actually needed.
- Make that split explicit in docs and runner defaults: backend-only agents should not guess
  between the two lifecycle families.
- Redefine "preserve data" semantics around artifact classes rather than `--clean none`; preserving
  corpora/models should not automatically preserve runtime port files, PID files, queue DB state,
  watcher metadata, or stale crash/log debris.
- Align backend/eval defaults around a single source of truth for API base URL selection. The main
  BEIR and ranking surfaces now do this; remaining secondary human-facing scripts should follow the
  same policy rather than reintroducing fixed-port assumptions.
- Replace raw JSON CLI parameters for agent-facing eval scripts with a safer transport:
  file-based config, hashtable/object input inside PowerShell, or a simpler named-flag surface.
- Reuse the existing safe PowerShell invocation style already present in
  `run-ranking-experiments.ps1` rather than inventing a new calling convention from scratch.
- Extend the existing lifecycle test harnesses with regression cases for:
  stale `api-port.txt`, preserved `jobs.db` / run-state artifacts, and argument-binding failures
  at the PowerShell wrapper boundary.

### Medium-confidence directions

- Narrow progress/status reporting so wrappers can clearly distinguish:
  "process spawned", "port bound", "HTTP ready", "index busy", "queue busy", and "silent but healthy".
- Add pruning/retention for `tmp/dev-runner/runs` so run-state directories do not accumulate without
  bound. This is now implemented for the current supervisor state path, but long-term retention
  policy can still evolve if the repo adds new workflow artifacts.
- Expose a more explicit "workflow isolation" mode for evals that fails fast or warns when unrelated
  background jobs are active.
- Make logs more structured and bounded for long-running agent sessions so a Jetty dump or similar
  large output cannot bury the actual failure cause.

## Historical implementation work packages

The work packages below are retained as implementation history. The scoped items in these packages
are now implemented for the current program.

### WP1. Lifecycle family normalization

- Goal:
  - make backend-only and eval workflows converge on one supported lifecycle path
  - make full-stack workflows explicit when they still require the legacy path
- Primary files:
  - `scripts/lib/bench/backend-launcher.mjs`
  - `scripts/lib/bench/eval-backend-lifecycle.mjs`
  - `scripts/lib/bench/dev-runner-lifecycle.mjs`
  - `scripts/eval/EvalSession.psm1`
  - `scripts/ci/dag-runner-local-agent-gate.mjs`
  - `scripts/perf/dag-runner-perf-suite.mjs`
  - `scripts/search/run-ranking-experiments.ps1`
- Concrete shape:
  - treat `eval-backend-lifecycle.mjs` as the canonical adapter for backend-only automation
  - keep `dev-runner-lifecycle.mjs` only for full-stack workflows that genuinely need UI readiness
  - either migrate `EvalSession.psm1` callers onto the adapter or make `EvalSession` delegate to it
    instead of maintaining a separate lifecycle family

### WP2. Artifact-class cleaning and preserved-state semantics

- Goal:
  - stop equating "preserve expensive assets" with "preserve everything under the data dir"
- Primary files:
  - `scripts/dev/dev-runner.cjs`
  - `scripts/lib/bench/backend-launcher.mjs`
  - `scripts/lib/bench/eval-backend-lifecycle.mjs`
- Concrete shape:
  - classify preserved state into runtime, logs, queue DB, models, corpora, index, UI settings,
    watcher metadata, and training artifacts
  - make cleanup modes map to those classes explicitly
  - ensure runtime state (`api-port.txt`, PID files, run records) never survives a mode intended for
    "fresh runnable state"

### WP3. Agent-safe eval invocation surface

- Goal:
  - remove Bash-to-PowerShell JSON quoting fragility from the common eval path
- Primary files:
  - `scripts/search/beir-eval-win.ps1`
  - `scripts/search/run-ranking-experiments.ps1`
  - `scripts/search/lib/BeirEval.Indexing.psm1`
- Concrete shape:
  - keep the PowerShell implementation for corpus and metrics logic
  - add a Node entry wrapper for agent use that accepts structured flags or a config file and then
    invokes PowerShell via splatted named parameters
  - move manual agent workflows away from raw `-Pipeline '{...}'` command lines

### WP4. Progress and liveness normalization

- Goal:
  - make "spawned", "port discovered", "HTTP ready", "worker ready", "queue busy", and
    "degraded but serving" machine-readable across wrappers
- Primary files:
  - `scripts/lib/bench/backend-launcher.mjs`
  - `scripts/lib/bench/dev-runner-lifecycle.mjs`
  - `scripts/lib/bench/eval-backend-lifecycle.mjs`
  - `scripts/dev/justsearch-dev-mcp/server.mjs`
  - `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java`
- Concrete shape:
  - keep `/api/status` as the canonical runtime truth
  - normalize wrapper envelopes around it instead of inventing separate readiness meanings per script
  - emit phase timestamps and direct-detection or fallback reasons where wrappers currently infer
    readiness heuristically

### WP5. Regression coverage for workflow reliability

- Goal:
  - make the reliability work testable before any large refactor
- Primary files:
  - `scripts/lib/bench/test-backend-launcher.mjs`
  - `scripts/lib/bench/test-dev-runner-lifecycle.mjs`
  - `scripts/lib/bench/test-eval-backend-lifecycle.mjs`
  - `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java`
- Concrete shape:
  - add cases for stale runtime files, preserved queue state, ambiguous `NO_ACTIVE_RUN`,
    stop-without-recorded-root-PID, and wrapper progress semantics
  - extend lifecycle contract assertions only for additive fields so `/api/status` stays compatible

## Sequencing for implementation

1. Land test expansions and envelope fields first.
2. Normalize cleanup semantics and direct-launch defaults next.
3. Migrate backend-only DAGs and PowerShell eval callers after the adapter surface is stable.
4. Defer full-stack legacy-path simplification until the repo has a deliberate replacement for UI
   aware workflows.

## Post-2026-03-06 commit correction

This tempdoc was initially written against a baseline that is now partly outdated. The following
recent commits already implemented some of the work that had been described here as future-facing:

- `31d443d6 feat(eval): harden and centralize BEIR lane contracts`
- `9afee0dc scripts/bench: close eval core modernization gaps`

### Already implemented in repo head

- `scripts/lib/bench/eval-backend-lifecycle.mjs` now exists as a real adapter over `direct` and
  `legacy` engines with registry-backed `start|status|stop|restart`.
- `scripts/lib/bench/backend-launcher.mjs` already has parity features that older planning noted as
  gaps:
  - `restart`
  - `--env-overrides`
  - explicit stdout/stderr logs
  - `run-id`
- The main lifecycle-sensitive DAG runners now route through the adapter rather than calling raw
  lifecycle engines directly:
  - `scripts/ci/dag-runner-beir-gate.mjs`
  - `scripts/ci/dag-runner-search-eval-rank-report.mjs`
  - `scripts/ci/dag-runner-agent-battery.mjs`
  - `scripts/ci/dag-runner-local-agent-gate.mjs`
  - `scripts/perf/dag-runner-perf-suite.mjs`
  - retry helpers under `scripts/ci/helpers/`
- `scripts/search/beir-eval-win.ps1` has already been decomposed materially by moving search/runtime
  contract logic into `scripts/search/lib/BeirEval.Search.psm1`.
- BEIR runtime gates for hybrid/vector and LambdaMART-bearing runs are already real and tested.
- Search-eval readiness aggregation/reporting already exists in:
  - `scripts/bench/build-search-eval-readiness.mjs`
  - `scripts/bench/lib/search-eval-readiness-core.mjs`

### What remains the real workflow-reliability scope

- extend adapter usage beyond current search-eval DAGs where justified
- normalize cleanup by artifact class instead of current `none|soft|hard` ambiguity
- add repo-wide workflow-run telemetry and join keys
- provide an agent-safe structured entry surface for BEIR/ranking automation
- reduce dependence on raw Bash-to-PowerShell JSON command lines
- turn `EvalSession` from lifecycle owner into a compatibility/delegation layer
- expand regression coverage for stale runtime state, orphan processes, and preserved-state drift

### Strategic correction to the previous plan

The repo's current direction is more nuanced than "replace PowerShell quickly":

- PowerShell lifecycle ownership should still be deprecated quickly where the adapter already gives a
  better automation surface.
- But the PowerShell eval core is still an actively hardened implementation, not dead code. Recent
  commits invested in:
  - PowerShell module extraction
  - PowerShell unit tests
  - PowerShell integration tests
- Therefore the realistic near-term target is:
  - deprecate PowerShell as the authoritative lifecycle/orchestration surface
  - keep PowerShell corpus/metric logic as a compatibility layer or retained implementation until a
    replacement proves clearly better

## Historical investigation plan

### Local code paths inspected

- [x] `scripts/dev/dev-runner.cjs`
- [x] `scripts/lib/bench/dev-runner-lifecycle.mjs`
- [x] `scripts/lib/bench/eval-backend-lifecycle.mjs`
- [x] `scripts/search/beir-eval-win.ps1`
- [x] `scripts/search/lib/BeirEval.*`
- [x] frontend/dev stack scripts that write or consume runtime port files
- [x] backend code responsible for writing `api-port.txt`

### Future research question outside current scope

- [ ] Any reliable patterns for agent-facing CLI tools to report liveness and structured progress

## Critical assessment

### What this doc does well

- It is the strongest of the child docs on direct repo relevance: most findings are tied to real
  code paths and observed workflow behavior.
- It correctly distinguishes backend-only and full-stack lifecycle families, which is one of the
  most important architectural realities in this repo.
- The candidate directions are practical and mostly grounded in code already present in the tree.

### Current weaknesses

- The `Problem inventory` checklist format is semantically muddy: `[x]` reads like "resolved" even
  though the section is listing active problems.
- The doc still mixes:
  - repo-controlled workflow issues
  - agent-runtime or CLI limitations
  - general shell/platform friction
  without a clean ownership boundary.
- Prioritization remains more qualitative than ideal. The implemented closure work now has targeted
  tests and saved closure artifacts, but the document still does not rank issues by frequency,
  severity, leverage, or implementation cost in one compact table.
- Although the code-path review is good, the narrative still leans heavily on one trigger session
  and then generalizes outward.
- The historical candidate directions are now largely realized, but the document still does not
  summarize them in a single validation matrix of: symptom, change, expected metric movement, and
  regression coverage.

### What would improve this doc next

- Replace the current problem checklist with a severity and ownership table.
- Separate repo-native failures from external agent-runtime limitations so remediation scope is
  clear.
- Add a validation plan that maps each high-confidence direction to:
  - target workflows
  - test coverage
  - observable success signals
- Reduce or remove statements that still describe pre-implementation adapter adoption gaps; most of
  the remaining work is now around semantics and compatibility, not basic adapter rollout.
- Compress or move lower-signal background sections if this doc starts growing further; it should
  stay implementation-facing.
