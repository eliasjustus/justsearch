---
title: Agent Infrastructure External-Fit Research
status: done
created: 2026-02-17
updated: 2026-02-19
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.

# 210: 12-Month Whole-Product Strategy (Velocity-First, Local-First Default)

## Split-Out Workstreams (2026-02-19)

Dedicated domain packets:
1. Unified eval/benchmark investigation and planning:
`docs/tempdocs/216-eval-harness-consolidation.md`
2. Runtime resilience hardening investigation and planning:
`docs/tempdocs/219-runtime-resilience-hardening.md`

This document (`210`) remains the whole-product strategy view.

## Strategic Objective

Reframe this document from a 90-day agent-infra research packet into a 12-month whole-product strategy that is:
1. Decision-complete for implementation sequencing.
2. Portfolio-based across product domains.
3. Ordered by developer velocity, with safety guardrails.
4. Local-first by default, with opt-in cloud overlays only behind policy gates.

This document is planning-only. No runtime feature implementation is in scope.

Strategic policy for this cycle:
1. Speed of validated delivery is the primary optimization target.
2. No velocity optimization may bypass safety, replay integrity, or contract compatibility.
3. Local-first remains baseline behavior across all tracks.

## Portfolio Scope

### Scope and Boundaries

| Area | In Scope | Out of Scope (this cycle) |
|---|---|---|
| Agent infrastructure | Yes | Full parallel multi-runtime orchestration |
| Retrieval and RAG quality | Yes | Net-new retrieval architecture rewrite |
| Benchmark and eval platform | Yes | Replacing all existing bench lanes in one cut |
| Observability and telemetry | Yes | Full observability platform migration in one quarter |
| Runtime resilience | Yes | One-off per-client resilience patches without shared policy |
| Config and UX reliability | Yes | Major UI redesign programs |
| Installer and distribution reliability | Yes | Packaging model changes unrelated to current blockers |
| Policy and enterprise controls | Yes | Broad policy revamp without roadmap dependency |
| Optional cloud overlays | Yes (policy-gated only) | Cloud-default behavior |

### Strategy Backlog (Canonical for This Cycle)

Backlog fields are intentionally sortable by velocity impact and risk.

| ID | Domain | Backlog Item | Evidence Source | Severity / Impact | Time Criticality | Dependency Blockers | Velocity Impact (1-5) | Quarter Target | Owner Role |
|---|---|---|---|---|---|---|---:|---|---|
| S-001 | Benchmark/eval platform | Close repeatable model eval harness gap (`BEN-004`) | `docs/reference/issues/benchmarking.md` | P3 / High | Immediate | None | 5 | Q1 | Benchmark owner |
| S-002 | RAG/retrieval quality | Unblock reranker upgrade path (`RAG-007`) | `docs/reference/issues/retrieval-quality.md` | P3 / High | Immediate | S-001 | 4 | Q1 | Search quality owner |
| S-003 | Runtime resilience | Unify retries for non-RKC gRPC clients (`BKD-009`) | `docs/reference/issues/backend-tech-debt.md` | P2 / High | Immediate | None | 5 | Q1 | Runtime owner |
| S-004 | Observability/telemetry | Trace identity parity (`runId/stepId/spanId/...`) | `docs/tempdocs/208-ai-model-tuning-optimization.md` | High | High | S-001 artifact conventions | 4 | Q2 | Agent platform owner |
| S-005 | Agent infra | Scorecard v2 process metrics (+ calibration policy hardening) | `docs/tempdocs/208-ai-model-tuning-optimization.md` | Medium-High | High | S-001, S-004 | 4 | Q2 | Agent platform owner |
| S-015 | Eval/observability operations | Overnight evidence ingestion parity (manifest naming + scorecard loader compatibility) | `scripts/bench/overnight-rag-ai-queue-win.ps1`, `scripts/ci/build-agent-live-scorecard.mjs` | High | Immediate | S-005 | 5 | Q1 | Agent platform owner |
| S-016 | Benchmark/eval platform | RAG comparability profile split (stub vs embedding baselines) | `scripts/bench/diff-rag-eval-suite.mjs`, `scripts/bench/baselines/rag-eval-baseline-profiles.v1.json` | High | Immediate | S-001 | 5 | Q1 | Benchmark owner |
| S-006 | Agent infra | Checkpoint schema versioning and replay compatibility hardening | `docs/tempdocs/208-ai-model-tuning-optimization.md` | High | High | S-004 | 4 | Q2 | Agent platform owner |
| S-007 | Runtime resilience | Health readiness contract upgrade (`BKD-010`) | `docs/reference/issues/backend-tech-debt.md` | P2 / High | High | S-003 | 4 | Q2 | Runtime owner |
| S-008 | Agent infra | M0 sequential handoff runtime (single-model-first) | `docs/tempdocs/208-ai-model-tuning-optimization.md` | High | Medium | S-006, S-007 | 3 | Q3 | Agent platform owner |
| S-009 | Agent infra | Context-efficiency quality-safe maturity (post A/B) | `docs/tempdocs/208-ai-model-tuning-optimization.md` | Medium-High | Medium | S-001, S-005 | 3 | Q3 | Agent platform owner |
| S-010 | Config/UX reliability | Address highest-friction open UX/config reliability issues | `docs/reference/issues/ui-ux.md`, `docs/reference/issues/documentation.md` | P3/P4 / Medium | Medium | S-001 telemetry visibility | 3 | Q3 | UX owner |
| S-011 | Installer/distribution reliability | Resolve top open installer reliability gap(s) | `docs/reference/issues/installer.md` | P2 / Medium-High | Medium | None | 3 | Q3 | Installer owner |
| S-012 | Policy/enterprise controls | Add policy gates for optional cloud overlays | `docs/future-features/enterprise-policy-v3.md` | Medium | Medium | S-007 | 3 | Q4 | Policy owner |
| S-013 | Optional cloud overlays | Pilot policy-gated cloud-connected enhancement(s) | `docs/future-features/cloud-connectors-research.md` | Medium | Low-Medium | S-012 | 2 | Q4 | Platform owner |
| S-014 | Observability/telemetry | Promote stable strategy outputs to canonical docs | `docs/reference/contributing/development-philosophy.md` | Medium | End-cycle | Q1-Q3 gate completion | 2 | Q4 | Documentation owner |

