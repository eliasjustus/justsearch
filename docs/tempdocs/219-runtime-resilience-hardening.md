---
title: Runtime Resilience Hardening
status: active
created: 2026-02-19
updated: 2026-02-27
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against issues + code + tests.

# 219: Runtime Resilience Hardening

## Purpose

Create a dedicated investigation/planning packet for runtime resilience so this track can proceed independently of broader product strategy work.

This tempdoc is focused on the `BKD-009` / `BKD-010` domain and the remaining resilience hardening needed for operational confidence.

## Long-Term Objective (12-24 Months)

Move from issue-by-issue resilience fixes to a stable runtime resilience capability with:
1. Explicit resilience SLOs/failure budgets (not implicit expectations).
2. Durable governance for retry/circuit/readiness semantics across all RPC surfaces.
3. Repeatable fault-injection and soak evidence as a release input.
4. Local-first default operations with optional diagnostic overlays.

## Scope

| In Scope                                                   | Out of Scope                                     |
| ---------------------------------------------------------- | ------------------------------------------------ |
| gRPC retry/backoff and circuit-breaker consistency         | Eval/benchmark unification work (moved to `216`) |
| Readiness/health semantics (`ai_ready`, `embedding_ready`) | Multi-agent/handoff architecture                 |
| Resilience evidence quality (soak artifacts, gateability)  | Broad UI redesign                                |
| Issue/doc status alignment for runtime resilience          | RAG quality policy work not tied to resilience   |

### Boundary Contract with Tempdoc 216 (Locked)

`docs/tempdocs/216-eval-harness-consolidation.md` owns:
1. Eval/benchmark harness architecture and runner orchestration.
2. Lane wiring, scorecard lane topology, and lane-specific ingestion behavior.
3. Eval artifact schema migration/unification (`bench-suite`, lane manifests, converters).
4. Benchmark CI workflow composition and benchmark-only gate mechanics.

This tempdoc (`219`) owns:
1. Runtime resilience semantics and policy (`retry`, `breaker`, `readiness`).
2. Resilience-specific evidence requirements (what evidence must exist for release decisions).
3. Release-governance interpretation for resilience (how to consume evidence, waiver semantics, decisioning).

Non-overlap rules:
1. `219` may define required resilience evidence fields, but does not redesign benchmark lane contracts.
2. Any schema/gate changes in benchmark artifacts are requested through `216`, then consumed by `219`.
3. `219` is downstream of `216` for consolidated benchmark evidence production.

### RR-014: Consumed Artifact Contract (Locked v1)

This tempdoc consumes benchmark/eval artifacts from `216` as immutable evidence inputs for resilience governance.

#### Required Producer Artifacts

| Artifact family        | Identity                                             | Minimum required fields (consumer-critical)                                                                                                                                                                                                                                                                |
| ---------------------- | ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Consolidated scorecard | `kind: benchmark-scorecard.v1`                       | `kind`, `schema_version`, `generated_at`, `source.manifests`, `lanes[]`, `release_readiness.status`, `release_readiness.reasons`, `ratchet`                                                                                                                                                                |
| Diff decision          | `schema_family: bench-decision`, `schema_version: 1` | `schema_family`, `schema_version`, `decisions.policy_version`, `decisions.comparable`, `decisions.gate_status`, `decisions.regression_count`, `decisions.non_comparable_count`, `baseline.source_path`, `candidate.source_path`                                                                            |
| Agent manifest         | `kind: agent-live-battery-manifest.v1`               | `kind`, `version`, `generatedAt`, `phase`, `blocking`, `config.scenarioProfile.signature`, `run.runId`, `aggregate.total`, `aggregate.passed`, `aggregate.failed`, `aggregate.passRate`, `aggregate.infraFailure`, `aggregate.teardownFailure`, `errors[]`                                                 |
| Agent scorecard        | `kind: agent-live-scorecard.v1`                      | `kind`, `schemaVersion`, `generatedAt`, `window.targetRuns`, `window.actualRuns`, `comparability.expectedScenarioProfile.signature`, `comparability.skippedRuns[].reason`, `metrics.infraFailureRate`, `metrics.passRateStdDev`, `metrics.scenarioInstabilityRate`, `gates.*`, `phaseA.graduationEligible` |

#### Required Scorecard Lane Fields

For each `benchmark-scorecard.v1` lane row, `219` requires:
1. `lane`
2. `present`
3. `manifest_path`
4. `generated_at`
5. `comparable`
6. `gate_status`
7. `regression_count`
8. `non_comparable_count`
9. `report_only_regression_detected`
10. `final_exit_code`
11. `warnings[]`

Agent lane rows additionally require:
1. `agent_pass_rate`
2. `agent_infra_failure`
3. `agent_total`
4. `agent_passed`
5. `agent_failed`
6. `agent_scenario_count`

#### Normalization Rules (Mixed Casing)

1. `benchmark-scorecard` and `bench-decision` remain `snake_case`.
2. Agent manifest/scorecard remain `camelCase`.
3. `219` consumers must normalize internally to canonical P6 field names without mutating producer contracts.
4. Any producer casing migration requires dual-read compatibility and explicit versioning.

#### Change-Request Protocol (216 -> 219 Interface)

1. `219` raises field-gap requests in `RR-014` with:
   - required field name/path,
   - reason (gate/calculation blocked),
   - additive compatibility expectation,
   - deadline and fallback behavior.
2. `216` owns implementation of producer-side field additions/version bumps.
3. `219` may not enforce new required fields until `216` publishes the contract update and at least one fixture/manifest proving presence.

#### Precondition Note (Contract Hygiene)

Before resilience-gate hardening depends on agent manifests:
1. `phase` values must be emitted as `A|B|C` (not `phaseA` style aliases).
2. `errors[]` in `agent-live-battery-manifest.v1` must remain `string[]`.

## Implementation Pass A (2026-02-19)

Executed order (no multi-hour runs):

| Order | ID     | Status      | Output                                                                                                                                         |
| ----- | ------ | ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | RR-014 | Done        | Locked consumed-artifact contract in this doc (`benchmark-scorecard`, `bench-decision`, `agent-live-battery-manifest`, `agent-live-scorecard`) |
| 2     | RR-001 | Done        | `BKD-009` and `BKD-010` reconciled to resolved in `docs/reference/issues/backend-tech-debt.md` with residual mapping                           |
| 3     | RR-009 | Done        | `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.json` + schema + validator + policy contract                                       |
| 4     | RR-005 | Done        | Additive `/api/status.readiness` envelope + legacy alias compatibility + UI contract/tests                                                     |
| 5     | RR-011 | Done (`G0`) | Release governance policy/schema/evaluator package in advisory mode                                                                            |
| 6     | RR-004 | Done (`G0`) | Fault-scenario catalog + soak-report schema + comparability validator (`warn-first`)                                                           |

### Artifacts Added

1. `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.json`
2. `scripts/resilience/contracts/rpc-retry-ownership-matrix.v1.schema.json`
3. `scripts/resilience/validate-rpc-retry-ownership.mjs`
4. `docs/reference/contracts/rpc-retry-ownership-policy.v1.md`
5. `docs/reference/contracts/health-readiness-contract.v1.md`
6. `scripts/governance/resilience/release-resilience-gates.v1.json`
7. `scripts/governance/resilience/release-evidence-index.v1.schema.json`
8. `scripts/governance/resilience/release-governance-waivers.v1.schema.json`
9. `scripts/ci/evaluate-release-governance.mjs`
10. `docs/reference/contracts/release-resilience-gates.v1.md`
11. `scripts/resilience/faults/grpc-fault-scenarios.v1.json`
12. `scripts/resilience/faults/grpc-fault-scenarios.v1.schema.json`
13. `scripts/resilience/faults/grpc-retry-soak-report.v1.schema.json`
14. `scripts/resilience/faults/validate-grpc-soak-report.mjs`
15. `docs/reference/contracts/grpc-soak-comparability.v1.md`

### Validation Evidence (Short-Run Only)

1. `node scripts/resilience/validate-rpc-retry-ownership.mjs` -> pass (`expectedMethods=35`, `matrixMethods=35`, `observedRetryMethods=21`, `observedIngestRetryMethods=5`).
2. `node scripts/ci/evaluate-release-governance.mjs ... --mode G0` with pass and fail fixtures -> deterministic `allow` and `hold`, non-blocking advisory behavior confirmed.
3. `node scripts/resilience/faults/validate-grpc-soak-report.mjs ... --mode G0` with baseline/candidate fixture mismatch -> `gateStatus=warn`, reason `MISMATCH_RETRY_POLICY`.
4. `./gradlew.bat --no-build-cache :modules:ui:test --tests \"*LifecycleContractTest\" --tests \"*StatusLifecycleHandlerTest\"` -> pass.
5. `./gradlew.bat --no-build-cache :modules:app-services:test --tests \"*WorkerStatusMapperTest\" :modules:indexer-worker:test --tests \"*GrpcHealthServiceTest\"` -> pass.
6. `npm --prefix modules/ui-web run test:unit:run -- src/components/views/health/deriveHealthEvents.test.ts` -> pass.

## Implementation Pass B (2026-02-19)

Executed order (no multi-hour runs):

| Order | ID     | Status                | Output                                                                                                         |
| ----- | ------ | --------------------- | -------------------------------------------------------------------------------------------------------------- |
| 1     | RR-002 | Done                  | Canonical breaker in `ipc-common` with observer hooks; app-services breaker converted to compatibility adapter |
| 2     | RR-003 | Done                  | Retry policy governance consolidated through shared `GrpcRetryServiceConfig` + matrix policy IDs               |
| 3     | RR-008 | Done (`G0`)           | SLO contracts + OpenSLO spec + observe-first budget snapshot builder                                           |
| 4     | RR-010 | Done (`short smokes`) | Calibration policy/schema/builder + bounded Windows smoke runner                                               |
| 5     | RR-013 | Done (`G0`)           | P0-P6 conformance schema + builder + human ratification contract, wired into governance evidence/gates         |

### Additional Artifacts Added (Pass B)

1. `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.json`
2. `docs/reference/contracts/runtime-resilience-slo.v1.md`
3. `docs/reference/contracts/runtime-resilience-slo.v1.openslo.yaml`
4. `docs/reference/contracts/runtime-resilience-error-budget-policy.v1.md`
5. `scripts/governance/resilience/build-runtime-resilience-budget-snapshot.mjs`
6. `scripts/resilience/calibration/grpc-resilience-policy.v1.json`
7. `scripts/resilience/calibration/grpc-breaker-retry-calibration-report.v1.schema.json`
8. `scripts/resilience/calibration/build-grpc-breaker-retry-calibration-report.mjs`
9. `scripts/resilience/calibration/run-grpc-resilience-smoke-win.ps1`
10. `scripts/resilience/faults/fixtures/grpc-retry-soak-report-baseline.v1.json`
11. `scripts/resilience/faults/fixtures/grpc-retry-soak-report-candidate.v1.json`
12. `scripts/governance/resilience/runtime-resilience-control-loop-conformance.v1.schema.json`
13. `scripts/governance/resilience/build-runtime-resilience-control-loop-conformance.mjs`
14. `docs/reference/contracts/runtime-resilience-control-loop-conformance.v1.md`

### Additional Validation Evidence (Short-Run Only)

1. `node scripts/resilience/validate-rpc-retry-ownership.mjs` -> pass after RR-003 refactor (`expectedMethods=35`, `matrixMethods=35`, `transportRetryOwnedMethods=21`, `policyProfiles=2`).
2. `./gradlew.bat :modules:ipc-common:test --tests "*GrpcCircuitBreakerTest" --tests "*GrpcRetryServiceConfigTest"` -> pass.
3. `./gradlew.bat :modules:app-services:test --tests "*GrpcCircuitBreakerTest" --tests "*RemoteKnowledgeClientRetryConfigTest"` -> pass.
4. `./gradlew.bat :modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest"` -> pass.
5. `./gradlew.bat :modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest"` -> pass.
6. `node scripts/ci/evaluate-release-governance.mjs ... --mode G0` with deterministic pass/fail fixtures -> pass (`allow` and `hold`).
7. `node scripts/resilience/faults/validate-grpc-soak-report.mjs ... --mode G0` comparable + mismatch fixture cases -> pass (`pass` and `warn` with `MISMATCH_RETRY_POLICY`).
8. `powershell -File scripts/resilience/calibration/run-grpc-resilience-smoke-win.ps1 -Mode short` -> pass (4/4 short steps, calibration report emitted).
9. `node scripts/governance/resilience/build-runtime-resilience-budget-snapshot.mjs ...` -> pass (deterministic snapshot emitted).
10. `node scripts/governance/resilience/build-runtime-resilience-control-loop-conformance.mjs ...` -> pass (conformance verdict `warn` due missing P5 TCO artifacts; no critical failures in implemented slice).

## Implementation Pass C (2026-02-19)

Post-review stabilization focused on eliminating false-healthy evidence paths and brittle validation assumptions.

| Order | ID                             | Status      | Output                                                                                                                                                                                       |
| ----- | ------------------------------ | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | RR-010 hardening               | Done        | `run-grpc-resilience-smoke-win.ps1` now enforces per-step hard timeout (`-StepTimeoutMinutes`, max 30) with timeout status + process-tree termination metadata                               |
| 2     | RR-010 data quality            | Done        | Calibration builder rejects missing per-profile metrics (`MISSING_PROFILE_METRICS`, `MISSING_SUCCESS_RATE`, `MISSING_LATENCY_P95`, `MISSING_BREAKER_REJECTION_RATE`) and emits `dataQuality` |
| 3     | RR-008 integrity               | Done        | Budget snapshot enforces strict required evidence integrity for benchmark/beir/rag/agent artifacts with contradiction-aware `RRS-006` semantics                                              |
| 4     | RR-011 producer hardening      | Done        | Added canonical `release-evidence-index.v1` builder: `scripts/governance/resilience/build-release-evidence-index.mjs`                                                                        |
| 5     | RR-009 governance SoT          | Done        | Added `grpc-retry-policy-profiles.v1.schema.json`; validator now checks profile schema + value constraints (not ID-only)                                                                     |
| 6     | RR-009 validator robustness    | Done        | Removed source-text retry wiring sniffing from `validate-rpc-retry-ownership.mjs`; added fixture test script                                                                                 |
| 7     | RR-011 blast-radius mitigation | Done (`G0`) | Added `run-rr219-regression-pack-win.ps1` + `rr219-regression-pack-report.v1`; governance now has warn-only gate for missing pack evidence                                                   |

### Additional Artifacts Added (Pass C)

1. `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.schema.json`
2. `scripts/governance/resilience/build-release-evidence-index.mjs`
3. `scripts/resilience/test-validate-rpc-retry-ownership.mjs`
4. `scripts/governance/resilience/run-rr219-regression-pack-win.ps1`

### Contract/Gate Updates (Pass C)

1. `scripts/governance/resilience/release-evidence-index.v1.schema.json`: adds optional `evidence.regressionPackReport`.
2. `scripts/governance/resilience/release-resilience-gates.v1.json`: adds `RG-016` warn gate (`MISSING_RR219_REGRESSION_PACK`).
3. `docs/reference/contracts/release-resilience-gates.v1.md`: documents canonical evidence-index builder and regression-pack visibility gate.

### Validation Evidence (Pass C Completion, 2026-02-19)

1. `node scripts/resilience/validate-rpc-retry-ownership.mjs` -> pass (`expectedMethods=35`, `matrixMethods=35`, `transportRetryOwnedMethods=21`, `policyProfiles=2`).
2. `node scripts/resilience/test-validate-rpc-retry-ownership.mjs` -> pass (fixture checks for unknown policy, missing evidence ref, duplicate method).
3. `./gradlew.bat :modules:ipc-common:test --tests "*GrpcCircuitBreakerTest" --tests "*GrpcRetryServiceConfigTest"` -> pass.
4. `./gradlew.bat :modules:app-services:test --tests "*RemoteKnowledgeClientRetryConfigTest"` -> pass.
5. `./gradlew.bat :modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest"` -> pass.
6. `./gradlew.bat :modules:app-ai:cleanTest :modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest"` -> pass.
7. Builder chain smoke (`build/test-results/rr219-smoke`):
   - `build-release-evidence-index.mjs` -> pass
   - `build-runtime-resilience-budget-snapshot.mjs` -> pass
   - `build-runtime-resilience-control-loop-conformance.mjs` -> pass (`verdict=warn`, expected under partial P5 evidence)
   - `evaluate-release-governance.mjs --mode G0` -> pass (`decision=hold`, non-blocking advisory)
8. `powershell -File scripts/resilience/calibration/run-grpc-resilience-smoke-win.ps1 -Mode short -StepTimeoutMinutes 10` -> pass (`passedSteps=4/4`, `timeoutCount=0`, calibration artifact emitted).
9. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath ... -StepTimeoutMinutes 20` -> pass (`passedSteps=11/11`, `timeoutCount=0`, regression-pack report emitted).
10. Contradiction fixture check: `build-runtime-resilience-budget-snapshot.mjs` with `release-evidence-index.fixture.v1.json` now emits `RRS-006.budgetState=red` when `present=true` evidence paths are unresolved/unreadable.

### Validation-Discovered Corrections (Applied)

1. Repo-root discovery for JSON policy parity tests was brittle (only checked cwd/parent). Updated all new parity tests to walk ancestor directories until `scripts/resilience/contracts` is found.
2. `RemoteKnowledgeClientRetryConfigTest` now correctly models `maxRetriesSource` behavior (`grpc-rkc-unavailable-v1`) when asserting expected `maxAttempts`.
3. `RRS-006` contradiction semantics now remain `red` even when required evidence count is zero (no null-to-unknown override on integrity contradiction).

## Implementation Pass D (2026-02-19)

Focused completion slice for remaining Phase E work (`RR-006`, `RR-007`, `RR-012`) plus script-level regression guards.

| Order | ID                     | Status | Output                                                                                                                             |
| ----- | ---------------------- | ------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| 1     | RR-006                 | Done   | Added versioned external-fit decision artifact + schema (`runtime-resilience-external-fit-decision.v1`) and canonical contract doc |
| 2     | RR-007                 | Done   | Added Toxiproxy standalone spike specification + scenario catalog (`toxiproxy-spike-scenarios.v1.json`)                            |
| 3     | RR-012                 | Done   | Added lifecycle/TCO register artifact + contract (`resilience-tooling-tco.v1`)                                                     |
| 4     | Guard tests            | Done   | Added fixture tests for evidence-index builder determinism and strict `RRS-006` contradiction semantics                            |
| 5     | Regression pack update | Done   | `run-rr219-regression-pack-win.ps1` now runs both new fixture tests before module tests                                            |

### Additional Artifacts Added (Pass D)

1. `scripts/governance/resilience/runtime-resilience-external-fit-decision.v1.schema.json`
2. `scripts/governance/resilience/runtime-resilience-external-fit-decision.v1.json`
3. `docs/reference/contracts/runtime-resilience-external-fit-decision.v1.md`
4. `scripts/resilience/faults/toxiproxy-spike-scenarios.v1.json`
5. `docs/reference/contracts/toxiproxy-spike-spec.v1.md`
6. `scripts/governance/resilience/resilience-tooling-tco.v1.json`
7. `docs/reference/contracts/resilience-tooling-tco.v1.md`
8. `scripts/governance/resilience/test-build-release-evidence-index.mjs`
9. `scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs`

### Validation Evidence (Pass D, Short-Run)

1. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass (deterministic output except `generatedAt`; missing required artifact rejected).
2. `node scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs` -> pass (valid evidence -> `RRS-006=green`; contradiction fixture -> `RRS-006=red`).
3. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath ... -StepTimeoutMinutes 20` -> pass (`passedSteps=11/11`, `timeoutCount=0`).

## Implementation Pass E (2026-02-19)

Focused closure slice for P2 conformance warning removal and external-artifact hardening, while keeping this tempdoc active.

| Order | Target                                  | Status | Output                                                                                                                      |
| ----- | --------------------------------------- | ------ | --------------------------------------------------------------------------------------------------------------------------- |
| 1     | P2 conformance pass now                 | Done   | Calibration evidence now flows from evidence index + regression pack into control-loop conformance (`--calibration-report`) |
| 2     | RR-007 schema hardening                 | Done   | Added strict schema for `toxiproxy-spike-scenarios.v1.json`                                                                 |
| 3     | RR-012 schema hardening                 | Done   | Added strict schema for `resilience-tooling-tco.v1.json`                                                                    |
| 4     | External artifact validation entrypoint | Done   | Added AJV-based validator + fixture tests and wired both into regression pack                                               |

### Additional Artifacts Added (Pass E)

1. `scripts/resilience/faults/toxiproxy-spike-scenarios.v1.schema.json`
2. `scripts/governance/resilience/resilience-tooling-tco.v1.schema.json`
3. `scripts/governance/resilience/validate-runtime-resilience-external-artifacts.mjs`
4. `scripts/governance/resilience/test-validate-runtime-resilience-external-artifacts.mjs`

### Updated Interfaces/Wiring (Pass E)

1. `scripts/governance/resilience/build-release-evidence-index.mjs`: additive CLI option `--calibration-report <path>` and output field `metadata.calibrationReportPath`.
2. `scripts/governance/resilience/run-rr219-regression-pack-win.ps1`: adds external-artifact validation steps, short calibration smoke step, and conformance wiring with explicit `--calibration-report`.
3. `scripts/governance/resilience/build-runtime-resilience-control-loop-conformance.mjs`: accepts calibration evidence from CLI and/or evidence-index metadata.
4. `rr219-regression-pack-report.v1`: additive `artifacts.calibrationReport` reference.

### Validation Evidence (Pass E Completion, Short-Run Only)

1. `node scripts/resilience/validate-rpc-retry-ownership.mjs` -> pass.
2. `node scripts/resilience/test-validate-rpc-retry-ownership.mjs` -> pass.
3. `node scripts/governance/resilience/validate-runtime-resilience-external-artifacts.mjs` -> pass.
4. `node scripts/governance/resilience/test-validate-runtime-resilience-external-artifacts.mjs` -> pass.
5. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass (`--calibration-report` success + missing-path failure coverage).
6. `node scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs` -> pass (`RRS-006=green` valid; `RRS-006=red` contradiction/parse-failure cases).
7. `./gradlew.bat :modules:ipc-common:test --tests "*GrpcCircuitBreakerTest" --tests "*GrpcRetryServiceConfigTest"` -> pass.
8. `./gradlew.bat :modules:app-services:test --tests "*RemoteKnowledgeClientRetryConfigTest"` -> pass.
9. `./gradlew.bat :modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest"` -> pass.
10. `./gradlew.bat :modules:app-ai:cleanTest :modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest"` -> pass.
11. `powershell -File scripts/resilience/calibration/run-grpc-resilience-smoke-win.ps1 -Mode short -StepTimeoutMinutes 10` -> pass; calibration artifact emitted:
    `build/test-results/rr219-passe/calibration/grpc-breaker-retry-calibration-report-20260219-163741.json`.
12. `node scripts/governance/resilience/build-release-evidence-index.mjs ... --calibration-report ...` -> pass; candidate evidence index emitted:
    `build/test-results/rr219-passe/candidate/release-evidence-index.v1.json`.
13. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath ... -StepTimeoutMinutes 20` -> pass:
    `build/test-results/rr219-passe/regression/rr219-regression-pack-report-20260219-163840.json` with `passedSteps=14`, `totalSteps=14`, `timeoutCount=0`.
14. Conformance artifact from the same run:
    `build/test-results/rr219-passe/regression/runtime-resilience-control-loop-conformance-20260219-163840.json` -> `verdict="pass"` (P2 now pass with calibration evidence attached).

### Current Track State (Explicit)

1. This tempdoc remains `active` and is not closed in Pass E.
2. Pass E closes the prior P2 conformance warning path for calibrated short-run evidence.
3. Remaining work is now narrowed to recurring lane history accumulation and governance promotion policy decisions.

## Implementation Pass F (2026-02-19)

Fix-all stabilization slice for post-implementation findings: strict conformance health semantics, same-run governance evidence freshness, deterministic calibration handoff, `RRS-006` identity integrity, and external-artifact semantic invariants.

| Order | Target                                   | Status | Output                                                                                                                                                  |
| ----- | ---------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | F1 conformance exit semantics            | Done   | `build-runtime-resilience-control-loop-conformance.mjs` now exits non-zero for `verdict=fail`; supports `--require-verdict` and emits `requiredVerdict` |
| 2     | F2 governance ordering/freshness         | Done   | `run-rr219-regression-pack-win.ps1` now evaluates governance only after same-run budget + calibration + conformance and via effective evidence index    |
| 3     | F3 deterministic calibration handoff     | Done   | `run-grpc-resilience-smoke-win.ps1` adds deterministic output options (`-SmokeSummaryOutPath`, `-CalibrationReportOutPath`, `-ResultOutPath`)           |
| 4     | F4 `RRS-006` identity integrity          | Done   | Budget snapshot builder now marks `present=true` kind/schema mismatches as contradictions with explicit reason codes                                    |
| 5     | F5 external semantic invariants          | Done   | External-artifact validator now enforces candidate/decision/trigger semantic consistency and TCO budget-sum parity                                      |
| 6     | F6 regression-pack integration/docs sync | Done   | Regression pack report adds `effectiveEvidenceIndex` + deterministic calibration artifact fields; docs updated                                          |

### Additional Artifacts Added (Pass F)

1. `scripts/governance/resilience/test-build-runtime-resilience-control-loop-conformance.mjs`

### Validation Evidence (Pass F Completion, Short-Run Only)

1. `node scripts/resilience/validate-rpc-retry-ownership.mjs` -> pass.
2. `node scripts/resilience/test-validate-rpc-retry-ownership.mjs` -> pass.
3. `node scripts/governance/resilience/validate-runtime-resilience-external-artifacts.mjs` -> pass (schema + semantic checks).
4. `node scripts/governance/resilience/test-validate-runtime-resilience-external-artifacts.mjs` -> pass (schema corruption + semantic invariant failures).
5. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass.
6. `node scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs` -> pass (`RRS-006` green valid; red for missing/parse/identity mismatch).
7. `node scripts/governance/resilience/test-build-runtime-resilience-control-loop-conformance.mjs` -> pass (default fail exit + strict `--require-verdict pass` behavior).
8. `./gradlew.bat :modules:ipc-common:test --tests "*GrpcCircuitBreakerTest" --tests "*GrpcRetryServiceConfigTest"` -> pass.
9. `./gradlew.bat :modules:app-services:test --tests "*RemoteKnowledgeClientRetryConfigTest"` -> pass.
10. `./gradlew.bat :modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest"` -> pass.
11. `./gradlew.bat :modules:app-ai:cleanTest :modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest"` -> pass.
12. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath build/test-results/rr219-passe/candidate/release-evidence-index.v1.json -StepTimeoutMinutes 20` -> pass:
    `tmp/resilience/governance/rr219-regression-pack-report-20260219-171649.json` with `passedSteps=15`, `totalSteps=15`, `timeoutCount=0`.
13. Same run conformance artifact:
    `tmp/resilience/governance/runtime-resilience-control-loop-conformance-20260219-171649.json` -> `requiredVerdict="pass"`, `verdict="pass"`.
14. Gate-run confirmation (3 consecutive successful short regression-pack runs, no skipped steps):
    - `tmp/resilience/governance/rr219-regression-pack-report-20260219-171649.json` (`passedSteps=15/15`, `timeoutCount=0`, `nonPassedSteps=0`)
    - `tmp/resilience/governance/rr219-regression-pack-report-20260219-175013.json` (`passedSteps=15/15`, `timeoutCount=0`, `nonPassedSteps=0`)
    - `tmp/resilience/governance/rr219-regression-pack-report-20260219-175316.json` (`passedSteps=15/15`, `timeoutCount=0`, `nonPassedSteps=0`)
    -> Promotion precondition from Pass F recommendation is satisfied and recorded.

### Pass F Corrective Note

1. Initial Pass F regression-pack attempt exposed a PowerShell path-parent bug in the calibration runner (`Split-Path` parameter-set conflict) under deterministic output paths.
2. `run-grpc-resilience-smoke-win.ps1` now uses explicit parent-directory derivation (`System.IO.Path::GetDirectoryName`) and the rerun succeeded end-to-end.

## Implementation Pass G (2026-02-19)

Scheduled advisory lane activation for recurring RR-219 evidence, without changing `G0` blocking semantics.

| Order | Target                             | Status | Output                                                                                                                                         |
| ----- | ---------------------------------- | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | Scheduled non-blocking RR-219 lane | Done   | Added `.github/workflows/rr219-resilience-governance-nightly.yml` (`schedule` + `workflow_dispatch`, `continue-on-error: true`)                |
| 2     | Deterministic input generation     | Done   | Workflow generates deterministic short-run evidence artifacts and builds canonical `release-evidence-index.v1` before invoking regression pack |
| 3     | Artifact/summary publishing        | Done   | Workflow emits summary with conformance verdict and uploads nightly inputs, reports, conformance docs, and per-step logs                       |

### Validation Evidence (Pass G, Short-Run)

1. Workflow YAML syntax check:
   `Get-Content .github/workflows/rr219-resilience-governance-nightly.yml -Raw | npx --yes yaml valid` -> pass.
2. Contract behavior preserved:
   workflow runs existing `scripts/governance/resilience/run-rr219-regression-pack-win.ps1` with bounded `-StepTimeoutMinutes` and strict conformance requirement carried through pack step 13 (`--require-verdict pass`).
3. Tempdoc remains `active`; no closure in this pass.

## Implementation Pass H (2026-02-19)

Clean restart + issue investigation slice after external process interference (another agent session starting dev server / llama-server). This pass re-ran each lane once and classified failures as infra, comparability, or true-regression candidates.

### Restart Isolation Preflight

1. Ran `scripts/dev/cleanup.ps1 -Force`.
2. Terminated lingering local inference/runtime processes (`llama-server.exe`, stale Java PIDs).
3. Cleared stale dev-runner lock state (`tmp/dev-runner/active.json`).
4. Verified loopback ports were free before reruns (`9831`, `8080`, `5173`).

### Single-Run Lane Results (Post-Restart)

| Lane                     | Artifact                                                                      | Result                                                                       | Classification                          |
| ------------------------ | ----------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | --------------------------------------- |
| claim-a                  | `tmp/agent-evidence/_summaries/claim-a-report-manifest-restart.json`          | pass (`decision_gate_status=pass`, regressions `0`)                          | healthy                                 |
| search-rank              | `tmp/agent-evidence/_summaries/search-eval-rank-report-manifest-restart.json` | pass (`decision_gate_status=pass`, regressions `0`, comparable=true)         | healthy                                 |
| track-g                  | `tmp/agent-evidence/_summaries/track-g-report-manifest-restart.json`          | fail (`decision_gate_status=fail`, regressions `7`, comparable=true)         | true-regression candidate               |
| beir-gate (scifact)      | `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-restart.json`   | fail (`final_exit_code=1`, comparable=true, regressions `2`)                 | true-regression candidate               |
| perf                     | `tmp/agent-evidence/_summaries/perf-ci-manifest-restart.json`                 | decision fail (`regressions=10`, `non_comparable_count=1`, comparable=false) | comparability-mismatched run config     |
| agent-live-battery       | `tmp/agent-evidence/_summaries/agent-live-battery-manifest-restart.json`      | manifest pass, but quality low (`passRate=0.375`, infra/teardown=false)      | semantic-quality concern (not infra)    |
| rag (overnight rag-only) | `tmp/agent-evidence/_summaries/rag-eval-report-manifest-20260219-194405.json` | fail (`non_comparable_count=1`, comparable=false, `baseline_exists=false`)   | script bug + fallback baseline mismatch |

### Root-Cause Findings

1. RAG non-comparable is not a model failure in this run; it is a profile-selection path failure in `scripts/bench/overnight-rag-ai-queue-win.ps1`.
2. The queue summary shows parse warning:
   - `Failed to parse RAG result snapshot for run 01: The property 'Value' cannot be found on this object.`
3. Root cause in profile matcher:
   - `scripts/bench/overnight-rag-ai-queue-win.ps1:355` uses `$QueryCount.Value` even when value is plain `Int32`, throwing and dropping profile binding.
4. After profile binding fails, diff path falls back to legacy stub baseline alias:
   - `scripts/bench/overnight-rag-ai-queue-win.ps1:646-648` -> `"scripts/bench/baselines/rag-eval-baseline.v2.json"`,
   producing `similarity_mode` mismatch (`stub-jaccard` vs `embedding`) and forced non-comparable fail.

5. Perf non-comparable row is expected given this invocation used `-SkipUiScenario`; decision shows missing scenario-set row:
   - `phase2_ui_perf_smoke` missing in candidate.
6. Therefore this specific perf run is useful for smoke signal, but not valid for strict comparability conclusions.

7. Track-G and BEIR are comparable failures with no infra errors in logs:
   - Track-G decision: `tmp/agent-evidence/_summaries/track-g-report-decision-20260219-191433.json`
   - BEIR decision: `tmp/agent-evidence/_summaries/beir-gate-scifact-20260219-193120.decision.json`
8. Track-G regressions are concentrated in `worst_p95_ms` and `index_dir_bytes`; baseline is also stale (~11 days), so triage requires deciding regression vs baseline refresh.
9. BEIR regression is hybrid-only (`meanRecallAtK`, `meanNdcgAtK`) while lexical stayed near baseline, indicating retrieval-hybrid quality drift rather than infra instability.

10. Agent lane low pass rate is semantic (missing facts/keywords) with zero infra/teardown failures; run was `phase=A` non-blocking and used aggressive compression defaults (`enabled=true`, `minChars=200`, `keepLastResults=0`) in `scripts/ci/run-agent-live-battery.mjs`.

### Consolidated Restart Scorecard

1. `tmp/agent-evidence/_summaries/benchmark-scorecard-restart.json`
2. `tmp/agent-evidence/_summaries/benchmark-scorecard-restart.md`
3. Status: `release_readiness=warn`, with reasons matching lane outcomes above (track-g regressions, beir/rag/perf warnings, agent pass-rate warning, stale baseline warnings).

### Residuals Updated by Pass H

1. Runtime infra stability for short runs is currently acceptable after cleanup (no lane-level infra failure markers in restart manifests).
2. Remaining blockers before treating overnight history as quality evidence:
   - fix RAG baseline-profile selection bug in overnight queue path,
   - run comparable perf lane (no scenario-set omission),
   - triage or refresh baselines for comparable Track-G/BEIR failures.
3. Tempdoc remains `active`; this pass adds diagnosis evidence only and does not close remaining governance promotion work.

## Implementation Pass I (2026-02-19)

First real remediation from Pass H: fixed the strict-mode nullable access bug that broke overnight RAG baseline profile selection.

### Fix Applied

1. Updated `scripts/bench/overnight-rag-ai-queue-win.ps1` in `Resolve-RagBaselineProfile`:
   - from: `$qRaw -eq $QueryCount.Value`
   - to: `$qRaw -eq [int]$QueryCount`
2. This removes strict-mode property access on a plain `Int32`, which was causing:
   - `Failed to parse RAG result snapshot for run XX: The property 'Value' cannot be found on this object.`

### Verification (Short, Non-Heavy)

1. Script parse check:
   - `[ScriptBlock]::Create((Get-Content -Raw 'scripts/bench/overnight-rag-ai-queue-win.ps1'))` -> `syntax_ok`.
2. Strict-mode expression sanity check for nullable/non-nullable cases:
   - `qRaw=24; queryCount=24; qOk=True`
   - `qRaw=24; queryCount=; qOk=False`
   - `qRaw=; queryCount=24; qOk=True`

### What This Fix Does Not Yet Prove

1. A fresh overnight RAG queue rerun is still required to confirm profile selection now binds `embedding-cross-encoder-q24` end-to-end and removes the artificial non-comparable fallback path.

## Implementation Pass J (2026-02-19)

Deep investigation of remaining blockers using existing run history artifacts (no multi-hour reruns in this pass). Goal: convert residuals into concrete, low-ambiguity remediation paths.

Status update:
1. Pass J identified remediation targets only.
2. Those targets were implemented and validated in Pass K.

### Evidence Findings

1. **Track-G outlier severity is strongly tied to low-repeat runs (`LatencyRunRepeats=1`)**
   - Recent decision history shows repeat-3 runs usually in the `~1.0-1.5` ratio band, while repeat-1 runs produced extreme outliers (`doc-20000 worst_p95_ms` ratio `11.626` and `9.600`) and 7-regression bursts.
   - Evidence:
     - `tmp/agent-evidence/_summaries/track-g-report-decision-20260219-183234.json`
     - `tmp/agent-evidence/_summaries/track-g-report-decision-20260219-191433.json`
2. **Track-G also has a persistent moderate drift signal even with repeat-3**
   - Over 41 recent repeat-3 runs, `doc-20000 | worst_p95_ms` median ratio is ~`1.186` (above `1.10` threshold), indicating chronic baseline/policy mismatch, not only random spikes.
3. **BEIR-scifact regression is deterministic across repeated runs**
   - Both observed runs fail on exactly the same hybrid metrics and near-identical values:
     - `hybrid meanRecallAtK` ratio `0.939024`
     - `hybrid meanNdcgAtK` ratio `0.93659`
   - Lexical mode remains near baseline, implying targeted hybrid-path degradation or hybrid-profile drift.
   - Evidence:
     - `tmp/agent-evidence/_summaries/beir-gate-scifact-20260219-184841.decision.json`
     - `tmp/agent-evidence/_summaries/beir-gate-scifact-20260219-193120.decision.json`
4. **Perf non-comparable outcomes are mostly profile/run-shape mismatches, not evaluator defects**
   - History shows frequent `NON_COMPARABLE` rows due scenario-set and knob mismatches (`missing phase2_ui_perf_smoke`, sampling profile differences, UI iteration differences).
   - Current restart failure again reflects `phase2_ui_perf_smoke` missing in candidate from `-SkipUiScenario`.
5. **Search-eval/BEIR comparability metadata is currently too weak for root-cause attribution**
   - Comparability checks currently key on workload (`dataset/split/k`) but do not include hybrid-control provenance (vector weighting, candidate limits, low-signal thresholds, embedding model fingerprint), leaving a blind spot for semantic drift diagnosis.
6. **RAG profile-selection fix is applied, but end-to-end proof still pending**
   - Pass I fixed strict-mode nullable access; residual now is verification via fresh queue run.

### Determined Fixes (Implementation Targets)

1. **RAG queue hardening (P0)**
   - Keep Pass I fix.
   - Remove silent fallback to legacy stub baseline when profile resolution fails in real-embedding lane:
     - on profile resolution failure, mark run explicit error and stop diff for that run;
     - emit `profile_resolution_error` and `baseline_selection_mode` in run record.
   - Add fixture test for profile selection under `Set-StrictMode -Version Latest`.

2. **Track-G comparability and stability hardening (P0/P1)**
   - Treat `LatencyRunRepeats < 3` as non-gating/debug profile:
     - either enforce `>=3` for gated runs, or mark decisions as non-comparable for gate purposes.
   - Extend `diff-track-g-suite.mjs` comparability checks to include run-profile knobs (`latency_run_repeats`, core workload knobs) and emit `NON_COMPARABLE` reason when mismatched.
   - Rebuild baseline using repeat-3-only methodology and record profile metadata in baseline artifact.
   - Evaluate whether `doc-20000 worst_p95_ms` needs a lane-specific threshold adjustment or baseline refresh based on repeat-3 history.

3. **Perf profile split and guardrails (P0/P1)**
   - Add explicit perf baseline profiles (UI-on vs UI-skipped if UI-skipped remains a supported quick path).
   - In `run-perf-regression-win.ps1`, fail-fast in gate mode when requested run shape cannot be comparable to selected baseline profile (instead of producing misleading gate summaries).
   - Keep `-SkipUiScenario` path for smoke diagnostics, but classify as non-gating profile by contract.

4. **BEIR hybrid drift diagnosis and comparability upgrade (P1)**
   - Expand `search-eval` artifact metadata and diff comparability keys to include hybrid-profile provenance:
     - embedding model identifier/fingerprint,
     - hybrid weighting/candidate-limit/low-signal knobs,
     - search profile identifier.
   - Add BEIR preflight assertion in gate scripts that embedding path is ready/pinned to expected model profile.
   - After metadata hardening, run a short BEIR A/B diagnostic (same dataset, same corpus, profile-varied knobs) to determine:
     - true algorithmic regression vs
     - expected behavior under changed hybrid defaults/profile.
   - Based on outcome, either fix hybrid tuning or promote baseline with explicit profile version bump.

5. **Governance/trend residual closure path (P2)**
   - Keep `G0` advisory.
   - Collect sufficient recurring run history after the above profile/comparability fixes to avoid trend contamination from apples-to-oranges runs.
   - Only then evaluate `G1` promotion criteria.

## Implementation Pass K (2026-02-19)

Comparability + provenance hardening completion slice for RR-219 residuals (short-run only; no multi-hour runs).

| Order | Target                                  | Status | Output                                                                                                                                                                        |
| ----- | --------------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | RAG overnight strict baseline selection | Done   | Removed implicit `rag-eval-baseline.v2.json` fallback path; profile resolution is mandatory before diff and emits explicit `RAG_PROFILE_RESOLUTION_FAILED` warning on failure |
| 2     | Track-G comparability hardening         | Done   | Added repeat-profile normalization (`latency_run_repeats`), non-comparable policy controls, and locked gate policy (`max_non_comparable_count=2`)                             |
| 3     | Perf dual-baseline profile guardrails   | Done   | Added profile registry (`UI-on` vs `UI-skipped`), gate-mode baseline/profile preflight, and explicit comparability profile metadata                                           |
| 4     | BEIR provenance hardening               | Done   | Added profile-aware runner env overrides, provenance fields in v1/v2 artifacts, and provenance-required comparability checks                                                  |
| 5     | Regression pack wiring                  | Done   | Added short deterministic comparator/converter tests to RR-219 regression pack and validated end-to-end pack health                                                           |

### Artifacts and Interfaces Updated (Pass K)

1. RAG queue hardening:
   - `scripts/bench/overnight-rag-ai-queue-win.ps1`
2. Track-G policy and workflows:
   - `scripts/bench/lib/suite-loader.mjs`
   - `scripts/bench/diff-track-g-suite.mjs`
   - `scripts/ci/run-track-g-report-win.ps1`
   - `scripts/bench/promote-track-g-baseline-win.ps1`
   - `.github/workflows/track-g-report-win.yml`
   - `scripts/bench/test-diff-track-g-suite.mjs` (new)
3. Perf dual-baseline profile model:
   - `scripts/perf/baselines/perf-baseline-profiles.v1.json` (new)
   - `scripts/ci/run-perf-regression-win.ps1`
   - `scripts/perf/promote-perf-baseline-repo-win.ps1`
   - `.github/workflows/perf-regression-win.yml`
   - `scripts/perf/baselines/README.md`
4. BEIR provenance contract + comparability:
   - `scripts/ci/lib/benchmark-ci-common.ps1` (`Start-DevRunnerManaged -EnvOverrides`)
   - `scripts/ci/run-beir-gate-win.ps1`
   - `scripts/search/beir-eval-win.ps1`
   - `scripts/bench/convert-beir-metrics-v1-to-v2.mjs`
   - `scripts/bench/lib/suite-loader.mjs`
   - `scripts/bench/diff-search-eval-suite.mjs`
   - `scripts/bench/promote-search-eval-beir-baseline-win.ps1`
   - `.github/workflows/beir-eval-gate-win.yml`
   - `scripts/bench/test-diff-search-eval-suite.mjs` (new)
   - `scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs` (new)
   - `scripts/bench/baselines/search-eval-beir-*-baseline.metrics*.json` (provenance additions)
5. RR-219 regression pack:
   - `scripts/governance/resilience/run-rr219-regression-pack-win.ps1` (added comparator/converter test steps)

### Validation Evidence (Pass K Completion, Short-Run)

1. Comparator/converter tests:
   - `node scripts/bench/test-diff-rag-eval-suite.mjs` -> pass
   - `node scripts/bench/test-diff-track-g-suite.mjs` -> pass
   - `node scripts/bench/test-diff-search-eval-suite.mjs` -> pass
   - `node scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs` -> pass
2. RR-219 resilience validator/evaluator tests:
   - `node scripts/resilience/validate-rpc-retry-ownership.mjs` -> pass
   - `node scripts/resilience/test-validate-rpc-retry-ownership.mjs` -> pass
   - `node scripts/governance/resilience/validate-runtime-resilience-external-artifacts.mjs` -> pass
   - `node scripts/governance/resilience/test-validate-runtime-resilience-external-artifacts.mjs` -> pass
   - `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass
   - `node scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs` -> pass
   - `node scripts/governance/resilience/test-build-runtime-resilience-control-loop-conformance.mjs` -> pass
3. Targeted module tests:
   - `./gradlew.bat :modules:ipc-common:test --tests "*GrpcCircuitBreakerTest" --tests "*GrpcRetryServiceConfigTest"` -> pass
   - `./gradlew.bat :modules:app-services:test --tests "*RemoteKnowledgeClientRetryConfigTest"` -> pass
   - `./gradlew.bat :modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest"` -> pass
   - `./gradlew.bat :modules:app-ai:cleanTest :modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest"` -> pass
4. Regression pack end-to-end:
   - `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passk.json -StepTimeoutMinutes 20`
   - Result: `ok=true`, `passedSteps=18`, `totalSteps=18`, `timeoutCount=0`
   - Report: `tmp/resilience/governance/rr219-regression-pack-report-20260219-213448.json`

### Pass K Closure Note

1. Pass J residual implementation targets are now completed.
2. Tempdoc remains `active`; closure is intentionally deferred for trend-history and governance-mode promotion decisions.

## Implementation Pass L (2026-02-19)

Post-review fix completion for remaining correctness gaps in comparability/provenance wiring.

| Order | Target                                   | Status | Output                                                                                                              |
| ----- | ---------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------------- |
| 1     | BEIR profile-aware baseline routing      | Done   | Added shared baseline resolver so `stub-jaccard` and `embedding-nomic-q4` map to different baseline paths           |
| 2     | BEIR provenance truthfulness             | Done   | `beir-eval` provenance now derives embedding metadata from selected profile semantics (not ambient process env)     |
| 3     | Perf no-UI profile artifact completeness | Done   | Materialized missing `win-regression-no-ui` and `win-smoke-no-ui` baseline artifacts referenced by profile registry |
| 4     | Wrapper-level regression coverage        | Done   | Added `test-beir-baseline-profile-selection.ps1` and wired it into RR-219 regression pack                           |

### Artifacts and Interfaces Updated (Pass L)

1. Profile-aware BEIR baseline path resolver:
   - `scripts/ci/lib/benchmark-ci-common.ps1` (`Resolve-BeirBaselinePath`)
2. BEIR gate wrapper baseline selection:
   - `scripts/ci/run-beir-gate-win.ps1`
3. BEIR metrics provenance semantics:
   - `scripts/search/beir-eval-win.ps1`
4. RR-219 regression pack coverage:
   - `scripts/ci/test-beir-baseline-profile-selection.ps1` (new)
   - `scripts/governance/resilience/run-rr219-regression-pack-win.ps1`
5. Added no-UI perf baselines referenced by `perf-baseline-profiles.v1`:
   - `scripts/perf/baselines/win-regression-no-ui.perf-suite.json`
   - `scripts/perf/baselines/win-regression-no-ui.perf-suite.v2.json`
   - `scripts/perf/baselines/win-regression-no-ui.perf-suite.md`
   - `scripts/perf/baselines/win-smoke-no-ui.perf-suite.json`
   - `scripts/perf/baselines/win-smoke-no-ui.perf-suite.v2.json`
   - `scripts/perf/baselines/win-smoke-no-ui.perf-suite.md`

### Validation Evidence (Pass L Completion, Short-Run)

1. Wrapper-level BEIR baseline/profile selection:
   - `powershell -File scripts/ci/test-beir-baseline-profile-selection.ps1` -> pass
2. Comparator/converter regression tests:
   - `node scripts/bench/test-diff-track-g-suite.mjs` -> pass
   - `node scripts/bench/test-diff-search-eval-suite.mjs` -> pass
   - `node scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs` -> pass
3. Script parse checks:
   - `scripts/ci/lib/benchmark-ci-common.ps1` -> parse ok
   - `scripts/ci/run-beir-gate-win.ps1` -> parse ok
   - `scripts/search/beir-eval-win.ps1` -> parse ok
   - `scripts/governance/resilience/run-rr219-regression-pack-win.ps1` -> parse ok
4. Perf no-UI baseline generation source run:
   - `powershell -File scripts/perf/run-perf-suite-win.ps1 -SamplingProfile regression -SkipUiScenario -EvidenceOutRoot tmp/agent-evidence` -> pass
   - Produced source suite: `tmp/agent-evidence/_summaries/perf-suite-20260219-215320.json` (+ v2/md)

## Implementation Pass M (2026-02-19)

Overnight-prep closure slice: bootstrap missing baseline assets, validate wrapper parity, and re-run bounded RR-219 pack with newest wiring.

| Order | Target                                  | Status | Output                                                                                                     |
| ----- | --------------------------------------- | ------ | ---------------------------------------------------------------------------------------------------------- |
| 1     | BEIR embedding baseline bootstrap       | Done   | Generated and promoted embedding-profile BEIR baselines for `arguana`, `nfcorpus`, `scifact`               |
| 2     | Search-eval suite-loader parity bug fix | Done   | Fixed `workloadComparabilityProfile is not defined` in v2 load path and restored BEIR diff stability       |
| 3     | Wrapper smoke validation                | Done   | Stub + embedding BEIR smokes and perf no-UI smoke executed with expected comparability/provenance behavior |
| 4     | RR-219 regression pack revalidation     | Done   | Latest pack run passed with expanded wrapper coverage (`19/19`, `timeoutCount=0`)                          |
| 5     | Overnight entrypoint readiness check    | Done   | Queue no-op smoke produced clean session summary/log outputs                                               |

### Artifacts and Interfaces Updated (Pass M)

1. Search-eval suite loader fix:
   - `scripts/bench/lib/suite-loader.mjs`
2. Embedding BEIR baseline assets:
   - `scripts/bench/baselines/search-eval-beir-arguana-embedding-baseline.metrics.json`
   - `scripts/bench/baselines/search-eval-beir-arguana-embedding-baseline.metrics.v2.json`
   - `scripts/bench/baselines/search-eval-beir-nfcorpus-embedding-baseline.metrics.json`
   - `scripts/bench/baselines/search-eval-beir-nfcorpus-embedding-baseline.metrics.v2.json`
   - `scripts/bench/baselines/search-eval-beir-scifact-embedding-baseline.metrics.json`
   - `scripts/bench/baselines/search-eval-beir-scifact-embedding-baseline.metrics.v2.json`

### Validation Evidence (Pass M Completion, Short-Run)

1. BEIR embedding baseline bootstrap + promotion:
   - `powershell -File scripts/ci/run-beir-gate-win.ps1 -Datasets "arguana,nfcorpus,scifact" -ProfileId embedding-nomic-q4 -MaxQueries 300 -SkipDownload ...`
   - Manifest: `tmp/agent-evidence/_summaries/beir-gate-embedding-bootstrap-manifest.json`
   - Promotion: `powershell -File scripts/bench/promote-search-eval-beir-baseline-win.ps1 -ProfileId embedding-nomic-q4 ...`
2. BEIR wrapper smoke checks:
   - Stub smoke manifest: `tmp/agent-evidence/_summaries/beir-gate-stub-smoke-manifest-2.json`
   - Embedding smoke manifest: `tmp/agent-evidence/_summaries/beir-gate-embedding-smoke-manifest-2.json`
3. Perf no-UI stable smoke:
   - Manifest: `tmp/agent-evidence/_summaries/perf-stable-no-ui-smoke-manifest.json`
4. Wrapper and comparator tests:
   - `powershell -File scripts/ci/test-beir-baseline-profile-selection.ps1` -> pass
   - `node scripts/bench/test-diff-track-g-suite.mjs` -> pass
   - `node scripts/bench/test-diff-search-eval-suite.mjs` -> pass
   - `node scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs` -> pass
   - `node scripts/bench/test-diff-rag-eval-suite.mjs` -> pass
5. RR-219 regression pack rerun:
   - `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passk.json -StepTimeoutMinutes 20`
   - Report: `tmp/resilience/governance/rr219-regression-pack-report-20260219-224414.json`
   - Result: `ok=true`, `passedSteps=19`, `totalSteps=19`, `timeoutCount=0`
6. Overnight queue entrypoint no-op readiness smoke:
   - `powershell -File scripts/bench/overnight-rag-ai-queue-win.ps1 -SkipRagRuns -SkipClaimD -SkipClaimA -SkipTrackG -SkipAgentLiveBattery -SkipAgentLiveGateEval -SkipDocsChecks`
   - Session JSON: `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260219-224735.json`
   - Session MD: `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260219-224735.md`

### Pass M Notes

1. Stub BEIR smoke remains a true-regression signal (comparable, regression count > 0), not a routing/comparability defect.
2. Embedding BEIR smoke and perf no-UI smoke validate profile wiring and provenance/comparability controls for overnight execution.

## Implementation Pass N (2026-02-20)

Run-health correction pass focused on resolving overnight/CI harness defects before additional long-run accumulation.

| Order | Target                              | Status | Output                                                                                                                                                               |
| ----- | ----------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | Perf CI harness stability           | Done   | Added root `npm ci` in `.github/workflows/perf-regression-win.yml` so EBv1 validator dependencies (`ajv`, `ajv-formats`) are present on runner                       |
| 2     | Workflow summary parse safety       | Done   | Replaced fragile double-quoted PowerShell format strings with single-quoted `-f` format strings in perf/search/track/claim workflow summary blocks                   |
| 3     | BEIR skip-download robustness       | Done   | `run-beir-gate-win.ps1` now degrades gracefully when `-SkipDownload` is requested but cache is absent (forced download instead of eval crash)                        |
| 4     | BEIR cache probe correctness        | Done   | Cache probe now checks `raw/**/corpus.jsonl` recursively (not just `raw/corpus.jsonl`) so cached corpora are recognized correctly                                    |
| 5     | Track-G comparability noise removal | Done   | Promoted a repeat-3 baseline (`track-g-baseline.v2.json`) and verified `baseline_latency_run_repeats=3`, `candidate_latency_run_repeats=3`, `non_comparable_count=0` |
| 6     | Claim-A stale baseline cleanup      | Done   | Promoted fresh Claim-A baseline from latest 3-run suite to remove stale-baseline advisory noise                                                                      |
| 7     | Wrapper-level short smokes          | Done   | Executed BEIR (`stub-jaccard`, `embedding-nomic-q4`) + perf no-UI stable wrapper runs; failures now classify as true quality regressions, not harness defects        |

### Validation Evidence (Pass N Completion, Short-Run)

1. Comparator/contract tests:
   - `node scripts/bench/test-diff-track-g-suite.mjs` -> pass
   - `node scripts/bench/test-diff-search-eval-suite.mjs` -> pass
   - `node scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs` -> pass
   - `powershell -File scripts/ci/test-beir-baseline-profile-selection.ps1` -> pass
2. Track-G wrapper run:
   - `powershell -File scripts/ci/run-track-g-report-win.ps1 -SkipBuild -SkipQuantizationGate -LatencyRunRepeats 3 ...`
   - Manifest: `tmp/agent-evidence/_summaries/track-g-report-manifest-fix-20260220-104257.json`
   - Result: `decision_comparable=true`, `decision_non_comparable_count=0`, `required/candidate/baseline latency_run_repeats=3`
3. Perf no-UI stable wrapper run:
   - `powershell -File scripts/ci/run-perf-regression-win.ps1 -BaselineSuiteJson scripts/perf/baselines/win-regression-no-ui.perf-suite.json -BaselineProfileId win-regression-no-ui -GateLevel stable -SkipUiScenario ...`
   - Manifest: `tmp/agent-evidence/_summaries/perf-ci-manifest-fix-20260220-110648.json`
   - Result: `decision_gate_status=pass`, `decision_non_comparable_count=0`, `decision_regression_count=0`
4. BEIR wrapper runs (bounded queries, cache-aware):
   - Stub: `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-fix-stub2-20260220-111902.json`
   - Embedding: `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-fix-embed2-20260220-112144.json`
   - Result: `eval_exit_code=0` with `skip_download_effective=true`, `cache_probe_ready=true`; gate failures are regression-driven (`diff_exit_code=1`)
5. Scorecard recheck after baseline refresh + wrapper reruns:
   - `tmp/agent-evidence/_summaries/rr219-fix-scorecard-20260220-112346.json`
   - Cleared from prior warning mix: Track-G non-comparable warning and Claim-A stale-baseline warning
   - Remaining warnings are true signal areas (search-rank judged BEIR regressions, track-g regressions, agent/rag/perf trend history)

## Implementation Pass O (2026-02-20)

True-signal resolution and governance-prep slice: locked comparability inputs, added deterministic lane triage/history-readiness artifacts, and wired G0/G1 shadow decision support without changing production governance mode.

| Order | Target                                | Status | Output                                                                                                                                                                                   |
| ----- | ------------------------------------- | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | Comparability input lock              | Done   | Added `rr219-comparability-policy.v1` + schema and strict preflight validator for lane profile identity/provenance                                                                       |
| 2     | True-signal triage artifact           | Done   | Added `runtime-resilience-lane-triage.v1` builder + schema with deterministic classes (`infra_failure`, `non_comparable`, `persistent_regression`, `intermittent_regression`, `healthy`) |
| 3     | Comparable-history readiness artifact | Done   | Added `runtime-resilience-history-readiness.v1` builder + schema with critical-lane readiness verdicts                                                                                   |
| 4     | Governance evidence wiring            | Done   | Extended `release-evidence-index` with optional `runtimeHistoryReadiness`; added RG-017/RG-018 gates with mode-scoped escalation via `enforceInModes`                                    |
| 5     | G0->G1 promotion packet tooling       | Done   | Added `build-runtime-resilience-g1-readiness.mjs` to produce JSON/Markdown packet plus G0/G1 (no-waiver/with-waiver) decisions                                                           |
| 6     | Regression-pack integration           | Done   | `run-rr219-regression-pack-win.ps1` now runs comparability/triage/history/G1 packet steps and emits all artifact paths in report                                                         |

### Artifacts and Interfaces Added/Updated (Pass O)

1. Comparability contract:
   - `scripts/governance/resilience/rr219-comparability-policy.v1.json`
   - `scripts/governance/resilience/rr219-comparability-policy.v1.schema.json`
   - `scripts/governance/resilience/rr219-comparability-lib.mjs`
   - `scripts/governance/resilience/validate-rr219-comparability-inputs.mjs`
   - `scripts/governance/resilience/test-validate-rr219-comparability-inputs.mjs`
2. Triage and history readiness:
   - `scripts/governance/resilience/build-runtime-resilience-lane-triage.mjs`
   - `scripts/governance/resilience/runtime-resilience-lane-triage.v1.schema.json`
   - `scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs`
   - `scripts/governance/resilience/build-runtime-resilience-history-readiness.mjs`
   - `scripts/governance/resilience/runtime-resilience-history-readiness.v1.schema.json`
   - `scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs`
3. Governance and readiness packet:
   - `scripts/governance/resilience/build-runtime-resilience-g1-readiness.mjs`
   - `scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs`
   - `scripts/governance/resilience/test-evaluate-release-governance.mjs`
   - `scripts/ci/evaluate-release-governance.mjs` (mode-scoped gate severity support)
   - `scripts/governance/resilience/build-release-evidence-index.mjs` (`--runtime-history-readiness`)
   - `scripts/governance/resilience/release-evidence-index.v1.schema.json` (optional `runtimeHistoryReadiness`)
   - `scripts/governance/resilience/release-resilience-gates.v1.json` (RG-017/RG-018, `enforceInModes`)
   - `scripts/governance/resilience/run-rr219-regression-pack-win.ps1` (new conformance/history/G1 packet steps + artifact references)
4. Contract docs:
   - `docs/reference/contracts/release-resilience-gates.v1.md` (mode-scoped escalation + history-readiness overlay details)

### Validation Evidence (Pass O Completion, Short-Run)

1. New governance package tests:
   - `node scripts/governance/resilience/test-validate-rr219-comparability-inputs.mjs` -> pass
   - `node scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs` -> pass
   - `node scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs` -> pass
   - `node scripts/governance/resilience/test-evaluate-release-governance.mjs` -> pass
   - `node scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs` -> pass
2. Current comparability preflight:
   - Artifact: `tmp/resilience/governance/rr219-comparability-validation-current.json`
   - Result: `summary.ok=false`; failing lane `beir-gate` (`scifact: decision_comparable must be true`).
3. Current triage classification:
   - Artifact: `tmp/resilience/governance/runtime-resilience-lane-triage-current.json`
   - Summary: `infra_failure=0`, `non_comparable=1`, `persistent_regression=1`, `intermittent_regression=2`, `healthy=2`, `unresolvedUnknownCount=0`.
4. Current history readiness:
   - Artifact: `tmp/resilience/governance/runtime-resilience-history-readiness-current.json`
   - Result: `overallVerdict=fail`, `criticalLanesReady=false`.
   - Dominant reasons include insufficient comparable-run counts for critical lanes and persistent comparable regression in judged search-rank.
5. Current governance shadow outcomes:
   - `tmp/resilience/governance/release-governance-decision-current-g0.json` -> `decision=hold` (advisory).
   - `tmp/resilience/governance/release-governance-decision-current-g1.json` -> `decision=block`.
   - `tmp/resilience/governance/runtime-resilience-g1-readiness-current.json` -> `recommendation=stay_g0`.
6. Regression pack rerun with integrated Pass O steps:
   - `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-current.base.json -StepTimeoutMinutes 20`
   - Report: `tmp/resilience/governance/rr219-regression-pack-report-20260220-120418.json`
   - Result: `passedSteps=28`, `totalSteps=28`, `timeoutCount=0`, `infraFailure=false`.

### Pass O Decision Summary

1. Remaining warnings are now dominated by true quality/comparability signals, not harness parse/runtime failures.
2. G1 promotion is not ready; critical blockers are still BEIR gate/comparability quality and insufficient critical-lane comparable history.
3. Tempdoc remains `active`; no closure in this pass.

## Implementation Pass P (2026-02-20)

Execution continuation after Pass O: cleared the active BEIR comparability blocker, recomputed same-run governance artifacts, and added new comparable lane runs for history accumulation.

| Order | Target                                         | Status  | Output                                                                                                                     |
| ----- | ---------------------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------- |
| 1     | BEIR comparability unblock                     | Done    | Re-ran `run-beir-gate-win.ps1` for `scifact` + `embedding-nomic-q4`; decision now `comparable=true`, `gate_status=pass`    |
| 2     | Explicit comparability preflight rerun         | Done    | `rr219-comparability-validation-latest-explicit.json` now `summary.ok=true` for all 7 lanes                                |
| 3     | Same-run regression pack recompute             | Done    | `run-rr219-regression-pack-win.ps1` passed `28/28`, `timeoutCount=0`, with refreshed triage/history/conformance artifacts  |
| 4     | Post-pack evidence integrity cleanup           | Done    | Built post-pack evidence index including regression-pack report to remove false `RG-016` warning from governance decisions |
| 5     | Critical-lane history accumulation (short-run) | Partial | Added one new comparable run each for `track-g`, `search-rank (judged)`, and `perf`                                        |

### Validation Evidence (Pass P Completion)

1. BEIR rerun (embedding profile):
   - Manifest: `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-passO-rerun-20260220-121252.json`
   - Decision: `tmp/agent-evidence/_summaries/beir-gate-scifact-20260220-121254.decision.json`
   - Result: `decision_comparable=true`, `decision_gate_status=pass`.
2. Explicit comparability lock check:
   - `node scripts/governance/resilience/validate-rr219-comparability-inputs.mjs ... --out-json tmp/resilience/governance/rr219-comparability-validation-latest-explicit.json`
   - Result: `summary.ok=true`, `failedLanes=[]`.
3. RR-219 regression pack rerun:
   - `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passo-base-20260220-121649.json -StepTimeoutMinutes 20`
   - Report: `tmp/resilience/governance/rr219-regression-pack-report-20260220-121655.json`
   - Result: `passedSteps=28`, `totalSteps=28`, `timeoutCount=0`, `infraFailure=false`.
4. Post-pack governance shadow outputs (with regression-pack artifact included):
   - Evidence index: `tmp/resilience/governance/release-evidence-index-passo-postpack-20260220-121822.json`
   - G0 decision: `tmp/resilience/governance/release-governance-decision-passo-postpack-g0.json` -> `hold`, reason codes:
     - `AGENT_PHASE_A_NOT_GRADUATED`
     - `INSUFFICIENT_RUNTIME_HISTORY_CRITICAL_LANES`
   - G1 decision: `tmp/resilience/governance/release-governance-decision-passo-postpack-g1.json` -> `block` (history readiness escalation).
   - G1 readiness: `tmp/resilience/governance/runtime-resilience-g1-readiness-passo-postpack.json` -> `recommendation=stay_g0`.
