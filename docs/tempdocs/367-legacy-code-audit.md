---
title: "367: Legacy Code Audit & Module Restructuring"
status: active
created: 2026-03-28
last-updated: 2026-05-12
phase-1-superseded-by: "ADR-0017 (module decomposition), docs/explanation/19-module-architecture.md, docs/reference/issues/backend-tech-debt.md"
depends_on: []
---

# 367: Legacy Code Audit & Module Restructuring

## Summary of Completed Work

### Phase 1: Dead Code Removal (original branch, merged 2026-04-06)

Removed ~17,200 lines across ~206 files. Two entire modules eliminated
(`ai-worker`, `app-ai`), one hollow interface removed (`IndexingAiService`).

| Commit | What | Files | Lines |
|--------|------|-------|-------|
| `8898179fb` | T1: Dead code (core, test-support, ui, indexer-worker) | 14 | -761 |
| `eb440dc33` | T2: Stale coverage-gap tests | 12 | -846 |
| `8c04440b3` | T3a: modules/ai-worker (unused 4th process) | 46 | -5,849 |
| `755af4da5` | T3b: ai-bridge LLM pipeline + app-ai translator | 49 | -5,589 |
| `38d3a0849` | Cascading: proto, config, gRPC clients, pipelines | 21 | -2,458 |
| `6e14df003` | Scripts/docs cleanup | 12 | -172 |
| `87f02dc6c` | Dead TranslatorTelemetry | 2 | -174 |
| `48b584e3e` | Integrity fixes (lint task, Noop, deps, exemptions) | 3 | -96 |
| `05fc545d5` | Remove IndexingAiService chain (hollow interface) | 31 | -927 |
| `a34b08e62` | Collapse app-ai into app-inference | 11 | -154 |
| `6e4b70e62` | Replace LocalIntentTranslatorConfig in embedding SPI | 5 | +3 |
| `8ecb7885c` | Delete 14 dead frozen files from second-pass audit | 14 | -178 |

### Phase 2: Module Restructuring (2026-04-06)

Split the monolithic `ai-bridge` module into 3 focused modules:

| Old | New | Package | Files | Purpose |
|-----|-----|---------|-------|---------|
| `ai-bridge/gpu/*` | `gpu-bridge` | `io.justsearch.gpu` | 5+1 test | GPU/VRAM detection |
| `ai-bridge/prompts/*` | `prompt-support` | `io.justsearch.prompts` | 8+1 test | Prompt template loading |
| `ai-bridge` (residual) | `ai-backend` | `io.justsearch.aibackend` | 30+12 tests | Embedding SPI, engine monitoring, backend registry |

Additional cleanup:
- Deleted `modules/ai-engine-native/` — 721 MB of untracked DLLs
- Deleted vacuous `DtoRoundTripTest`
- Moved `RrfFusionHarness` from `src/main` to `src/test`
- Removed stale `promptTemplateLint` root task
- Added `loadMetadataOnly()` to decouple `CapabilitiesService` from Handlebars

### End-to-End Validation (2026-04-06)

Full SciFact pipeline run (5,183 docs, 300 queries) after restructuring.

| Mode | Measured | Baseline | Delta | Verdict |
|------|----------|----------|-------|---------|
| lexical | 0.6623 | 0.661 | +0.2% | PASS |
| hybrid | 0.7359 | 0.736 | -0.0% | PASS |

Zero functional regressions. All enrichment stages (embedding, SPLADE,
NER, chunks) completed. GPU active at 72-88%.

---

### Phase 3: DAG-Runner Family + Workflow / Doc Burndown (2026-05-12)

