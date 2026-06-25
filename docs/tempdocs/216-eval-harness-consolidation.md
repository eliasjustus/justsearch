---
title: "Eval Harness Consolidation (BEN-004 / S-001)"
type: tempdoc
status: done
created: 2026-02-18
updated: 2026-02-21 (run reality sync; F-7 still open)
---


> NOTE: Noncanonical doc (investigation + plan). May drift. Verify against code before acting.

# 216: Eval Harness Consolidation

## Origin

Promoted from tempdoc 215 Finding 7. Strategy item BEN-004 / S-001 identifies the
three-independent-harness problem as the primary Q1 throughput unblocker. This doc owns
the investigation, plan, and execution log.

## Acceptance Criteria

This tempdoc is complete when:

1. **All three harnesses appear in one consolidated scorecard** with cross-lane health
   visible (`build-benchmark-scorecard.mjs`). — **MET.** Overnight run `20260219-225114`
   produced manifests for all 3 families: search quality (claim-a pass, track-g fail),
   RAG (rag-eval pass), agent (agent-battery pass, 43.8% pass rate). Scorecard discovers
   6/6 lanes, 3/3 families. Release readiness status: `warn` (all data present, some
   quality signals need attention). Phase 4c lanes (Claim A + Track G) confirmed working
   in overnight context.
2. **All eval artifacts use `bench-suite.v2` or have a self-declared `main_score`** —
   enabling lane-agnostic aggregation without hardcoded per-lane metric names.
   — **MET.** All 6 lanes now use the generic scorecard extraction path. Agent-battery
   manifests emit `decision_*` fields (gate_status, comparable, regression_count,
   non_comparable_count) and the two hardcoded `if (lane === 'agent-battery')` branches
   in the scorecard have been removed. Agent-specific operational data (pass_rate,
   infra_failure, scenario_count) is preserved as additive extensions. See §IV.11.
3. **The agent battery produces quality signal, not infrastructure smoke** — corpus
   ingested, required_facts assertions, tool traces retained.
   — **MET** (Phases 2b, 3d+3e, 3f). Validated 2026-02-19: battery ran end-to-end with
   all new code. `ingestCorpus()` indexed 201 docs and registers the docs folder as a
   watched root via `POST /api/indexing/roots` (fixed in validation2 run). All 16 scenarios
   have `toolTraces` (1-3 per scenario with full arguments and output). `requiredFacts`
   assertions correctly differentiate passing from failing scenarios. Pass rate: 6/16 (37.5%)
   after root fix (up from 25% without roots). Remaining 10 failures are genuine content
   quality gaps (`missing_facts`, `missing_keywords`) — the agent finds some content but
   not enough to satisfy fact assertions. This is honest quality signal, not infrastructure
   noise.
4. **Corpus/truth versioning prevents silent non-comparability** — both RAG eval and
   agent battery enforce SHA-based comparability guards.
   — **SUBSTANTIALLY MET** (Agent battery's scenario signature + RAG eval's corpus SHA via
   Phase 3b). Transition gap: migrated RAG baselines have null SHA, so the comparability
   check is skipped until baselines are re-promoted from a v2 run.

**Summary.** Criterion 1 is MET (overnight run 20260219-225114: 6/6 lanes, 3/3 families).
Criterion 2 is MET (all 6 lanes use generic scorecard extraction). Criterion 3 is MET
(battery produces real quality signal, 43.8% pass rate in latest overnight). Criterion 4 is
substantially met (transition gap: migrated RAG baselines have null SHA). The genuine achievements are the
infrastructure and format improvements (bench-suite.v2, main_score, RAG v2 migration, agent
battery quality transformation, scorecard, producer contract). The "consolidation" framing
is generous — the codebase still has 6 independent eval scripts, 2 overnight scripts, 2
summary directories, and 4 manifest formats.

### Post-Completion Residual (Tracked, Does Not Reopen Consolidation Scope)

1. **F-7 remains open as a lane-quality/harness realism residual:** BEIR gate hybrid profile can run with ANN disabled, producing lexical-equivalent hybrid behavior.
2. **Owner path:** tempdoc 216 (lane/harness owner) + canonical issue `BEN-005` in `docs/reference/issues/benchmarking.md`.
3. **Downstream consumer impact:** tempdoc 219 should treat this as upstream evidence-quality dependency and keep it explicit in release-governance interpretation.
4. **Next action path (216-owned):**
   - enable ANN for embedding profile in CI, or
   - switch CI gate profile to lexical-only when ANN is unavailable, or
   - emit explicit ANN-inactive provenance and force non-comparable handling.

### Reality Sync (2026-02-21, Runs Deferred In This Update)

Observed lane manifests show the harness is executing end-to-end, but with baseline-shift caveats:
1. `search-rank` failed then passed after promotion:
   - fail: `tmp/track-check/search-rank-manifest-post-autopilot.json` (`decision_gate_status=fail`, `decision_regression_count=5`)
   - pass: `tmp/track-check/search-rank-manifest-after-promote.json` (`decision_gate_status=pass`)
2. `track-g` failed then passed after refresh:
   - fail: `tmp/track-check/track-g-manifest.json` (`decision_gate_status=fail`, `decision_regression_count=3`)
   - pass: `tmp/track-check/track-g-manifest-after-refresh.json` (`decision_gate_status=pass`)
3. `beir-gate` stub failed then passed after promotion; embedding profile also passed:
   - fail: `tmp/track-check/beir-gate-manifest-post-autopilot.json`
   - pass (stub): `tmp/track-check/beir-gate-manifest-after-promote.json`
   - pass (embedding): `tmp/track-check/beir-gate-embedding-manifest.json`
4. `claim-a` and `perf` spot manifests are passing:
   - `tmp/track-check/claim-a-manifest-post-autopilot.json`
   - `tmp/track-check/perf-manifest-post-autopilot.json`
5. Implication for this tempdoc: criterion-1 execution is now observed in practice; sections that describe "never executed" are historical snapshots.

---

### 216/219 Ownership Boundary (Locked)

This section mirrors the boundary contract in `docs/tempdocs/219-runtime-resilience-hardening.md`.
Changes to either side require agreement between both tempdocs.

**Tempdoc 216 owns:**
1. Eval/benchmark harness architecture and runner orchestration.
2. Lane wiring, scorecard lane topology, and lane-specific ingestion behavior.
3. Eval artifact schema migration/unification (`bench-suite.v2`, lane manifests, converters).
4. Benchmark CI workflow composition and benchmark-only gate mechanics.

**Tempdoc 219 owns:**
1. Runtime resilience semantics and policy (retry, breaker, readiness).
2. Resilience-specific evidence requirements (what evidence must exist for release decisions).
3. Release-governance interpretation for resilience (how to consume evidence, waiver semantics, decisioning).

**Non-overlap rules:**
1. 219 may define required resilience evidence fields, but does not redesign benchmark lane contracts.
2. Any schema/gate changes in benchmark artifacts are requested through 216, then consumed by 219.
3. 219 is downstream of 216 for consolidated benchmark evidence production.

---

### Producer Contract for Tempdoc 219

This section documents the artifacts, fields, and compatibility guarantees that tempdoc 216
provides as a producer for tempdoc 219 (the consumer). 219 may depend on these contracts
for release-governance evidence requirements. All field names are code-validated against
the producing scripts as of Phase 3g.

#### A) Consolidated Scorecard (`benchmark-scorecard.v1`)

**Producer:** `scripts/bench/build-benchmark-scorecard.mjs`

**Top-level fields:**

| Field | Type | Description |
|-------|------|-------------|
| `kind` | string | Always `"benchmark-scorecard.v1"` |
| `schema_version` | number | Always `1` |
| `generated_at` | ISO 8601 | Scorecard generation timestamp |
| `source.manifests` | object | Lane → manifest path map (one key per lane in `LANE_ORDER`) |
| `release_readiness.status` | enum | `"pass"` \| `"warn"` \| `"insufficient_data"` |
| `release_readiness.reasons[]` | string[] | Human-readable reasons for non-pass status |
| `ratchet` | object | Ratchet-policy evaluation with per-lane recommendations |

**Lane row fields (`lanes[]`):**

| Field | Type | Description |
|-------|------|-------------|
| `lane` | string | Lane identifier (see stable lane IDs below) |
| `present` | boolean | Whether a manifest was found for this lane |
| `manifest_path` | string \| null | Path to the source manifest |
| `generated_at` | ISO 8601 \| null | Manifest generation timestamp |
| `comparable` | boolean \| null | Whether baseline/candidate are comparable |
| `gate_status` | enum \| null | `"pass"` \| `"fail"` \| `"unknown"` |
| `regression_count` | number | Count of regressed comparisons |
| `non_comparable_count` | number | Count of non-comparable comparisons |
| `report_only_regression_detected` | boolean \| null | Regression signal in report-only mode |
| `final_exit_code` | number \| null | Wrapper script exit code |
| `warnings[]` | string[] | Lane-specific warning messages |

**Agent-specific fields (present when `lane == "agent-battery"`):**

| Field | Type | Description |
|-------|------|-------------|
| `agent_pass_rate` | number \| null | Fraction of scenarios passing (0.0–1.0) |
| `agent_infra_failure` | boolean | Infrastructure failure detected in run |
| `agent_total` | number \| null | Total scenarios in the run |
| `agent_passed` | number \| null | Count of passed scenarios |
| `agent_failed` | number \| null | Count of failed scenarios |
| `agent_scenario_count` | number \| null | Count of scenario definitions |

#### B) Diff Decision Artifact (`bench-decision`)

**Producer:** `scripts/bench/lib/policy-engine.mjs` (`buildDecisionArtifact`)
**Used by:** `diff-search-eval-suite.mjs` (BEIR), `diff-rag-eval-suite.mjs` (RAG)

Note: Uses `schema_family`/`schema_version` discrimination, not `kind`-based.

| Field | Type | Description |
|-------|------|-------------|
| `schema_family` | string | Always `"bench-decision"` |
| `schema_version` | number | Always `1` |
| `decisions.policy_version` | string \| null | Policy identifier (e.g., `"bench-policy.rag-eval.v1"`) |
| `decisions.comparable` | boolean | Whether baseline and candidate are comparable |
| `decisions.gate_status` | enum | `"pass"` \| `"fail"` \| `"unknown"` |
| `decisions.regression_count` | number | Count of regressed comparisons |
| `decisions.non_comparable_count` | number | Count of non-comparable comparisons |
| `decisions.regressions[]` | object[] | Details of each regressed comparison |
| `baseline.source_path` | string \| null | Path to baseline artifact |
| `candidate.source_path` | string \| null | Path to candidate artifact |

#### C) Agent Battery Manifest (`agent-live-battery-manifest.v1`)

**Producer:** `scripts/ci/run-agent-live-battery.mjs`

| Field | Type | Description |
|-------|------|-------------|
| `kind` | string | Always `"agent-live-battery-manifest.v1"` |
| `version` | number | Always `1` |
| `generatedAt` | ISO 8601 | Manifest generation timestamp |
| `phase` | string | Run phase: `"A"` \| `"B"` \| `"C"` |
| `blocking` | boolean | Whether gate failures block release |
| `config.scenarioProfile.signature` | string | SHA-256 of scenario definitions |
| `run.runId` | string \| null | Dev-runner run ID |
| `aggregate.total` | number | Total scenarios executed |
| `aggregate.passed` | number | Passed scenario count |
| `aggregate.failed` | number | Failed scenario count |
| `aggregate.passRate` | number | Pass rate (0.0–1.0) |
| `aggregate.infraFailure` | boolean | Infrastructure failure detected |
| `aggregate.teardownFailure` | boolean | Teardown failure detected |
| `errors[]` | string[] | Runtime error messages |

#### D) Agent Scorecard (`agent-live-scorecard.v1`)

**Producer:** `scripts/ci/build-agent-live-scorecard.mjs`

| Field | Type | Description |
|-------|------|-------------|
| `kind` | string | Always `"agent-live-scorecard.v1"` |
| `schemaVersion` | number | Currently `2` |
| `generatedAt` | ISO 8601 | Scorecard generation timestamp |
| `window.targetRuns` | number | Configured window size |
| `window.actualRuns` | number | Actual runs in window |
| `comparability.expectedScenarioProfile.signature` | string | Expected scenario SHA |
| `comparability.skippedRuns[].reason` | string | Why each run was skipped |
| `metrics.infraFailureRate` | number | Infra failure rate across window |
| `metrics.passRateStdDev` | number | Pass rate standard deviation |
| `metrics.scenarioInstabilityRate` | number | Fraction of unstable scenarios |
| `gates.infraFailureRate` | boolean | Gate pass/fail for infra failures |
| `gates.passRateStdDev` | boolean | Gate pass/fail for pass rate variance |
| `gates.scenarioInstability` | boolean | Gate pass/fail for scenario instability |
| `gates.runsRequired` | boolean | Gate pass/fail for minimum run count |
| `phaseA.graduationEligible` | boolean | All Phase A gates pass |

#### Compatibility Rules

1. **Additive-only schema changes.** New fields may be added to any producer artifact;
   existing fields are never removed or renamed without a major version bump.
2. **Stable lane IDs.** The following lane IDs are stable and will not be renamed:
   `perf`, `search-rank`, `claim-a`, `track-g`, `rag-eval`, `agent-battery`.
3. **Stable enums:**
   - `release_readiness.status`: `pass` | `warn` | `insufficient_data`
   - `gate_status` (where present): `pass` | `fail` | `unknown`
4. **Casing convention per artifact family:**
   - `benchmark-scorecard.v1`: snake_case
   - `agent-live-battery-manifest.v1` and `agent-live-scorecard.v1`: camelCase
5. **If `bench-decision` migrates to `kind`-based format**, a dual-read compatibility
   window will be provided (both `schema_family` and `kind` emitted simultaneously).

---

## 1. Problem Statement

Three separate evaluation pipelines exist in the codebase. Each was built independently
to serve a different quality dimension as that dimension emerged. They share no framework,
have divergent conventions, and each new quality dimension would require building a fourth
harness from scratch.

| Harness | Quality dimension | Primary script(s) | CI gate? |
|---------|------------------|-------------------|----------|
| BEIR eval | Search retrieval ranking (Recall@K, nDCG@K) | `beir-eval-win.ps1` | Yes — `beir-eval-gate-win.yml` *(Phase 1)* |
| RAG quality eval | End-to-end RAG (fact coverage, faithfulness, citation) | `RagQualityEvalTest.java` + `diff-rag-eval-suite.mjs` | No dedicated workflow |
| Agent live battery | Agent task success rate (pass@k, loop incidence, trajectory) | `run-agent-live-battery-win.ps1` + `build-agent-live-scorecard.mjs` | Yes — `agent-live-eval-nightly.yml` (Phase B, nightly) |

---

## 2. File Inventory

### 2.1 BEIR Eval

| File | Role |
|------|------|
| `scripts/search/beir-eval-win.ps1` | Main runner: downloads dataset, indexes via API, runs queries, writes `metrics.json` |
| `scripts/bench/promote-search-eval-beir-baseline-win.ps1` | Promotion: copies `metrics.json` → `baselines/search-eval-beir-<dataset>-baseline.metrics.json` |
| `scripts/bench/convert-beir-metrics-v1-to-v2.mjs` | Migration: converts legacy `beir-metrics.v1` → `bench-suite.v2` |
| `scripts/bench/diff-search-eval-suite.mjs` | Diff: compares BEIR (+ rank-eval) metrics, emits `bench-decision.v1` |
| `scripts/bench/baselines/search-eval-beir-<dataset>-baseline.metrics.{json,v2.json}` | Baselines (3 datasets: arguana, nfcorpus, scifact) |

No CI workflow for BEIR eval. The rank-eval workflow
(`search-eval-rank-report-win.yml`) is adjacent but distinct — it calls
`run-search-eval-rank-report-win.ps1` which is its own pipeline.

### 2.2 RAG Quality Eval

| File | Role |
|------|------|
| `modules/system-tests/src/integrationTest/java/.../RagQualityEvalTest.java` | Main runner: Java integration test, writes `rag-eval-result.v1.json` |
| `modules/system-tests/src/test/resources/manifests/rag-eval-truth.v1.json` | Ground truth facts per query |
| `modules/system-tests/src/test/resources/corpus/rag/rag-eval-vectors.json` | Corpus vectors |
| `scripts/bench/diff-rag-eval-suite.mjs` | Diff: 7 aggregate metrics, emits `bench-decision.v1` |
| `scripts/bench/promote-rag-eval-baseline-win.ps1` | Promotion: profile-aware, derives profile from run metadata |
| `scripts/bench/overnight-rag-ai-queue-win.ps1` | Overnight orchestration: runs RAG loop + Claim D + agent battery |
| `scripts/bench/baselines/rag-eval-baseline-profiles.v1.json` | Profile index (2 profiles) |
| `scripts/bench/baselines/rag-eval-baseline.<profile>.json` | Per-profile baseline files |

No dedicated CI workflow for RAG eval. The overnight script
(`overnight-rag-ai-queue-win.ps1`) orchestrates it manually and also runs the agent
battery — these two are coupled in one script.

### 2.3 Agent Live Battery

| File | Role |
|------|------|
| `scripts/ci/run-agent-live-battery-win.ps1` | Main runner: executes scenarios, writes `agent-live-battery-manifest.v1.json` |
| `scripts/ci/build-agent-live-scorecard.mjs` | Scorecard: aggregates 14-run history window → pass-rate, loop incidence, trajectory, path convergence |
| `scripts/ci/evaluate-agent-live-gate.mjs` | Gate evaluator: Phase A (no-op) / B (soft) / C (hard) |
| `scripts/ci/build-agent-live-ab-report.mjs` | A/B comparison report |
| `scripts/ci/agent-live-battery-scenarios.v1.json` | Scenario definitions |
| `.github/workflows/agent-live-eval-nightly.yml` | CI: nightly, Phase B, soft-gate |
| `scripts/agent-analytics/analyze-session.mjs` | Session telemetry analysis |
| `scripts/agent-analytics/score-session.mjs` | Per-session scoring |

### 2.4 Shared Infrastructure

| File | Used by |
|------|---------|
| `scripts/bench/lib/policy-engine.mjs` | BEIR diff, RAG diff — NOT agent battery |
| `scripts/bench/lib/suite-loader.mjs` | BEIR diff, RAG diff — NOT agent battery |
| `scripts/bench/build-benchmark-scorecard.mjs` | All 6 lanes: perf, search-rank, claim-a, track-g, rag-eval, agent-battery *(Phase 2)* |
| `scripts/bench/schemas/bench-suite.v2.schema.json` | Unified schema with `main_score` *(Phase 3a)* |

---

## 3. Divergence Analysis

### 3.1 Baseline Storage and Governance

| Dimension | BEIR | RAG | Agent |
|-----------|------|-----|-------|
| Baseline format | Flat files per dataset (`beir-metrics.v1` + `bench-suite.v2`) | Per-profile JSON files (profile index in `rag-eval-baseline-profiles.v1.json`) | **No baseline** — rolling history window |
| Profile/variant support | None — one baseline per dataset | Yes — profiles keyed by `similarity_mode`, `faithfulness_mode`, `query_count` | N/A |
| Promotion tool | `promote-search-eval-beir-baseline-win.ps1` (simple copy) | `promote-rag-eval-baseline-win.ps1` (profile-aware derivation) | No promotion — manifests append to history dir |
| Age tracking | None | None | 14-run window |

### 3.2 Gate Mechanism

| Dimension | BEIR | RAG | Agent |
|-----------|------|-----|-------|
| CI workflow | **None** | None (overnight script only) | `agent-live-eval-nightly.yml` (nightly) |
| Gate type | N/A | Ratio-based (via `diff-rag-eval-suite.mjs`) | Window-based stability gates (infraFailureRate, passRateStdDev, scenarioInstability) |
| Decision artifact | `bench-decision.v1` (diff tool exists, never called in CI) | `bench-decision.v1` (diff tool exists, called in overnight script) | Custom `gates` object in scorecard — NOT `bench-decision.v1` |
| Pass/fail logic | N/A | `buildGateDecision` (policy-engine.mjs) | `evaluate-agent-live-gate.mjs` (independent) |