5. Additional comparable short runs:
   - Track-G manifest: `tmp/agent-evidence/_summaries/track-g-report-manifest-passo-20260220-121916.json` -> `comparable=true`, `gate_status=fail`, `regression_count=5`, `non_comparable_count=0`.
   - Search-rank manifest: `tmp/agent-evidence/_summaries/search-rank-report-manifest-passo-20260220-124348.json` -> `comparable=true`, `gate_status=fail`, `regression_count=2`, `non_comparable_count=0`, judged profile `scifact|k10|q300`.
   - Perf manifest: `tmp/agent-evidence/_summaries/perf-ci-manifest-passo-20260220-124729.json` -> `comparable=true`, `gate_status=pass`, `regression_count=0`.
6. Updated triage/history snapshots:
   - Triage: `tmp/resilience/governance/runtime-resilience-lane-triage-passo-20260220-125234.json`
     - Classifications: `persistent_regression=2` (`search-rank`, `track-g`), `intermittent_regression=1` (`agent-battery`), `healthy=3` (`perf`, `beir-gate`, `rag-eval`).
   - History readiness: `tmp/resilience/governance/runtime-resilience-history-readiness-passo-20260220-125234.json`
     - `overallVerdict=fail`, `criticalLanesReady=false` (insufficient comparable-run counts remain the dominant gate).

### Pass P Decision Summary

1. Active BEIR comparability failure is resolved for the latest profile-locked run.
2. Remaining blockers are now primarily true quality signals (`search-rank`, `track-g`) and insufficient comparable history volume across critical lanes.
3. Governance remains `G0` by recommendation (`stay_g0`), with `G1` still blocked by history readiness and agent phase graduation criteria.

## Implementation Pass Q (2026-02-20)

Second short comparable-history batch after Pass P: added fresh BEIR/perf/search-rank evidence, regenerated triage/history snapshots, and re-evaluated governance with an updated scorecard-backed evidence index.

| Order | Target                                    | Status | Output                                                                                                                     |
| ----- | ----------------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------- |
| 1     | Additional comparable BEIR sample         | Done   | `embedding-nomic-q4` rerun remained `comparable=true`, `gate_status=pass`                                                  |
| 2     | Additional comparable perf sample         | Done   | `win-regression-no-ui` rerun remained `comparable=true`, `gate_status=pass`                                                |
| 3     | Additional comparable search-rank sample  | Done   | Judged profile stayed `scifact                                                                                             | k10 | q300` and repeated comparable regression (`regression_count=2`) |
| 4     | Triage/history refresh                    | Done   | Updated lane triage/history artifacts show no infra-failure drift, but persistent quality + history-volume blockers remain |
| 5     | Governance refresh with updated scorecard | Done   | G0 remains `hold`, G1 remains `block`; reason set stable and now reduced to two policy reasons                             |

### Validation Evidence (Pass Q Completion)

1. New comparable lane manifests:
   - BEIR: `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-passp2-20260220-125332.json` -> `decision_comparable=true`, `decision_gate_status=pass`.
   - Perf: `tmp/agent-evidence/_summaries/perf-ci-manifest-passp2-20260220-125332.json` -> `decision_comparable=true`, `decision_gate_status=pass`, `decision_regression_count=0`.
   - Search-rank: `tmp/agent-evidence/_summaries/search-rank-report-manifest-passp2-20260220-125332.json` -> comparable fail, judged regression persists.
2. Updated comparability lock validation:
   - `tmp/resilience/governance/rr219-comparability-validation-latest-explicit.json` -> `summary.ok=true`, all 7 lanes comparable.
3. Updated triage snapshot:
   - `tmp/resilience/governance/runtime-resilience-lane-triage-passp2-20260220-130258.json`
   - Summary: `persistent_regression=2` (`search-rank`, `track-g`), `intermittent_regression=1` (`agent-battery`), `healthy=3` (`perf`, `beir-gate`, `rag-eval`), `infra_failure=0`.
4. Updated history-readiness snapshot:
   - `tmp/resilience/governance/runtime-resilience-history-readiness-passp2-20260220-130258.json`
   - `overallVerdict=fail`, `criticalLanesReady=false`.
   - Comparable-run progress (critical lanes): `track-g 2/8`, `perf 3/8`, `beir-gate 2/4`, `rag-eval 1/8`, `agent-battery 5/14`.
5. Governance refresh with updated scorecard/evidence:
   - Scorecard: `tmp/agent-evidence/_summaries/rr219-scorecard-passp2-20260220-130318.json`
   - Evidence index: `tmp/resilience/governance/release-evidence-index-passp2-postpack.json`
   - G0: `tmp/resilience/governance/release-governance-decision-passp2-g0.json` -> `hold`
   - G1: `tmp/resilience/governance/release-governance-decision-passp2-g1.json` -> `block`
   - G1 readiness: `tmp/resilience/governance/runtime-resilience-g1-readiness-passp2.json` -> `stay_g0`
   - Remaining governance reason codes:
     - `AGENT_PHASE_A_NOT_GRADUATED`
     - `INSUFFICIENT_RUNTIME_HISTORY_CRITICAL_LANES`

### Pass Q Decision Summary

1. Comparability controls are now stable across the active run set; no new harness/comparability regressions surfaced.
2. Critical blockers are unchanged in type: persistent quality regressions (`search-rank`, `track-g`) and insufficient comparable-history depth.
3. Next progression is history accumulation at target profile locks plus delegated quality-owner remediation for the persistent lanes.

## Implementation Pass R (2026-02-20)

Third short comparable-history accumulation pass focused on low-cost critical lanes (`beir-gate`, `perf`) and readiness delta tracking.

| Order | Target                            | Status | Output                                                                                       |
| ----- | --------------------------------- | ------ | -------------------------------------------------------------------------------------------- |
| 1     | Additional BEIR comparable sample | Done   | `embedding-nomic-q4` rerun remains `comparable=true`, `gate_status=pass`                     |
| 2     | Additional perf comparable sample | Done   | `win-regression-no-ui` rerun remains `comparable=true`, `gate_status=pass`, zero regressions |
| 3     | History-readiness recompute       | Done   | Critical-lane counts improved but still below promotion targets                              |

### Validation Evidence (Pass R Completion)

1. New lane artifacts:
   - BEIR: `tmp/agent-evidence/_summaries/search-eval-beir-gate-manifest-passq2-20260220-130442.json`
   - Perf: `tmp/agent-evidence/_summaries/perf-ci-manifest-passq2-20260220-130442.json`
2. Updated history-readiness artifact:
   - `tmp/resilience/governance/runtime-resilience-history-readiness-passq2-20260220-131150.json`
   - `overallVerdict=fail`, `criticalLanesReady=false`.
3. Critical-lane comparable-run progress after Pass R:
   - `track-g`: `2/8`
   - `perf`: `4/8`
   - `beir-gate`: `3/4`
   - `rag-eval`: `1/8`
   - `agent-battery`: `5/14`
4. Blocking reason set remains aligned with governance outcomes:
   - `AGENT_PHASE_A_NOT_GRADUATED`
   - `INSUFFICIENT_RUNTIME_HISTORY_CRITICAL_LANES`

### Pass R Decision Summary

1. Comparability stability is maintained; no new infra/comparability defects observed in this pass.
2. Promotion remains blocked by insufficient history depth and unresolved persistent-quality lanes.
3. Next meaningful progress now requires higher-volume accumulation on `track-g`, `rag-eval`, and `agent-battery` (longer-run cadence).

## Implementation Pass S (2026-02-20)

Bounded queue accumulation pass for `rag-eval` + `agent-battery` (single-run mode), followed by readiness/governance recompute.

| Order | Target                     | Status | Output                                                                                        |
| ----- | -------------------------- | ------ | --------------------------------------------------------------------------------------------- |
| 1     | RAG history increment      | Done   | Queue produced one fresh RAG run with embedding profile baseline and comparable pass          |
| 2     | Agent history increment    | Done   | Queue produced one fresh agent battery attempt (`passRate=56.25%`, no infra/teardown failure) |
| 3     | Agent gate recompute       | Done   | Agent phase gate remained not graduated in this window                                        |
| 4     | History/governance refresh | Done   | Comparable-history counts improved, but G0/G1 decisions unchanged in reason set               |

### Validation Evidence (Pass S Completion)

1. Queue session:
   - Session JSON: `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260220-131232.json`
   - Session MD: `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260220-131232.md`
   - Queue mode: `RagRuns=1`, `AgentMaxAttempts=1`, `SkipClaimD`, `SkipClaimA`, `SkipTrackG`.
2. RAG run outcome:
   - Manifest: `tmp/agent-evidence/_summaries/rag-eval-report-manifest-20260220-131232.json`
   - Decision snapshot: `build/test-results/rag-eval/rag-eval-decision.phase6-real-embedding.20260220-131232.run01.v1.json`
   - Result: `decision_comparable=true`, `decision_gate=pass`, `rag_baseline_profile_id=embedding-cross-encoder-q24`.
3. Agent run outcome:
   - Manifest: `tmp/agent-evidence/_summaries/agent-live-battery-manifest-overnight-20260220-131232.attempt01.json`
   - Scorecard: `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260220-131232.json`
   - Battery result: `aggregate_pass_rate=0.5625`, `aggregate_infra_failure=false`.
   - Gate eval result: `gate_eval_exit_code=1` (phase not graduated).
4. Updated history-readiness:
   - `tmp/resilience/governance/runtime-resilience-history-readiness-passr2-20260220-134548.json`
   - Critical comparable counts after this pass:
     - `track-g: 2/8`
     - `perf: 4/8`
     - `beir-gate: 3/4`
     - `rag-eval: 2/8`
     - `agent-battery: 6/14`
5. Governance refresh:
   - Evidence index: `tmp/resilience/governance/release-evidence-index-passr2.json`
   - G0 decision: `tmp/resilience/governance/release-governance-decision-passr2-g0.json` -> `hold`
   - G1 decision: `tmp/resilience/governance/release-governance-decision-passr2-g1.json` -> `block`
   - G1 readiness: `tmp/resilience/governance/runtime-resilience-g1-readiness-passr2.json` -> `stay_g0`
   - Reason codes unchanged:
     - `AGENT_PHASE_A_NOT_GRADUATED`
     - `INSUFFICIENT_RUNTIME_HISTORY_CRITICAL_LANES`

### Pass S Decision Summary

1. This pass improved `rag-eval` and `agent-battery` history counts without introducing new infra/comparability defects.
2. Promotion remains blocked by history sufficiency and agent phase graduation criteria.
3. Remaining acceleration now requires higher-volume runs, especially for `track-g`, plus continued `rag/agent/perf/beir` accumulation.

## Overnight Execution Estimate (Planned, 2026-02-20)

Estimated remaining runtime to satisfy current history targets, using observed short-run durations:

| Lane                                        |                                              Remaining target gap | Observed avg runtime | Estimated subtotal |
| ------------------------------------------- | ----------------------------------------------------------------: | -------------------: | -----------------: |
| `track-g`                                   |                                           `6` runs (`2/8 -> 8/8`) |        ~`24 min/run` |         ~`144 min` |
| `perf` (`win-regression-no-ui`)             |                                           `4` runs (`4/8 -> 8/8`) |         ~`5 min/run` |          ~`20 min` |
| `beir-gate` (`embedding-nomic-q4`)          | `1-2` clean comparable runs (`3/4` plus profile-coverage cleanup) |       ~`2-3 min/run` |           ~`5 min` |
| `rag-eval` + `agent-battery` (paired queue) |                        `8` queue cycles (`rag 2/8`, `agent 6/14`) |      ~`32 min/cycle` |         ~`256 min` |

Total estimate:
1. Raw runtime: ~`425 min` (~`7h 05m`).
2. With recompute/coordination overhead: ~`7h 30m`.
3. With retry/failure buffer: reserve ~`8h 30m` to `9h`.

## Remaining Non-Run Work (Historical Snapshot, Superseded)

This section reflects pre-Pass-T planning state only.

Current authoritative open items are tracked in **Authoritative Open Residuals (Current)** near the end of this tempdoc.

## Why Split Out

`docs/tempdocs/210-agent-infra-external-fit-research.md` is whole-product strategy.
Runtime resilience has enough independent depth and remaining uncertainty to warrant dedicated ownership, sequencing, and closure criteria.

## Baseline (Code-Validated)

### What Is Implemented

| Item                                              | State       | Evidence                                                                                                                                                                                                          |
| ------------------------------------------------- | ----------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Shared retry service config for non-RKC clients   | Implemented | `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcRetryServiceConfig.java`                                                                                                                             |
| Shared circuit breaker for non-RKC clients        | Implemented | `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker.java`                                                                                                                                 |
| ANN search client resilience wiring               | Implemented | `modules/app-search/src/main/java/io/justsearch/app/search/GrpcAnnSearchClient.java`                                                                                                                              |
| Embedding client resilience wiring                | Implemented | `modules/app-search/src/main/java/io/justsearch/app/search/GrpcEmbeddingClient.java`                                                                                                                              |
| AI translator resilience wiring                   | Implemented | `modules/app-ai/src/main/java/io/justsearch/app/ai/GrpcAiTranslatorService.java`                                                                                                                                  |
| Worker health readiness fields in proto + service | Implemented | `modules/ipc-common/src/main/proto/indexing.proto`, `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcHealthService.java`                                                            |
| Head/UI readiness propagation                     | Implemented | `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java`, `modules/ui-web/src/stores/useSystemStore.ts`, `modules/ui-web/src/components/views/health/deriveHealthEvents.ts` |

### Test Evidence Confirmed

Targeted tests executed successfully on 2026-02-19:

1. `:modules:ipc-common:test --tests "*GrpcRetryServiceConfigTest" --tests "*GrpcCircuitBreakerTest"`
2. `:modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest"`
3. `:modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest"`
4. `:modules:app-services:test --tests "*WorkerStatusMapperTest"`

Command used:

```powershell
.\gradlew.bat :modules:ipc-common:test --tests "*GrpcRetryServiceConfigTest" --tests "*GrpcCircuitBreakerTest" :modules:app-search:test --tests "*GrpcAnnSearchClientTest" --tests "*GrpcEmbeddingClientTest" :modules:app-ai:test --tests "*GrpcAiTranslatorServiceTest" :modules:app-services:test --tests "*WorkerStatusMapperTest" -q
```

## Remaining Gaps and Uncertainties

### G1: Issue Registry Drift (Closed 2026-02-19; Residuals Tracked)

`BKD-009` and `BKD-010` were originally left open while their primary implementation landed.
RR-001 reconciles issue state to reflect implemented code, with residual hardening tracked explicitly in this tempdoc:
1. `BKD-009` residuals -> `RR-002` + `RR-003` + `RR-009` (now implemented; retained as governance hardening lineage).
2. `BKD-010` residuals -> `RR-005` (typed readiness semantics and cross-surface contract parity).

Result:
1. Issue registry and code baseline are aligned.
2. Remaining work is tracked as explicit forward-looking contracts, not stale root issue state.

### G2: Circuit-Breaker Primitive Consolidation (Closed 2026-02-19)

RR-002 implemented a single canonical breaker primitive in `ipc-common` with observer hooks.
App-services now keeps a compatibility adapter that delegates to the canonical primitive:
1. Canonical: `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker.java`
2. Adapter: `modules/app-services/src/main/java/io/justsearch/app/services/worker/GrpcCircuitBreaker.java`

Residual risk (low, tracked):
1. Adapter removal/migration timing (if/when direct adoption in app-services is desired).
2. Telemetry parity must remain covered by adapter tests during transition window.

### G3: Missing Production Soak Artifact (Partial)

RR-004 + RR-010 now provide schema/contracts, fixtures, comparability validator, and short-smoke calibration output.
However, a production-grade, recurring `grpc-retry-soak-report.v1.json` lane is not yet scheduled.

Risk:
1. Contract readiness is high, but long-horizon workload evidence is still limited.

### G4: Readiness Semantics Ambiguity

In worker health service, `ai_ready` is currently tied to `embeddingService.isAvailable()`.

Evidence:
`modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcHealthService.java` sets both `aiReady` and `embeddingReady` from embedding availability.

Risk:
1. `ai_ready` may not represent full AI path readiness in all configurations.
2. Could mislead UI/operator interpretation.
3. Missing tri-state semantics (`unknown` vs `not_ready`) can hide probe failures as absent fields.
4. Multiple readiness surfaces (`/api/health` lifecycle vs `/api/status` worker details) are useful but not formally reconciled by one contract today.

### G5: Retry Policy Surface Fragmentation (Closed 2026-02-19)

RR-003 replaced bespoke retry map assembly in `RemoteKnowledgeClient` with shared
`GrpcRetryServiceConfig` mixed-scope APIs and policy-profile governance.

Residual risk (low, tracked):
1. Policy ID drift must stay blocked by `validate-rpc-retry-ownership.mjs`.

## External Program/Library Fit (Research-Only, 2026-02-19)

### Method and Gates

Hard gates (must pass):
1. Local/self-hostable on Windows.
2. Compatible with loopback-only and local-first default.
3. No mandatory always-on cloud dependency.
4. Integrates with Java + gRPC stack without forcing architecture rewrite.

Scoring (100 total):
1. Reliability impact: 35
2. Integration cost/risk: 25
3. Local-first + Windows fit: 25
4. Operational visibility uplift: 15

Decision bands:
1. `Adopt`: score >= 80 and all hard gates pass.
2. `Pilot`: score 65-79 and all hard gates pass.
3. `Defer`: score < 65 or any hard gate fails.

### Candidate Matrix (Runtime Resilience Scope)

| Candidate                                                 | What It Solves for 219                                                             | Fit Score | Decision | Why                                                                                                             |
| --------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------: | -------- | --------------------------------------------------------------------------------------------------------------- |
| gRPC native retry/hedging + retry metrics                 | Standard retry/throttle semantics and attempt visibility                           |        88 | Adopt    | Already in-stack, idempotency-aware service-config model, avoids new runtime dependency                         |
| Toxiproxy (standalone proxy + HTTP API)                   | Deterministic fault injection for soak evidence (`UNAVAILABLE`, latency, timeouts) |        84 | Adopt    | Local/self-hosted, Windows-compatible binaries, directly supports RR-004 evidence                               |
| OpenTelemetry Collector + Jaeger (optional local profile) | Better resilience diagnosis for retry storms and latency amplification             |        72 | Pilot    | Strong observability value, but adds operational overhead vs current NDJSON local defaults                      |
| Resilience4j                                              | Unified external retry/circuit primitives                                          |        68 | Pilot    | Mature library set, but replacing current gRPC-native + internal breaker risks churn and policy overlap         |
| `grpcdebug` CLI                                           | On-host debugging of gRPC service/channel health                                   |        66 | Pilot    | Useful ops tool, but not a core runtime primitive                                                               |
| Testcontainers Toxiproxy module                           | Fault injection in containerized CI lanes                                          |        58 | Defer    | Requires Docker; less aligned with local-first Windows runs used for primary validation                         |
| Failsafe (Java resilience policies)                       | Alternative external retry/circuit abstraction                                     |        57 | Defer    | Overlaps with gRPC native retry + existing breaker; high risk of nested retry logic and ownership fragmentation |

### External-Fit Conclusions for 219

1. Keep gRPC-native retry/circuit architecture as the primary path; do not replace it with a second full resilience framework now.
2. Adopt Toxiproxy for RR-004 soak artifact generation, prioritizing standalone mode (no Docker requirement) for local/Windows parity.
3. Treat OTel Collector + Jaeger as an optional pilot profile for resilience investigations, not as a default runtime dependency.
4. Defer broad framework swaps (`Resilience4j`/`Failsafe`) after RR-002/RR-003 convergence unless new evidence shows the consolidated in-repo path is insufficient.
5. Guard against double-retry loops: never layer framework retries on top of gRPC retries without explicit disable/ownership rules.

### Long-Term Adoption Position (Critical Filter)

| Candidate                                   | Long-Term Position                     | Conditions                                                                                 |
| ------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------ |
| gRPC native retry policy + in-repo breakers | Keep as long-term default              | Only replace if evidence shows persistent failure modes that this stack cannot cover       |
| Toxiproxy                                   | Adopt long-term for resilience testing | Standardize runbook/artifact schema and confirm runner compatibility                       |
| OTel Collector + Jaeger                     | Keep as optional diagnostics profile   | Off by default; enable only for investigations/soak campaigns                              |
| Resilience4j                                | Not a long-term default now            | Reconsider only if policy unification cannot be achieved in-repo and measured gaps remain  |
| Failsafe                                    | Not a long-term default now            | Reconsider only under same trigger as Resilience4j; never with overlapping retry ownership |
| `grpcdebug` CLI                             | Utility tool only                      | Developer/operator debugging aid, not runtime dependency                                   |

Implication:
1. Long-term direction is consolidation and evidence-driven tuning of existing primitives, not framework replacement by default.

### Residual External Uncertainties (Now Tracked Artifacts)

1. Toxiproxy repeatability on existing runners without Docker remains open and is tracked by trigger `TR-001` in `runtime-resilience-external-fit-decision.v1.json`.
2. Optional Collector/Jaeger diagnostic value versus operational overhead remains open and is tracked by trigger `TR-003`.
3. Framework-replacement value (`Resilience4j`/`Failsafe`) versus canonical in-repo path remains open and is tracked by trigger `TR-002`.

## Research-First Priorities Before Implementation (Long-Term Focus)

### Priority Order

| Priority | Research Aspect                                                                   | Why This Is First                                                                         | Required Output                                                                                                            |
| -------- | --------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| P0       | Resilience SLO + failure budget model                                             | Without this, improvements are not objectively targetable                                 | `runtime-resilience-slo.v1.md` + `runtime-resilience-slo.v1.openslo.yaml` + `runtime-resilience-error-budget-policy.v1.md` |
| P1       | RPC idempotency and retry-ownership map (all methods)                             | Prevents unsafe retries and double-retry loops                                            | `rpc-retry-ownership-matrix.v1.json` + schema + descriptor-completeness tests + generated retry config parity report       |
| P2       | Breaker/retry calibration from observed workloads                                 | Current thresholds/backoff are code literals and may be arbitrary for desktop variability | `grpc-breaker-retry-calibration-report.v1.json` + `grpc-resilience-policy.v1.json` (profiled parameters)                   |
| P3       | Fault-model library and soak comparability profile                                | Needed for reproducible long-term evidence, not ad hoc chaos runs                         | `grpc-fault-scenarios.v1.json` + `grpc-retry-soak-report.v1.json` + comparability signature/gate contract                  |
| P4       | Readiness semantics contract (`ai_ready` vs `embedding_ready` vs degraded states) | Avoids operator/UI misinterpretation and false healthy states                             | `health-readiness-contract.v1.md` + validation matrix                                                                      |
| P5       | Lifecycle/maintenance cost model for external tools                               | Long-term ownership and velocity impact must be explicit                                  | `resilience-tooling-tco.v1.md` (ops burden, failure modes, upgrade cadence)                                                |
| P6       | Release governance integration                                                    | Ensures resilience evidence actually influences ship decisions                            | `release-resilience-gates.v1.json/.md` + `release-governance-decision.v1.json` + waiver/evidence contracts                 |

### Research Stop Conditions (Before Any Broad Rollout)

1. Do not adopt new runtime resilience libraries by default until P0-P3 artifacts are complete.
2. Do not mark resilience track complete until P4 and P6 are ratified.
3. Keep external tooling pilot-only unless ownership/maintenance model (P5) is accepted.

## Cross-Dive Unified Theory (Resilience Control Loop v1)

### Control-Loop Model

| Priority | Role in the Loop          | Primary Function                                                  |
| -------- | ------------------------- | ----------------------------------------------------------------- |
| P0       | Objective layer           | Defines resilience targets and error-budget policy                |
| P1       | Safety contract layer     | Defines method-level retry safety and ownership                   |
| P2       | Control policy layer      | Selects breaker/retry parameters by workload profile              |
| P3       | Evidence layer            | Produces comparable fault/soak evidence for policy validation     |
| P4       | State semantics layer     | Defines readiness meaning and cross-surface health interpretation |
| P5       | Economics/lifecycle layer | Constrains adoption choices via TCO and lifecycle governance      |
| P6       | Decision layer            | Converts artifacts/policies into release `allow                   | hold | block` decisions |

### Global Invariants (Must Hold Across P0-P6)

1. No retry enablement without explicit idempotency ownership (`P1`).
2. No regression judgment without comparability match and reason codes (`P3`/`P6`).
3. No readiness claim without typed state and source ownership (`P4`).
4. No breaker/retry policy promotion without calibration evidence (`P2` + `P3`).
5. No external tooling promotion without fallback path, owner, and TCO budget (`P5`).
6. No release decision without policy version, evidence index, and waiver handling (`P6`).

### Shared Artifact Backbone (All P0-P6 Outputs)

| Field           | Purpose                                      |
| --------------- | -------------------------------------------- |
| `kind`          | Artifact family identity                     |
| `schemaVersion` | Backward compatibility and parsing safety    |
| `policyVersion` | Policy provenance for decision replay        |
| `generatedAt`   | Temporal traceability                        |
| `gitSha`        | Source-state binding                         |
| `candidateId`   | Release-candidate identity                   |
| `runId`         | Run-level correlation across artifacts       |
| `comparability` | Explicit comparable/non-comparable semantics |
| `evidenceRefs`  | Traceable linkage to logs/tests/manifests    |

### P0-P6 Dependency Graph

1. `P0 -> (P1, P4)` to define objective boundaries and state semantics.
2. `P1 -> P2` so control policies inherit retry/idempotency ownership constraints.
3. `P2 + P3 -> policy fitness` via comparable workload/fault evidence.
4. `P5 -> P6` so lifecycle/TCO constraints participate in release decisioning.
5. `P0-P5 -> P6` where release verdicts are deterministic and recomputable.

### Integration Contract Matrix (Role/Consumes/Produces)

| Priority | Role in Loop    | Consumes                                                        | Produces                                                         |
| -------- | --------------- | --------------------------------------------------------------- | ---------------------------------------------------------------- |
| P0       | Objective       | Existing telemetry/lifecycle signals + service SLO intent       | SLO spec + error-budget policy + burn-rate thresholds            |
| P1       | Safety contract | Proto descriptors + client retry wiring + idempotency semantics | Method-level ownership matrix + generated retry config contract  |
| P2       | Control policy  | P1 ownership matrix + observed workload telemetry               | Versioned breaker/retry policy profiles + calibration report     |
| P3       | Evidence        | P2 policy candidates + fault scenario profiles                  | Comparable soak report + decision-ready process metrics          |
| P4       | State semantics | Worker/head health sources + UI/API status contracts            | Typed readiness contract + parity matrix                         |
| P5       | Economics       | External-fit candidates + ops constraints + upgrade signals     | Lifecycle/TCO register + promote/freeze/retire triggers          |
| P6       | Decision        | P0-P5 artifacts + waiver register + policy version              | Release governance decision artifact + action and rollback state |

## P0 Deep Dive: Resilience SLO + Failure Budget Model (Investigated Target State)

### Design Inputs from Research

1. SLOs should be user-journey oriented and defined as explicit SLI targets, not generic infrastructure KPIs.
2. Prefer ratio-style SLIs (`good_events / total_events`) so error budgets and burn rates are consistent across indicators.
3. Use a rolling multi-week window and formal error-budget policy for release decisioning.
4. Burn-rate alerting should use multiwindow, multi-burn-rate rules with a short confirmation window (about `1/12` of long window) to improve reset behavior.
5. Low-traffic services require either synthetic signal, grouped indicators, or traffic-aware guardrails to avoid noisy false pages.

### Recommended Long-Term Structure (What "Good" Looks Like)

| Layer           | Long-Term State                                                                                                        | Why                                                                  |
| --------------- | ---------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Contract        | Two artifacts: `runtime-resilience-slo.v1.md` (human policy) + `runtime-resilience-slo.v1.openslo.yaml` (machine spec) | Keeps governance auditable and automation-friendly                   |
| Indicator model | Split every SLI into `specification` (user outcome) and `implementation` (metric/query)                                | Prevents metric drift from silently changing objective meaning       |
| Budgeting model | Hybrid: `Occurrences` for request/path success SLIs, `Timeslices` for lifecycle-state continuity SLIs                  | Fits both request-driven and state-driven resilience concerns        |
| Windowing       | Rolling `4w` (28d) default for runtime resilience SLOs                                                                 | Preserves weekday/weekend symmetry and aligns with workbook examples |
| Alerting        | Formula-driven burn rates + multiwindow rules (long + short windows)                                                   | Strong precision/recall without stale long-window paging             |
| Policy          | Error-budget policy with freeze/escalation/exemption rules, reviewed monthly                                           | Gives SLOs operational "teeth" instead of report-only status         |
| Governance      | Quarterly ratchet review with explicit loosen/tighten criteria and change log                                          | Prevents stale SLOs and accidental over/under-tightening             |

### Proposed Runtime Resilience SLO Families (v1)

| SLO ID    | User Journey                                                  | SLI Type             | Initial Measurement Source                                        |
| --------- | ------------------------------------------------------------- | -------------------- | ----------------------------------------------------------------- |
| `RRS-001` | Local API lifecycle available for user work                   | Occurrence ratio     | `/api/health` lifecycle state + HTTP status semantics             |
| `RRS-002` | Worker control-plane resilient under transient failures       | Timeslice ratio      | `components.worker.state`, reconnect/restart telemetry (`ipc.*`)  |
| `RRS-003` | Core request paths succeed without resilience-induced failure | Occurrence ratio     | `api.request_ms` by route/status_class + API error codes          |
| `RRS-004` | Recovery after transient worker/channel failures is timely    | Threshold/timeslice  | `ipc.grpc.reconnect`, restart outcomes, readiness transitions     |
| `RRS-005` | Migration/write durability path remains safe under stress     | Occurrence ratio     | `worker.switch_buffer.write_failures`, queue/switch status fields |
| `RRS-006` | Resilience observability itself remains trustworthy           | Occurrence/timeslice | `/api/telemetry/health` (`READY                                   | DEGRADED | ERROR`) |

### Burn-Rate Model (Formula-First, Window-Aware)

1. `burn_rate = observed_error_rate / allowed_error_rate`.
2. `allowed_error_rate = 1 - SLO_target`.
3. Burn thresholds must be derived from:
   - `burn_rate = budget_fraction_to_consume * slo_window_hours / alert_window_hours`
4. For a `4w` window (`672h`), starting points equivalent to workbook guidance are approximately:
   - Page: `2%` in `1h` -> `13.44x`
   - Page: `5%` in `6h` -> `5.6x`
   - Ticket: `10%` in `3d` -> `0.93x`
5. Use paired windows (`long` and `short ~= long/12`) before firing to reduce false positives and stale alerts.

### Error-Budget Policy Shape (Long-Term)

1. If any critical runtime resilience SLO exhausts budget, freeze non-critical resilience-affecting changes until recovery criteria are met.
2. If one incident burns `>20%` of the window budget, require postmortem + mandatory reliability action item.
3. Allow explicit exemptions (for example, upstream dependency or out-of-scope synthetic traffic) only with written decision records.
4. Tie release gates to budget state (`green/amber/red`) rather than binary pass/fail only.

### Gaps to Resolve Before Implementing P0

1. Canonical mapping from existing metrics to each SLI numerator/denominator query (some are implied but not locked).
2. Startup/warmup exclusion rules (to avoid budget burn from expected boot transitions).
3. Low-traffic handling strategy for desktop/offline patterns (synthetic probes vs grouped objectives).
4. Ownership of budget freeze decisions and escalation path when policy conflicts occur.

## P1 Deep Dive: RPC Idempotency + Retry Ownership Map (Investigated Target State)

### Current Structure (Code-Validated)

1. Idempotency hints exist in `indexing.proto` comments for `IngestService` methods, but are not yet machine-enforced as a contract artifact.
2. Retry ownership is currently split across client implementations:
   - `RemoteKnowledgeClient` uses a bespoke per-method retry builder for `IngestService` plus service-level retry for `SearchService` and `HealthService`.
   - `GrpcAnnSearchClient`, `GrpcEmbeddingClient`, and `GrpcAiTranslatorService` use shared `GrpcRetryServiceConfig` service-level retry for AI/ANN services.
3. Coverage is partial by construction today: `IngestService` exposes 19 RPC methods, while `RemoteKnowledgeClient` currently applies transport retry to 5 method-level ingest RPCs.
4. There is no descriptor-complete map proving every RPC method has an explicit idempotency class and retry owner.
5. Existing tests validate selected allow/deny cases, but do not fail when new RPC methods are added without retry/idempotency classification.
6. Current doc/proto signal is partially inconsistent for some mutation methods (for example, delete operations are described as retry-safe in proto comments but are not in the current retry allowlist).