## Baseline State (Evidence-Backed)

### Current High-Impact Blockers

| Blocker | State | Why It Matters | Evidence Pointer |
|---|---|---|---|
| `BEN-004` | Open | Prevents confident model/eval decisions | `docs/reference/issues/benchmarking.md` |
| `RAG-007` | Blocked on `BEN-004` | Blocks reranker quality evolution | `docs/reference/issues/retrieval-quality.md` |
| `BKD-009` | Open | Inconsistent retry behavior across gRPC clients | `docs/reference/issues/backend-tech-debt.md` |
| `BKD-010` | In progress (contract patch landed) | Health/readiness contract patch now surfaces additive AI/embedding readiness fields; remaining work is issue closure + broader rollout validation | `docs/reference/issues/backend-tech-debt.md` |

### Code-Grounded Validation Snapshot (2026-02-18)

| Strategy Item | Validation Status | Current State in Code | Code Evidence |
|---|---|---|---|
| S-003 (`BKD-009`) | Implemented (targeted tests green) | Shared retry service-config + shared circuit breaker added in `ipc-common`, wired into non-RKC ANN/embedding/translator gRPC clients | `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcRetryServiceConfig.java`, `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker.java`, `modules/app-search/src/main/java/io/justsearch/app/search/GrpcAnnSearchClient.java`, `modules/app-search/src/main/java/io/justsearch/app/search/GrpcEmbeddingClient.java`, `modules/app-ai/src/main/java/io/justsearch/app/ai/GrpcAiTranslatorService.java`, `modules/app-search/src/test/java/io/justsearch/app/search/GrpcAnnSearchClientTest.java`, `modules/app-search/src/test/java/io/justsearch/app/search/GrpcEmbeddingClientTest.java`, `modules/app-ai/src/test/java/io/justsearch/app/ai/GrpcAiTranslatorServiceTest.java` |
| S-007 (`BKD-010`) | Implemented (targeted tests green) | Additive readiness fields (`ai_ready`, `embedding_ready`) now flow from worker gRPC health through `/api/status` and UI health derivation without changing serving semantics | `modules/ipc-common/src/main/proto/indexing.proto:754`, `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcHealthService.java:95`, `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java:18`, `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java:637`, `modules/ui-web/src/stores/useSystemStore.ts:237`, `modules/ui-web/src/components/views/health/deriveHealthEvents.ts:63`, `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/GrpcHealthServiceTest.java:14`, `modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java:1`, `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java:138` |
| S-004 (trace identity parity) | Implemented (targeted tests green) | Per-event identity envelope is now emitted and propagated across SSE payloads, persisted event payloads, and MCP transcript artifacts (`runId/stepId/spanId/parentSpanId/agentId/toolCallId`) | `modules/app-agent-api/src/main/java/io/justsearch/agent/api/TraceContext.java:4`, `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java:757`, `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java:202`, `modules/app-agent/src/main/java/io/justsearch/agent/AgentRunStore.java:173`, `scripts/dev/justsearch-dev-mcp/server.mjs:1431`, `scripts/dev/justsearch-dev-mcp/schemas.mjs:532`, `modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java:46`, `modules/ui/src/test/java/io/justsearch/ui/api/AgentSseContractTest.java:46` |
| S-005 (scorecard v2 process metrics) | Implemented (script + live continuation validation) | Nightly scorecard emits process metrics in additive `schemaVersion: 2`: loop incidence, trajectory conformance, path convergence, terminal error class/code distributions, latency p50/p90/p99, and pass^k; includes per-run process summaries and scenario-profile comparability filtering to prevent mixed scenario-set windows. Latest recovered comparable window: `10/14` runs; gates currently failing (`infraFailureRate`, `passRateStdDev`, `runsRequired`). | `scripts/ci/build-agent-live-scorecard.mjs`, `scripts/ci/run-agent-live-battery.mjs`, `scripts/ci/agent-live-battery-scenarios.v1.json`, `tmp/agent-evidence/nightly-agent-live/agent-live-scorecard-recovered-20260218-114853.json` |
| S-015 (overnight ingestion parity + run-health semantics) | Implemented in code (short smoke validation complete) | Scorecard/A-B loaders now ingest by `kind` instead of filename coupling; overnight writes canonical-compatible manifest names; strict teardown semantics propagate `teardownFailure` and force gate failure for `stop_failed:*`; overnight now emits durable checkpoint artifacts | `scripts/ci/build-agent-live-scorecard.mjs`, `scripts/ci/build-agent-live-ab-report.mjs`, `scripts/ci/run-agent-live-battery.mjs`, `scripts/ci/evaluate-agent-live-gate.mjs`, `scripts/bench/overnight-rag-ai-queue-win.ps1`, `tmp/agent-evidence/_summaries/agent-live-battery-manifest-overnight-20260218-140303.attempt01.json`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-140303.checkpoint.json` |
| S-016 (RAG comparability profile split) | Implemented (runtime-validated) | Baseline profile index + explicit stub/embedding baseline files are live; diff and promotion flows are profile-aware (`--profile-id`, `-ProfileId`), and embedding-profile runtime validation has produced a comparable/pass decision artifact | `scripts/bench/baselines/rag-eval-baseline-profiles.v1.json`, `scripts/bench/baselines/rag-eval-baseline.stub-jaccard-cross-encoder-q24.json`, `scripts/bench/baselines/rag-eval-baseline.embedding-cross-encoder-q24.json`, `scripts/bench/diff-rag-eval-suite.mjs`, `scripts/bench/promote-rag-eval-baseline-win.ps1`, `scripts/bench/overnight-rag-ai-queue-win.ps1`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-141316.json` |
| S-006 (checkpoint schema versioning) | Implemented (unit coverage present) | Durable store now includes explicit schema version envelope with upcaster chain and golden fixture replay coverage | `modules/app-agent/src/main/java/io/justsearch/agent/AgentRunStore.java`, `modules/app-agent/src/test/java/io/justsearch/agent/AgentRunStoreTest.java`, `modules/app-agent/src/test/resources/fixtures/schema-v0/` |
| S-008 (M0 handoff runtime) | Confirmed not implemented | No handoff events or active-agent transition fields in current agent event surfaces | `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEvent.java:4`, `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java:246` |
| Observability parity v1 (208 phase-1 target) | Confirmed implemented | Budget updates, done-token totals, and typed error metadata are present in SSE + MCP transcript output | `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEvent.java:72`, `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java:218`, `scripts/dev/justsearch-dev-mcp/server.mjs:1504`, `scripts/dev/justsearch-dev-mcp/server.mjs:1755`, `scripts/dev/justsearch-dev-mcp/schemas.mjs:567` |