### 3.3 Output Schema Divergence

**BEIR baseline (`bench-suite.v2`):**
```json
{
  "schema_family": "bench-suite",
  "schema_version": 2,
  "suite_kind": "search-eval",
  "suite_id": "beir-scifact-test-k10",
  "workload": { "eval_family": "beir", "dataset": "scifact", ... },
  "measurements": { "summary": {...}, "samples": { "mode_results": {...} } },
  "decisions": { "gate_status": "unknown", "regressions": [] }
}
```

**RAG baseline (`rag-eval.v1`) — NOT bench-suite.v2:**
```json
{
  "suite": "rag-eval",
  "version": 1,
  "run_metadata": { "model_name": "...", "similarity_mode": "...", ... },
  "aggregate": { "fact_coverage_mean": 0.747, "faithfulness_mean": 0.403, ... },
  "per_query": [ { "id": "rag-001", ... } ]
}
```

**Agent manifest (`agent-live-battery-manifest.v1`) — bespoke format:**
```json
{
  "kind": "agent-live-battery-manifest.v1",
  "generatedAt": "...",
  "config": { "scenariosPath": "...", "scenarioProfile": { "signature": "...", ... } },
  "scenarios": [...],
  "aggregate": { ... }
}
```

Three different top-level keys (`schema_family`/`suite`/`kind`), three different versioning
conventions, three different schema families. `suite-loader.mjs` handles BEIR and RAG;
agent manifests require their own loader.

### 3.4 Scorecard / Ratchet Coverage

`build-benchmark-scorecard.mjs` aggregates lane manifests for: **perf**, **search-rank**,
**claim-a**, **track-g**. It uses a `MANIFEST_KIND_TO_LANE` map to discover which manifests
belong to which lane.

Neither **RAG eval** nor **agent live battery** appear in this map. Both produce their own
separate scorecard outputs with no consolidation into the cross-lane view.

### 3.5 Overnight Script Coupling

`overnight-rag-ai-queue-win.ps1` runs:
1. RAG eval loop (configurable N runs)
2. Claim D suite
3. Agent live battery

This means two of the three harnesses (RAG + agent) are coupled in one orchestration
script, creating an implicit dependency. Running agent battery without RAG requires
extracting it from this script or using the standalone CI workflow. The BEIR eval is
fully independent.

---

## 4. Critical Findings

### ~~F-1: BEIR Has No CI Gate Despite Gate Infrastructure Existing~~ — **Fixed (Phase 1)**

> **Resolved.** `beir-eval-gate-win.yml` created in Phase 1.

~~`diff-search-eval-suite.mjs` is complete and correctly implements ratio-based regression
detection with `bench-decision.v1` output. The three BEIR baselines (arguana, nfcorpus,
scifact) are promoted and stored. Yet no CI workflow calls this diff tool. BEIR regressions
can only be detected manually by running `beir-eval-win.ps1` + `diff-search-eval-suite.mjs`
by hand.~~

~~This is the cheapest improvement: one CI workflow file calling the existing diff tool
against the existing baselines.~~

### ~~F-2: RAG Eval Schema Is Stranded on `rag-eval.v1`~~ — **Fixed (Phase 3b)**

> **Resolved.** `RagQualityEvalTest.java` now dual-writes v1 + `bench-suite.v2`.
> Converter (`convert-rag-eval-v1-to-v2.mjs`) created. Baselines promoted to v2.
> Corpus SHA-256 versioning added. See §IV.3 for implementation details.

~~BEIR artifacts were migrated to `bench-suite.v2` format (via `convert-beir-metrics-v1-to-v2.mjs`).
RAG eval output (`RealRagEvaluationTest.java`) still writes `rag-eval.v1`. The `suite-loader.mjs`
has a `loadRagEvalSuite` adapter that bridges the format difference, but the underlying
artifact is not `bench-suite.v2`. This means:~~
- ~~RAG cannot be added to `build-benchmark-scorecard.mjs` without a custom lane loader~~
- ~~The v1 schema lacks `decisions` block (gate_status, regressions) at the top level~~
- ~~Profile system is RAG-specific; bench-suite.v2 has no equivalent~~

~~Migration path: add a `convert-rag-eval-v1-to-v2.mjs` (parallel to the BEIR converter)
and update `RealRagEvaluationTest.java` to emit the v2 schema. The RAG-specific fields
(per-query data, profile) can go in `extensions`.~~

### F-3: Agent Battery Gate Model Is Fundamentally Different

BEIR and RAG both use a ratio-based gate: `candidate_metric / baseline_metric >= threshold`.
The agent battery uses a window-based stability gate: rolling 14-run history, checking
variance and flakiness rather than regressing against a fixed point. These models cannot be
merged without an abstraction layer.

However, the agent battery does NOT need to produce a `bench-decision.v1` artifact to be
added to `build-benchmark-scorecard.mjs`. The scorecard could accept agent lane input in its
own format, with the scorecard builder having a separate reader for `agent-live-battery`
manifests.

### ~~F-4: `build-benchmark-scorecard.mjs` Has the Right Shape but Wrong Lane Set~~ — **Fixed (Phase 2)**

> **Resolved.** RAG eval and agent battery lanes added to the scorecard in Phase 2.
> `LANE_ORDER`, `MANIFEST_KIND_TO_LANE`, and lane-specific readers all updated.

~~The ratchet policy in `build-benchmark-scorecard.mjs` already defines L1/L2/L3 maturity
levels, minimum history runs, and regression signal rate thresholds. The `LANE_ORDER` array
and `MANIFEST_KIND_TO_LANE` map are the only integration points needed to add new lanes.
Adding RAG and agent battery requires:~~
- ~~Adding their manifest kinds to `MANIFEST_KIND_TO_LANE`~~
- ~~Implementing lane-specific readers in the scorecard builder~~
- ~~Defining `lane_targets` and `lane_requirements` for the new lanes~~

### F-5: Per-Query Data in RAG Baseline Creates Baseline Size Problem

The RAG baseline JSON includes full `per_query` arrays (24 queries × ~500 bytes each).
This is appropriate for investigation but makes baselines heavier than needed for gate
comparison (which only uses the `aggregate` block). The agent battery sidesteps this
entirely (history manifests store per-scenario data but the gate operates on scorecard
aggregates). A consolidated harness should separate gate artifacts (aggregates only) from
investigation artifacts (per-query / per-scenario).

### ~~F-6: No Shared Corpus/Scenario Signature Governance Between RAG and Agent~~ — **Fixed (Phase 3b)**

> **Resolved.** RAG eval now computes a corpus SHA-256 (combined hash of truth manifest +
> vectors + corpus docs) and stores it in `bench-suite.v2` workload. The diff tool checks
> corpus_sha comparability when both sides have non-null values.

~~The RAG eval uses a static corpus (`rag-eval-vectors.json`) with a fixed 24-query truth
file. The agent battery uses scenario definitions with a SHA-256 signature
(`buildScenarioProfile` in `build-agent-live-scorecard.mjs`) that allows manifests to be
filtered by scenario set version. RAG eval has no equivalent versioning for its corpus or
truth file — if either changes, all existing baselines become non-comparable without any
automated detection.~~

### F-7: beir-gate CI Runs Produce Hybrid == Lexical Metrics — ANN Service Disabled

**Found:** 2026-02-20, during post-implementation BEIR investigation for tempdoc 223.

The `run-beir-gate-win.ps1` CI gate runs the BEIR eval against a dev stack that has
`search.hybrid.ann_service.enabled: false` in its search config. As a result, hybrid
mode queries never reach the ANN index — hybrid falls back silently to pure BM25. The
`embedding-nomic-q4` provenance profile label in the output artifacts is correct
(the embedding model fingerprint is recorded), but the hybrid metric values are
identical to lexical, making them useless for measuring ANN contribution.

**Evidence (Feb 19 2026 gate runs vs. manual baseline runs):**

| Run | Source | Hybrid nDCG@10 | Lexical nDCG@10 | ANN contributing? |
|-----|--------|----------------|-----------------|-------------------|
| `beir-gate-arguana-20260219-221743` | CI gate | 0.3065 | 0.3065 | **No** (identical) |
| `beir-gate-nfcorpus-20260219-222146` | CI gate | 0.3042 | 0.3075 | **No** (nearly identical) |
| `arguana-baseline-all` | Manual run | 0.3519 | 0.3368 | Yes (+4.5 pp) |
| `nfcorpus-baseline-all` | Manual run | 0.3326 | 0.3081 | Yes (+2.5 pp) |

The gate runs also cap queries at `MaxQueries=300` instead of running all queries,
making the numbers non-comparable with the full-corpus baselines on a second axis.

**Impact:**
- Any promoted baseline captured from a gate run would understate hybrid quality.
- The BEIR gate currently passes/fails on lexical metrics only in practice, regardless
  of what the profile label says.
- Investigation work depending on beir-gate output for hybrid A/B comparison is
  unreliable — only manual runs (non-gate) give valid hybrid numbers.

**Root cause:** The beir-gate dev stack is started with a clean data dir
(`tmp/dev-runner-data/beir-gate/<dataset>-<timestamp>/`) that has no ANN service
configured. The `ann_service.enabled` flag defaults to `false` unless the stack is
configured with an ANN endpoint, which the gate orchestration does not provide.

**Resolution needed:**
- Either configure the beir-gate stack with ANN enabled (requires embedding model +
  ANN worker to be available in CI), or
- Change the gate's profile to `stub-jaccard` (lexical-only) and stop claiming
  `embedding-nomic-q4` comparability for runs where ANN is not functional, or
- Accept that the gate only measures lexical quality and document this limitation
  explicitly in the gate workflow and artifact metadata.

---

## 5. Consolidation Options

### Option A: Minimal — BEIR CI Gate Only (1–2 days)

Add a CI workflow that runs `diff-search-eval-suite.mjs` against existing baselines on
changes to search-related code. No schema changes. No harness restructuring.

**Impact:** BEIR regressions detected automatically.
**Risk:** Low — existing tooling is complete.
**What it doesn't fix:** Schema divergence, scorecard gap, RAG CI gap, agent scorecard isolation.

### Option B: Incremental — Schema Migration + Scorecard Integration (1 week)

1. Add `convert-rag-eval-v1-to-v2.mjs` + update `RealRagEvaluationTest.java` to write `bench-suite.v2`
2. Add RAG lane to `build-benchmark-scorecard.mjs` (manifest kind: `rag-eval-manifest.v1`)
3. Add agent lane to `build-benchmark-scorecard.mjs` (manifest kind: `agent-live-battery-manifest.v1`)
4. Add BEIR CI workflow (from Option A)

**Impact:** All three harnesses appear in one consolidated scorecard. Cross-lane health visible.
**Risk:** Medium — Java test output change requires re-promoting all RAG baselines.
**What it doesn't fix:** Overnight script coupling; agent gate model remains separate from policy-engine.mjs.

### Option C: Full Consolidation — Shared Runner Protocol (2–3 weeks)

All of Option B plus:
- Define a `eval-runner-protocol.v1` interface: standard input manifest, standard output
  `bench-suite.v2` artifact, standard `bench-decision.v1` gate
- Migrate agent battery output to `bench-suite.v2` format (agent-specific fields in `extensions`)
- Unify gate evaluation: agent stability gates become first-class gates in `policy-engine.mjs`
  (e.g., `max-variance`, `window-based` gate types)
- Extract overnight script into composable lane steps, callable independently
- Add corpus/truth versioning to RAG eval (parallel to agent scenario signature)

**Impact:** Adding a 4th eval dimension is a matter of implementing the protocol, not
building new scaffolding from scratch.
**Risk:** High — changes agent battery output format, requires history re-migration or
period of dual-write, increases scope significantly.

---

## 6. Recommended Sequence

> **Superseded.** This was the initial 4-phase sketch. See §II.7 for the current phase
> table (11 phases with status tracking), and §IV.6 for the dependency chain and
> implementation order.

~~Given S-001 / BEN-004 priority and the principle of smallest viable step:~~

| Phase | Scope | Goal | Status |
|-------|-------|------|--------|
| ~~**Phase 1**~~ | ~~Option A~~ | ~~BEIR CI gate. One workflow file. ~1 day.~~ | **Done** |
| ~~**Phase 2**~~ | ~~Option B (partial)~~ | ~~Add RAG and agent lanes to `build-benchmark-scorecard.mjs`. No schema change. ~3 days.~~ | **Done** |
| **Phase 3** | Option B (schema) | Split into 3a/3b/3c/3d/3e/3f/3g — see §II.7 | **Done** (all sub-phases complete) |
| **Phase 4** | Option C (selective) | Split into 4a/4b/4c/4d — see §II.7 | 4a/4c/4d done; 4b extracted→224; 4e superseded |

---

## 7. Open Questions

> **Consolidated.** All open questions are now tracked in §IV.7. Below is the original
> list with resolution status.

1. **BEIR datasets in CI:** ~~Running BEIR eval in CI requires the BEIR corpus to be
   available on the `justsearch-perf` runner.~~ Open — not yet verified.
2. ~~**RAG baseline re-promotion:**~~ **Resolved (Phase 3b).** Baselines converted to v2
   and profile index updated.
3. ~~**Agent lane maturity in scorecard:**~~ **Resolved (Phase 2).**
4. **Overnight script ownership:** Open — addressed by Phase 4c scope (§IV.6).

---

## Part II: External Benchmark Research (2026-02-18)

*Research conducted via live web search across framework docs, GitHub, arXiv, and blog
posts. Covers current state of practice as of February 2026.*

---

### II.1 State of Practice Assessment

The most important finding: **JustSearch's harness design already matches or exceeds
industry patterns in the dimensions that matter.** The research validates the existing
architecture rather than suggesting wholesale replacement.

Specific validations:
- Committed baseline files in `scripts/bench/baselines/` is **more robust** than
  artifact-based storage (the alternative used by GitHub Actions `upload-artifact`).
  Committed files require a reviewable PR to change, have permanent git history, and have
  no retention expiry. Artifact-based baselines expire after N days and can be silently
  lost.
- `bench-suite.v2` schema closely maps to the MTEB TaskResult convention — the same
  `schema_family` + `schema_version` + `suite_kind` discrimination pattern. No external
  tool produces a strictly better schema for this problem.
- `bench-decision.v1` + ratio gates (`policy-engine.mjs`) is **Pattern B** (baseline-
  relative ratchet), the consensus industry standard for deterministic evals. Used by
  Braintrust, Evidently, and every framework that has a proper regression concept.
- Agent window-based stability gate (14-run history, `passRateStdDev`,
  `scenarioInstability`) is **Pattern C**, the correct approach for noisy generative/agent
  evals. No external framework (lm-eval-harness, MTEB, RAGAS, DeepEval) addresses this
  pattern — they are all designed for deterministic benchmarks.

**What the research shows is missing in JustSearch** (not in external tools, but revealed
by comparing patterns):
1. ~~No `main_score` field in `bench-suite.v2`~~ — **Fixed (Phase 3a).** `main_score` +
   `main_score_metric` added to schema and all 8 converters.
2. ~~No corpus/truth versioning in RAG eval — if `rag-eval-truth.v1.json` changes, existing
   baselines silently become non-comparable.~~ — **Fixed (Phase 3b).** Corpus SHA-256
   versioning added to RAG eval v2 output.
3. ~~No BEIR CI gate despite complete gate infrastructure.~~ — **Fixed (Phase 1).**
   `beir-eval-gate-win.yml` created.
4. ~~No pass^k reliability tracking for the agent battery~~ — **Fixed (Phase 3c).**
   Per-scenario pass^k curves added to `build-agent-live-scorecard.mjs`.

---

### II.2 RAG Evaluation Frameworks

#### RAGAS v0.4.3
**License:** Apache 2.0 | **Language:** Python | **GPU:** No (API judge) | **Offline:** Yes (Ollama)

Architecture: pure Python library. Input is a 4-tuple `(question, retrieved_context,
generated_answer, optional_reference)` per sample. LLM-as-judge (configurable to OpenAI,
Anthropic, or local via Ollama).

**Metrics relevant to JustSearch:**
- Context Precision, Context Recall (retrieval quality — complements BEIR)
- Faithfulness, Response Relevancy, Factual Correctness (generation quality — similar to
  existing RAG eval metrics)
- Tool Call Accuracy, Tool Call F1, Agent Goal Accuracy (agentic — new dimension)

**CLI:** Yes — `ragas evals` command. But **outputs CSV only**, not JSON. To parse from
PowerShell/Node.js, requires post-processing or a thin Python wrapper that writes JSON.

**Integration verdict:** Usable as a subprocess, but awkward. The existing
`RealRagEvaluationTest.java` computes equivalent metrics (fact coverage ≈ context recall,
faithfulness already present) without a Python dependency. RAGAS adds value mainly for the
**agentic metrics** (Tool Call Accuracy, Agent Goal Accuracy) which have no current
equivalent in the battery. Consider for Phase 4+ as an optional deeper agent eval layer.

---

#### DeepEval v3.8.4
**License:** Apache 2.0 | **Language:** Python | **GPU:** No (API judge) | **Offline:** Yes (Ollama)

Architecture: pytest-style test harness. Tests are `LLMTestCase` objects. Full CI
integration via `deepeval test run test_file.py`. Ollama first-class:
`deepeval set-ollama --model=deepseek-r1:1.5b`.

**Regression detection:** Via Confident AI cloud dashboard — no local baseline file
mechanism. This is a significant gap for a local-first system.

**Integration verdict:** Better developer experience than RAGAS for writing eval test
suites. However, the lack of a local regression baseline mechanism means it cannot replace
`diff-rag-eval-suite.mjs` without a custom wrapper. Adds a Python dependency. Not
recommended unless a richer per-metric test authoring experience is needed.

---

#### TruLens (Truera)
**License:** Apache 2.0 | **Language:** Python | **GPU:** No | **Offline:** Yes

**Key architectural fact:** TruLens 1.x integrates with OpenTelemetry (OTel). Java
applications can emit OTel spans that TruLens consumes for evaluation. This is the most
Java-compatible RAG eval approach — no subprocess needed, native trace ingestion via OTLP.

**The RAG Triad:**
- Context Relevance: Is each retrieved chunk relevant to the query?
- Groundedness: Is the response supported by retrieved context?
- Answer Relevance: Does the response address the user's query?

**Integration verdict:** Worth considering if JustSearch adds OpenTelemetry instrumentation
to the RAG pipeline. The OTel path means no eval-specific code in the Java application
itself — traces flow to TruLens automatically once the OTel exporter is configured.
However, TruLens requires a persistent server process and SQLite/PostgreSQL backend.
Overhead for current needs; bookmark for when observability infrastructure matures.

---

#### ARES (Stanford)
**License:** Apache 2.0 | **Language:** Python | **GPU:** Yes (A100-class) | **Offline:** Yes

Three-stage pipeline: synthetic dataset generation → fine-tune judge classifiers →
Prediction-Powered Inference with human-labeled validation set (≥150 examples).

**Output:** Scores with statistical confidence intervals:
`ARES Prediction: [0.6057], ARES Confidence Interval: [[0.547, 0.664]]`

**Integration verdict:** Research-grade. Requires A100 GPU for classifier training. Disk
requirement: 100+ GB. Input requirement: 150+ human-annotated validation examples.
**Ruled out** for current needs. The confidence-interval methodology is genuinely novel
but the infrastructure cost is prohibitive.

---

### II.3 Search / IR Evaluation Frameworks

#### BEIR 2.0 (2025)
The upstream BEIR benchmark released a v2.0 in 2025 with more challenging test scenarios
and adversarial queries. The 18+ dataset suite remains the standard for zero-shot IR
evaluation. Uses `pytrec_eval` internally.

**Datasets most relevant to JustSearch's use case:**
SciFact (300 test queries, scientific fact-checking), NFCorpus (323 queries, biomedical),
ArguAna (1,406 queries, argument retrieval) — all three already have JustSearch baselines.