### Descriptor Inventory Baseline (P1 Scope)

Current method inventory across active gRPC surfaces in this track:
1. `io.justsearch.ipc.SearchService`: 8 methods.
2. `io.justsearch.ipc.IngestService`: 19 methods.
3. `io.justsearch.ipc.HealthService` (indexing proto): 1 method.
4. `io.justsearch.ipc.v1.AiService`: 3 methods.
5. `io.justsearch.ipc.v1.AnnService`: 1 method.
6. `io.justsearch.ipc.v1.HealthService` (AI worker proto): 3 methods.

P1 descriptor-complete target baseline: **35 methods mapped, owned, and test-verified**.

### Recommended Long-Term Structure (Best State)

| Layer              | Long-Term State                                               | Why                                                      |
| ------------------ | ------------------------------------------------------------- | -------------------------------------------------------- |
| Canonical artifact | `rpc-retry-ownership-matrix.v1.json` (one row per RPC method) | Makes policy explicit and machine-checkable              |
| Schema             | `rpc-retry-ownership-matrix.v1.schema.json`                   | Prevents ad hoc fields and classification drift          |
| Policy doc         | `rpc-retry-ownership-policy.v1.md`                            | Captures intent, escalation rules, and exception process |
| Build output       | Generated retry service-config snapshots from matrix          | Eliminates hand-maintained retry allowlists              |
| Verification       | Descriptor-completeness + parity tests                        | Guarantees every RPC is classified and wired as intended |

### Proposed Matrix Row Model (v1)

Required fields per RPC method:
1. `surfaceId` (`head-worker`, `head-ai-worker`, `search-ai-worker`)
2. `protoFile`, `service`, `method`, `methodFqn`
3. `operationClass` (`READ`, `WRITE`, `CONTROL`, `DESTRUCTIVE`)
4. `idempotencyClass`:
   - `STRICT` (re-execution yields equivalent state and response class)
   - `EFFECTIVE_RETRY_SAFE` (not strictly identical but safe under replay)
   - `CONDITIONAL` (safe only with idempotency key/dedupe contract)
   - `NON_IDEMPOTENT` (replay can cause invalid side effects)
5. `transportRetryOwner` (`grpc_service_config` or `none`)
6. `callerRetryOwner` (`none` or explicit component)
7. `circuitBreakerOwner` (component + primitive)
8. `deadlineCategory`
9. `retryPolicyId` (references backoff/attempt policy)
10. `evidenceRefs` (tests/contracts proving behavior)

### Matrix Schema Contract (v1 Draft)

| Field                   | Type        | Rule                                                              |
| ----------------------- | ----------- | ----------------------------------------------------------------- |
| `methodFqn`             | string      | Unique key (`package.service/method`)                             |
| `surfaceId`             | enum        | `head-worker`, `head-ai-worker`, `search-ai-worker`               |
| `operationClass`        | enum        | `READ`, `WRITE`, `CONTROL`, `DESTRUCTIVE`                         |
| `idempotencyClass`      | enum        | `STRICT`, `EFFECTIVE_RETRY_SAFE`, `CONDITIONAL`, `NON_IDEMPOTENT` |
| `transportRetryOwner`   | enum        | `grpc_service_config`, `none`                                     |
| `callerRetryOwner`      | enum        | `none`, `client_wrapper`, `workflow_layer`                        |
| `circuitBreakerOwner`   | string      | Must point to concrete class/primitive owner                      |
| `deadlineCategory`      | string      | Must match runtime category IDs used by caller                    |
| `retryPolicyId`         | string/null | Required when `transportRetryOwner=grpc_service_config`           |
| `idempotencyKeySupport` | enum        | `none`, `required`, `optional`                                    |
| `stateSafetyNotes`      | string      | Short bounded note for replay behavior                            |
| `evidenceRefs`          | string[]    | At least one contract/unit/integration evidence pointer           |

Schema-level hard checks:
1. No unknown enum values.
2. `retryPolicyId` forbidden when `transportRetryOwner=none`.
3. `idempotencyKeySupport!=none` only allowed for `CONDITIONAL`.
4. Every `methodFqn` appears once (uniqueness).

### Invariants for the Long-Term State

1. **Descriptor completeness:** every method in service descriptors must appear exactly once in the matrix.
2. **Single retry owner:** transport and caller retries cannot both own retries unless explicitly allowlisted for a bounded staged pattern.
3. **Safety gate:** `transportRetryOwner=grpc_service_config` is valid only for `STRICT` or `EFFECTIVE_RETRY_SAFE` methods.
4. **Drift gate:** generated retry config from matrix must equal runtime-applied config (parity test).
5. **Change gate:** adding/changing proto methods requires matrix update + classification review in same change.
6. **Evidence gate:** each matrix row must reference at least one test/contract artifact proving the declared class/ownership.
7. **No silent promotion:** moving a method from `NON_IDEMPOTENT` to retryable classes requires explicit decision record.

### Best Long-Term State (L5 Definition)

1. The matrix is descriptor-complete for all active RPC surfaces and versioned.
2. Retry config is generated from the matrix (not handwritten per client) and parity-tested in CI.
3. Idempotency classification is part of API evolution: proto method additions fail CI until classified.
4. Conditional retry methods use explicit idempotency-key semantics (request identity + dedupe behavior) before retry enablement.
5. Ownership is singular and auditable:
   - transport retry owner,
   - caller retry owner,
   - breaker owner,
   - policy owner.
6. Release gates consume matrix + parity + replay evidence as required artifacts.

### Enforcement Pipeline (Target)

1. `descriptor_snapshot_test`: extracts method list from generated descriptors and validates matrix completeness.
2. `matrix_schema_lint_test`: validates structure and enum correctness.
3. `retry_ownership_conflict_test`: fails on overlapping retry owners unless allowlisted.
4. `retry_config_parity_test`: compares generated config vs runtime client config.
5. `classification_evidence_test`: ensures each row references existing evidence artifacts.
6. `non_idempotent_retry_guard_test`: asserts non-idempotent methods are excluded from transport retry.

### Family-Level Target Classification (Current Best Direction)

| RPC Family                                                                                                                                 | Target Class             | Long-Term Retry Ownership                                                   |
| ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------ | --------------------------------------------------------------------------- |
| Search/ANN/AI read methods                                                                                                                 | `STRICT`                 | gRPC transport retry + shared circuit breaker                               |
| Health/readiness methods                                                                                                                   | `STRICT`                 | gRPC transport retry + shared circuit breaker                               |
| Retry-safe ingest reconciliations (`SyncDirectory`, `PruneMissing`, selected deletes)                                                      | `EFFECTIVE_RETRY_SAFE`   | gRPC transport retry once explicitly ratified in matrix                     |
| Queue/state mutation methods (`SubmitBatch`, `MarkVduProcessing`, `UpdateVduResult`, `ClearFailedJobs`, migration controls, path rewrites) | `NON_IDEMPOTENT` (today) | no automatic retry; breaker only                                            |
| Future promoted mutation methods                                                                                                           | `CONDITIONAL`            | retry allowed only after idempotency key + dedupe semantics are implemented |

Classification nuance:
1. `STRICT` is reserved for operations that are replay-safe and semantically stable under retry.
2. `EFFECTIVE_RETRY_SAFE` is allowed when replay may not produce identical payloads but does not violate state safety.
3. `CONDITIONAL` requires request identity semantics and documented duplicate suppression behavior.

### Migration Path to the Best Long-Term State

1. Build matrix + schema from current descriptors and comments.
2. Add completeness/parity tests before changing retry policies.
3. Ratify disputed methods (notably retry-safe mutations) through focused failure-replay tests.
4. Introduce optional idempotency-key fields for selected mutation RPCs if they need retryability.
5. Generate retry config from matrix for all clients to remove hand-coded divergence.
6. Promote matrix-based ownership checks into required pre-merge gates.

### Gaps to Resolve Before Implementing P1

1. Resolve classification disagreements between proto comments, docs, and current retry allowlists.
2. Define exact criteria for `EFFECTIVE_RETRY_SAFE` vs `NON_IDEMPOTENT` in this codebase.
3. Decide whether migration control RPCs should remain strictly non-retryable or move to conditional idempotency-key mode.
4. Choose rollout order for matrix-driven config generation across `RemoteKnowledgeClient`, `GrpcAnnSearchClient`, `GrpcEmbeddingClient`, and `GrpcAiTranslatorService`.

## P2 Deep Dive: Breaker/Retry Calibration from Observed Workloads (Investigated Target State)

### Current Structure (Code-Validated)

1. Shared gRPC retry defaults (`GrpcRetryServiceConfig`) are fixed literals:
   - `maxRetries=2` (`maxAttempts=3`)
   - `initialBackoff=100ms`, `maxBackoff=1000ms`, `backoffMultiplier=2.0`
   - `retryableStatusCodes=["UNAVAILABLE"]`
2. `RemoteKnowledgeClient` uses a bespoke retry config builder with a different backoff cap (`maxBackoff=2000ms`) and per-method allowlist for 5 `IngestService` RPCs.
3. Both breaker implementations currently use fixed literals (`failureThreshold=3`, `cooldown=10s`) and consecutive-failure logic.
4. Retry and breaker semantics are not fully symmetric:
   - transport retry targets `UNAVAILABLE`
   - breaker failure accounting includes `UNAVAILABLE` and `DEADLINE_EXCEEDED`
5. Telemetry coverage is uneven:
   - app-services breaker emits `ipc.circuit_breaker.state_change` and `ipc.circuit_breaker.rejected`
   - shared `ipc-common` breaker used by ANN/embedding/translator clients has no equivalent built-in transition/rejection telemetry surface today
6. No versioned resilience policy artifact currently owns these parameters; behavior is defined by code constants.

### Observed Workload Evidence Available Today

1. No committed calibration artifact currently exists (`grpc-breaker-calibration-report.v1.json` / `grpc-breaker-retry-calibration-report.v1.json` absent).
2. No committed retry soak artifact currently exists (`grpc-retry-soak-report.v1.json` absent).
3. Existing telemetry sample at `reports/phase6a/telemetry/search/telemetry/metrics.ndjson` does not include breaker/retry calibration metrics (for example `ipc.circuit_breaker.*` or retry-attempt counters).
4. Result: current retry/breaker constants are validated by tests for correctness and guardrails, but not yet calibrated by observed workload distributions.

### External Guidance Synthesis (Primary Sources)

1. gRPC retries are policy-driven (no explicit retry policy means no policy retries), with transparent retry behavior only in narrow transport races.
2. gRPC retry policy includes backoff and retry throttling controls (`maxTokens`, `tokenRatio`) and applies jitter to retry delay.
3. gRPC OpenTelemetry metrics expose per-attempt visibility (`grpc.client.call.attempt.started`, `grpc.client.attempt.duration`) needed to measure retry amplification.
4. AWS guidance emphasizes:
   - retries are "selfish" under overload,
   - retries should be owned at a single layer,
   - capped exponential backoff with jitter is preferred,
   - stacked retries across layers can multiply load dramatically.
5. Resilience4j breaker guidance reinforces workload-based tuning using minimum call volume + sliding windows + failure/slow-call rates (useful target model even if we keep in-repo primitives).

### Recommended Long-Term Structure (Best State)

| Layer                | Long-Term State                                                                                                                   | Why                                                 |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------- |
| Policy contract      | `grpc-resilience-policy.v1.json` with profile-based breaker/retry parameters and explicit policy IDs                              | Removes hidden literals and enables governed change |
| Workload taxonomy    | Stable profiles (`interactive-read`, `indexing-burst`, `restart-recovery`, `fault-injected`)                                      | Prevents one-size-fits-all tuning mistakes          |
| Telemetry contract   | Attempt-level + breaker metrics captured for all gRPC clients (including shared-breaker users)                                    | Makes calibration evidence possible and comparable  |
| Calibration artifact | `grpc-breaker-retry-calibration-report.v1.json` with candidate policies, objective scores, selected policy, and rejection reasons | Makes parameter selection auditable                 |
| Runtime wiring       | Generated retry service-config + breaker parameter mapping from policy IDs (linked to P1 ownership matrix)                        | Aligns retry ownership and parameter ownership      |
| Governance           | Release gate requires calibration artifact freshness and comparability vs baseline policy                                         | Prevents silent drift and regression by intuition   |

### Calibration Model Shape (v1)

Candidate policy tuple per profile:
1. `retry`: `maxAttempts`, `initialBackoffMs`, `maxBackoffMs`, `backoffMultiplier`, retryable status set, optional throttling controls.
2. `breaker`: `failureThreshold`, `cooldownMs`, optional future window/rate controls when call volume supports it.
3. `ownership`: retry owner and breaker owner references (must match P1 matrix).

Objective vector per candidate:
1. Availability impact (`success_rate`, `retry_exhausted_rate`).
2. User latency impact (`p50/p95/p99` and latency amplification vs baseline).
3. Load amplification (`attempts_per_call`, retry burst characteristics).
4. Breaker stability (`open_rate`, `half_open_reopen_rate`, rejection share, recovery time).

Hard constraints:
1. No overlapping retry ownership layers unless explicitly allowlisted.
2. No policy that increases critical-path failure rate.
3. No policy that exceeds agreed latency amplification budget for interactive paths.
4. No policy that degrades recovery behavior under restart/fault scenarios.

### Best Long-Term State (L5 Definition)

1. Breaker/retry parameters are profile-driven and versioned (no hidden code literals as primary control plane).
2. All active gRPC surfaces emit calibration-grade attempt and breaker telemetry.
3. Parameter changes are accepted only through calibration artifacts with scenario comparability.
4. Retry/breaker policy versions are traceable in run artifacts and release gates.
5. Fallback to conservative local defaults remains available for offline/local-first operation if telemetry overlays are disabled.

### Gaps to Resolve Before Implementing P2

1. Decide whether to unify the two breaker implementations or define explicit dual-primitive ownership boundaries.
2. Define a cross-client telemetry contract for retry attempts and breaker transitions/rejections.
3. Decide whether selected read-only paths should include `DEADLINE_EXCEEDED` in transport retry policy (currently breaker-only transient class).
4. Ratify workload profile set and comparability rules for calibration runs.
5. Lock schema for `grpc-breaker-retry-calibration-report.v1.json` and attach it to release-gate rules (RR-011).

## P3 Deep Dive: Fault-Model Library + Soak Comparability Schema (Investigated Target State)

### Current Structure (Code-Validated)

1. Fault injection today is code-embedded in JUnit test logic in `ChaosSuiteTest`, not artifact-driven.
2. Long-run behavior is encoded in `SoakSuiteTest` and `SoakTestRunner`, not artifact-driven.
3. `SoakTestRunner` returns in-memory `SoakResult` records with summaries/logs, but there is no versioned resilience soak artifact schema emitted by default.
4. A resilient comparability pattern already exists in other lanes: agent live scorecard uses scenario-profile signatures and explicit skip reasons for non-comparable runs.
5. A resilient comparability pattern already exists in other lanes: bench/perf diff stack already supports policy-versioned gate decisions with explicit non-comparable semantics.
6. There is no dedicated `grpc-fault-scenarios.v1.json` library or `grpc-retry-soak-report.v1.json` report contract in the repo yet.

### Structural Gap (Why P3 Is Still Open)

1. Scenario identity is not machine-locked, so two "similar" soak runs are not provably comparable.
2. Fault-injection configuration is not represented as a stable profile/signature, so policy calibration can drift silently.
3. Run outputs are not normalized into release-gate-ready artifacts; evidence remains test-log-centric.
4. Existing comparability/governance patterns are not reused for resilience soak evidence yet.

### Recommended Long-Term Structure (Best State)

| Layer                      | Long-Term State                                                                                                    | Why                                                                      |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------ |
| Fault library contract     | `grpc-fault-scenarios.v1.json` (versioned scenario library)                                                        | Moves from ad hoc test code to declarative, diffable scenario governance |
| Injection backend contract | Explicit backend identity/version in each run (`toxiproxy-standalone`, etc.)                                       | Prevents cross-backend apples-to-oranges comparisons                     |
| Workload contract          | Named workload profiles (`interactive-read`, `restart-recovery`, `fault-injected`) with fixed traffic shape fields | Keeps retry/breaker calibration tied to known traffic patterns           |
| Soak report contract       | `grpc-retry-soak-report.v1.json` with per-scenario + aggregate process metrics                                     | Produces machine-checkable evidence for gates                            |
| Comparability contract     | Signature + hard key matching + explicit non-comparable reason codes                                               | Removes ambiguous "close enough" comparisons                             |
| Gate contract              | Policy-versioned decision block (`pass                                                                             | warn                                                                     | fail`) with optional `fail_on_non_comparable` | Aligns resilience evidence with existing bench/scorecard governance |

### Proposed `grpc-fault-scenarios.v1.json` Shape (v1)

Top-level:
1. `kind: "grpc-fault-scenarios.v1"`
2. `schemaVersion`
3. `profileId`
4. `policyVersion`
5. `injectionBackend` (`id`, `version`)
6. `workloadProfile` (traffic defaults and timeout budget)
7. `scenarios[]`

Per-scenario fields:
1. `id`, `description`, `tags`
2. `target` (`service`, `method`, `surfaceId`)
3. `fault` (`type`, parameter payload such as latency/jitter/error-rate/reset mode)
4. `traffic` (`durationSec`, `concurrency`, `requests`/`qps`)
5. `expectedEnvelope` (`maxErrorRate`, `maxLatencyAmplificationP95`, `maxAttemptsPerCall`, `maxBreakerRejectRate`)
6. `comparabilityKeyOverrides` (optional explicit keys for exceptional scenarios)

### Proposed `grpc-retry-soak-report.v1.json` Shape (v1)

Top-level:
1. `kind: "grpc-retry-soak-report.v1"`
2. `generatedAt`, `runId`
3. `scenarioProfile` (`profileId`, `signature`, `scenarioCount`, `scenarioIds`)
4. `policyRefs` (`retryOwnershipMatrixVersion`, `resiliencePolicyVersion`, `gatePolicyVersion`)
5. `environment` (`gitSha`, machine fingerprint, runtime versions, backend identity)
6. `comparability` (`comparable`, `nonComparableReasons[]`, `failOnNonComparable`)
7. `aggregateMetrics`
8. `scenarioResults[]`
9. `decision` (`gateStatus`, `regressions[]`, `thresholdBreaches[]`)

Aggregate/scenario metric minimums:
1. `successRate`
2. `retryAttemptsPerCall` (`mean`, `p95`)
3. `retryExhaustedRate`
4. `latencyAmplification` (`p95`, `p99`) vs control profile
5. `breakerOpenRate`, `breakerRejectedRate`, `halfOpenReopenRate`
6. `timeToRecoveryMs`
7. `infraFailure` / teardown failure signals

### Soak Comparability Contract (v1)

Hard-match keys (otherwise non-comparable):
1. `scenarioProfile.signature`
2. `injectionBackend.id` + `injectionBackend.version`
3. `workloadProfile.id`
4. `retryOwnershipMatrixVersion`
5. `resiliencePolicyVersion`

Soft-match keys (warn/annotate, not immediate non-comparable):
1. Machine class and CPU/RAM fingerprint drift
2. Optional diagnostics knobs (for example trace overlays)

Non-comparable reason code set:
1. `SCENARIO_SIGNATURE_MISMATCH`
2. `INJECTION_BACKEND_MISMATCH`
3. `WORKLOAD_PROFILE_MISMATCH`
4. `RETRY_OWNERSHIP_VERSION_MISMATCH`
5. `RESILIENCE_POLICY_VERSION_MISMATCH`

### Best Long-Term State (L5 Definition)

1. Fault scenarios are governed as versioned artifacts, not implicit Java test code only.
2. Every soak run emits schema-validated resilience reports with profile signatures and policy refs.
3. Comparability is deterministic and enforced by gate tooling with explicit non-comparable reasons.
4. Calibration/promotion decisions consume resilience artifacts directly, not manual log interpretation.
5. Local-first execution remains default, with optional diagnostics overlays recorded as comparability-affecting knobs.

### Gaps to Resolve Before Implementing P3

1. Decide initial injector backend set for v1 (`toxiproxy-standalone` only vs mixed backends).
2. Define canonical mapping from current chaos/soak test cases into scenario-library IDs without coverage loss.
3. Lock metric extraction path for retry/breaker attempt-level counters across all gRPC clients.
4. Choose report emission path (dedicated resilience script vs Gradle/system-test post-processor) and ownership.
5. Ratify strict vs report-only non-comparable gate mode for initial rollout.

## P4 Deep Dive: Health/Readiness Semantics Contract (Investigated Target State)

### Current Structure (Code-Validated)

1. Worker gRPC health (`indexing.proto` `HealthCheckResponse`) exposes:
   - `serving`
   - `worker_state` (`RUNNING|PAUSED|IDLE`)
   - `ai_ready`
   - `embedding_ready`
2. Worker implementation currently sets both `ai_ready` and `embedding_ready` from `embeddingService.isAvailable()` in `GrpcHealthService`.
3. Head lifecycle gate (`/api/health`) uses `LifecycleSnapshotV1` and is intentionally coarse:
   - returns `200` for `READY|DEGRADED`
   - treats inference as optional (offline inference can still be overall `DEGRADED` and HTTP `200`)
4. Rich status (`/api/status`) merges lifecycle + worker status and may include additive `aiReady` / `embeddingReady` when the worker health RPC succeeds.
5. UI health logic consumes `aiReady` and `embeddingReady` as optional booleans and emits warnings only on explicit `false`.
6. If worker health details are unavailable at status mapping time, those fields may be absent rather than typed as `unknown`.

### Structural Gap (Why P4 Is Still Open)

1. `ai_ready` naming implies broad AI readiness, but current implementation is an embedding-availability alias.
2. Readiness values are boolean-only in the worker contract, so they cannot represent `UNKNOWN`, `NOT_CONFIGURED`, or `DEGRADED`.
3. Source boundaries are not explicit:
   - worker-side readiness (index/search/embedding paths)
   - head-side inference readiness (online AI service)
4. `/api/health` and `/api/status` each have valid semantics, but no single contract defines how they must agree and diverge.
5. UI/operator interpretation depends on optional-field behavior, which can obscure missing-probe vs actual-false states.

### Recommended Long-Term Structure (Best State)

| Layer                     | Long-Term State                                                                            | Why                                                                     |
| ------------------------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------- |
| Lifecycle gate contract   | Keep `/api/health` as coarse release/automation gate (`READY                               | DEGRADED                                                                | ERROR` + HTTP mapping) | Preserves stable gate semantics and low-cardinality automation |
| Readiness detail contract | Add a typed readiness envelope under `/api/status` (and debug surfaces)                    | Provides operator-grade detail without weakening lifecycle gate clarity |
| Source separation         | Explicitly split worker readiness vs head inference readiness                              | Eliminates semantic overload of `ai_ready`                              |
| Component criticality     | Each readiness component declares `criticalForLifecycle` and `criticalForFeature`          | Prevents optional subsystems from incorrectly blocking global readiness |
| State model               | Replace boolean-only readiness with enum states + reason codes + probe metadata            | Distinguishes true failures from unknown/stale data                     |
| Compatibility policy      | Keep existing `aiReady`/`embeddingReady` as additive legacy fields during migration window | Avoids client breakage while contract hardens                           |

### Proposed Readiness State Model (v1)

State enum (for each readiness component):
1. `READY`
2. `DEGRADED`
3. `NOT_READY`
4. `NOT_CONFIGURED`
5. `UNKNOWN`

Required per-component fields:
1. `state`
2. `reasonCode` (stable taxonomy; no dynamic text)
3. `source` (`worker_health_rpc`, `head_online_ai`, `derived_composite`, etc.)
4. `observedAt`
5. `stale` (boolean based on probe TTL policy)
6. `criticality` (`lifecycle`, `feature`, or `none`)

### Proposed Readiness Envelope Shape (Status Surface)

Top-level additive status block:
1. `readiness.schemaVersion`
2. `readiness.components.worker_core`
3. `readiness.components.worker_embedding`
4. `readiness.components.head_inference`
5. `readiness.composites.search_path`
6. `readiness.composites.agent_path`

Compatibility notes:
1. Keep legacy `aiReady`/`embeddingReady` booleans for migration.
2. Define deterministic mapping from new enum states to legacy booleans:
   - `READY -> true`
   - `NOT_READY|DEGRADED|UNKNOWN|NOT_CONFIGURED -> false` (or omitted only when contract version absent)
3. Publish explicit deprecation window and removal gate for legacy booleans.

### Cross-Surface Contract Rules (Best Long-Term)

1. `/api/health` remains lifecycle-only; it must not require optional feature readiness for HTTP `200`.
2. `/api/status.readiness` must include source-specific detail and probe timestamps.
3. Worker health (`HealthCheckResponse`) must map one-to-one into the worker subsection of status readiness (no silent reinterpretation).
4. Head inference readiness must come from head-owned sources (`OnlineAiService` / inference status), not worker aliases.
5. Any missing probe result must surface as typed `UNKNOWN`, not silent field absence.

### Best Long-Term State (L5 Definition)

1. Readiness semantics are source-explicit, typed, and versioned across all health surfaces.
2. Operator-facing status can distinguish `not_ready`, `unknown`, `not_configured`, and `degraded`.
3. Lifecycle gates remain stable and intentionally coarse, while rich readiness details stay in `/api/status`.
4. UI warnings are driven by typed readiness + reason codes, eliminating ambiguity from optional booleans.
5. Contract tests enforce parity between worker health, lifecycle snapshot, status readiness envelope, and UI ingestion schema.

### Gaps to Resolve Before Implementing P4

1. Decide canonical ownership for `ai_ready` semantics (rename/remap vs keep as strict alias with explicit docs).
2. Define readiness reason-code taxonomy extension and allowlist updates.
3. Lock probe TTL/staleness policy and `UNKNOWN` transition rules.
4. Decide migration timeline for legacy boolean fields in `/api/status`.
5. Add parity/contract test matrix for:
   - worker RPC health success/failure
   - head inference offline/starting/ready
   - partial probe loss (typed `UNKNOWN`)
   - lifecycle/status agreement invariants

## P5 Deep Dive: External Tooling Lifecycle/TCO Model (Investigated Target State)

### Current Structure (Code-Validated + Research-Validated)

1. External-fit scoring exists (`Adopt/Pilot/Defer`) with strong local-first gates, but lifecycle ownership and long-term cost accounting are not yet formalized.
2. Current repo baseline already has low-ops local observability primitives (`NdjsonMetricExporter`, `NdjsonSpanExporter`, `RrdMetricStore`, `/api/telemetry/health`) and does not require a collector by default.
3. External tooling in scope is heterogeneous in lifecycle profile:
   - `Toxiproxy`: MIT-licensed, local binaries and container paths, latest tagged release `v2.5.0` (2025-09-10).
   - `grpcdebug`: Apache-2.0, active 2026 releases (`v1.0.5` at 2026-02-17), CLI-only footprint.
   - `Jaeger`: v1 reached EOL on 2025-12-31; v2 active with latest `2.15.0` on download page.
   - `OpenTelemetry Collector`: explicit release process with short release cadence (2-week cycles); status page marks collector as mixed stability.
   - `Resilience4j`: active releases (`v2.3.0` on 2026-01-03), but introduces runtime/platform coupling considerations (for example v3 requiring Java 21).
   - `Failsafe`: Apache-2.0 and lightweight, but no GitHub Releases page entries and latest visible tags are older (`failsafe-parent-3.3.2`, 2023-06-24).
4. Testcontainers-based Toxiproxy path imposes a container runtime dependency (Docker API compatible runtime), which conflicts with primary local-first Windows runner assumptions for default validation.

### Structural Gap (Why P5 Is Still Open)

1. Decisioning currently answers "should we adopt?" but not "what is the 12-24 month ownership cost and retirement trigger?"
2. No standardized external-tool lifecycle states are tied to release governance.
3. No TCO ledger exists that captures engineering toil, incident overhead, upgrade churn, and runner/environment drift.
4. No explicit policy defines when a pilot must be promoted, paused, or removed.

### Recommended Long-Term Structure (Best State)

| Layer                   | Long-Term State                                                                                     | Why                                              |
| ----------------------- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Lifecycle state machine | `evaluate -> pilot -> adopt -> sustain -> retire` with explicit entry/exit gates                    | Prevents pilot drift and zombie dependencies     |
| Ownership contract      | Every external tool has a primary owner + backup owner + quarterly review date                      | Avoids orphaned integrations                     |
| TCO ledger              | Versioned `resilience-tooling-tco.v1.md` + machine-readable `resilience-tooling-tco.v1.json`        | Makes cost trends auditable and gateable         |
| Cost dimensions         | Build/integration cost, run/ops cost, reliability tax, security/compliance cost, docs/training cost | Captures total cost beyond implementation effort |
| Runtime class policy    | Class A (`runtime-critical`), Class B (`test/eval-only`), Class C (`optional diagnostics`)          | Aligns adoption rigor with blast radius          |
| Exit governance         | Promote/pause/retire rules bound to measurable thresholds and rollback path                         | Enforces long-term maintainability               |

### Proposed Lifecycle/TCO Schema (v1)

Top-level fields (per tool):
1. `toolId`, `class`, `state`
2. `owner`, `backupOwner`, `reviewCadenceDays`
3. `license`, `upstreamRepo`, `upstreamReleaseSignal`
4. `localFirstCompatibility` (`native`, `requires_container_runtime`, `requires_network_service`)
5. `windowsRunnerCompatibility` (`pass`, `conditional`, `fail`)
6. `fallbackPath` (in-repo fallback or feature-disable mode)
7. `costs`:
   - `integrationHoursInitial`
   - `maintenanceHoursPerMonth`
   - `incidentHoursPerQuarter`
   - `upgradeEventsPerYear`
   - `ciOverheadMinutesPerRun`
8. `riskSignals`:
   - `releaseCadenceRisk`
   - `busFactorRisk`
   - `platformDriftRisk`
   - `doubleRetryOwnershipRisk`
9. `gates`:
   - promotion gate
   - freeze gate
   - retirement trigger

### Long-Term Portfolio Policy for 219 Candidates

1. `Toxiproxy`:
   - Target class: `Class B (test/eval-only)`, state `pilot -> adopt`.
   - Reason: strong value for fault scenarios without runtime dependency in product path.
   - Cost guard: keep standalone-first; no Docker dependency in default validation lanes.
2. `OpenTelemetry Collector + Jaeger`:
   - Target class: `Class C (optional diagnostics)`, state `pilot`.
   - Reason: high observability upside, but nontrivial operational/config burden and mixed collector component maturity.
   - Cost guard: off by default, opt-in profile only.
3. `grpcdebug`:
   - Target class: `Class C utility`, state `adopt` as operator tooling (not runtime dependency).
   - Reason: low integration cost, active updates, no runtime coupling.
4. `Resilience4j` / `Failsafe`:
   - Target class: `defer` (no runtime adoption now).
   - Reason: would overlap with gRPC-native retry + in-repo breakers, increasing ownership fragmentation and double-retry risk.
   - Re-evaluate only if post-RR-002/RR-003 evidence shows unstable in-repo policy convergence.
5. `Testcontainers Toxiproxy module`:
   - Target class: `defer` for default lanes.
   - Reason: container runtime requirement conflicts with primary local-first Windows evidence strategy.
   - Optional narrow use only where dedicated containerized CI lane exists.

### Lifecycle/TCO Gates (Operational)

Promotion (`pilot -> adopt`) requires:
1. Two release cycles with no severity-1 incidents attributed to tool integration.
2. `maintenanceHoursPerMonth` within budget cap.
3. Successful rollback drill and fallback-path proof.
4. No unresolved policy conflicts (`retry ownership`, `comparability`, `security`).

Freeze trigger (hold upgrades/new scope):
1. Upstream breakage causes repeated non-comparable evidence or CI instability.
2. Monthly maintenance toil exceeds budget for 2 consecutive months.
3. Tool requires environment changes violating local-first default policy.

Retire trigger:
1. Equivalent in-repo capability reaches lower 2-quarter TCO with acceptable risk.
2. Upstream support signal degrades (stagnant releases, unresolved critical issues, or EOL path).
3. Tool remains pilot for >2 quarters without meeting promotion gates.

### Best Long-Term State (L5 Definition)