### Operational Validation Update (2026-02-18)

| Finding | Current State | Why It Matters | Evidence Pointer |
|---|---|---|---|
| Partial overnight evidence is now first-class even on interrupted runs | Overnight queue now emits `overnight-rag-ai-queue-<session>.checkpoint.json` with lane-level state, phase, and `running|interrupted|completed` status | Removes manual forensics dependency and makes partial evidence machine-readable | `scripts/bench/overnight-rag-ai-queue-win.ps1`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-140303.checkpoint.json` |
| Overnight manifests are now scorecard-visible without aliasing | Loader accepts any `.json` with `kind === "agent-live-battery-manifest.v1"`; overnight run emits canonical-compatible `agent-live-battery-manifest-overnight-...attemptNN.json` | Closes ingestion blind spot and preserves backward compatibility with historical naming | `scripts/ci/build-agent-live-scorecard.mjs`, `scripts/ci/build-agent-live-ab-report.mjs`, `scripts/bench/overnight-rag-ai-queue-win.ps1`, `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260218-140303.json` |
| Strict teardown semantics are now enforced for run health | `stop_failed:*` now forces `aggregate.infraFailure = true` and `aggregate.teardownFailure = true`, and gate evaluation fails affected runs | Prevents false-healthy interpretation of operationally failed runs | `scripts/ci/run-agent-live-battery.mjs`, `scripts/ci/evaluate-agent-live-gate.mjs`, `scripts/ci/test-evaluate-agent-live-gate.mjs` |
| RAG profile split is now runtime-validated in embedding lane | Single-run embedding session completed with comparable diff and pass gate under explicit profile (`embedding-cross-encoder-q24`) | Removes the previous S-016 runtime uncertainty; remaining risk is quality variance, not comparability wiring | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-141316.json`, `build/test-results/rag-eval/rag-eval-decision.phase6-real-embedding.20260218-141316.run01.v1.json`, `scripts/bench/diff-rag-eval-suite.mjs` |
| Latest short overnight smoke confirms artifact continuity but not gate graduation | Session `20260218-140303` produced final summary, checkpoint, manifest, and scorecard; gate eval failed on stability gates (`passRateStdDev`, `runsRequired`) | Confirms instrumentation path is healthy while highlighting remaining model stability variance | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-140303.json`, `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260218-140303.json` |
| Short mixed-lane run now completes with full artifact chain after Claim-D startup hardening | Session `20260218-162153` produced final summary + checkpoint + RAG decision + agent manifest + scorecard; Claim-D no longer hard-fails on startup stderr (now returns cleanly and can skip if AI unavailable) | Confirms S-015/S-016 plumbing integrity across lanes; non-zero exit now reflects real RAG regression + gate thresholds, not harness wiring faults | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-162153.json`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-162153.checkpoint.json`, `scripts/bench/run-claim-d-suite-win.ps1` |
| Multi-hour cadence remains operator-gated by request | First resumed multi-hour run was manually aborted and force-stopped; only early checkpoint exists (`status: running`, `phase: preflight_complete`) | Prevents unintended long background workloads; short-run-only path remains active until explicit approval | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-172603.checkpoint.json` |

### Sequencing Impact from Code Validation

1. Quarter ordering remains correct: S-001 remains the primary throughput unblocker, and S-003 has moved from planned to implemented baseline.
2. S-004, S-005, and S-006 are now implemented baselines; the next primary architecture blocker is S-008 (M0 handoff runtime/events), with S-005 calibration policy still pending.
3. S-007 should remain additive-only at the contract level because current health consumers map a narrow field set.
4. S-015 and S-016 must be treated as immediate Q1 accelerators because they convert existing overnight evidence into gate-usable signals and remove comparability deadlocks.

### Existing Strategic Strengths

| Strength | Why It Is Useful for This Strategy | Evidence Pointer |
|---|---|---|
| Bench/eval schema governance exists | Enables controlled extension rather than ad hoc fields | `docs/reference/contracts/benchmark-eval-contract.md` |
| Compatibility matrix for benchmark lanes exists | Supports phased maturity and migration planning | `docs/reference/benchmark-eval-compatibility-matrix.md` |
| Agent reliability scaffold implemented | Allows strategy to focus on hardening and expansion, not bootstrap | `docs/tempdocs/208-ai-model-tuning-optimization.md` |
| Risk register discipline exists | Supports explicit trade-off handling in roadmap execution | `docs/reference/architectural-risks.md` |

### Preserved Agent-Infra Findings (Retained, Not Discarded)

Previous 210 outputs remain valid as one portfolio section:
1. Adopt-first cluster: OpenTelemetry + Collector + Jaeger, Promptfoo, Resilience4j.
2. Pilot-first cluster: Ragas, Langfuse self-host, LLMLingua-2.
3. Defer cluster: full Temporal workflow migration before native hardening, sidecar-heavy orchestration before M0 closure.

Preserved evidence base:
1. `docs/tempdocs/210-agent-infra-external-fit-research.md` (earlier sections).
2. `docs/tempdocs/208-ai-model-tuning-optimization.md` (remaining gap checklist).

## Capability Maturity Model

### Maturity Level Definitions