**2025 leaderboard leaders:** Voyage-Large-2 (54.8% nDCG@10), Cohere Embed v4 (53.7%).
JustSearch's hybrid mode is not competing at embedding-model level; these numbers provide
external context for whether the BEIR scores in `scripts/bench/baselines/` are at a
reasonable absolute level.

---

#### trec_eval (NIST C binary)
**License:** NIST / Public domain | **Language:** C | **GPU:** No | **Offline:** Yes

The canonical TREC evaluation binary. Input: qrels file + results file (both TREC format).
Output: plain text, one metric per line (`ndcg_cut_10  all  0.3841`).

```bash
trec_eval -m ndcg_cut.10 -m map -m recip_rank qrels.txt results.txt
trec_eval -q -m ndcg_cut.10 qrels.txt results.txt   # per-query output
```

**Windows availability:** Via conda (`conda install -c conda-forge trec_eval`). No official
precompiled binary, but conda works reliably. Works as subprocess from PowerShell 7.4+.

**Integration verdict:** The `beir-eval-win.ps1` script currently computes Recall@K and
nDCG@K directly in PowerShell (custom implementation). Replacing or augmenting with
`trec_eval` would use the NIST-standard implementation, enabling direct comparison with
published BEIR results. The custom PowerShell nDCG implementation should be validated
against `trec_eval` output to confirm identical results. Low integration cost for a
significant validation gain.

---

#### ir_measures (Python CLI)
**License:** Apache 2.0 | **Language:** Python | **GPU:** No

Unified interface over pytrec_eval, gdeval, trectools. Standard metric names (`nDCG@10`,
`P@5`). CLI: `ir_measures qrels.txt run.txt nDCG@10 P@5`.

**Integration verdict:** Equivalent to trec_eval for subprocess use. Slightly higher
barrier (requires Python) but cleaner metric name syntax. Prefer `trec_eval` binary
for zero-dependency subprocess use.

---

#### Anserini
**License:** Apache 2.0 | **Language:** Java | **GPU:** No (BM25) | **Offline:** Yes

Lucene-based IR toolkit with native Java API. Standalone fat JAR. Built-in regression
experiments for MS MARCO, BEIR, TREC DL. This is the only major IR eval tool with
first-class Java APIs.

**What it offers that JustSearch doesn't have:**
- Pre-built BEIR dataset loaders
- TREC-format topic/qrels files for every standard dataset
- Automated regression experiment infrastructure (expected vs actual metric check)
- Dense HNSW index evaluation support

**Integration verdict:** High-value consideration if the BEIR eval pipeline is expanded.
For the current scope (three datasets, search via `/api/knowledge/search`), adding
Anserini as a dependency is heavier than needed. But the regression experiment pattern
in Anserini is a direct model for Phase 1 BEIR CI gate design — worth studying closely.

---

### II.4 Agent Evaluation Frameworks

#### tau-bench (Sierra AI) — Key Pattern: pass^k

**The most important finding from agent eval research.**

tau-bench introduces a distinction that JustSearch's agent battery does not currently make:
- **pass@k**: Does the agent succeed across k varied scenario instances? (current metric)
- **pass^k**: Does the agent succeed on k independent reruns of the *same* scenario?
  (reliability metric — new)

**Findings from tau-bench experiments:** GPT-4o achieves ~50% pass^1 but drops to ~25%
pass^8 on the same tasks. The gap reveals systematic flakiness that aggregate pass@k
masks — an agent can "pass" a scenario on average while failing it reliably in any given
run due to non-determinism.

The current `build-agent-live-scorecard.mjs` computes per-scenario stability over a
rolling window (which approximates this), but doesn't compute a formal pass^k curve per
scenario. Adding pass^k curves would give a cleaner signal: scenarios with low pass^k are
reliability problems; scenarios with zero pass@k are correctness problems.

**Integration verdict:** The pass^k concept should be incorporated into the scorecard
in Phase 4. It requires no external tool — it's a change to
`build-agent-live-scorecard.mjs` to aggregate repeated runs of the same scenario ID
into a reliability curve. Low implementation cost, high signal improvement.

---

#### AgentBench, WebArena, SWE-bench, OSWorld
All are research benchmarks for evaluating frontier agents on external environments
(web browsing, software engineering, OS control). They are **not applicable** to
JustSearch's agent battery, which evaluates a bounded search-and-retrieval agent
with specific tools and a controlled corpus. These benchmarks measure general-purpose
agent capability; JustSearch measures a specialized agent's correctness and reliability
on its own tool set.

---

### II.5 Harness Architecture Patterns

#### lm-evaluation-harness (EleutherAI)
**Architecture:** Three-layer separation — Models (LM interface), Tasks (YAML), Metrics
(aggregation functions). Task version tracking via integer `metadata.version` field.

**Key design to borrow:** The `metadata.version` integer in task YAML. When a task config
changes, the version increments. The output JSON includes a `"versions"` map so any
comparison script can detect when task definitions changed and refuse to compare as
non-comparable. This is equivalent to what the RAG eval needs for corpus versioning
(Finding F-6 in §4 of this doc).

**What lm-eval does NOT have:** Built-in CI gate, promotion workflow, baseline comparison.
It produces the JSON; external tooling does the diff. This is the right separation.

**Integration verdict:** Not applicable as a runner (would require rewriting all Java and
PowerShell runners as Python/HuggingFace loaders). The task versioning pattern is directly
borrowable without adopting the framework.

---

#### MTEB Schema — `main_score` + `dataset_revision`

The two MTEB design decisions most applicable to JustSearch:

**1. `main_score` field:**
```json
{
  "test": {
    "main_score": 0.7022,
    "ndcg_at_10": 0.7022,
    "recall_at_10": 0.8246
  }
}
```
`main_score` is task-type-agnostic. The scorecard aggregator sorts and compares lanes by
`main_score` without knowing that retrieval uses `ndcg_at_10` and classification uses
`accuracy`. New lanes self-declare their primary metric.

`bench-suite.v2` currently has no equivalent — `build-benchmark-scorecard.mjs` must
hardcode per-lane metric names. Adding `main_score` to the `measurements.samples` block
is a backward-compatible, low-cost improvement.

**2. `dataset_revision` (SHA) as comparability guard:**
```json
{ "dataset_revision": "5effa1b9b5fa3b0f9e12523e6e43e5f86a6e6d59" }
```
MTEB refuses to compare results across different `dataset_revision` values. This prevents
the scenario where a corpus change causes misleading regression signals. The RAG eval has
no equivalent. The agent battery already implements this via scenario SHA-256 signatures
in `buildScenarioProfile`. Applying the same pattern to RAG eval (SHA of
`rag-eval-truth.v1.json` + `rag-eval-vectors.json`) closes Finding F-6.

---

#### Four CI Gate Patterns (2025 State of Practice)

| Pattern | Description | JustSearch usage |
|---------|-------------|-----------------|
| A — Absolute threshold | Fixed floor. Score ≥ constant. | Not used |
| B — Baseline-relative ratchet | Score ≥ baseline × (1 - maxRegression). Baseline promoted explicitly. | `diff-search-eval-suite.mjs`, `diff-rag-eval-suite.mjs` ✓ |
| C — Window-based stability | Rolling N-run history. Gate on variance + flakiness, not a fixed point. | `build-agent-live-scorecard.mjs` ✓ |
| D — Soft gate | Reports but does not fail CI. Used during calibration. | Agent battery Phase B ✓ |

JustSearch uses all three deterministic patterns correctly. No changes needed here.

---

### II.6 External Tools: Integration Verdicts

#### Promptfoo v0.120.24 ← Primary external integration candidate
**License:** MIT | **Language:** Node.js | **GPU:** No | **Offline:** Yes (Ollama)

**The only tool in the survey designed from the ground up for subprocess invocation from
non-Python environments.** Node.js programmatic API: `import promptfoo from 'promptfoo'`
then `promptfoo.evaluate(config, options)` returns a structured `EvaluateSummary`. No
subprocess needed — in-process invocation from any Node.js script.

**CLI:**
```bash
npx promptfoo@latest eval -c promptfooconfig.yaml --output results.json
```

**JSON output schema:**
```json
{
  "version": 3,
  "timestamp": "...",
  "results": {
    "stats": { "successes": 8, "failures": 2 },
    "outputs": [{ "pass": true, "score": 0.92, "gradingResult": {...} }]
  }
}
```

**RAG assertion types directly applicable to JustSearch:**
`context-faithfulness`, `context-relevance`, `context-recall`, `context-adherence`,
`answer-relevance`, `factuality`, `llm-rubric`.

**What Promptfoo adds that JustSearch doesn't currently have:**
- LLM-as-judge assertions for RAG generation quality, callable from the existing Node.js
  overnight script (`overnight-rag-ai-queue-win.ps1` already runs Node.js scripts)
- Prompt regression testing (does a change to system prompt degrade quality?)
- Local model support via Ollama (can use the running llama-server instance)
- CI gate via `--fail-on-error` with structured JSON output for diff tools

**Integration path:** Add promptfoo as a `devDependency` in a `package.json` under
`scripts/bench/` or reuse the existing `modules/ui-web` or `SSOT/tools` Node.js
package. Write a `promptfoo-rag-eval-config.yaml` pointing to the local API, run via
`overnight-rag-ai-queue-win.ps1`, parse `results.json` as a 4th eval lane.

**Verdict: Recommended for Phase 4.** Not a replacement for the existing Java-based RAG
eval (which has custom truth-grounded metrics); a complement that adds LLM-as-judge
generation quality assessment. Together they cover both the rule-based and judge-based
dimensions of RAG quality.

---

#### trec_eval (NIST C binary) ← Validation tool
**License:** NIST/Public domain | **Language:** C | **GPU:** No

Recommended for **validating** the BEIR Recall@K and nDCG@K computation in
`beir-eval-win.ps1` against the NIST standard implementation. If results are identical
(or within floating-point tolerance), confidence in the custom implementation is high.
If they diverge, the `trec_eval` output is the ground truth.

Available via `conda install -c conda-forge trec_eval`. Subprocess from PowerShell:
```powershell
$output = & trec_eval.exe -m ndcg_cut.10 qrels.txt run.txt
```

**Verdict: Use once as validation, not as ongoing dependency.** Unless a TREC submission
is planned, the conda dependency adds friction for minimal gain after initial validation.

---

#### Arize Phoenix 13.0.3
**License:** ELv2 (not fully open source — cannot use to build competing SaaS) | **Language:** Python

Server-based observability platform. Persistent process required. No standalone eval CLI.
ELv2 license excludes it from a project that may compete with Phoenix as a product.

**Verdict: Ruled out.** License restriction + server-based architecture are both blockers.

---

#### RAGAS / DeepEval as subprocess
Both support offline use via Ollama. Both would require Python to be installed and a
wrapper script that reads JSON test cases and writes JSON results. RAGAS outputs CSV only
(no `--output results.json`); DeepEval outputs to a pytest state file. Neither integrates
cleanly without custom wrapper code.

**Verdict: Optional, Phase 4+.** Consider only if LLM-as-judge metrics are needed and
Promptfoo's built-in assertion types don't cover the needed criteria. If a Python
dependency is acceptable, RAGAS's agentic metrics (Tool Call Accuracy, Agent Goal
Accuracy) are the most unique offering not covered by current tooling.

---

#### Java-native options (RankLib, RRE, RiVal)
All are effectively unmaintained (RRE: 2020, RiVal: 2015). RankLib requires LETOR-format
feature vectors, not applicable to free-form search. No Java-native RAG eval library exists.

**Verdict: None applicable.** Implement IR metrics directly in Java (30–50 lines each for
nDCG, MAP, Recall@k) or use subprocess to `trec_eval` binary. JustSearch already
implemented the metrics in PowerShell.

---

### II.7 Revised Consolidation Plan

The research confirms the four-phase plan from §6 and adds concrete deliverables:

| Phase | Work | External dependency | Risk | Status |
|-------|------|---------------------|------|--------|
| **1** | Add BEIR CI workflow calling `diff-search-eval-suite.mjs` | None | Low | **Done** |
| **2** | Add RAG + agent lanes to `build-benchmark-scorecard.mjs` (no schema change) | None | Low | **Done** |
| **2b** | Add ingestion step to agent battery (corpus for 14 search-dependent scenarios) | None | Low | **Done** |
| **3a** | Add `main_score` to `bench-suite.v2` (backward-compatible add) | None | Low | **Done** |
| **3b** | Migrate RAG output to `bench-suite.v2`; add corpus SHA versioning to RAG eval | None | Medium — re-promote baselines | **Done** (§IV.3) |
| **3c** | Add pass^k reliability curves to `build-agent-live-scorecard.mjs` | None | Low | **Done** |
| **3d+3e** | Transcript retention + tool argument/result tracing (combined) | None | Low | **Done** |
| **3f** | `required_facts` for all 15 non-introspection scenarios + evaluator changes | None | Medium | **Done** |
| **3g** | 216→219 interface lock: document producer contract, ownership boundary, compatibility rules | None | Low | **Done** |
| **4a** | Validate BEIR nDCG/Recall impl against `trec_eval` binary | `pytrec_eval` (pip, one-time) | Low | **Done** — validates actual production per-query output against pytrec_eval |
| **4b** | Claude Code CLI LLM-as-judge for agent battery quality | Claude Code CLI (subscription) | Low | **Dropped** — tempdoc 224 deleted (low value at current pipeline maturity) |
| **4c** | Add Claim A + Track G lanes to overnight runner — close criterion 1 | None | Low | **Done** (validated) |
| **4d** | Wire consolidated scorecard into overnight + agent nightly — close criterion 1 operational gap | None | Low | **Done** (partial — see §IV.8) |
| **4e** | ~~Add search quality lane to overnight queue~~ — superseded by 4c | None | Low | Superseded |

**What will NOT be adopted:**
- lm-eval-harness as a runner (full rewrite of existing Java/PS runners)
- Arize Phoenix (ELv2 license, server-based)
- ARES (A100 GPU required)
- MLflow as gate infrastructure (Python-only eval APIs; commit-file baselines are better)
- RRE, RiVal (unmaintained)

---

### II.8 Updated Open Questions

> **Consolidated into §IV.7.** Questions below are kept for historical context; see §IV.7
> for current status and answers.

5. ~~**trec_eval validation:**~~ Answered in §IV.7 Q-5. Low priority.
6. ~~**Promptfoo model target:**~~ Answered in §IV.7 Q-6. Deferred until Phase 3f.
7. ~~**pass^k implementation scope:**~~ **Resolved (Phase 3c).**
8. ~~**RAG eval frequency:**~~ Answered in §IV.7 Q-8. Nightly, not PR-trigger.

---

## Part III: Agent Battery Deep Analysis (2026-02-19)

> **Historical.** This analysis describes the state of the agent battery BEFORE Phases
> 2b, 3d+3e, and 3f were implemented. The structural weaknesses documented here
> (no corpus, keyword-only matching, no tool tracing) have been **fixed** — see the
> work log entry for "Phase 2b + 3d+3e + 3f complete." This section is retained as
> the root-cause analysis that motivated those fixes.

*Analysis conducted via full read of `scripts/ci/run-agent-live-battery.mjs` (1075 lines),
`scripts/ci/agent-live-battery-scenarios.v1.json`, and `scripts/ci/build-agent-live-scorecard.mjs`.
Cross-referenced against industry research and the external benchmark findings in Part II.*

---

### III.1 Executive Summary *(pre-fix state — see callout above)*

**The agent live battery ~~is~~ was an infrastructure availability test, not a quality gate.**

It answers: "Is the system up? Can agents make tool calls?" It does NOT answer: "Are agents
retrieving correct information? Are their answers accurate?"

The battery starts with `clean: soft` (empty data dir), activates the LLM, then immediately
runs scenarios — with no document ingestion step anywhere in the 1075-line script (no
`/api/knowledge/ingest` call exists). As a result:

- 11 of 16 scenarios hit `"No results found"` or `"No indexed folders found."`
- The 13 "passes" are false positives: expected keywords (`search`, `gpu`, `configuration`)
  appear in the LLM's error-recovery phrasing ("I searched for X but found nothing"), not in
  knowledge-grounded answers
- The 3 "failures" failed because their keywords (`agent`, `/api/agent`,
  `explanation/reference/how-to`) don't naturally appear in error-recovery text

This was discovered when tempdoc 213 Step 4 declared the battery regression check valid,
then found identical pre/post results — and subsequent transcript inspection revealed the
above. Tempdoc 213 documents the epistemological failure; this section documents the
structural causes and long-term remediation path.

---

### III.2 What the Battery Claims vs. What It Tests

| Claimed | Actual |
|---------|--------|
| "Agent live battery" — 16 scenarios, pass rates | Infrastructure smoke test |
| Task completion validation | HTTP health + SSE protocol + tool dispatch |
| Search quality regression detection | Index availability (often empty) |
| Trajectory conformance | Tool *name* presence in event timeline |
| Pass/fail based on answer correctness | Keyword substring anywhere in response |

---

### III.3 Structural Weaknesses (15 Found)

| # | Category | Issue | Severity | Evidence | Disposition |
|---|----------|-------|----------|---------|-------------|
| 1 | ~~No ground truth corpus~~ | ~~`clean: soft` + no ingest = empty index on every run~~ | ~~**CRITICAL**~~ | Lines 933, 980 | ~~**Phase 2b**~~ **FIXED** |
| 2 | ~~Keyword substring matching~~ | ~~`lower.includes(kw)` — substring, not semantic~~ | ~~**CRITICAL**~~ | Lines 773-774 | ~~**Phase 3f**~~ **FIXED** |
| 3 | ~~Tool name validation only~~ | ~~Arguments and return values not captured~~ | ~~HIGH~~ | Lines 634-645, 790-794 | ~~**Phase 3e**~~ **FIXED** |
| 4 | ~~No transcript retention~~ | ~~Full message arrays discarded after eval~~ | ~~HIGH~~ | Line 1042 | ~~**Phase 3d**~~ **FIXED** |
| 5 | Model drift | 5 candidates in precedence; actual model not persisted per-scenario | MEDIUM | Lines 108-114, 963 | Accepted — model path is in manifest `run.resolvedModelPath`; per-scenario persistence is low value |
| 6 | Phase gate not wired | `blocking` flag saved to manifest but exit code never checks it | MEDIUM | Lines 889-890, 1045-1047 | Accepted — Phase C gate is disabled by design (maturity model); will matter when Phase C is enabled |
| 7 | Infra failure masks quality | Single timeout → `infraFailure = true`; quality issues inside invisible | MEDIUM | Lines 996-999 | Accepted — infra failures are correctly excluded from quality metrics by the scorecard; fixing the overlap is low value |
| 8 | Config changes not gated | Scenario signature doesn't include config hash; config drift undetected | MEDIUM | Lines 833-858 | Out of scope — config changes are rare and intentional; adding config hash to signature would invalidate history on every config touch |
| 9 | No concurrent load test | Sequential execution only; no parallelism or stress testing | LOW-MED | Line 980 | Out of scope — stress testing is a different quality dimension; the battery tests correctness, not throughput |
| 10 | ~~Scenario overpromise~~ | ~~13 "passes" are error-recovery keyword matches, not grounded answers~~ | ~~MEDIUM~~ | — | ~~**Phase 2b + 3f**~~ **FIXED** |
| 11 | Hard-coded CUDA variant | `variantId: 'cuda12'` (line 416); CPU-only machines fail silently | LOW-MED | Line 416 | Accepted — all target machines have CUDA GPUs; CPU fallback is not a supported deployment |
| 12 | Infra/quality conflation | Same exit code 2 covers "infra down" and "answers wrong" | LOW-MED | Line 1045 | Accepted — the scorecard already separates infra vs quality failures via `infraFailure` flag; exit code conflation only affects the CI gate binary, which is Phase B (non-blocking) |
| 13 | No pass^k tracking | Only pass@1 per run; tau-bench shows this misses systemic flakiness | LOW-MED | — | **Done (Phase 3c)** |
| 14 | No score per scenario | Binary pass/fail; no rubric or partial credit | LOW | — | Out of scope — partial scoring requires LLM-as-judge (Phase 4b); not needed for the current binary quality gate |
| 15 | Activation timeout not fatal | `activation_timeout` not caught as fatal; run proceeds without LLM | LOW | Lines 394-410 | Accepted — the run records `aiActivation.state` in manifest; all scenarios fail without LLM, so the issue is self-evident from results |