1. Every external dependency in the resilience track has a lifecycle state, owners, cost ledger, and retirement policy.
2. Runtime-critical path remains minimal and local-first by default; external stacks are mostly test/diagnostic overlays.
3. Tooling decisions are revisited on cadence with measurable evidence instead of one-time adoption judgments.
4. Release governance consumes lifecycle/TCO artifacts directly, preventing hidden cost accumulation.
5. External-tool sprawl is controlled through explicit portfolio caps and sunset rules.

### Gaps to Resolve Before Implementing P5

1. Assign owner/backup roles for each candidate tool and agree on review cadence.
2. Lock budget caps for monthly maintenance toil and CI overhead.
3. Define the initial portfolio cap (max concurrent pilots/adopted external tools in this track).
4. Ratify fallback requirements per class (`A/B/C`) and rollback drill frequency.
5. Wire lifecycle/TCO artifact checks into RR-011 release-gate logic.

## P6 Deep Dive: Release Governance Integration (Investigated Target State)

### Current Structure (Code-Validated + Workflow-Validated)

1. Lane diff tools already emit policy-versioned decision artifacts (`bench-decision.v1`) with comparability and gate status via `scripts/bench/lib/policy-engine.mjs`.
2. Lane wrappers expose governance signals in manifests, but enforcement is mixed:
   - report-only wrappers (`scripts/ci/run-claim-a-report-win.ps1`, `scripts/ci/run-track-g-report-win.ps1`, `scripts/ci/run-search-eval-rank-report-win.ps1`) intentionally exit `0` on regressions and encode `report_only_regression_detected`.
   - gating wrappers (`scripts/ci/run-perf-regression-win.ps1`, `scripts/ci/run-beir-gate-win.ps1`, `scripts/ci/evaluate-agent-live-gate.mjs`) can fail run status.
3. Cross-lane aggregation exists: `scripts/bench/build-benchmark-scorecard.mjs` computes `release_readiness` (`pass|warn|insufficient_data`) plus ratchet recommendations.
4. CI workflows publish decisions/scorecards, but most benchmark lanes remain report-oriented and there is no single required check that enforces scorecard `release_readiness` as a canonical release decision.
5. Overnight orchestration (`scripts/bench/overnight-benchmark-autopilot-win.ps1`) consumes scorecard release status primarily for adaptive cadence, not for release authorization.
6. Local gate reliability reporting (`scripts/ci/local-agent-gate-win.ps1`, `scripts/ci/report-reliability-budget.mjs`) exists but is not fused into one release decision contract with benchmark/resilience evidence.

### External Guidance Synthesis (Primary Sources)

1. Error-budget policy should drive release behavior (freeze/escalate/exempt), not ad hoc human interpretation.
2. Required status checks via branch protection are the core deterministic merge/release gate primitive.
3. Merge queue requires explicit `merge_group` trigger support for required checks to remain enforceable in queued merges.
4. Deployment environments should enforce protection rules (for example required reviewers/waits) after CI gates pass.
5. Policy-as-code (for example OPA/Rego + bundle distribution) is the durable pattern for auditable, versioned gate logic.

### Structural Gap (Why P6 Is Still Open)

1. Governance logic is fragmented across wrapper scripts, scorecard logic, workflow YAML, and tempdoc policy text.
2. `release_readiness` is computed, but not consistently consumed as a release-blocking control-plane signal.
3. No versioned waiver/exception register with owner, expiry, and reason code exists for resilience gate bypasses.
4. No single machine-readable release decision artifact binds:
   - candidate identity,
   - policy version,
   - evidence set,
   - gate outcomes,
   - final allow/hold/block verdict.
5. Branch/deployment protection mapping is operationally implied, but not represented as a repository contract.
6. Ownership boundary with `216` must stay explicit: `219` consumes benchmark artifacts; it does not own benchmark harness refactors.

### Recommended Long-Term Structure (Best State)

| Layer                      | Long-Term State                                                                             | Why                                                                |
| -------------------------- | ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| Governance policy contract | `release-resilience-gates.v1.json` + `release-resilience-gates.v1.md`                       | Single source of truth for gate semantics and severity             |
| Evidence index contract    | `release-evidence-index.v1.json` per candidate                                              | Deterministic linkage from candidate to required artifacts         |
| Decision engine            | `scripts/ci/evaluate-release-governance.mjs` emitting `release-governance-decision.v1.json` | Reproducible, auditable verdict computation                        |
| Exception model            | `release-governance-waivers.v1.json` (time-bound, owner-bound, reason-coded)                | Prevents silent permanent bypasses                                 |
| CI integration             | One canonical required check (`release-governance`) fed by `216`-owned lane artifacts       | Removes lane-by-lane enforcement drift without splitting ownership |
| Deployment integration     | Environment protection consumes decision verdict prior to promotion                         | Separates verification and deployment authorization cleanly        |

### Proposed Governance Artifact Set (v1)

1. `release-resilience-gates.v1.json` (policy):
   - `policyVersion`, `effectiveFrom`, `mode` (`advisory|soft|hard`)
   - lane criticality map (`critical|important|advisory`)
   - required artifacts by lane/kind
   - comparability enforcement rules (`fail_on_non_comparable`, profile match requirements)
   - freshness limits and trend windows
   - gate actions per severity (`warn|hold|block`)
2. `release-evidence-index.v1.json` (inputs):
   - `candidateId`, `gitSha`, `runIds`
   - artifact references (`manifest`, `decision`, `scorecard`, `soak`, `readiness`)
   - comparability signatures/policy refs
3. `release-governance-decision.v1.json` (output):
   - `policyVersion`, `candidateId`, `generatedAt`
   - gate evaluations (`id`, `status`, `reasonCodes`, `evidenceRefs`)
   - waiver applications (if any)
   - final verdict (`allow|hold|block`) + rollback recommendation state
4. `release-governance-waivers.v1.json`:
   - `waiverId`, `scope`, `reasonCode`, `owner`, `approver`, `expiresAt`, `ticketRef`, `status`

### Enforcement Phasing (Target)

| Phase | Mode     | Behavior                                                                               |
| ----- | -------- | -------------------------------------------------------------------------------------- |
| G0    | advisory | Emit decision artifact only; never blocks                                              |
| G1    | soft     | Blocks release candidate promotion; PR merge still allowed with explicit waiver        |
| G2    | hard     | Required check blocks merge/release when verdict is `block` and no valid waiver exists |

Promotion from `G0 -> G1 -> G2` requires:
1. Stable artifact production and schema validation across 4 consecutive weeks.
2. False-block rate below agreed threshold.
3. Waiver workflow exercised with expiry and audit trail.

### Best Long-Term State (L5 Definition)

1. Every release candidate has a deterministic `release-governance-decision.v1.json`.
2. Governance policy is versioned, auditable, and changed through explicit review.
3. Required checks and deployment protections enforce the same machine decision (no split-brain between CI and deploy).
4. Waivers are explicit, time-limited, and owner-approved with automatic expiry.
5. Resilience evidence (P0-P5 artifacts) is mandatory input to release decisions, not optional commentary.

### Gaps to Resolve Before Implementing P6

1. Define lane criticality classes and default action mapping (`warn|hold|block`).
2. Ratify ownership for policy changes, waiver approvals, and release override authority.
3. Decide where the canonical required check runs (PR workflow vs release workflow vs both) and how it maps to local-first development cadence.
4. Lock schema contracts for evidence index and decision artifact, including backward compatibility rules.
5. Define rollback trigger semantics tied to decision reason codes (for example comparability failure vs true quality regression).

## Investigation Plan (Historical Baseline)

### Phase A: Truth and Ownership Alignment

Deliverables:
1. `BKD-009`/`BKD-010` status recommendation (close/re-scope with explicit residual tasks).
2. Runtime resilience ownership map (who owns policy, code, tests, and gates).

Exit criteria:
1. No contradiction between issue state and code state.

### Phase B: Policy and Primitive Consolidation Plan

Deliverables:
1. Decision on single circuit-breaker primitive vs justified dual-primitives.
2. Retry-policy governance map (shared defaults + explicit deviations).

Exit criteria:
1. Drift risk reduced to documented, intentional exceptions only.

### Phase C: Operational Confidence Evidence Plan

Deliverables:
1. Fault-model library design (`grpc-fault-scenarios.v1.json`) with canonical profile/signature rules.
2. Artifact schema proposal for `grpc-retry-soak-report.v1.json` with policy refs and decision block.
3. Comparability contract and reason-code model for non-comparable runs.
4. Thresholds for latency amplification and retry-storm detection.

Exit criteria:
1. Runtime resilience can be graded with objective evidence, not code inspection only.
2. Soak runs are deterministically comparable (or explicitly non-comparable) by contract.

### Phase D: Readiness Semantics Hardening Plan

Deliverables:
1. Proposed meaning contract for worker and head readiness fields (including `ai_ready` vs `embedding_ready` ownership).
2. Typed readiness state model + reason-code taxonomy + staleness rules.
3. Cross-surface parity rules (`/api/health` gate vs `/api/status` readiness envelope).
4. Test matrix proving semantics across offline/degraded/unknown/partial-ready cases.

Exit criteria:
1. Health/readiness semantics are unambiguous and user-visible behavior matches intent.
2. Missing-probe conditions are represented as typed states, not omitted booleans.
3. Lifecycle gate behavior remains stable while rich readiness detail is additive.

### Phase E: External-Fit Validation Plan

Deliverables:
1. Toxiproxy adoption spike plan (standalone process control, scenario library, artifact wiring).
2. Optional Collector/Jaeger pilot plan with explicit off-by-default policy.
3. Framework replacement go/no-go criteria (`Resilience4j`/`Failsafe`) based on measured outcomes, not preference.
4. External tooling lifecycle/TCO register (`resilience-tooling-tco.v1.md` + JSON companion) with owners, budgets, and gates.

Exit criteria:
1. One external adoption path (`Toxiproxy`) is selected with clear success metrics.
2. Deferred items have explicit re-evaluation trigger and date.
3. All external tools in scope have lifecycle state + owner + cost guardrails.

### Phase F: Long-Term Resilience Program Foundations

Deliverables:
1. SLO/failure-budget contract for runtime resilience (P0), including human + machine-readable specs.
2. Method-level retry/idempotency ownership matrix (P1), descriptor-complete and parity-tested against runtime config.
3. Breaker/retry calibration evidence and recommended defaults (P2-P3).
4. Release governance package (P6): policy contract + evidence index + decision artifact + waiver model.
5. Required-check/deployment-protection mapping plan with phased enforcement (`G0/G1/G2`).
6. Unified control-loop conformance packet: P0-P6 role/consume/produce parity + shared artifact-backbone conformance.
7. Interface contract with `216`: explicit list of benchmark artifacts/fields that `219` consumes, plus change-request path for missing data.

Exit criteria:
1. Resilience roadmap is measurable quarter-over-quarter.
2. Rollout decisions can be made from evidence, not qualitative confidence only.
3. One canonical release-governance decision path exists for resilience evidence.
4. P6 decision is recomputable from P0-P5 artifacts without manual interpretation.

## Candidate Work Packages

| ID     | Work Package                              | Output                                                                                                    | Depends On | Status                              |
| ------ | ----------------------------------------- | --------------------------------------------------------------------------------------------------------- | ---------- | ----------------------------------- |
| RR-001 | Issue/doc reconciliation                  | Updated issue states + residual checklist                                                                 | Phase A    | Done (2026-02-19)                   |
| RR-002 | Primitive consolidation decision          | Canonical breaker in `ipc-common` + app-services compatibility adapter + observer hook parity             | Phase B    | Done (2026-02-19)                   |
| RR-003 | Retry policy governance                   | Shared mixed-scope retry builder + policy-profile registry + matrix-policy parity validation              | Phase B    | Done (2026-02-19)                   |
| RR-004 | Resilience soak harness plan              | Fault scenario library + report schema + comparability/gate contract                                      | Phase C    | Done (G0, 2026-02-19)               |
| RR-005 | Readiness semantics contract              | Field semantics + typed state model + cross-surface parity tests                                          | Phase D    | Done (v1, 2026-02-19)               |
| RR-006 | External-fit decision record              | Adopt/Pilot/Defer rationale with scoring sheet                                                            | Phase E    | Done (2026-02-19)                   |
| RR-007 | Toxiproxy spike specification             | Standalone runbook + failure scenario catalog                                                             | Phase E    | Done (2026-02-19)                   |
| RR-012 | External tooling lifecycle/TCO register   | Lifecycle states + ownership + quarterly cost/risk review model                                           | Phase E    | Done (2026-02-19)                   |
| RR-008 | Resilience SLO/failure-budget model       | Human policy + OpenSLO spec + error-budget policy + observe-first budget snapshot artifact                | Phase F    | Done (`G0`, 2026-02-19)             |
| RR-009 | Retry/idempotency ownership matrix        | Descriptor-complete matrix + schema + generated config + ownership/parity gates                           | Phase F    | Done (v1, 2026-02-19)               |
| RR-010 | Breaker/retry calibration study           | Calibration policy + schema + builder + short-smoke runner + emitted calibration report                   | Phase F    | Done (short-smoke path, 2026-02-19) |
| RR-011 | Resilience release governance integration | Policy/evidence/decision/waiver contracts + required-check mapping + rollback rules                       | Phase F    | Done (G0, 2026-02-19)               |
| RR-014 | 216/219 interface contract                | Consumer contract for benchmark artifact inputs into resilience governance (no harness ownership overlap) | Phase F    | Done (2026-02-19)                   |
| RR-013 | Unified P0-P6 control-loop conformance    | Conformance schema + builder + ratification contract + governance evidence wiring (`warn-first`)          | Phase F    | Done (`G0`, 2026-02-19)             |

## Exit Criteria for This Tempdoc

1. `BKD-009` and `BKD-010` have reconciled status and explicit remaining tasks. -> Done (2026-02-19)
2. Circuit-breaker/retry governance is documented with no ambiguous ownership. -> Done (`RR-002`, `RR-003`, `RR-009`)
3. Soak evidence plan is approved and measurable. -> Done (`RR-004` contract package, G0)
4. `ai_ready`/`embedding_ready` semantics are explicitly specified and test-backed. -> **Partial** (`RR-005`):
   - Done: transport propagation and status-surface wiring (`worker -> app-services -> /api/status -> UI`) is implemented and test-backed.
   - Open: semantic ownership/meaning is still ambiguous (`ai_ready` currently aliases embedding availability in worker health path).
5. External-tool adoption/defer decisions are explicit with re-evaluation triggers. -> Done (`RR-006` decision artifact + schema, `RR-007` spike spec, `RR-012` TCO register)
6. SLO/failure-budget and release-gate contracts are ratified for long-term use. -> Done (`RR-008` + `RR-011`, observe-first `G0`)
7. P0-P6 unified theory is ratified with deterministic artifact flow into P6 decisions. -> Done (`RR-013` ratification artifact wired; Pass E calibrated candidate flow now yields conformance `verdict=pass`)

## Residuals (Track Still Active, Historical Snapshot)

This section records mid-stream residual state at the time it was written.
Current source-of-truth residuals are in **Authoritative Open Residuals (Current)** near the end of this tempdoc.

1. Resolve current true-signal blockers isolated by Pass O artifacts:
   - `search-rank` persistent comparable regression (`hybrid meanRecallAtK`, `hybrid meanNdcgAtK`) in triage,
   - `track-g` persistent comparable regression under repeat-3 profile,
   - `agent` and selected benchmark lanes below history-readiness criteria.
2. Build comparable history windows to satisfy lane minimums and reduce critical-lane uncertainty:
   - critical lanes still failing history-readiness targets (`track-g`, `perf`, `rag-eval`, `agent-battery`, `beir-gate`),
   - `beir-gate` latest run is comparable/pass, but historical window still contains mixed-profile/non-comparable residue and insufficient comparable count.
3. Re-run G0/G1 shadow decisions on fresh same-run evidence after each history batch until `runtime-resilience-g1-readiness` recommends promotion.
4. Governance promotion beyond `G0` remains a manual policy decision after criteria are met (`G1`/`G2` criteria, rollout date, rollback triggers).

## References

Strategy:
1. `docs/tempdocs/210-agent-infra-external-fit-research.md`
2. `docs/tempdocs/216-eval-harness-consolidation.md`

Issues:
1. `docs/reference/issues/backend-tech-debt.md`

Core code:
1. `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcRetryServiceConfig.java`
2. `modules/ipc-common/src/main/java/io/justsearch/ipc/grpc/GrpcCircuitBreaker.java`
3. `modules/app-search/src/main/java/io/justsearch/app/search/GrpcAnnSearchClient.java`
4. `modules/app-search/src/main/java/io/justsearch/app/search/GrpcEmbeddingClient.java`
5. `modules/app-ai/src/main/java/io/justsearch/app/ai/GrpcAiTranslatorService.java`
6. `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteKnowledgeClient.java`
7. `modules/app-services/src/main/java/io/justsearch/app/services/worker/GrpcCircuitBreaker.java`
8. `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/services/GrpcHealthService.java`
9. `modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java`
10. `modules/ui-web/src/stores/useSystemStore.ts`
11. `modules/ui-web/src/components/views/health/deriveHealthEvents.ts`
12. `modules/ipc-common/src/main/proto/indexing.proto`
13. `modules/ipc-common/src/main/proto/io/justsearch/ipc/v1/ai.proto`
14. `reports/phase6a/telemetry/search/telemetry/metrics.ndjson`
15. `modules/system-tests/src/systemTest/java/io/justsearch/systemtests/ChaosSuiteTest.java`
16. `modules/system-tests/src/soakTest/java/io/justsearch/systemtests/SoakSuiteTest.java`
17. `modules/system-tests/src/main/java/io/justsearch/systemtests/soak/SoakTestRunner.java`
18. `scripts/ci/run-agent-live-battery.mjs`
19. `scripts/ci/build-agent-live-scorecard.mjs`
20. `scripts/bench/lib/policy-engine.mjs`
21. `scripts/perf/diff-perf-suite.mjs`
22. `scripts/perf/core/perf-suite-runtime-common.ps1`
23. `modules/system-tests/README.md`
24. `modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java`
25. `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleSnapshotV1.java`
26. `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleState.java`
27. `modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/LifecycleReasonCode.java`
28. `modules/ipc-common/src/main/proto/io/justsearch/ipc/v1/health.proto`
29. `scripts/bench/build-benchmark-scorecard.mjs`
30. `scripts/bench/scorecard-ratchet-policy.v1.json`
31. `scripts/ci/run-claim-a-report-win.ps1`
32. `scripts/ci/run-track-g-report-win.ps1`
33. `scripts/ci/run-search-eval-rank-report-win.ps1`
34. `scripts/ci/run-perf-regression-win.ps1`
35. `scripts/ci/run-beir-gate-win.ps1`
36. `scripts/ci/evaluate-agent-live-gate.mjs`
37. `scripts/bench/overnight-benchmark-autopilot-win.ps1`
38. `scripts/ci/local-agent-gate-win.ps1`
39. `.github/workflows/claim-a-report-win.yml`
40. `.github/workflows/rr219-resilience-governance-nightly.yml`
41. `.github/workflows/track-g-report-win.yml`
42. `.github/workflows/search-eval-rank-report-win.yml`
43. `.github/workflows/perf-regression-win.yml`
44. `.github/workflows/beir-eval-gate-win.yml`
45. `.github/workflows/agent-live-eval-nightly.yml`
46. `scripts/resilience/contracts/grpc-retry-policy-profiles.v1.json`
47. `scripts/governance/resilience/build-runtime-resilience-budget-snapshot.mjs`
48. `scripts/resilience/calibration/grpc-resilience-policy.v1.json`
49. `scripts/resilience/calibration/build-grpc-breaker-retry-calibration-report.mjs`
50. `scripts/resilience/calibration/run-grpc-resilience-smoke-win.ps1`
51. `scripts/governance/resilience/build-runtime-resilience-control-loop-conformance.mjs`
52. `docs/reference/contracts/runtime-resilience-slo.v1.md`
53. `docs/reference/contracts/runtime-resilience-slo.v1.openslo.yaml`
54. `docs/reference/contracts/runtime-resilience-error-budget-policy.v1.md`
55. `docs/reference/contracts/runtime-resilience-control-loop-conformance.v1.md`
56. `scripts/bench/overnight-rag-ai-queue-win.ps1`
57. `scripts/bench/diff-track-g-suite.mjs`
58. `scripts/bench/test-diff-track-g-suite.mjs`
59. `scripts/perf/baselines/perf-baseline-profiles.v1.json`
60. `scripts/ci/run-perf-regression-win.ps1`
61. `scripts/ci/run-beir-gate-win.ps1`
62. `scripts/search/beir-eval-win.ps1`
63. `scripts/bench/diff-search-eval-suite.mjs`
64. `scripts/bench/test-diff-search-eval-suite.mjs`
65. `scripts/bench/test-convert-beir-metrics-v1-to-v2.mjs`
66. `scripts/governance/resilience/run-rr219-regression-pack-win.ps1`
56. `scripts/governance/resilience/runtime-resilience-external-fit-decision.v1.schema.json`
57. `scripts/governance/resilience/runtime-resilience-external-fit-decision.v1.json`
58. `docs/reference/contracts/runtime-resilience-external-fit-decision.v1.md`
59. `scripts/resilience/faults/toxiproxy-spike-scenarios.v1.json`
60. `docs/reference/contracts/toxiproxy-spike-spec.v1.md`
61. `scripts/governance/resilience/resilience-tooling-tco.v1.json`
62. `docs/reference/contracts/resilience-tooling-tco.v1.md`
63. `scripts/governance/resilience/test-build-release-evidence-index.mjs`
64. `scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs`
65. `scripts/governance/resilience/run-rr219-regression-pack-win.ps1`
66. `scripts/resilience/faults/toxiproxy-spike-scenarios.v1.schema.json`
67. `scripts/governance/resilience/resilience-tooling-tco.v1.schema.json`
68. `scripts/governance/resilience/validate-runtime-resilience-external-artifacts.mjs`
69. `scripts/governance/resilience/test-validate-runtime-resilience-external-artifacts.mjs`
70. `scripts/governance/resilience/build-release-evidence-index.mjs`
71. `scripts/governance/resilience/test-build-runtime-resilience-control-loop-conformance.mjs`
72. `scripts/ci/test-beir-baseline-profile-selection.ps1`
73. `scripts/perf/baselines/win-regression-no-ui.perf-suite.json`
74. `scripts/perf/baselines/win-regression-no-ui.perf-suite.v2.json`
75. `scripts/perf/baselines/win-smoke-no-ui.perf-suite.json`
76. `scripts/perf/baselines/win-smoke-no-ui.perf-suite.v2.json`
77. `scripts/governance/resilience/rr219-comparability-policy.v1.json`
78. `scripts/governance/resilience/rr219-comparability-policy.v1.schema.json`
79. `scripts/governance/resilience/rr219-comparability-lib.mjs`
80. `scripts/governance/resilience/validate-rr219-comparability-inputs.mjs`
81. `scripts/governance/resilience/build-runtime-resilience-lane-triage.mjs`
82. `scripts/governance/resilience/runtime-resilience-lane-triage.v1.schema.json`
83. `scripts/governance/resilience/build-runtime-resilience-history-readiness.mjs`
84. `scripts/governance/resilience/runtime-resilience-history-readiness.v1.schema.json`
85. `scripts/governance/resilience/build-runtime-resilience-g1-readiness.mjs`
86. `scripts/governance/resilience/test-evaluate-release-governance.mjs`
87. `scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs`
88. `scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs`
89. `scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs`

Tests:
1. `modules/ipc-common/src/test/java/io/justsearch/ipc/grpc/GrpcRetryServiceConfigTest.java`
2. `modules/ipc-common/src/test/java/io/justsearch/ipc/grpc/GrpcCircuitBreakerTest.java`
3. `modules/app-search/src/test/java/io/justsearch/app/search/GrpcAnnSearchClientTest.java`
4. `modules/app-search/src/test/java/io/justsearch/app/search/GrpcEmbeddingClientTest.java`
5. `modules/app-ai/src/test/java/io/justsearch/app/ai/GrpcAiTranslatorServiceTest.java`
6. `modules/app-services/src/test/java/io/justsearch/app/services/worker/WorkerStatusMapperTest.java`
7. `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteKnowledgeClientRetryConfigTest.java`
8. `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/services/GrpcHealthServiceTest.java`
9. `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java`
10. `modules/ui-web/src/components/views/health/deriveHealthEvents.test.ts`
11. `scripts/resilience/test-validate-rpc-retry-ownership.mjs`
12. `scripts/governance/resilience/test-build-release-evidence-index.mjs`
13. `scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs`
14. `scripts/governance/resilience/test-validate-runtime-resilience-external-artifacts.mjs`
15. `scripts/governance/resilience/test-build-runtime-resilience-control-loop-conformance.mjs`
16. `scripts/governance/resilience/test-validate-rr219-comparability-inputs.mjs`
17. `scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs`
18. `scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs`
19. `scripts/governance/resilience/test-evaluate-release-governance.mjs`
20. `scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs`

External sources:
1. https://grpc.io/docs/guides/retry/
2. https://grpc.io/docs/guides/request-hedging/
3. https://grpc.github.io/grpc-java/javadoc/io/grpc/ManagedChannelBuilder.html
4. https://github.com/resilience4j/resilience4j
5. https://failsafe.dev/
6. https://github.com/Shopify/toxiproxy
7. https://java.testcontainers.org/modules/toxiproxy/
8. https://opentelemetry.io/docs/collector/resiliency/
9. https://www.jaegertracing.io/docs/2.11/
10. https://grpc.io/docs/guides/opentelemetry-metrics/
11. https://github.com/grpc-ecosystem/grpcdebug
12. https://sre.google/workbook/implementing-slos/
13. https://sre.google/workbook/alerting-on-slos/
14. https://sre.google/workbook/appendix-a-slos-for-your-service/
15. https://sre.google/workbook/appendix-b-error-budget-policy/
16. https://github.com/OpenSLO/OpenSLO
17. https://google.aip.dev/194
18. https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/
19. https://resilience4j.readme.io/docs/circuitbreaker
20. https://github.com/open-telemetry/opentelemetry-collector/blob/main/docs/release.md
21. https://opentelemetry.io/status/
22. https://github.com/open-telemetry/opentelemetry-collector-releases/releases
23. https://www.jaegertracing.io/download/
24. https://github.com/grpc-ecosystem/grpcdebug/releases
25. https://github.com/failsafe-lib/failsafe/releases
26. https://github.com/failsafe-lib/failsafe/tags
27. https://java.testcontainers.org/supported_docker_environment/
28. https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches
29. https://docs.github.com/en/actions/reference/events-that-trigger-workflows#merge_group
30. https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
31. https://www.openpolicyagent.org/docs/latest/policy-language/
32. https://www.openpolicyagent.org/docs/latest/management-bundles/

---

## Implementation Pass T (2026-02-20)

Pass T implemented the remaining non-run workstream and keeps this tempdoc active.

| Order | Target                               | Status | Output                                                                                                                                                                                                                      |
| ----- | ------------------------------------ | ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1     | Governance automation cleanup        | Done   | Regression pack now writes an intermediate report, builds same-run **final** evidence index with `--regression-pack-report`, evaluates `G0` + `G1` shadow from that final index, and rewrites one stable report path        |
| 2     | Post-lock history-window hygiene     | Done   | Added `historyWindow` policy contract and enforced `post_lock_only` filtering before lookback slicing in triage/history builders                                                                                            |
| 3     | Reason-code owner/action mapping     | Done   | Added `runtime-resilience-reason-code-owner-map.v1` (+ schema) and evaluator enrichment (`ownerRole`, `actionType`, `targetSlaDays`, `expectedEvidence`) with strict mapping mode                                           |
| 4     | Persistent-regression handoff packet | Done   | Added `build-runtime-resilience-persistent-handoff.mjs` (+ schema) emitting JSON + Markdown owner packets                                                                                                                   |
| 5     | G1 readiness preflight completeness  | Done   | Added strict preflight in `build-runtime-resilience-g1-readiness.mjs` (`--require-complete-preflight`, `--max-artifact-age-minutes`)                                                                                        |
| 6     | Regression-pack integration + sync   | Done   | Pack now emits final artifact references (`finalEvidenceIndex`, `governanceDecisionG0Final`, `governanceDecisionG1Final`, `g1ReadinessFinal`, `persistentHandoffJson`, `persistentHandoffMd`) and executes new handoff test |

### Artifacts Added (Pass T)

1. `scripts/governance/resilience/runtime-resilience-reason-code-owner-map.v1.schema.json`
2. `scripts/governance/resilience/runtime-resilience-reason-code-owner-map.v1.json`
3. `scripts/governance/resilience/runtime-resilience-persistent-handoff.v1.schema.json`
4. `scripts/governance/resilience/build-runtime-resilience-persistent-handoff.mjs`
5. `scripts/governance/resilience/test-build-runtime-resilience-persistent-handoff.mjs`

### Validation Evidence (Pass T Completion, Short-Run)

1. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass.
2. `node scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs` -> pass.
3. `node scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs` -> pass.
4. `node scripts/governance/resilience/test-evaluate-release-governance.mjs` -> pass.
5. `node scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs` -> pass.
6. `node scripts/governance/resilience/test-build-runtime-resilience-persistent-handoff.mjs` -> pass.
7. `node scripts/governance/resilience/test-validate-rr219-comparability-inputs.mjs` -> pass.
8. `node scripts/governance/resilience/validate-rr219-comparability-inputs.mjs --allow-missing-lanes --out-json tmp/resilience/governance/rr219-comparability-validation-pass-t-smoke.json` -> pass.
9. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passr2.json -StepTimeoutMinutes 20` -> pass:
   - report: `tmp/resilience/governance/rr219-regression-pack-report-20260220-154032.json`
   - report truth fields: `summary.passedSteps=33`, `summary.totalSteps=33`, `summary.timeoutCount=0`
   - CLI result output includes additive `ok=true` derived from `infraFailure=false`
10. Same-run final-index checks from report artifacts:
   - `evidence.regressionPackReport.present=true`
   - `RG-016` status is `pass` (no manual post-pack rebuild required)

## Implementation Pass U (2026-02-20)

Pass U resolves the 219/216 consistency slice and adds policy-version drift hardening in governance.

| Order | Target                           | Status | Output                                                                                                      |
| ----- | -------------------------------- | ------ | ----------------------------------------------------------------------------------------------------------- |
| 1     | 219 truth-model cleanup          | Done   | Stale non-run section marked historical; RR-005 status corrected to Partial; residual authority centralized |
| 2     | 216/219 boundary sync            | Done   | 216 now tracks F-7 as post-completion residual with explicit 219 downstream impact                          |
| 3     | Canonical issue tracking for F-7 | Done   | Added `BEN-005` to `docs/reference/issues/benchmarking.md`                                                  |
| 4     | Policy-version drift guard       | Done   | Added `RG-019` (`meta.policyVersionMatch`, warn in `G0`, block in `G1/G2`)                                  |
| 5     | Evaluator diagnostics enrichment | Done   | `release-governance-decision.v1` now includes policy-version diagnostics in `inputs` and `meta`             |
| 6     | Reason-owner mapping extension   | Done   | Added `POLICY_VERSION_REF_MISMATCH` ownership/action mapping                                                |

### Validation Evidence (Pass U Completion, Short-Run)

1. `node scripts/governance/resilience/test-evaluate-release-governance.mjs` -> pass.
2. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass.
3. `node scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs` -> pass.
4. `node scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs` -> pass.
5. `node scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs` -> pass.
6. `node scripts/governance/resilience/test-build-runtime-resilience-persistent-handoff.mjs` -> pass.
7. `node scripts/governance/resilience/test-validate-rr219-comparability-inputs.mjs` -> pass.
8. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passu-clean2.json -StepTimeoutMinutes 20` -> pass (`passedSteps=33`, `totalSteps=33`, `timeoutCount=0`).
9. Explicit RG-019 policy mismatch scenario checks:
   - `G0` + matching `policyVersionRef` -> `RG-019=pass` (warn-severity gate satisfied).
   - `G0` + mismatched `policyVersionRef` -> `RG-019=fail` warn-only (non-blocking).
   - `G1` + mismatched `policyVersionRef` -> `RG-019=fail` escalated to block via `enforceInModes`.

### 216-Owned Dependency Tracking (Explicit)

| Residual signal in 219                         | Upstream owner                   | Tracking artifact                                           | 219 posture                                                  |
| ---------------------------------------------- | -------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------ |
| `search-rank` persistent comparable regression | tempdoc 216 / lane-quality owner | `runtime-resilience-persistent-handoff.v1` + lane manifests | Consume evidence, do not redesign lane contracts             |
| `track-g` persistent comparable regression     | tempdoc 216 / lane-quality owner | `runtime-resilience-persistent-handoff.v1` + lane manifests | Consume evidence, do not redesign lane contracts             |
| `beir-gate` F-7 hybrid realism gap             | tempdoc 216 / BEN issue owner    | `BEN-005` in `docs/reference/issues/benchmarking.md`        | Treat as upstream evidence-quality dependency until resolved |