Driven by slice 3a-1-8f §B.12 / §B.13 / §B.14 (governance kernel
amendments + audit findings). Completed the post-`a9c484f59`
audit gap: 2026-03-16 commit `a9c484f59` ("refactor: remove legacy
bench, eval, and shared lib infrastructure") deleted `scripts/lib/`,
`scripts/bench/`, `scripts/eval/` and ~239 files, but left several
classes of stranded code referencing the deleted substrate. The
commit message claimed completion ("All evaluation and benchmark
capabilities are now in `scripts/jseval/`"); the audit gap took 2
months to surface.

The Phase 1 frozen-file methodology (last-modified > N months ago,
verify imports, verify callers) extended here to a *consumer-side*
audit (does the file still load? does anything live invoke it?).
Slices 487 / 491 had already retired the Agent Battery harness in
this style; slice 3a-1-8f §B.12–§B.14 retired the gate cluster
+ resilience + governance + workflow yamls + canonical docs.

#### Phase 3 deletions

| Cluster | Files | Lines (approx.) | Note |
|---|---|---|---|
| Gate chain (slice §B.12) | 4 files | ~1,200 | `gate.ps1`, `local-agent-gate-win.ps1`, broken `dag-runner-local-agent-gate.mjs` + test |
| Buf preflight (slice §B.13) | 5 files | ~475 | `bootstrap-buf-win.ps1`, `run-buf-preflight-win.ps1`, `run-buf-win.ps1`, `capture-arch-preflight-win.ps1`, `run-archunit-preflight-win.ps1` |
| Stranded DAG runners + tests (§B.14) | 8 files | ~1,000 | `dag-runner-agent-battery.{mjs,test}`, `dag-runner-workflow-quality-gate.{mjs,test}`, gRPC smoke / soak / governance runners + tests |
| Resilience + governance trees (§B.14) | entire `scripts/resilience/` + entire `scripts/governance/` | ~3,500+ | All cascade-dead: consumed by deleted DAG runners; no live invokers |
| Agent battery legacy (§B.14) | 3 files | ~500 | `agent-battery-core.mjs`, `run-agent-live-battery.{mjs,-win.ps1}` — superseded by slice 487/491's `agent-battery-url-probe.mjs` |
| Workflow yamls (§B.14) | 5 yamls | ~600 | `rr219-{governance-nightly,soak-weekly}.yml`, `track-g-report-win.yml`, `claim-a-report-win.yml`, `agent-live-eval-nightly.yml` — all invoke deleted code |
| Canonical docs (§B.14) | 14 docs | ~2,500 | `docs/governance/` (8 files), 3 how-tos, 3 contracts, `benchmark-eval-compatibility-matrix.md`, `dag-runner-operations.md` |
| Orphan utilities | 1 file | ~80 | `scripts/architecture/diff-arch-preflight.mjs` (no callers) |

**Approximate Phase 3 total: ~9,800 lines deleted across ~45 files
+ entire subdirectories** (`scripts/resilience/`, `scripts/governance/`,
`docs/governance/`).

Combined with Phase 1 (~17,200 lines) and Phase 2's `ai-engine-native/`
(721 MB of DLLs), the legacy-burndown total since 2026-03-28 is
~27,000 lines of code + ~721 MB of binaries.

#### Phase 3 repairs (not deletions)

- `scripts/dev/justsearch-dev-mcp/log.mjs` — stripped the eager
  `createWorkflowRunStore` import from the deleted
  `scripts/lib/workflow-telemetry.mjs`. Was a silent module-load
  crash that would have fired on the first MCP tool invocation
  (the dev-MCP server has been broken-but-untested for ~2 months
  because no MCP tool was invoked in any session). Dev-MCP server
  now loads cleanly.
- `docs/decisions/0009-custom-dag-engine-ci-orchestration.md` —
  status flipped `stable` → `superseded`. The ADR records the
  original (correct, for its time) decision; the implementation it
  enabled was deleted by `a9c484f59` and finally cleaned up by
  this Phase 3.
- `CLAUDE.md` "Verification Workflow" step 5 + step 6 — pre-merge
  checks listed individually (no wrapper); nightly-workflow list
  trimmed from 4 to 1 (only `phase-3-observability-nightly`
  survives).
- `docs/decisions/0026-manual-ci-triggering.md`, `docs/explanation/09-testing-strategy.md`,
  `docs/future-features/open-source-readiness.md`,
  `docs/reference/contributing/agent-guide.md`,
  `docs/observations.md`, slice 3a-1-8f §B.14 — references to
  deleted infrastructure updated or removed.

#### Pattern recognition (the structural lesson)

Phase 1's 17,200-line burndown caught dead Java code via the
frozen-file inventory + ArchUnit unreferenced-code gate. Phase 3
caught dead Node/PS code via a *different* signal: a refactor commit
(`a9c484f59`) whose message claimed completion but whose execution
left consumers stranded. The signal:

- DAG runners + wrappers that **import from a deleted module**;
- Workflow yamls that **invoke deleted scripts**, often masked by
  `continue-on-error: true` + literal "# TODO: directory doesn't
  exist" comments where the steps were *not* commented out;
- Canonical docs (`status: stable`) that **continue to advertise
  the deleted infrastructure as authoritative**;
- CLAUDE.md "Verification Workflow" step 6 + agent-guide referenced
  the dead workflows as dispatchable signals.

Two-month gap between `a9c484f59` (2026-03-16) and the cleanup
(2026-05-12) because:

1. CI was `workflow_dispatch`-only (ADR-0026); the dead workflows
   never auto-fired, so their hard failures stayed invisible.
2. Dev-MCP's broken module loaded lazily on first MCP tool
   invocation; if no session exercises that path, the crash never
   manifests.
3. Canonical-doc references propagate the illusion of liveness —
   agents (including me, earlier in this conversation) trust
   `status: stable` reference docs without verifying file existence.

**Suggested process change:** for future refactor cleanups that
claim "X capabilities are now in Y," follow with a `git grep`
audit against deleted module names + a `git ls-files`-check of
every file path referenced from canonical docs. The
`workflow_dispatch`-only CI policy + lazy-module-load pattern make
silent rot the default failure mode if this discipline is skipped.

#### Phase 3 known issues — resolved + remaining

Crossed-out items were closed by Phase 3. The remaining items are
new findings or pre-Phase-3 items still open.

##### Resolved by Phase 3

- ~~`dag-runner-grpc-soak.mjs` refs deleted `:modules:app-ai` — but
  script already broken (missing `scripts/lib/`)~~ — **resolved**:
  `dag-runner-grpc-soak.mjs` + entire `scripts/resilience/` tree
  deleted.

##### Remaining

(See §"Known Issues" below — only the Phase 1 / Phase 2 entries
remain.)

#### Phase 3 supplemental sweep (2026-05-12, post-§B.14)

User prompt: "investigate further if there is more dead code." Second
audit pass after §B.14 surfaced an additional cluster — `-win.ps1`
wrappers that were exclusively invoked by the deleted DAG runner
family, plus their vendored dependencies. The pattern: scripts that
each existed for one DAG-runner step (`psscriptanalyzer`,
`run-smoke`, `package`, `verify-installer-ui-browser`,
`verify-tauri-ui-ready`, `run-parallel-safe-verify`) became orphan
when the DAG runner was deleted; the wrappers and their downstream
tool dependencies stayed in the repo because the deletion commit
audited at the DAG runner's level, not at the wrapper level.

**Supplemental deletions:**

| File / directory | Reason |
|---|---|
| `scripts/ci/run-psscriptanalyzer-win.ps1` | DAG-runner step orphan |
| `scripts/ci/run-smoke-win.ps1` | DAG-runner step orphan |
| `scripts/ci/run-parallel-safe-verify-win.ps1` | DAG-runner step orphan |
| `scripts/ci/package-win.ps1` | DAG-runner step orphan (NOT package-installer-win which is alive) |
| `scripts/ci/verify-installer-ui-browser-win.ps1` | DAG-runner flag orphan |
| `scripts/ci/verify-tauri-ui-ready-win.ps1` | DAG-runner flag orphan |
| `scripts/tools/PSScriptAnalyzer/` (entire vendored tree, ~50 files) | Cascade-orphan of `run-psscriptanalyzer-win.ps1` |
| `scripts/dev/justsearch-dev-mcp.cjs` | Superseded by `.mjs` version (`.mcp.json` invokes the `.mjs`); the `.cjs` was only cited in stale `scripts/README.md` |
| `scripts/tools/` (empty parent dir) | Cascade-orphan |

**Doc rewrite:** `scripts/README.md` rewritten to match current state —
removed references to `scripts/perf/`, `scripts/bench/README.md`,
`scripts/gate.ps1`, `run-parallel-safe-verify-win.ps1`,
`justsearch-dev-mcp.cjs`. Added jseval (the canonical replacement)
to the top-level guide.

**Pattern distinction surfaced:** "no script caller" ≠ "no caller."
Several files initially looked orphan because no `.mjs`/`.ps1`/yaml
invokes them, but turned out to be **human-facing manual tools**
referenced in user-facing how-tos. Examples:

- `scripts/dev/dev-all.ps1` (referenced by `docs/how-to/use-ui.md:243`)
- `scripts/dev/cleanup.ps1` (similar manual-tool pattern)
- `scripts/setup/preflight.ps1` (referenced by `scripts/README.md` quick-start)

These are alive even though no automation invokes them. Honest framing:
the rot signal is **second-order claim of liveness without first-order
exercise** — for human-facing tools, "human runs it" is the first-order
exercise; "documented in a how-to" is a valid second-order signal
*if* the how-to itself is alive.

**Not deleted (deferred — defensible-as-occasional-use):**

- `scripts/ops/capture-{capabilities,infra-health,infra-health-grpc}.sh` — debug utilities for ops; no live invoker but plausibly invoked by humans during incidents.
- `scripts/ui/{force-rerank-skip,frame-profiler,prepare-phase8-artifacts}.sh` — debug utilities; phase 8 = historical, but `force-rerank-skip` may be a live debug tool.
- `scripts/search/{convert-*,extract-*,vlm-extract-sample}.py + sweep-cc-weights.sh` — data-conversion utilities; produced corpus files are committed, so the scripts are unlikely to be re-run.
- Phase 1 entries in the §Known Issues block below.

Future audit pass could apply the human-facing-vs-automated test
to each of these and propose per-tool deletion.

**Phase 3 supplemental total: ~8 files + entire vendored `PSScriptAnalyzer` tree (~50 files) + README rewrite.**

---

### Phase 4: Functionality audit — what was deleted, what survives, what would I use (2026-05-12)

User prompt (post-supplemental, autonomous):

> "investigate your deleted code and understand all of its functionalities.
> then investigate which of that functionality is still possible/exists on
> main after deletion and which is completely lost. then critically
> analyze which of the missing functionality you could imagine using."

This phase is **analysis, not deletion**. The session deleted 192 files
across 13 clusters. Phase 3 + supplemental answered "what was dead?";
Phase 4 answers "what was the dead code actually for, and how much of
that intent now has no home on main?"

#### Methodology

For each cluster: (a) read the deleted files' purpose from headers /
schemas / commit messages of original introduction, (b) `git grep` for
inbound references on main (Java tests, ci.yml, surviving scripts,
non-tempdoc canonical docs), (c) classify functionality as
**survivor**, **partial-survivor**, or **lost**, (d) score each lost
capability for "would-have-used" value on a 3-point scale (low / medium
/ high) reflecting how easily I could imagine wanting it in future
work.

#### Cluster A: RR-229 release-readiness pipeline (`scripts/governance/bench-meta/`)

**Original purpose:** 5-dimension uncertainty-closure framework for
promoting a new ranking policy (`adaptive-a` vs `static-default`).
Each dimension produces an evidence artifact with a pass/fail verdict:

| ID | Capability | Threshold logic |
|---|---|---|
| RR-U1 | Objective alignment — bootstrap CI on Spearman ρ between human rankings and utility scores | `mean ρ ≥ 0.70`, CI lower bound `≥ 0.60`, ≥200 paired judgments |
| RR-U2 | Coverage audit — query-bucket sufficiency across 5 dimensions (scale / content / structure / language / query_class) | ≥40 per required bucket; 15 required buckets total |
| RR-U3 | Label sensitivity — winner-stability across label perturbations (anchor vs supplemental) | Winner sign + magnitude consistent |
| RR-U6 | Drift-trigger backtest — precision/false-alarm on historical drift windows | precision ≥0.70, FAR ≤0.15 |
| RR-U7 | Transfer consistency — winner direction + guardrails across 4-stage rollout ladder (offline → replay_shadow → limited_interleaving → gradual_rollout) | ≥1000 queries per live stage; consistent direction |
| RR-229 | Aggregate evidence-readiness manifest combining U1+U2+U3+U7 verdicts | GO/NO-GO |

**Closed program.** Tempdoc 229 ran 14 cycles, final decision recorded
2026-02-22 ("static default selected; adaptive path blocked-by-evidence").
The relocation to `scripts/governance/bench-meta/` (commit `750b6e845`,
2026-02-23) was a holding move for "future re-execution" that never
came; the relocation introduced no new consumer.

**Survivors on main:** none of the RR-U primitives. `jseval` covers
nDCG/recall + quality-regression but does not implement bootstrap CIs on
ρ, structured-bucket coverage audit, or staged-rollout transfer
checks.

**Lost capabilities (would-have-used):**
- **RR-U2 coverage audit (high value).** The "≥40 per bucket across 5
  dimensions" check is a *generic* eval-dataset quality gate. Any
  future eval dataset addition (BEIR variant, MultiHop-RAG expansion,
  custom corpus) would benefit from running this audit on it. Easily
  reusable.
- **RR-U7 transfer consistency (medium value).** The 4-stage ladder
  + winner-direction check is specifically scoped to a live rollout
  process JustSearch doesn't currently have (no offline / shadow /
  interleaving stages exist). Low immediate utility, high if/when a
  rollout discipline is reintroduced.
- **RR-U1 bootstrap-ρ (medium value).** Generic primitive (bootstrap CI
  on Spearman ρ between two ranking vectors). Useful for any "is my
  proxy metric correlated with judgment X?" question — comes up
  naturally during reranker tuning. Easily reimplementable from scratch
  (~50 LOC), so "lost" doesn't mean "irrecoverable."
- **RR-U3 label sensitivity (low value).** Only valuable if multiple
  labelers exist; JustSearch eval today is single-labeler.
- **RR-U6 drift trigger backtest (low value).** Tied to a specific
  closed program; not generic.

**Verdict:** Cluster A deletion was correct (closed program, no live
consumer) but lost two generically-useful primitives (RR-U2, RR-U1).
Restorable from `git show 750b6e845~` if either is needed; not worth
preserving in-tree as latent.

#### Cluster B: Release-resilience gate framework (`scripts/governance/resilience/`)

**Original purpose:** 23-gate release-promotion framework (RG-001 …
RG-023) that evaluated a release candidate's evidence index against
required artifacts:

| Gate band | Examples |
|---|---|
| Evidence-presence | RG-001 (`benchmarkScorecard` present), RG-003 (`ragDecision` present), RG-004 (`agentManifest` present) |
| Evidence-verdict | RG-008 (`beirDecision` pass), RG-015 (`controlLoopConformance` not fail), RG-022 (`soakReadiness` pass) |
| Comparability | RG-010 (`ragDecision` comparable), RG-011 (`beirDecision` comparable) |
| Phase-A graduation | RG-012 (`agentScorecard` Phase-A eligible) |
| Integrity | RG-019 (policy version matches), RG-023 (zero artifact-presence contradictions) |
| Lifecycle | RG-006 (no infra failure), RG-007 (no teardown failure) |

The companion builders produced the intermediate artifacts
(`build-release-evidence-index`, `build-runtime-resilience-budget-snapshot`,
`build-runtime-resilience-control-loop-conformance`,
`build-runtime-resilience-soak-readiness`, `build-runtime-resilience-history-readiness`,
`build-runtime-resilience-lane-triage`). All shared a `dag-runner.mjs`
in the same dir, deleted with the rest.

**Survivors on main (broken):** `scripts/ci/evaluate-release-governance.mjs`
(348 lines) survives but:
1. its default `--policy` argument points at the deleted
   `release-resilience-gates.v1.json`;
2. its only test (`test-evaluate-release-governance.mjs`) was deleted;
3. its only consumers (`rr219-resilience-governance-nightly.yml` +
   `dag-runner.mjs`) were deleted.

This is an orphan-with-broken-default; followup task #9 logged.

**Tempdoc 219 (Runtime Resilience Hardening)** is still
`status: active` in front-matter but had **no commit activity** under
its file after 2026-02-27, ~3 weeks before `a9c484f59` deleted the
underlying DAG infrastructure. The deletion sweep took out 219's
runtime side; the tempdoc is stale-active.

**Lost capabilities (would-have-used):**
- **Generic-policy evaluator pattern (high value).** The `policy.json`
  + `evidence-index.json` + 23-rule evaluator + waiver-by-reasonCode
  pattern is a reusable primitive for any future release-gate work.
  The `field` / `op` / `expected` / `severity` / `allowWaiver` schema is
  competent design. Even if 219's specific gates are stale, the
  evaluator engine (`evaluate-release-governance.mjs`) is generic;
  refactoring it to `evaluate-policy.mjs` (drop the hardcoded default
  path) keeps the capability.
- **Reason-code → owner mapping (medium value).** A small but
  thoughtful pattern — every blocking reason has a routed owner. Useful
  for any future on-call/escalation surface.
- **Budget snapshot + control-loop conformance + soak readiness
  builders (low value).** Tightly coupled to runtime resilience
  artifacts that no longer exist on main. Functionally lost without
  the upstream artifacts.

**Verdict:** Cluster B's evaluator engine is salvageable and should
not be silently orphaned. Followup #9 should decide between "promote
to generic evaluator" or "delete cleanly."

#### Cluster C: gRPC resilience contracts + soak/calibration tooling (`scripts/resilience/`)

**Original purpose:** Runtime-resilience evidence pipeline:
- `contracts/rpc-retry-ownership-matrix.v1.json` — declares retry /
  circuit-breaker / deadline / idempotency owner for 29 gRPC methods
  across 5 services (`SearchService`, `IngestService`, `HealthService`,
  `AiService` v1, `HealthService` v1).
- `contracts/grpc-retry-policy-profiles.v1.json` — named retry policy
  profiles (e.g., `grpc-rkc-unavailable-v1`) referenced by matrix rows.
- `calibration/dag-runner-grpc-smoke.mjs` — gRPC smoke calibration
  pipeline (5-step DAG, 4 edges).
- `faults/dag-runner-grpc-soak.mjs` — gRPC retry soak pipeline (smoke /
  soak modes, 5–7 steps).
- `faults/grpc-fault-scenarios.v1.json` — Toxiproxy fault scenarios.
- `faults/toxiproxy-spike-scenarios.v1.json` — Toxiproxy spike profile.
- `validate-rpc-retry-ownership.mjs` — coverage check: every `.proto`
  RPC method has a declared ownership row.

**🚨 CRITICAL REGRESSION:** `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.json`
has **2 live Java test consumers** that the session deletion broke:
1. `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteKnowledgeClientRetryConfigTest.java:218`
2. `modules/ipc-common/src/test/java/io/justsearch/ipc/grpc/GrpcRetryServiceConfigTest.java:122`

Both walk-up parents looking for `scripts/resilience/contracts/`; when
the dir doesn't exist they throw "Unable to resolve repo root from …"
The `docs/observations.md:38` entry that records the failure as
"pre-existing" is misclassified — it's a regression from this session.
Followup task #10 logged with priority. The companion canonical doc
`docs/reference/contracts/rpc-retry-ownership-policy.v1.md` (status
`stable`) was also deleted; followup #12.

**Survivors on main:** none of the script tooling. `jseval` has no
gRPC fault-injection or soak counterpart. The matrix data + policy
profiles are in the git index but absent from working tree.

**Lost capabilities (would-have-used):**
- **rpc-retry-ownership matrix as a declared contract (high value).**
  Even without an executable consumer, the declaration-of-record for
  retry semantics across 29 RPC methods is *valuable architecture
  documentation*. Two Java tests use it; the canonical policy doc
  treated it as source-of-truth. **This file deserves restoration**,
  ideally inside the `modules/ipc-common/src/main/resources/` tree so
  its lifecycle is bound to the proto definitions it covers.
- **Toxiproxy fault-injection scenarios (medium value).** Even if the
  surrounding pipeline is dead, the catalog of fault profiles (network
  partition, latency, slow_close, etc.) is reusable scaffolding for any
  future resilience work. Not high-priority to restore.
- **gRPC soak pipeline (low value).** Built atop the deleted
  orchestration substrate; rebuilding from scratch would be easier than
  resurrecting. The *intent* (run soak workload, classify retries,
  compare baseline vs candidate) survives only as institutional memory.

**Verdict:** The retry-ownership matrix + policy profiles **should be
restored** (followup #10 + #12 — these are not "would-have-used", they
are *currently-used*). The soak/calibration pipeline is correctly
deleted.

#### Cluster D: Live agent battery (`scripts/ci/` DAG runners + harness)

**Original purpose:** Live-model agent eval harness — runs a real
backend (HEAD + Worker + Brain processes), configures a GGUF model,
activates AI runtime, ingests corpus, streams agent scenarios via
SSE, evaluates transcripts against deterministic expectations
(expected first tool call, oracle path candidates, expected min tool
calls, efficiency diagnostics). 1,429 lines of substantive logic in
`agent-battery-core.mjs` + 6-step DAG runner.

**Survivors on main:**
- `modules/system-tests/.../AgentBatteryTest.java` — Java integration
  test (tempdoc 186) with **different scope**: in-process
  `AgentLoopService` + stub Operations, not real Brain/SSE/IPC. Sound
  contract-level coverage, weaker live-stack integration coverage.
- `scripts/jseval/jseval/agent_retrieval_eval.py` — retrieval-quality
  eval on MultiHop-RAG (Phase 1: deterministic recall@k; Phase 2:
  optional Claude Code CLI comparison). Different capability — does not
  exercise JustSearch's own agent loop, only retrieval.
- `scripts/ci/agent-live-battery-scenarios.v1.json` — scenario data
  (orphan).
- `scripts/ci/build-agent-live-scorecard.mjs` — scorecard builder
  consuming battery output (orphan).
- `scripts/ci/test-build-agent-live-scorecard.mjs` — test for above
  (orphan).
- `scripts/ci/test-run-agent-live-battery-eval.mjs` — **broken**:
  imports the deleted `run-agent-live-battery.mjs`. Followup task #11.

**Lost capabilities (would-have-used):**
- **Live-stack agent integration test (high value).** Running real
  Brain + Worker + scoring real SSE transcripts is the only test that
  catches Brain/HEAD wire-protocol drift, model-config validation, and
  IPC contract failures simultaneously. `AgentBatteryTest.java` covers
  the contract path; the deleted Node battery covered the
  end-to-end-live path. **This is a meaningful loss for the
  test-pyramid coverage.** Slices 487 / 491's `agent-battery-url-probe.mjs`
  replaces some of this for URL-emission probing but not for the
  general scenario-and-transcript pattern.
- **Transcript-vs-expectation evaluation engine (medium value).** The
  `evaluateTranscript` / `evaluateDeterministicChecks` /
  `computeEfficiencyDiagnostics` / `normalizeFirstToolCallOracle`
  primitives are reusable for any future agent eval. About 400 LOC of
  thoughtful logic. Recoverable but not trivially.
- **Scenario format + scorecard schema (low value).** The
  `agent-live-battery-scenarios.v1.json` and scorecard schema are
  data-shape decisions; reconstructable if needed.

**Verdict:** Cluster D's harness deletion was correct (workflow was
dead, DAG runner imports broken since `a9c484f59`), but the eval
engine + scenario semantics had value beyond the dead workflow. The
4 sidecar orphans (followup #11) should be deleted to reduce noise.
If live-LLM agent regression coverage is desired in future, the
`agent-battery-url-probe.mjs` pattern from slices 487 / 491 is a
better starting point than restoring the 1,429-line monolith.

#### Cluster E: Workflows + architecture preflight + win wrappers

**Original purpose:**
- 5 workflow yamls (rr219 governance/soak, track-g, claim-a,
  agent-live-eval) — all `workflow_dispatch`-only, all referenced
  scripts deleted by `a9c484f59`, none of them auto-fired, all stranded
  for 2 months.
- 4 architecture preflight (`capture-arch-preflight-win.ps1`,
  `diff-arch-preflight.mjs`, `run-archunit-preflight-win.ps1`,
  `run-buf-preflight-win.ps1`) — chained orphan: each invoked the
  next, the top of the chain (`capture-arch-preflight`) had no
  invoker.
- 6 `-win.ps1` wrappers + `gate.ps1` — covered Phase 3 above.

**Survivors on main:** `scripts/architecture/module-deps.mjs` +
`ipc-usage.mjs` (the real architecture analysis); both have ci.yml
callers. The buf workflow is in `scripts/wire-contract/` (npm-pinned),
unrelated to the deleted `run-buf-preflight-win.ps1`.

**Lost capabilities (would-have-used):**
- **Workflow-quality gate (low value).** The deleted
  `dag-runner-workflow-quality-gate.mjs` measured workflow telemetry
  health. Useful for any future workflow expansion but not currently
  active (manual-only CI per ADR-0026).
- **PSScriptAnalyzer integration (negligible).** Vendored linter for
  PowerShell scripts; with most -win.ps1 wrappers deleted, the demand
  for linting them is much lower.
- **`diff-arch-preflight.mjs` (negligible).** Diffed two architecture
  preflight outputs — only useful if the preflight pipeline itself was
  alive.

**Verdict:** Cluster E deletion was clean. No salvage value.

#### Cluster F: Canonical docs (governance / how-to / reference contracts)

**Original purpose:**
- 8 `docs/governance/*.md` — runtime resilience SLO + error-budget
  policy + control-loop conformance contracts; all `status: advisory`
  in `G0` (observe-only); described intent + invariants for the
  resilience program.
- 3 `docs/how-to/validate-*.md` (agent-quality, performance,
  workflow-quality) — runbooks for the deleted batteries.
- 4 `docs/reference/contracts/*.md` — `benchmark-eval-contract` (was
  `status: stable`), `grpc-soak-comparability.v1` (was
  `status: stable`), `rpc-retry-ownership-policy.v1` (was
  `status: stable`), `dag-runner-operations`.
- 1 `docs/reference/benchmark-eval-compatibility-matrix.md` — lane ×
  schema × CI-maturity table.

**Survivors on main:** `docs/reference/contracts/workflow-telemetry-contract.v1.md`
(canonical telemetry shape, not deleted), `docs/explanation/09-testing-strategy.md`
(updated in Phase 3 to remove dead-lane references).

**Lost capabilities (would-have-used):**
- **`rpc-retry-ownership-policy.v1.md` (high value, regression).** As
  noted in Cluster C — this was canonical for an active code domain.
  Followup #12 captures.
- **Runtime resilience SLO + error-budget policy (medium value).**
  Even in `G0` advisory mode, the SLO definitions and budget-state
  vocabulary are reference architecture for resilience work. If
  resilience work resumes (tempdoc 219 has been silent ~3 months),
  these docs would need to be reconstructed.
- **`benchmark-eval-contract.md` (medium value).** The artifact-schema
  contract for `bench-suite.v2` etc.; if jseval ever needs to emit
  comparable artifacts to a future external consumer (CI dashboard,
  cross-project comparison), this contract is the starting point.
- **`grpc-soak-comparability.v1.md` (low value).** Tied to the deleted
  soak pipeline; reconstructable if soak resumes.
- **How-to runbooks (low value).** Documented dead infrastructure.
  Correctly deleted.

**Verdict:** Cluster F deletion has one regression
(`rpc-retry-ownership-policy.v1.md` — followup #12) and one
"institutional memory loss" risk (resilience SLO docs). Other deletions
were correct.

#### Regressions discovered during Phase 4

| ID | File / scope | Impact | Followup task |
|---|---|---|---|
| R1 | `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.json` (deleted) | 2 live Java tests break at runtime | #10 |
| R2 | `ci.yml:282-286` invokes deleted `test-build-runtime-resilience-*` + `test-validate-runtime-resilience-*` | `gh workflow run ci.yml` would fail on this step | #8 |
| R3 | `scripts/ci/evaluate-release-governance.mjs` (kept, orphan) | Hardcoded default points at deleted policy; only test deleted | #9 |
| R4 | `docs/reference/contracts/rpc-retry-ownership-policy.v1.md` (deleted) was canonical for live consumers | Canonical contract for active code surface is gone | #12 |
| R5 | 4 agent-battery sidecar files in `scripts/ci/` (`agent-live-battery-scenarios.v1.json`, `build-agent-live-scorecard.mjs`, `test-build-agent-live-scorecard.mjs`, `test-run-agent-live-battery-eval.mjs`) | Last one is broken-import; others are orphan | #11 |
| R6 | `docs/observations.md:38` entry mislabeled "pre-existing" | Misclassification of cause | (folded into #10) |

R1, R3, R4 are real regressions where the deletion took out load-bearing
state. R2, R5 are dead-code-not-deleted (missed cleanup). R6 is a
documentation classification error.

#### Critical analysis: what I would actually use

Ranked by personal "would-have-reached-for" intuition for likely future
work in this codebase:

1. **rpc-retry-ownership matrix + policy profiles + canonical doc
   (high, currently used).** Not "would use" — *is being used right
   now by 2 Java tests*. Restore is non-negotiable.
2. **Generic policy evaluator pattern (high, latent).** The
   `policy.json` + `evidence-index.json` + rule evaluator + waiver-by-
   reasonCode design is a reusable primitive. The slice 3a-1-8f
   governance kernel uses a similar shape; consolidating both into one
   generic `evaluate-policy.mjs` would be a clean improvement.
3. **RR-U2 coverage audit primitive (medium, latent).** Any future eval
   dataset addition has the same "is every required bucket sufficiently
   populated?" question. ~150 LOC reusable.
4. **Live-stack agent integration coverage (medium, latent).** Currently
   only `agent-battery-url-probe.mjs` exercises the live stack at this
   level; the deleted scenario+transcript pattern was broader. If
   agent-loop regressions become a class of bug (they have historically:
   slice 447 §X.12 wrong-component-name; ConfigWiringTest mismatch in
   observations), this coverage is worth rebuilding.
5. **Bootstrap-ρ primitive (low, easy-to-rewrite).** Generic enough but
   trivially reimplementable.
6. **Toxiproxy fault catalog (low, scaffolding only).** Useful only
   when/if soak work resumes.

Everything else (RR-U3/U6/U7, control-loop conformance, history
readiness, soak readiness, lane triage, the rr219 lane manifest
resolver) was tightly bound to the closed tempdoc 219 + tempdoc 229
programs. Reconstructing from scratch when needed will be cleaner than
preserving stale infrastructure.

#### Net Phase 4 recommendation

Three concrete actions:

1. **Restore (Cluster C regression):** Either
   - (a) revert the deletion of `scripts/resilience/contracts/` (4 files)
     + `docs/reference/contracts/rpc-retry-ownership-policy.v1.md`, OR
   - (b) move the matrix + profile JSONs into
     `modules/ipc-common/src/main/resources/` (binding their lifecycle
     to the proto + tests) and re-point the 2 Java tests + restored
     policy doc.
   Option (b) is the long-term-better fix. Followups #10 + #12.
2. **Remediate (Cluster B + agent battery dangle):**
   - Either delete or generify `scripts/ci/evaluate-release-governance.mjs`
     (followup #9). My preference: generify to
     `scripts/ci/evaluate-policy.mjs` so it can become the substrate
     for any future gate. The slice 3a-1-8f kernel might naturally
     adopt it if the truth-table grows beyond 6 ruleIds.
   - Delete 4 agent-battery sidecar files (followup #11).
   - Delete ci.yml step 282-286 (followup #8) — or rewrite the
     runtime-resilience guardrails if 219 ever revives.
3. **Accept (everything else):** The remaining deleted clusters were
   correctly retired; their loss is not actionable.

**Phase 4 produces no new deletions and no new restores; it produces
6 followup tasks (#8–#12 logged this session; one is sub-task of #10).**
The point of Phase 4 was to convert "we deleted N files" into "we know
which of them mattered, which were truly dead, and which we got
wrong" — which is what the user asked for.

#### Phase 4 resolution (2026-05-12, post-analysis)

User direction: restore live-consumer files in place; delete genuinely
orphan files; reject the "generify for future use" instinct.

**Restored (5 files):**

| Path | Reason |
|---|---|
| `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.json` | 2 live Java test consumers |
| `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.schema.json` | Companion schema |
| `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.json` | Declared contract for 29 gRPC methods |
| `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.schema.json` | Companion schema |
| `docs/reference/contracts/rpc-retry-ownership-policy.v1.md` | Canonical (`status: stable`) for above |

Verification: `./gradlew.bat :modules:ipc-common:test --tests GrpcRetryServiceConfigTest --rerun-tasks` → PASSED. The app-services equivalent (`RemoteKnowledgeClientRetryConfigTest`) is blocked by unrelated working-tree compile errors in conversation API tests (`HierarchicalShapeRunnerTest`, `SubstrateDrivenEngineTest`) — out of scope for this restore.

**Deleted (6 items):**

| Path | Reason |
|---|---|
| `scripts/ci/evaluate-release-governance.mjs` | Orphan: policy default, test, all callers deleted. Rejected "generify" — speculative |
| `scripts/ci/agent-live-battery-scenarios.v1.json` | Battery harness deleted |
| `scripts/ci/build-agent-live-scorecard.mjs` | Consumed battery output |
| `scripts/ci/test-build-agent-live-scorecard.mjs` | Tested above |
| `scripts/ci/test-run-agent-live-battery-eval.mjs` | Broken-import (imports deleted file) |
| `.github/workflows/ci.yml:282-286` (step) | Invoked deleted `test-build-runtime-resilience-*` / `test-validate-runtime-resilience-*` scripts |

**Net change:** -6 dead files / +5 live-consumer files / -1 ci.yml step. observations.md regression entry flipped `[x]` with resolution note. All 12 Phase 3 + Phase 4 follow-ups closed.

#### Pattern lesson — restore-in-place vs relocate

Considered relocating the retry-ownership JSONs into `modules/ipc-common/src/main/resources/` to bind their lifecycle to the proto + tests. Rejected:

- The matrix's `evidenceRefs` point at `modules/app-services/...`, so it doesn't naturally "live" under ipc-common.
- Relocation would require updates to 2 Java test paths + 1 canonical doc path + the matrix's internal refs.
- The original location was *canonical* (referenced by `status: stable` doc + 2 test paths), not accidental. The deletion was wrong; the fix is to undo the deletion, not to "improve" the structure.
- Reaching for relocation here is the same "compromise instinct" pattern called out in slice 3a-1-8f §B.12.6 (the gate.mjs episode) and confirmed in conversation: when the user asks for the simple fix, do the simple fix, don't smuggle in a refactor.

#### Phase 4 closure — remote-side audit + phase-1 archive (2026-05-13)

After the Phase 4 commits landed and pushed, two follow-up sweeps closed the session.

**Remote-side dead-code audit (3 passes, all clean).** Once Phase 3 + Phase 4 were on origin, the question shifted from "is dead code on main?" (no) to "is dead code on origin that isn't on main?" — a different surface the local audit didn't cover.

| Pass | Surface | Outcome |
|---|---|---|
| 1 | `gh api` sweep: branch protection, secrets, vars, workflows GitHub knows about, repo settings | Clean. Branch protection unreachable on tier (private + free), 0 secrets, 0 vars, 5 active workflows match local, no Pages/Wiki/Discussions, no human-filed issues. |
| 2 | Cross-ref URLs (`github.com/.../blob/main/...` links in canonical docs) | Clean. 3 such URLs repo-wide, all pointing at `docs/how-to/write-a-plugin.md` which exists. |
| 3 | `.github/` re-audit (CODEOWNERS, issue + PR templates, dependabot.yml) | Clean. No references to deleted infrastructure. |

**Pattern lesson — generalizes the Phase 3 lesson:** refactor cleanups in a GitHub-hosted repo need **three** audit surfaces:

1. **Local tree** (the Phase 3/4 surface): `git grep` against deleted module names, `git ls-files` against every path referenced from canonical docs.
2. **Remote-only GitHub config**: branch protection rules, secrets, variables, repo settings, Pages/Wiki/Discussions content, CODEOWNERS, issue + PR templates, dependabot config. Lives outside git but references paths in it.
3. **Cross-ref URLs** from canonical docs *to* remote paths (`blob/main/X` links). Spans both — local docs, remote-served paths.

Each surface has its own canonical record location. The first lives in git; the second only on GitHub's metadata layer; the third spans both. Phase 3/4 caught surface 1; the post-push sweep caught surfaces 2 + 3.

**Phase-1 orphan-tag archive operation (2026-05-13).** The audit additionally surfaced 2 local-only tags (`2.0`, `phase-1.done`) pinning an orphan commit lineage (`12776a7eff…` ← `b6072a09614e…`, "build(p1.15): initial import") with **zero merge-base with current main**. The lineage was an earlier "phase 1" project version, scrapped and rebuilt from scratch as the current `Phase-4a`-rooted main lineage. Last activity 2025-10-24, dormant ~7 months. Reachable only via the 2 tags; no branches, worktrees, stashes, or reflog hits.

Resolution: **preserved-but-removed via bundle archive**.

- Created `F:\justsearch-archive\justsearch-phase-1.bundle` (10 MB, both tags packed, `git bundle verify` passed).
- Wrote companion `F:\justsearch-archive\README.md` with SHA pointers + restore command.
- Deleted the 2 tags from the local JustSearch clone (`git tag -d 2.0 phase-1.done`).
- Optionally `git gc --prune=now` to reclaim the loose-object space immediately; lazy default (~2 weeks) also works.

The bundle lives **outside** `F:\JustSearch\` so agent tooling indexing the project never encounters it. Recovery is one command: `git clone F:/justsearch-archive/justsearch-phase-1.bundle <dest>`.

**Generalizable pattern for "preserve-but-remove" decisions:** `git bundle create` → archive directory outside the project tree → README documenting purpose + restore command → delete refs from source. Three properties this combination has that alternatives don't:

| | Bundle to F: + README + delete | Archive tags on origin (`refs/tags/archive/*`) | Permanent deletion |
|---|---|---|---|
| Data preserved | ✓ | ✓ | ✗ |
| Invisible to project tooling | ✓ | ✗ (tags show in `git tag -l`) | ✓ |
| Doesn't pollute origin's namespace | ✓ | ✗ | ✓ |
| Self-documenting purpose | ✓ via README | partial via `archive/` namespace | ✗ |

The trade-off the bundle approach takes is "you have to know `F:\justsearch-archive\` exists" — solved by the README and by recording the operation in this tempdoc.

---

## Frozen File Inventory (last modified 2+ months ago)

131 hand-written Java source files last modified before 2026-02-06.
Grouped by module, sorted by last-modified date within each group.

Legend: `src` = production source, `test` = unit/integration test,
`stub` = test fixture/helper, `IT` = integration test.

### modules/core — 12 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-01 | src | `analyzers/AnalyzerDescriptor.java` | Immutable record holding an analyzer's ID and human-readable description |
| 2025-11-01 | src | `analyzers/AnalyzerRegistry.java` | Interface for looking up AnalyzerDescriptor by ID from the engine's analyzer registry |
| 2025-11-01 | src | `search/SearchPort.java` | Core search port interface — single `search(Query)` → `Result` contract between layers |
| 2025-11-01 | test | `analyzers/AnalyzerDescriptorTest.java` | Verifies AnalyzerDescriptor record accessors return construction values |
| 2025-11-03 | src | `dto/Cursor.java` | Paging cursor DTO with mode/token/expiry fields and defensive-copy extras map |
| 2025-11-03 | src | `dto/Result.java` | Search result DTO carrying scored hits, facet counts, cursor, and metadata |
| 2025-11-06 | test | `dto/CursorTest.java` | Verifies Cursor rejects blank/null mode and defensively copies extras map |
| 2025-11-06 | test | `dto/ResultTest.java` | Verifies Result defaults metadata to unmodifiable empty map and defensive copies |
| 2025-11-14 | src | `dto/Query.java` | Search query DTO carrying limit/offset, filters, sort, clauses, cursor, and context map |
| 2026-01-21 | src | `util/DocumentTypeDetector.java` | Classifies documents (PDF, code, office, markdown, etc.) from MIME types and filename patterns |
| 2026-01-21 | src | `util/TokenEstimation.java` | Estimates token counts, computes safe LLM input budgets, and truncates content to fit context windows |
| 2026-01-21 | test | `util/DocumentTypeDetectorTest.java` | Tests detection by MIME type, filename pattern, and path extraction across all document categories |

### modules/app-api — 17 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-01 | src | `SearchResponse.java` | Stable app-API DTO for search responses — hits, facets, cursor, metadata |
| 2025-11-06 | test | `SearchModelsTest.java` | Verifies SearchRequest.Cursor normalises null mode, rejects blank tokens |
| 2025-11-14 | test | `SearchRequestTest.java` | Verifies SearchRequest record accessor correctness for all fields |
| 2025-11-03 | test | `SearchResponseTest.java` | Verifies SearchResponse metadata is defensively copied |
| 2025-11-18 | src | `stream/ChunkFormat.java` | Streaming chunk contract — splits text into chunks, validates START→DATA→END lifecycle |
| 2025-11-18 | src | `summary/SummaryRejection.java` | Structured rejection payload (reason code, i18n key, attributes) when summary is refused |
| 2025-11-18 | test | `stream/ChunkFormatTest.java` | Verifies ChunkFormat splits on whitespace boundaries and enforces lifecycle sequence |
| 2025-11-18 | test | `summary/SummaryRejectionTest.java` | Verifies SummaryRejection preserves exact casing of messageKey |
| 2025-12-09 | src | `SearchRequest.java` | Stable app-API DTO for search requests — limit/offset, filters, sort, clauses, cursor |
| 2026-01-15 | src | `knowledge/KnowledgeIngestRequest.java` | DTO for `POST /api/knowledge/ingest` carrying file paths to ingest |
| 2026-01-15 | src | `knowledge/KnowledgeIngestResponse.java` | DTO for ingest response — accepted count and error message |
| 2026-01-15 | src | `knowledge/KnowledgeStatus.java` | DTO snapshot for Knowledge Server status (state, queue, doc counts, enrichment coverage) |
| 2026-01-15 | src | `lifecycle/LifecycleSnapshotV1.java` | Contract-tested schema-v1 record for `/api/status` and `/api/health` |
| 2026-01-15 | src | `lifecycle/LifecycleState.java` | Enum: STARTING, READY, DEGRADED, ERROR, STOPPING, STOPPED |
| 2026-01-17 | src | `OnlineAiRuntimeControl.java` | Interface for operator control of llama-server lifecycle — restart policies, detach |
| 2026-01-17 | test | `SeparatorConstantDriftTest.java` | Drift-prevention test asserting section separator matches canonical literal |

### modules/indexing — 16 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-01 | src | `api/IndexApi.java` | Minimal interface for submitting IndexDocument to the index; nested `IndexDocument` record heavily used |
| 2025-11-01 | src | `runtime/CommitMetadataSource.java` | Interface producing SSOT-aligned key/value pairs to stamp on Lucene index commits |
| 2025-11-01 | src | `runtime/CommitMetadataValidator.java` | Interface validating commit metadata map against authoritative schema |
| 2025-11-01 | src | `runtime/IndexOpenGuard.java` | Interface enforcing analyzer/similarity/schema parity when a shard is opened |
| 2025-11-08 | IT | `invariants/InvariantSuiteIT.java` | Integration test for IndexMetadataParityGuard against real Lucene FSDirectory |
| 2026-01-15 | src | `rag/ContextBudgeter.java` | Builds multi-section RAG context string with strict character budget and overhead tracking |
| 2026-01-15 | test | `rag/ContextBudgeterTest.java` | Verifies overhead accounting and truncation within maxChars budget |
| 2026-01-15 | test | `chunking/ChunkSplitterTokenEstimateTest.java` | Verifies estimateTokens heuristic: length/3 for ASCII, word count for whitespace-rich, 1-per-char for CJK |
| 2026-01-17 | src | `rag/MmrSelector.java` | Maximal Marginal Relevance selection balancing relevance vs diversity via cosine similarity |
| 2026-01-17 | src | `rag/TokenAwareBudgeter.java` | RAG budgeter using actual token counts from llama-server `/tokenize`, falling back to character estimation |
| 2026-01-17 | test | `rag/MmrSelectorTest.java` | Verifies MMR prefers diverse second pick over near-duplicate |
| 2026-01-17 | test | `chunking/ChunkSplitterCsvModeTest.java` | Verifies CSV chunking never splits inside quoted fields with embedded newlines |
| 2026-01-17 | test | `chunking/ChunkSplitterJsonModeTest.java` | Verifies JSON chunking produces valid chunks without splitting inside string literals |
| 2026-01-22 | test | `chunking/ChunkSplitterContentAwareTest.java` | Verifies MARKDOWN mode avoids splitting inside fenced code blocks |
| 2026-01-22 | test | `chunking/ChunkSplitterCoreTest.java` | Core tests: overlap mechanics, boundary preference hierarchy, chunk metadata accuracy |
| 2026-02-03 | src | `chunking/ChunkIds.java` | Centralised chunk ID generation using UUID-based opaque IDs with `chunk:` prefix |
| 2026-02-03 | test | `rag/SeparatorConstantDriftTest.java` | Drift-prevention test: TokenAwareBudgeter delegates to ContextBudgeter separator |

### modules/ui — 18 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-12-06 | src | `api/EventBuffer.java` | Thread-safe in-memory ring buffer (max 50 events) for debug/observability events |
| 2025-12-27 | src | `ai/install/AiInstallStatus.java` | Mutable AI installation progress state persisted to `install-state.json` |
| 2025-12-27 | src | `ai/pack/AiPackImportStatus.java` | Persisted status for v2 offline AI Pack import operations |
| 2025-12-27 | src | `ai/pack/AiPackManifestV1.java` | JSON model for v1 AI Pack manifest — identity, version constraints, assets |
| 2025-12-27 | src | `ai/pack/AiPackPreflightResult.java` | Immutable result from AI Pack preflight check (pack ID, version, manifest SHA) |
| 2025-12-27 | src | `ai/runtime/AiRuntimeActivationStatus.java` | Persisted v3 status for GPU Booster Pack runtime variant activation |
| 2025-12-27 | src | `policy/EnterprisePolicy.java` | JSON model for `policy.v1.json` — flags for downloads, online AI, GPU, allowlists |
| 2025-12-27 | test | `ai/pack/PackAllowlistServiceTest.java` | Machine-level policy allowlist takes precedence over user-level when non-empty |
| 2026-01-17 | IT | `api/EffectiveConfigIntegrationTest.java` | **EMPTY FILE** — no content |
| 2026-01-17 | IT | `api/EffectiveConfigRuntimeIntegrationTest.java` | **EMPTY FILE** — no content |
| 2026-01-17 | src | `api/TelemetryHealthController.java` | Javalin controller for `/api/telemetry/health` — evaluates export success rates and staleness |
| 2026-01-17 | src | `api/TelemetryHealthResponse.java` | Response record for telemetry health — state, reason, export counters, success rates |
| 2026-01-17 | src | `api/dto/UiSettingsV2.java` | Immutable v2 DTO for UI preferences (theme, density, vim mode) |
| 2026-01-20 | src | `infra/DocumentFetcher.java` | Async helper resolving full document content via DocumentService on background executor |
| 2026-01-21 | src | `policy/EffectivePolicy.java` | Computed effective policy record (merged machine + user + env overrides) |
| 2026-01-21 | test | `ai/runtime/RuntimeActivationServiceBaselineTest.java` | Tests deterministic CPU baseline exe resolution in RuntimeActivationService |
| 2026-01-21 | test | `api/TokenEstimationUtilsBudgetTest.java` | Tests safe input budget formula: `(ctx - output - 256 - 256) * 0.9` with 256-token floor |
| 2026-01-21 | test | `api/TokenEstimationUtilsTest.java` | Tests token counting and prefix truncation methods |

### modules/app-services — 14 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-12-09 | src | `vdu/ImagePreparer.java` | Stateless utility — resizes images to max 1280px for Qwen3-VL, converts to JPEG bytes |
| 2025-12-09 | test | `vdu/GrpcVduOperationsTest.java` | Integration tests for VDU gRPC operations using in-process server |
| 2025-12-09 | stub | `vdu/StubRemoteKnowledgeClient.java` | Simulates gRPC responses and tracks invocations for VDU tests |
| 2025-12-09 | stub | `vdu/StubVduProcessor.java` | Configurable extracted-text/enrichment results or exceptions for VDU tests |
| 2025-12-09 | stub | `vdu/StubVramDetector.java` | Controllable VRAM reporting for VDU tests |
| 2025-12-09 | test | `vdu/VduPoisonPillTest.java` | Verifies repeatedly-failing VDU docs are marked FAILED after max retries |
| 2026-01-04 | test | `vdu/VduBatchProcessorTest.java` | Tests VRAM gating, retry logic, failure marking in batch processing |
| 2026-01-14 | src | `vdu/OfflineCoordinator.java` | Sequences VDU (LLM mode) before embedding (SLM mode) with single-run concurrency guard |
| 2026-01-14 | test | `vdu/OfflineCoordinatorTest.java` | Tests phase sequencing, concurrency guard, and recovery |
| 2026-01-14 | test | `worker/AppServicesWorkerGuardrailsTest.java` | ArchUnit rule: no direct env var / sysprop reads in app-services |
| 2026-01-18 | src | `worker/IpcTelemetry.java` | Telemetry helper recording `ipc.*` metrics for Worker lifecycle events |
| 2026-01-18 | test | `worker/GrpcCircuitBreakerTest.java` | Tests CLOSED/OPEN state machine transitions, failure counting, reset |
| 2026-01-20 | test | `vdu/VduConcurrentTriggerTest.java` | Verifies concurrent offline triggers deduplicated via AtomicBoolean guard |
| 2026-02-05 | test | `worker/IpcTelemetryTest.java` | Verifies IpcTelemetry.noop() executes all methods without throwing |

### modules/adapters-lucene — 9 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-01 | src | `runtime/TelemetrySoftDeletesMergePolicy.java` | Lucene merge policy emitting kept/purged soft-delete counts via telemetry |
| 2025-11-22 | test | `runtime/TelemetrySoftDeletesMergePolicyMetricsTest.java` | Verifies correct counts during a real in-memory Lucene merge |
| 2025-12-15 | src | `runtime/IndexRuntimeIOException.java` | Typed runtime exception with categorized Reason enum (DISK_IO, LOCKED, CORRUPT, etc.) |
| 2026-01-15 | src | `runtime/SafeIndexPathOps.java` | Guarded, backup-first destructive ops on Lucene index directories |
| 2026-01-15 | test | `runtime/AdaptersLuceneGuardrailsTest.java` | ArchUnit: no direct System.getenv/getProperty in adapters-lucene |
| 2026-01-21 | test | `runtime/LuceneRuntimeUtilsTest.java` | Tests sort construction and sort field mapping helpers |
| 2026-01-21 | test | `runtime/SearchAfterCursorHelperTest.java` | Tests cursor encoding/decoding for all RuntimeSearchSort types |
| 2026-01-21 | test | `runtime/SearchResultFormatterTest.java` | Tests field extraction and null-input validation |
| 2026-01-21 | test | `runtime/VectorFormatDetectorTest.java` | Tests KNN vector field identification on real on-disk Lucene index |

### modules/telemetry — 8 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-01 | IT | `GrpcTracePropagationIT.java` | Verifies trace ID injected by client interceptor is received by server over in-process gRPC |
| 2025-11-01 | test | `TracePropagationTest.java` | Tests W3C TraceContext inject/extract round-trip via OpenTelemetry SDK |
| 2025-11-01 | stub | `grpc/TraceClientInterceptor.java` | **DEAD** — test-only gRPC client interceptor, orphaned helper for GrpcTracePropagationIT |
| 2025-11-01 | stub | `grpc/TraceServerInterceptor.java` | **DEAD** — test-only gRPC server interceptor, orphaned helper for GrpcTracePropagationIT |
| 2025-11-04 | test | `TelemetryDefaultMethodsTest.java` | Tests Telemetry interface default methods via minimal no-op implementation |
| 2025-12-31 | IT | `CollectorSmokeIT.java` | Exports metric + span to real OTLP collector (skipped when unreachable) |
| 2026-01-17 | src | `TelemetryHealthSnapshot.java` | Immutable record capturing point-in-time telemetry export/rotation counters |
| 2026-01-20 | test | `TelemetryHealthStateTest.java` | Verifies snapshots are immutable, thread-safe, and accumulate counters correctly |

### modules/system-tests — 13 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-28 | src | `chaos/HandleLeakDetector.java` | Queries OS-level open file handles to verify Head never opens Lucene/SQLite files |
| 2025-11-28 | test | `AiJudgeTest.java` | Tests AI judge helpers (JsonFormatValidator, KeywordPresenceChecker, SemanticSimilarityChecker) |
| 2025-11-28 | test | `GoldenCorpusTest.java` | Tests relevance metrics against a golden frozen corpus with deterministic embedding backend |
| 2025-11-28 | test | `RrfFusionHarnessTest.java` | Tests RRF score formula, cross-list boosting, BM25/vector rank mixing |
| 2025-12-04 | test | `torture/FileIntruder.java` | Spawns background thread to aggressively lock index/db/log files, simulating AV interference |
| 2025-12-06 | test | `process/ContentExtractionTest.java` | Exercises ContentExtractor against real document files on disk |
| 2026-01-18 | src | `relevance/RelevanceMetrics.java` | Computes Recall@K, Precision@K, NDCG@K, MRR over search result lists |
| 2026-01-19 | src | `soak/NmtMemoryTracker.java` | Parses JVM NMT summary from Worker to detect off-heap memory growth in soak tests |
| 2026-01-20 | test | `NastyCorpusTest.java` | Tests ContentExtractor handles malformed/corrupt files gracefully |
| 2026-01-20 | test | `chaos/MmfSignalBusCompatibilityTest.java` | Tests Head and Worker can read/write same MMF signal bus layout |
| 2026-02-01 | src | `chaos/MmfTestHarness.java` | Manipulates MMF signal bus via direct memory-mapped writes for deterministic testing |
| 2026-02-01 | src | `process/ManagedProcess.java` | Base class for external child processes in tests — lifecycle, log piping, port-wait |
| 2026-02-05 | test | `torture/ReadWhileWriteTest.java` | Concurrent gRPC reads against actively indexing Worker — verifies no corruption |

### modules/indexer-worker — 6 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-12-04 | test | `coordination/WorkerSignalBusTest.java` | Tests MMF open/read/write lifecycle — heartbeat, throttle, suicide-pact fields |
| 2026-01-15 | test | `queue/SwitchBufferDurabilityTest.java` | Tests putSwitchBuffer returns true on success, false on SQL failure |
| 2026-02-01 | src | `queue/SqliteSchema.java` | Single source of truth for SQLite DDL (jobs.db) — tables, indexes, migrations V1–V4 |
| 2026-02-01 | test | `queue/JobQueueMigrationTest.java` | Tests migration from V1 through current version preserving rows |
| 2026-02-01 | test | `queue/JobQueueTest.java` | Tests enqueue, poll, acknowledge, fail-retry against real SQLite |
| 2026-02-02 | test | `server/KnowledgeServerUtilsTest.java` | Tests KnowledgeServer utility methods (gauge helpers, path formatting) via reflection |

### modules/configuration — 5 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-15 | src | `ConfigPrecedence.java` | Resolves config: system property > env var > fallback, for ad-hoc keys outside EnvRegistry |
| 2026-01-15 | src | `RepoRootLocator.java` | Locates repo root via sys-prop/env-var, SSOT path derivation, or CWD auto-traversal |
| 2026-01-15 | src | `SystemAccess.java` | Centralizes all System.getProperty/getenv access to avoid direct JVM global-state coupling |
| 2026-01-15 | test | `RepoRootLocatorTest.java` | Tests resolution priority: explicit prop > SSOT-derived > auto-discovery |
| 2026-01-19 | test | `PlatformPathsTest.java` | Tests resolveDataDir returns non-null path containing "justsearch" component |

### modules/ipc-common — 4 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-15 | src | `v1/SummaryRejectionMetadata.java` | Encodes/decodes SummaryRejection into gRPC trailer metadata for cross-process error propagation |
| 2026-01-15 | test | `v1/SummaryRejectionMetadataTest.java` | Tests round-trip of reason code, message key, attributes through gRPC Metadata |
| 2026-01-18 | test | `IndexingProtoDeprecationsTest.java` | Asserts deprecated proto fields/RPCs are annotated deprecated in compiled descriptors |
| 2026-02-03 | src | `mmf/MmfWorkerSignalHeaderV1.java` | Constants for MMF v1 signal bus header (magic bytes, format version, compat flags) |

### modules/app-launcher — 4 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-01 | test | `BoundaryRulesTest.java` | ArchUnit: launcher must not depend on ui or app-services packages |
| 2025-11-28 | test | `LauncherInternalsTest.java` | Tests launcher internals via reflection — CLI formatting, data-dir config |
| 2026-01-15 | test | `LauncherTest.java` | Tests CLI argument parsing for smoke options and default command resolution |
| 2026-02-02 | test | `LauncherEnvironmentResolveTest.java` | Tests resolveProfilePath locates profile YAML relative to repo root |

### modules/infra-core — 2 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-04 | src | `health/InfraHealthAggregator.java` | Aggregates NRT, translator, ANN health signals into timestamped status snapshot |
| 2025-11-04 | test | `health/InfraHealthAggregatorTest.java` | Tests HEALTHY/degraded status under various staleness/readiness combinations |

### modules/test-support — 3 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2025-11-07 | src | `docs/SampleDoc.java` | Immutable record for deterministic sample documents used by MiniIndexFixture |
| 2025-11-07 | test | `docs/SampleDocTest.java` | Tests null normalization, null facet stripping, blank ID rejection |
| 2025-11-07 | test | `docs/SampleDocsTest.java` | Tests named doc set loading from classpath JSON, caching by identity |

### modules/app-config — 1 file

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-14 | src | `ConfigSnapshotListener.java` | Functional interface callback invoked on new ConfigSnapshot production |

### modules/app-indexing — 8 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-14 | src | `ocr/OcrGuards.java` | Enforces OCR pre-flight checks (pixel threshold, timeout, page count, text layer) |
| 2026-01-14 | src | `watch/FileWatcherStrategy.java` | SPI interface for filesystem change detection — start, close, Kind enum |
| 2026-01-14 | test | `UserIndexConfigTest.java` | Tests save/load round-trip of watched root paths to disk |
| 2026-01-14 | test | `ocr/OcrGuardsTest.java` | Tests decision logic for each skip condition, verifies counter increments |
| 2026-01-14 | test | `watch/MethvinWatcherStrategyTest.java` | Tests filesystem event delivery on real temp directories |
| 2026-01-14 | stub | `support/TestTelemetry.java` | In-memory AtomicLong counters for asserting metric emissions in tests |
| 2026-01-20 | src | `ocr/OcrProcessor.java` | Orchestrates OCR with bounded concurrency, timeouts, guardrails, telemetry |
| 2026-01-20 | test | `ocr/OcrProcessorTest.java` | Tests concurrency limits, timeout enforcement, telemetry via fake executor |

### modules/app-observability — 4 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-14 | src | `InfraDiagnosticsService.java` | Aggregates NRT lag, translator, ANN, config health from injectable suppliers |
| 2026-01-14 | test | `InfraDiagnosticsServiceTest.java` | Tests composite health evaluation |
| 2026-01-20 | src | `InfraHealthGrpcService.java` | gRPC service exposing InfraDiagnosticsService as InfraHealthSnapshot proto |
| 2026-01-20 | test | `InfraHealthGrpcServiceTest.java` | Tests proto conversion and onNext/onCompleted call sequence |

### modules/app-util — 2 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-14 | src | `RepoPaths.java` | Thin facade over RepoRootLocator for repo-root resolution |
| 2026-01-14 | src | `TempFileManager.java` | Centralized temp file/directory manager with AutoCloseable cleanup and shutdown hook |

### modules/reranker — 2 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-14 | src | `RerankerTokenizer.java` | Wraps HuggingFace tokenizer to encode query-document pairs for ONNX cross-encoder |
| 2026-01-14 | IT | `CrossEncoderRerankerIntegrationTest.java` | Integration test requiring real model at RERANK_MODEL_PATH (skipped if absent) |

### modules/benchmarks — 5 files

| Last Modified | Type | File | Description |
|---------------|------|------|-------------|
| 2026-01-22 | src | `util/BenchmarkUtils.java` | Percentile calculation, GC stats, RSS via OSHI, result file writing |
| 2026-01-22 | src | `util/MachineFingerprint.java` | Captures hostname, OS, Java version, CPU count, RAM for benchmark reproducibility |
| 2026-01-22 | test | `util/BenchmarkCliTest.java` | Tests argument parsing helpers (parseString, parseInt, parseFlag) |
| 2026-01-22 | test | `util/BenchmarkUtilsTest.java` | Tests Commons Math percentile matches legacy algorithm, plus formatting helpers |
| 2026-01-22 | test | `util/MachineFingerprintTest.java` | Tests capture returns non-null hostname and valid CPU/RAM values |

---

## Known Issues

### Dead files (2)

| File | Reason |
|------|--------|
| `telemetry/test/grpc/TraceClientInterceptor.java` | Orphaned test helper — only used within same test source set |
| `telemetry/test/grpc/TraceServerInterceptor.java` | Orphaned test helper — only used within same test source set |

### Empty files (2)

| File | Notes |
|------|-------|
| `ui/src/integrationTest/.../EffectiveConfigIntegrationTest.java` | Placeholder — no content |
| `ui/src/integrationTest/.../EffectiveConfigRuntimeIntegrationTest.java` | Placeholder — no content |

### Stale references from restructuring (cosmetic, not blocking)

| File | Issue |
|------|-------|
| `modules/ai-backend/README.md` | Still says "ai-bridge module" |
| `CONTRIBUTING.md` | Module table lists `modules/ai-bridge` |
| ~15 docs/READMEs | Cosmetic `ai-bridge` / `ai-worker` / `app-ai` references |
| ~~`dag-runner-grpc-soak.mjs`~~ | ~~Refs deleted `:modules:app-ai` — but script already broken (missing `scripts/lib/`)~~ — **closed Phase 3 (2026-05-12)**: script + entire `scripts/resilience/` tree deleted |
| `verify-prerequisites.mjs` | Refs old VramDetector path — tracked in tempdoc 288 |