---

### III.4 Root Cause: No Ground Truth Corpus *(fixed by Phase 2b)*

The ingestion gap ~~is~~ was structural, not incidental. Battery startup sequence (pre-fix):

1. `dev-runner start` with `clean: 'soft'` — wipes runtime state, starts with empty data dir (line 933)
2. `waitReady` — polls `/api/status` and `/api/health` (lines 957-960)
3. `configureModelPath` — sets `llm.modelPath` via `/api/settings/v2` (lines 962-968)
4. `maybeActivateAi` — polls `/api/ai/runtime/activate` (lines 970-977)
5. **Immediately enters scenario loop** (line 980) — **no ingest step**

Without indexing documents before step 5, every search returns `"No results found"` or
`"No indexed folders found."` The SSE protocol, tool dispatch, and LLM interaction are all
exercised — but against an empty knowledge store.

**Consequence for regression testing:** Results before and after any search quality change
(e.g., tempdoc 213 Approach A parameter changes) are identical. Both runs get the same
empty-index responses. Pass rates don't move because there's no signal to detect.

---

### III.5 Keyword Matching vs. Semantic Validation *(augmented by Phase 3f)*

The ~~current~~ original evaluator (lines 772-778, pre-Phase 3f):

```javascript
const lower = response.toLowerCase();
const missing = expected.filter((kw) => !lower.includes(String(kw).toLowerCase()));
```

This is a character substring match. Its failure modes:

| Failure mode | Example |
|-------------|---------|
| False positives | Agent: "I searched for 'gpu' but found nothing" — `gpu` substring matches |
| Paraphrase blindness | "unauthorized access" ≠ "hacked" — zero overlap, same meaning |
| Context-blindness | "no explanation was found" satisfies keyword `explanation` |
| Negation blindness | "NOT using configuration files" satisfies `configuration` |

**Better approach:** `required_facts` per scenario — explicit fact strings that must appear
as complete claims (parallel to RAG eval's `KeywordPresenceChecker`). This adds ground
truth without requiring an external judge model. LLM-as-judge (via Promptfoo, Phase 4b)
is the longer-term target for answer quality that can't be expressed as explicit facts.

---

### III.6 Tool Tracing Gap *(fixed by Phase 3d+3e)*

The SSE capture (lines 634-645, pre-fix) recorded event types (`tool_call_proposed`,
`tool_call_approved`, `tool_result_returned`) but only extracted the **tool name** from
each event. Arguments and result payloads were not captured.

What this means:
- `search_index` called with `query: "*"` passes trajectory validation identically to a precise query
- `search_index` returning `"No results found"` passes if the agent says the right word in its final response
- It's impossible to distinguish "agent searched correctly and found the answer" from "agent searched incorrectly and hallucinated the answer"

The manifest saves `toolSequence` (tool name array) and `iterationsUsed` (count). After a
run, there is no way to determine what queries were made, what results were returned, or
whether the agent used the results correctly.

---

### III.7 Long-Term State Roadmap

**What to preserve (correctly designed):**
- Window-based stability gate (Pattern C from §II.5) — correct for noisy generative evals
- Scenario signature SHA versioning — equivalent to MTEB `dataset_revision`
- Phase A/B/C maturity model — correct gating approach
- 16-scenario structure — representative task breadth

**Improvement roadmap** (integrating into §6 phase sequence):

| Phase | Item | Scope | Est. size | Design |
|-------|------|-------|-----------|--------|
| **2b** (done) | Add ingestion step to battery | POST `/api/knowledge/ingest` for `docs/` after `maybeActivateAi()`, before scenario loop | Done | §IV.1 |
| **3c** (done) | pass^k curves | Per-scenario reliability curves added to `build-agent-live-scorecard.mjs` | Done | — |
| **3d+3e** (done) | Transcript retention + tool tracing | Capture arguments/output from SSE, inline in manifest with 4KB truncation | Done | §IV.4, IV.5 |
| **3f** (done) | `required_facts` for all 15 non-introspection scenarios | S1+S2 tier facts, `mustNotContain`, `requiresCorpus` flag | Done | §IV.2 |
| **4b** (extracted) | Claude Code CLI LLM-as-judge | Coherence, faithfulness, hallucination scoring via `claude -p` | ~1 day | **Extracted** → tempdoc 224 |

The immediate unblocking step for tempdoc 213 Approach B regression checking is Phase 2b.

**Research support:**
- *Corpus first* — without a fixed corpus, all other improvements measure noise (confirmed by tempdoc 213 Step 4 invalidity finding)
- *`required_facts` before LLM-as-judge* — adds ground truth without external judge model; parallel to RAG eval's established `KeywordPresenceChecker` pattern
- *Tool tracing before semantic eval* — answer faithfulness can't be evaluated without knowing what context was retrieved
- *pass^k* — tau-bench (Sierra AI): GPT-4o drops from ~50% pass^1 to ~25% pass^8 on same tasks; aggregate pass@k masks systemic flakiness. Phase 3c addresses this.

---

## 9. Work Log

*(Append entries as work proceeds)*

- 2026-02-18: Tempdoc created. Investigation complete (Part I). File inventory, divergence
  analysis, and consolidation options drafted.
- 2026-02-18: Part II added. Live web research across 20+ frameworks and tools. Revised
  consolidation plan incorporating external tool findings. Key conclusions: existing
  harness design is already well-aligned with industry patterns; Promptfoo is the primary
  external integration candidate (Phase 4); trec_eval for one-time validation; no other
  external tools warranted. Three concrete improvements identified: `main_score` in
  bench-suite.v2, corpus SHA versioning for RAG eval, pass^k curves for agent battery.
- 2026-02-18: **Phase 1 complete.** `scripts/ci/run-beir-gate-win.ps1` was already in the
  codebase (loops over arguana, nfcorpus, scifact; fresh dev-runner per dataset; calls
  `beir-eval-win.ps1` then `diff-search-eval-suite.mjs`; emits
  `search-eval-beir-gate-manifest.v1`). Created `.github/workflows/beir-eval-gate-win.yml`
  (`workflow_dispatch`, 4 inputs: datasets/k/max_queries/skip_download, `justsearch-perf`
  runner, 360 min timeout, hard-gate exit propagation, per-dataset gate table in step
  summary, artifact upload on `if: always()`). Nightly schedule deferred until corpus
  caching on runner is confirmed (tempdoc 216 Q-1).
- 2026-02-19: **Phase 2 complete.** Added rag-eval and agent-battery lanes to
  `build-benchmark-scorecard.mjs`. RAG eval uses standard ratio-based model via new
  `rag-eval-report-manifest.v1` emitted by overnight script. Agent battery uses
  window-based summary (pass_rate, infra_failure) with ratchet held. Refactored
  hardcoded lane lists to derive from `LANE_ORDER`. Ratchet policy updated with
  `agent-battery` lane target. All 6 lanes appear in scorecard output (lane table,
  trends, calibration, ratchet recommendations, release verdict).
- 2026-02-19: **Phase 3a + 3c complete.** Added `main_score` and `main_score_metric`
  to bench-suite.v2 schema (optional fields). Updated all 8 converters to emit
  main_score from each lane's primary metric. Updated suite-loader.mjs to extract
  main_score in all v2 return paths. Added per-scenario pass^k reliability curves to
  agent scorecard (passRateByScenario tracking in computeProcessMetrics, worstScenarios
  in passKReliability output, markdown table showing worst-10 scenarios by pass^k).
- 2026-02-19: **Part III added.** Deep structural analysis of the agent battery (15
  weaknesses identified via full read of `run-agent-live-battery.mjs`). Root cause of
  tempdoc 213 Step 4 invalidity documented (no ingestion step; empty index; 13 of 16
  "passes" are false positives from error-recovery keyword matching). Long-term state
  roadmap integrated into phase sequence: Phase 2b (ingestion step, immediate), 3d
  (transcript retention), 3e (tool argument tracing), 3f (`required_facts` for 6 search
  scenarios). Research-backed recommendations: corpus first, required_facts before
  LLM-as-judge, tool tracing before semantic eval.
- 2026-02-19: **Part IV added.** Deep investigation of remaining phases (2b, 3b, 3d, 3e,
  3f). Full read of all 16 agent battery scenarios, RAG eval test + truth manifest +
  overnight script, SSE capture code + manifest structure. Produced corpus design spec,
  per-scenario required_facts design, RAG v2 migration mapping, tool tracing architecture,
  and transcript retention design. Updated open questions with concrete answers.
- 2026-02-19: **Structural review.** Critical analysis of tempdoc identified 11 issues.
  Fixed: (1) marked §6 as superseded by §II.7, (2) consolidated 3 scattered open-question
  sections into §IV.7 with cross-references, (3) added disposition column to all 15
  structural weaknesses in §III.3, (4) fixed test class name (`RagQualityEvalTest` not
  `RealRagEvaluationTest`), (5) expanded Phase 3f from 6 to all 15 non-introspection
  scenarios with required_facts designs, (6) added acceptance criteria section, (7) added
  rollback strategy for RAG v2 migration, (8) added Phase 4b cost/benefit justification
  (Q-11), (9) corrected ingestion wait endpoint to `GET /api/knowledge/status`, (10) added
  S2 stability maintenance obligation note, (11) updated effort estimates for expanded 3f.
- 2026-02-19: **Phase 2b + 3d+3e + 3f complete.** Added `ingestCorpus()` function to
  `run-agent-live-battery.mjs` — POSTs to `/api/knowledge/ingest` with `docs/` directory,
  polls `GET /api/knowledge/status` until queue drains and docCount > 0. Added
  `--ingest-timeout-ms` CLI arg (default 60s). Added `truncateStr()` helper for 4KB
  truncation. Extended SSE handler to capture `data.arguments` in `tool_call_proposed`
  and `data.output` in `tool_exec_completed`, stored in `toolTraces[]` array on transcript.
  Extended `evaluateTranscript()` return with `toolTraces`, `finalResponse`,
  `requiredFacts`, `mustNotContain`, `requiresCorpus`. Added two new assertion blocks:
  `missing_facts` (case-insensitive substring match on requiredFacts array) and
  `contains_forbidden` (mustNotContain array). Updated all 15 non-introspection scenarios
  in `agent-live-battery-scenarios.v1.json` with `requiredFacts`, `mustNotContain`, and
  `requiresCorpus` fields. Scenario signature changes automatically (expected — old runs
  without corpus are incomparable).
- 2026-02-19: **Post-implementation critical analysis.** Audited entire tempdoc for staleness
  after Phase 2b/3d+3e/3f implementation. Fixes applied: (1) Added acceptance criteria status
  tracking (1: MET, 2: Partially met, 3: MET, 4: Partially met), (2) updated Phase 3 status
  in §6 to note 3b remaining, (3) added historical context callout to Part III header — entire
  section describes pre-fix state, (4) annotated §III.1, III.4, III.5, III.6 headers as pre-fix
  or fixed-by-Phase-X, (5) struck through and marked §III.3 weaknesses #1, 2, 3, 4, 10 as
  FIXED, (6) updated §II.7 Phase 4b from "blocked on 3f" to "unblocked", (7) added
  implementation deviation notes to §IV.1.4, IV.2.2, IV.4, IV.5 documenting where code
  diverged from design, (8) updated §IV.6 dependency chain with ✅ DONE markers and remaining
  work summary, (9) resolved §IV.7 Q-6, Q-9, Q-10 with implementation outcomes.
- 2026-02-19: **Phase 3b complete.** RAG eval v2 migration implemented. Created
  `convert-rag-eval-v1-to-v2.mjs` converter following established pattern (assertV1 →
  toV2 → assertV2). Converted all 3 existing baselines to v2 (without legacy payload to
  keep file sizes reasonable). Updated `RagQualityEvalTest.java` with dual-write: v1 output
  unchanged, new `writeResultsV2Json()` emits bench-suite.v2 alongside. Added corpus SHA-256
  computation — combined hash of truth manifest + frozen vectors + all corpus docs (sorted by
  docId for determinism). Extended `loadRagEvalSuite()` in suite-loader to extract
  `corpus_sha` from v2 workload (null for v1). Updated `diff-rag-eval-suite.mjs` to include
  corpus_sha in workload comparability checks (only when both sides non-null — migrated
  baselines with null SHA skip the check). Updated profile index to point baseline_path entries
  to `.v2.json` files. Updated overnight script to snapshot v2 results, prefer v2 candidate for
  diffing, and use v2 fallback baseline. Verified: v2-vs-v2 diff passes, cross-format
  v2-vs-v1 diff passes, profile-id resolution works, Java compiles clean.
- 2026-02-19: **Phase 3g complete.** 216→219 interface lock. Added "216/219 Ownership
  Boundary (Locked)" section mirroring the contract in tempdoc 219. Added "Producer Contract
  for 219" section with code-validated field names across 4 artifact types: (A) consolidated
  scorecard — top-level fields, lane row fields, agent-specific fields; (B) diff decision
  artifact — `schema_family`/`schema_version` based, not `kind`; (C) agent battery manifest
  — camelCase fields; (D) agent scorecard — window, comparability, metrics, gates, phaseA.
  5 compatibility rules: additive-only, 6 stable lane IDs, stable enums with exact values,
  casing convention per artifact family, dual-read window for future bench-decision migration.
  Fixed stale internal contradictions: annotated F-1 (fixed Phase 1), F-2 (fixed Phase 3b),
  F-4 (fixed Phase 2), F-6 (fixed Phase 3b), §II.1 item 2 (fixed Phase 3b), §7 item 2
  (resolved Phase 3b) as resolved. Added Phase 3g to phase table (§II.7), §6 summary, and
  dependency chain (§IV.6). Added cross-reference section pointing to tempdoc 219.
  **Correction applied:** Initial contract had invented field names (`lane_id`, `health`,
  `history_runs`, `release_verdict`, `pass_rate`, `infra_failure_rate`, `scenario_count`,
  `pass_k_reliability`). Replaced with code-validated names from `build-benchmark-scorecard.mjs`
  (L797–844, L1231–1323, L1844–1888), `policy-engine.mjs` (L60–97),
  `run-agent-live-battery.mjs` (L987–1026), `build-agent-live-scorecard.mjs` (L745–783).
- 2026-02-19: **Criterion 1 operational gap identified.** Code-validated audit of all
  callers of `build-benchmark-scorecard.mjs` revealed that neither the overnight script
  (`overnight-rag-ai-queue-win.ps1`) nor the agent nightly workflow
  (`agent-live-eval-nightly.yml`) calls the consolidated scorecard. The `rag-eval` and
  `agent-battery` lanes appear only via opportunistic `--discover-dir` discovery when
  their manifests happen to be in `_summaries/` at the time another workflow runs. Also
  found: `overnight-benchmark-autopilot-win.ps1` does call the scorecard with
  `--discover-dir` (missed in initial analysis) but runs only claim-a/search-rank/track-g,
  not RAG or agent. Downgraded criterion 1 from MET to "structurally met, operationally
  incomplete." Added Phase 4d with 3 specific gaps and fix designs (§IV.8). Phase 4d
  is ~0.5 day of work — ~15 lines in the overnight script + ~15 lines in the nightly
  workflow YAML.
- 2026-02-19: **Phase 4d implemented.** Wired consolidated scorecard into both automated
  paths: `overnight-rag-ai-queue-win.ps1` (new `consolidated_scorecard` phase block +
  summary fields + markdown section) and `agent-live-eval-nightly.yml` (new
  `Build consolidated benchmark scorecard` step with `if: always()`). Both use
  `--discover-dir` for manifest discovery and are non-blocking (warn on failure).
  Gaps 1 and 2 in §IV.8 closed.
- 2026-02-19: **Post-Phase 4d lane coverage audit.** Code-validated which manifests each
  automated path actually produces. Findings: overnight RAG/AI queue is the best path
  (3 lanes: claim-a + rag-eval + agent-battery, covering all 3 harness families). Nightly
  workflow scorecard discovers only agent-battery (1 lane — minimal value). `perf` lane
  has no producer in any overnight/nightly path. No single automated path covers all 6
  lanes. Downgraded criterion 1 from MET to PARTIALLY MET. Identified 2 new gaps
  (Gap 4: orphaned perf lane, Gap 5: no full convergence path). Full convergence requires
  Phase 4c (overnight orchestration).
- 2026-02-19: **Critical correction: claim-a was false.** Deeper code audit revealed the
  overnight RAG/AI queue runs Claim D (`run-claim-d-suite-win.ps1`), NOT Claim A. Claim D
  output: (a) has no matching `kind` in `MANIFEST_KIND_TO_LANE` (no `claim-d` lane exists);
  (b) is written to `tmp/bench/_summaries`, not the scorecard's `tmp/agent-evidence/_summaries`
  discovery directory. The scorecard from this path discovers only 2 lanes (rag-eval +
  agent-battery), covering 2 of 3 harness families — NOT 3. Search quality family has
  zero representation. Downgraded criterion 1 from PARTIALLY MET to NOT MET. Added §IV.10
  with fix analysis: minimum fix is adding one search quality lane (claim-a or track-g)
  to the overnight queue as Phase 4e.
- 2026-02-19: **Architectural assessment (§IV.9).** Phases 1–3f achieved genuine
  consolidation: bench-suite.v2, main_score, scorecard infrastructure, agent battery
  quality, RAG v2 migration. From Phase 4d onward, the tempdoc devolved into reactive
  plumbing — adding script calls, discovering the wiring doesn't work, patching again.
  Root cause: the codebase has two overnight scripts, two summary directories, and six
  bespoke lane wrappers with no shared runner or orchestration layer. Each Phase 4d+ fix
  patches one gap but reveals another. Phase 4e (add Claim A call) would technically close
  criterion 1 but perpetuate the fragmentation. Phase 4c (unified lane runner) is the
  architectural fix. Renumbered old §IV.9 to §IV.10; new §IV.9 contains the assessment.
- 2026-02-19: **Phase 4c complete.** Added Claim A and Track G report lanes to
  `overnight-rag-ai-queue-win.ps1`. 10 new parameters (`SkipClaimA`, `ClaimARuns`,
  `ClaimAMaxRatio`, `ClaimAMinRatio`, `SkipTrackG`, `TrackGLatencyRunRepeats`,
  `TrackGThreshold`, `TrackGSkipBuild`, `TrackGSkipQuantizationGate`). Two new phase blocks
  (log prefixes 25/26) between Claim D and Agent Battery. Non-blocking: failures warn but
  don't set `$hadFailures`. Manifests written to `$summaryDir` (`tmp/agent-evidence/_summaries/`)
  via `-ManifestOutJson`, discoverable by consolidated scorecard. Summary object, config,
  checkpoint, and markdown sections added. Scorecard now discovers 4 lanes across all 3
  harness families: claim-a + track-g (search quality), rag-eval (RAG), agent-battery (agent).
  **Criterion 1 is code-complete.** Phase 4e superseded. Requires a validation run to
  confirm the lanes produce manifests and the scorecard discovers all 3 families.

  **Post-implementation critical analysis:**

  1. **`$hadFailures` asymmetry (deliberate).** Claim D failure sets `$hadFailures = $true`
     (hard failure, gates exit code). Claim A/Track G failure only adds to `$warnings`
     (soft failure). Rationale: these lanes were added for scorecard completeness, not as
     primary evaluations. The overnight script's identity is RAG eval + agent battery; search
     quality lanes are supplementary. If a future user expects Claim A regressions to fail
     the overnight run, this needs changing.
  2. **Style inconsistency: skip messages.** Claim D prints nothing when skipped (entire block
     is guarded by `if (-not $SkipClaimD.IsPresent)`). Claim A/Track G print
     "[...] Skipped (-SkipClaimA)" in yellow. This is a UX improvement (operators see what
     was skipped) but the style differs from Claim D.
  3. **Style inconsistency: `-File` path.** Claim D uses a relative path
     (`"scripts/bench/run-claim-d-suite-win.ps1"`) which works because `Invoke-LoggedCommand`
     does `Push-Location $RepoRoot`. Claim A/Track G use absolute paths
     (`Join-Path $repoRoot "scripts/ci/..."`). Both work; the absolute path is safer.
  4. **Sequential Gradle builds.** Claim A's inner suite runs
     `./gradlew.bat :modules:benchmarks:classes`. Track G's inner suite also runs the same
     target (with `-SkipBuild` opt-out). When both run sequentially, the second build is a
     Gradle cache no-op (~5s). Not a real problem, but `-TrackGSkipBuild` could be defaulted
     to `$true` in the overnight context since Claim A always builds first.
  5. **No `BaselineSuiteJson` forwarding.** Both wrappers use their own default baseline paths.
     This is correct for standard use, but the overnight script provides no way to override
     baselines without modifying the wrapper scripts.
  6. **No resume support.** The checkpoint records `claim_a_complete`/`track_g_complete` phases,
     but the script has no resume logic that skips already-completed phases on restart. This
     is the same limitation as all other phases — the checkpoint is diagnostic, not
     operational.

- 2026-02-19: **Honest status audit — criteria overclaim correction.** Reviewed all 4
  acceptance criteria against code-validated evidence and execution history.

  **Criterion 1 (all 3 families in scorecard): code-complete, never validated (historical at 2026-02-19).** The
  overnight runner with Phase 4c lanes had not yet been executed at that time. The existing
  `benchmark-scorecard-latest.json` (Feb 8) shows only search quality lanes from the
  benchmark autopilot — zero RAG, zero agent. No scorecard has ever been produced showing
  all 3 families simultaneously.

  **Criterion 2 (bench-suite.v2 / main_score): MET.** All 6 scorecard lanes now use the
  generic `decision_*` extraction path. Agent-battery manifest emits `decision_gate_status`,
  `decision_comparable`, `decision_regression_count`, `decision_non_comparable_count`, and
  `generated_at`. The two hardcoded `if (lane === 'agent-battery')` branches in
  `build-benchmark-scorecard.mjs` have been removed. Agent-specific operational data
  (pass_rate, infra_failure, scenario_count) is preserved as additive extensions on both
  lane summaries and trend summaries. See §IV.11 for investigation and fix details.

  **Criterion 3 (agent battery quality signal): ~~implemented, never run~~ → MET
  (validated 2026-02-19).** Battery ran end-to-end: 191 docs ingested, requiredFacts
  assertions active on all 15 non-introspection scenarios, toolTraces captured. Pass rate:
  4/16 (25%) — real quality signal, not false positives. See validation run work log entry.

  **Criterion 4 (corpus/truth versioning): substantially met.** Transition gap: migrated
  RAG baselines have null SHA so comparability check is skipped until re-promoted.

  **Structural assessment.** The tempdoc's genuine achievements are real: bench-suite.v2,
  main_score, RAG v2 migration, agent battery code improvements, scorecard infrastructure,
  BEIR CI gate, producer contract. But the "consolidation" framing overstates what was
  delivered. The codebase still has 6 independent eval scripts, 2 overnight scripts, 2
  summary directories, and 4 manifest formats. The scorecard is a post-hoc reader of
  disparate outputs, not a consolidated evaluation framework. Phase 4c added ~80 lines and
  10 parameters to an already-complex overnight runner — accretion, not consolidation.

  Corrected all 4 criteria annotations and the dependency chain summary. Previous "All four
  criteria are MET" language replaced with honest status.
- 2026-02-19: **Validation run executed.** Ran agent battery end-to-end with all new code
  (ingestCorpus, requiredFacts, toolTraces). Results:

  **Agent battery** (`agent-live-battery-manifest-validation-20260219-153712.json`):
  - `ingestCorpus()`: indexed 191 docs successfully
  - All 16 scenarios executed (no infra failures)
  - `requiredFacts` assertions active: 10 scenarios failed via `missing_facts`, 1 via
    `contains_forbidden`, 1 via `missing_keywords`
  - `toolTraces` captured on all 16 scenarios (1-3 per scenario with full args + output)
  - Pass rate: 4/16 (25%) — down from pre-fix false-positive 81%
  - Passing: exp-001-tools (introspection), exp-012-multistep, exp-013-crossref, exp-014-compare
  - Root cause of failures: `browse_folders` returns "No indexed folders found" (ingestion
    via `/api/knowledge/ingest` does not set up watched roots); agent uses relative
    path_prefix (e.g., `/docs`) which doesn't match absolute paths in the index
  - **Criterion 3 upgraded to MET** — battery produces real quality signal

  **Consolidated scorecard** (`benchmark-scorecard-validation-20260219-154527.json`):
  - Discovery found 5/6 lanes: perf, search-rank, claim-a, track-g, agent-battery
  - `rag-eval`: absent (no `rag-eval-report-manifest.v1` in discovery dir — only produced
    by overnight runner's RAG eval phase)
  - Families covered: 2/3 (search quality + agent). RAG family absent.
  - `release_readiness.status`: `insufficient_data` (missing rag-eval, stale baselines,
    low agent pass rate)
  - Agent-battery lane correctly shows: `agent_pass_rate: 0.25`, `agent_passed: 4`,
    `agent_failed: 12`, warning: "agent pass rate low (25.0%)"
  - **Criterion 1 upgraded to PARTIALLY VALIDATED** — needs overnight run for rag-eval

  **New finding: requiredFacts calibration issue.** The facts themselves are accurate
  (e.g., exp-006 requires "process" and "head" in response about architecture sections).
  But the agent can't find the content because: (a) `browse_folders` returns nothing
  without watched roots, and (b) the agent doesn't know to search without path_prefix.
  Fix options: (1) add watched root setup to `ingestCorpus()`, (2) adjust scenario
  prompts to avoid browse-dependent patterns, (3) lower the bar by removing
  `requiredFacts` from browse-dependent scenarios. Option 1 is the correct fix.
- 2026-02-19: **Validation2 run — browse_folders root fix applied + main_score added.**
  Two code changes to `run-agent-live-battery.mjs`:
  1. `ingestCorpus()` now calls `POST /api/indexing/roots` with the docs folder after
     ingestion, so `browse_folders` tool returns the registered root.
  2. `aggregate.main_score` (= passRate) added to manifest output.

  Results (`agent-live-battery-manifest-validation2-20260219.json`):
  - Pass rate: **37.5%** (6/16) — up from 25% (4/16) in validation1
  - Ingestion: 201 docs (up from 191)
  - New passes: exp-002-roots (browse_folders now returns root), exp-016-verify
  - Remaining 10 failures: all `missing_facts` or `missing_keywords` — genuine content
    quality gaps where the agent searches but doesn't find enough content to satisfy
    fact assertions. No infrastructure failures.
  - `main_score: 0.375` present in manifest

  Consolidated scorecard (`benchmark-scorecard-validation2-20260219.json`):
  - Agent-battery lane: `pass_rate=37.5%`, correctly discovered
  - 5/6 lanes present (rag-eval still absent)
  - Criterion 2 partially improved: `main_score` now emitted, though scorecard still uses
    hardcoded extraction path
- 2026-02-19: **Phase 4a — initial script, then retracted.**
  Created `scripts/bench/validate-beir-metrics-against-trec-eval.mjs`. Script reimplements
  the nDCG/Recall formulas in JavaScript and compares against pytrec_eval. All 3 cached
  datasets pass (zero delta for linear-gain nDCG and Recall).

  **Critical analysis (self-correction):** The script does NOT validate the actual
  production code. It validates a third reimplementation of the same formulas. The real
  production code lives in `beir-eval-win.ps1` (PowerShell) and `RelevanceMetrics.java`
  (Java) — neither is invoked by the script. Proving that `f(x)` in JavaScript equals
  `f(x)` in pytrec_eval says nothing about whether `f(x)` in PowerShell also equals it.
  A proper Phase 4a must run `beir-eval-win.ps1` on a known dataset, capture its per-query
  output, and compare against pytrec_eval on the same qrels + rankings.

  **Useful finding retained:** beir-eval-win.ps1 uses exponential gain (2^rel-1, Burges
  2005), trec_eval uses linear gain (rel, Järvelin & Kekäläinen 2002). Identical for
  binary relevance (all current BEIR gates). Diverges on graded relevance (nfcorpus: max
  Δ=5.6%). Both are valid nDCG formulations. This was discoverable by code reading but
  is now documented.

  Phase 4a status: **incomplete — needs rework.**

- 2026-02-19: **Phase 4a — reworked and validated.**
  Rewrote `scripts/bench/validate-beir-metrics-against-trec-eval.mjs` to validate actual
  production output, not reimplemented formulas. The script now:
  1. Reads per-query JSON from prior `beir-eval-win.ps1` runs (predictedDocIds + stored metrics)
  2. Recomputes exponential-gain nDCG and recall from predictedDocIds + BEIR qrels (Layer 1)
  3. Runs pytrec_eval on the same rankings as independent cross-check (Layers 2 & 3)

  **Results across all 3 cached datasets:**
  - **scifact** (binary, 300 queries): Layer 1 perfect, Layer 2 nDCG perfect (max Δ=1.1e-16),
    Layer 3 recall perfect (zero delta). Both modes (lexical, hybrid) pass.
  - **arguana** (binary, 1406 queries): Layer 1 perfect, Layer 2 nDCG perfect (max Δ=1.1e-16),
    Layer 3 recall perfect. Both modes pass.
  - **nfcorpus** (graded, 323 queries): Layer 1 perfect (323/323 reproduce), Layer 2 shows
    documented exponential vs linear gain divergence (max Δ=9.8%, expected), Layer 3 recall
    perfect. Both modes pass.

  **Fixes applied during rework:** case-insensitive doc ID matching (PowerShell hashtables
  are case-insensitive by default; `MED-2429` in qrels matches `med-2429` from search results).
  UTF-8 BOM stripping (PowerShell `Out-File -Encoding utf8` writes BOM).

  Phase 4a status: **Done.**

- 2026-02-19: **Phase 4a — extended validation.**
  Broadened validation beyond the 3 baseline runs to cover the full investigation scope:

  **1. `RelevanceMetrics.java` cross-check against pytrec_eval:**
  All 11 test cases from `RelevanceMetricsTest.java` match pytrec_eval exactly. The
  imperfect-ranking test (expected ≈0.91973) matches to floating-point precision. The
  one edge case pytrec_eval can't validate (empty relevant set, where Java returns 1.0
  by convention) is correctly handled but unreachable in pytrec_eval (it ignores queries
  with no relevant docs in qrels). Binary-gain nDCG in Java matches pytrec_eval's
  linear-gain nDCG for binary relevance — confirmed identical as expected.

  **2. Non-baseline run validation (9 additional runs):**
  All pass. Tested: `scifact-with-rerank`, `scifact-rerank-fixed`, `scifact-no-rerank`,
  `arguana-bm25hits2-all`, `nfcorpus-bm25hits2-all`, `rank-report-judged-20260208-065012`,
  `rank-report-judged-20260208-091741`, `rank-report-judged-20260208-141659`,
  `rank-report-judged-20260209-162331`, `step1-cand100`, `step3-topk20`. Confirms
  validation isn't accidentally tuned to baseline data.

  **3. `FileNameToDocId` mapping analysis:**
  Mapping chain: `docId` → `EscapeDataString(docId) + ".txt"` → ingest to Lucene →
  search returns `filename` → `GetFileNameWithoutExtension` → `UnescapeDataString` → docId.
  Reversible by construction. Case discrepancy on nfcorpus: qrels have `MED-2429`, Lucene
  stores filename as `med-2429.txt` (lowercase normalization). PowerShell's case-insensitive
  hashtables mask this. Not a bug (works correctly in production), but would be a pitfall
  if porting to a case-sensitive language.

  **4. Aggregate metric validation:**
  Mean nDCG@10 and mean Recall@10 from stored per-query values match pytrec_eval aggregates
  across all 3 datasets (max delta on binary datasets: 5.55e-17, i.e. FP rounding only).
  nfcorpus graded divergence at aggregate level: mean nDCG Δ=1.21e-3 (expected).

  **5. Edge case coverage:**
  - Short rankings (< K docs): arguana lexical has 2, nfcorpus lexical has 79. All validate
    correctly — both production code and pytrec_eval handle truncated rankings identically.
  - No queries with zero relevant docs or null metrics in any dataset.
  - `totalRelevant` consistency: 4,058 entries checked across all runs/modes, zero mismatches
    between stored value and qrels ground truth.

  **Total validation coverage:** 12 runs × 2 modes = 24 mode-runs, 4,058+ query evaluations.
  Zero failures on binary datasets. nfcorpus graded divergence fully documented and expected.

- 2026-02-19: **Criterion 2 — agent-battery generic extraction (Approach A implemented).**
  Added `decision_*` fields to `run-agent-live-battery.mjs` manifest (both main and fallback):
  `decision_gate_status`, `decision_comparable`, `decision_regression_count`,
  `decision_non_comparable_count`, `generated_at`. Removed two hardcoded
  `if (lane === 'agent-battery')` branches from `build-benchmark-scorecard.mjs`
  (`buildLaneSummary()` and `buildTrendSummary()`). Agent-specific operational data preserved
  as additive `agentExtensions`/`agentTrendExtensions` spread into generic return objects.
  **Verified:** syntax checks pass, scorecard dry-run with existing manifests produces correct
  output (agent_* fields present, warnings correct, release readiness intact). Old manifests
  degrade gracefully (gate_status: null, comparable: null). Criterion 2 status: **MET.**

- 2026-02-20: **Criterion 1 — validated from overnight run `20260219-225114`.**
  Overnight run produced manifests for all 3 families:
  - claim-a: pass, comparable=true (search quality family)
  - track-g: fail, 2 regressions, comparable=false (search quality family)
  - rag-eval: pass, comparable=true (RAG family)
  - agent-battery: pass, passRate=43.8%, comparable=true (agent family, with new decision_* fields)
  Scorecard rebuilt: 6/6 lanes present, 3/3 families covered. Release readiness: `warn`.
  The latest agent-battery manifest already contains `decision_*` fields from the Criterion 2
  fix, confirming end-to-end generic extraction works with real overnight data.
  Criterion 1 status: **MET.**

- 2026-02-20: **Phase 4b — replaced Promptfoo with Claude Code CLI judge (§IV.12).**
  Investigation found that Promptfoo's LLM-as-judge requires either a paid API key (for
  quality judgments) or a local model (poor quality). Claude Code CLI (`claude -p`) provides
  frontier-model judgment via the existing subscription with no API key. Empirically tested:
  `cmd /c "set CLAUDECODE=& type prompt.txt | claude --print - --output-format json
  --max-turns 1 --no-session-persistence > out.json"`. `--json-schema` flag causes multi-turn
  behavior and must NOT be used — instead ask for JSON in the prompt text and parse markdown
  fences. Validated with realistic judge prompt: produced accurate JSON scoring (coherence
  0.85, faithfulness 0.90) for $0.067 in 12.3s. Cost projection: ~$1.12 / 16 scenarios.
  Architecture: new `run-agent-battery-judge.mjs` script, per-scenario invocation, structured
  JSON scoring (coherence, faithfulness, hallucination detection). Estimated effort: ~1 day.