### Residuals (Still Open, Historical Snapshot)

The residual list in Pass T is preserved for traceability.  
Current authoritative open items are listed below.

## Implementation Pass V (2026-02-20)

Pass V implements the revised control-plane and contract hardening slice, while explicitly splitting implementation closure from evidence accrual closure.

### Two-Track Status

| Workstream                          | Implementation Status     | Evidence Status     | Notes                                                                                                                                                                   |
| ----------------------------------- | ------------------------- | ------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| V1 RG-019 producer consistency      | `implementation_complete` | `evidence_accrual`  | `build-release-evidence-index` now resolves policy version from active policy file (`--policy-path`) in production paths; mismatch fixtures still use explicit override |
| V2 RR-005 semantic migration        | `implementation_complete` | `evidence_complete` | `/api/status.readiness.components.ai` now follows inference lifecycle; legacy `aiReady`/`embeddingReady` are derived aliases; backend + UI tests updated                |
| V3 G3 recurring soak lane           | `implementation_complete` | `evidence_accrual`  | Added soak runner/builder using existing `grpc-retry-soak-report.v1` + validator; nightly short + weekly soak workflows now defined                                     |
| V4 owner-blocked lane signaling     | `implementation_complete` | `evidence_accrual`  | Triage/history artifacts now emit `blockedByOwner` metadata; governance adds `RG-020` (`G0` warn, `G1/G2` block)                                                        |
| V5 7-day promotion packet readiness | `implementation_complete` | `evidence_accrual`  | Same-run final evidence generation and dual-mode governance are automated; promotion remains manual decision after window closes                                        |

### Residual Execution Matrix

| residual_id    | owner                                | implementation_exit                                                      | evidence_exit                                                                                                                                        | current_state                                 |
| -------------- | ------------------------------------ | ------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| V3-G3-SOAK     | 219 runtime-governance               | soak runner + report builder + nightly/weekly workflow merged            | 7-day window has >=2 successful nightly `smoke` soak reports and >=1 successful weekly `soak` report (schema-valid + comparable/explicitly reasoned) | `implementation_complete`, `evidence_accrual` |
| V4-HISTORY     | 219 runtime-governance               | post-lock history + owner-block metadata + `RG-020` merged               | profile-locked comparable-history minimums reached for critical lanes (or explicit owner-block waivers recorded)                                     | `implementation_complete`, `evidence_accrual` |
| V4-OWNER-BLOCK | 216 lane-quality owners              | owner boundaries and reason-code mapping wired in 219 governance outputs | unresolved owner-blocked critical lanes reduced to zero or waived for G1 packet                                                                      | `blocked_by_owner`                            |
| V5-G1-PACKET   | runtime-governance + policy approver | G0+G1 shadow packet generation fully automated same-run                  | manual policy decision recorded with waiver posture and rollback triggers                                                                            | `pending_manual_decision`                     |

### Code Closure vs Residual Closure

Code closure and residual closure are separate in Pass V:
1. Code closure = contracts, scripts, workflows, and tests merged.
2. Residual closure = scheduled evidence accrual over days/weeks and explicit manual governance decision.

### Pass V Validation Evidence (Short-Run)

1. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass.
2. `node scripts/governance/resilience/test-evaluate-release-governance.mjs` -> pass (`RG-019` + `RG-020` mode behavior covered).
3. `node scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs` -> pass (post-lock filtering + owner-block metadata).
4. `node scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs` -> pass (persistent regression owner-block classification).
5. `node scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs` -> pass.
6. `node scripts/governance/resilience/test-build-runtime-resilience-budget-snapshot.mjs` -> pass.
7. `node scripts/resilience/faults/test-build-grpc-retry-soak-report.mjs` -> pass.
8. `./gradlew.bat :modules:ui:test --tests "*LifecycleContractTest" --tests "*StatusLifecycleHandlerTest"` -> pass.
9. `npm --prefix modules/ui-web run test:unit:run -- src/components/views/health/deriveHealthEvents.test.ts` -> pass.
10. `powershell -File scripts/resilience/faults/run-grpc-retry-soak-win.ps1 -RunMode smoke -GovernanceMode G0 -OutDir tmp/resilience/faults/smoke-test -StepTimeoutMinutes 20` -> pass.
11. `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passu-clean2.json -StepTimeoutMinutes 20` -> pass (`passedSteps=34`, `totalSteps=34`, `timeoutCount=0`).
12. `powershell -File scripts/resilience/faults/run-grpc-retry-soak-win.ps1 -RunMode smoke -GovernanceMode G1 -OutDir tmp/resilience/faults/smoke-test-g1 -StepTimeoutMinutes 20` -> pass (`gateStatus=pass`, comparable in `G1` mode).
13. Latest generated evidence references:
   - `tmp/resilience/governance/rr219-regression-pack-report-20260220-225214.json`
   - `tmp/resilience/faults/smoke-test/grpc-retry-soak-report-smoke-20260220-225143.json`
   - `tmp/resilience/faults/smoke-test-g1/grpc-retry-soak-report-smoke-20260220-225444.json`
   - `tmp/resilience/faults/smoke-test-g1/grpc-soak-comparability-decision-smoke-20260220-225444.json`

### How To Proceed (Execution)

Implementation work for Pass V is complete; remaining work is evidence accrual and governance decisioning.

1. Run evidence accrual for 7 days with profile locks unchanged:
   - nightly: `.github/workflows/rr219-resilience-governance-nightly.yml` (includes short soak `runMode=smoke`)
   - weekly: `.github/workflows/rr219-resilience-soak-weekly.yml` (includes long soak `runMode=soak`)
2. Enforce no profile contamination during accrual:
   - keep `rr219-comparability-policy.v1.json` lock window/profile settings unchanged
   - treat non-comparable evidence as explicit warnings in `G0` and blockers for `G1` simulation
3. Regenerate same-run governance packet daily from fresh artifacts:
   - `run-rr219-regression-pack-win.ps1` on current evidence index
   - track `RG-018`, `RG-019`, and `RG-020` trend states per day
4. Track owner-boundary blockers separately from governance defects:
   - unresolved `216` lane blockers remain `blocked_by_owner=true`
   - do not collapse these into runtime-governance implementation gaps
5. At end of 7-day window, build promotion packet:
   - include latest history readiness, triage, conformance, budget, soak summary, G0 and G1-shadow decisions
   - keep final `G0 -> G1` promotion as explicit manual policy decision

## Implementation Pass W (2026-02-21)

Reality sync from latest existing artifacts (no new runs started in this pass).

### Latest Lane Signals (Observed)

| Lane          | Latest observed signal                                                  | Source                                                                                                                                                                             | Assessment                                                                                              |
| ------------- | ----------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| search-rank   | fail -> pass after baseline promotion                                   | `tmp/track-check/search-rank-manifest-post-autopilot.json`, `tmp/track-check/search-rank-manifest-after-promote.json`                                                              | execution path works; quality stability still needs repeated comparable history                         |
| track-g       | fail -> pass after baseline refresh                                     | `tmp/track-check/track-g-manifest.json`, `tmp/track-check/track-g-manifest-after-refresh.json`                                                                                     | repeat-profile and comparability controls work; stability still needs history                           |
| beir-gate     | stub fail -> pass after promotion; embedding pass                       | `tmp/track-check/beir-gate-manifest-post-autopilot.json`, `tmp/track-check/beir-gate-manifest-after-promote.json`, `tmp/track-check/beir-gate-embedding-manifest.json`             | provenance/profile split is working; BEN-005 realism concern remains open upstream                      |
| perf          | pass, but profile identity fields missing in manifest                   | `tmp/track-check/perf-manifest-post-autopilot.json`                                                                                                                                | comparability works, but `baseline_profile_id`/`comparability_profile` null indicates producer-path gap |
| rag-eval      | latest runs fail on non-comparable workload metadata                    | `tmp/agent-evidence/_summaries/rag-eval-report-manifest-20260221-011350.json`, `build/test-results/rag-eval/rag-eval-decision.phase6-real-embedding.20260221-011350.run06.v1.json` | contract gap: candidate missing corpus profile/signature/components                                     |
| agent-battery | only 1 comparable run in active signature window; Phase A not graduated | `tmp/agent-evidence/_summaries/agent-live-scorecard-overnight-20260221-011350.json`                                                                                                | infra stable, quality/readiness still below promotion criteria                                          |

### Governance Packet Freshness Gap

The latest RR-219 packet is still based on synthetic/empty summary input:
- `tmp/resilience/governance/runtime-resilience-history-readiness-20260220-225214.json` uses `summaryDir=...passu-empty-summaries-2` and reports zero considered runs.
- `tmp/resilience/governance/runtime-resilience-lane-triage-20260220-225214.json` also uses zero selected runs.

Implication: current `RG-018`/`RG-020` outcomes are structurally conservative but stale relative to newer lane manifests.

## Implementation Pass X (2026-02-21)

Implemented the four Pass W follow-ups as code changes (no long runs in this pass).

### What Was Implemented

1. **RR-219 real-summary ingestion hardening**
   - `scripts/governance/resilience/run-rr219-regression-pack-win.ps1` now resolves summary discovery from real evidence paths, not only scorecard `discover_dir`.
   - Added additive CLI override: `-SummaryDir`.
   - Summary dir selection now ranks candidate dirs by lane-manifest signal coverage and recency.

2. **RAG corpus comparability metadata propagation**
   - `scripts/bench/overnight-rag-ai-queue-win.ps1` now enriches copied RAG v1/v2 result snapshots with corpus identity fields before diff:
     - `corpus_profile_id`, `corpus_signature`, `corpus_components`, `corpus_tier`
     - `threshold_context.threshold_profile.*`
   - Manifest candidate path now prefers v2 snapshot when present.

3. **Perf profile identity emission for non-gated paths**
   - `scripts/ci/run-perf-regression-win.ps1` now resolves profile metadata even when `GateLevel=none` (best-effort by profile id or baseline path match).
   - This fills `baseline_profile_id`, `comparability_profile`, `ui_required` whenever profile mapping is deterministically available.

4. **Baseline-shift provenance interpretation in triage**
   - `scripts/governance/resilience/build-runtime-resilience-lane-triage.mjs` now emits additive `baselineShift` evidence per lane and uses it in classification/action ownership logic.
   - `run-rr219-regression-pack-win.ps1` now passes accepted baseline-shift lanes (`search-rank,track-g,beir-gate`) to triage builder.
   - Schema/test updates:
     - `scripts/governance/resilience/runtime-resilience-lane-triage.v1.schema.json`
     - `scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs`

### Short Validation Evidence (Pass X)

1. `node scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs` -> pass
2. `node scripts/governance/resilience/test-build-runtime-resilience-history-readiness.mjs` -> pass
3. `node scripts/governance/resilience/test-build-release-evidence-index.mjs` -> pass
4. `node scripts/governance/resilience/test-evaluate-release-governance.mjs` -> pass
5. `powershell -File scripts/ci/test-wrapper-corpus-preflight.ps1` -> pass
6. PowerShell parse checks passed for:
   - `scripts/ci/run-perf-regression-win.ps1`
   - `scripts/bench/overnight-rag-ai-queue-win.ps1`
   - `scripts/governance/resilience/run-rr219-regression-pack-win.ps1`
7. Real perf smoke verification (non-gated path):
   - `powershell -File scripts/ci/run-perf-regression-win.ps1 -BaselineSuiteJson scripts/perf/baselines/win-smoke-no-ui.perf-suite.json -GateLevel none -SkipUiScenario -SamplingProfile smoke -ManifestOutJson tmp/track-check/perf-manifest-passx-check.json` -> pass
   - Manifest confirms identity fields now present:
     - `baseline_profile_id=win-smoke-no-ui`
     - `comparability_profile=win-smoke-no-ui`
     - `ui_required=false`, `ui_included=false`

## Implementation Pass Y (2026-02-21)

Run-backed verification of Pass X changes.

### Execution Evidence

1. **RAG corpus enrichment verified with a real run**
   - Command: `powershell -File scripts/bench/overnight-rag-ai-queue-win.ps1 -RagRuns 1 -SkipClaimD -SkipClaimA -SkipTrackG -SkipAgentLiveBattery -SkipDocsChecks`
   - Session: `tmp/agent-evidence/_summaries/overnight-rag-ai-queue-20260221-131010.json`
   - Result:
     - `gradle_exit_code=0`
     - `decision_comparable=true`
     - `decision_gate=pass`
     - `decision_non_comparable_count=0`
   - Candidate v2 now carries workload corpus identity:
     - `workload.corpus_profile_id=rag-eval-frozen.v1`
     - `workload.corpus_signature=23f6...a8bc`
     - `workload.corpus_components=[rag-corpus-frozen.v1, rag-truth-frozen.v1]`
     - `workload.corpus_tier=frozen`

2. **RR-219 regression pack now resolves real summary dir**
   - Command:
     - `node scripts/governance/resilience/build-release-evidence-index.mjs ... --out-json tmp/resilience/governance/release-evidence-index-passx-current.json`
     - `powershell -File scripts/governance/resilience/run-rr219-regression-pack-win.ps1 -EvidenceIndexPath tmp/resilience/governance/release-evidence-index-passx-current.json -SummaryDir tmp/agent-evidence/_summaries -StepTimeoutMinutes 20`
   - Report: `tmp/resilience/governance/rr219-regression-pack-report-20260221-134610.json`
   - Verified:
     - `summaryDiscoverDir=D:\\code\\JustSearch\\tmp\\agent-evidence\\_summaries` (real path, not synthetic fixture dir)
     - Pack failed only at `02e-validate-rr219-comparability-inputs` (33/34 passed), i.e. evidence mismatch rather than harness drift.

3. **Current 02e failure is true-signal input mismatch**
   - `perf` manifest used by scorecard is old `temp225` profile (`baseline_profile_id missing`, wrong corpus tier for current RR-219 gate profile).
   - `agent-battery` manifest signature mismatches active required signature.
   - These are evidence freshness/profile-alignment issues, not RR-219 runner contract failures.

## Authoritative Open Residuals (Current)

1. **Agent Phase A readiness remains open** (insufficient comparable runs in active signature window; `phaseA.graduationEligible=false`).
   `blocked_by_owner=false`; `owner_issue_ref=n/a`; `governance_effect=warn_g0|block_g1` (`AGENT_PHASE_A_NOT_GRADUATED`).

2. **Profile-locked comparable run-count + soak evidence accrual incomplete.**
   `blocked_by_owner=false`; `owner_issue_ref=n/a`; `governance_effect=warn_g0|block_g1` (`RG-018` plus soak-window criteria).

## Implementation Pass Z (2026-02-21)

Reality sync after reviewing tempdoc `225-corpus-strategy-eval-alignment.md`.

### 225 Alignment (What Changes for 219)

1. Corpus governance is now explicit and implemented under a profile registry model (`allrounder-core.v1.small`, `allrounder-core.v1.gate`, and lane-specific frozen profiles like `rag-eval-frozen.v1`).
2. RR-219 comparability policy already matches that direction (`requiredCorpusProfileId` is set per lane in `scripts/governance/resilience/rr219-comparability-policy.v1.json`).
3. Remaining RR-219 risk is no longer policy-definition ambiguity; it is evidence freshness and profile/signature alignment in produced lane manifests and same-run governance packets.

### Immediate Next Execution Order (Before Overnight Windows)

1. Generate one fresh profile-aligned manifest for each critical lane (`perf`, `search-rank`, `track-g`, `beir-gate`, `rag-eval`, `agent-battery`) using current corpus/profile policy.
2. Rebuild `release-evidence-index.v1` from those fresh manifests only (no stale/synthetic summary dirs).
3. Run one short RR-219 regression pack using that rebuilt index and real summary dir to clear strict comparability preflight (`02e`).
4. If the short pack is green, resume profile-locked accrual runs (nightly governance + weekly soak) until run-count evidence targets are met.
5. Keep owner-boundary blockers visible as `blocked_by_owner`, not as governance implementation defects, until ownership transitions are reflected in artifacts/policy maps.

### Residual State After Pass Z

1. No new contract-level blockers discovered in 219 from 225 alignment.
2. Authoritative open residuals remain the same five items listed above.
3. Tempdoc remains `active`; closure still depends on evidence accrual and manual governance decision.

### Run-Count Evidence Policy (Locked, 2026-02-21)

1. Promotion-readiness evidence is now governed by profile-locked **comparable run counts and quality/comparability thresholds**, not calendar duration.
2. The earlier "7-day window" framing is retained only as historical scheduling guidance; it is not the closure gate.
3. Weekly soak cadence remains required for long-run soak evidence diversity, but readiness closure is based on required evidence counts + pass criteria.

## Implementation Pass AA (2026-02-21)

State sync after user-approved BEN-005 takeover for execution planning.

### BEN-005 Ownership Transition (Current)

1. Execution ownership for BEN-005 is now approved to move into this track (`219`) for remediation speed.
2. Current governance artifacts still classify BEIR lane owner-boundary as `216` (`blockedByOwner=true`), so there is a temporary intent-vs-artifact lag.
3. Canonical issue ID remains `BEN-005` in `docs/reference/issues/benchmarking.md`.
4. Earlier `216` ownership references in historical pass sections remain as snapshots; this section and the authoritative residuals are the current intent.

### Remaining Uncertainties for BEN-005 Resolution

1. **CI remediation mode selection:** hard-fail embedding profile when ANN inactive vs auto-route to `stub-jaccard` vs explicit non-comparable handling.
2. **Canonical ANN-activity signal:** which source becomes authoritative for gating (`hybridFallback/vectorBlocked`, debug-score vector evidence, or runtime config/state).
3. **Runner capability floor:** whether CI runners can reliably provide ANN-active execution for embedding-profile BEIR gate runs.
4. **Baseline strategy:** whether existing embedding baselines require invalidation/regeneration after ANN-activity enforcement.
5. **Rollout strictness:** immediate blocking enforcement vs staged mode (`G0` warn-first, `G1` block) for ANN-inactive embedding evidence.

### Immediate Next Step (BEN-005 Track)

1. Lock one remediation mode and ANN-activity rule in policy.
2. Implement manifest/decision evidence so ANN-active vs ANN-fallback is machine-checkable.
3. Recompute BEIR gate evidence/baselines under the chosen mode.
4. Remove temporary owner-boundary lag by updating governance owner classification for `beir-gate` once remediation is merged.

## Implementation Pass AB (2026-02-21–2026-02-24)

Post-AA implementation slice covering closure plan phases AB, AD, and AF.

| Order | Target | Status | Output |
| ----- | ------ | ------ | ------ |
| 1 | Phase AB: Lane manifest resolver | Done | `resolve-rr219-lane-manifests.mjs` + `test-resolve-rr219-lane-manifests.mjs`; mandatory-lane enforcement wired into regression pack; `--allow-missing-lanes` escape removed from production path |
| 2 | Phase AB: Agent signature sync | Done | `rr219-comparability-policy.v1.json` updated to current signature; `test-rr219-agent-signature-policy-sync.mjs` (new) detects drift in CI |
| 3 | Phase AD: Soak readiness artifact | Done | `build-runtime-resilience-soak-readiness.mjs` (new) + `runtime-resilience-soak-readiness.v1.schema.json` (new) + `test-build-runtime-resilience-soak-readiness.mjs` (new); wired into `build-runtime-resilience-g1-readiness.mjs` and `release-evidence-index` |
| 4 | Phase AD: RG-021/RG-022 soak gates | Done | `release-resilience-gates.v1.json` extended; `runtime-resilience-reason-code-owner-map.v1.json` extended |
| 5 | Governance + weekly soak extensions | Done | `rr219-resilience-soak-weekly.yml` (new weekly soak workflow); `build-rr-u6-drift-trigger-backtest.mjs` + schema + test (new); governance DAG runner (`dag-runner.mjs` + `dag-runner.test.mjs`) replaces `run-rr219-regression-pack-win.ps1` body; grpc soak DAG runner (`dag-runner-grpc-soak.mjs` + test) |
| 6 | Phase AF: DAG runner framework | Done | ADR-0009 custom DAG engine (tempdoc 233, `near-complete`); 9 pipeline runners replace ~6,550 PS1 lines with thin wrappers; shared orchestration library in `scripts/lib/orchestration/` |
| 7 | Phase AF: Nightly workflow conversion | Done | All 7 lane slots in `rr219-resilience-governance-nightly.yml` invoke real producers (BEIR, perf, claim-a, track-g, search-rank, agent-battery, grpc-soak); one synthetic placeholder remains for RAG eval manifest (Phase AC residue — see below) |

### Pass AB State Note

1. Phase AB and Phase AD from the Remaining Closure Plan are complete.
2. Phase AF is complete via the DAG runner framework migration (tempdoc 233).
3. **Phase AC done**: synthetic RAG eval manifest replaced; `diff-rag-eval-suite.mjs` now writes a real `rag-eval-report-manifest.v1.json` via `--manifest-out`. Also fixed: nightly workflow was passing `--profile-id rag-eval-frozen.v1` (not a valid profile ID), causing the node call to fail silently on every nightly run.
4. **Phase AE done** (2026-02-25): `beir-eval-win.ps1` now computes and emits `provenance.ann.*` (7 fields); `convert-beir-metrics-v1-to-v2.mjs` propagates `ann` to v2; `suite-loader.mjs` exposes `ann_proof_status`/`ann_runtime_active` from both v1 and v2 formats; `diff-search-eval-suite.mjs` has `--require-ann-active` option; `test-diff-search-eval-suite.mjs` has ANN enforcement tests; `run-beir-gate-win.ps1` has 3 new ANN params; `dag-runner-beir-gate.mjs` passes `--require-ann-active` for `embedding-nomic-q4`; `promote-search-eval-beir-baseline-win.ps1` enforces `provenance.ann.proof_status=PASS` for embedding promotion; `build-runtime-resilience-lane-triage.mjs` and `build-runtime-resilience-history-readiness.mjs` transitioned to `beir-gate: { ownerBoundary: '219', ownerAckState: 'resolved' }`.
5. **Phase AG done** (2026-02-25): `runtime-resilience-promotion-decision.v1.schema.json` created; `validate-runtime-resilience-promotion-decision.mjs` created (AJV 2020-12 + semantic checks); `build-runtime-resilience-g1-readiness.mjs` adds `manualDecisionRequired: true` and `validatedDecisionPath` + `--decision` CLI opt; `test-build-runtime-resilience-g1-readiness.mjs` extended with AG tests.
6. All implementation phases complete. Remaining work is evidence accrual only (run-count thresholds).
7. **Phase AH done** (2026-02-25): Post-implementation quality fixes for Phase AE + AG. See Pass AH.
8. **Pass AI done** (2026-02-25): Structural audit of evidence pipeline identified 7 logic issues (F1–F7). No fixes applied; findings and confidence documented in Pass AI section.
9. **Pass AJ done** (2026-02-25): All 7 logic fixes from Pass AI implemented with test coverage. See Pass AJ section.
10. **Pass AK done** (2026-02-25): RRS-006 implemented as gate RG-023 (`lte 0`, `enforceInModes: G1/G2`, `severity: warn`). Extraction in evidence index builder, reason code owner map entry, two test suites updated. F3 closed as won't-fix (dead output, zero operational consequence).

## Tempdoc 219 Remaining Closure Plan (Implementation + Evidence Accrual)
### Summary
This plan resolves every currently open item in 219-runtime-resilience-hardening.md with a two-track model:

Implementation closure now: eliminate stale-evidence drift, complete BEN-005 remediation under strict practical ANN proof, align ownership artifacts, and harden governance wiring.
Evidence closure by run counts: accumulate profile-locked comparable runs and soak evidence until thresholds are met, then produce a decision-complete G0/G1 promotion packet.

Locked decisions applied:
- BEN-005 ANN policy: strict practical.
- Owner scope: keep search-rank/track-g in 216; move BEN-005 execution ownership to 219.
- G1 behavior: block unless waived for unresolved owner-blocked critical lanes.
- Closure basis: run-count thresholds, not calendar duration.

### Residual-to-Workstream Mapping
| Residual                                        | Resolution Path                                                                          | Status |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------- | ------ |
| 1. Policy-aligned evidence set incomplete (02e) | Pass AB: policy-locked manifest resolver + strict required-lane comparability validation | **Closed** |
| 2. Agent Phase A readiness open                 | Evidence accrual only (signature-lock done in Pass AB)                                   | **Evidence-only** |
| 3. Comparable-history + soak accrual incomplete | Soak readiness artifact done (Pass AD); evidence accumulation ongoing                    | **Evidence-only** |
| 4. BEN-005 hybrid realism open                  | Phase AE: ANN-active proof contract + BEIR gate/runtime provenance enforcement           | **Closed** |
| 5. Manual governance promotion pending          | Phase AG: explicit promotion decision artifact + validator + promotion packet            | **Closed** |

### Ordered Implementation Plan

### Phase AB: Evidence Manifest Resolution and Freshness (BEN-005) - DONE
**Goal:** Fix Residual 1 (Stale Evidence) and ensure strict comparability policy.

*   [x] **Lane Manifest Resolver:** Implement `scripts/governance/resilience/resolve-rr219-lane-manifests.mjs` to dynamically select the *newest valid* manifest per required lane from `tmp/agent-evidence/_summaries`.
*   [x] **Agent Signature Synchrony:** Update `rr219-comparability-policy.v1.json` `requiredScenarioSignature` to strictly match the canonical hash of `agent-live-battery-scenarios.v1.json`.
*   [x] **Regression Pack Orchestration:** Modify `run-rr219-regression-pack-win.ps1` to replace hardcoded scorecard references with dynamic resolution using `resolve-rr219-lane-manifests.mjs` before comparability inputs validation.
*   [x] **Enforce Mandatory Lanes:** Update `validate-rr219-comparability-inputs.mjs` to strictly fail if mandatory lanes are missing, dropping the `--allow-missing-lanes` escape hatch.

### Ordered Implementation Plan

#### Phase AB — Fix Evidence Freshness and Strict Comparability (02e) - DONE
Add lane-manifest resolver.
Files:
- resolve-rr219-lane-manifests.mjs (new)
- test-resolve-rr219-lane-manifests.mjs (new)

Behavior:
Discover manifests in summary dir by lane pattern.
Validate each candidate with existing lane validators from rr219-comparability-lib.mjs.
Select newest policy-valid manifest per required lane.
Emit rr219-lane-manifest-selection.v1.json with selected paths + rejection reasons.

Use resolver inside regression pack.
Files:
- run-rr219-regression-pack-win.ps1

Changes:
Add resolver step before 02e.
Feed selected manifest paths to validate-rr219-comparability-inputs.mjs.
Remove --allow-missing-lanes for production pack path.
Include selected-manifest artifact path in pack report.

Make beir-gate lane mandatory in 02e.
Files:
- run-rr219-regression-pack-win.ps1
- validate-rr219-comparability-inputs.mjs (only if arg wiring needed)

Change:
Pass --beir-manifest from resolver output.
Align agent scenario signature policy.
Files:
- rr219-comparability-policy.v1.json
- test-rr219-agent-signature-policy-sync.mjs (new)

Change:
Update requiredScenarioSignature to current signature.
Add sync test that recomputes signature from agent-live-battery-scenarios.v1.json using same canonicalization rules and fails on drift.

Exit gate:
- 02e-validate-rr219-comparability-inputs passes in regression pack with required lanes present.
- No stale scorecard manifest path can silently pass if policy-valid newer evidence exists.
- Agent policy signature drift is CI-detected immediately.

#### Phase AC — Finalize Policy-Version and Same-Run Evidence Consistency - DONE

> **Status (2026-02-25):** Policy-version consistency is done (RG-019 wired in Pass U/V). Synthetic RAG eval manifest removed; `diff-rag-eval-suite.mjs` now accepts `--manifest-out` and emits a real `rag-eval-report-manifest.v1.json` with all required fields. Nightly workflow updated to use `--profile-id embedding-cross-encoder-q24 --manifest-out`. Also fixed a latent bug: workflow was passing `--profile-id rag-eval-frozen.v1` (not a valid profile ID), causing the node call to fail silently on every nightly run.

Keep active policy source authoritative.
Files:
- build-release-evidence-index.mjs
- run-rr219-regression-pack-win.ps1
- test-build-release-evidence-index.mjs

Change:
Continue defaulting policyVersionRef from --policy-path.
Ensure final same-run index always uses active policy path.
Keep mismatch fixtures explicit via override only.

Nightly path must produce real, not synthetic, evidence.
Files:
- rr219-resilience-governance-nightly.yml

Change:
Remove synthetic JSON generation block.
Build evidence from real lane outputs + same-run final index.

Exit gate:
- Default nightly runs produce meta.policyVersionMatch=true.
- RG-019 mismatch remains testable via fixture path only.

#### Phase AD — Soak Readiness as First-Class Evidence (Reuse Existing Soak Contracts) - DONE
Add soak-readiness builder.
Files:
- build-runtime-resilience-soak-readiness.mjs (new)
- runtime-resilience-soak-readiness.v1.schema.json (new)
- test-build-runtime-resilience-soak-readiness.mjs (new)

Inputs:
soak result/report/decision artifacts already produced by existing soak runner.

Outputs:
shortComparableSuccessCount, longComparableSuccessCount, verdict, reasons.

Thresholds:
- short smoke comparable success >=2
- long soak comparable success >=1

Wire soak-readiness into governance.
Files:
- release-evidence-index.v1.schema.json
- release-resilience-gates.v1.json
- runtime-resilience-reason-code-owner-map.v1.json
- evaluate-release-governance.mjs
- build-runtime-resilience-g1-readiness.mjs

Add gates:
- RG-021: soak-readiness evidence present (warn G0, block G1/G2)
- RG-022: soak-readiness verdict pass (warn G0, block G1/G2)

Exit gate:
- Soak evidence accrual is machine-readable and gate-consumed.
- No separate parallel soak schema is introduced.

#### Phase AE — BEN-005 Remediation (Strict Practical ANN Proof) - DONE
Add ANN-proof contract to BEIR runtime evidence.
Files:
- beir-eval-win.ps1
- convert-beir-metrics-v1-to-v2.mjs
- suite-loader.mjs

Add provenance fields:
- provenance.ann.expected_active
- provenance.ann.runtime_active
- provenance.ann.hybrid_effective_rate
- provenance.ann.vector_debug_evidence_rate
- provenance.ann.ann_disabled_reason_rate
- provenance.ann.proof_status
- provenance.ann.proof_reasons[]

Enforce ANN proof in BEIR gate wrapper for embedding profile.
Files:
- run-beir-gate-win.ps1
- test-beir-baseline-profile-selection.ps1 (extend)

Add params:
- -RequireAnnActiveForEmbedding (default true)
- -MinHybridEffectiveRate (default 0.95)
- -RequireVectorEvidence (default true)

Runtime rule for embedding-nomic-q4:
- ANN config expected active.
- No ANN-disabled fallback reason occurrences.
- Hybrid effective rate >= 0.95.
- Vector evidence present.
- Otherwise mark non-comparable and fail gate path.

Enforce ANN comparability in diff and baseline promotion.
Files:
- diff-search-eval-suite.mjs
- test-diff-search-eval-suite.mjs
- promote-search-eval-beir-baseline-win.ps1

Add diff option:
--require-ann-active (used by BEIR gate wrapper for embedding profile).

Promotion requirement:
embedding baseline must have ANN runtime proof active.

Owner-boundary transition for BEN-005.
Files:
- build-runtime-resilience-lane-triage.mjs
- build-runtime-resilience-history-readiness.mjs
- benchmarking.md
- 219-runtime-resilience-hardening.md

Change:
beir-gate owner boundary becomes 219 (BEN-005 execution takeover reflected in artifacts).

Exit gate:
- Embedding-profile BEIR run cannot pass as comparable without ANN runtime proof.
- BEN-005 is no longer represented as an upstream owner-block in 219 artifacts.

#### Phase AF — Real-Lane Nightly Governance Pipeline - DONE (via tempdoc 233)
Convert nightly workflow to real lane producers.
File:
- rr219-resilience-governance-nightly.yml

Sequence:
1. produce lane manifests using profile-locked wrappers (claim-a, search-rank, track-g, perf, beir-gate, rag-eval, agent-battery)
2. build scorecard
3. build release evidence index from same-run artifacts
4. run RR-219 pack
5. run short soak

Keep heavy-run non-overlap.
Change: serialize heavy lanes and keep single heavy flow per job.