| Level | Definition | Objective Signal |
|---|---|---|
| L1 | Ad hoc/manual | No reproducible workflow; high operator dependence |
| L2 | Repeatable/report-only | Repeatable artifacts exist; informational use only |
| L3 | Governed soft-gate | Policy checks exist; warn/defer decisions are enforceable |
| L4 | Hard-gated with rollback | Blocking gates and rollback playbooks are operating |
| L5 | Adaptive and self-calibrating | Auto-calibrated thresholds with drift control and explicit exception governance |

### Domain Maturity Matrix (Current -> 12-Month Target)

| Domain | Current | Target (12 months) | Graduation Criteria (Objective) | Roadmap Milestones |
|---|---|---|---|---|
| Eval reproducibility | L2 | L4 | Comparable run profile coverage >= 95%, non-comparable incidents tracked and bounded, blocking rules for promoted lanes | S-001, S-002 |
| Observability depth | L3 | L4 | Trace identity parity across SSE/MCP/persisted events, process-level scorecard metrics operational, and calibration policy promoted to enforced governance | S-004, S-005 |
| Durability/replay compatibility | L3 | L4 | Versioned checkpoints, replay forward/backward compatibility matrix green for supported versions | S-006 |
| Runtime resilience | L2 | L4 | Shared retry/circuit policy applied across targeted clients, no unbounded retry classes, readiness semantics upgraded | S-003, S-007 |
| Agent orchestration maturity | L2 | L3 | M0 sequential handoff events and approval invariants validated under restart/resume tests | S-008 |
| Product UX reliability | L2 | L3 | Top reliability-friction items reduced with measurable incident decline and regression checks | S-010 |
| Policy/compliance controls | L2 | L3 | Explicit policy gates for optional cloud overlays and enforceable default-off behavior | S-012 |
| Optional cloud overlay readiness | L1 | L2 | At least one policy-gated pilot with local-first fallback and explicit kill switch | S-013 |

## 12-Month Roadmap

### Sequencing Rules (Velocity-First with Guardrails)

1. Prioritize work that reduces cycle time for all downstream tracks.
2. Never bypass contract safety, replay safety, or approval safety gates.
3. Prefer additive contract changes over breaking revisions.
4. Keep cloud-connected capabilities opt-in and policy gated.

### Quarter-by-Quarter Execution Map

| Quarter | Workstream | Key Deliverable | Owner Role | Dependency Chain | Exit Gate | Rollback Trigger |
|---|---|---|---|---|---|---|
| Q1 (0-3m) | Eval foundation | Repeatable model eval harness baseline (`BEN-004` path) | Benchmark owner | S-001 -> S-002 | Stable comparable runs for canonical profile; documented non-comparable handling | Revert to report-only lane if comparability cannot be guaranteed |
| Q1 (0-3m) | Runtime resilience | Shared non-RKC retry/circuit baseline (`BKD-009` path) | Runtime owner | S-003 | Retry matrix coverage complete; no unbounded classes | Disable new retry layer if retry storm patterns appear |
| Q1 (0-3m) | Evidence throughput | Unified evidence lane conventions for strategy tracks | Platform owner | S-001, S-003 | Evidence artifacts generated by all in-scope tracks | Fall back to existing lane outputs if schema convergence fails |
| Q1 (0-3m) | Overnight evidence parity | Align overnight agent output naming with scorecard ingestion and add partial-run recovery summary emission | Agent platform owner | S-015 depends on S-005 | Overnight attempts automatically appear in scorecard windows; partial runs emit recoverable summary envelope | Revert to read-only ingestion fallback if compatibility with existing scorecard consumers breaks |
| Q1 (0-3m) | RAG comparability hardening | Split and govern RAG comparability profiles (stub vs embedding), then rerun baseline promotion path | Benchmark owner | S-016 depends on S-001 | Non-comparable incidence for active profile reduced to agreed bound; decision artifacts become gate-usable | Keep lane report-only and block regression claims if profile alignment is not achieved |
| Q2 (3-6m) | Contract hardening | Trace identity parity across event surfaces | Agent platform owner | S-004 depends on Q1 evidence conventions | Identity fields present and validated in contracts | Defer non-critical identity fields if cardinality risk exceeds cap |
| Q2 (3-6m) | Process visibility | Scorecard v2 process metrics live (implemented baseline) + calibration policy hardening | Agent platform owner | S-005 depends on S-004 | Process metrics included in monthly scorecard, trendable, and tied to stable interpretation policy | Revert to v1 scorecard if process metrics are noisy or unstable |
| Q2 (3-6m) | Durability safety | Checkpoint schema versioning + replay compatibility | Agent platform owner | S-006 depends on S-004 | Replay matrix passes supported version combinations | Freeze schema changes and retain previous version if replay drift appears |
| Q2 (3-6m) | Health clarity | AI readiness contract upgrade (`BKD-010`) | Runtime owner | S-007 depends on S-003 | Health/status exposes subservice readiness consistently | Feature-flag new fields if downstream consumers mis-handle responses |
| Q3 (6-9m) | Orchestration expansion | M0 sequential handoff architecture | Agent platform owner | S-008 depends on S-006 + S-007 | Handoff event ordering and approval-boundary tests green | Disable handoff path if safety invariants fail |
| Q3 (6-9m) | Quality efficiency | Context-efficiency maturity after quality-safe validation | Agent platform owner | S-009 depends on S-001 + S-005 | Token savings and quality criteria both pass agreed gates | Revert aggressive compression settings on quality regression |
| Q3 (6-9m) | UX/config reliability | Reliability cleanup for top open UX/config issues | UX owner | S-010 depends on Q1-Q2 observability | Measurable regression reduction in tracked issue classes | Pause low-value UX work if it degrades core throughput |
| Q3 (6-9m) | Installer reliability | Top installer reliability gap closure | Installer owner | S-011 | Installer reliability gate passes defined scenarios | Revert changes if installer error rate increases |
| Q4 (9-12m) | Policy control | Cloud-overlay policy gates with default-off behavior | Policy owner | S-012 depends on S-007 | Policy enforcement verified in effective policy and runtime behavior | Disable overlay paths if policy enforcement is incomplete |
| Q4 (9-12m) | Optional overlays | One policy-gated cloud-connected pilot | Platform owner | S-013 depends on S-012 | Pilot demonstrates value with local-first fallback intact | Disable pilot on privacy, policy, or reliability breach |
| Q4 (9-12m) | Documentation promotion | Promote stable strategy outputs to canonical docs | Documentation owner | S-014 depends on gate completion | Promotion criteria met and accepted | Keep in tempdoc if criteria are unmet |