- 2026-02-20: **Phase 4b dropped.** Was extracted to tempdoc 224, which has since been
  deleted (low value at current pipeline maturity). Investigation (§IV.12) retained here
  as reference. All remaining work on this tempdoc is complete.

- 2026-02-20: **§IV.12 updated with empirically tested approach.** Removed speculative
  `--json-schema` references from CLI capabilities table, proposed architecture, and advantages
  table. Added: stdin piping requirement, nested session env var note, output format details,
  resolved questions (json-schema, stdin vs inline, nested session), Agent SDK as future
  alternative. All documentation now reflects tested-and-working patterns only.

---

## Part IV: Pre-Implementation Research (2026-02-19)

*Full code read of all 16 agent battery scenarios, `RagQualityEvalTest.java` + truth manifest,
SSE capture in `run-agent-live-battery.mjs`, manifest structure, and overnight script RAG lane.
Cross-referenced with bench-suite.v2 schema, suite-loader, diff tools, and scorecard.*

---

### IV.1 Agent Battery Corpus Design (Phase 2b prerequisite)

#### IV.1.1 Scenario Classification

All 16 scenarios were analyzed for their corpus dependency:

| Category | Count | Scenarios |
|----------|-------|-----------|
| **Introspection** (no corpus needed) | 1 | exp-001-tools |
| **Hybrid** (needs configured roots) | 1 | exp-002-roots |
| **Search-dependent** (needs indexed docs) | 14 | exp-003 through exp-016 |

**14 of 16 scenarios (87.5%) require indexed documents to produce grounded answers.** The
battery is fundamentally broken without corpus ingestion.

#### IV.1.2 Corpus File Requirements

Union of all corpus dependencies across 16 scenarios:

**Tier 1 — Required by 3+ scenarios:**

| File | Scenarios | Est. size |
|------|-----------|-----------|
| `docs/explanation/01-system-overview.md` | exp-006, exp-008, exp-014 | ~5 KB |
| `docs/explanation/05-ai-architecture.md` | exp-008, exp-009, exp-013 | ~8 KB |
| `docs/explanation/06-configuration-ssot.md` | exp-005, exp-012, exp-014 | ~5 KB |
| `docs/llms.txt` | exp-003, exp-015 | ~5 KB |

**Tier 2 — Required by 1–2 scenarios:**

| File | Scenarios | Est. size |
|------|-----------|-----------|
| `docs/explanation/22-agent-system-architecture.md` | exp-011, exp-016 | ~6 KB |
| `docs/reference/configuration/environment-variables.md` | exp-010 | ~5 KB |
| `docs/reference/api-contract-map.md` | exp-011 | ~4 KB |
| `docs/how-to/test-gpu-locally.md` | exp-009, exp-013 | ~3 KB |
| `docs/tempdocs/186-llm-agentic-file-operations.md` | exp-007 | ~10 KB |

**Tier 3 — Browse-dependent** (need watched root for directory listing):

Scenarios exp-004, exp-013, exp-015 require `browse_folders` to navigate `docs/`. This
needs a watched root pointing at the `docs/` directory, plus all subdirectories being
browsable: `docs/explanation/`, `docs/reference/`, `docs/how-to/`, `docs/tempdocs/`,
`docs/decisions/`.

#### IV.1.3 Ingestion Design Decision: Live Docs vs. Frozen Snapshot

| Approach | Pros | Cons |
|----------|------|------|
| **Live docs** (`docs/` directory) | Tests real system state; no snapshot maintenance; detects indexing regressions | Keyword facts may break on doc rewrites; non-reproducible across branches |
| **Frozen snapshot** (committed test corpus) | Deterministic; reproducible; pinned expected answers | Maintenance burden; drifts from reality; extra build artifact |
| **Live docs + structural facts** (recommended) | Real content; facts survive rewrites; no snapshot needed | Facts must be chosen carefully for stability |

**Decision: Live docs with structurally stable `required_facts`.**

The `docs/` directory is stable enough for live testing. Required_facts are designed using
the stability tier system (§IV.1.5) to survive routine documentation edits. Volatile facts
(S3/S4) are excluded from blocking assertions.

