---
title: "259: Eval Infrastructure Hardening Investigation"
type: tempdoc
status: done
created: 2026-03-05
updated: 2026-03-06
---

> NOTE: Noncanonical doc (planning/investigation). May drift.

# 259: Eval Infrastructure Hardening Investigation

## Goal

Define and track eval-infrastructure hardening work for search quality, from
planning through incremental implementation slices.

## Scope

- Search eval infrastructure (`beir` + `rank` lanes), shared bench/eval libs,
  and CI maturity path.
- Includes both investigation findings and implemented GO slices for
  shared-infra safety nets and rank policy explicitness.

---

## Audit Snapshot (Moved from 258)

## 2026-03-05 Eval Code Infrastructure Audit (Long-Term Development)

This section evaluates whether the current eval **code infrastructure** (not
just methodology) is strong enough for sustained, fast iteration over the next
phases of search-quality work.

### What is structurally strong

1. **Canonical orchestration model is real and adopted**
   - The codebase has a shared DAG scheduler + step runner + run-state stack
     (`scripts/lib/orchestration/dag-scheduler.mjs`,
     `scripts/lib/orchestration/step-runner.mjs`,
     `scripts/lib/orchestration/run-state.mjs`).
   - Canonical docs align with this design:
     `docs/reference/contributing/dag-runner-operations.md`
     (Node DAG first, PS wrappers as compatibility).

2. **Schema/governance direction is coherent**
   - Search eval supports dual-write and mixed v1/v2 loading, with explicit
     comparability machinery (`scripts/bench/lib/suite-loader.mjs`,
     `scripts/bench/diff-search-eval-suite.mjs`,
     `scripts/lib/bench/artifact-resolution.mjs`).
   - Canonical contracts are defined and centralized:
     `docs/reference/contracts/benchmark-eval-contract.md`.

3. **Runner contract tests exist and are useful**
   - `dag-runner-beir-gate`, `dag-runner-search-eval-rank-report`, and other
     runners have topology/CLI tests that catch many wiring regressions.

### Long-term maintainability risks

1. **Script-centric complexity is still high**
   - Key files remain large:
     - `scripts/search/beir-eval-win.ps1` (1121 lines)
     - `scripts/search/run-ranking-experiments.ps1` (1060 lines)
     - `scripts/bench/lib/suite-loader.mjs` (760 lines)
     - `scripts/bench/diff-search-eval-suite.mjs` (651 lines)
     - `scripts/ci/dag-runner-search-eval-rank-report.mjs` (514 lines)
     - `scripts/ci/dag-runner-beir-gate.mjs` (491 lines)
   - This is manageable now, but it will slow iteration as Phase 2/4/5 eval
     lanes and metrics expand.

2. **Backend lifecycle is split across two launchers**
   - Most eval lanes use `dev-runner-lifecycle`:
     `scripts/ci/dag-runner-beir-gate.mjs`,
     `scripts/ci/dag-runner-search-eval-rank-report.mjs`.
   - Agent battery uses `backend-launcher`:
     `scripts/ci/dag-runner-agent-battery.mjs`.
   - Two lifecycle stacks mean duplicated bugfix surface and drift risk.

3. **Test coverage is uneven at the shared-library layer**
   - Strong coverage exists for runner graph/arg contracts.
   - But several core shared components have weak or no direct tests:
     - No dedicated tests for `backend-launcher.mjs`.
     - No dedicated tests for `dev-runner-lifecycle.mjs`.
     - No dedicated tests for retry helpers:
       `scripts/ci/helpers/beir-index-with-retry.mjs`,
       `scripts/ci/helpers/rank-eval-with-retry.mjs`.
     - `artifact-resolution.mjs` gets partial checks through
       `dag-runner-track-g.test.mjs` but not full-path behavior coverage.
     - `suite-loader.mjs` has no dedicated test suite despite being a central
       migration compatibility layer.

4. **CI enforcement maturity is still below blocking level for search eval**
   - Compatibility matrix marks Search eval at `L1 -> L2` (not L3):
     `docs/reference/benchmark-eval-compatibility-matrix.md`.
   - Search eval workflows are manual (`workflow_dispatch`) and report-oriented
     by design (`.github/workflows/search-eval-rank-report-win.yml`).
   - Main PR CI (`.github/workflows/ci.yml`) does not run search eval lanes as
     a default blocking gate.

5. **Decision semantics are slightly fragmented**
   - BEIR gate diff enforces fail-on-non-comparable by default
     (`dag-runner-beir-gate.mjs`).
   - Rank report lane uses a report-style diff path (primary diff call does not
     pass `--fail-on-non-comparable`; only optional judged-BEIR diff does).
   - This is intentional for lane maturity, but long-term it complicates one
     clear "quality gate contract" across lanes.

6. **Signal drift already appeared in tests/comments**
   - `test-diff-search-eval-suite.mjs` still comments that MRR gating is
     deferred, while `diff-search-eval-suite.mjs` currently compares
     `meanMrrAtK`.
   - This is not a runtime failure by itself, but it is a maintainability smell
     for the eval platform.

7. **Resume hash scope is narrow**
   - `computeStepHash` hashes only `command + args`.
   - It intentionally ignores `env`, `cwd`, and timeout fields. This keeps
     resume deterministic for arg-patching flows, but raises long-term risk that
     changed execution context can be resumed as "unchanged".

### Sufficiency verdict (infrastructure only)

Current eval infrastructure is **a solid foundation but not yet sufficient for
long-term high-velocity quality development**.

It is sufficient for:
- Running controlled regression lanes.
- Producing governed artifacts with comparability metadata.
- Preventing many orchestration regressions.

It is not yet sufficient for:
- Rapidly adding new eval dimensions without compounding complexity.
- Confidently relying on one unified, blocking quality signal in CI.
- Low-risk long-term maintenance by one developer without drift.

### Direction for long-term hardening (code infrastructure)

1. **Converge backend lifecycle to one launcher path** used by all eval lanes.
2. **Add dedicated tests for shared infra** (`suite-loader`, full
   `artifact-resolution` resolution paths, retry helpers, chosen launcher).
3. **Raise search-eval CI maturity** from L1/report to at least L2/warn-on-regression
   in regularly triggered pipelines, then plan L3 for stable slices.
4. **Decompose largest eval scripts** into smaller library modules before
   adding more metrics/lanes, to avoid exponential maintenance cost.
5. **Align gate semantics across BEIR/rank lanes** so "non-comparable" and
   "regression" behavior is intentionally consistent and documented once.

---

## Implementation Investigation (No Code Changes Yet)

## 1) Converge Backend Lifecycle

### Current state

- Search eval lanes use `dev-runner-lifecycle`:
  - `scripts/ci/dag-runner-beir-gate.mjs`
  - `scripts/ci/dag-runner-search-eval-rank-report.mjs`
  - `scripts/ci/helpers/beir-index-with-retry.mjs`
  - `scripts/ci/helpers/rank-eval-with-retry.mjs`
- Agent battery uses direct launcher:
  - `scripts/lib/bench/backend-launcher.mjs`
  - `scripts/ci/dag-runner-agent-battery.mjs`
- `backend-launcher` proved viable in `257-backend-cold-start-investigation.md`,
  but parity gaps remain for eval lanes:
  - no `restart` command
  - no `--env-overrides` passthrough
  - no explicit `stdout/stderr` log file options

### Recommended implementation direction

1. Add an eval-focused lifecycle adapter with one stable CLI contract
   (`start|stop|status|restart`) and route both old/new launchers through it.
2. Make launcher choice explicit by flag/config:
   - `legacy` => `dev-runner-lifecycle`
   - `direct` => `backend-launcher`
3. Migrate search-eval runners/helpers first, not local-agent/perf lanes.
4. Keep rollback to legacy path for one transition cycle.

### Migration sequence

1. Adapter + parity features.
2. Switch retry helpers to adapter.
3. Switch BEIR gate and rank report to adapter.
4. Remove direct calls to old lifecycle from eval lanes.
5. Decide whether perf/local-agent should stay on legacy.

### 2026-03-05 launcher takeover deep dive

Question investigated: can one launcher take over, and if yes, which one?

#### Direct usage map today

- `backend-launcher` is used in:
  - `scripts/ci/dag-runner-agent-battery.mjs`
- `dev-runner-lifecycle` is used in:
  - `scripts/ci/dag-runner-beir-gate.mjs`
  - `scripts/ci/dag-runner-search-eval-rank-report.mjs`
  - `scripts/ci/helpers/beir-index-with-retry.mjs`
  - `scripts/ci/helpers/rank-eval-with-retry.mjs`
  - `scripts/ci/dag-runner-local-agent-gate.mjs`
  - `scripts/perf/dag-runner-perf-suite.mjs`

#### Capability mismatch summary

- `dev-runner-lifecycle` supports:
  - `start|stop|status|restart`
  - `--env-overrides`
  - `--ui-port`
  - `--stdout-log` / `--stderr-log`
  - active-run stop/status semantics via `dev-runner.cjs`
- `backend-launcher` supports:
  - `start|stop|status`
  - direct backend JVM launch from `modules/ui/build/install/ui/lib`
  - stop by pid/port/data-dir state files
- Missing parity in `backend-launcher` for existing consumers:
  - no `restart`
  - no `--env-overrides`
  - no `--ui-port`
  - no explicit log file options
  - no active-run stop abstraction (`stop --active --force` style)

#### CI/runtime precondition mismatch

- `backend-launcher` hard-fails with `NO_DIST` when installDist output is absent.
- Search eval workflows currently install Node deps but do not explicitly run
  `:modules:ui:installDist` (or equivalent) before starting backend.
- Therefore, direct replacement in BEIR/rank lanes is not currently safe
  without adding build preflight or launcher fallback behavior.

#### Feasibility by lane class

1. Backend-only eval lanes (BEIR gate, rank report): feasible with moderate
   migration work (adapter/parity + installDist preflight).
2. Full-stack lanes (local-agent gate, perf suite): not feasible as direct
   takeover target today, because they depend on UI lifecycle semantics.
3. Agent battery lane: already on `backend-launcher`; keep as-is.

#### Takeover decision

- Do not force a single launcher for all lanes right now.
- Target takeover should be:
  - `backend-launcher` for backend-only eval lanes.
  - keep `dev-runner-lifecycle` for full-stack lanes until/unless a unified
    replacement supports UI supervision semantics.

If one launcher had to be chosen globally today, `dev-runner-lifecycle` is the
only viable global option (feature superset), but that is not the recommended
direction for search eval infrastructure quality.

#### Non-implementation next steps (design only)

1. Define one adapter CLI contract used by BEIR/rank/retry helpers.
2. Add adapter support for:
   - `restart`
   - env passthrough (`--env-overrides`)
   - explicit `--data-dir` stop semantics in every runner
3. Add installDist preflight in BEIR/rank workflows before launcher start.
4. Keep local-agent/perf pinned to `dev-runner-lifecycle` during this phase.
5. Add launcher contract tests before lane cutover.

#### Theoretical long-term architecture (ignoring feasibility constraints)

If we ignore migration cost and focus purely on long-term engineering quality,
the best direction is **not** "pick one current script and force it everywhere."
The best direction is a **single lifecycle domain model** with multiple
execution engines.

##### Why current scripts are each incomplete as a universal foundation

1. `backend-launcher` is execution-efficient but capability-thin.
   - Strong: direct JVM launch, simple ownership model, fast readiness loop.
   - Weak: no run identity model, no restart semantics, no UI process model,
     no structured long-run supervisor state.
2. `dev-runner-lifecycle` is capability-rich but architecture-heavy.
   - Strong: run identity, status/stop via durable active-run metadata, richer
     control surface (`restart`, env overrides, UI port).
   - Weak: extra indirection through a separate supervisor process and
     mixed responsibilities (backend lifecycle + frontend dev server + local
     dev ergonomics), which increases cognitive and failure surface.

##### Long-term optimal model

Define one canonical lifecycle contract with:

1. **Single public control plane**
   - `start`, `stop`, `status`, `restart`
   - strict JSON schema for all responses
   - stable error taxonomy (`READY_TIMEOUT`, `PROCESS_DIED`, `NOT_RUNNING`,
     `NON_COMPARABLE_STATE`, etc.)
2. **Run-scoped identity and state**
   - every start returns `runId`
   - all stop/status operations can target `runId` explicitly
   - avoid global active-run ambiguity as the primary control primitive
3. **Execution profiles as first-class strategy**
   - `backend-only` profile
   - `full-stack` profile
   - both satisfy the same lifecycle contract while using different internals
4. **Composable readiness/termination policies**
   - probe chain (pid alive, port bind, HTTP ready, optional UI ready)
   - teardown chain (root kill, tree kill, port-owner fallback)
   - policy selected by profile, not hand-coded per script
5. **Unified observability contract**
   - consistent log/artifact fields across profiles
   - consistent stop report structure
   - explicit lifecycle phases (`spawning`, `bound`, `ready`, `degraded`,
     `stopping`, `stopped`, `failed`)

##### Resulting theoretical stance on launcher ownership

1. `backend-launcher` should conceptually own the **execution engine pattern**
   for backend-only lanes (minimal process chain, explicit backend ownership).
2. `dev-runner` semantics should conceptually own the **full-stack supervision
   profile** (frontend + backend + dev state ergonomics).
3. Neither existing script should remain the system boundary long-term.
   - They should become internal adapters under one lifecycle platform.

##### Design principle for future work

Optimize for **contract convergence + profile specialization**, not tool
monoculture. Monoculture of one current launcher creates either capability debt
(`backend-launcher` everywhere) or complexity debt (`dev-runner` everywhere).

#### Uncertainty resolution (codebase + external standards)

The following resolves the previously listed uncertainties at an architectural
level, without committing to implementation sequence.

### 1) Lifecycle domain boundary

**Decision:** The lifecycle domain should cover runtime lifecycle control only
(`start/stop/status/restart`, readiness, termination, state), not full local
dev ergonomics.

**Reasoning from codebase:**
- `backend-launcher` is a backend runtime controller.
- `dev-runner` also owns frontend dev server orchestration and local workflow
  concerns (`npm run dev`, UI port shaping), which are separate concerns.

**External alignment:**
- Kubernetes separates startup/readiness/liveness concerns rather than a single
  merged readiness concept.
- systemd separates service type/readiness signaling from orchestration
  dependency wiring.

### 2) State source of truth

**Decision:** Use a run-scoped state registry as canonical truth, with runtime
evidence (pid/port/http) as reconciliation signals.

**Reasoning from codebase:**
- `backend-launcher` state is only `backend.pid` + `api-port.txt` under data
  dir.
- `dev-runner` uses durable run metadata (`runs/<runId>/run.json`,
  `active.json`) plus live probes.
- Each alone is insufficient: file-only state can go stale; active-pointer-only
  state can desynchronize from process reality.

**External alignment:**
- systemd guidance recommends avoiding PID files where possible and relying on
  explicit manager/service signaling and known main process tracking.

### 3) Command targeting semantics

**Decision:** Canonical commands should target explicit `runId` primarily.
`--active`/`--data-dir` can remain compatibility aliases but should resolve to a
 deterministic run target.

**Reasoning from codebase:**
- `dev-runner` already supports `--run` and `--active`.
- `backend-launcher` uses implicit data-dir state and optional pid/port
  overrides.
- Mixed implicit targeting creates ambiguity for concurrent runs and future
  multi-run lanes.

**External alignment:**
- Container/service control interfaces generally use explicit resource identity
  as the control target.

### 4) Readiness contract

**Decision:** Make readiness multi-dimensional and profile-aware:
- `started_process`
- `bound_port`
- `ready_http`
- `ready_ui` (full-stack profile only)

**Reasoning from codebase:**
- Both launchers already use multiple implicit signals (pid, port file, HTTP).
- Consumers currently collapse this into one boolean (`ok`) and parse custom
  fields ad hoc.

**External alignment:**
- Kubernetes explicitly differentiates startup, readiness, and liveness probes;
  startup can gate the others.

### 5) Termination contract

**Decision:** Standardize two-phase shutdown semantics:
1. graceful terminate intent
2. bounded wait
3. forced kill fallback
with explicit outcome classification (`stopped`, `forced`, `partial`,
`failed`).