## External-Fit Program v2 (Whole Product)

### Hard Gates (Unchanged)

1. Self-host or local viability.
2. Windows operability.
3. Licensing suitability.
4. Local-first compatibility, or explicit policy-gated opt-in cloud mode.

### Deterministic Scoring

Primary score:
`FitScore = round((Q*40 + R*20 + C*15 + L*15 + S*10) / 5)`

Secondary tie-break index:
1. Higher velocity impact score wins.
2. If tied, higher local-first fit wins.
3. If tied, lower operational uncertainty wins.

### Expanded External-Fit Matrix (Whole Product Domains)

| Domain | Recommended Candidate (Class/Score) | Rejected or Deferred Candidate (Class/Score) | Why Recommended | Reject/Defer Rationale | First Spike Artifact | Stop Condition |
|---|---|---|---|---|---|---|
| Agent infra | Native M0 + contract hardening (Pilot/79) | Sidecar-first multi-agent orchestration (Watchlist/67) | Minimizes split-state risk and aligns with current durability model | Too early before schema/versioning closure | `handoff-contract-spike.v1.md` | Any approval-boundary safety failure |
| RAG/retrieval quality | Harness-first quality loop via BEN-004 closure (Adopt/85) | Direct reranker/model swap without harness (Defer/<50) | Unblocks evidence-based quality decisions | Non-repeatable outcomes | `rag-quality-harness-profile.v1.json` | Non-comparable incidence exceeds threshold |
| Benchmark/eval platform | Promptfoo + existing contract/matrix alignment (Adopt/85) | Lane-specific ad hoc scripts as gate owners (Defer/52) | Reproducible, policy-governed evaluations | High drift and maintenance duplication | `promptfoo-canonical-battery-spike.md` | Schema drift with contract matrix |
| Observability/telemetry | OTel + Collector + Jaeger baseline (Adopt/89) | Full-stack backend replacement in one cut (Defer/58) | High value with manageable integration cost | Operational complexity too high for current phase | `trace-identity-envelope.v1.md` | Cardinality budget breach |
| Runtime resilience | Resilience4j + shared retry/circuit baseline (Adopt/90) | Per-client bespoke retry logic continuation (Defer/71) | Consistent policy across clients | Extends inconsistency debt | `grpc-resilience-shared-policy.v1.md` | Retry storm or latency amplification |
| Config/UX reliability | Targeted reliability backlog with telemetry support (Pilot/76) | Broad redesign-first approach (Defer/54) | Faster cycle-time and regression control | High effort, weak blocker coverage | `ux-reliability-bundle-q3.md` | Regression trend worsens over two periods |
| Installer/distribution reliability | Focused scenario-based hardening (Pilot/75) | Packaging architecture rewrite (Defer/49) | Solves current open reliability pain with lower cost | Rewrite risk exceeds current need | `installer-reliability-matrix-q3.json` | Installer failure rate increase |
| Policy/compliance controls | Additive policy gates for overlays (Pilot/74) | Open-by-default cloud feature rollout (Defer/38) | Preserves local-first default and enterprise posture | Violates default-off policy requirement | `overlay-policy-gates.v1.yaml` | Any path bypasses policy gating |
| Optional cloud overlays | One default-off pilot with local fallback (Watchlist/66) | Cloud-parity target in this cycle (Defer/45) | Allows evidence gathering without architecture inversion | Conflicts with local-first default and current risk profile | `cloud-overlay-pilot-charter.v1.md` | Privacy, policy, or reliability breach |

## API/Contract Evolution Plan

Planned targets are additive/versioned strategy items, not immediate implementation.

### 1) Agent SSE/MCP/Persisted Event Identity Envelope

Planned fields:
1. `runId`
2. `stepId`
3. `spanId`
4. `parentSpanId`
5. `agentId`
6. `toolCallId`

Compatibility rule:
1. Additive only.

Primary evidence:
1. `docs/tempdocs/208-ai-model-tuning-optimization.md`

### 2) Eval/Benchmark Artifact Governance Fields

Planned fields:
1. `workload_profile_id`
2. `comparability_profile`
3. `policy_version_ref`

Compatibility rule:
1. Govern through existing benchmark contract and matrix process.

Primary evidence:
1. `docs/reference/contracts/benchmark-eval-contract.md`
2. `docs/reference/benchmark-eval-compatibility-matrix.md`

### 3) Health/Readiness Surface Refinement

Planned additions:
1. AI readiness/subservice readiness fields in health/status contract (`BKD-010` path).

Compatibility rule:
1. Additive response fields; existing consumers remain valid.

Primary evidence:
1. `docs/reference/issues/backend-tech-debt.md`

### 4) Resilience Interface Unification

Planned additions:
1. Shared retry/circuit policy module for non-RKC gRPC clients (`BKD-009` path).

Compatibility rule:
1. Internal interface evolution; no public API break expected.

Primary evidence:
1. `docs/reference/issues/backend-tech-debt.md`

### 5) Policy Model for Optional Cloud Overlays

Planned additions:
1. Additive effective-policy fields for opt-in cloud overlay gates, default `off`.

Compatibility rule:
1. Additive policy fields with conservative defaults.

Primary evidence:
1. `docs/future-features/enterprise-policy-v3.md`

## Risk and Uncertainty Register