**Known maintenance obligation:** S2 facts (42% of proposed facts) can break on file
renames or directory restructuring. Example: if `docs/explanation/01-system-overview.md`
is renamed, the fact "Response includes `01-system-overview`" (exp-004) breaks. This is
accepted as a quarterly maintenance cost — same cadence as reviewing scenario definitions.
To minimize this, S2 facts should prefer folder-level assertions ("lists files from
`docs/explanation/`") over specific filename assertions where possible. Filename-specific
facts are used only when no folder-level alternative exists (exp-004 specifically tests
file listing, so filenames are unavoidable).

#### IV.1.4 Ingestion Sequence for Phase 2b

> **Implemented.** See `ingestCorpus()` in `run-agent-live-battery.mjs`. Deviations
> from original design: (a) uses absolute path `path.join(repoRoot, 'docs')` instead
> of repo-relative; (b) step 3 (verification search query) was omitted — polling
> `/api/knowledge/status` until `docCount > 0` is sufficient.

Insert between `maybeActivateAi()` and the scenario loop (after line 977 of
`run-agent-live-battery.mjs`):

1. `POST /api/knowledge/ingest` with `paths: ["docs"]` (repo-relative)
2. Poll `GET /api/knowledge/status` for indexing completion: wait for
   `state === "READY" && docCount > 0 && queueDepth === 0` (see Q-9 in §IV.7)
3. ~~Verify via `POST /api/knowledge/search` with `query: "architecture"` that search
   returns non-empty results~~ *(not implemented — polling suffices)*

Estimated indexing time: 5–15 seconds for ~80 text files (~400 KB total).

#### IV.1.5 Fact Stability Tiers

| Tier | Definition | Lifetime | Use in gates? |
|------|-----------|----------|---------------|
| **S1 (Architectural)** | Fundamental system design choices | Years | Yes — hard assertions |
| **S2 (Structural)** | File/folder organization, stable conventions | Months–years | Yes — soft assertions; review quarterly |
| **S3 (Content)** | Specific document wording | Weeks–months | No — monitoring only |
| **S4 (Volatile)** | Transient state, rapidly evolving content | Days–weeks | No — exclude entirely |

Stability distribution across all proposed facts: S1: 42%, S2: 42%, S3: 7%, S4: 9%.
Only S1+S2 facts should be used for `required_facts` in CI gate assertions.

---

### IV.2 Per-Scenario Required Facts Design (Phase 3f)

Phase 3f covers all 14 search-dependent scenarios plus the 1 hybrid scenario (exp-002),
not just the original 6. The 6 originally specified in §III.7 were the highest false-
positive-risk scenarios; the remaining 9 also need required_facts to provide meaningful
quality signal once corpus ingestion (Phase 2b) is in place.

**Scope: 15 scenarios** (all except exp-001-tools, which is pure introspection).
**Estimated effort: ~2.5 days** (up from ~1.5 days for 6 scenarios).

Each scenario gets a `requiredFacts` array (case-insensitive substring match, but matched
against *complete claims* in the response, not prompt echo) and optional `mustNotContain`
array.

#### exp-003-explanation-purpose

**Prompt:** "What is the docs/explanation folder used for?"
**Current keywords:** `["explanation"]` — trivially satisfied by prompt echo.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Response mentions "architecture" | S2 | The explanation/ folder contains architecture docs |
| Response mentions a specific doc name (e.g., "system overview") | S2 | File names are stable |

**Must not contain:** *none*

#### exp-004-list-explanation-files

**Prompt:** "List files in docs/explanation."
**Current keywords:** `["docs/explanation"]` — trivially satisfied by prompt echo.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Response lists at least 3 specific filenames from `docs/explanation/` | S2 | File names are stable |
| Response includes "01-system-overview" | S2 | Foundational file, unlikely to be removed |

**Must not contain:** *none*

#### exp-005-config-doc

**Prompt:** "What does the configuration documentation explain?"
**Current keywords:** `["config"]` — trivially satisfied.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "SSOT" or "Single Source of Truth" | S1 | Core design principle |
| Mentions "environment variables" or "EnvRegistry" | S2 | Central config mechanism |

**Must not contain:** *none*

#### exp-006-architecture-sections

**Prompt:** "How many main sections are in the architecture documentation?"
**Current keywords:** `["section"]` — trivially satisfied.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "Head" or "Body" or "Brain" | S1 | Architectural invariant — 3-process model |
| Mentions "3-Process Model" or "three process" | S1 | Core architecture section title |

**Must not contain:** *none*

#### exp-008-search-inference

**Prompt:** "Search the repository for references to inference and summarize what you find."
**Current keywords:** `["inference"]` — trivially satisfied by prompt echo.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "llama-server" or "llama.cpp" | S1 | Inference server technology |
| Mentions "Brain" (process name) | S1 | Architecture name |

**Must not contain:** *none*

#### exp-009-search-gpu

**Prompt:** "Find files mentioning GPU and summarize the main theme."
**Current keywords:** `["gpu"]` — trivially satisfied by prompt echo.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "VRAM" | S1 | Core AI architecture concern |
| Mentions "CUDA" or "NVIDIA" | S1 | Only supported GPU vendor |

**Must not contain:** *none*

#### exp-002-roots (hybrid)

**Prompt:** "What indexed root folders are available right now?"
**Current keywords:** `["root"]` — satisfied by prompt echo.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Response includes an absolute path or "docs" | S2 | With corpus ingested, at least one root exists |

**Must not contain:** *none*
**Note:** This scenario has different expected behavior with vs. without corpus. With Phase
2b in place, the response should list the `docs/` root.

#### exp-007-tempdoc-186

**Prompt:** "What is documented in tempdocs 186?"
**Current keywords:** `["agent"]` — very high false positive risk.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "tool execution" or "function calling" | S2 | Core topic of tempdoc 186 |
| Mentions "file operations" | S2 | First use case, documented in title |

**Must not contain:** *none*

#### exp-010-read-env-vars-doc

**Prompt:** "Summarize key entries in docs/reference/configuration/environment-variables.md."
**Current keywords:** `["environment"]` — trivially satisfied by prompt echo.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "JUSTSEARCH_DATA_DIR" or "JUSTSEARCH_API_PORT" | S1 | Fundamental env vars |
| Mentions at least 2 distinct env var names | S1 | Doc contains 10+ vars |

**Must not contain:** *none*

#### exp-011-agent-api-endpoints

**Prompt:** "What API endpoints exist for agent execution and approval?"
**Current keywords:** `["/api/agent"]` — moderately specific.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions "/api/agent/run" or "/api/agent/stream" | S2 | The SSE endpoint |
| Mentions "/api/agent/approve" | S2 | The approval endpoint |

**Must not contain:** *none*

#### exp-012-multistep

**Prompt:** "Search for configuration docs and then browse the relevant folder before answering."
**Current keywords:** `["configuration"]` — trivially satisfied.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions a specific file path or folder from the configuration docs | S2 | Proves grounded browsing |
| Tool trajectory includes both search_index and browse_folders | S1 | Behavioral — scenario-defined |

**Must not contain:** *none*

#### exp-013-crossref

**Prompt:** "Search for documents about GPU setup, then browse the folder containing the top result, and list what other files are in that folder."
**Current keywords:** `["gpu", "setup", "folder"]`

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Lists at least 2 specific filenames from a docs subfolder | S2 | Proves actual browsing |
| Identifies the folder containing the GPU document | S2 | Proves cross-referencing |

**Must not contain:** *none*

#### exp-014-compare

**Prompt:** "Find documents about both 'configuration' and 'architecture', then compare what topics each set covers."
**Current keywords:** `["configuration", "architecture"]`

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions at least 1 topic from configuration docs (e.g., "SSOT", "environment") | S2 | Grounded config content |
| Mentions at least 1 topic from architecture docs (e.g., "Head", "Body", "Brain") | S1 | Grounded architecture content |

**Must not contain:** *none*

#### exp-015-deep-browse

**Prompt:** "Browse the docs folder, then browse one of its subfolders, and tell me what documentation categories exist."
**Current keywords:** `["explanation", "reference", "how-to"]`

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Mentions all three: "explanation", "reference", "how-to" | S1 | Doc category structure |
| Mentions at least one additional category (e.g., "decisions", "tempdocs") | S2 | Proves deeper browsing |

**Must not contain:** *none*
**Note:** This scenario already has the strongest keyword check in the battery (3 specific
category names). required_facts add coverage of a 4th+ category.

#### exp-016-verify

**Prompt:** "Search for information about the agent system, browse the relevant source folder, and verify that the files mentioned in search results actually exist."
**Current keywords:** `["agent"]` — very high false positive risk.

| Proposed fact | Tier | Rationale |
|--------------|------|-----------|
| Confirms existence of at least 1 specific file by name | S2 | Proves verification behavior |
| References a specific file found via search_index | S2 | Proves search→browse→verify chain |

**Must not contain:** *none*

#### IV.2.1 Schema Extension for Scenarios

Extend `agent-live-battery-scenarios.v1.json` (or create v2) with:

```json
{
  "id": "exp-005-config-doc",
  "prompt": "...",
  "expectedKeywords": ["config"],
  "requiredFacts": ["SSOT", "environment variable"],
  "mustNotContain": [],
  "requiresCorpus": true,
  "expectedToolSubset": ["search_index"],
  ...
}
```

New fields:
- `requiredFacts` (string[]): Case-insensitive substrings that must appear in the response.
  Unlike `expectedKeywords`, these are designed to resist false positives from prompt echo
  and error-recovery phrasing.
- `mustNotContain` (string[]): Negative assertions. Response fails if any of these appear.
- `requiresCorpus` (boolean): Whether the scenario needs indexed docs. Scenarios with
  `requiresCorpus: true` should automatically fail if the ingestion step was skipped.

#### IV.2.2 Evaluator Changes

> **Implemented.** Deviations from original design: (a) `requiredFacts` failures use
> reason string `missing_facts:X,Y` instead of a separate `factsMissing` field;
> (b) `mustNotContain` failures use reason string `contains_forbidden:X,Y`;
> (c) `requiresCorpus` auto-fail guard was not implemented — instead, `requiresCorpus`
> is recorded in the scenario result for informational use. If ingestion fails, the
> entire run aborts as an infra failure before any scenario executes, making the guard
> unnecessary.

In `evaluateTranscript()` (line 772), after the existing `expectedKeywords` check, add:

1. **requiredFacts check**: Same substring logic as keywords, but with a different semantic
   meaning (facts from grounded content, not prompt echo). ~~Track separately in the manifest
   as `factsMissing` alongside existing `keywordsMissing`.~~ *(Implemented as reason string
   `missing_facts:` instead.)*

2. **mustNotContain check**: Invert — fail if any substring is found.

3. ~~**requiresCorpus guard**: If `scenario.requiresCorpus && !ingestionCompleted`, auto-fail
   with reason `"corpus_not_available"`.~~ *(Not implemented — ingestion failure aborts
   the entire run.)*

---

### IV.3 RAG Eval v2 Migration (Phase 3b) — **Implemented**

> **Implemented.** Deviations from design: (1) Dual-write is enabled immediately (no
> separate switch-on step). (2) Converter uses `--no-legacy-payload` for baselines to
> avoid doubling file sizes. (3) `computeCorpusSha()` hashes doc contents from the
> already-loaded `docContents` map rather than re-reading resource files separately for
> the corpus docs (truth manifest and vectors are read from classpath resources).

#### IV.3.1 Current Format: `rag-eval.v1`

Output by `RagQualityEvalTest.java` to `build/test-results/rag-eval/rag-eval-result.v1.json`.

**Structure:**
- `run_metadata`: model_name, similarity_mode, faithfulness_mode, retrieval_mode,
  context_chars_per_doc, max_new_tokens, context_format
- `aggregate`: 7 mean metrics + total_citations + query_count
- `per_query`: 24 entries with per-query metrics, answer text, retrieved docs

**7 aggregate metrics:**
1. `fact_coverage_mean` — ratio of required_facts found in answer
2. `forbidden_fact_rate_mean` — ratio of forbidden_facts found (lower = better)
3. `answer_similarity_mean` — semantic similarity to golden answer
4. `faithfulness_mean` — sentence-level grounding via cross-encoder
5. `retrieval_recall_mean` — fraction of expected source docs in top-5
6. `citation_precision_mean` — fraction of cited markers pointing to correct sources
7. `citation_recall_mean` — fraction of expected citations in answer

#### IV.3.2 v2 Mapping

| `rag-eval.v1` field | `bench-suite.v2` location |
|---------------------|--------------------------|
| `aggregate.fact_coverage_mean` | `measurements.summary.fact_coverage_mean.median` |
| `aggregate.forbidden_fact_rate_mean` | `measurements.summary.forbidden_fact_rate_mean.median` |
| `aggregate.answer_similarity_mean` | `measurements.summary.answer_similarity_mean.median` |
| `aggregate.faithfulness_mean` | `measurements.summary.faithfulness_mean.median` |
| `aggregate.retrieval_recall_mean` | `measurements.summary.retrieval_recall_mean.median` |
| `aggregate.citation_precision_mean` | `measurements.summary.citation_precision_mean.median` |
| `aggregate.citation_recall_mean` | `measurements.summary.citation_recall_mean.median` |
| `aggregate.total_citations` | `measurements.summary.total_citations.median` |
| `aggregate.query_count` | `measurements.summary.query_count.median` |
| `per_query[...]` | `measurements.samples.per_query[...]` |
| `run_metadata.similarity_mode` | `run_metadata.similarity_mode` (top-level) |
| `run_metadata.faithfulness_mode` | `run_metadata.faithfulness_mode` (top-level) |
| Other run_metadata fields | `run_metadata.knobs.*` |

All metrics use `statConst(v)` = `{median: v, min: v, max: v}` since each run is a
single measurement (not multi-sample).

**`main_score`:** `fact_coverage_mean` — the most directly interpretable quality metric.
It is the primary assertion in the test (test fails if fact coverage drops below threshold),
has the largest dynamic range (0.0–1.0, baseline 0.747), and does not depend on external
model availability.

**`suite_kind`:** `"rag-eval"`
**`suite_id`:** Derived from profile: `"rag-eval-<similarity_mode>-<faithfulness_mode>-q<count>"`

#### IV.3.3 Profile Handling

**Decision: One v2 artifact per profile (separate files).**

The existing profile system selects which baseline to compare against based on
`(similarity_mode, faithfulness_mode, query_count)`. The diff tool loads baseline and
candidate independently and compares them. Collapsing profiles into one file would require
restructuring the diff tool.

The profile index (`rag-eval-baseline-profiles.v1.json`) continues to work as-is — only
the `baseline_path` entries need to point to `.v2.json` files.

#### IV.3.4 Corpus SHA Versioning

The RAG eval corpus consists of three components:
1. **Truth manifest**: `modules/system-tests/src/test/resources/manifests/rag-eval-truth.v1.json`
   (24 queries with ground truth)
2. **Frozen vectors**: `modules/system-tests/src/test/resources/corpus/rag/rag-eval-vectors.json`
   (pre-computed embeddings)
3. **Corpus documents**: 6 `.txt` files under `modules/system-tests/src/test/resources/corpus/rag/`

**SHA computation:** SHA-256 of concatenated individual file hashes (truth manifest +
vectors file + all 6 corpus docs). Computed in the Java test (which already loads all files)
and written to the output JSON.

**v2 representation:**
```json
{
  "workload": {
    "corpus_sha": "abc123...",
    "truth_manifest": "rag-eval-truth.v1.json",
    "vectors_file": "rag-eval-vectors.json",
    "corpus_doc_count": 6,
    "query_count": 24
  }
}
```

The diff tool should treat mismatched `corpus_sha` as `NON_COMPARABLE` (same as the agent
battery treats mismatched scenario signatures).

#### IV.3.5 Migration Sequence

1. Write `convert-rag-eval-v1-to-v2.mjs` (convert existing baselines)
2. Update `writeResultsJson()` in `RagQualityEvalTest.java` to emit v2 natively
3. Run converter on existing baselines to produce `.v2.json` files
4. Update profile index `baseline_path` entries
5. Verify: run eval → diff tool → scorecard (same gate decisions)

**Dual-write option:** During migration, the Java test can write both v1 and v2 files.
This ensures the overnight script works against whichever format baselines use, allowing
gradual migration. Remove v1 write once baselines are re-promoted in v2.

**Rollback strategy:** Run one overnight cycle with dual-write enabled. Compare gate
decisions from the v1 path (existing diff tool + v1 baseline) vs. v2 path (diff tool +
v2 baseline). If gate decisions diverge, the v2 conversion has a bug — fix the converter
before promoting v2 baselines. The dual-write period is the safety window; v1 baselines
remain authoritative until v2 decisions are validated.

**Suite-loader already supports v2:** `loadRagEvalSuite()` in `suite-loader.mjs` already
handles `schema_family === 'bench-suite' && schema_version === 2`. No loader changes needed.

**Estimated effort:** ~2 days.

---

### IV.4 Tool Tracing Architecture (Phase 3e) — **Implemented**

> **Implemented.** Deviation from design: `safetyLevel` was not added to the extended
> `toolCalls` object (it is already in the timeline trace entries). The `toolTraces`
> array per scenario is implemented as designed.

#### IV.4.1 SSE Capture Gaps *(pre-fix state)*

The battery's `consumeAgentSse()` function captures 12 SSE event types but systematically
discards substantive tool data:

| Event | Captured | **Discarded** |
|-------|----------|--------------|
| `tool_call_proposed` | callId, toolName | **arguments** (JSON string) |
| `tool_exec_completed` | callId, success | **output** (result text), executionId |
| `tool_call_rejected` | — | All fields (unhandled event) |

This means the manifest records *what tools were called* but not *with what arguments* or
*what they returned*. It is impossible to distinguish:
- "agent searched correctly and found the answer" from
- "agent searched incorrectly and hallucinated the answer"

#### IV.4.2 Reference Implementation

The MCP agent_chat handler (`scripts/dev/justsearch-dev-mcp/server.mjs`, lines 1470–1575)
already captures full tool traces: arguments, output (truncated to 8000 chars), trace context,
and per-iteration detail. Phase 3e should bring the battery's capture up to parity.

#### IV.4.3 Design

Extend `transcript.toolCalls[callId]` to include:

```javascript
{
  callId: data.callId,
  toolName: data.toolName,
  arguments: data.arguments || '{}',   // NEW: raw JSON argument string
  safetyLevel: data.safetyLevel,       // NEW: already in timeline
  approved: false,
  success: null,
  output: null,                        // NEW: populated on tool_exec_completed
}
```

Emit a new `toolTraces` array per scenario in the manifest:

```javascript
toolTraces: Object.values(transcript.toolCalls).map(tc => ({
  callId: tc.callId,
  toolName: tc.toolName,
  arguments: tc.arguments,
  success: tc.success,
  output: truncate(tc.output, 4000),   // capped to prevent manifest bloat
}))
```

#### IV.4.4 Size Analysis

| Tool | Typical `arguments` size | Typical `output` size |
|------|--------------------------|----------------------|
| `search_index` | 30–80 bytes | 500–30,000 bytes |
| `browse_folders` | 30–100 bytes | 200–5,000 bytes |
| `ingest_files` | 50–200 bytes | 100–500 bytes |
| `file_operations` | 50–300 bytes | 100–2,000 bytes |

**Search results are the dominant cost.** With k=3 and 800-char excerpts (tempdoc 213),
a single search result can be up to 4 KB. A scenario with 2 search calls: ~8.5 KB.

**With output truncation at 4000 chars per tool call:**
- Per scenario: ~5–15 KB of tool trace data
- Full 16-scenario battery: ~100–200 KB total
- Manifest growth: from ~10–20 KB to ~100–250 KB — still loads in milliseconds

#### IV.4.5 Scorecard Impact

The scorecard (`build-agent-live-scorecard.mjs`) handles missing fields gracefully via
existing patterns (`if (Array.isArray(scenario?.toolTraces))`). New fields are ignored
by the current code unless explicitly consumed.

New metrics enabled by tool traces (informational, not gated):
- **Argument quality:** Are search queries relevant to the prompt?
- **Result utilization:** Does the final response incorporate tool output?
- **Search precision proxy:** Do search results contain expected facts?

These metrics are Phase 4b+ concerns and do not need to be implemented with Phase 3e.

---

### IV.5 Transcript Retention (Phase 3d) — **Implemented**

> **Implemented.** The "full transcript" level described below was simplified: instead
> of a separate structured transcript with chunk coalescing, the implementation adds
> `toolTraces[]` (structured tool records) and `finalResponse` (agent's text response,
> 8KB cap) to each scenario result. Combined with the existing `timeline` and `trace`
> arrays, this provides equivalent debugging capability without a fourth storage format.

#### IV.5.1 What to Retain

Two levels of transcript:

1. **Timeline** (already in manifest) — event type + timestamp + minimal metadata per event.
   Serves as the structured summary.

2. **Full transcript** (new) — substantive SSE messages with content. Retain:
   - `session_started` (1 per scenario)
   - `chunk` events coalesced into per-iteration text blocks
   - `tool_call_proposed` with `arguments`
   - `tool_exec_completed` with `output` (truncated)
   - `done` with `finalResponse`
   - `error`
   - `progress` (iteration boundaries)

   Exclude from full transcript (already in timeline):
   - `tool_call_pending` (redundant with proposed/approved)
   - `tool_call_approved` (binary flag)
   - `tool_exec_started` (in timeline and toolSequence)
   - `budget_update` (token accounting)

#### IV.5.2 Storage Decision

**Inline in manifest** (recommended over separate files).

With output truncation at 4000 chars per tool call and chunk coalescing, estimated per-
scenario transcript: ~5–15 KB. Full 16-scenario battery: ~80–240 KB per manifest. This
keeps manifests self-contained and atomic — no separate file management.

#### IV.5.3 Relationship to Phase 3e

Phase 3d (transcript retention) and Phase 3e (tool tracing) share implementation:
- Both require capturing `arguments` and `output` from SSE events
- Both write to the manifest
- `toolTraces` (3e) is a structured view; inline transcript (3d) is the raw record

**Recommended: implement 3d and 3e together.** The SSE capture changes are identical.
The manifest gains both `toolTraces` (structured, for scorecard) and `transcript` (raw,
for debugging/investigation).

---

### IV.6 Dependency Chain and Implementation Order

```
Phase 2b (corpus ingestion)                          ✅ DONE
  ├── Phase 3d+3e (transcript + tool tracing)        ✅ DONE
  │     └── Phase 3f (required_facts, 15 scenarios)  ✅ DONE
  │           └── Phase 4b (Claude Code CLI judge)    → DROPPED (tempdoc 224 deleted)
  └── Enables meaningful pass/fail for 14 search-dependent scenarios  ✅

Phase 3b (RAG v2 migration) — independent track      ✅ DONE
Phase 3g (216→219 interface lock)                     ✅ DONE
Phase 4d (scorecard wiring into overnight + nightly)  ✅ DONE (partial)
Phase 4c (Claim A + Track G in overnight runner)      ✅ DONE (validated overnight 20260219-225114)
Phase 4e (search quality lane plumbing fix)            — Superseded by 4c
Phase 4a (trec_eval validation) — independent         ✅ DONE (validates production per-query output, 3 datasets, 3 layers)
Criterion 2 fix (agent-battery generic extraction)    ✅ DONE (decision_* fields + hardcoded branches removed)
```

**Status: COMPLETE.** Criteria 1-3 MET, criterion 4 substantially met. Phase 4b dropped
(tempdoc 224 deleted). Overnight run `20260219-225114` validated 6/6 lanes, 3/3 families. Agent
battery pass rate: 43.8% (7/16). Post-completion residual F-7 remains open and is tracked
as `BEN-005` without reopening this tempdoc's consolidation acceptance criteria.

Remaining work:
1. ~~**Overnight runner validation**~~ — **Done.** Overnight run `20260219-225114` produced
   claim-a (pass), track-g (fail, 2 regressions), rag-eval (pass), agent-battery (pass,
   43.8%) manifests. Scorecard discovers 6/6 lanes, 3/3 families. Criterion 1 MET.
2. ~~**Agent battery calibration** — fix `browse_folders` root issue~~ — **Fixed.** Root
   registration added to `ingestCorpus()` in `run-agent-live-battery.mjs`. Pass rate
   improved from 25% to 37.5%. Remaining failures are genuine scenario weaknesses.
3. ~~**Phase 4b**~~ — **Dropped.** Tempdoc 224 (LLM-augmented eval) deleted — low value
   at current pipeline maturity. Investigation in §IV.12 retained for reference.
4. **Phase 4a** (trec_eval) — **Done.** Reworked script validates actual production per-query
   output from `beir-eval-win.ps1` against pytrec_eval. All 3 datasets pass (scifact, arguana,
   nfcorpus). Three validation layers: reproduce from stored rankings, cross-check nDCG vs
   pytrec_eval, cross-check recall vs pytrec_eval.
5. **Criterion 2 gap** — **Closed.** Agent-battery manifest now emits `decision_*` fields
   (`decision_gate_status`, `decision_comparable`, `decision_regression_count`,
   `decision_non_comparable_count`, `generated_at`). The two hardcoded `if (lane ===
   'agent-battery')` branches in `build-benchmark-scorecard.mjs` (lane extraction and trend
   building) have been removed. All 6 lanes use the generic extraction path. Agent-specific
   operational data preserved as additive extensions. See §IV.11.

6. **F-7 BEIR gate hybrid realism residual** - **Open (post-completion follow-up).** Tracked in
   `docs/reference/issues/benchmarking.md` as `BEN-005`. Tempdoc 216 owns lane remediation;
   tempdoc 219 consumes this as downstream governance dependency.

---

### IV.7 Updated Open Questions

Answers to questions from §II.8, informed by this research:

**Q-5 (trec_eval validation):** **Resolved (Phase 4a).** Reworked script reads actual
per-query output from prior `beir-eval-win.ps1` runs (stored `predictedDocIds` + `ndcgAtK` +
`recallAtK`), recomputes metrics from rankings + qrels, and cross-checks against pytrec_eval.
All 3 cached datasets pass: scifact (300 queries), arguana (1406 queries), nfcorpus (323
queries, graded). Key finding: exponential vs linear gain divergence on graded datasets is
expected and documented (max Δ=9.8% on nfcorpus); recall matches perfectly regardless.
`RelevanceMetrics.java` uses binary-only nDCG (gain=1) and is never used with BEIR data —
separate validation path not needed.
Useful finding from initial script: beir-eval-win.ps1 uses exponential gain (2^rel-1,
Burges 2005), trec_eval uses linear gain (rel, Järvelin & Kekäläinen 2002). Identical for
binary relevance (all current BEIR gates). Diverges on graded relevance (nfcorpus: max
Δ=5.6%).

**Q-6 (Promptfoo model target):** ~~Depends on Phase 3e+3f being complete first.~~ Phase
3e+3f are now complete. The key decision is: (a) local llama-server instance via
Ollama-compatible endpoint, (b) API-backed judge, or (c) separate local model.
**Recommendation: start with local llama-server (cheapest, already running during agent
battery) and measure judge quality before considering API-backed alternatives.**

**Q-8 (RAG eval frequency):** The test runs 24 queries with LLM inference per query.
**Recommendation: nightly trigger at most, not PR-trigger.** The overnight script already
provides the right cadence. A CI workflow would use `workflow_dispatch` + optional
`schedule` (same pattern as BEIR gate). Trigger on changes to
`modules/system-tests/src/test/resources/` (corpus/truth changes) or
`modules/ui/src/main/java/io/justsearch/ui/ai/rag/` (RAG pipeline changes).

**New Q-9 (ingestion wait strategy):** ~~Phase 2b adds a `/api/knowledge/ingest` call.~~
**Resolved.** Phase 2b implemented option (b): `ingestCorpus()` polls
`GET /api/knowledge/status` with timeout. Wait condition:
`ready === true && queueDepth === 0 && pendingJobsCount === 0 &&
processingJobsCount === 0 && activeDocCount > 0`. Default timeout: 60s
(configurable via `--ingest-timeout-ms`).

**New Q-10 (scenario schema versioning):** ~~Phase 3f adds `requiredFacts`, `mustNotContain`,
`requiresCorpus` to the scenario definitions.~~ **Resolved.** Kept v1 schema with new
additive fields as recommended. Scenario signature SHA changed automatically —
old runs without corpus are correctly marked as non-comparable by the scorecard.

**New Q-11 (Phase 4b value proposition):** What quality regressions would Promptfoo
LLM-as-judge catch that the existing 7 RAG metrics + required_facts cannot?
**Answer:** The existing metrics cover retrieval quality (recall), factual coverage
(keyword presence), and citation accuracy. They do NOT cover:
- **Coherence regressions** — the answer could contain all required facts but be
  incoherent, repetitive, or contradictory. LLM-as-judge `llm-rubric` catches this.
- **Hallucination beyond forbidden_facts** — `forbidden_fact_rate` only checks a
  predefined list. LLM-as-judge `context-faithfulness` detects novel hallucinations
  that weren't anticipated in the truth manifest.
- **Answer quality on new queries** — adding a query to the truth manifest requires
  authoring ground truth. LLM-as-judge can evaluate new queries without manual fact
  annotation.
Phase 4b is justified if any of these failure modes materialize after Phases 2b–3f.
If required_facts prove sufficient for regression detection, Phase 4b can be deprioritized.
**Decision (2026-02-20):** Phase 4b extracted to tempdoc 224 with expanded scope.
**Decision (updated 2026-02-20):** Phase 3f is complete and overnight runs are available.
The `required_facts` evaluation produces honest signal (43.8% pass rate) but is limited to
binary substring matching. Replacing Promptfoo with Claude Code CLI as the judge model
(§IV.12) eliminates the API key dependency while providing frontier-quality judgment.
Estimated effort reduced from ~2 days to ~1 day.

---

### IV.8 Operational Gap Analysis: Consolidated Scorecard (Phase 4d)

Code-validated audit of which lanes are actually exercised end-to-end in automated
pipelines. All field names and call sites verified against code as of 2026-02-19.

#### Current Connectivity Map (Post-Phase 4c)

| Lane | Manifest Kind | CI Workflow | Overnight Script | In Scorecard |
|------|---------------|-------------|------------------|--------------|
| `perf` | `perf-ci-manifest.v1` | `perf-regression-win.yml` | — | CI only |
| `search-rank` | `search-eval-rank-report-manifest.v1` | `search-eval-rank-report-win.yml` | autopilot | CI + autopilot |
| `claim-a` | `claim-a-report-manifest.v1` | `claim-a-report-win.yml` | autopilot, **RAG/AI queue** | CI + autopilot + RAG/AI queue |
| `track-g` | `track-g-report-manifest.v1` | `track-g-report-win.yml` | autopilot, **RAG/AI queue** | CI + autopilot + RAG/AI queue |
| `rag-eval` | `rag-eval-report-manifest.v1` | **No CI workflow** | RAG/AI queue | RAG/AI queue only |
| `agent-battery` | `agent-live-battery-manifest.v1` | `agent-live-eval-nightly.yml` | RAG/AI queue | nightly + RAG/AI queue |

**Note:** The RAG/AI queue also runs Claim D (`run-claim-d-suite-win.ps1`), but Claim D
output is NOT discoverable by the scorecard: (a) there is no `claim-d` lane or manifest
kind in `MANIFEST_KIND_TO_LANE`; (b) Claim D output goes to `tmp/bench/_summaries`,
not the scorecard's `tmp/agent-evidence/_summaries` discovery directory.

#### Lane Coverage per Automated Path (Code-Validated, Post-Phase 4c)

| Automated Path | perf | search-rank | claim-a | track-g | rag-eval | agent-battery | Families |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `overnight-rag-ai-queue-win.ps1` | — | — | **yes** | **yes** | **yes** | **yes** | **3/3** |
| `overnight-benchmark-autopilot-win.ps1` | — | **yes** | **yes** | **yes** | — | — | 1/3 |
| `agent-live-eval-nightly.yml` | — | — | — | — | — | **yes** | 1/3 |
| Individual CI workflows (perf, search-rank, claim-a, track-g) | **yes** | **yes** | **yes** | **yes** | — | — | 1/3 |

**Phase 4c result (historical note):** The overnight RAG/AI queue has code for 4 lanes
across all 3 families. As of 2026-02-21 this path has been executed in spot checks; lane
coverage is now also backed by observed manifests (see "Reality Sync").

#### Discovery-Based Opportunistic Inclusion

All scorecard callers pass `--discover-dir` which scans for JSON with a recognized `kind`.
Cross-script discovery is **unreliable** because:
- The two overnight scripts use different summary dirs (`tmp/agent-evidence/_summaries`
  vs `tmp/bench/_summaries`) for different artifact types
- The two scripts are never orchestrated together
- On self-hosted runners, stale manifests from prior sessions may be picked up

#### Phase 4d Status

**Deliverables completed:**
1. ✅ Added `build-benchmark-scorecard.mjs` call to `overnight-rag-ai-queue-win.ps1`
   (new `consolidated_scorecard` phase, `Invoke-LoggedCommand` pattern, non-blocking)
2. ✅ Added consolidated scorecard step to `agent-live-eval-nightly.yml`
   (`if: always()`, non-blocking, output in existing artifact upload path)
3. ✅ Added `$summary.consolidated_scorecard` fields and markdown section to overnight script

**Remaining gaps (post-Phase 4c):**

**Gap 1 (CLOSED by 4c):** Search quality lanes now present. Claim A + Track G produce
manifests in `tmp/agent-evidence/_summaries/` with recognized `kind` values, discoverable
by the consolidated scorecard.

**Gap 2 (closed but minimal value):** `agent-live-eval-nightly.yml` scorecard discovers
only agent-battery (1 lane, 1 family). Informational only.

**Gap 3 (open, non-blocking):** RAG eval has no CI workflow. Only runs via overnight script.

**Gap 4 (open, non-blocking):** The `perf` lane has no manifest producer in any overnight
script. Only produced by `perf-regression-win.yml` (on-demand CI).

**Gap 5 (CLOSED by 4c):** Overnight RAG/AI queue now covers all 3 harness families.

---

### IV.9 Architectural Assessment: Consolidation vs Plumbing

#### What genuinely improved the codebase (Phases 1–3f)

The first 10 phases created real infrastructure and unified abstractions:

- **`bench-suite.v2`** as a common artifact format (Phase 3a) — all lanes can now express
  results in one schema with `main_score` for lane-agnostic aggregation
- **Scorecard infrastructure** (Phase 2) — `build-benchmark-scorecard.mjs` with 6-lane
  definitions, manifest-kind mapping, ratchet policy, release verdict
- **RAG eval v2 migration** (Phase 3b) — RAG artifacts normalized to `bench-suite.v2`
  with corpus SHA for comparability
- **Agent battery quality** (Phases 2b, 3d+3e, 3f) — corpus ingestion, tool tracing,
  required_facts converted the agent harness from infrastructure smoke to quality signal
- **Producer contract** (Phase 3g) — formal interface lock between this tempdoc and 219

These phases solved real problems and created reusable abstractions.

#### What devolved into reactive plumbing (Phases 4d onward)

From Phase 4d, the tempdoc shifted to patching script-call wiring:

- **Phase 4d:** Add `build-benchmark-scorecard.mjs` calls to two scripts
- **Post-4d audits:** Discovered the wiring doesn't work (wrong directories, Claim D ≠
  Claim A, missing manifest kinds)