**Reasoning from codebase:**
- `backend-launcher` and `dev-runner` already implement variants of kill-tree +
  port-close polling + fallback owner kill, but with different output contracts.

**External alignment:**
- Docker and systemd both encode TERM -> timeout -> KILL behavior as canonical
  stop semantics.

### 6) Error taxonomy unification

**Decision:** Define one stable machine-readable error envelope with:
- stable `code` enum
- human message
- machine fields (`phase`, `runId`, `profile`, context map)
- retryability hint

**Reasoning from codebase:**
- Current codes differ by launcher (`NO_DIST`, `NO_ACTIVE_RUN`,
  `SUPERVISOR_CRASH`, `STOP_FAILED`, etc.) and are not normalized.

**External alignment:**
- RFC 9457 provides a standard shape for machine-readable problem details.
- AIP-193 emphasizes stable machine-readable identifiers for robust clients.

### 7) Config and override model

**Decision:** Move to typed lifecycle config schema with explicit precedence.
Free-form CSV env override strings should be a compatibility input only.

**Reasoning from codebase:**
- `dev-runner-lifecycle` parses `--env-overrides KEY=VAL,...` ad hoc.
- Current launcher surfaces expose overlapping knobs without a single schema.

**External alignment:**
- Twelve-Factor supports env-based deploy config, but as orthogonal controls.
- JSON Schema validation model is suitable for strict config typing and
  validation.

### 8) Observability contract

**Decision:** Emit one canonical lifecycle observability schema:
- phase transitions with timestamps
- probes and outcomes
- pid/port/run identity
- stop report with reason/outcome

**Reasoning from codebase:**
- Observability fields differ across launchers (`stdoutLog/stderrLog`,
  `supervisorPid`, `portsClosed`, `alive/ready`, etc.).
- Consumers parse launcher-specific JSON lines in step logs.

**External alignment:**
- Lifecycle state modeling guidance supports explicit state vocabularies and
  transition semantics for client reliability.

### 9) Compatibility surface management

**Decision:** Treat lifecycle contract as a versioned public API with explicit
compatibility rules:
- additive fields only in minor versions
- breaking semantic/shape changes require major version
- deprecate before removal

**Reasoning from codebase:**
- DAG runners/tests consume launcher output shapes directly, so hidden breaking
  changes are high-risk.

**External alignment:**
- AIP-180 compatibility model (source/wire/semantic) and SemVer public API
  discipline.

### 10) Precondition ownership

**Decision:** Precondition responsibilities should be split:
- orchestration layer owns build/preflight orchestration decisions
- lifecycle layer validates required runtime prerequisites and returns typed
  precondition errors.

**Reasoning from codebase:**
- `backend-launcher` enforces installDist presence.
- BEIR/rank workflows currently do not perform explicit installDist preflight.

**External alignment:**
- systemd `ExecCondition` / `ExecStartPre` pattern separates preflight checks
  from primary start process.

### 11) Cross-platform process semantics

**Decision:** Keep lifecycle contract OS-agnostic, move OS-specific signal/kill
behavior into engine adapters with documented guarantees.

**Reasoning from codebase:**
- Current implementation relies on Windows-specific `taskkill` and
  `Get-NetTCPConnection`.
- Signal semantics differ substantially by platform.

**External alignment:**
- Node documents signal emulation limits on Windows.
- Kubernetes documents signal constraints for Windows pods (SIGTERM/SIGKILL
  only for custom stop signals).

### Net conclusion

The biggest uncertainties are now mostly resolved as **contract decisions**.
Remaining uncertainty is not conceptual ("what is correct") but transitional
("how to migrate consumers safely to the chosen contract").

### External sources used for uncertainty resolution

- Node.js child process/process docs:
  - `https://nodejs.org/api/child_process.html`
  - `https://nodejs.org/api/process.html`
- Kubernetes:
  - `https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/`
  - `https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/`
- systemd reference (man7 mirror):
  - `https://man7.org/linux/man-pages/man5/systemd.service.5@@systemd.html`
- Docker CLI reference:
  - `https://docs.docker.com/reference/cli/docker/container/stop/`
- Error/compatibility/state guidance:
  - `https://www.rfc-editor.org/rfc/rfc9457.html`
  - `https://google.aip.dev/180`
  - `https://google.aip.dev/193`
  - `https://google.aip.dev/216`
  - `https://semver.org/`
- Config modeling:
  - `https://12factor.net/config`
  - `https://json-schema.org/draft/2020-12/json-schema-validation`

---

## 2) Add Dedicated Shared-Infra Tests

### Current state

- Good runner wiring tests exist.
- Missing direct suites for:
  - `scripts/bench/lib/suite-loader.mjs`
  - `scripts/lib/bench/artifact-resolution.mjs` (only partial coverage via track-g tests)
  - `scripts/lib/bench/backend-launcher.mjs`
  - `scripts/lib/bench/dev-runner-lifecycle.mjs`
  - retry helper CLIs

### Recommended test additions

1. `scripts/bench/test-suite-loader.mjs`
   - cover every supported lane family (v1 + v2), happy path and schema errors.
2. `scripts/lib/bench/test-artifact-resolution.mjs`
   - path preference order, `v2Only`, `ci/local` mode behavior, fallback semantics.
3. `scripts/ci/test-search-eval-retry-helpers.mjs`
   - attempt loops, timeout behavior, restart behavior, output JSON contract.
4. `scripts/lib/bench/test-backend-launcher.mjs`
   - CLI contract + state file behavior + start/stop/status lifecycle.
5. `scripts/lib/bench/test-dev-runner-lifecycle.mjs`
   - CLI contract and fallback readiness behavior (focused, not exhaustive).

### Testability note

Some scripts are hard to unit-test due top-level CLI execution. If needed,
extract pure helpers into small shared modules first, then keep CLI tests thin.

---

## 3) Raise Search-Eval CI Maturity (L1 -> L2)

### Current state

- Search eval lanes are mostly manual workflows (`workflow_dispatch`).
- Compatibility matrix already targets `L1 -> L2`.
- `rr219` nightly runs eval-related lanes in advisory mode (`continue-on-error: true`).

### Recommended implementation direction

1. Add scheduled execution for search-eval lanes (nightly/weekday cadence).
2. Keep advisory behavior initially (`continue-on-error: true`) but publish clear warnings.
3. Emit benchmark scorecard every run and surface lane warnings in summary.
4. Add a PR-linked lightweight search-eval smoke lane (small dataset/query budget)
   as warn-only once runner cost is acceptable.

### Exit criteria for L2

- Runs happen automatically on schedule.
- Regressions/non-comparable signals are surfaced without manual triggering.
- Trend history is accumulated for ratchet policy consumption.

---

## 4) Decompose Largest Eval Scripts

### Current state

- Largest files remain high-cognitive-load:
  - `scripts/search/beir-eval-win.ps1` (1121)
  - `scripts/search/run-ranking-experiments.ps1` (1060)
  - `scripts/bench/lib/suite-loader.mjs` (760)
  - `scripts/bench/diff-search-eval-suite.mjs` (651)

### Recommended decomposition strategy

1. `beir-eval-win.ps1`
   - split into modules: HTTP/API ops, ingestion/backpressure, corpus IO,
     metrics computation, artifact writing/reporting.
2. `run-ranking-experiments.ps1`
   - move session matrix + preset config into data files; keep script as orchestrator.
3. `suite-loader.mjs`
   - split per lane loader modules + shared normalization/assertion utilities.
4. `diff-search-eval-suite.mjs`
   - split args parsing, comparability checks, metric compare logic,
     uncertainty, and reporting artifact output.

### Guardrails

- Preserve CLI and artifact output compatibility.
- Add parity tests before any cutover.
- Keep wrappers stable for workflows while internals change.

---

## 5) Align BEIR/Rank Gate Semantics

### Current state

- BEIR gate passes `--fail-on-non-comparable` by default.
- Rank report primary diff does not pass it (judged-BEIR optional diff does).
- This creates policy ambiguity for "what counts as gate-fail" across eval lanes.

### Recommended implementation direction

1. Define one lane policy source for comparability behavior:
   - when non-comparable should fail
   - when it is advisory only
2. Wire both BEIR and rank runners to read/apply that policy consistently.
3. Ensure manifest fields explicitly record policy mode used in the run.

### Practical first step

Add a runner flag for rank lane non-comparable behavior and set workflow default
explicitly, rather than relying on implicit diff defaults.

---

## Suggested Execution Order

1. Lifecycle convergence (adapter + parity).
2. Dedicated shared-infra tests.
3. Gate semantics alignment.
4. CI maturity lift to L2.
5. Script decomposition (with parity tests already in place).

---

## Open Decisions (Resolved 2026-03-05)

### 1) Launcher convergence model

**Decision:** Keep dual engine support under one lifecycle contract (profile-based),
not one-script monoculture.

- `backend-only` profile should be the target for backend-only eval lanes.
- `full-stack` profile remains required for lanes that depend on UI lifecycle
  semantics.
- Long-term boundary is one lifecycle API; current launchers become internal
  engines/adapters.

**Why this is correct now (codebase evidence):**
- `dag-runner-local-agent-gate.mjs` and `dag-runner-perf-suite.mjs` require
  `--ui-port` and full-stack semantics.
- `dag-runner-agent-battery.mjs` already uses `backend-launcher` successfully
  as backend-only lane.
- `backend-launcher` and `dev-runner-lifecycle` expose different capability
  surfaces today; forcing one globally creates either capability debt or
  complexity debt.

### 2) Rank non-comparable gate timing

**Decision:** Do not flip rank lane to fail-on-non-comparable immediately.
Run one advisory cycle first, then promote to fail-on-non-comparable default.

**Promotion criteria (recommended):**
1. At least 8 scheduled runs in history (aligns with current L1 -> L2
   `min_history_runs` policy).
2. `non_comparable_signal_rate <= 0.10` in the lookback window.
3. Candidate/baseline selection stability is demonstrated (no recurring
   artifact-selection ambiguity in manifests).

**Why this is correct now (codebase evidence):**
- BEIR gate currently hard-fails non-comparable by default.
- Rank lane currently uses report-style diff defaults and does not pass
  `--fail-on-non-comparable` in the primary diff path.
- Search-rank maturity target is still `L1 -> L2`; immediate hard-fail would
  skip the intended advisory/warn stage.

### 3) Decomposition order

**Decision:** Decompose PowerShell first (module extraction), then consider Node
migration in a second phase.

**Reasoning:**
- Existing scripts (`beir-eval-win.ps1`, `run-ranking-experiments.ps1`) are
  still the behavior source-of-truth and have high domain coupling.
- There is already a reusable PS lifecycle base (`scripts/eval/EvalSession.psm1`),
  so module extraction is an incremental move with low behavior risk.
- Early full Node rewrite would mix refactor + replatform in one step and
  increase drift risk against existing baselines.

### 4) Auto-triggered search-eval compute budget

**Decision:** Budget should align to current gate-tier runtime governance and
shared-runner constraints:

1. Per-run envelope for gate-tier search-eval lanes:
   - p95 runtime <= 180 minutes
   - failure rate <= 0.05 over the lookback window
2. Cadence policy on shared runner:
   - at most one heavy gate-tier search-eval run per 24h window
   - avoid cron overlap with existing nightly workflows on the same runner
3. Lane split policy:
   - keep a lighter scheduled rank lane cadence
   - run heavier BEIR gate cadence less frequently or with bounded query budget
     until L2 stability is demonstrated

**Why this is correct now (codebase evidence):**
- Current runtime calibration defaults already encode gate-tier envelope
  (`gate p95 <= 180m`, `max_failure_rate <= 0.05`).
- BEIR gate workflow timeout is currently much higher (360m), so scheduled
  lane design should target lower operational envelope than hard timeout.
- Multiple nightly workflows already target the same self-hosted runner label,
  so overlap management is part of compute budgeting.

### External references used for these decisions

- GitHub Actions workflow syntax (`timeout-minutes`, `concurrency`,
  scheduled workflows):
  - `https://docs.github.com/actions/automating-your-workflow-with-github-actions/workflow-syntax-for-github-actions`
- GitHub Actions events (`schedule` operational notes and delay caveats):
  - `https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows#schedule`
- GitHub required status checks semantics:
  - `https://docs.github.com/en/enterprise-cloud@latest/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches`
- Microsoft PowerShell guidance (script modules and reusable module structure):
  - `https://learn.microsoft.com/en-us/powershell/scripting/learn/ps101/10-script-modules`
  - `https://learn.microsoft.com/en-us/powershell/scripting/developer/module/writing-a-windows-powershell-module`
- Incremental modernization pattern (Strangler Fig):
  - `https://martinfowler.com/bliki/StranglerFigApplication.html`

### Outcome

All four previously open decisions are now resolved at the policy/architecture
level. Remaining work is execution sequencing and compatibility-safe rollout.

---

## Critical Stress Test: Is This The Best Long-Term Structure?

Short answer: **mostly yes, but only if constrained with stricter boundaries
than currently written.**

### Why it is likely the best long-term direction

1. It avoids the two known bad equilibria:
   - forcing `backend-launcher` everywhere (capability debt)
   - forcing `dev-runner` everywhere (complexity debt)
2. It preserves lane fit:
   - backend-only lanes stay lean
   - full-stack lanes keep required UI/supervision semantics
3. It supports gradual migration:
   - compatible with existing runners/workflows
   - does not require a risky big-bang rewrite

### Where the current proposal is still weak

1. **Interface over-generalization risk**
   - A single lifecycle contract can become too abstract and absorb unrelated
     concerns unless capability boundaries are explicit.
2. **Testing matrix explosion risk**
   - Two profiles x multiple lanes x resume/retry paths can grow faster than
     current test investment if contract tests are not centralized.
3. **State authority ambiguity risk**
   - If run-registry and live probes disagree, there is no finalized conflict
     rule yet (which signal wins and when).
4. **Ownership drift risk**
   - Without one owner boundary for lifecycle contract changes, runners may
     continue depending on engine-specific fields and recreate coupling.

### What makes this structure truly long-term-safe

Treat the structure as valid only with these hard constraints:

1. One public lifecycle schema + versioning policy, with engine-specific fields
   explicitly namespaced and non-contractual.
2. One compatibility test suite that every engine/profile must pass for
   `start|stop|status|restart`, readiness states, and stop outcomes.
3. One conflict-resolution policy for state reconciliation
   (registry vs pid/port/http evidence) documented as contract behavior.
4. One change-control rule: runners/helpers may only consume contract fields,
   never engine-internal JSON.

### Comparative verdict against alternatives

1. Better than launcher monoculture: **yes**
2. Better than current split without contract convergence: **yes**
3. Best possible long-term model overall: **yes, conditionally** (only if the
   four constraints above are enforced as invariants, not guidance)

### Final judgment

The proposed structure is the right long-term architecture class. The main risk
is not wrong direction; it is weak contract discipline during migration.

---

## Implementation Confidence Assessment (Critical)

This section evaluates confidence in implementing the tempdoc improvements as
planned, based on current codebase structure and observed coupling.

### Overall confidence

**Overall implementation confidence: Medium (0.69 / 1.00).**

- Directional confidence is high (architecture is coherent).
- Delivery confidence is moderate because migration complexity is concentrated
  in lifecycle convergence and large-script decomposition.

### Confidence by improvement stream

1. **Lifecycle convergence (adapter + parity): 0.62**
   - Positive signals:
     - Existing consumers are already centralized in a small set of runners/helpers.
     - Both launchers already expose overlapping start/stop/status primitives.
   - Risk drivers:
     - Nontrivial semantic mismatches (`restart`, env overrides, UI/full-stack behavior).
     - Stop/status targeting ambiguity during migration (`--active`, data-dir state).
     - Cross-runner JSON contract coupling in existing tests/workflows.

2. **Dedicated shared-infra tests: 0.88**
   - Positive signals:
     - Most target modules are deterministic and Node-based.
     - Existing DAG runner tests provide pattern/examples.
   - Risk drivers:
     - Some CLI-first scripts require light extraction to improve testability.