| ID | Risk / Uncertainty | Likelihood | Impact | Detection Signal | Mitigation / De-Risk Artifact | Decision Gate | Owner Role |
|---|---|---|---|---|---|---|---|
| RU-001 | Trace cardinality blowup after identity envelope | Medium | High | Export overhead and unique label count spike | `trace-cardinality-budget-report.v1.json` | Q2 trace gate | Agent platform owner |
| RU-002 | Replay compatibility drift after checkpoint versioning | Medium | High | Replay mismatch in version matrix | `checkpoint-compat-matrix.v1.json` | Q2 durability gate | Agent platform owner |
| RU-003 | Retry policy causes latency amplification | Medium | High | Tail latency inflation on resilient clients | `grpc-retry-soak-report.v1.json` | Q1 resilience gate | Runtime owner |
| RU-004 | Eval harness comparability debt persists | Medium | High | High non-comparable incident rate | `comparability-incidents-monthly.v1.json` | Q1 eval gate | Benchmark owner |
| RU-005 | Handoff path bypasses approval invariants | Low-Medium | High | Any write/destructive execution without fresh approval at boundary | `handoff-approval-boundary-tests.v1.md` | Q3 handoff gate | Agent platform owner |
| RU-006 | Optional cloud overlay breaks local-first expectations | Low-Medium | High | Overlay dependency appears in default flow | `overlay-default-off-audit.v1.md` | Q4 overlay gate | Policy owner |
| RU-007 | Overnight run interruption loses consolidated session summary | Low-Medium (mitigated) | Medium-High | Missing or malformed `overnight-rag-ai-queue-*.checkpoint.json` on interrupted paths | `scripts/bench/overnight-rag-ai-queue-win.ps1`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-140303.checkpoint.json` | Q1 evidence parity gate | Agent platform owner |
| RU-008 | Scorecard blind spot from filename-coupled manifest discovery | Low (mitigated) | High | `kind === "agent-live-battery-manifest.v1"` manifests present on disk but absent in scorecard window | `scripts/ci/build-agent-live-scorecard.mjs`, `scripts/ci/build-agent-live-ab-report.mjs`, `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260218-140303.json` | Q1 evidence parity gate | Agent platform owner |

## Execution Governance

### Monthly Strategy Scorecard Format

All fields must be computable from existing or planned artifacts.

| Metric | Definition | Data Source | Cadence | Owner |
|---|---|---|---|---|
| Cycle-time median (strategy tracks) | Median days from backlog start to gate-ready artifact | Backlog + decision log in this doc; artifact timestamps | Monthly | Program owner |
| Gate pass/fail counts | Count of passed vs failed gates by quarter stream | Quarterly gate ledger in this doc + artifact links | Monthly | Program owner |
| Non-comparable eval incidents | Count of candidate/baseline comparisons marked non-comparable | Bench/eval decision artifacts and scorecards | Monthly | Benchmark owner |
| Replay compatibility drift | Count of replay mismatches across supported versions | Replay compatibility matrix artifact | Monthly | Agent platform owner |
| Operational regressions | Count of new P2/P3 incidents linked to active strategy tracks | `docs/reference/issues/*.md` deltas | Monthly | Domain owners |

### Quarterly Strategy Review Protocol

Carry-over rules:
1. Carry over only items that missed gate due to dependency or explicit risk trigger.
2. Carry-over items require revised stop condition and owner recommitment.

De-scope rules:
1. De-scope items with low velocity impact and no high-impact blocker linkage.
2. De-scope any item whose required dependency misses two consecutive review cycles.

Policy escalation rules (cloud overlays):
1. Any privacy/policy enforcement gap escalates to immediate overlay freeze.
2. Any default-on behavior detected in overlay path triggers automatic rollback to default-off.

### Canonical Promotion Criteria (from Tempdoc 210)

Promote sections to canonical docs only when:
1. Related quarter exit gate has passed.
2. Metrics are stable for one full monthly review cycle.
3. Contract and compatibility impacts are documented with concrete artifacts.
4. No unresolved high-severity risk remains for the promoted section.

## Quarterly Exit Gates

| Quarter | Must-Pass Gates | Evidence Required | Fail Action |
|---|---|---|---|
| Q1 | Eval reproducibility baseline, non-RKC resilience baseline, unified evidence conventions | Harness report, retry policy soak report, artifact convention doc | Keep lanes report-only, freeze downstream blocker-dependent work |
| Q2 | Trace identity parity, scorecard v2 process metrics, checkpoint compatibility, health readiness clarity | Trace contract tests, scorecard artifacts, replay matrix, readiness contract artifact | Revert to prior scorecard/contract mode and freeze expansion |
| Q3 | M0 handoff safety, context-efficiency quality-safe gains, UX/config reliability reduction | Handoff boundary tests, A/B quality report, reliability issue trend report | Disable handoff/compression expansions and continue hardening |
| Q4 | Policy-gated overlay controls, one safe optional overlay pilot, canonical promotion readiness | Policy audit, overlay pilot report, promotion checklist | Disable overlays and keep strategy in tempdoc-only mode |

## Immediate Proceeding Plan (Next 14 Days)

This section is the active execution interpretation of this strategy document as of 2026-02-18.

Current execution status:

| Step | Status | Evidence |
|---|---|---|
| 1. Targeted script tests for S-015/S-016 | Complete | `scripts/ci/test-build-agent-live-scorecard.mjs`, `scripts/ci/test-evaluate-agent-live-gate.mjs`, `scripts/bench/test-diff-rag-eval-suite.mjs` (all passing on 2026-02-18) |
| 2. Embedding-lane profile-aware RAG validation run | Complete | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-141316.json` + `build/test-results/rag-eval/rag-eval-decision.phase6-real-embedding.20260218-141316.run01.v1.json` (`comparable=true`, `gate=pass`) |
| 3. Short mixed-lane overnight queue | Complete (integrity pass, quality/gate fail expected) | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-162153.json` (summary/checkpoint/scorecard/manifest all present; failures are RAG regressions + gate thresholds) |
| 4. Resume multi-hour cadence | Paused pending explicit user approval | User requested no multi-hour runs without asking first; aborted attempt `20260218-172603` was force-stopped |
| 5. 14-run trend go/no-go | Pending | Depends on step 4 |

Remaining execution steps:

1. Resume multi-hour cadence only after explicit operator approval.
   Entry criteria: operator approval granted for long run execution.
   Exit criteria: first approved long run completes with uninterrupted summary/checkpoint trail and no ingestion blind spots.
2. Use 14-run window trend as go/no-go for Q1 promotion.
   Entry criteria: long-run cadence restored.
   Exit criteria: either (a) gate thresholds pass and S-015/S-016 move to operationally validated, or (b) explicit calibration/mitigation actions are logged with owner and date.

## Remaining Items to Implement (As of 2026-02-18)

### Near-Term Execution Items

| Item | Status | What Is Left |
|---|---|---|
| Multi-hour cadence restart under current guardrail | Pending (operator-gated) | Only run after explicit approval; confirm uninterrupted final summary + checkpoint on the first approved long run |
| 14-run trend gate decision (Step 5) | Pending | Accumulate a comparable 14-run window and decide pass/fail against B-mode thresholds |
| S-005 calibration policy hardening | Pending | Convert scorecard v2 process metrics from visibility-only into stable interpretation policy and documented threshold governance |

### Remaining Strategy Backlog Implementation

| ID | Current State | Remaining Work |
|---|---|---|
| S-001 (`BEN-004`) | Open | Close repeatable model-eval harness gap and promote reproducible lane usage to default decision path |
| S-002 (`RAG-007`) | Blocked on S-001 | Unblock reranker quality progression once reproducible eval harness is stable |
| S-008 (M0 handoff runtime) | Not implemented | Add sequential handoff events/state transitions with approval-boundary safety coverage |
| S-009 (context-efficiency maturity) | Partially implemented baseline | Complete quality-safe optimization maturity and promotion criteria after stable A/B governance |
| S-010 (UX/config reliability) | Planned | Execute top-friction reliability fixes with measurable incident reduction |
| S-011 (installer reliability) | Planned | Close highest-impact installer reliability gaps with scenario-based validation |
| S-012 (policy gates for optional cloud overlays) | Planned | Add explicit default-off policy controls for cloud-connected paths |
| S-013 (optional cloud overlay pilot) | Planned | Run one policy-gated pilot with local-first fallback and kill switch |
| S-014 (canonical promotion) | Planned | Promote stable portions of this strategy into canonical docs after quarter-gate completion |

## Decision Log

| Date | Decision | Why | Evidence |
|---|---|---|---|
| 2026-02-17 | Keep tempdoc 210 as strategy source of truth for this cycle | Explicit constraint for this effort | User directive in this thread |
| 2026-02-17 | Expand scope from agent-only to whole product | Long-term planning intent | This document scope and backlog |
| 2026-02-17 | Use 12-month horizon with quarter phases | Aligns with delivery governance and maturity progression | Roadmap and gate structure in this document |
| 2026-02-17 | Prioritize developer velocity first | Explicit strategy preference | Sequencing rules and backlog ordering |
| 2026-02-17 | Keep local-first default with opt-in cloud overlays | Maintain baseline product posture while allowing controlled exploration | Policy gates and overlay rules |
| 2026-02-17 | Preserve previous 210 agent findings as one portfolio section | Avoid losing already-valid research and keep continuity with 208 | Baseline section references |
| 2026-02-17 | Revalidate priority gaps against implementation code and keep ordering unchanged | Issue registry findings are now code-anchored; only trace-identity wording required nuance | Code-grounded validation snapshot in this document |
| 2026-02-17 | Execute S-003 early with shared `ipc-common` gRPC resilience utilities | Removes known non-RKC retry/circuit inconsistency and unblocks Q1 resilience baseline confidence | `GrpcRetryServiceConfig`/`GrpcCircuitBreaker` + targeted module tests |
| 2026-02-17 | Execute S-007 early with additive health/readiness fields | Resolves BKD-010 core contract gap while keeping serving semantics and wire compatibility stable | `HealthCheckResponse.ai_ready/embedding_ready`, mapper/UI wiring, and targeted tests in `indexer-worker`, `app-services`, `ui`, and `ui-web` |
| 2026-02-17 | Execute S-004 with additive per-event trace identity envelope | Establishes observability parity across SSE, MCP, and persisted events without breaking existing consumers | `TraceContext` envelope + `AgentLoopService` enrichment + `AgentController`/`AgentRunStore` payload propagation + MCP schema/consumer updates |
| 2026-02-17 | Execute S-005 with additive scorecard schema upgrade | Lands process-level nightly visibility while preserving existing gate compatibility (`schemaVersion: 2`, kind unchanged) | `build-agent-live-scorecard.mjs` process metrics + v2 smoke artifact + unchanged `evaluate-agent-live-gate.mjs` behavior |
| 2026-02-17 | Harden S-005 with scenario-profile comparability filtering | Prevents false variance spikes from mixed scenario sets (12-case vs 16-case) in rolling windows and keeps gate metrics interpretable | `run-agent-live-battery.mjs` emits `config.scenarioProfile.signature`; `build-agent-live-scorecard.mjs` enforces signature match and reports skipped runs |
| 2026-02-18 | Classify overnight recovery work as strategic Q1 blocker work (S-015/S-016) | Incomplete long run produced usable artifacts but exposed ingestion and comparability blind spots that directly suppress decision velocity | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-025149.recovered.json`, `tmp/agent-evidence/nightly-agent-live/agent-live-scorecard-recovered-20260218-114853.json`, `scripts/ci/build-agent-live-scorecard.mjs`, `scripts/bench/diff-rag-eval-suite.mjs` |
| 2026-02-18 | Keep long-run execution gated behind short validation ladder | Prevents repeated multi-hour evidence loss while local runtime stability remains variable | `Immediate Proceeding Plan (Next 14 Days)` in this document |
| 2026-02-18 | Enforce strict teardown failure semantics for agent run health | Any `stop_failed:*` condition is now treated as infrastructure failure and must fail gate evaluation | `scripts/ci/run-agent-live-battery.mjs`, `scripts/ci/evaluate-agent-live-gate.mjs`, `scripts/ci/test-evaluate-agent-live-gate.mjs` |
| 2026-02-18 | Adopt explicit dual RAG comparability baselines (stub + embedding profiles) | Eliminates false non-comparable outcomes caused by baseline/profile mismatches and enables profile-accurate promotion/diff flow | `scripts/bench/baselines/rag-eval-baseline-profiles.v1.json`, `scripts/bench/diff-rag-eval-suite.mjs`, `scripts/bench/promote-rag-eval-baseline-win.ps1` |
| 2026-02-18 | Treat S-015 as code-complete with partial operational validation | Latest short overnight run confirms checkpoint emission + manifest ingestion parity; remaining work is stability gate progression, not plumbing | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-140303.json`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-140303.checkpoint.json`, `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260218-140303.json` |
| 2026-02-18 | Mark immediate-plan step 1 (script confidence tests) as complete | All three confidence-booster script tests are passing in current worktree; remaining uncertainty is runtime validation, not script contract behavior | `scripts/ci/test-build-agent-live-scorecard.mjs`, `scripts/ci/test-evaluate-agent-live-gate.mjs`, `scripts/bench/test-diff-rag-eval-suite.mjs` |
| 2026-02-18 | Mark immediate-plan step 2 as complete (embedding profile runtime validation) | Explicit embedding-profile run now proves comparable/pass outcome under the new dual-baseline flow | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-141316.json`, `build/test-results/rag-eval/rag-eval-decision.phase6-real-embedding.20260218-141316.run01.v1.json` |
| 2026-02-18 | Harden Claim-D managed stack startup/teardown to avoid false failures from native stderr and foreground supervisor behavior | `dev-runner start` is foreground by design; script now starts it in background with readiness polling and explicit managed stop cleanup | `scripts/bench/run-claim-d-suite-win.ps1`, `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-162153-logs/20-claim-d-suite.log` |
| 2026-02-18 | Mark immediate-plan step 3 as complete (artifact-integrity gate) | Mixed-lane run produced full artifacts; non-zero exit is attributable to real RAG regression + gate variance, not ingestion/contract/harness breakage | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-162153.json`, `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260218-162153.json` |
| 2026-02-18 | Pause multi-hour lane execution pending explicit operator confirmation | User directed no autonomous multi-hour runs; workflow now remains on short targeted validations unless asked | `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-172603.checkpoint.json` |

## Validation Scenarios

1. Strategy consistency test:
- Every roadmap item links to issue/tempdoc/canonical evidence.
- No orphan strategic claims.

2. Reproducibility test:
- Re-scoring external-fit candidates with unchanged rubric yields <=5 point drift.

3. Completeness test:
- All major open blockers in issue registry map to a quarter and owner role.

4. Contract safety test:
- Planned API changes are additive or explicitly versioned.
- No silent contract break path is allowed.

5. Governance viability test:
- Monthly scorecard fields can be produced from existing/planned artifacts without manual ad hoc interpretation.

6. Risk control test:
- Every Adopt/Pilot recommendation has a stop condition and rollback trigger.

7. Local-first policy test:
- Any cloud-dependent work item has an explicit opt-in flag and local-default fallback.
8. Partial-run recoverability test:
- Interrupted overnight sessions still produce machine-readable recovered summary artifacts and scorecard-eligible agent evidence.

## Assumptions and Defaults

1. Tempdoc 210 remains the strategy source of truth for this cycle.
2. No canonical promotion occurs until quarterly gates indicate stability.
3. Velocity-first ordering does not permit bypass of safety-critical gates.
4. Local-first remains default behavior across product surfaces.
5. Cloud-connected capabilities, if any, are opt-in and policy-controlled.
6. Existing dirty worktree state is unrelated and not modified by this strategy-writing workstream.

## Evidence Pointers

Required reconciliation sources:
1. `docs/tempdocs/208-ai-model-tuning-optimization.md`
2. `docs/reference/issues/benchmarking.md` (`BEN-004`)
3. `docs/reference/issues/retrieval-quality.md` (`RAG-007`)
4. `docs/reference/issues/backend-tech-debt.md` (`BKD-009`, `BKD-010`)
5. `docs/reference/benchmark-eval-compatibility-matrix.md`
6. `docs/reference/contracts/benchmark-eval-contract.md`
7. `docs/reference/architectural-risks.md`

Supporting context used by this strategy:
1. `docs/future-features/future-features.md`
2. `docs/reference/contributing/development-philosophy.md`

Code-grounding pointers used for this update:
1. `modules/app-search/src/main/java/io/justsearch/app/search/GrpcAnnSearchClient.java`
2. `modules/app-search/src/main/java/io/justsearch/app/search/GrpcEmbeddingClient.java`
3. `modules/app-ai/src/main/java/io/justsearch/app/ai/GrpcAiTranslatorService.java`
4. `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java`
5. `modules/ipc-common/src/main/proto/indexing.proto`
6. `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcHealthService.java`
7. `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java`
8. `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEvent.java`
9. `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java`
10. `modules/app-agent/src/main/java/io/justsearch/agent/AgentRunStore.java`
11. `scripts/dev/justsearch-dev-mcp/schemas.mjs`
12. `scripts/dev/justsearch-dev-mcp/server.mjs`
13. `scripts/ci/build-agent-live-scorecard.mjs`
14. `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcRetryServiceConfig.java`
15. `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker.java`
16. `modules/app-search/src/main/java/io/justsearch/app/search/GrpcAnnSearchClient.java`
17. `modules/app-search/src/main/java/io/justsearch/app/search/GrpcEmbeddingClient.java`
18. `modules/app-ai/src/main/java/io/justsearch/app/ai/GrpcAiTranslatorService.java`
19. `modules/app-search/src/test/java/io/justsearch/app/search/GrpcAnnSearchClientTest.java`
20. `modules/app-search/src/test/java/io/justsearch/app/search/GrpcEmbeddingClientTest.java`
21. `modules/app-ai/src/test/java/io/justsearch/app/ai/GrpcAiTranslatorServiceTest.java`
22. `scripts/ci/run-agent-live-battery.mjs`
23. `scripts/ci/agent-live-battery-scenarios.v1.json`
24. `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-025149.recovered.json`
25. `tmp/agent-evidence/nightly-agent-live/agent-live-scorecard-recovered-20260218-114853.json`
26. `scripts/bench/run-claim-d-suite-win.ps1`
27. `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-141316.json`
28. `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-162153.json`
29. `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260218-162153.json`
30. `build/test-results/rag-eval/rag-eval-decision.phase6-real-embedding.20260218-141316.run01.v1.json`
31. `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260218-172603.checkpoint.json`

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 88 days at audit time.