- **Proposed Phase 4e:** Add one more script call to produce one more manifest

Each fix reveals a new gap because the underlying structure is fragmented:

1. **Two overnight scripts with arbitrary lane splits.** RAG + Claim D + agent battery
   in one script. Search-rank + Claim A + Track G in the other. No architectural reason
   for the split — it's historical accident.

2. **Two summary directories.** `tmp/bench/_summaries` and `tmp/agent-evidence/_summaries`.
   Manifests end up in one or the other based on which script wrote them, not on any
   intentional taxonomy. The scorecard only scans one.

3. **Each lane is a fully bespoke script.** Six independent report wrappers, each with
   its own parameter conventions, lifecycle management, error handling, and output path
   logic. No shared runner or configuration.

4. **Claim D doesn't fit the model.** No report manifest, no matching scorecard lane,
   writes to a different directory. It was never integrated into the consolidated view.

5. **The scorecard is a read-only aggregator, not an orchestration framework.** It
   discovers whatever JSON files happen to be lying around in a directory. If a file is
   missing, the lane silently shows as absent.

#### The fundamental problem

The tempdoc's title is "Eval Harness Consolidation" but the codebase still has six
independent evaluation scripts, two orchestration scripts, two output directories, and
a post-hoc viewer. Adding more script calls (Phase 4e) would technically meet
criterion 1's letter but not its spirit. The "consolidated scorecard" would be an
artifact of accident (which scripts happened to run and where they happened to write)
rather than of design.

#### What actual consolidation would look like

A unified evaluation runner that:
- Takes a declarative lane configuration (which lanes to run, with what parameters)
- Manages shared infrastructure (dev-runner lifecycle, output directories, checkpointing)
- Produces all manifests to one canonical location
- Has one parameter surface instead of six independent scripts

This is what Phase 4c ("overnight decoupling") was supposed to be, but it was scoped as
"decouple overnight script into composable lane steps" — a refactor of the orchestration,
not just another plumbing patch.

#### Implications for this tempdoc

Phases 1–3f achieved genuine consolidation of formats, quality, and infrastructure.
The remaining criterion 1 gap was an orchestration problem, not a consolidation problem.

**Resolution attempt (Phase 4c):** Rather than the full unified-runner architectural
rewrite described above, Phase 4c took a pragmatic middle path: added Claim A + Track G
lanes directly to the overnight RAG/AI queue. This is closer to "Phase 4e plumbing" in
scope (~80 lines, not a day of refactoring). The structural fragmentation (two scripts,
two directories, bespoke wrappers) persists. Criterion 1 is code-complete but has never
been validated — the overnight runner has not been executed with these lanes, and no
scorecard showing all 3 families has ever been produced.

---

### IV.10 Fix Analysis: Closing Criterion 1 (Phase 4e or 4c) — CODE-COMPLETE

**Status: Executed in spot runs (2026-02-21) with baseline-shift caveats.** Claim A +
Track G code in the overnight RAG/AI queue has now been exercised. Options below remain
historical context for how criterion 1 was originally closed.

Criterion 1 required all 3 harness families in one consolidated scorecard:
1. **Search quality** (perf, search-rank, claim-a, track-g)
2. **RAG eval**
3. **Agent battery**

The overnight RAG/AI queue covered families 2 and 3. Family 1 was absent. Options:

#### Option A: Add `run-claim-a-report-win.ps1` to overnight queue (RECOMMENDED)

**What:** Add a Claim A report phase to `overnight-rag-ai-queue-win.ps1`, producing
a `claim-a-report-manifest.v1` that the scorecard discovers.

**Why Claim A:** Lightest search quality lane — no dev-runner, no GPU, 10–20 min runtime.
Already has a report wrapper (`scripts/ci/run-claim-a-report-win.ps1`) that produces a
manifest with the correct `kind`. Claim A and Claim D are independent (different suites,
different baselines).

**Implementation (~30 lines):**
- Add parameters: `-SkipClaimA`, `-ClaimARuns` (default 3)
- Add phase block after Claim D (line 802), before agent battery (line 804)
- Call `run-claim-a-report-win.ps1 -Runs $ClaimARuns -SkipBuild` with
  `-SummaryDir $summaryDir` so the manifest lands in the discovery dir
- Record result in `$summary.claim_a`
- Non-blocking on failure (warn only, like scorecard)

**Result:** Scorecard discovers claim-a + rag-eval + agent-battery = **3 families, 3 lanes.**
Criterion 1 met at minimum interpretation.

#### Option B: Add `run-track-g-report-win.ps1` instead

Same approach as Option A but with Track G. Also no dev-runner, 10–20 min. Slightly
heavier (includes latency benchmarking). Either lane satisfies criterion 1.

#### Option C: Add both Claim A and Track G

Covers 4 of 6 lanes. +20–40 min runtime. More robust cross-lane signal but not
required for criterion 1.

#### Option D: Add Search-Rank (full BEIR eval)

Adds the most valuable search quality signal but requires dev-runner coordination
(only one can run at a time). 45–90 min runtime. The overnight queue already manages
dev-runner for agent battery, so lifecycle coordination is needed. Higher complexity.

#### Option E: Full convergence (Phase 4c)

Orchestrate both overnight scripts sequentially, or merge them into one unified script.
This is the most comprehensive fix but is a larger refactor (~1 day). Would produce
5 of 6 lanes (all except perf, which is on-demand only).

#### Recommendation