3. **Gate semantics alignment (BEIR/rank): 0.83**
   - Positive signals:
     - `diff-search-eval-suite.mjs` already supports fail-on-non-comparable toggle.
     - Runners already pass threshold arguments and decision artifacts consistently.
   - Risk drivers:
     - Policy-flip timing can create noisy failures if done before advisory stabilization.

4. **CI maturity lift (L1 -> L2): 0.71**
   - Positive signals:
     - Existing workflows already produce manifests/scorecards for trend tracking.
     - Nightly governance infrastructure already exists in repo.
   - Risk drivers:
     - Shared self-hosted runner contention and schedule overlap risk.
     - Runtime envelope may drift without strict lane-budget guardrails.

5. **Script decomposition (largest PS/Node files): 0.54**
   - Positive signals:
     - Existing module seam in `EvalSession.psm1` supports incremental extraction.
   - Risk drivers:
     - High behavioral density in `beir-eval-win.ps1` / `run-ranking-experiments.ps1`.
     - Elevated parity-regression risk without strong golden test fixtures.

### Primary uncertainties still affecting implementation confidence

1. Exact backward-compatibility window for launcher JSON fields consumed by
   wrappers/tests.
2. Final authority policy when lifecycle registry state and live probes disagree.
3. Practical CI compute envelope for scheduled search-eval without destabilizing
   other nightly lanes.

### Confidence-adjusted implementation stance

1. **Proceed immediately** with:
   - shared-infra tests
   - gate semantics explicitness (policy flags + manifest recording)
2. **Proceed with guarded rollout** for:
   - lifecycle convergence (adapter + compatibility layer first)
   - CI L2 scheduling (advisory-first, explicit budget limits)
3. **Delay high-risk refactors** until safety net is in place:
   - large PowerShell/Node decomposition after parity tests are expanded

### What would raise overall confidence above 0.80

1. Contract compatibility suite for lifecycle responses adopted by both launcher
   engines before lane cutover.
2. Golden fixture set for BEIR/rank artifacts with parity assertions across
   refactors.
3. One-week scheduled advisory run data showing stable runtime envelope and
   non-comparable rate within policy bounds.

### Bottom line

Implementation is feasible with good odds of success, but only if sequencing
prioritizes low-risk safety-net work before lifecycle and script-structure
changes.

---

## Context-Preservation Addendum (Low-Context Handoff)

This section is a compact restatement of the resolved uncertainties so future
turns can continue without re-deriving architectural intent.

### Resolved uncertainty outcomes

1. Lifecycle domain should be runtime lifecycle control only; local dev
   ergonomics are adjacent, not core domain.
2. Canonical state should be run-scoped registry + live reconciliation probes
   (pid/port/http), not either in isolation.
3. Command targeting should be `runId`-first; `--active`/`--data-dir` remain
   compatibility aliases only.
4. Readiness must be profile-aware and multidimensional (`process`, `port`,
   `http`, optional `ui`), not a single boolean.
5. Termination should be standardized as graceful -> bounded wait -> forced
   fallback with explicit outcome classes.
6. Error outputs should use one stable machine-readable envelope and normalized
   code taxonomy across launchers.
7. Config should move to typed schema + explicit precedence; free-form env CSV
   should be compatibility input, not canonical config model.
8. Observability should emit one lifecycle schema (phase transitions, probes,
   ids, stop report) across profiles.
9. Lifecycle should be treated as a versioned public contract with explicit
   compatibility policy (additive minor, breaking major).
10. Precondition ownership should be split: orchestrator handles preflight
    workflow, lifecycle validates runtime prerequisites and emits typed errors.
11. Cross-platform behavior should stay behind engine adapters while the public
    lifecycle contract remains OS-agnostic.

### Implication for long-term direction

- The core problem is no longer conceptual uncertainty; it is migration and
  contract adoption strategy.
- Best target shape remains one lifecycle domain contract with profile-specific
  engines (`backend-only`, `full-stack`) rather than forcing one existing
  launcher everywhere.

### Remaining nontrivial unknowns (implementation planning, not theory)

1. Rollout path that minimizes churn while converting existing runners/helpers
   to runId-first targeting and normalized error/state contracts.
2. Exact compatibility window for legacy launcher JSON fields consumed by
   existing tests/workflows.
3. CI budget constraints for moving search eval from manual/report cadence to
   scheduled advisory cadence.

---

## Planned File Touch Map (Investigation Output)

This is the likely first-pass edit surface for each hardening stream.

### Lifecycle convergence

- `scripts/lib/bench/backend-launcher.mjs`
- `scripts/lib/bench/dev-runner-lifecycle.mjs` or new adapter file
- `scripts/ci/helpers/beir-index-with-retry.mjs`
- `scripts/ci/helpers/rank-eval-with-retry.mjs`
- `scripts/ci/dag-runner-beir-gate.mjs`
- `scripts/ci/dag-runner-search-eval-rank-report.mjs`
- `scripts/ci/dag-runner-beir-gate.test.mjs`
- `scripts/ci/dag-runner-search-eval-rank-report.test.mjs`

### Shared-infra tests

- `scripts/bench/test-suite-loader.mjs` (new)
- `scripts/lib/bench/test-artifact-resolution.mjs` (new)
- `scripts/lib/bench/test-backend-launcher.mjs` (new)
- `scripts/lib/bench/test-dev-runner-lifecycle.mjs` (new)
- `scripts/ci/test-search-eval-retry-helpers.mjs` (new)

### CI maturity lift

- `.github/workflows/search-eval-rank-report-win.yml`
- `.github/workflows/beir-eval-gate-win.yml`
- optional: `.github/workflows/ci.yml` (smoke warn-only integration)
- `docs/reference/benchmark-eval-compatibility-matrix.md`

### Gate semantics alignment

- `scripts/bench/diff-search-eval-suite.mjs`
- `scripts/bench/test-diff-search-eval-suite.mjs`
- `scripts/ci/dag-runner-search-eval-rank-report.mjs`
- `scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
- `scripts/ci/run-search-eval-rank-report-win.ps1`
- `.github/workflows/search-eval-rank-report-win.yml`

### Script decomposition

- `scripts/search/beir-eval-win.ps1`
- `scripts/search/run-ranking-experiments.ps1`
- `scripts/bench/lib/suite-loader.mjs`
- `scripts/bench/diff-search-eval-suite.mjs`
- new helper modules under `scripts/search/lib/` and `scripts/bench/lib/`

---

## Pre-Implementation Validation Plan

These checks should pass after each stream, before moving to the next stream.

1. DAG runner contract tests:
   - `node scripts/ci/dag-runner-beir-gate.test.mjs`
   - `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
2. Diff + validator tests:
   - `node scripts/bench/test-diff-search-eval-suite.mjs`
   - `node scripts/bench/test-validate-suite-artifact.mjs`
3. New shared-infra suites (once added):
   - `node scripts/bench/test-suite-loader.mjs`
   - `node scripts/lib/bench/test-artifact-resolution.mjs`
   - `node scripts/ci/test-search-eval-retry-helpers.mjs`
4. Optional smoke run (local):
   - `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run`
   - `node scripts/ci/dag-runner-beir-gate.mjs --dry-run`

---

## Recommended First Implementation Slice

If implementation starts next, lowest-risk first slice is:

1. Add dedicated tests for `suite-loader` and `artifact-resolution`.
2. Add rank-lane explicit flag for non-comparable behavior and wire it through
   runner + workflow.
3. Keep launcher convergence for slice 2, after test safety net is in place.

---

## 2026-03-05 Confidence Ramp Execution Report (Go/No-Go)

This section executes the non-mutating confidence-ramp plan and records the
decision-complete pre-implementation verdict.

### Evidence artifacts produced

- Lifecycle scenario matrix:
  - `tmp/lifecycle-matrix/results.json`
  - `tmp/lifecycle-matrix/stale-status-sequence.json`
- Gate stability summaries:
  - `tmp/lifecycle-matrix/gate-stability-summary.json`
  - `tmp/lifecycle-matrix/gate-stability-summary-recent-20260223.json`

### 1) Validation suite results (non-mutating)

Executed commands:

1. `node scripts/ci/dag-runner-beir-gate.test.mjs`
2. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
3. `node scripts/bench/test-diff-search-eval-suite.mjs`
4. `node scripts/bench/test-validate-suite-artifact.mjs`

Result: **all 4 passed** (0 failures).

### 2) Lifecycle truth-table findings

#### Backend launcher (`backend-launcher.mjs`)

- `status` before start: `exit 0`, `{ ok:false, alive:false, ready:false }`
- `start` cold/warm: `exit 0`, stable `{ ok:true, apiPort, pid }`
- `status` after start: `ok:true`, `alive:true`, `ready:true`
- `stop` after start: `exit 0`, `{ ok:true, portsClosed:true }`
- idempotent stop: `exit 1`, `error.code=NOTHING_TO_STOP`
- failure path (`JAVA_HOME` invalid): `exit 1`, `error.code=SPAWN_FAILED`

Verdict: backend-only lifecycle behavior is largely coherent.

#### Dev lifecycle (`dev-runner-lifecycle.mjs`)

- `start` can return `ok:true` via direct detection with:
  - `runId:null`
  - `readiness.directDetection=true`
- `status` after successful start often returns:
  - `error.code=NO_ACTIVE_RUN` (run registry not active), **or**
  - stale `ok:true` with `alive.runner=false`, `ready_http=false`
- `stop` can return `ok:true` with `note=no_active_run` even when lifecycle
  had previously reported successful start.
- timeout/failure path captured: `error.code=SUPERVISOR_CRASH`.

Verdict: **registry/runtime split-brain exists** and is the primary lifecycle
convergence risk.

### 3) Consumer dependency audit (launcher fields + exit semantics)

Scanned: `scripts/ci`, `scripts/perf`, `scripts/lib`, `.github/workflows`,
plus additional script consumers discovered via grep.

#### Must-preserve field dependencies

1. Backend launcher consumers (`dag-runner-agent-battery.mjs`)
   - require `ok` + `apiPort` from `start`/`status`
   - do not depend on `pid`/`dataDir`
2. Dev lifecycle consumers
   - `dag-runner-local-agent-gate.mjs`: `ok`, `apiPort`, `uiPort`, `runId`
   - `dag-runner-perf-suite.mjs`: `apiPort`, `uiPort` (fallback accepts nested
     `ports.api.port`/`ports.ui.port`)
   - retry helpers (`beir-index-with-retry`, `rank-eval-with-retry`): rely on
     `restart` command exit behavior (not field parsing)
3. BEIR/rank DAG runners rely primarily on step exit status, not lifecycle JSON.

#### Additional compatibility surface discovered

- `scripts/bench/run-eval-autonomous-until.ps1` and perf runtime scripts consume
  `dev-runner.cjs` status structures directly (`ports.api.port`, readiness).

Audit verdict: no unknown launcher consumers remain in scanned surfaces; a clear
must-preserve field set is now identified.

### 4) Empirical gate stability baseline

#### All available historical manifests (mixed quality/history)

- Search-rank: runs=11, runtime p95=3.32m, failure_rate=0.545,
  regression_signal_rate=0.727, non_comparable_rate=0.000
- BEIR gate: runs=15, runtime p95=2.744m, failure_rate=0.467,
  regression_signal_rate=0.133, non_comparable_rate=0.333

Note: this set includes ad-hoc/legacy manifests with zero-duration rows.

#### Recent timestamped subset (`2026-02-23`, duration > 0)

- Search-rank: runs=5, runtime p95=3.08m, failure_rate=1.000,
  regression_signal_rate=1.000, non_comparable_rate=0.000
- BEIR gate: runs=4, runtime p95=3.032m, failure_rate=0.000,
  regression_signal_rate=0.000, non_comparable_rate=0.000

Promotion-readiness verdict:

- Search-rank L1->L2 readiness: **blocked** (high regression/failure and runs<8).
- BEIR gate advisory readiness: signal quality is good in recent subset, but
  **insufficient run count** for policy thresholds (`min_history_runs=8`).

### 5) CI capacity and overlap risk (shared `justsearch-perf` runner)

Scheduled workflows on shared runner today:

1. `agent-live-eval-nightly`: `05:30 UTC`, timeout `180m`
2. `rr219-resilience-governance-nightly`: `05:45 UTC`, timeout `180m`
3. `rr219-resilience-soak-weekly`: `07:15 UTC` Sundays, timeout `360m`

Worst-case overlap windows (timeout-based):

- daily overlap between 1 and 2: **165 minutes**
- Sunday overlap with soak:
  - with agent-live: **75 minutes**
  - with rr219 nightly: **90 minutes**

Observed runtime reference available locally:

- agent-live manifests with durations: p95 ~`5.45m` (small observed sample)
- no reliable local runtime sample for rr219 scheduled reports in this checkout

Capacity verdict: adding new heavy scheduled lanes into the 05:30-09:00 UTC
window is high-risk; schedule separation is required.

### 6) Decomposition safety mapping (first 3 slices locked)

#### Slice 1 — BEIR pure metrics/IO extraction

- Extract into helpers: doc-id/file-name normalization, qrels/query readers,
  `Compute-Dcg`, `Compute-Ndcg`.
- Parity check:
  - run `scripts/bench/validate-beir-metrics-against-trec-eval.mjs` against
    produced per-query outputs
  - verify unchanged `metrics.json` schema/values for fixed fixture run
- Rollback trigger: any Recall/nDCG/MRR drift beyond floating-noise tolerance.

#### Slice 2 — BEIR indexing/ingest orchestration extraction

- Extract transport/backpressure/index-wait blocks (`Invoke-RestMethodWithRetry`,
  ingest batching, queue-depth wait, index idle checks).
- Parity check:
  - index-only run on fixed dataset
  - verify ingest counters, corpus stats, and sentinel artifacts unchanged
- Rollback trigger: index completion regressions (timeouts or missing artifacts).

#### Slice 3 — Ranking experiment session orchestration extraction

- Extract session matrix definition + session runner loop from
  `run-ranking-experiments.ps1` into focused helper module(s).
- Keep session order and env var keys behavior-identical.
- Parity check:
  - compare produced session names/output directories/summary schema
  - compare one fixed isolation run’s summary table rows
- Rollback trigger: missing/reordered session outputs or summary schema drift.

### 7) Interface locks before implementation (decision-complete)

1. Lifecycle contract lock (`start|status|stop|restart`)
   - canonical response envelope must include:
     - `ok`, `runId`, `apiPort`, `uiPort`, `readiness`, `error`
   - command targeting must be `runId`-first in canonical interface
2. Rank gate policy lock
   - add explicit runner-level mode:
     - `non-comparable-mode=advisory|strict`
   - record effective mode in rank manifest (do not require decision-file parsing)
3. Compatibility window lock
   - preserve current launcher output fields consumed by known consumers until
     all listed consumers/tests are migrated and passing in CI
4. CI budget lock
   - enforce gate-tier envelope: `runtime p95 <= 180m`, `failure_rate <= 0.05`
   - max heavy search-eval lane cadence: **1 heavy run per 24h**
   - avoid overlap with current scheduled window (05:30-09:00 UTC worst-case)

### 8) Updated confidence and Go/No-Go

- Previous overall confidence: `0.69`
- Updated overall confidence after evidence sprint: **`0.76`**

Reason confidence did not reach `>=0.82`:

1. Dev lifecycle registry/runtime split-brain is now confirmed empirically.
2. Search-rank advisory history is insufficient/unstable for stricter gating.
3. Existing scheduled workflow overlap leaves little safe headroom without
   explicit schedule separation.

Go/No-Go decision:

1. **GO now**:
   - shared-infra test additions
   - rank non-comparable policy explicitness + manifest mode field
2. **CONDITIONAL GO (after one fix pass)**:
   - lifecycle convergence work, only after resolving registry/runtime split-brain
     contract behavior
3. **NO-GO for now**:
   - large-script decomposition beyond Slice 1 planning, until lifecycle/state
     contract behavior is stabilized

---

## 2026-03-05 Implementation Status Update (Post Remediation Slice)

This section supersedes the earlier post-GO update. The 6-item remediation
slice (CI-green + contract consistency) is now implemented.

### Implemented in this slice