Exit gate:
- Nightly artifacts are fully real and policy-locked.
- Synthetic placeholder inputs are removed.

#### Phase AG — Manual Governance Promotion Artifact (Residual #5 Closure Mechanism) - DONE
Add explicit manual promotion decision artifact contract.
Files:
- runtime-resilience-promotion-decision.v1.schema.json (new)
- validate-runtime-resilience-promotion-decision.mjs (new)
- release-resilience-gates.v1.md

Fields:
candidate/evidence references, mode transition (G0->G1), approver, decision timestamp, waiver refs, rollback triggers.

Extend promotion packet builder output.
Files:
- build-runtime-resilience-g1-readiness.mjs
- 219-runtime-resilience-hardening.md

Change:
include explicit manualDecisionRequired=true and validated-decision path if present.

Exit gate:
- Manual promotion is no longer ad hoc; it has a versioned artifact and validator.
- Residual #5 is procedural-only, not tooling-ambiguous.

### Evidence Accrual Execution Plan (Post-Implementation)
Use run-count closure criteria from rr219-comparability-policy.v1.json:

- Perf: minComparableRuns=8
- BEIR gate (embedding profile): minComparableRuns=4 with ANN proof active
- RAG eval: minComparableRuns=8
- Agent battery: minComparableRuns=14 (same signature + phase)
- Claim-A: minComparableRuns=10
- Search-rank/Track-g: keep owner boundary with 216; unresolved quality remains blocked_by_owner and is G1-blocking unless waived.

Daily loop:
1. Run profile-locked lanes.
2. Rebuild same-run evidence index.
3. Recompute triage/history/budget/conformance/soak-readiness.
4. Evaluate G0 official + G1 shadow.
5. Emit persistent handoff updates for failing lanes.

Promotion packet creation trigger:
1. All critical lanes meet history/comparability minima or have explicit waivers.
2. Soak-readiness pass.
3. Conformance pass.
4. No policy-version drift.

### Important Changes/Additions to Public APIs, Interfaces, Types
- New artifact: rr219-lane-manifest-selection.v1.json.
- New artifact: runtime-resilience-soak-readiness.v1.json.
- New artifact contract: runtime-resilience-promotion-decision.v1.
- release-evidence-index.v1 additive node: evidence.runtimeSoakReadiness.
- release-resilience-gates.v1 additive gates: RG-021, RG-022.
- run-beir-gate-win.ps1 additive ANN-proof parameters.
- diff-search-eval-suite.mjs additive option: --require-ann-active.
- BEIR provenance additive ANN fields in v1 metrics and v2 converted suite payloads.
- No runtime service endpoint removals or breaking wire changes.

### Test Cases and Scenarios
- Manifest resolver correctness: newest policy-valid manifest chosen per lane; stale invalid manifests rejected.
- Strict 02e: required lane missing or invalid causes failure; beir lane no longer silently optional.
- Agent signature drift test: policy signature mismatch vs scenarios file fails deterministically.
- BEN-005 ANN-proof pass/fail:
  - embedding profile with ANN-active evidence -> comparable allowed
  - embedding profile with ANN-disabled fallback -> non-comparable fail
  - embedding profile missing vector evidence -> non-comparable fail
- Diff ANN option: --require-ann-active rejects mismatched/missing ANN provenance.
- Soak readiness: thresholds met -> pass; missing/insufficient -> warn G0, block G1.
- RG-019 consistency: matching policy version passes; mismatch warns in G0, blocks in G1.
- Owner-block semantics: unresolved 216-owned critical lanes keep RG-020 fail in G1 unless waiver exists.
- Promotion decision validation: malformed or incomplete manual decision artifact fails schema validator.

### Assumptions and Defaults
- 219 stays active until evidence accrual + manual promotion decision are complete.
- search-rank and track-g remain 216 ownership; BEN-005 execution is moved to 219.
- G1/G2 remain blocked by unresolved owner-blocked critical lanes unless explicitly waived.
- BEN-005 ANN strictness uses strict practical thresholds:
  - profile expects embedding ANN active
  - no ANN-disabled fallback reason occurrences
  - hybrid effective rate >= 0.95
  - vector evidence present
- Closure is run-count-driven; calendar windows are scheduling guidance only.
- Heavy-run overlap remains disallowed during accrual execution.

## Implementation Pass AH (2026-02-25)

Post-implementation quality pass for Phase AE and Phase AG. Critical-analysis-driven fixes; no new features.

| Order | Fix | Status | Detail |
| ----- | --- | ------ | ------ |
| 1 | Dead CLI options removed | Done | `min-hybrid-effective-rate` and `require-vector-evidence` removed from `dag-runner-beir-gate.mjs` and `run-beir-gate-win.ps1`; these were accepted but never enforced (threshold hardcoded in `beir-eval-win.ps1`). |
| 2 | Missing else-branch in ANN proof block | Done | `beir-eval-win.ps1`: added `elseif ($profileEmbeddingEnabled)` branch for embedding-without-hybrid case; `proof_status=FAIL`, reason="embedding profile selected but hybrid mode results absent". |
| 3 | Null rate string interpolation | Done | `beir-eval-win.ps1`: explicit `$rateStr` null-to-string conversion so reason reads "actual: null" not "actual: "; removed duplicate "no vector evidence" reason overlapping with rate check. |
| 4 | AJV strict:false missing | Done | `validate-runtime-resilience-promotion-decision.mjs`: added `strict: false` to AJV constructor, matching codebase pattern. |
| 5 | Silent error suppression in --decision | Done | `build-runtime-resilience-g1-readiness.mjs`: added `process.stderr.write` WARNING in both the structural-check-failure path and the catch block. |
| 6 | Broken PS1 test assertion | Done | `test-beir-baseline-profile-selection.ps1`: `Resolve-BeirBaselinePath` string-match replaced with `dag-runner-beir-gate\.mjs` check (accurate since Phase AF). |
| 7 | Null ann_proof_status test gap | Done | `test-diff-search-eval-suite.mjs`: added test verifying null `ann_proof_status` on embedding profile with `--require-ann-active` → `NON_COMPARABLE`. |
| 8 | BEN-005 benchmarking.md stale | Done | `docs/reference/issues/benchmarking.md`: BEN-005 status set to closed; owner updated to 219; implementation note added. |
| 9 | Converter ann coverage zero | Done | `test-convert-beir-metrics-v1-to-v2.mjs`: added 3 ann round-trip cases (full block, absent→null, partial block with null numeric fields). |
| 10 | Validator test file absent | Done | `test-validate-runtime-resilience-promotion-decision.mjs` (new): 7 test cases covering happy path, missing field, same-mode transition, future timestamp, invalid kind, extra property, --out-json. |

All verification runs passed: `test-diff-search-eval-suite.mjs`, `test-convert-beir-metrics-v1-to-v2.mjs`, `test-validate-runtime-resilience-promotion-decision.mjs`, `test-build-runtime-resilience-g1-readiness.mjs`, `test-beir-baseline-profile-selection.ps1`.

## Implementation Pass AI (2026-02-25)

Evidence output pipeline audit. Structural audit of `build-runtime-resilience-g1-readiness.mjs` and related governance scripts identified 7 logic issues. No fixes applied yet; findings and fix-confidence documented here for planning.

### Findings

**F1 — `recommendation` blind to waivers (Medium)**

`scripts/governance/resilience/build-runtime-resilience-g1-readiness.mjs:441`

```js
const recommendation = criteriaPass && g1NoWaivers.decision.blocking === false ? 'pilot_g1' : 'stay_g0';
```

`recommendation` uses the **no-waivers** evaluation path (`g1NoWaivers`). If gates are blocking without waivers but unblocked with waivers, `recommendation` is always `'stay_g0'` regardless of the waiver-adjusted result. The `G1WithWaivers` decision block is emitted in the output but never fed back into the recommendation.

*Fix direction:* Use `g1WithWaivers.decision.blocking === false` instead of `g1NoWaivers`. Or emit two fields: `recommendationWithoutWaivers` and `recommendationWithWaivers`.

*Fix confidence:* **Medium.** The fix is technically clear but the design intent is ambiguous — there is a defensible argument that `recommendation` should reflect the unwaived posture as the "ideal" signal. The current behaviour means the waiver mechanism never produces a `'pilot_g1'` recommendation, which undermines its purpose. Needs user confirmation on intended semantics before implementing.

---

**F2 — Criteria stricter than gates (Medium)**

Two sub-issues in `evaluateCriteria()` (`build-runtime-resilience-g1-readiness.mjs:135,138`):

**(2a)** `historyReadinessPass: historyVerdict === 'pass' && criticalLanesReady === true`

`overallVerdict` in `build-runtime-resilience-history-readiness.mjs` is computed across **all** lanes (critical and non-critical). Gate RG-013 only checks `criticalLanesReady === true`. If non-critical lanes are failing, `overallVerdict` can be `'fail'` while `criticalLanesReady === true` — criteria blocks pilot even though the gate allows it.

**(2b)** `controlLoopConformancePass: conformanceVerdict === 'pass'`

Gate RG-015: `op: in, expected: ['pass', 'warn'], severity: warn`. A `'warn'` conformance verdict passes RG-015 but fails the criterion. The criterion is stricter than the gate it mirrors. Note: `buildPreflight()` at line 269 also hard-rejects non-pass conformance (marks artifact as `invalid`), so the same disagreement appears twice.

*Fix direction:* Align criteria to gate semantics: (2a) drop `historyVerdict === 'pass'` from `historyReadinessPass` or rename it to only check `criticalLanesReady`; (2b) change to `['pass', 'warn'].includes(conformanceVerdict)` and fix the matching preflight check at line 269.

*Fix confidence:* **Medium.** The gate definitions are authoritative, so aligning criteria to them is correct in principle. However, (2a) may be intentionally more conservative (summarising overall health, not just critical gates). Both sub-issues require confirming whether the intended semantics is "match the gate exactly" or "be stricter in the readiness report." Recommend confirming (2b) first as it is more clearly a mismatch between a severity:warn gate and a strict criterion.

---

**F3 — `blockedByOwner` semantics diverge (Medium)**

Two scripts answer the same conceptual question but with different logic:

- `build-runtime-resilience-history-readiness.mjs`: `blockedByOwner` fires on **any non-pass verdict** for critical 216-owned lanes with unresolved ack state.
- `build-runtime-resilience-lane-triage.mjs`: `blockedByOwner` fires only on `classification === 'persistent_regression'`. Misses `non_comparable`, `intermittent_regression`, and `infra_failure` classifications.

Consumers reading both outputs may get contradictory `blockedByOwner` signals for the same lane and candidate.

*Fix direction:* Align on one formula. Likely the history-readiness formula (verdict-based) is more complete, but lane-triage may intentionally scope to persistent regressions only. Need to decide which is authoritative per context.

*Fix confidence:* **Low.** This is a semantic design question. Both formulas are internally consistent in their own scripts; whether they should match is a product decision. Do not fix without explicit direction on which semantics is intended for each consumer.

---

**F4 — Expired waiver filter includes warn-severity gates (Low-Medium)**

`build-runtime-resilience-g1-readiness.mjs:126`

```js
const gateStillFailing = (g1DecisionWithoutWaivers.gates || []).some(
  (gate) => gate.gateId === gateId && gate.status === 'fail'
);
```

The filter that identifies "expired relevant waivers" matches **any** failing gate, including warn-severity gates. A waiver written for a warn gate (which is never blocking) that has expired would cause `noExpiredUnresolvedBlockingWaivers: false`, blocking the readiness check even though no blocking gate was waived.

*Fix direction:* Add `&& gate.severity === 'blocking'` (or equivalent) to the `.some()` predicate. Need to verify the exact field name for severity in the gate decision objects first.

*Fix confidence:* **Medium-High.** The bug is clear in principle. Implementation confidence depends on verifying the gate object's severity field name in the output schema — straightforward to check, then fix.

---

**F5 — `g1WaivedOut` absent from preflight completeness (Low)**

`buildPreflight()` validates: `finalEvidenceIndex`, `runtimeBudgetSnapshot`, `controlLoopConformance`, `runtimeHistoryReadiness`, `runtimeSoakReadiness`, `regressionPackReport`, `governanceDecisionG0`, `governanceDecisionG1Shadow`. It does **not** include the G1-with-waivers decision artifact (`release-governance-decision-g1-with-waivers.json`), even though that artifact is produced in the same run and referenced in `decisions.G1WithWaivers`.

A stale or corrupt with-waivers artifact will not be caught by the preflight completeness check.

*Fix direction:* Add a `governanceDecisionG1WithWaivers` row to `buildPreflight()` pointing at `g1WaivedOut`. Pass `g1WaivedOut` as a parameter to `buildPreflight()`.

*Fix confidence:* **High.** Pure additive change. No design ambiguity. Safe to implement without further confirmation.

---

**F6 — `rollbackTriggers` text overstates conformance requirement (Low)**

`build-runtime-resilience-g1-readiness.mjs:486`

```js
'Control-loop conformance verdict != pass.',
```

Gate RG-015 has `severity: warn` and allows `['pass', 'warn']`. A conformance `'warn'` verdict will NOT cause a blocking gate failure and will NOT trigger rollback — but the text implies it would. This can mislead operators reading the rollback trigger list.

*Fix direction:* Change text to `'Control-loop conformance verdict becomes fail (warn is tolerated).'` or similar.

*Fix confidence:* **Very High.** Text-only change, zero logic impact. Safe to fix immediately.

---

**F7 — `overallBudgetState` stored but never gate-checked (Low)**

`build-release-evidence-index.mjs:305` stores `evidence.runtimeResilienceBudgetSnapshot.overallBudgetState`. The budget snapshot artifact is included in the preflight check (so absence is caught), but `overallBudgetState` (`'green'`/`'amber'`/`'red'`) is never read by `evaluateCriteria()` and is not part of `criteriaPass`. A `'red'` budget state (all SLOs measuring, one or more in `'red'`) has no gate effect.

*Fix direction:* Add `noCriticalBudgetRed: overallBudgetState !== 'red'` (or similar) to `evaluateCriteria()` and include in `criteriaPass`. Alternatively, emit `overallBudgetState` in the readiness doc output for observability.

*Fix confidence:* **Low.** Whether budget state should gate-block is a design question. The current behaviour may be intentional — budget state is informational while formal SLO gate checks happen elsewhere. Do not fix without explicit user direction.

---

### Summary Table

| ID | Severity | File | Fix Confidence | Notes |
|----|----------|------|----------------|-------|
| F1 | Medium | `build-runtime-resilience-g1-readiness.mjs:441` | Medium | Design question — confirm waiver semantics first |
| F2a | Medium | `build-runtime-resilience-g1-readiness.mjs:135` | Medium | Loosen `historyReadinessPass` or confirm conservatism intent |
| F2b | Medium | `build-runtime-resilience-g1-readiness.mjs:138,269` | Medium | Align criteria+preflight to RG-015 warn-allowed semantics |
| F3 | Medium | `build-runtime-resilience-history-readiness.mjs`, `build-runtime-resilience-lane-triage.mjs` | Low | **Resolved (AJ/AK):** AJ added CRITICAL_LANES check to lane-triage (code change applied); AK found lane-triage's `blockedByOwner` is dead output — never read downstream. Semantic divergence closed as won't-fix. |
| F4 | Low-Medium | `build-runtime-resilience-g1-readiness.mjs:126` | Medium-High | Verify gate severity field name, then add filter predicate |
| F5 | Low | `build-runtime-resilience-g1-readiness.mjs` (buildPreflight) | High | Safe additive check; implement without further input |
| F6 | Low | `build-runtime-resilience-g1-readiness.mjs:486` | Very High | Text-only; safe to fix immediately |
| F7 | Low | `build-release-evidence-index.mjs:305` + g1-readiness | Low | Observability vs gating design question; needs direction |

Recommended immediate fixes (no user input needed): **F5** (additive preflight check), **F6** (text accuracy).
Fixes requiring design confirmation before implementing: **F1**, **F2a/b**, **F3**, **F7**.
Fix requiring one field-name verification then safe to implement: **F4**.

---

## Pass AJ — Evidence Pipeline Logic Fixes (F1–F7) (2026-02-25)

Implemented all 7 logic fixes identified in Pass AI, in execution order.

### Changes Made

**`scripts/governance/resilience/build-runtime-resilience-g1-readiness.mjs`**

- **F6 (text):** `rollbackTriggers` entry updated from `'Control-loop conformance verdict != pass.'` to `'Control-loop conformance verdict becomes fail (warn is tolerated per RG-015).'`

- **F4 (expired waiver filter):** Added `&& gate.severity === 'block'` to the expired waiver predicate. Field name confirmed as `'block'` (not `'blocking'`) from `evaluate-release-governance.mjs` which sets `effectiveSeverity = enforceInModes.includes(mode) ? 'block' : policySeverity`. The filter now only flags expired waivers for gates whose effective severity is blocking in G1 mode.

- **F7 (budget state observability):** Added `overallBudgetState` extraction (`evidence.runtimeResilienceBudgetSnapshot.overallBudgetState`) and included it as an informational field in the `evaluateCriteria()` return value. Not included in `criteriaPass` — observability only.

- **F2a (`historyReadinessPass`):** Removed the `historyVerdict === 'pass'` conjunct. Criterion is now just `criticalLanesReady === true`, aligning with what RG-018 actually gates.

- **F2b (`controlLoopConformancePass` + preflight):** Criteria changed from `conformanceVerdict === 'pass'` to `conformanceVerdict === 'pass' || conformanceVerdict === 'warn'`. Preflight check updated from `!== 'pass'` to `!['pass', 'warn'].includes(...)`. Both changes align with RG-015 (`op: in, expected: ['pass', 'warn']`).

- **F5 (buildPreflight G1WithWaivers):** Added `g1WaivedDecisionPath` parameter to `buildPreflight()` and a new `governanceDecisionG1WithWaivers` artifact row (kind: `release-governance-decision.v1`). Updated call site to pass `g1WaivedOut`.

- **F1 (`recommendation`):** Changed `g1NoWaivers.decision.blocking === false` to `g1WithWaivers.decision.blocking === false`. The recommendation now uses the waiver-adjusted blocking flag, making waivers effective for the `'pilot_g1'` outcome.

**`scripts/governance/resilience/build-runtime-resilience-lane-triage.mjs`**