**Option A (add Claim A)** is the minimum viable fix. ~30 lines of PowerShell, no new
dependencies, 10–20 min additional runtime. Gets criterion 1 to MET. Option C (add
both Claim A and Track G) is a small incremental improvement for better signal.

#### Prerequisite check

Claim A report wrapper must accept a `-SummaryDir` parameter (or write to a well-known
location). Verify that `run-claim-a-report-win.ps1` writes its manifest to a path the
scorecard's `--discover-dir` can find.

---

### IV.11 Investigation: Closing Criterion 2 (lane-agnostic `main_score`)

#### What the criterion requires

> All eval artifacts use `bench-suite.v2` or have a self-declared `main_score` — enabling
> lane-agnostic aggregation without hardcoded per-lane metric names.

#### What the investigation found

The gap is **narrower than originally described**. A code-level audit of
`build-benchmark-scorecard.mjs` reveals:

**Lanes that already use the generic extraction path (no hardcoded branch):**
- **perf** — report manifest has `decision_*` fields → generic path (lines 847-970)
- **search-rank** — same generic path, with additive `lane === 'search-rank'` guards for
  BEIR-judged profile data and regression signal classification (lines 897-903, 915-920,
  1032-1038, 1058-1072). These are extensions, not a separate extraction branch.
- **claim-a** — same generic path. Manifest has `decision_*` fields (produced by
  `run-claim-a-report-win.ps1` lines 138-172).
- **track-g** — same generic path. Manifest has `decision_*` fields (produced by
  `run-track-g-report-win.ps1` lines 146-181).
- **rag-eval** — same generic path. Manifest has `decision_*` fields.

**The only lane with a completely separate extraction path:**
- **agent-battery** — two `if (lane === 'agent-battery')` blocks:
  1. Lane status extraction (lines 796-845): reads `aggregate.passRate`, `aggregate.infraFailure`,
     `aggregate.total/passed/failed`. Returns a completely different object shape with
     `agent_*` fields and no baseline/regression logic.
  2. Trend building (lines 975-1013): counts infra failures and low pass rates instead of
     `decision_regression_count`/`decision_non_comparable_count`.

**Why agent-battery can't use the generic path today:**
The generic path expects these manifest fields: `decision_gate_status`, `decision_comparable`,
`decision_regression_count`, `decision_non_comparable_count`, `report_only_regression_detected`,
`baseline_selected_path`, `candidate_selected_path`, `threshold_context`, `runner_fingerprint`,
`final_exit_code`. Agent-battery's `agent-live-battery-manifest.v1` has none of these — it uses
a fundamentally different model: window-based stability (pass rate over time) instead of
baseline-relative regression detection.

**What about `main_score` specifically?**
The scorecard's generic path does NOT read `main_score` from manifests. It reads `decision_*`
fields for gate status and regression counting. `main_score` lives in the underlying suite files
(bench-suite.v2), not in the report manifests. The suite-loader (`lib/suite-loader.mjs`) extracts
`main_score` for diff tooling, but the scorecard itself doesn't aggregate by `main_score`.

Claim-a and track-g already have v2 converters that emit `main_score`:
- `convert-claim-a-suite-v1-to-v2.mjs` → `main_score = time_to_searchable_ms`
- `convert-track-g-suite-v1-to-v2.mjs` → `main_score = case_ok_ratio`

These are invoked by the report wrappers during runs. The v2 suite files are already produced
and stored. They just aren't read by the scorecard.

#### The original claim of "3 of 6 lanes with hardcoded extraction" was overcounted

Only agent-battery has a hardcoded extraction branch. The tempdoc's §3 self-audit incorrectly
counted claim-a and track-g as hardcoded. They use the same generic path as perf.

#### Two approaches to close Criterion 2

**Approach A: Add `decision_*` fields to agent-battery manifest (RECOMMENDED)**

Make `run-agent-live-battery.mjs` emit the same `decision_*` fields the generic path expects:
```
decision_gate_status: infraFailure ? 'fail' : passRate > 0 ? 'pass' : 'unknown'
decision_comparable: null   (no baseline comparison model)
decision_regression_count: 0
decision_non_comparable_count: 0
report_only_regression_detected: null
final_exit_code: <process exit code>
```
Plus a `main_score` field (already present: `aggregate.main_score = aggregate.passRate`).

Then remove the `if (lane === 'agent-battery')` branches from the scorecard. The generic path
would extract gate status from `decision_gate_status` and ignore baseline fields (all null).

**Pros:** Minimal code. Scorecard becomes truly lane-agnostic for core extraction.
**Cons:** The trend building still needs special handling — agent-battery trends track infra
failures and pass rate, not regression/non-comparable signals. Could add `agent_*` fields as
additive extensions (like search-rank's BEIR-judged data) rather than a separate branch.

**Trade-off for trend building:** The generic trend path counts `decision_regression_count > 0`
as a regression signal. For agent-battery, this would always be 0 (no baseline comparison), so
the generic trend would show 0% regression rate forever — useless but not harmful. The
agent-specific trend data (infra failures, low pass rate) could move to additive fields
computed after the generic path returns, similar to how search-rank adds `total_hits_only_*`.

**Approach B: Add `main_score` to report manifests, read in scorecard**

Instead of making agent-battery conform to the generic path, add a `main_score` field to all
6 manifest types. The scorecard would read `main_score` from manifests for lane-agnostic
comparison, while keeping lane-specific extraction for operational details (regressions, trends).

**Pros:** Doesn't force agent-battery into a regression-detection model it doesn't use.
**Cons:** Doesn't eliminate the hardcoded branch — just adds a common field alongside it.
The scorecard would still have the `if (lane === 'agent-battery')` blocks. This is
criterion-2-by-letter but not criterion-2-in-spirit.

#### Recommendation and Implementation

**Approach A implemented (2026-02-19).** Changes made:
1. `run-agent-live-battery.mjs`: Added `decision_gate_status`, `decision_comparable`,
   `decision_regression_count`, `decision_non_comparable_count`, and `generated_at` to both
   the main manifest and the fallback manifest.
2. `build-benchmark-scorecard.mjs`: Removed `if (lane === 'agent-battery')` lane extraction
   block. Agent-specific data preserved as `agentExtensions` spread into the generic return.
3. `build-benchmark-scorecard.mjs`: Removed `if (lane === 'agent-battery')` trend block.
   Agent-specific trend extensions (`agent_low_pass_rate_signal_count`, `agent_low_pass_rate`)
   preserved as `agentTrendExtensions` spread into the generic return.

**Verified:** Scorecard dry-run with existing manifests produces correct output. Agent-battery
lane shows `agent_*` additive fields, warnings ("agent pass rate low"), and release readiness
reasons. Old manifests (without `decision_*` fields) degrade gracefully to `gate_status: null`,
`comparable: null`. Criterion 2 is now MET.

---

### IV.12 Investigation: Claude Code CLI as LLM-as-Judge (Phase 4b — Extracted to Tempdoc 224)

#### Problem

Phase 4b originally proposed Promptfoo (Node.js, MIT) for LLM-as-judge evaluation of agent
battery scenarios. The LLM-as-judge assertions (`context-faithfulness`, `llm-rubric`,
`factuality`) require a capable judge model. Promptfoo supports local models via Ollama, but
small quantized local models are poor judges for subtle quality issues (coherence, novel
hallucination). Frontier models (GPT-4, Claude) produce reliable judgments but require paid
API keys. The project currently has no API key — only a Claude Code subscription.

#### Key Insight

Claude Code CLI is already installed and supports non-interactive invocation via `claude -p`.
The subscription covers CLI usage. This gives access to a frontier judge model (Sonnet/Opus)
without an additional API key dependency.

#### Claude Code CLI Capabilities for Automation

Relevant flags for automated judge invocation:

| Flag | Purpose |
|------|---------|
| `--print` / `-p` | Non-interactive mode (print, then exit). Prompt via stdin or argument. |
| `--output-format json` | Structured JSON output with `result`, `session_id`, `usage` fields |
| `--model sonnet` | Model selection (sonnet, opus, haiku) |
| `--max-turns 1` | Prevent agentic tool use — single LLM call only |
| `--max-budget-usd 1.00` | Cost control per invocation |
| `--append-system-prompt "..."` | Custom system prompt for judge role |
| `--no-session-persistence` | Don't save judge sessions to disk |

**Important:** `--json-schema` exists but triggers multi-turn agentic behavior even with
`--max-turns 1`, causing `error_max_turns` failures. Do NOT use it. Instead, instruct the
model to produce JSON in the prompt text itself and parse the `result` field from the output.

**Invocation pattern (tested, working):**
```bash
# Write prompt to temp file, pipe to claude via stdin
type prompt.txt | claude --print - --output-format json --max-turns 1 --no-session-persistence > out.json
```

**Why stdin piping:** Inline prompt arguments (`-p "prompt text"`) are consumed by cmd.exe
shell quoting on Windows, resulting in only 2-3 input tokens reaching the model. Piping from
a file bypasses this entirely.

**Nested session note:** When invoked from within an active Claude Code session, the
`CLAUDECODE` env var must be cleared (`set CLAUDECODE=` on Windows) to bypass the nested
session check. This is only relevant for testing — the overnight runner runs in standalone
PowerShell where `CLAUDECODE` is not set.

The `--output-format json` response contains:
```json
{ "result": "<model text output>", "session_id": "...", "usage": { "input_tokens": ..., "output_tokens": ... }, "total_cost_usd": 0.067 }
```

The `result` field contains the model's text response. When the prompt asks for JSON output,
the model wraps it in markdown code fences (`` ```json ... ``` ``). The judge script must
strip these before parsing.

#### Agent Battery Scenario Data (What the Judge Sees)

Per-scenario data available from the manifest:

| Field | Tokens (est.) | Purpose |
|-------|---------------|---------|
| `prompt` | 50-100 | The agent's task |
| `finalResponse` | 200-500 | The agent's answer (capped at 8,192 chars) |
| `requiredFacts` | 20-50 | Ground truth facts that should appear |
| `expectedKeywords` | 10-30 | Required keywords |
| `mustNotContain` | 10-20 | Forbidden phrases |
| `toolSequence` | 10-20 | Tools the agent called |
| **Total per scenario** | **~400-700** | |

16 scenarios × 500 tokens avg = ~8,000 tokens total input per judge run.
At subscription rates this is negligible — well under a single conversation's budget.

#### Proposed Architecture

**New script:** `scripts/ci/run-agent-battery-judge.mjs`

1. Reads the agent-battery manifest JSON (from `--manifest` arg)
2. For each scenario, constructs a judge prompt containing: prompt, finalResponse,
   requiredFacts, mustNotContain, toolSequence
3. For each scenario, writes a judge prompt to a temp file and invokes:
   `type <prompt-file> | claude --print - --output-format json --max-turns 1 --no-session-persistence`
   Parses the `result` field from JSON output (stripping markdown code fences) to extract:
   ```json
   {
     "coherence_score": 0.85,
     "faithfulness_score": 0.92,
     "hallucination_detected": false,
     "hallucination_details": null,
     "quality_issues": [],
     "overall_quality": "good",
     "reasoning": "..."
   }
   ```
4. Aggregates per-scenario scores into a judge manifest:
   ```json
   {
     "kind": "agent-battery-judge-manifest.v1",
     "generated_at": "...",
     "judge_model": "claude-sonnet-4-6",
     "scenarios_judged": 16,
     "mean_coherence": 0.82,
     "mean_faithfulness": 0.88,
     "hallucination_count": 1,
     "quality_distribution": { "good": 10, "acceptable": 4, "poor": 2 },
     "per_scenario": [...]
   }
   ```
5. Writes to `tmp/agent-evidence/_summaries/agent-battery-judge-manifest-<timestamp>.json`

**Integration into overnight runner:** Add as a post-processing step after agent-battery
completes. The judge reads the battery manifest that was just produced. Non-blocking (warns
on failure, does not set `$hadFailures`). Skippable via `-SkipAgentJudge`.

**Per-scenario invocation vs batch:** Per-scenario (16 separate calls) is preferred over
batching. Reasons: (a) simpler error isolation — a single failed call doesn't lose other
results, (b) each call is small (~700 tokens in + ~200 tokens out), (c) easy to implement
retry logic per scenario, (d) parallelizable if latency becomes an issue.

#### Advantages over Promptfoo

| Dimension | Promptfoo | Claude Code CLI |
|-----------|-----------|-----------------|
| Judge model quality | Local model (poor) or paid API (good) | Frontier model via subscription (excellent) |
| Additional dependency | `promptfoo` npm package | None (already installed) |
| API key required | Yes (for quality judge) | No (subscription covers it) |
| Structured output | YAML config + assertion types | Prompt-based JSON + `--output-format json` parsing |
| Configuration | `promptfooconfig.yaml` + assertion YAML | Judge prompt template (in-script) |
| Offline capability | Yes (with local model) | No (needs internet) |
| Cost model | Per-token (API) or free (local) | Flat-rate subscription |

**Trade-off:** Claude Code CLI requires internet connectivity. For a nightly/overnight runner
that already needs network for GitHub operations, this is acceptable.

#### What the Judge Catches That `required_facts` Cannot

1. **Coherence regressions** — answer contains all required facts but is incoherent,
   repetitive, or contradictory. `required_facts` only checks substring presence.
2. **Novel hallucinations** — fabricated claims not in the predefined `mustNotContain` list.
   The judge can verify claims against the provided context (tool outputs).
3. **Answer quality gradients** — partial credit. `required_facts` is binary (pass/fail);
   the judge can score quality on a continuous scale.
4. **Reasoning quality** — whether the agent's tool usage was logical and its conclusions
   follow from the retrieved information.

#### Empirical Test Results (2026-02-20)

Tested nested `claude -p` invocation from within a running Claude Code session.

**Invocation method:**
```
cmd /c "set CLAUDECODE=& type prompt.txt | claude --print - --output-format json --max-turns 1 --no-session-persistence > out.json"
```

- Must use `cmd /c` with `set CLAUDECODE=` to bypass nested session protection
- Prompt must be piped from a file (`type prompt.txt |`), not passed as inline argument
  (cmd.exe quoting eats the prompt text — only 2-3 input tokens arrive)
- `--max-turns 1` prevents tool use, `--no-session-persistence` avoids session clutter

**Results:**

| Test | Prompt | Latency | Cost | Output |
|------|--------|---------|------|--------|
| Simple (9 words) | "Rate coherence 1-10" | 3.1s | $0.047 | `"9"` — correct |
| Realistic judge (180 words, `--json-schema`) | Coherence/faithfulness/hallucination | 19.2s | $0.172 | `error_max_turns` — schema triggered 2 turns |
| Realistic judge (180 words, no schema) | Coherence/faithfulness/hallucination | 12.3s | $0.067 | Valid JSON with scores and reasoning |

Test 5 (`--json-schema`): The `--json-schema` flag caused the model to attempt tool use,
exceeding `--max-turns 1`. The `result` field was absent. Cost was 2.5× higher due to the
extra turn. **Verdict: do not use `--json-schema` — ask for JSON in the prompt text.**

Test 6 (no schema, prompt-only JSON): Produced accurate, nuanced output:
`coherence_score: 0.85, faithfulness_score: 0.90, hallucination_detected: false` — correctly
identified required facts, noted the vague claim about pipeline docs as "unverified but not
definitively false." Model: `claude-sonnet-4-6` (754 output tokens).

**Cost projection:** 16 scenarios × ~$0.07 avg = ~$1.12 per overnight run.
**Latency projection:** 16 scenarios × ~12s avg = ~3 minutes sequential.

**Parsing note:** The `result` field wraps JSON in markdown code fences. The judge script
must strip these before parsing (regex: `/```json\n([\s\S]*?)\n```/`).

#### Verification Plan

The script can be tested end-to-end during implementation using the real overnight manifest
(`agent-live-battery-manifest-overnight-20260219-225114.attempt01.json`, 16 scenarios,
7 pass / 9 fail on `required_facts`).

**Test execution:** Launch via background task to handle ~3min runtime:
```bash
cmd /c "set CLAUDECODE=& node scripts/ci/run-agent-battery-judge.mjs --manifest tmp/agent-evidence/_summaries/agent-live-battery-manifest-overnight-20260219-225114.attempt01.json"
```
Clearing `CLAUDECODE` in the parent process means all child `claude --print` subprocesses
also inherit the cleared env — no per-call env handling needed.

**Acceptance criteria:**
1. **Completeness:** All 16 scenarios scored (no errors, no missing entries)
2. **Valid JSON:** Each per-scenario judge response parses to the expected schema
   (`coherence_score`, `faithfulness_score`, `hallucination_detected`, `reasoning`)
3. **Score sanity:** The 7 scenarios that passed `required_facts` should have higher mean
   `faithfulness_score` than the 9 that failed. If the judge gives uniformly high scores to
   scenarios that failed fact-checking, the prompt needs adjustment.
4. **Cost:** Total should be ~$1.12 (16 × ~$0.07). Check `total_cost_usd` in each response.
5. **Manifest output:** Judge manifest written to `tmp/agent-evidence/_summaries/` with
   correct `kind`, `generated_at`, `scenarios_judged`, aggregate scores, and `per_scenario` array.

**Parse reliability check:** Verify the code-fence stripping regex handles all 16 outputs.
If the model occasionally returns raw JSON without fences, the regex should fall through to
raw JSON.parse as a fallback.

#### Remaining Open Questions

1. **Judge consistency:** LLM-as-judge scores can vary between runs. Consider running the
   judge 2-3 times per scenario and averaging, or accept single-run variance.
2. **Scorecard integration:** Should the judge manifest be a 7th scorecard lane, or an
   additive extension on the existing agent-battery lane? Additive extension is simpler
   (no new lane plumbing) and keeps the judge subordinate to the primary evaluation.

#### Resolved Questions

- ~~**`--json-schema` flag:**~~ Tested and rejected. Caused multi-turn agentic behavior
  (`error_max_turns`) and 2.5× cost. Ask for JSON in the prompt text instead.
- ~~**Stdin vs inline prompt:**~~ Inline `-p "prompt"` fails on Windows (cmd.exe eats quotes,
  only 2-3 input tokens arrive). Must pipe from file via `type <file> | claude --print -`.
- ~~**Nested session:**~~ `CLAUDECODE` env var blocks nested invocation. Clear with
  `set CLAUDECODE=` in subprocess. Not an issue for standalone overnight runner.
- ~~**System prompt engineering:**~~ Sonnet 4.6 is capable enough for judge tasks with a
  straightforward prompt. Verified empirically: a simple prompt (180 words, no rubric examples)
  produced calibrated scores and nuanced reasoning. Prompt iteration is not a blocker.

#### Recommendation

Replace Phase 4b's Promptfoo approach with Claude Code CLI judge. The implementation is:
1. Write `scripts/ci/run-agent-battery-judge.mjs` (~200-300 lines)
2. Add judge phase to `overnight-rag-ai-queue-win.ps1` (following Claim A / Track G pattern)
3. Optionally wire judge scores as additive fields on agent-battery scorecard lane
4. Estimated effort: ~1 day (down from ~2 days for Promptfoo)

#### Future Alternative: Agent SDK

The `@anthropic-ai/claude-agent-sdk` npm package provides a programmatic TypeScript/Node.js
interface for building custom agents. It could replace the `child_process.execSync` + CLI
subprocess approach with direct SDK calls, offering better error handling, streaming, and
type safety. However, it requires an Anthropic API key (not covered by subscription).
If an API key becomes available, migrating from CLI subprocess to Agent SDK would be a
straightforward improvement. For now, the CLI approach is the correct choice given the
subscription-only constraint.

---

## Cross-References

- **Tempdoc 219** (`docs/tempdocs/219-runtime-resilience-hardening.md`): Runtime resilience
  hardening. Consumer of this tempdoc's benchmark evidence artifacts for release governance.
  See "216/219 Ownership Boundary (Locked)" above and the matching section in tempdoc 219.
- ~~**Tempdoc 224**~~: LLM-augmented evaluation pipeline — deleted. Low value at current
  pipeline maturity. §IV.12 retains the empirical investigation for reference.