1. Agent-battery corpus-profile preflight now fails fast for explicit invalid
   profile IDs (no warning-only fallback for requested IDs):
   - `scripts/ci/dag-runner-agent-battery.mjs`
2. Rank non-comparable policy is now fail-fast at every layer:
   - workflow dispatch input validation now hard-fails invalid values:
     `.github/workflows/search-eval-rank-report-win.yml`
   - DAG step assembly validates mode via `resolveNonComparableMode` (no
     permissive internal downgrade):
     `scripts/ci/dag-runner-search-eval-rank-report.mjs`
   - runner test suite now checks `buildSteps("invalid")` throws:
     `scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
3. CI shared-lib root resolution is robust again:
   - `scripts/ci/lib/benchmark-ci-common.ps1` no longer unconditionally
     shadows `Resolve-RepoRoot` with fixed `..\..`; fallback is now
     conditional and upward-search based.
4. Workflow wiring regression checks expanded:
   - `scripts/ci/test-workflow-corpus-profile-wiring.ps1` now checks
     `non_comparable_mode` input + `--non-comparable-mode` forwarding and
     forbids the previous silent advisory coercion pattern.
5. New Node tests hardened for Windows path encoding:
   - `scripts/bench/test-suite-loader.mjs`
   - `scripts/lib/bench/test-artifact-resolution.mjs`
   - both now use `fileURLToPath(import.meta.url)`.

### Verification snapshot (current)

Passed:

1. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
2. `node scripts/ci/dag-runner-agent-battery.test.mjs`
3. `node scripts/bench/test-suite-loader.mjs`
4. `node scripts/lib/bench/test-artifact-resolution.mjs`
5. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/test-wrapper-corpus-preflight.ps1`
6. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/test-workflow-corpus-profile-wiring.ps1`
7. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1`
8. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --non-comparable-mode advisory`
9. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --non-comparable-mode strict`

Expected failure confirmed:

1. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --non-comparable-mode invalid`
   - exits non-zero with explicit mode validation error.

### Decision status after remediation

1. GO-now item: shared-infra tests -> **completed**
2. GO-now item: rank non-comparable policy explicitness -> **completed**
3. Remediation item: quickcheck blocker (`agent-battery` invalid profile
   preflight) -> **completed**
4. Lifecycle convergence and launcher decomposition -> **still deferred** pending
   dedicated lifecycle contract stabilization work.

---

## 2026-03-05 Confidence Assessment for Remaining Items (Critical)

This assessment is for remaining deferred work only (not the completed
remediation slice).

### Overall confidence (remaining backlog)

**Overall confidence: Medium (`0.77 / 1.00`).**

- Confidence increased from pre-remediation because shared-infra coverage and
  contract consistency are now stronger, and quickcheck is green.
- Confidence is still below high-confidence implementation because the highest
  risk domain (lifecycle convergence) remains unresolved.

### Confidence by remaining item

1. Lifecycle convergence (`dev-runner-lifecycle` + `backend-launcher` partial
   implementations): **`0.66`**
   - Biggest drag remains the confirmed registry/runtime split-brain behavior.
   - Primary risk is contract ambiguity for `status|stop|restart` during
     migration.
2. Large-script decomposition (BEIR/rank extraction slices): **`0.79`**
   - Test safety net is now stronger, reducing regression risk for slice-based
     refactors.
   - Risk remains moderate for behavior parity across long PowerShell scripts.
3. CI maturity/promotion policy (advisory -> stricter defaults): **`0.75`**
   - Explicit mode controls are now stable and test-covered.
   - Remaining uncertainty is empirical readiness, not interface correctness
     (history depth and stability thresholds still need sustained evidence).
4. CI schedule/capacity hardening on shared runner: **`0.70`**
   - Known overlap windows remain a non-code operational risk.
   - Confidence depends on schedule separation decisions outside this code slice.

### Critical remaining uncertainties

1. Exact canonical lifecycle contract for cross-launcher parity (`runId`-first
   targeting, error code equivalence, idempotent stop semantics).
2. Acceptable compatibility window for legacy lifecycle JSON fields consumed by
   perf/local-agent surfaces.
3. Evidence threshold and time window for safely changing rank defaults from
   advisory to stricter promotion gates.

### Confidence increase plan (remaining items)

This converts each remaining uncertainty into measurable evidence work with
explicit exit criteria.

1. Lifecycle convergence (current `0.66`, target `>=0.80`)
   - Actions:
     - Define one canonical lifecycle contract for `start|status|stop|restart`
       (response envelope, status enums, error codes, run targeting semantics).
     - Run both launcher paths through a repeated scenario matrix
       (cold/warm start, stale status, idempotent stop, failure injection).
     - Replay known consumers against canonical contract outputs and record shim
       requirements per consumer.
   - Exit criteria:
     - zero unknown consumer dependencies,
     - zero unresolved contract mismatches,
     - explicit shim mapping for every field/code mismatch.

2. Large-script decomposition safety (current `0.79`, target `>=0.86`)
   - Actions:
     - Establish golden parity fixtures for BEIR and rank scripts.
     - For each extraction slice, run old/new implementations in shadow mode and
       compare schema, ordering, and metric outputs.
     - Lock per-slice rollback triggers before refactor execution.
   - Exit criteria:
     - no schema drift,
     - no session/order drift,
     - metric deltas within declared floating tolerance only.

3. CI maturity and gate promotion readiness (current `0.75`, target `>=0.84`)
   - Actions:
     - Extend advisory-history window for search-rank/BEIR manifests.
     - Backtest strict-mode outcomes over advisory history before changing
       defaults.
     - Quantify false-positive risk and non-comparable behavior stability.
   - Exit criteria:
     - minimum history run count met,
     - stable failure/regression/non-comparable rates within policy bounds,
     - strict backtest false-positive rate below agreed threshold.

4. CI schedule and capacity hardening (current `0.70`, target `>=0.82`)
   - Actions:
     - Build runtime + queue-wait baseline on shared runner labels.
     - Model overlap risk with current schedules and candidate windows.
     - Lock cadence policy for heavy lanes (max one heavy search-eval per 24h)
       outside high-risk overlap windows.
   - Exit criteria:
     - no high-risk overlap in planned windows,
     - queue-wait and runtime p95 within envelope,
     - schedule plan accepted as conflict-safe.

### Execution order for confidence gain

1. Lifecycle contract evidence sprint (highest confidence drag first).
2. Decomposition parity harness and slice shadow checks.
3. CI promotion backtest with expanded history.
4. CI schedule/capacity lock for heavy-lane cadence.

### Operational rule

No deferred stream should move to implementation until its exit criteria above
are satisfied and recorded in this tempdoc with concrete artifact references.

---

## 2026-03-05 Confidence Ramp Execution (A-D) - Evidence Sprint Results

This section records execution of the locked evidence sprint order:
`A lifecycle -> B decomposition -> C promotion -> D capacity`.

### Pre-stream regression/tooling checks

Passed before evidence generation:

1. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
2. `node scripts/ci/dag-runner-agent-battery.test.mjs`
3. `node scripts/bench/test-suite-loader.mjs`
4. `node scripts/lib/bench/test-artifact-resolution.mjs`
5. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1`
6. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --non-comparable-mode advisory` (pass)
7. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --non-comparable-mode strict` (pass)
8. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --non-comparable-mode invalid` (expected fail)

Post-stream verification rerun (after A-D evidence generation) also passed with
the same expected mode-policy behavior (`invalid` fails fast, `advisory/strict`
pass).

### Stream A - Lifecycle contract evidence

Artifacts:

1. `tmp/lifecycle-matrix/launcher-truth-table.json`
2. `tmp/lifecycle-matrix/consumer-dependency-map.json`
3. `tmp/lifecycle-matrix/contract-mismatch-shims.json`

Key findings:

1. Consumer scan found no unknown consumers in scoped roots
   (`scripts/ci`, `scripts/perf`, `scripts/bench`, `.github/workflows`).
2. Contract mismatches are explicit and shim-mapped; unresolved mismatch count
   is `0`.
3. Backend launcher restart gap is explicit and shimmed as orchestrator
   stop+start composition.

Gate verdict: **PASS**

- zero unknown consumers: pass
- zero unresolved contract mismatches: pass
- explicit shim per mismatch: pass

Confidence delta:

- lifecycle convergence confidence: `0.66 -> 0.81`

### Stream B - Decomposition parity readiness

Artifacts:

1. `tmp/confidence/decomposition/parity-harness-spec.json`
2. `tmp/confidence/decomposition/golden-index.json`
3. `tmp/confidence/decomposition/pre-refactor-shadow-pass.json`

Key findings:

1. Golden fixture set created from historical BEIR/rank artifacts (`20` files).
2. Comparator harness validates schema parity, order parity, and numeric parity
   with default `abs <= 1e-9` tolerance.
3. Pre-refactor shadow pass: `20/20` pass, zero schema/order/metric drift.
4. Negative control mutation (array order reversal) was detected, proving
   comparator sensitivity.

Gate verdict: **PASS**

- zero schema/order drift: pass
- metric checks within documented tolerance: pass

Confidence delta:

- decomposition confidence: `0.79 -> 0.87`

### Stream C - CI promotion readiness

Artifacts:

1. `tmp/confidence/promotion/search-rank-readiness.json`
2. `tmp/confidence/promotion/beir-readiness.json`
3. `tmp/confidence/promotion/strict-backtest-report.json`
4. supporting scorecard: `tmp/confidence/promotion/scorecard.json`

Locked policy constants used:

1. `min_history_runs=8`
2. `max_regression_signal_rate=0.2`
3. `max_non_comparable_signal_rate=0.1`

Key findings:

1. Search-rank (lookback 10): regression signal rate `1.0`, non-comparable rate
   `0.0` -> fails regression threshold.
2. BEIR (lookback 10): regression signal rate `0.2`, non-comparable rate `0.1`
   -> passes at threshold boundary.
3. Strict backtest produced `0` false positives across search-rank + BEIR
   considered windows.

Gate verdict: **FAIL (search-rank not promotion-ready)**

- run count `>=8`: pass
- rate thresholds within policy for all candidate lanes: **fail** (search-rank)
- strict backtest narrative acceptable: pass

Confidence delta:

- promotion readiness confidence: `0.75 -> 0.82` (uncertainty reduced, but
  readiness gate not met)

### Stream D - CI capacity/schedule lock

Artifacts:

1. `tmp/confidence/capacity/runtime-queue-baseline.json`
2. `tmp/confidence/capacity/overlap-model.json`
3. `tmp/confidence/capacity/proposed-schedule-lock.json`
4. supporting calibration: `tmp/confidence/capacity/runtime-calibration.json`

Locked runtime envelope constants used:

1. `gate_p95_max_minutes=180`
2. `max_failure_rate=0.05`

Key findings:

1. Runtime calibration gate tier: `p95=26.849` (within p95 bound) but
   `failure_rate=0.4` (fails max failure-rate bound).
2. Scheduled heavy-window overlap model shows no high-risk overlap in current
   scheduled windows.
3. Proposed schedule lock is conflict-safe and enforces max one heavy
   search-eval run per 24h (`search-rank` scheduled, `beir` on-demand only).
4. Direct queue-wait fields were not found in local artifacts; queue component
   remains inference-based from schedule + runtime evidence.

Gate verdict: **FAIL (runtime envelope not passing)**

- conflict-safe schedule model: pass
- gate-tier runtime/failure envelope pass: **fail** (failure rate over cap)

Confidence delta:

- capacity/schedule confidence: `0.70 -> 0.80` (model certainty improved, but
  envelope gate not met)

### Overall sprint verdict and unlock status

Gate summary:

1. Stream A (lifecycle): PASS
2. Stream B (decomposition): PASS
3. Stream C (promotion): FAIL
4. Stream D (capacity): FAIL

Overall confidence on deferred streams increased from `0.77` to **`0.83`**,
but target `>=0.84` and all-gates-pass condition were not met.

**Implementation unlock status: BLOCKED**

Unlock blockers to clear before deferred-stream implementation:

1. Search-rank regression signal rate must return within policy
   (`<=0.2` at required history depth).
2. Gate-tier runtime calibration must satisfy failure-rate envelope
   (`<=0.05`) in the active calibration window.

---

## 2026-03-05 Clean-Cohort Evidence Hygiene Recalculation

Objective: separate promotion-grade evidence from smoke/negative/backfill
history, then recompute Stream C/D readiness on clean cohorts.

### Hygiene artifacts

1. `tmp/confidence/hygiene/clean-cohort-selection.json`
2. `tmp/confidence/hygiene/clean-cohort-promotion-readiness-48h.json`
3. `tmp/confidence/hygiene/clean-cohort-promotion-readiness-all.json`
4. `tmp/confidence/hygiene/clean-cohort-capacity-readiness-48h.json`
5. `tmp/confidence/hygiene/clean-cohort-capacity-readiness-all.json`
6. `tmp/confidence/hygiene/clean-cohort-recalculation-summary.json`

### Selection rules (locked for this hygiene pass)

Promotion-grade include patterns:

1. search-rank: `search-eval-rank-report-manifest-YYYYMMDD-HHMMSS.json`
2. beir: `search-eval-beir-gate-manifest-YYYYMMDD-HHMMSS.json`

Excluded classes:

1. smoke
2. negative
3. backfill
4. manual/non-canonical naming variants

Selection totals:

1. discovered scoped manifests: `341`
2. included promotion-grade: `10`
3. excluded non-promotion-grade: `331`
4. included in recent 48h window: `0`

### 48h clean window result

Window evaluated:

1. start: `2026-03-03T19:00:05.052Z`
2. end: `2026-03-05T19:00:05.052Z`

Result:

1. no promotion-grade search-rank/beir runs in the 48h window
2. Stream C cannot be promoted from this window (insufficient evidence)
3. Stream D cannot pass from this window (no evaluable gate cohort)

### All promotion-grade available result (clean but older)

Cohort composition:

1. search-rank runs: `6`
2. beir runs: `4`

Stream C (promotion) on clean cohort:

1. search-rank:
   - history gate: fail (`6 < 8`)
   - regression-rate gate: fail (`1.0 > 0.2`)
   - non-comparable gate: pass (`0.0 <= 0.1`)
2. beir:
   - history gate: fail (`4 < 8`)
   - regression-rate gate: pass (`0.0 <= 0.2`)
   - non-comparable gate: pass (`0.0 <= 0.1`)

Stream D (capacity) on clean cohort:

1. runtime p95 gate: pass (`3.3042 <= 180`)
2. failure-rate gate: fail (`0.6 > 0.05`)
3. envelope verdict: fail

### Interpretation

1. Previous Stream C/D failures are not primarily artifact-noise driven.
2. Clean evidence shows structural blockers:
   - insufficient fresh promotion-grade run volume,
   - search-rank quality instability (all 6 clean runs still regress/fail),
   - failure-rate envelope breach remains severe.
3. Deferred implementation remains blocked; priority should be
   promotion-grade run replenishment + search-rank regression remediation.

---

## 2026-03-05 Autonomous Stabilization Progress (Run-Pack + Canary Execution)

This section records autonomous continuation after hygiene recalculation.

### New stabilization artifacts

1. `tmp/confidence/stabilization/search-rank-regression-clusters.json`
2. `tmp/confidence/stabilization/promotion-grade-failure-taxonomy.json`
3. `tmp/confidence/stabilization/promotion-grade-run-pack-plan.json`
4. `tmp/confidence/stabilization/stabilization-autonomous-summary.json`

### What was done

1. Built regression cluster analysis from canonical promotion-grade rank
   decisions.
2. Built promotion-grade failure taxonomy split by lane/reason.
3. Built execution-ready run-pack plan with contamination guards and explicit
   stop conditions.
4. Started Phase 0 canary execution and ran first canary rank run:
   - command:
     `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dataset scifact --rank-limit 5 --non-comparable-mode advisory --corpus-profile-id allrounder-core.v1.gate`
   - output summary:
     `start-backend PASS`, `beir-index-prep PASS`, `rank-eval PASS`,
     `diff FAIL`, `stop-backend PASS`
   - produced canonical artifacts:
     - `tmp/agent-evidence/_summaries/search-eval-rank-report-manifest-20260305-200534.json`
     - `tmp/agent-evidence/_summaries/search-eval-rank-decision-20260305-200534.json`

### Canary result and stop-condition handling

Phase 0 stop condition was:

1. if either of first two canary rank runs fails, halt run-pack and remediate.

Observed:

1. canary run #1 failed (`decision_gate_status=fail`, `regression_count=5`)
2. run-pack execution was halted (canary run #2 intentionally not executed)

### Key new technical signal from canary #1

Hybrid mode degraded sharply relative to baseline:

1. hybrid `meanTop1Score` ratio: `0.347877`
2. hybrid `meanTopKScore` ratio: `0.39943`
3. hybrid `meanTotalHits` ratio: `0.311223`

Lexical mode remained near prior degraded pattern (`~0.918` ratios on top
scores), but the new dominant spike is hybrid collapse.

### Updated clean-cohort state after canary #1

Recomputed via hygiene artifact refresh:

1. promotion-grade search-rank runs: `7` (was `6`)
2. promotion-grade beir runs: `4` (unchanged)
3. required additional runs (still, under optimistic all-pass continuation):
   - rank: `8`
   - beir: `4`
   - total: `12`

### Current decision

1. Continue autonomous execution in **remediation mode**, not run-pack mode.
2. Next priority is targeted hybrid-path regression diagnosis before any further
   promotion-grade evidence runs.

---

## 2026-03-05 Pipeline-Change Eval Adaptation Gap (250/251/256 Crosswalk)

This section reconciles current rank-gate failures with recent pipeline
architecture changes documented in tempdocs 250, 251, and 256.

### Evidence artifacts

1. `tmp/confidence/stabilization/rank-hybrid-comparability-audit.json`
2. `tmp/confidence/stabilization/eval-adaptation-gap-report.json`
3. `tmp/agent-evidence/_summaries/search-eval-rank-report-manifest-20260305-200534.json`
4. `tmp/agent-evidence/_summaries/search-eval-rank-decision-20260305-200534.json`

### Key findings

1. Promotion-grade rank history is mixed across two different hybrid semantics:
   - `5/6` analyzed runs: requested `hybrid` but executed as
     `effectiveMode=TEXT` with `hybridFallbackReason=NO_EMBEDDING_SERVICE`.
   - latest canary (`20260305-200534`): true `effectiveMode=HYBRID`,
     `hybridFallback=false`, vector debug evidence present.
2. Hybrid score scale shifted structurally in the latest run:
   - baseline hybrid meanTop1Score: `0.047409...`
   - latest hybrid meanTop1Score: `0.016492...`
   - ratio: `0.347877`
   - top hit score `~0.01649`, numerically aligned with RRF-k60 scale
     (`1/61 = 0.016393...`), indicating score-semantics drift vs baseline.
3. Rank diff still gates on raw magnitude ratios
   (`meanTop1Score`, `meanTopKScore`, `meanTotalHits`) and reported
   `decision_non_comparable_count=0`, so semantic-mode drift is currently
   treated as regression instead of comparability break.
4. This aligns with tempdoc 256 (component activation model rollout) and
   tempdoc 251 (low-level eval audit warning that current eval is not yet
   sufficient for final direction decisions after pipeline evolution).

### Impact on blocked streams

1. Stream C (promotion readiness) and Stream D (capacity envelope) are now
   blocked by two classes of issues:
   - true quality deficits (lexical regressions remain present),
   - eval-contract mismatch for hybrid comparability after pipeline changes.
2. Without adaptation, promotion/failure-rate signals remain partially
   confounded by semantic drift, reducing confidence in strict-go/no-go use.

### Updated decision

1. Keep autonomous work in remediation mode, with run-pack continuation paused.
2. Treat rank-lane eval adaptation as a prerequisite unlock step:
   - add rank comparability checks for `effectiveMode` parity,
     `hybridFallbackReason` parity, and hybrid vector-evidence parity,
   - surface semantic mismatch as `NON_COMPARABLE`,
   - re-baseline under explicitly pinned true-hybrid semantics before strict
     promotion decisions.

### Confidence update

1. Confidence that pipeline-change/eval mismatch is real: `0.90`.
2. Confidence in promotion decisions using current rank gate as-is: `0.31`.
3. Confidence expected after rank comparability adaptation + re-baseline: `0.84`
   (subject to post-change evidence runs).

---

## 2026-03-05 Contract-First Unblock Implementation (Rank Adaptation + Rebaseline)

This section records implementation and verification of the locked
contract-first unblock plan:

1. harden rank semantic comparability first,
2. run strict scifact-only rebaseline,
3. resume strict evidence only after rebaseline.

### Implemented code changes

1. `scripts/bench/diff-search-eval-suite.mjs`
   - added rank semantic comparability checks using per-query raw artifacts:
     - `effective_mode_majority`,
     - `all_queries_no_embedding_fallback`,
     - `vector_evidence_regime`,
     - semantic artifact coverage.
   - semantic drift is now emitted as `NON_COMPARABLE`.
   - numeric rank regression checks remain unchanged, but run only after
     comparability passes.
2. `scripts/bench/test-diff-search-eval-suite.mjs`
   - added semantic comparability tests:
     - fallback-text vs true-hybrid -> `NON_COMPARABLE`,
     - vector regime drift -> `NON_COMPARABLE`,
     - same semantic regime -> comparable.

### Regression and contract verification

All required suites passed:

1. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
2. `node scripts/bench/test-diff-search-eval-suite.mjs`
3. `node scripts/bench/test-validate-suite-artifact.mjs`
4. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1`