- **F3 (blockedByOwner criticality):** Added `CRITICAL_LANES` constant (`Set` of 6 critical lanes matching history-readiness semantics). Added `CRITICAL_LANES.has(lane) &&` as the first condition in `blockedByOwner`. Updated `ownerBlockedCriticalCount` post-filter to use `CRITICAL_LANES.has(row.lane)` explicitly (previously used inline array). The `ownerBlockedCriticalCount` post-filter is now redundant with the `blockedByOwner` computation but kept for clarity.

  > **Post-fact note (Pass AK):** Subsequent analysis found that `blockedByOwner` in lane-triage is never read by `build-runtime-resilience-persistent-handoff.mjs` (which filters by `classification`) or by any gate (which reads exclusively from history-readiness's `ownerBlockedCriticalCount`). The CRITICAL_LANES check above is in the codebase but has no operational effect. The remaining semantic divergence between lane-triage and history-readiness `blockedByOwner` formulas was closed as won't-fix in Pass AK.

### Test Cases Added

**`scripts/governance/resilience/test-build-runtime-resilience-g1-readiness.mjs`**

- Updated `makeEvidence()` to support `options.historyOverallVerdict` (independent of criticalLanesReady) and `options.budgetState`.
- Added `makePolicyWithBlockingGate()` helper: policy with RG-T1 (warn) + RG-BLOCK (block, checks `evidence.agentScorecard.phaseA.graduationEligible`). Used by F1 test.
- Added `makeF4Policy()` helper: policy with `RG-WARN-G2` gate (`severity: 'warn'`, `enforceInModes: ['G2']` — so effectiveSeverity stays `'warn'` in G1 mode). Used by F4 test.
- Added `makeWaivers()` helper: creates a valid `release-governance-waivers.v1` doc with all required fields.

New test scenarios:
- **F1:** Block gate fails, valid (non-expired) waiver present → `recommendation='pilot_g1'`, `G1WithWaivers.blocking=false`, `G1WithoutWaivers.blocking=true`.
- **F2a:** `criticalLanesReady=true` with `historyOverallVerdict='warn'` → `historyReadinessPass=true`, `recommendation='pilot_g1'`.
- **F2b:** `conformanceVerdict='warn'` with fresh preflight artifacts + `--require-complete-preflight` → exit 0, `controlLoopConformancePass=true`, conformance not in `preflight.invalidArtifacts`, `preflight.complete=true`.
- **F4:** Expired waiver for `RG-WARN-G2` (warn-severity in G1 mode) → `noExpiredUnresolvedBlockingWaivers=true`, `expiredRelevantWaivers.length=0`.
- **F5:** `strictPassDoc.preflight.requiredArtifacts` includes `'governanceDecisionG1WithWaivers'`.
- **F7:** `criteria.overallBudgetState='green'` when evidence has `overallBudgetState: 'green'`.
- **F7b:** `criteria.overallBudgetState=null` when budget state absent.

**`scripts/governance/resilience/test-build-runtime-resilience-lane-triage.mjs`**

- Added F3 block verifying `track-g` (critical + 216 + open + persistent_regression) has `blockedByOwner=true`, and `beir-gate` (critical + ownerAckState='resolved') has `blockedByOwner=false`.
- Note: All lanes in `laneOrder` are in `CRITICAL_LANES`, so the non-critical lane scenario (e.g., `claim-a`) cannot be exercised via CLI integration test. The criticality check is code-verified and covered by the negative `beir-gate` case.

### Implementation Notes

- **F4 field name:** Gate severity in `evaluate-release-governance.mjs` output is `effectiveSeverity` (stored as `severity`): `'block'` when `enforceInModes.includes(mode)`, else `policySeverity`. The fix correctly uses `gate.severity === 'block'` (not `'blocking'`).
- **F2b test isolation:** F2b uses a fresh `preflightArtifactsF2b` set because the stale-artifact test earlier in the suite mutates the shared `preflightArtifacts.history` timestamp to 120 minutes old, which would fail the `--max-artifact-age-minutes 60` check.
- **F7 design note:** `overallBudgetState` is surfaced as informational only. Including it in `criteriaPass` would require a gate policy change — not done without explicit direction.

### Verification

Both test suites pass:
```
build-runtime-resilience-g1-readiness tests: PASS
build-runtime-resilience-lane-triage tests: PASS
```

---

## Pass AK — RRS-006 Evidence Integrity Gate (2026-02-25)

Implemented RRS-006 as gate RG-023 in the governance pipeline.

### What Was Done

**F3 (lane-triage `blockedByOwner` divergence) — semantic divergence closed as won't-fix:**
Pass AJ applied a CRITICAL_LANES check to lane-triage's `blockedByOwner` (see Pass AJ notes). Analysis
in this pass found that `blockedByOwner` in lane-triage is never read by
`build-runtime-resilience-persistent-handoff.mjs` (which filters by `classification` only) or by any
gate (which reads exclusively from history-readiness's `ownerBlockedCriticalCount`). The field is
dead output; the AJ code change has no operational effect. The remaining semantic divergence between
the two scripts' `blockedByOwner` formulas (lane-triage: `persistent_regression` only; history-readiness:
any non-pass verdict for 216-owned critical lanes) has zero downstream consequence and is closed as
won't-fix.

**RRS-006 — implemented as RG-023:**

- **`build-release-evidence-index.mjs`**: Added `evidenceIntegrityContradictionCount` to the
  `runtimeResilienceBudgetSnapshot` extraction. Absent block emits `null` sentinel (gate passes via
  `lte` semantics: `Number(null) = 0 ≤ 0`). Present block extracts
  `doc?.summary?.evidenceIntegrity?.contradictionCount`.
- **`release-resilience-gates.v1.json`**: Added RG-023 — `op: lte`, `expected: 0`,
  `severity: warn`, `enforceInModes: ['G1','G2']`, `reasonCode: EVIDENCE_INTEGRITY_CONTRADICTION`,
  `allowWaiver: true`. Gate is non-blocking in G0 (only warns); becomes blocking in G1/G2 mode.
- **`runtime-resilience-reason-code-owner-map.v1.json`**: Added `EVIDENCE_INTEGRITY_CONTRADICTION`
  entry — `ownerRole: runtime-governance`, `actionType: investigate_evidence_integrity_contradiction`,
  `targetSlaDays: 2`.
- **`test-build-release-evidence-index.mjs`**: Added `runtimeBudgetSnapshotPath` option to
  `runBuilder()`. Two new tests: present case (`contradictionCount: 2` → extracted correctly) and
  absent case (no snapshot → field is `null`).
- **`test-build-runtime-resilience-g1-readiness.mjs`**: Added `contradictionCount` option to
  `makeEvidence()`. Added `makePolicyWithContradictionGate()` helper. Two new gate behavior tests:
  `contradictionCount: 1` → `blocking=true`, `recommendation='stay_g0'`; `contradictionCount: null`
  → `blocking=false`, `recommendation='pilot_g1'`.

### Why `op: lte` Not `equals`

`opPass('equals', null, 0)` = `null === 0` = `false` (false positive when snapshot absent).
`opPass('lte', null, 0)` = `Number(null) = 0`, `0 ≤ 0 = true` (gate passes correctly).
The `null` sentinel in the absent block ensures this invariant even when the evidence field
is explicitly set to `null`.

### Verification

```
build-release-evidence-index tests: PASS
build-runtime-resilience-g1-readiness tests: PASS
build-runtime-resilience-lane-triage tests: PASS
```

---

## Implementation Pass AL — Corpus Governance Quickcheck CI Fixes (2026-02-26)

Fixes to the Corpus Governance Quickcheck CI suite that had been failing due to DAG runner
migration artifacts and an opts-passing bug in two pipeline runners. Four CI runs required
to resolve cascading failures; final run 22417981224 passed both Corpus Governance Quickcheck
and Fast Build & Test.

### Changes Made

**`scripts/ci/test-wrapper-corpus-preflight.ps1`**

- **Track-g pattern fix:** Changed `ExpectedPattern` from `"Lane corpus selection\s+failed"` to
  `"resolve-corpus-selection"`. Root cause: track-g migrated to DAG runner where corpus selection
  is a subprocess step. `step-runner.mjs` uses `stdio: ['ignore', 'pipe', 'pipe']` — subprocess
  output goes to log files; only the step summary line
  `[track-g] 01-resolve-corpus-selection ... FAILED` reaches the terminal via
  `createProgressTracker` in `format-utils.mjs`. The old string-match pattern never fired because
  the step error is in a log file, not terminal stderr.
- **`-RequireLatencyRunRepeats 1` added** to track-g test invocation (prior session fix).
- **Perf pattern retained** as `"Lane corpus selection\s+failed"`. Perf calls
  `resolveLaneCorpusSelection()` directly in `main()` without try/catch — errors propagate to
  `main().catch()` which emits `[perf-reg] Fatal: Lane corpus selection failed...`.

**`scripts/ci/dag-runner-search-eval-rank-report.mjs`**

Fixed two bugs in corpus selection when `corpusProfileId` is explicitly provided:

1. **Wrong call signature:** `corpusProfileId` (a string) was passed as the `opts` arg to
   `resolveLaneCorpusSelection()`. The function signature is `(repoRoot, lane, opts = {})` where
   `opts.requestedProfileId` carries the profile. The profile ID was silently dropped, causing the
   full pipeline to run against the default corpus instead of failing.
2. **Error swallowed:** `catch { /* best effort */ }` wrapped the entire corpus selection block,
   including the explicit-profile case. Errors were never propagated even with an invalid profile.

Fix: when `corpusProfileId` is set, pass `{ requestedProfileId: corpusProfileId, gated: true }` and
propagate errors. Preserve best-effort behaviour only for the no-profile-provided case.

**`scripts/ci/dag-runner-beir-gate.mjs`**

Same two bugs as search-eval-rank. Identical fix applied with `'beir-gate'` lane name.

**`scripts/ci/test-workflow-corpus-profile-wiring.ps1`**

Updated the expected pattern for 4 workflows (track-g, search-eval-rank, beir-eval-gate,
perf-regression) from `-CorpusProfileId` (PS1 wrapper parameter syntax) to
`--corpus-profile-id` (node CLI flag syntax). These workflows now call DAG runners directly via
`node scripts/ci/dag-runner-*.mjs --corpus-profile-id ${{ inputs.corpus_profile_id }}` rather
than through PS1 wrappers. Claim-a and agent-live-eval-nightly still use PS1 wrappers and retain
`-CorpusProfileId`.

### CI Validation

| Run | Result | Failure reason |
|-----|--------|----------------|
| 22417134970 | FAIL | Track-g pattern fixed but search-rank ran full pipeline (corpus selection bug) |
| 22417512161 | FAIL | Perf pattern changed incorrectly to `resolve-corpus-and-baseline` (reverted) |
| 22417706596 | FAIL | Wiring test expected `-CorpusProfileId` but DAG-runner workflows use `--corpus-profile-id` |
| 22417981224 | **PASS** | Corpus Governance Quickcheck + Fast Build & Test both passed |

### Corpus Selection Failure Paths (Reference)

| Wrapper | Error propagation path | `ExpectedPattern` |
|---------|------------------------|-------------------|
| claim-a | PS1 `Resolve-LaneCorpusSelection` throws | `Lane corpus selection\s+failed` |
| track-g | DAG step subprocess → step summary line (`createProgressTracker`) | `resolve-corpus-selection` |
| search-rank | `resolveLaneCorpusSelection()` in `main()`, now propagated | `Lane corpus selection\s+failed` |
| beir-gate | Same as search-rank | `Lane corpus selection\s+failed` |
| perf | `resolveLaneCorpusSelection()` in `main()` without try/catch → `main().catch()` Fatal | `Lane corpus selection\s+failed` |
| agent-battery | PS1 wrapper throws | `corpus selection\s+failed` |

---

## Implementation Pass AM — Nightly CI Investigation and Workflow Fixes (2026-02-26)

### Context

After Pass AL, the first nightly run (`22430885164`) produced multiple failures. The fixes from AL
had not been pushed to remote before the run was triggered; a second run (`22448699777`) also used
pre-fix code. After `git push`, a third run (`22450553285`) executed the fixes from `ac45ac53`.

This pass investigated all failures, applied two additional fixes, and documented the remaining
operational/boundary issues that are outside 219's scope.

### Changes Made

**`.github/workflows/rr219-resilience-governance-nightly.yml`** (commit `ac45ac53`, `d2c623b9`):

1. **PS1 parse error fixed** (`ac45ac53`): The "Publish summary" step had `` `" `` (PS escape
   for literal `"`) where ` `` ` (literal backtick) was intended. Changed all 4 occurrences.
   **Confirmed working** in run `22450553285` — "Publish summary" now passes.

2. **Perf corpus profile intent made explicit** (`ac45ac53`): Added
   `--corpus-profile-id allrounder-core.v1.gate` to the perf regression step. The lane corpus
   policy still requires `allrounder-core.v1.small`, causing a policy conflict regardless of
   whether the flag is present — both approaches produce the same validation failure. The flag
   makes the nightly's intent explicit but does not resolve the underlying conflict.

3. **RAG eval: wrong module fixed** (`ac45ac53`): Changed `:modules:app-inference:test` →
   `:modules:system-tests:integrationTest`. `RagQualityEvalTest` was moved to system-tests during
   tempdoc 216 eval consolidation.

4. **RAG eval: ai tag exclusion fixed** (`d2c623b9`): Added `-PincludeAiTests=true` to the Gradle
   command. `RagQualityEvalTest` is annotated `@Tag("ai")`; the `integrationTest` task excludes
   `ai`-tagged tests by default, causing "No tests found" without this flag.

### Run Summary

| Run | Commit on runner | Perf | BEIR gate | RAG eval | Publish summary | Build scorecards |
|-----|-----------------|------|-----------|----------|-----------------|-----------------|
| 22430885164 (Feb 26 scheduled) | pre-fix | fail | fail | fail | **parse error** | fail |
| 22448699777 (manual, push forgotten) | pre-fix | fail | fail | fail | **parse error** | fail |
| 22450553285 (manual, post-push) | `ac45ac53` | fail (policy conflict) | fail (diff regressions) | fail (ai tag) | **✓ fixed** | fail (beir-decision missing) |
| 22465103392 (Feb 27 manual, Pass AO) | `94bd2b09` | — | — | — | — | fail (em-dash PS1 parse error) |
| 22466612854 (Feb 27 manual, em-dash fix) | `a93a946d` | — | — | — | — | fail (stale `--calibration-lookback`) |
| 22467907928 (Feb 27 manual, final) | `c46463a2` | **✓** | **✓** | **✓** | **✓** | **✓ first success** |
| 22475598428 (Feb 27 scheduled) | pre-AO2 | **✓** | **✓** | **✓** | **✓** | **✓** (regression pack: 19/22, step 01 still pre-fix) |
| 22485942956 (Feb 27 manual, post-AO2) | `05c9a9ae` | **✓** | **✓** | **✓** | **✓** | **✓** (regression pack: 21/22, step 01+02+16+16b PASS, step 17 fail: `--runtime-soak-readiness` unconditional) |
| 22488148485 (Feb 27 manual, AO3) | `21050795` | **✓** | **✓** | **✓** | **✓** | **fail** (step 15 regression pack: EPERM on `writeJsonAtomic` rename at state write 8; 7/35 steps passed before crash) |
| 22489951029 (Feb 27 manual, AO4) | `33cc1df3` | **✓** | **✓** | **✓** | **✓** | **fail** (step 15: 33/35 DAG steps passed; `02e` FAILED (no comparable runs, expected), `18b` FAILED (`--require-complete-preflight` + `preflightComplete=false`). First time step 17 (`build-final-evidence-index`) and `18c` deps reached successfully) |
| 22491892324 (Feb 27 manual, AO5) | `21f6d5e3` | **✓** | **✓** | **✓** | **✓** | **✓** (34/35 DAG steps passed; only `02e` failed as expected (no comparable runs). **First complete governance run ever.** All major artifacts produced: `release-evidence-index-final`, `rr219-regression-pack-report`, `runtime-resilience-g1-readiness`, `release-governance-decision-g0/g1-shadow`, `runtime-resilience-history-readiness`, `runtime-resilience-control-loop-conformance`, `runtime-resilience-budget-snapshot`, `runtime-resilience-persistent-handoff`, `runtime-resilience-lane-triage`) |

### Remaining Operational Issues (not fixable from 219)

| Issue | Root cause | Scope |
|-------|-----------|-------|
| ~~"Missing beir-decision" in Build scorecards~~ | ~~Search-rank absent → no beir-decision file → hard throw.~~ **Fixed in Pass AO:** fault-tolerant Build scorecards step (warn + proceed with partial evidence index). | ~~216-boundary~~ → **Fixed (AO)** |
| ~~Perf corpus selection failure~~ | ~~Misdiagnosed as corpus-identity.mjs bug.~~ **Fixed in Pass AO:** `--gate-level stable` added to workflow; `resolveRequiredProfileId` was correct all along — the bug was the workflow not setting gate level. | ~~216-boundary~~ → **Fixed (AO)** |
| BEIR quality regressions (arguana, scifact) | diff-arguana and diff-scifact fail quality gate. May improve after embedding fix `3b2877cc` propagates through baseline accrual. | Operational / baseline accrual |
| Track-g timeout (30 min) | Runner/infra capacity issue. | Infra |
| Agent battery start timeout | Backend cold-start exceeds timeout. | Infra |
| ~~Regression pack EPERM on atomic rename~~ | ~~`writeJsonAtomic` rename fails with `EPERM` (errno -4048) on Windows CI runner — antivirus/indexer file lock race.~~ **Fixed (AO4):** retry up to 5x with 50ms linear back-off on EPERM/EACCES. Confirmed cause of run 22467907928 and 22488148485 crashes. | ~~Infra / `json-utils.mjs`~~ → **Fixed (AO4)** |
| ~~Step 01 `validate-rpc-retry-ownership` failure~~ | ~~`SearchService/ListAllDocumentIds` missing from retry ownership matrix (expected 36, found 35). Added by 234 parallel agent without updating the matrix. Blocks regression pack and gRPC soak report entirely.~~ **Fixed (AO2):** added READ/STRICT/`grpc-rkc-unavailable-v1`/STANDARD entry, expectedMethodCount 35→36. Regression pack now runs 19/22 steps (was 0/22 downstream). | ~~234 boundary~~ → **Fixed (AO2)** |
| `--calibration-lookback` stale callers | Tempdoc 235 S4 removed the arg but left 6+ callers broken: `agent-live-eval-nightly.yml`, overnight scripts, READMEs. Two rr219-path callers fixed in AO. | 235-boundary |

## Implementation Pass AN — Tempdoc Accuracy Corrections (2026-02-26)

### What This Pass Corrects

Pass AM contained one factual error and did not surface a structural finding about the nightly.

#### Correction 1: Perf Corpus Policy Analysis

Pass AM stated: "Lane corpus policy requires `allrounder-core.v1.small` for perf. Comparability
policy requires `allrounder-core.v1.gate`. Two policies in conflict."

**This is wrong.** Both policies agree:
- `rr219-comparability-policy.v1.json` (219-owned): `requiredCorpusProfileId: allrounder-core.v1.gate`
- `lane-corpus-policy.v1.json` (216-owned): `gated_profile_id: allrounder-core.v1.gate`

The actual failure is that `resolveLaneCorpusSelection` in `scripts/lib/bench/corpus-identity.mjs`
validates the resolved profile against `default_profile_id` (`allrounder-core.v1.small`) rather
than `gated_profile_id` (`allrounder-core.v1.gate`). The error message
`"required_profile_id": "allrounder-core.v1.small"` reflects `default_profile_id` from the lane
corpus policy, not a policy-level disagreement. This is a 216-boundary implementation bug in
`resolveLaneCorpusSelection`'s interpretation of which profile field is authoritative for gated
nightly runs.

#### Correction 2: The Nightly Has Never Produced an Evidence Index

The "Build scorecards and RR-219 evidence index" step contains three hard `throw` statements:

```powershell
if (-not $beirDecisions) { throw "Missing beir-decision in $summaryDir" }
if (-not $ragDecisions)  { throw "Missing rag-decision in $summaryDir" }
if (-not $agentManifests){ throw "Missing agent-battery manifest in $summaryDir" }
```

`beir-decision` (`search-eval-beir-decision-*.json`) is produced by search-rank's `judged-beir-diff`
step, not the BEIR gate runner. Search-rank has failed on every nightly run due to the
corpus_components mismatch (216-boundary). The first throw fires immediately, before
`evidence_index_path` is written to `$GITHUB_OUTPUT`.

Consequence: the "Run RR-219 regression pack" step receives no `evidence_index_path` and is
always skipped. **No governance output has ever been produced by the nightly workflow** — no
history readiness, lane triage, G1 readiness packet, control loop conformance, or regression pack
report.

#### Structural Implication for Evidence Accrual

The "Promotion Blockers" section describes run-count gaps as self-resolving as nightly runs
accumulate. This is incorrect as stated: the nightly cannot accumulate governance evidence
while "Build scorecards" always throws on the missing beir-decision. The governance artifacts
remain at their Pass S (2026-02-20) baseline — all sourced from local development runs, not CI.

**Unblocking paths (either is sufficient):**
- **(a) 216-boundary fix** — resolve the search-rank corpus_components mismatch so
  `judged-beir-diff` runs and produces `beir-decision`. No 219 action required.
- **(b) Fault-tolerant "Build scorecards" (actionable from 219)** — replace the three hard
  throws with warnings that allow the step to proceed with absent inputs and build a partial
  evidence index. The builder (`build-release-evidence-index.mjs`) already accepts optional
  inputs; the workflow step does not need to abort when they are absent.

Option (b) does not require 216 cooperation and would allow governance artifacts to accrue from
every nightly run regardless of search-rank/agent-battery status.

---

## Implementation Pass AO — Fault-Tolerant Evidence Index + Perf Gated Corpus Fix (2026-02-26)

### What This Pass Fixes

Two actionable issues confirmed by the Feb 26 05:45 UTC nightly run (run 22430885164).

#### Fix 1: Fault-Tolerant "Build Scorecards and RR-219 Evidence Index" Step

**Diagnosis:** Run 22430885164 confirmed the throw pattern: `beir-decision` is absent because
search-rank is owner-blocked; the first `throw` fires before `evidence_index_path` is written to
`$GITHUB_OUTPUT`. The regression pack receives no path and is skipped unconditionally.

**Root cause correction:** Pass AN §Correction 2 stated "the builder
(`build-release-evidence-index.mjs`) already accepts optional inputs" — this was incorrect. The
builder had a hard required check for `beirDecision`, `ragDecision`, and `agentManifest`, and
`dag-runner.mjs` `resolveInputs()` similarly required all three paths.

**Three-layer fix (all files must agree):**
1. **Workflow (`rr219-resilience-governance-nightly.yml`):** Replaced three `throw` statements with
   `Write-Warning` + conditional arg arrays (`$beirArgs`, `$ragArgs`, `$agentArgs`). Splatted into
   the builder invocation as `@beirArgs @ragArgs @agentArgs`.
2. **Builder (`build-release-evidence-index.mjs`):** Made `beirDecision`, `ragDecision`,
   `agentManifest` optional throughout: removed from required-arg check; conditional load
   (`args.X ? readArtifact(...) : null`); `extractOptionalNode` pattern with `present: false`
   sentinels when absent; null metadata paths.
3. **DAG runner (`dag-runner.mjs`):** Added `resolveOptionalEvidenceArtifactPath()` (returns `null`
   instead of throwing when path is absent/not found); used in `resolveInputs()`; step 17
   (`build-final-evidence-index`) uses conditional arg spreads so absent paths are omitted.

**Test coverage:** New partial/absent-artifact test and mixed-presence test (beir present,
rag+manifest absent) in `scripts/governance/resilience/test-build-release-evidence-index.mjs`.
Dead code (`extractDecisionNode`, `extractAgentManifestNode`) removed; `usage()` string updated
to show `[--beir-decision]`, `[--rag-decision]`, `[--agent-manifest]` as optional.

#### Fix 2: Perf Gated Corpus Selection (`--gate-level stable`)

**Diagnosis:** `dag-runner-perf-regression.mjs` has `DEFAULT_GATE_LEVEL = 'none'`. Without
`--gate-level`, `isGatedRun = (effectiveGateLevel !== 'none')` → `isGatedRun=false` →
`resolveLaneCorpusSelection` called with `gated: false` → `resolveRequiredProfileId` returns
`default_profile_id` (`allrounder-core.v1.small`) instead of `gated_profile_id`
(`allrounder-core.v1.gate`). The requested profile (`gate`) mismatches the required profile
(`small`) → corpus selection validation fails.

**Fix:** Added `--gate-level stable` to the nightly perf step invocation. Makes
`isGatedRun=true`, `resolveLaneCorpusSelection` called with `gated: true`, required profile
resolves to `allrounder-core.v1.gate`, matches requested profile.

**Note:** `resolveRequiredProfileId` in `corpus-governance-lib.mjs` is correct — it returns
`gated_profile_id` when `gated=true`. The bug was purely in the workflow invocation not setting
the gate level. The Pass AN analysis attributing this to a "216-boundary implementation bug in
`resolveLaneCorpusSelection`" was incorrect.

**Test coverage:** New `perf gated=true` test in `scripts/bench/test-corpus-governance-lib.mjs` —
asserts `required_profile_id === 'allrounder-core.v1.gate'`.

#### Fix 2b: Stale `--calibration-lookback` Arg (Cross-Cutting From Tempdoc 235 S4)

**Diagnosis:** First two CI runs after Pass AO deployment (runs 22465103392, 22466612854) failed in
the Build scorecards step. Run 1 failed due to em-dash encoding issue (PowerShell parse error);
run 2 succeeded past the PS1 parse but `build-benchmark-scorecard.mjs` exited with usage help.

**Root cause:** Tempdoc 235 S4 removed `--calibration-lookback` from the scorecard script's
`parseArgs()` but did not update callers. The unknown arg hit the `else { usage(2) }` fallback.
Affected callers: `rr219-resilience-governance-nightly.yml` (blocking), `dag-runner-backfill-report-history.mjs`
(non-blocking, continue-on-error step), plus several overnight scripts and `agent-live-eval-nightly.yml`
(not fixed here — tempdoc 235 responsibility).

**Fix:** Removed `--calibration-lookback 30` from the two rr219-path callers.

#### Fix 3: Downstream Consumer `.present` Guards (Self-Review)

**Diagnosis:** Self-review of Fix 1 identified that `build-runtime-resilience-g1-readiness.mjs`
`evaluateCriteria()` accesses `beirDecision.decision.comparable`, `ragDecision.decision.comparable`,
and `agentManifest.aggregate.infraFailure/teardownFailure` without checking `.present`. When these
fields have `present: false` sentinels (all nulls), the strict equality checks (`null === false`,
`null === true`) evaluate to `false` — correct by accident but semantically misleading (conflates
"no data" with "criterion failed").

**Fix:** Added `beirPresent`, `ragPresent`, `agentManifestPresent` guards in `evaluateCriteria()`.
Criteria return `null` (not evaluated) instead of `false` (failed) when evidence is absent. The
`criteriaPass` AND chain handles `null` correctly — `null && x` is falsy, so `recommendation`
remains `'stay_g0'`.

Also confirmed `build-runtime-resilience-budget-snapshot.mjs` is NOT affected — it loads artifacts
via `safeLoad(resolveEvidencePath(...))`, which returns `null` when `present: false` (path is null),
and all downstream `asBool(null?.aggregate?.infraFailure)` → `null` chains already produce correct
measurement-status sentinels.

**Test coverage:** Two new integration tests in
`test-build-runtime-resilience-g1-readiness.mjs`: (a) all three absent — asserts
`noInfraOrTeardownFailure === null`, `criticalLaneEvidenceComparable === null`,
`recommendation === 'stay_g0'`; (b) beir present + rag absent — asserts
`noInfraOrTeardownFailure === true` (manifest present), `criticalLaneEvidenceComparable === null`
(need both beir+rag), `recommendation === 'stay_g0'`.

---

## Current Implementation State (as of 2026-02-26, after Pass AO)

### Governance Pipeline — Fully Implemented and Quality-Hardened

All scripts, schemas, validators, and tests are complete. No outstanding implementation items.

| Phase | Scope | Status |
|-------|-------|--------|
| AB | Lane manifest resolver, strict comparability, agent signature sync | Done |
| AC | Synthetic RAG eval manifest replaced with real `rag-eval-report-manifest.v1.json` | Done |
| AD | Soak readiness artifact (`runtime-resilience-soak-readiness.v1`), RG-021/RG-022 gates | Done |
| AE | BEN-005 ANN proof enforcement (provenance, converter, diff-suite, gate wiring, promotion enforcement) | Done |
| AF | DAG runner framework (9 pipeline runners, `scripts/lib/orchestration/`), nightly workflow wired | Done |
| AG | Manual promotion decision artifact (`runtime-resilience-promotion-decision.v1`), validator, `--decision` CLI opt | Done |
| AH | Post-implementation quality fixes (10 items): dead CLI opts, ANN else-branch, AJV strict, test gaps, BEN-005 docs | Done |
| AI | Evidence pipeline audit: 7 logic issues identified (F1–F7) | Done (findings only) |
| AJ | Evidence pipeline logic fixes: all 7 F1–F7 issues resolved with test coverage | Done |
| AK | RRS-006 as gate RG-023: extraction in builder, gate policy, reason code map, tests | Done |
| AL | Corpus Governance Quickcheck CI fixes: track-g preflight pattern, search-rank/beir-gate corpus selection opts bug, wiring test patterns for DAG-runner workflows | Done |
| AM | Nightly run investigation; PS1 parse fix, perf corpus intent flag, RAG eval module + ai-tag flag fixes; remaining failures documented as 216-boundary/operational | Done |
| AN | Tempdoc accuracy corrections: perf corpus misdiagnosis corrected (both policies agree on `gate`; bug is in corpus-identity.mjs); structural nightly failure documented (evidence index never produced in CI); evidence accrual blocker and fault-tolerant fix path surfaced | Done |
| AO | Fault-tolerant Build scorecards: three-layer fix (workflow warn-guards, builder optional beir/rag/agent-manifest, dag-runner null-safe path resolution + conditional step-17 args); perf `--gate-level stable`; downstream `.present` guards in g1-readiness `evaluateCriteria()` (null criteria vs false); dead code + usage string cleanup; mixed-presence + g1-readiness partial-evidence integration tests; stale `--calibration-lookback` arg removal (tempdoc 235 S4 cross-cutting breakage). **CI verified (run 22467907928): Build scorecards SUCCESS (first time ever).** | Done |
| AO2 | Retry ownership matrix: `SearchService/ListAllDocumentIds` added (READ/STRICT/grpc-rkc-unavailable-v1/STANDARD); `expectedMethodCount` 35→36. Unblocks regression DAG steps 01+02; first history readiness report produced (run 22475598428: 19/22 steps passed). | Done |
| AO3 | dag-runner step 17 fix: `--runtime-soak-readiness` was unconditionally passed even when step 16c (`build-runtime-soak-readiness`) was skipped (no soak history dir). Applied same conditional spread pattern as beir/rag/agent-manifest lines 513-515. Builder was already null-safe via `extractOptionalNode`. Root cause confirmed in run 22485942956 (step 17 fail). Fix: `...(steps.has('16c-build-runtime-soak-readiness') ? [...] : [])`. | Done |
| AO4 | EPERM atomic rename retry in `writeJsonAtomic` (`scripts/lib/json-utils.mjs`): `fs.rename()` from `.tmp` to destination retried up to 5x with 50ms linear back-off on EPERM/EACCES (Windows AV/indexer file lock race). Confirmed root cause of run 22488148485 crash — step 15 (regression pack DAG) aborted on 8th state write at 14:11:15Z with `EPERM rename run-state.json.tmp -> run-state.json`. Also present in run 22467907928 (was masked by step-01 being the observable failure). | Done |
| AO5 | Remove `--require-complete-preflight` from dag-runner step 18b: `build-runtime-resilience-g1-readiness.mjs` exits 1 when `preflightComplete === false` (no comparable runs yet). During accrual phase this always fails, permanently blocking step 18c (`write-final-report`). Flag belongs only in explicit promotion gating, not advisory nightly. Confirmed root cause in run 22489951029 (18b FAILED, 18c SKIPPED; 33/35 steps passed, only `02e` and `18b` failing). Fix makes 18b exit 0 regardless of preflight completion; output doc still contains `preflightComplete: false`. | Done |
| AO6 | **Step 12 reporting gap:** `12-ui-lifecycle-contract-test` builds Vite web frontend (~4-5 min). Had no edges to later steps, so the critical path (~2.5 min) completed first and 18c wrote the final report while step 12 was still running — step 12 absent from every report (including run 22491892324 where it passed but wasn't captured). Added edge `12-ui-lifecycle-contract-test → 18c-write-final-report` to gate the report on step 12 completing. **`infraFailure` semantic split:** report-builder computed `infraFailure: passedSteps !== totalSteps`, conflating expected advisory failures (02e during accrual) with genuine infrastructure problems. Split into `infraFailure: timeoutCount > 0` (genuine timeout/crash) and `hasFailures: passedSteps !== totalSteps \|\| timeoutCount > 0` (any step didn't pass). Updated `ok` check, workflow summary, and test assertions. | Done |

### What Was Fixed in Pass AJ

| Fix | Impact |
|-----|--------|
| F1: `recommendation` now uses waiver-adjusted blocking | Waivers can now produce `'pilot_g1'` recommendation |
| F2a: `historyReadinessPass` drops `overallVerdict` check | Non-critical lane failures no longer block pilot when critical lanes are ready |
| F2b: `controlLoopConformancePass` + preflight allow 'warn' | Aligns with RG-015 (`op: in, expected: ['pass','warn']`) |
| F3: `blockedByOwner` in lane-triage requires `CRITICAL_LANES.has(lane)` | Code change applied; but AK found the field is dead output (never read downstream). No operational effect. Semantic divergence with history-readiness closed as won't-fix in AK. |
| F4: Expired waiver filter restricted to block-severity gates | Warn-gate expired waivers no longer pollute `noExpiredUnresolvedBlockingWaivers` |
| F5: `buildPreflight()` checks `governanceDecisionG1WithWaivers` | Stale/corrupt with-waivers artifact now caught by preflight |
| F6: rollbackTriggers text corrected | Accurately states conformance 'warn' is tolerated per RG-015 |
| F7: `overallBudgetState` surfaced in criteria output | Observable from readiness doc; not gating (design intent preserved) |

### Promotion Blockers

> **Data staleness:** Run counts below are from Pass S (2026-02-20). The nightly workflow has been
> running since Pass AF; these counts are almost certainly higher. Refresh by re-running the G1
> readiness builder against the latest summary dir artifacts.

> **CI regression pack broken (2026-02-25 finding, updated Pass AO):** The "Run RR-219 regression
> pack" step has been failing since Feb 24 with `Cannot find package 'ajv'` — the workflow was
> missing `npm ci` at root. **Fix committed (Pass AM)**: added `npm ci` at root and
> `package-lock.json` to the npm cache path.
>
> **Feb 26 05:45 UTC nightly (run 22430885164) — still did not produce artifacts.** The `ajv`
> failure was resolved, but the "Build scorecards" step immediately throws on the absent
> `beir-decision` file (produced by search-rank, which remains owner-blocked). The evidence index
> is never written → `evidence_index_path` output is empty → regression pack step is skipped.
>
> **Pass AO fix:** three-layer fault-tolerant change (workflow PS1 warn-and-continue guards,
> `build-release-evidence-index.mjs` optional beir/rag/agent-manifest support, `dag-runner.mjs`
> null-safe path resolution + conditional step-17 args). Accrual can now proceed from the next
> nightly run. Note: Pass AN §Correction 2 stated "the builder already accepts optional inputs" —
> this was incorrect; the builder had a hard required check for all three artifacts. Pass AO
> corrects this in all three layers.

The governance pipeline structure is correct; promotion is blocked by evidence accrual gaps and the now-fixed CI pipeline issue (Pass AO).

**G1 blocking reason codes (structural — will not self-resolve without intervention):**
1. `CRITICAL_LANE_OWNER_BLOCKED` / `INSUFFICIENT_RUNTIME_HISTORY_CRITICAL_LANES` — `search-rank` and `track-g` are 216-boundary owner-blocked. Requires quality-owner remediation or explicit waiver.

**G1 blocking reason codes (accrual — resolve as nightly runs accumulate):**
2. `AGENT_PHASE_A_NOT_GRADUATED` — agent battery phase A gate not met (`passRate < threshold`)
3. `INSUFFICIENT_RUNTIME_HISTORY_CRITICAL_LANES` — `criticalLanesReady=false` on other lanes (comparable run counts below minimums)

**G1 blocking reason codes (conditional — check on next evidence run):**
4. `EVIDENCE_INTEGRITY_CONTRADICTION` (RG-023, added Pass AK) — blocks if any budget snapshot present with `contradictionCount > 0`. Passes automatically when snapshot is absent (null sentinel → `0 ≤ 0`). Verify contradiction count is zero once snapshots begin accruing.

**Critical lane comparable-run counts (Pass S baseline — stale, refresh needed):**
| Lane | Count at Pass S | Minimum | Notes |
|------|-----------------|---------|-------|
| search-rank | owner-blocked | 8 | BEN-004 / 216 boundary, unresolved |
| track-g | owner-blocked | 8 | BEN-004 / 216 boundary, unresolved |
| perf | 4 | 8 | Blocked pre-AO (evidence index never built); accruing post-AO |
| beir-gate | 3 | 4 | ANN-proof active required; accrual also blocked pre-AO |
| rag-eval | 2 | 8 | Blocked pre-AO (evidence index never built); accruing post-AO |
| agent-battery | 6 | 14 | Blocked pre-AO (evidence index never built); accruing post-AO |

**CI verification (Feb 27, run 22467907928 — Pass AO initial confirmed working):**
- Build scorecards and RR-219 evidence index: **SUCCESS** (first time ever in CI)
- Perf regression lane: **SUCCESS** (`--gate-level stable` fix confirmed)
- Benchmark scorecard: **SUCCESS** (required removing stale `--calibration-lookback` arg removed by tempdoc 235 S4)
- Fault-tolerant warnings fired correctly: beir-decision + rag-decision absent → warned, continued
- Agent-manifest was present (no warning) — battery step produced it
- Evidence index: `{"ok": true}` — partial index built successfully
- Regression pack: FAILED (pre-existing EPERM on atomic rename + step 01 validation failure — not AO-related)
- gRPC soak, Collect metadata, Upload artifacts: all SUCCESS

**CI verification (Feb 27, run 22491892324 — AO3+AO4+AO5 all confirmed, first complete governance run):**
- 34/35 DAG steps passed (32 passed / 33 total in report; `02e` expected failure — no comparable runs)
- AO3 (`--runtime-soak-readiness` conditional): confirmed — step 17 PASSED
- AO4 (EPERM retry): confirmed — no EPERM crash; all 34 steps executed normally
- AO5 (`--require-complete-preflight` removed): confirmed — step 18b PASSED, 18c ran and produced final report
- **First time ever:** `rr219-regression-pack-report`, `release-evidence-index-final`, `runtime-resilience-g1-readiness`, `release-governance-decision-g0/g1-shadow`, `runtime-resilience-history-readiness`, `runtime-resilience-control-loop-conformance`, `runtime-resilience-budget-snapshot`, `runtime-resilience-persistent-handoff`, `runtime-resilience-lane-triage` — all produced successfully

### Scorecard Read — Run 22467907928 (Feb 27 2026)

First scorecard artifacts ever produced. Artifacts downloaded and analyzed.

**Benchmark scorecard (`benchmark-scorecard.json`) — `release_readiness: insufficient_data`:**

| Lane | Status | Detail |
|------|--------|--------|
| perf | `final_exit_code=2`, not comparable | No candidate manifest produced. Baseline is **55 days stale** (captured 2026-01-02). Only 1/3 history runs for trend. |
| search-rank | `gate_status=fail`, **6 regressions** | Comparable, fresh baseline (5.7d). 6 regressions detected (`report_only_regression_detected=true`). |
| claim-a | `final_exit_code=1`, not comparable | No candidate manifest produced (backfill ran but no comparable output). |
| track-g | absent | Owner-blocked (216 boundary). |
| rag-eval | absent | Owner-blocked (216 boundary). |
| agent-battery | infra failure, passRate=0 | Backend failed to start (`start-backend` lifecycle timed out). |

Perf ran correctly under the `stable` threshold profile (`regression-thresholds.gate-stable.v1.json`) — that part works. The `exit_code=2` is because the runner produced no candidate manifest to compare against.

**Agent live scorecard (`agent-live-scorecard.json`):**
- 0 comparable runs (1 run skipped: `missing_scenario_signature` — battery produced a manifest but scenarios never ran due to infra failure)
- `runsRequired` gate: **false** — needs 14 comparable runs, has 0
- `phaseA.graduationEligible`: **false**
- All other gates (infraFailureRate, passRateStdDev, scenarioInstability): pass vacuously (no data)

**Evidence index (`release-evidence-index.v1.json`):**
- `beirDecision`: absent (search-rank lane doesn't write a BEIR decision artifact)
- `ragDecision`: absent (RAG eval owner-blocked)
- `agentManifest`: present — `infraFailure=true`, `total=0`, `passRate=0`, errors: `step_failed:start-backend`
- All other slots (budget snapshot, soak decision, history readiness, regression pack): absent (regression pack DAG crashed before producing them)

**Regression pack DAG — crashed on step 01:**
`01-validate-rpc-retry-ownership` failed: `SearchService/ListAllDocumentIds` is missing from the retry ownership matrix. Expected 36 methods, found 35. This method was likely added by a parallel agent (234 work) but not added to the ownership matrix file. This single failure cascades and blocks steps 07b (evaluate-release-governance) and 07c (build-runtime-resilience-g1-readiness) from running entirely — so no G1 readiness output was produced this run.

The `02d-test-build-runtime-resilience-history-readiness` test passed (no dependency on step 01).

**gRPC soak — `ok: false`:** Same step 01 failure (`ListAllDocumentIds` missing). Steps 02 (ipc-common retry/breaker tests) and 03 (app-services retry test) passed. Report/validate steps were skipped due to upstream failure.

### Actionable Blockers (as of Feb 27 2026, updated after AO4)

| Blocker | Type | Action |
|---------|------|--------|
| ~~`SearchService/ListAllDocumentIds` missing from retry matrix~~ | ~~Blocks entire regression DAG~~ | **Fixed (AO2)** |
| ~~Step 17 `build-final-evidence-index` failure~~ | ~~`--runtime-soak-readiness` unconditionally passed when step 16c skipped; `resolveArtifactPath` throws~~ | **Fixed (AO3)** |
| ~~EPERM on `writeJsonAtomic` rename~~ | ~~Crashes dag-runner on state write; Windows AV/indexer race~~ | **Fixed (AO4)** |
| ~~Step 18b `--require-complete-preflight` blocks 18c during accrual~~ | ~~Flag causes 18b exit 1 when preflightComplete=false; blocks write-final-report (18c)~~ | **Fixed (AO5)** |
| ~~Step 12 absent from final report~~ | ~~`12-ui-lifecycle-contract-test` builds Vite (~4-5 min); no edge to 18c; report written before step 12 finishes~~ | **Fixed (AO6)** |
| ~~`infraFailure` semantic misnomer~~ | ~~`report-builder` computed `infraFailure: passedSteps !== totalSteps`, firing on expected advisory failures (02e). Now `infraFailure: timeoutCount > 0` (genuine crash); `hasFailures` is the broad signal.~~ | **Fixed (AO6)** |
| Perf baseline 55 days stale | Operational | Refresh baseline against current runner |
| Agent battery infra failure (backend start timeout) | Operational / infra | Backend cold-start exceeds step timeout on CI runner |
| Search-rank 6 regressions | Needs investigation | May be real or accrual noise from 234 hybrid search changes |
| 6 stale `--calibration-lookback` callers | 235-boundary | `agent-live-eval-nightly.yml` + overnight scripts still broken |
| Calibration smoke tests timing out (ipc-common, app-services) | Operational / infra | Steps 02+03 of calibration smoke hit 173s timeout |
| `claim-a` comparability failure | Accrual | `decision_comparable=false` — 1 run considered, 0 comparable |

### History Readiness Report — Scheduled Run 22475598428 (Feb 27 06:31 UTC)

First live history readiness report produced (predates AO2 matrix fix — step 01 still failed in this run, but was not a blocking dependency for 16b). **19/22 steps passed.**

Failed steps: `01-validate-rpc-retry-ownership` (now fixed), `02-test-validate-rpc-retry-ownership` (now fixed), `02e-validate-rr219-comparability-inputs` (no comparable runs yet).

**`runtime-resilience-history-readiness.v1` output:**

```
overallVerdict: fail
criticalLanesReady: false
ownerBlockedCriticalCount: 2  (search-rank, track-g — 216 boundary)
```

| Lane | Critical | Blocked | Comparable Runs | Target | Verdict |
|------|----------|---------|-----------------|--------|---------|
| search-rank | yes | 216 boundary (BEN-004) | 0 | 10 | fail |
| track-g | yes | 216 boundary (BEN-004) | 0 | 8 | fail |
| perf | yes | no | 0 | 8 | fail — accrual |
| beir-gate | yes | no | 0 | 4 | fail — accrual |
| rag-eval | yes | no | 0 | 8 | fail — accrual |
| agent-battery | yes | no | 0 | 14 | fail — accrual |
| claim-a | no | no | 0 (1 considered, not comparable) | 10 | fail — accrual |

All non-owner-blocked lanes are at 0/target because the evidence index was never built before AO (Feb 27). Accrual clock started with run 22467907928 (Feb 27 01:39 UTC). The 06:31 UTC scheduled run used old code (step 01 failure), so lane manifests were resolved but comparability validation failed — no runs counted yet.

**Other artifacts produced in run 22475598428:**
- `budget-snapshot`: ok:true (first budget snapshot produced)
- `lane-triage`: ok:true
- `persistent-handoff`: ok:true, `persistentCount: 0`
- `regression-pack-report`: 19/22 passed (infraFailure:true due to step 01+15)

**Next action to unblock promotion:**
- Evidence accrual now proceeding from AO (Feb 27) — all 0-count lanes need nightly runs to accumulate
- Owner escalation: `search-rank` and `track-g` require 216-boundary remediation or explicit waiver
- Agent phase A: battery must reach graduation threshold (`minComparableRuns: 14`, currently 0)
- Perf baseline refresh: 55-day-stale baseline needs updating
- Agent battery infra: backend start timeout blocking comparable run accumulation
- RG-023: `evidenceIntegrityContradictionCount` is null (budget snapshot absent from evidence index) — passes automatically

**When all accrual gates pass:** produce a `runtime-resilience-promotion-decision.v1` artifact, validate it with `validate-runtime-resilience-promotion-decision.mjs`, and supply via `--decision` to the readiness builder.