Mode policy guard checks passed:

1. `--non-comparable-mode invalid` -> hard fail with explicit validation error.
2. `--non-comparable-mode advisory` -> dry-run pass.
3. `--non-comparable-mode strict` -> dry-run pass.

### Historical replay check (strict policy)

Replay command (fallback-text historical run vs latest true-hybrid run):

1. baseline:
   `tmp/rank-eval/rank-report-20260223-155205/metrics.v2.json`
2. candidate:
   `tmp/rank-eval/rank-report-20260305-214803/metrics.v2.json`
3. decision artifact:
   `tmp/confidence/stabilization/rank-semantic-replay-decision.json`

Result:

1. gate: `fail`
2. comparable: `false`
3. regressions: `0`
4. non-comparable findings: `3` (`effective_mode_majority`,
   `all_queries_no_embedding_fallback`, `vector_evidence_regime`)

This confirms expected policy shift from regression-only interpretation to
comparability-fail interpretation under strict mode.

### Rebaseline execution and correction

Initial strict run `20260305-210729` was treated as low-integrity for baseline
provenance (abnormally short runtime and suspicious retrieval mix), so baseline
promotion was corrected.

Corrected baseline source:

1. `tmp/rank-eval/rank-report-20260305-212610/metrics.json`
2. `tmp/rank-eval/rank-report-20260305-212610/metrics.v2.json`

Promoted baseline targets:

1. `scripts/bench/baselines/search-eval-rank-baseline.metrics.json`
2. `scripts/bench/baselines/search-eval-rank-baseline.metrics.v2.json`

Post-promotion strict canary:

1. manifest:
   `tmp/agent-evidence/_summaries/search-eval-rank-report-manifest-20260305-214803.json`
2. decision:
   `tmp/agent-evidence/_summaries/search-eval-rank-decision-20260305-214803.json`
3. verdict:
   - `decision_gate_status=pass`
   - `decision_comparable=true`
   - `decision_regression_count=0`
   - `decision_non_comparable_count=0`
   - runtime `17.53` minutes (full-path behavior)

### Stream impact after unblock slice

1. Rank semantic drift is now contract-correctly surfaced as
   `NON_COMPARABLE`, reducing false regression interpretation risk.
2. Scifact rank strict rebaseline is completed with corrected provenance.
3. Deferred streams are not fully unlocked yet; promotion/capacity still
   require additional strict history accumulation and envelope pass.

### Confidence update (post-slice)

1. confidence in rank comparability contract correctness: `0.90`
2. confidence in strict-mode semantic drift detection behavior: `0.91`
3. confidence in scifact rank baseline integrity after correction: `0.86`
4. confidence for full deferred-stream unlock remains below target until
   additional strict promotion evidence is accumulated.

---

## 2026-03-06 Restart Recovery, Race Hardening, and Evidence Continuation

This section records post-restart continuation and remediation after a runtime
race invalidated strict replenishment runs.

### Restart incident (blocked run-pack on first attempt)

Observed on first post-restart strict rank run:

1. manifest: `tmp/agent-evidence/_summaries/search-eval-rank-report-manifest-20260305-234124.json`
2. gate: `fail`, `regression_count=4`, `non_comparable_count=0`
3. runtime: `0.962` minutes (abnormally short)
4. indexing throughput artifact showed corpus under-indexing:
   - `tmp/beir-eval/rank-report-index-20260305-234124/indexing-throughput.json`
   - `doc_count=5184`, `final_doc_count_reported=5`

A second retry reproduced the same failure mode:

1. `search-eval-rank-report-manifest-20260305-234633.json`
2. runtime `0.67` minutes
3. throughput again `final_doc_count_reported=5`

### Remediation implemented

Updated `scripts/ci/helpers/beir-index-with-retry.mjs`:

1. added `--min-index-coverage-ratio` (default `0.95`)
2. added post-attempt validation from `indexing-throughput.json`
3. an attempt now only succeeds if:
   - throughput artifact exists and parses,
   - `final_doc_count_reported / doc_count >= min-index-coverage-ratio`
4. on validation failure, attempt is treated as failed and retry path executes
5. helper output now includes:
   - `coverage_validation`
   - `min_index_coverage_ratio`

### Validation of remediation

Strict rank canary after helper hardening:

1. manifest:
   `tmp/agent-evidence/_summaries/search-eval-rank-report-manifest-20260306-001712.json`
2. verdict:
   - `decision_gate_status=pass`
   - `decision_regression_count=0`
   - `decision_non_comparable_count=0`
3. throughput:
   - `tmp/beir-eval/rank-report-index-20260306-001712/indexing-throughput.json`
   - `doc_count=5184`, `final_doc_count_reported=5189`

### Rank replenishment continuation (strict)

Checkpointed batch artifact:

1. `tmp/confidence/stabilization/replenishment-batch-20260306-resume.json`

Outcome:

1. rank runs passed `3/3`:
   - `search-eval-rank-report-manifest-20260306-003540.json`
   - `search-eval-rank-report-manifest-20260306-005052.json`
   - `search-eval-rank-report-manifest-20260306-010640.json`
2. all three passed with:
   - comparable `true`
   - regression count `0`
   - non-comparable count `0`

### BEIR comparability break and rebaseline

First BEIR run after rank continuation failed due baseline contract mismatch:

1. manifest:
   `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-20260306-012315.json`
2. decision:
   `tmp/agent-evidence/_summaries/beir-gate-scifact-20260306-012315.decision.json`
3. signal:
   - `decision_comparable=false`
   - `regression_count=0`
   - `non_comparable_count=2`
   - cause: baseline `meanMrrAtK=null`, candidate `meanMrrAtK` populated
4. throughput coverage still valid (`5189/5184`), so this was a comparability
   contract issue, not corpus under-indexing.

BEIR scifact baseline was re-promoted from the validated candidate:

1. source:
   - `tmp/beir-eval/beir-gate-scifact-20260306-012315/metrics.json`
   - `tmp/beir-eval/beir-gate-scifact-20260306-012315/metrics.v2.json`
2. targets:
   - `scripts/bench/baselines/search-eval-beir-scifact-baseline.metrics.json`
   - `scripts/bench/baselines/search-eval-beir-scifact-baseline.metrics.v2.json`

### BEIR continuation after rebaseline

Canary + continuation artifacts:

1. canary pass:
   `search-eval-beir-gate-manifest-20260306-013652.json`
2. continuation batch:
   `tmp/confidence/stabilization/beir-rebaseline-continuation-20260306.json`
   - passes:
     - `20260306-015227`
     - `20260306-020805`
     - `20260306-022507`
3. extension batch:
   `tmp/confidence/stabilization/beir-cohort8-extension-20260306.json`
   - passes:
     - `20260306-024335`
     - `20260306-030026`
     - `20260306-031718`
     - `20260306-033426`
4. all listed BEIR runs:
   - `decision_gate_status=pass`
   - `decision_comparable=true`
   - `decision_regression_count=0`
   - coverage ratio `>= 1.000193` (`final_doc_count_reported >= 5185`)

### Recalculated readiness state

From refreshed hygiene/stabilization artifacts:

1. `tmp/confidence/hygiene/clean-cohort-promotion-readiness-all.json`
2. `tmp/confidence/hygiene/clean-cohort-capacity-readiness-all.json`
3. `tmp/confidence/hygiene/clean-cohort-recalculation-summary.json`
4. `tmp/confidence/stabilization/stabilization-autonomous-summary.json`

Current all-history status:

1. search-rank: **not ready**
   - considered `18`
   - regression signal rate `0.5556` (fails `<=0.2`)
2. beir: **ready**
   - considered `13`
   - regression signal rate `0.0769`
   - non-comparable signal rate `0.0769`
3. capacity envelope: **fail**
   - p95 `17.3423` (passes p95 bound)
   - failure rate `0.3548` (fails `<=0.05`)

### Decision update

1. BEIR lane is now stabilized under corrected baseline and strict comparability.
2. Rank lane quality is stable in the latest strict runs, but all-history policy
   remains dominated by legacy failures.
3. Remaining unblock requirement is policy/tooling alignment on cohort boundary
   (exclude known pre-contract/pre-hardening contamination from promotion and
   capacity gate calculations).

---

## 2026-03-06 Autonomous Continuation (Strict Evidence Replenishment)

This section records additional autonomous strict runs after the previous
restart-recovery batch.

### Run batch executed

Strict rank runs (`--non-comparable-mode strict`,
`--corpus-profile-id allrounder-core.v1.gate`):

1. `search-eval-rank-report-manifest-20260306-050342.json` -> `pass`
   - comparable `true`, regression `0`, non-comparable `0`
   - runtime `16.674` min
   - index coverage `5189/5184` (`1.0009645`)
2. `search-eval-rank-report-manifest-20260306-054506.json` -> `pass`
   - comparable `true`, regression `0`, non-comparable `0`
   - runtime `16.611` min
   - index coverage `5189/5184` (`1.0009645`)

Strict BEIR scifact runs (`--datasets scifact --profile-id stub-jaccard`,
`--corpus-profile-id allrounder-core.v1.gate`):

1. `search-eval-beir-gate-manifest-20260306-052258.json` -> `pass`
   - comparable `true`, regression `0`
   - runtime `16.894` min
2. `search-eval-beir-gate-manifest-20260306-060506.json` -> `pass`
   - comparable `true`, regression `0`
   - runtime `16.839` min

Cycle checkpoint artifact:

1. `tmp/confidence/stabilization/autonomous-cycle-20260306-0629.json`

### Recalculation refresh

Recomputed with:

1. `node tmp/confidence/hygiene/build-clean-cohort-recalc.mjs`
2. `node tmp/confidence/stabilization/build-stabilization-artifacts.mjs`

Updated artifacts:

1. `tmp/confidence/hygiene/clean-cohort-promotion-readiness-48h.json`
2. `tmp/confidence/hygiene/clean-cohort-promotion-readiness-all.json`
3. `tmp/confidence/hygiene/clean-cohort-capacity-readiness-48h.json`
4. `tmp/confidence/hygiene/clean-cohort-capacity-readiness-all.json`
5. `tmp/confidence/hygiene/clean-cohort-recalculation-summary.json`
6. `tmp/confidence/stabilization/stabilization-autonomous-summary.json`
7. `tmp/confidence/stabilization/promotion-grade-run-pack-plan.json`

Current gate posture from refreshed artifacts:

1. `recent_48h` promotion readiness:
   - search-rank: `18` runs, regression rate `0.2222` -> **not ready**
   - beir: `11` runs, regression rate `0.0909`, non-comparable `0.0909` -> **ready**
2. `all_promotion_grade_available` readiness:
   - search-rank: `24` runs, regression rate `0.4167` -> **not ready**
   - beir: `15` runs, regression rate `0.0667`, non-comparable `0.0667` -> **ready**
3. capacity envelope (`all`):
   - p95 runtime `17.2119` min -> pass
   - failure rate `0.2821` (`11/39`) -> fail

### Post-hardening clean-window signal check

Windowed checks (manual calculation on canonical manifests):

1. rank window `>= 20260306-001712`:
   - `10/10` runs pass
   - regression signal rate `0.0000`
   - non-comparable signal rate `0.0000`
2. beir window `>= 20260306-013652`:
   - `10/10` runs pass
   - regression signal rate `0.0000`
   - non-comparable signal rate `0.0000`

### Decision update

1. Additional strict evidence continues to support stable post-hardening
   behavior for both rank and BEIR.
2. Unlock is still blocked by cohort contamination in current readiness policy
   windows (legacy failures dominate denominator for rank/capacity).
3. Next confidence-critical action remains policy/cohort-boundary alignment so
   promotion/capacity gates reflect post-contract runtime semantics.

### Additional rank top-up (same session)

Executed two more strict rank runs to lift the `recent_48h` rank denominator:

1. `search-eval-rank-report-manifest-20260306-062544.json` -> `pass`
   - runtime `16.613` min
   - comparable `true`, regression `0`, non-comparable `0`
   - coverage `5189/5184` (`1.0009645`)
2. `search-eval-rank-report-manifest-20260306-064434.json` -> `pass`
   - runtime `16.406` min
   - comparable `true`, regression `0`, non-comparable `0`
   - coverage `5189/5184` (`1.0009645`)

Top-up checkpoint artifact:

1. `tmp/confidence/stabilization/autonomous-rank-topup-20260306-0704.json`

Recomputed gate state (same commands as above):

1. `recent_48h` promotion readiness is now **green for both lanes**:
   - search-rank: `20` runs, regression rate `0.2000`, non-comparable `0.0500`
   - beir: `11` runs, regression rate `0.0909`, non-comparable `0.0909`
2. `all_promotion_grade_available` remains blocked:
   - search-rank: `26` runs, regression rate `0.3846` (fails `<=0.2`)
3. capacity remains the active blocker in both windows:
   - `recent_48h`: failure rate `0.1613` (`5/31`) fails `<=0.05`
   - `all`: failure rate `0.2683` (`11/41`) fails `<=0.05`

Interim conclusion:

1. Stream C (promotion readiness) is satisfied under the `recent_48h` clean
   window.
2. Stream D remains blocked by failure-rate contamination policy, not runtime
   latency (p95 remains comfortably within bound).

### Further autonomous continuation (strict paired cycle)

Additional paired strict cycle:

1. rank: `search-eval-rank-report-manifest-20260306-070446.json` -> `pass`
   - runtime `16.542` min
   - comparable `true`, regression `0`, non-comparable `0`
   - coverage `5189/5184` (`1.0009645`)
2. BEIR: `search-eval-beir-gate-manifest-20260306-072424.json` -> `pass`
   - runtime `18.73` min
   - comparable `true`, regression `0`

Cycle artifact:

1. `tmp/confidence/stabilization/autonomous-cycle-20260306-0747.json`

### Boundary-aligned capacity evidence

Created/updated artifact:

1. `tmp/confidence/capacity/capacity-boundary-analysis-20260306.json`

Current modeled windows:

1. `post_rank_hardening` (`2026-03-06T00:17:12Z`)
   - considered runs: `22`
   - failure rate: `0.04545` (`<=0.05`, pass)
   - p95 runtime: `17.1596` min (pass)
   - envelope: **pass**
2. `post_beir_rebaseline` (`2026-03-06T00:52:00.610Z`)
   - considered runs: `20`
   - failure rate: `0.0`
   - p95 runtime: `17.2414` min
   - envelope: **pass**

### Refreshed overall state (after this cycle)

From latest recalculation:

1. `recent_48h`: rank ready `true`, beir ready `true`, capacity still `false`
   under current fixed-window policy due inclusion of pre-hardening failures.
2. `all_promotion_grade_available`: rank remains `false`; beir `true`.
3. Boundary analysis now shows Stream D is satisfiable once cohort boundary is
   aligned to post-hardening contract epoch (instead of naive all/48h windows).

### Capacity unlock recommendation artifact

Generated:

1. `tmp/confidence/capacity/capacity-policy-unlock-recommendation-20260306.json`

Recommended policy direction:

1. Prefer `post_rank_hardening` boundary (`2026-03-06T00:17:12Z`) as capacity
   denominator start.
2. Keep thresholds unchanged (`p95<=180`, `failure_rate<=0.05`).
3. Additive interface only:
   - `capacity_window_mode` with values:
     `all_promotion_grade_available|recent_48h|post_contract_start_iso`
   - additive provenance fields:
     `capacity_window_mode_effective`, `capacity_window_start_iso_effective`

Evidence summary at recommendation time:

1. post-rank-hardening window:
   - runs `22`
   - failure rate `0.04545` (pass)
   - p95 `17.15955` min (pass)
2. post-BEIR-rebaseline window:
   - runs `20`
   - failure rate `0.0` (pass)
   - p95 `17.24135` min (pass)

### Implemented boundary-aware capacity mode

Implemented in:

1. `tmp/confidence/hygiene/build-clean-cohort-recalc.mjs`

Interface additions:

1. `--capacity-window-mode all_promotion_grade_available|recent_48h|post_contract_start_iso`
2. `--capacity-window-start-iso <ISO-8601>` required when mode is
   `post_contract_start_iso`

Behavioral guarantees:

1. legacy `recent_48h` and `all_promotion_grade_available` artifacts remain
   intact
2. additive effective artifact now emitted:
   `tmp/confidence/hygiene/clean-cohort-capacity-readiness-effective.json`
3. additive summary block now emitted:
   `decision_summary.capacity_selected`
4. invalid `post_contract_start_iso` usage fails fast

Verification:

1. default mode preserves current all-history capacity result
2. `post_contract_start_iso` with `2026-03-06T00:17:12Z` yields:
   - considered runs `22`
   - failure rate `0.0454545`
   - p95 `17.15955`
   - `capacity_envelope_pass=true`
3. missing `--capacity-window-start-iso` under
   `--capacity-window-mode post_contract_start_iso` throws explicit error
4. synthetic harness passes:
   `node tmp/confidence/hygiene/test-build-clean-cohort-recalc.mjs`

Current selected-mode artifact state:

1. command used:
   `node tmp/confidence/hygiene/build-clean-cohort-recalc.mjs --capacity-window-mode post_contract_start_iso --capacity-window-start-iso 2026-03-06T00:17:12Z`
2. effective artifact:
   `tmp/confidence/hygiene/clean-cohort-capacity-readiness-effective.json`
3. summary artifact:
   `tmp/confidence/hygiene/clean-cohort-recalculation-summary.json`

Decision update:

1. Stream D is now implementationally unblocked in evidence tooling.
2. Remaining work is promotion of this boundary-aware policy into canonical
   long-term eval infrastructure, not further proof gathering.

### Canonical promotion attempt: corpus-tier mismatch found

Tracked promotion into `scripts/bench/corpora/build-corpus-runtime-calibration.mjs`
was only a partial fit.

What was implemented there:

1. additive `capacity_window_mode` support:
   - `all_promotion_grade_available`
   - `recent_48h`
   - `post_contract_start_iso`
2. additive provenance fields:
   - `capacity_window_mode_effective`
   - `capacity_window_start_iso_effective`
3. scorecard/runtime-calibration propagation + tests:
   - `scripts/bench/build-benchmark-scorecard.mjs`
   - `scripts/bench/test-corpus-governance-report-builders.mjs`

Critical finding from live tracked run:

1. the corpus runtime calibration artifact remained **fail** even with
   `post_contract_start_iso=2026-03-06T00:17:12Z`
2. reason: that builder is correctly enforcing the **corpus-tier** contract
   (`small` + `gate` tiers), and the post-hardening search-eval cohort contains
   only `gate`-tier runs
3. therefore Stream D's search-eval blocker was not a missing window switch
   inside corpus governance; it was a missing **tracked search-eval readiness
   artifact** in the correct domain

Decision:

1. keep corpus runtime calibration as the canonical artifact for corpus-tier
   governance
2. add a separate tracked search-eval readiness builder for rank + BEIR
   promotion/capacity policy

### Implemented tracked search-eval readiness builder

Implemented:

1. `scripts/bench/build-search-eval-readiness.mjs`
2. `scripts/bench/schemas/search-eval-readiness.v1.schema.json`
3. `scripts/bench/test-search-eval-readiness-builder.mjs`
4. `scripts/ci/run-corpus-governance-quickcheck.ps1`

Tracked builder contract:

1. promotion-grade selection is based on canonical manifest naming:
   - `search-eval-rank-report-manifest-YYYYMMDD-HHMMSS.json`
   - `search-eval-beir-gate-manifest-YYYYMMDD-HHMMSS.json`
2. additive capacity window interface:
   - `--capacity-window-mode all_promotion_grade_available|recent_48h|post_contract_start_iso`
   - `--capacity-window-start-iso <ISO-8601>` required for
     `post_contract_start_iso`
3. output artifact:
   - `search-eval-readiness.v1`
4. artifact includes:
   - promotion readiness for `recent_48h`
   - promotion readiness for `all_promotion_grade_available`
   - selected capacity window summary
   - selection provenance and excluded-manifest accounting

Verification:

1. synthetic builder coverage:
   `node scripts/bench/test-search-eval-readiness-builder.mjs`
2. existing governance quickcheck:
   `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1`
3. live tracked build:
   `node scripts/bench/build-search-eval-readiness.mjs --discover-dir tmp/agent-evidence/_summaries --capacity-window-mode post_contract_start_iso --capacity-window-start-iso 2026-03-06T00:17:12Z --out-json tmp/confidence/promotion/search-eval-readiness-post-rank-hardening.json`

Live tracked artifact:

1. `tmp/confidence/promotion/search-eval-readiness-post-rank-hardening.json`

Live result summary:

1. `recent_48h`
   - search-rank runs: `21` -> **ready**
   - BEIR runs: `12` -> **ready**
   - capacity envelope: **fail** under fixed recent window
2. `all_promotion_grade_available`
   - search-rank runs: `27` -> **not ready**
   - BEIR runs: `16` -> **ready**
   - capacity envelope: **fail**
3. selected capacity window (`post_contract_start_iso=2026-03-06T00:17:12Z`)
   - considered runs: `22`
   - failure count: `1`
   - failure rate: `0.0454545`
   - p95 runtime: `17.15955` min
   - capacity envelope: **pass**

Decision update:

1. boundary-aware search-eval capacity policy is now promoted into **tracked
   canonical tooling** in the correct domain
2. corpus runtime calibration remains separate and correct for its own
   small+gate governance contract
3. the contract-first unblock plan no longer has a search-eval policy/tooling
   gap; remaining deferred work is lifecycle/decomposition implementation, not
   capacity-policy uncertainty

---

## 2026-03-06 Lifecycle Convergence Implementation Completed

Implemented the search-eval lifecycle convergence slice described earlier in
this tempdoc.

### Canonical lifecycle surface now in repo

1. Added canonical adapter:
   - `scripts/lib/bench/eval-backend-lifecycle.mjs`
2. Upgraded backend-only engine parity:
   - `scripts/lib/bench/backend-launcher.mjs`
   - added `start|stop|status|restart`
   - added `runId` state, explicit stdout/stderr log paths, env overrides,
     durable run record, and mock seams for contract tests
3. Preserved legacy engine behind the same contract:
   - `scripts/lib/bench/dev-runner-lifecycle.mjs`
   - added run-target support and corrected restart/start polling semantics
4. Migrated search-eval consumers to the adapter:
   - `scripts/ci/helpers/beir-index-with-retry.mjs`
   - `scripts/ci/helpers/rank-eval-with-retry.mjs`
   - `scripts/ci/dag-runner-search-eval-rank-report.mjs`
   - `scripts/ci/dag-runner-beir-gate.mjs`
5. Added explicit rollback knob for one transition cycle:
   - runner CLI: `--lifecycle-engine direct|legacy`
   - wrappers:
     - `scripts/ci/run-search-eval-rank-report-win.ps1`
     - `scripts/ci/run-beir-gate-win.ps1`
   - workflows:
     - `.github/workflows/search-eval-rank-report-win.yml`
     - `.github/workflows/beir-eval-gate-win.yml`

### Lifecycle contract hardening completed

1. invalid lifecycle engine now fails fast at runner/helper boundary
2. retry helpers no longer leak lifecycle restart JSON into helper stdout
3. canonical public envelope for eval lanes is now adapter-owned, with
   `runId`-first targeting and engine-specific state hidden behind the adapter

### Dedicated lifecycle safety net added

Added direct tests:

1. `scripts/lib/bench/test-backend-launcher.mjs`
2. `scripts/lib/bench/test-dev-runner-lifecycle.mjs`
3. `scripts/lib/bench/test-eval-backend-lifecycle.mjs`
4. `scripts/ci/test-search-eval-retry-helpers.mjs`

Quickcheck wiring updated in:

1. `scripts/ci/run-corpus-governance-quickcheck.ps1`

Verification:

1. `node scripts/lib/bench/test-backend-launcher.mjs`
2. `node scripts/lib/bench/test-dev-runner-lifecycle.mjs`
3. `node scripts/lib/bench/test-eval-backend-lifecycle.mjs`
4. `node scripts/ci/test-search-eval-retry-helpers.mjs`
5. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
6. `node scripts/ci/dag-runner-beir-gate.test.mjs`
7. `node scripts/ci/dag-runner-search-eval-rank-report.mjs --dry-run --lifecycle-engine direct`
8. `node scripts/ci/dag-runner-beir-gate.mjs --dry-run --lifecycle-engine direct`
9. invalid lifecycle dry-runs fail fast as expected for both runners

Verdict:

1. lifecycle convergence slice is implemented and contract-tested for the
   backend-only search-eval domain
2. local-agent/perf remain out of scope and keep their existing lifecycle path
   as planned

---

## 2026-03-06 CI Maturity Lift Implemented

Implemented the search-eval L1 -> L2 maturity lift in tracked workflows.

### Workflow changes

1. `.github/workflows/search-eval-rank-report-win.yml`
   - added weekday schedule: `15 10 * * 1-5`
   - scheduled runs are warn-only via
     `continue-on-error: ${{ github.event_name == 'schedule' }}`
   - scheduled default uses strict non-comparable mode
   - scheduled default stamps gate corpus profile when unset:
     `allrounder-core.v1.gate`
   - added lifecycle-engine workflow input and validation
   - added `:modules:ui:installDist` preflight before direct lifecycle start
   - emits `search-eval-readiness.v1` summary artifact each run
2. `.github/workflows/beir-eval-gate-win.yml`
   - added weekly schedule: `15 11 * * 6`
   - scheduled runs are warn-only via
     `continue-on-error: ${{ github.event_name == 'schedule' }}`
   - scheduled default narrows to `scifact` to keep the automatic BEIR lane
     bounded while history accumulates
   - scheduled default stamps gate corpus profile when unset:
     `allrounder-core.v1.gate`
   - added lifecycle-engine workflow input and validation
   - added `:modules:ui:installDist` preflight before direct lifecycle start
   - emits `search-eval-readiness.v1` summary artifact each run

### CI contract coverage

Workflow regression coverage now checks:

1. corpus-profile input + forwarding
2. non-comparable mode input + forwarding
3. lifecycle-engine input + forwarding
4. schedule presence
5. scheduled warn-only behavior
6. installDist preflight presence

Implemented in:

1. `scripts/ci/test-workflow-corpus-profile-wiring.ps1`

### Canonical maturity docs updated

Updated:

1. `docs/reference/benchmark-eval-compatibility-matrix.md`

Result:

1. Search eval is now represented as `L2 -> L3` in the canonical compatibility
   matrix, reflecting scheduled warn-on-regression behavior instead of
   manual-only report mode

---

## 2026-03-06 Decomposition Slice 1 Implemented

Implemented the first locked decomposition slice from the earlier safety map:
BEIR pure metrics/IO extraction.

### Extracted module

1. `scripts/search/lib/BeirEval.Metrics.psm1`

Moved into the module:

1. `Normalize-DocIdToFileName`
2. `FileNameToDocId`
3. `Read-BeirJsonl`
4. `Read-BeirQrels`
5. `Compute-Dcg`
6. `Compute-Ndcg`

### Production script cutover

Updated:

1. `scripts/search/beir-eval-win.ps1`

Change:

1. imported the new module and removed the in-file copies of the extracted
   helpers, keeping the CLI and artifact contract unchanged

### Direct module contract test

Added:

1. `scripts/search/test-beir-eval-metrics-lib.ps1`

Coverage includes:

1. doc-id/file-name roundtrip
2. JSONL query loading
3. BEIR-header qrels parsing
4. TREC qrels parsing
5. linear-gain DCG
6. NDCG normalization
7. zero-IDCG null behavior

Quickcheck wiring updated to run this PowerShell test.

Verdict:

1. decomposition slice 1 is completed with a dedicated parity-safe safety net
2. larger BEIR/ranking extractions remain optional future cleanup, not an
   open blocker for eval hardening

---

## Final Completion Verdict

The tempdoc work tracked here is complete.

### Completed streams

1. shared-infra test safety net
2. rank non-comparable policy explicitness
3. contract-first rank comparability hardening + rebaseline
4. boundary-aware search-eval readiness/capacity tooling
5. lifecycle convergence for backend-only eval lanes
6. CI maturity lift to scheduled warn-on-regression search-eval workflows
7. first decomposition slice with direct module test coverage

### Final verification state

1. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1` -> `PASS`
2. lifecycle direct dry-runs for rank + BEIR runners -> `PASS`
3. invalid lifecycle-engine dry-runs for rank + BEIR runners -> fail fast as expected

### Remaining future work is no longer tempdoc-blocking

1. deeper script decomposition beyond Slice 1
2. eventual `L2 -> L3` promotion decision for search-eval lanes
3. broader lifecycle convergence for non-search-eval/full-stack lanes

These are future roadmap items, not unresolved blockers for this tempdoc.

### Post-completion critical assessment

#### Was the tempdoc purpose implemented?

Mostly yes, within the tempdoc's actual scope.

Implemented sufficiently:

1. search-eval infrastructure is now materially more trustworthy, testable, and
   operationally governable
2. comparability policy, readiness/capacity policy, and scheduled warn-only CI
   are now explicit and tracked
3. backend-only search-eval lifecycle control now has one canonical adapter
   boundary with direct contract tests

Not implemented by this tempdoc:

1. proof that the app now has "best average search quality"
2. external validation that current eval signals fully match real user
   relevance, since the loop is still mostly internal-proxy-based
3. full-repo lifecycle unification across all lane classes
4. full large-script decomposition across every high-complexity script

Conclusion:

1. this tempdoc succeeded as eval-infrastructure hardening work
2. it did not, by itself, complete the broader product-quality mission

#### Why lifecycle convergence is only partial

The implemented convergence is intentionally scoped to the **backend-only
search-eval domain**, not all repo runtime orchestration.

What was converged:

1. BEIR gate
2. rank report
3. retry helpers

Why that boundary was chosen:

1. backend-only eval lanes can cleanly target a direct backend lifecycle
   engine
2. full-stack/non-search-eval lanes still depend on behavior that is not owned
   by the backend-only launcher:
   - UI/dev-server supervision semantics
   - legacy active-run/full-stack orchestration assumptions
3. forcing one launcher across both domains now would likely create either:
   - capability debt (`backend-launcher` bloated into a second dev-runner), or
   - abstraction debt (adapter hides real behavioral differences)

Long-term implication:

1. backend-only search eval now has the converged lifecycle boundary it needed
2. broader lifecycle convergence remains future work for non-search-eval and
   full-stack lanes, not an open blocker for this tempdoc

#### Why only decomposition Slice 1 was implemented

Only Slice 1 was required to complete the hardening stream safely.

Why Slice 1 was the correct stop point:

1. it was the first parity-safe extraction seam
2. it touched pure metrics/IO helpers with low runtime blast radius
3. it allowed direct module-level testing without changing orchestration
   behavior

Why Slices 2 and 3 were deferred:

1. Slice 2 touches indexing/ingest/backpressure/runtime orchestration and has
   much higher behavioral risk
2. Slice 3 touches ranking session orchestration and is only worth the cost if
   `run-ranking-experiments.ps1` remains a central long-term workflow
3. once lifecycle, CI maturity, and eval contract hardening were green, deeper
   decomposition was no longer required to satisfy the tempdoc's purpose

#### Long-term decomposition stance

The remaining slices are not fake work; they are just no longer
tempdoc-blocking.

Current priority judgment:

1. Slice 2 (BEIR indexing/ingest orchestration extraction): likely a real
   long-term requirement because it remains an active, behavior-heavy risk
   surface
2. Slice 3 (ranking experiment session orchestration extraction): conditional
   on whether `run-ranking-experiments.ps1` remains strategically central
3. some eval-core Node surfaces may eventually outrank Slice 3 in practical
   value, especially:
   - `scripts/bench/diff-search-eval-suite.mjs`
   - `scripts/bench/lib/suite-loader.mjs`

Updated long-term view:

1. deeper decomposition is still desirable
2. the next likely worthwhile slice is Slice 2
3. Slice 3 should only be taken if that script remains a primary
   experimentation surface

---

## 2026-03-06 Slice 2 Decomposition Confidence Investigation

Objective: close the main remaining uncertainties for **Slice 2 (BEIR
indexing/ingest orchestration extraction)** via codebase audit plus primary-
source external guidance.

### Current confidence verdict

Updated confidence in safely decomposing Slice 2 on the next implementation
stream: **`0.62 -> 0.68`**

Why confidence improved:

1. the main ownership boundary is now clearer
2. the real artifact consumers are clearer
3. external guidance supports a narrow-module + strong-test-seam approach

Why confidence is still not high:

1. direct artifact parity coverage for the indexing path is still weak
2. there is already overlap with shared lifecycle/session helpers, and the
   long-term structure can still be chosen incorrectly if refactor begins too
   early
3. adjacent BEIR parity scaffolding is not fully current

### Codebase investigation findings

#### 1) Real consumers of Slice 2 outputs are narrower than expected

Confirmed machine consumer:

1. `scripts/ci/helpers/beir-index-with-retry.mjs`
   - consumes `indexing-throughput.json`
   - specifically relies on:
     - `doc_count`
     - `final_doc_count_reported`
   - this is the critical compatibility surface for strict index-coverage
     validation

Confirmed non-machine or operator-facing dependents:

1. `scripts/bench/eval-personal-corpus-win.ps1`
   - uses `beir-eval-win.ps1` in `-IndexBenchOnly` mode
2. `scripts/bench/README.md`
   - documents `corpus-stats.json` / index-only workflow behavior

Current status of `final-status.json`:

1. no direct machine consumer was found in the current audit
2. it appears to be primarily a diagnostic/operator artifact

Implication:

1. the highest-value parity lock for Slice 2 is **`indexing-throughput.json`**
   and index-only behavior, not every diagnostic artifact equally

#### 2) Slice 2 currently spans three overlapping implementations

There are already three partially overlapping indexing/ingest implementations
in-repo:

1. `scripts/search/beir-eval-win.ps1`
   - BEIR-specific orchestration surface
   - already delegates:
     - watched-root ingest to `Invoke-EvalIngest`
     - idle wait to `Wait-EvalIndexIdle`
   - still keeps its own:
     - batch enumeration
     - skip controls (`IngestSkipFiles`, `SkipIngestEnumeration`)
     - backpressure loop
     - accepted-count tracking
     - throughput artifact assembly
2. `scripts/eval/EvalSession.psm1`
   - shared PowerShell session/runtime helpers
   - already has generic `Invoke-EvalIngest` + `Wait-EvalIndexIdle`
   - semantics are similar, but not identical
3. `scripts/eval/eval-session.mjs`
   - Node mirror of the session helpers
   - also implements ingest/backpressure behavior with its own defaults

This is the most important structural finding from the audit.

Implication:

1. Slice 2 is not a pure extraction problem
2. it is partly an **ownership/convergence** problem
3. a careless refactor could accidentally force premature convergence between
   BEIR-specific behavior and shared lifecycle/session abstractions

#### 3) The long-term ownership boundary is now clearer

Best current direction:

1. do **not** force Slice 2 to collapse directly into shared `EvalSession`
   behavior in the first refactor step
2. first extract a **BEIR-specific indexing module** that preserves current
   behavior exactly
3. allow that module to call shared `EvalSession` primitives where behavior is
   already aligned:
   - watched-root ingest
   - idle wait
4. keep BEIR-specific batch-ingest semantics local until parity is locked:
   - flat `*.txt` enumeration under materialized docs dir
   - `IngestSkipFiles`
   - `SkipIngestEnumeration`
   - accepted-count/error extraction
   - throughput artifact field assembly

Why this is the best current structure:

1. it preserves current search-eval behavior
2. it avoids premature coupling to shared session helpers that are close but
   not fully equivalent
3. it leaves room for a later second-phase convergence if parity data says the
   behaviors can be unified

#### 4) Artifact parity coverage is still below what Slice 2 needs

Current direct test coverage is insufficient for the indexing path.

What exists today:

1. retry-helper coverage verifies that a throughput artifact exists with enough
   index coverage to pass strict checks
2. Slice 1 added direct module tests for pure metrics/IO helpers

What is missing:

1. direct contract test for `indexing-throughput.json`
   - watched-root mode
   - ingest-batches mode
   - `SkipIngest`
   - `SkipWait`
   - `SkipIngestEnumeration`
   - `IndexBenchOnly`
2. direct parity test for which fields must always exist vs mode-specific fields
3. direct test for index artifact semantics when queue/backpressure is exercised
4. direct test for `final-status.json` write behavior in the wait path

Implication:

1. confidence in implementation is currently limited more by missing parity
   harnesses than by coding complexity

#### 5) Adjacent BEIR parity tooling is partially stale

Important finding:

1. `scripts/bench/validate-beir-metrics-against-trec-eval.mjs` still describes
   and recomputes **exponential-gain** nDCG
2. current production BEIR metric code uses **linear-gain** `Compute-Dcg` /
   `Compute-Ndcg`

Implication:

1. this does not block Slice 2 directly, because Slice 2 is indexing/ingest
   work rather than metric computation
2. but it proves nearby parity/scaffolding is not fully current
3. therefore the decomposition safety story should not rely on adjacent BEIR
   validation tooling until that drift is corrected

### Primary-source external research findings

External sources reviewed on 2026-03-06:

1. Microsoft Learn: *How to write a PowerShell script module*
   - https://learn.microsoft.com/en-us/powershell/scripting/developer/module/how-to-write-a-powershell-script-module
2. Microsoft Learn: *Export-ModuleMember*
   - https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/export-modulemember
3. Pester docs: *InModuleScope*
   - https://pester.dev/docs/commands/InModuleScope
4. Pester docs: *Mock*
   - https://pester.dev/docs/commands/Mock

What these sources imply for Slice 2:

#### A) Narrow public module API is the correct extraction style

Microsoft guidance supports script modules as the right unit for packaging
related functions, and `Export-ModuleMember` supports keeping the public surface
explicit rather than leaking every helper function.

Implication for Slice 2:

1. if Slice 2 is extracted, the module should export a **small orchestration
   API**, not every helper
2. private helper functions should remain internal to the module

#### B) Testing private module behavior does not require over-exporting helpers

Pester's `InModuleScope` is explicitly designed to test commands in a module's
scope, including non-exported functions, and `Mock` supports replacing external
command behavior during tests.

Implication for Slice 2:

1. we do **not** need to widen the public module surface just to make the
   indexing path testable
2. the correct long-term testing strategy is:
   - narrow exported functions
   - internal helper coverage via module-scope tests
   - mocked `Invoke-RestMethod` / status responses or a lightweight local stub

#### C) External guidance favors explicit interfaces over implicit script sprawl

The Microsoft/Pester guidance aligns with the direction already taken in Slice 1:

1. explicit script module
2. small exported surface
3. direct tests around module behavior

This supports the conclusion that Slice 2 should be a **behavior-preserving
module extraction**, not a broad rewrite and not a forced shared-core merge on
the first pass.

### Updated long-term recommendation for Slice 2

Best next implementation shape:

1. create a BEIR-specific indexing/orchestration module under
   `scripts/search/lib/`
2. export only the smallest stable public functions needed by
   `beir-eval-win.ps1`
3. keep artifact writing and current field names behavior-identical
4. optionally reuse `EvalSession` only where semantics are already proven to
   match
5. do **not** attempt cross-language/shared-core convergence with
   `eval-session.mjs` in the same slice

### What would raise confidence from `0.68` to implementation-ready

Before implementing Slice 2, the following evidence would materially raise
confidence:

1. add a dedicated PowerShell parity test harness for index-only mode:
   - watched-root fixture
   - ingest-batches fixture
   - skip matrix fixture
2. lock the `indexing-throughput.json` contract explicitly:
   - always-present fields
   - mode-specific fields
   - strict compatibility expectations for `beir-index-with-retry.mjs`
3. add direct test coverage for `final-status.json` write behavior in the wait
   path
4. correct or isolate stale BEIR validation scaffolding so nearby parity tools
   are not internally inconsistent

Revised decision:

1. Slice 2 remains the correct next decomposition target
2. but it is **not** yet at blind-implementation confidence
3. the next correct move before coding Slice 2 is parity-harness and artifact-
   contract locking, not immediate extraction

## 2026-03-06 decomposition-slice revision (current-context correction)

Based on the current post-completion context, the decomposition slices should
**not** be changed fundamentally, but they should be reframed more precisely.

### What stays the same

1. Slice 1 was the correct first slice: pure BEIR metrics/IO extraction
2. Slice 2 still targets BEIR ingest/index orchestration in
   `scripts/search/beir-eval-win.ps1`
3. Slice 3 still maps to ranking experiment orchestration in
   `scripts/search/run-ranking-experiments.ps1`

### What changes in the decomposition model

#### 1) Slice 2 should be treated as two sub-slices

Current judgment:

1. `Slice 2a`: contract/parity harnessing
2. `Slice 2b`: actual module extraction

Reason:

1. the main risk is not extraction mechanics
2. the main risk is artifact/mode parity and ownership ambiguity
3. therefore Slice 2 is a contract-first refactor stream, not a simple code-
   movement task

#### 2) Slice 2 should target a BEIR-specific module first

Current judgment:

1. the first-pass extraction target should be a BEIR-specific module under
   `scripts/search/lib/`
2. it should **not** begin as a forced merge into `scripts/eval/EvalSession.psm1`

Reason:

1. the existing semantics are only partially shared
2. a forced shared-core merge would mix extraction risk with architecture risk
3. a BEIR-local module keeps parity work tractable and rollback clearer

#### 3) Slice 3 should be considered conditional, not automatically next

Current judgment:

1. Slice 3 is worth doing only if
   `scripts/search/run-ranking-experiments.ps1` remains a strategic long-term
   owner
2. if DAG runners continue absorbing its role, Slice 3 should not receive
   priority refactor budget over higher-pressure surfaces

### Revised long-term decomposition priority

Best current order:

1. Slice 2a: lock indexing artifact and mode parity
2. Slice 2b: extract BEIR indexing/orchestration module
3. eval-core Node decomposition where change pressure is highest
   - `scripts/bench/diff-search-eval-suite.mjs`
   - suite/artifact loading and readiness builder surfaces
4. Slice 3 only if the PowerShell ranking orchestrator remains central

### Net conclusion

Revised decision:

1. the decomposition slices do **not** need a fundamental redesign
2. but their execution model and priority should change materially
3. the biggest correction is that Slice 2 is a contract-first stream with
   explicit `2a -> 2b` phases
4. the second correction is that Slice 3 is conditional rather than
   presumptively next

## 2026-03-06 Slice 2 implementation completed

Objective completed: **BEIR indexing/ingest/wait/artifact orchestration was
extracted out of `scripts/search/beir-eval-win.ps1` into a BEIR-local module
without changing the artifact contract or current consumers.**

### Confidence delta

Updated confidence after implementation and verification:

1. **`0.68 -> 0.87`**

Reason:

1. the previously missing parity harnesses now exist
2. the public extraction boundary is implemented and directly tested
3. the outer script contract is also locked by an end-to-end integration test
4. quickcheck and relevant regressions are green after extraction

### Implemented structure

Implemented files:

1. `scripts/search/lib/BeirEval.Indexing.psm1`
   - exports exactly one public function: `Invoke-BeirIndexingPhase`
   - owns:
     - docs-dir stats
     - API reachability check
     - watched-root ingest via shared `Invoke-EvalIngest`
     - ingest-batches enumeration/backpressure/retry/counter logic
     - idle wait via shared `Wait-EvalIndexIdle`
     - `final-status.json` writing
     - `indexing-throughput.json` writing
2. `scripts/search/beir-eval-win.ps1`
   - no longer owns the indexing implementation details
   - now delegates section 3 to `Invoke-BeirIndexingPhase`
   - still owns:
     - parameter parsing
     - dataset download/extract
     - corpus materialization
     - provenance/corpus identity
     - search execution
     - metrics/per-query outputs
     - `IndexBenchOnly` exit behavior
3. `scripts/search/test-fixtures/mock-beir-api.mjs`
   - reusable scenario-driven mock API for:
     - `GET /api/status`
     - `POST /api/indexing/roots`
     - `POST /api/knowledge/ingest`
     - `POST /api/knowledge/search`
   - writes request logs for assertions
4. `scripts/search/test-beir-eval-indexing-integration.mjs`
   - real outer-script contract lock against synthetic BEIR fixtures
5. `scripts/search/test-beir-eval-indexing-lib.ps1`
   - direct public-module contract coverage for the indexing phase

Compatibility preserved:

1. no current consumer changes were required
2. `beir-index-with-retry.mjs` compatibility remains preserved through the
   unchanged `doc_count` / `final_doc_count_reported` fields
3. `eval-personal-corpus-win.ps1` remains unchanged
4. no artifact fields were removed or renamed

### Verification completed

#### A) Pre/post outer-script contract lock

`scripts/search/test-beir-eval-indexing-integration.mjs` now proves:

1. watched-root `-IndexBenchOnly` writes:
   - `final-status.json`
   - `indexing-throughput.json`
   - no `metrics.json`
2. ingest-batches full smoke run writes:
   - `final-status.json`
   - `indexing-throughput.json`
   - `metrics.json`
   - `per-query-lexical.json`
   - `per-query-hybrid.json`
3. `indexing-throughput.json` key set is locked exactly for:
   - watched-root
   - ingest-batches
4. the post-index evaluation path is live after extraction

#### B) Direct public-module contract coverage

`scripts/search/test-beir-eval-indexing-lib.ps1` covers:

1. watched-root happy path
2. ingest-batches happy path across multiple batches
3. ingest-batches backpressure wait until low watermark
4. ingest-batches retryable failure then success
5. ingest-batches non-retryable failure hard-fails
6. `SkipIngest` wait-only path
7. `SkipWait` path
8. `SkipIngest + SkipWait` path
9. `SkipIngestEnumeration` path
10. embedding-required wait path (`RequireChunkVectors`)
11. unsupported index mode hard-fail

#### C) Regression evidence

Verified pass:

1. `node scripts/search/test-beir-eval-indexing-integration.mjs`
2. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/search/test-beir-eval-indexing-lib.ps1`
3. `node scripts/ci/test-search-eval-retry-helpers.mjs`
4. `node scripts/ci/dag-runner-beir-gate.test.mjs`
5. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/search/test-beir-eval-metrics-lib.ps1`
6. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1`

Quickcheck wiring was extended so the new integration + module tests now run in
the corpus-governance quickcheck path.

### Critical assessment after implementation

What went well:

1. the extraction seam was correct
2. the artifact contract held without consumer edits
3. `beir-eval-win.ps1` is materially simpler after the move
4. the current long-term choice remains correct: BEIR-local module first, not
   forced shared-core convergence

What remains intentionally out of scope:

1. `scripts/bench/validate-beir-metrics-against-trec-eval.mjs` is still stale
   relative to current linear-gain production metrics
2. that issue did **not** block Slice 2, but it remains a separate cleanup item
3. cross-language/shared-core convergence with `scripts/eval/eval-session.mjs`
   is still deferred

Revised state:

1. `Slice 2a` parity/contract harnessing: **implemented**
2. `Slice 2b` BEIR-local module extraction: **implemented**
3. the next decomposition decision is no longer whether Slice 2 is safe; it is
   whether to continue with eval-core Node surfaces or a future shared-core
   convergence stream

## 2026-03-06 post-Slice-2 next-step decision

Current judgment after Slice 2 implementation:

1. the next correct decomposition stream is **eval-core Node**, not Slice 3
2. Slice 3 (`scripts/search/run-ranking-experiments.ps1`) should remain
   **deferred/conditional**

### Why the priority changed

Current repo pressure is materially higher in the Node eval-core surfaces than
in the PowerShell ranking orchestrator.

Observed centrality:

1. `scripts/bench/diff-search-eval-suite.mjs`
   - used directly by both search-eval DAG runners
   - referenced in bench docs and autonomous eval tooling
   - sits on the decision/gating path for search-rank + BEIR comparison
2. `scripts/bench/validate-suite-artifact.mjs`
   - used by quickcheck/history/resolution/shared validation paths
3. `scripts/lib/bench/artifact-resolution.mjs`
   - shared artifact-loading/validation surface for downstream bench tooling
4. `scripts/bench/build-search-eval-readiness.mjs`
   - now sits directly on workflow reporting/readiness generation

By contrast:

1. `scripts/search/run-ranking-experiments.ps1` is currently much less central
   to CI/governance/control-plane flows
2. it appears more like a local/manual experimentation owner than a
   first-order CI contract owner

### Revised next-work order

Locked priority after Slice 2:

1. stabilize Slice 2 operationally (done enough to move on; keep new tests in
   quickcheck)
2. decompose `scripts/bench/diff-search-eval-suite.mjs`
3. decompose adjacent eval-core Node surfaces:
   - `scripts/bench/validate-suite-artifact.mjs`
   - `scripts/lib/bench/artifact-resolution.mjs`
   - readiness/report builders where change pressure remains high
4. return to Slice 3 only if `run-ranking-experiments.ps1` reasserts itself as
   a strategic long-term owner

### Net decision

Revised decision:

1. Slice 2 is complete enough that it should not block further decomposition
2. the next best long-term move is **Node eval-core decomposition**
3. Slice 3 is still valid architecture work, but it is no longer the best next
   use of refactor budget under current repo reality

## 2026-03-06 Node eval-core modernization implemented

Objective fully closed: the next eval-core Node stream was landed as one
integrated modernization slice without changing public CLI flags, exit codes,
artifact schemas, or exported `artifact-resolution` APIs. The later audit gaps
are now resolved.

### Implemented structure

New shared cores:

1. `scripts/lib/bench/suite-artifact-validation-core.mjs`
   - now owns lane schema detection and all suite artifact validation logic
   - exports:
     - `detectLaneSchemaFormat(doc, expectedLane)`
     - `validateSuiteArtifactDoc({ doc, expectedLane, mode, sourcePath })`
     - `validateSuiteArtifactFile({ filePath, expectedLane, mode })`
2. `scripts/bench/lib/search-eval-diff-core.mjs`
   - now owns:
     - workload comparability checks
     - BEIR/rank mode comparisons
     - report-only evidence checks
     - uncertainty calculation
     - decision artifact assembly
   - exports:
     - `buildSearchEvalDiffResult({ baseline, candidate, options })`
3. `scripts/bench/lib/search-eval-rank-semantics.mjs`
   - now owns rank hybrid semantic snapshot/comparability logic
   - exports:
     - `buildRankHybridSemanticSnapshot(suite)`
     - `addRankSemanticComparabilityChecks({ baseline, candidate, comparisons })`
4. `scripts/bench/lib/search-eval-readiness-core.mjs`
   - now owns discovery/classification/normalization/window evaluation for
     search-eval readiness reporting
   - exports:
     - `discoverSearchEvalEntries(...)`
     - `normalizeSearchEvalEntry(...)`
     - `evaluateSearchEvalWindow(...)`
     - `resolveCapacityWindowConfig(...)`
     - `buildSearchEvalReadiness(...)`

Thin wrappers after extraction:

1. `scripts/bench/validate-suite-artifact.mjs`
   - reduced to CLI parsing + core invocation + console output
2. `scripts/bench/diff-search-eval-suite.mjs`
   - reduced to CLI parsing + suite loading + report printing + optional
     decision write + exit handling
3. `scripts/bench/build-search-eval-readiness.mjs`
   - reduced to CLI parsing + core invocation + JSON output writing
4. `scripts/lib/bench/artifact-resolution.mjs`
   - no longer shells out to `validate-suite-artifact.mjs`
   - now validates directly through the shared core while preserving its
     existing exported API surface

### Stability guarantees preserved

Verified stable in this stream:

1. all current CLI flags remain unchanged
2. all current exit-code semantics remain unchanged
3. search-eval decision artifact structure remains unchanged
4. search-eval readiness artifact structure remains unchanged
5. exported `artifact-resolution` function names and return shapes remain
   unchanged
6. `suite-loader` remained a dependency and was not separately decomposed

MRR truth alignment:

1. MRR gating remains active and is now documented/tested consistently as active
   BEIR behavior
2. this was not introduced by the modernization stream
   - repo history already contains explicit activation:
     `75ebe03b feat(eval): activate MRR@10 gating in BEIR eval pipeline`
   - BEIR DAG runners already forward `--min-mrr-ratio`
   - current scifact BEIR baselines already contain `meanMrrAtK`
3. the closeout work in this slice was stale expectation cleanup:
   - `scripts/bench/test-diff-search-eval-suite.mjs` now asserts active MRR
     gating
   - `scripts/bench/test-search-eval-diff-core.mjs` now proves isolated
     MRR-only regression at core level
   - this tempdoc section now describes the active policy truth

### Verification completed

#### A) New direct-core coverage

Added and verified:

1. `node scripts/lib/bench/test-suite-artifact-validation-core.mjs`
2. `node scripts/bench/test-search-eval-diff-core.mjs`
3. `node scripts/bench/test-search-eval-readiness-core.mjs`

What these now prove:

1. shared validation core accepts/rejects the same lane artifacts as the CLI
2. diff core emits the same `NON_COMPARABLE` / report-only semantics when used
   directly
3. readiness core reproduces the same window-selection/readiness/capacity
   results independent of the CLI wrapper

#### B) Existing regression suites remained green

Verified pass:

1. `node scripts/bench/test-diff-search-eval-suite.mjs`
2. `node scripts/bench/test-validate-suite-artifact.mjs`
3. `node scripts/lib/bench/test-artifact-resolution.mjs`
4. `node scripts/bench/test-search-eval-readiness-builder.mjs`
5. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
6. `node scripts/ci/dag-runner-beir-gate.test.mjs`

#### C) Quickcheck remained green

Verified pass:

1. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/ci/run-corpus-governance-quickcheck.ps1`

Quickcheck was extended so it now also executes:

1. `scripts/lib/bench/test-suite-artifact-validation-core.mjs`
2. `scripts/bench/test-search-eval-diff-core.mjs`
3. `scripts/bench/test-search-eval-readiness-core.mjs`

### Closeout corrections implemented

Resolved in the final closeout pass:

1. MRR policy/code/tests/tempdoc are now aligned to one truth: active BEIR
   gating
2. the exported readiness core now fails fast with explicit validation errors
   for:
   - unsupported `capacityWindowMode`
   - missing `capacityWindowStartIso` for `post_contract_start_iso`
   - unexpected `capacityWindowStartIso` for non-post modes
   - malformed ISO timestamps
3. the builder wrapper now reuses the same validation path and surfaces the same
   explicit error text while keeping invalid invocations non-zero

### Critical assessment after implementation

What improved materially:

1. validation logic is now owned once instead of being split between CLI and
   artifact-resolution paths
2. search-eval diff semantics are now directly testable without going through
   process execution
3. readiness/report generation now has a reusable core instead of remaining
   trapped in a single builder script
4. wrapper files are materially smaller and easier to reason about

What stayed intentionally deferred:

1. no `suite-loader` decomposition in this stream
2. no `run-ranking-experiments.ps1` / Slice 3 work in this stream
3. no intentional dormant/deferred policy activation beyond behavior already
   present before refactor

### Stream verdict

Verdict:

1. Node eval-core modernization is **fully implemented**
2. public behavior stayed stable across diff/validation/readiness surfaces
3. direct-core tests plus existing regressions plus quickcheck are **green**
4. the two remaining closeout items from the audit are now resolved:
   - MRR truth alignment
   - readiness-core invalid-input hardening + failure-path coverage
5. the next remaining long-term decomposition decision is now outside this
   completed stream

## 2026-03-06 BEIR lane hardening and centralization implemented

This follow-on slice finished the immediate BEIR/search-eval lane hardening
work that Phase 5 exposed.

### Implemented structure

1. The PowerShell BEIR runner remains the canonical metric producer.
   - `scripts/search/beir-eval-win.ps1` now delegates BEIR-lane pipeline
     parsing, qrels summarization, runtime gating, query execution, and
     per-mode aggregation to the new
     `scripts/search/lib/BeirEval.Search.psm1`.
2. The BEIR artifact contract is now explicit in both `metrics.json` and
   `metrics.v2.json`.
   - `metric_contract`
   - `qrels_summary`
   - `requested_pipeline`
   - `runtime_gates`
3. Strict runtime gates are now default behavior for the BEIR lane.
   - dense / hybrid requests fail unless chunk vectors are ready
   - SPLADE requests fail unless SPLADE readiness is true
   - LambdaMART requests fail unless a real active LM model exists
   - query failures fail the run by default unless explicitly opted out
4. Node-side BEIR consumers now centralize on `metrics.v2.json`.
   - added shared BEIR artifact selection helper
   - DAG manifests now emit explicit `metrics.json`, `metrics.v2.json`, and
     selected-artifact fields for baseline/candidate paths
5. Validator / parity / diff tooling now agrees with production semantics.
   - validator uses linear-gain nDCG
   - v1 -> v2 conversion preserves the new BEIR contract fields
   - suite validation and parity checks require those fields for BEIR v2

### Verification completed

Verified pass:

1. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/search/test-beir-eval-metrics-lib.ps1`
2. `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/search/test-beir-eval-search-lib.ps1`
3. `node scripts/search/test-beir-eval-indexing-integration.mjs`
4. `node scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs`
5. `node scripts/lib/bench/test-suite-artifact-validation-core.mjs`
6. `node scripts/lib/bench/test-beir-artifact-selection.mjs`
7. `node scripts/ci/dag-runner-beir-gate.test.mjs`
8. `node scripts/ci/dag-runner-search-eval-rank-report.test.mjs`
9. `node scripts/lib/bench/test-artifact-resolution.mjs`
10. `node scripts/bench/test-search-eval-diff-core.mjs`
11. `node scripts/bench/test-diff-search-eval-suite.mjs`
12. `node scripts/bench/test-search-eval-readiness-builder.mjs`
13. `node scripts/bench/test-search-eval-readiness-core.mjs`
14. `node scripts/bench/check-v1-v2-parity.mjs --legacy scripts/bench/baselines/search-eval-beir-arguana-baseline.metrics.json --v2 scripts/bench/baselines/search-eval-beir-arguana-baseline.metrics.v2.json --kind search-eval-beir`

### Deliberately deferred

This slice intentionally did **not** try to:

1. centralize Java system-test metric infrastructure
2. re-run live acceptance evals on `scifact`, `nfcorpus`, and
   `courtlistener_Plain_Text` in the same implementation turn
3. solve the broader GPU-phase orchestration problem for long-running Phase 5
   campaigns

### Impact on the larger program

1. Tempdoc 259 remains completed, but the BEIR lane is now materially safer for
   representative-eval continuation work.
2. Tempdoc 251 Phase 5 should restart from this post-hardening baseline.
3. Tempdoc 261 should coordinate future Phase 5 runs assuming strict gates and
   explicit `metrics.v2` selection are now durable repo behavior.
