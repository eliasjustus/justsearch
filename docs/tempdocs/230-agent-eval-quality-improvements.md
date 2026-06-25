---
title: "230: Eval Suite Decision Closure"
type: tempdoc
status: done
created: 2026-02-21
updated: 2026-02-25
---

> NOTE: Noncanonical decision-closure doc. Validate against live code before acting.

# 230: Eval Suite Decision Closure

## Purpose

Close open product decisions from tempdocs 227 and 222 using decision-grade evidence,
without rewriting the harness.

This doc is now the execution contract for the **agent-first, report-only, additive-v1**
program.

## Locked Decisions

1. Scope order: Agent lane first, then search lane.
2. Gate style: report-only first, enforce only after stability windows.
3. Schema policy: additive v1 only (no breaking removals/renames).

## Reality Sync (as of 2026-02-21)

### Already implemented before this closure wave

| Area | Existing in live battery |
|---|---|
| Scenario breadth | 18 scenarios (`exp-001` to `exp-016`, `handoff-001`, `handoff-002`) |
| pass^k core | Modeled `p^k` already computed in scorecard |
| Trajectory conformance | `expectedToolSubset`, `expectedMinToolCalls`, `toolCallsExecuted` |
| Content truth checks | `requiredFacts`, `mustNotContain` |
| Trace capture | `toolTraces` persisted in transcript artifacts |

### Core gap that remained

The missing layer was **argument correctness at tool-call argument level**, not trajectory
measurement in general:
- Did the first `ingest_files` call target the correct path?
- Did required tool executions succeed?
- Are retries hiding an incorrect first argument?

This is now the center of C3.

## High-Level Issue

The original issue was not "insufficient telemetry." The issue was **decision mismatch**:
existing telemetry could detect completion and broad trajectory shape, but could not answer
the high-risk product questions (argument correctness, handoff robustness, PRIMARY quality,
and chunk-path evidence fidelity) with enforceable, comparable signals.

## Telemetry Limits (Explicit)

| Limit | Impact | Current handling |
|---|---|---|
| Trace argument/output truncation | Large payloads can hide details | Keep truncation, add parsed path candidates + hashes for deterministic checks |
| Historical runs without deterministic fields | Partial comparability with new runs | Treat deterministic metrics as N/A for old manifests |
| Judge outputs are model-dependent | Rubric drift across judge model versions | Store model + prompt identity in judge artifacts |
| Search runs without debug evidence | Hybrid/vector contribution can be ambiguous | Report-only warning when vector debug evidence is missing |
| ANN internals not fully exposed | Cannot prove ANN path internals directly | Use explicit contribution evidence + comparability warnings |

## Comparability Caveats

| Caveat | Rule |
|---|---|
| Scenario truth drift | Compare only runs with matching scenario profile signature including truth-bearing fields |
| Report-only deterministic gate | `status` can remain pass while `wouldFailStrictDeterministicGate=true` |
| Judge replay comparability | Compare only same rubric version and judge model family where possible |
| Search evidence fidelity | Embedding-profile comparisons are report-only uncertain if vector evidence is absent |
| Query-set mismatch | Do not compare retrieval metrics across different query subsets as equivalent deltas |

## Wave Status

### Wave A0: Reality-sync docs
- [x] Marked C1/C2 as already implemented and narrowed C3 to argument correctness.
- [x] Reframed E3 as experiment wiring/measurement (not config implementation).
- [x] Added telemetry limits + comparability caveats.
- [x] Updated tempdoc 227 references to live battery artifacts.

### Wave A1: Deterministic checks (report-only)
- [x] Added `requiredToolSuccess` and `firstToolCallOracle` evaluation in runner.
- [x] Added report/enforce deterministic gate switch (default report).
- [x] Added `deterministicChecks` and `wouldFailStrictDeterministicGate`.
- [x] Enriched `toolTraces` attribution (`agentId`, hashes, parsed path evidence).
- [x] Hardened scenario profile signature with truth-bearing fields.

### Wave A2: Handoff suite expansion
- [x] Added `handoff-003` to `handoff-006`.
- [x] Backfilled `handoff-001` and `handoff-002` with explicit truth/oracle fields.
- [x] Produced baseline N=3 artifacts for six handoff scenarios.

### Wave A3: Scorecard decision slicing
- [x] Added stratified reliability (`exp-*` vs `handoff-*`).
- [x] Added empirical grouped pass^k alongside modeled `p^k`.
- [x] Added deterministic-check aggregate summaries/worst scenarios.

### Wave A4: LLM judge integration
- [x] Added `scripts/bench/judge-agent-run.mjs` and rubric templates.
- [x] Added deterministic precheck linkage/skip behavior.
- [x] Ran current baseline rubric pass (`primary` and `organizer`) for `handoff-001..006` across N=3 runs.
- [x] Historical replay explicitly marked unavailable when comparable manifests were not found.

### Wave A5: E3 measurement protocol
- [x] Framed as experiment matrix only (no new reasoning-budget config implementation item).
- [x] Ran budget matrix harness attempts and produced budgeted manifests/scorecards and decision artifacts.
- [x] Emitted report-only `wouldFailE3Gate` and decision table artifacts.
- [x] Captured runtime caveat: current llama runtime accepts reasoning budget values `0` or `-1`; `512` and `2048` runs are marked as non-comparable infra-failure evidence.

### Wave B1: Search evidence-fidelity hardening
- [x] Added additive proto/API response fields for chunk-merge activation + reasons.
- [x] Added optional `debug` request path and hit-level `debugScores`.
- [x] Extended BEIR per-query artifacts with effective mode/fallback/merge/vector evidence.
- [x] Extended diff suite with report-only evidence checks + uncertainty outputs.
- [x] Fixed embedding fallback all-query warning detection when `hybridFallbackReasonCounts` is emitted as a hashtable/object.
- [x] Fixed report-only per-query evidence presence checks to read BOM-encoded JSON artifacts reliably.
- [x] Mitigated Node `DEP0190` warning path in `scripts/dev/dev-runner.cjs` by avoiding `spawn(..., args, { shell: true })` argument mode.

### Wave B2: Search corpus coverage
- [x] Added resilient large-corpus indexing path in `beir-eval-win.ps1`:
  `-AddRootTimeoutSec`, `-IndexMode ingest_batches`, queue-backpressure controls, and resume support.
- [x] Finalized `webis-touche2020` promoted baseline metrics bundle (OFF and ON, required profiles).
- [x] Added MLDR-English converter script and test harness.
- [x] Ran MLDR-English baseline eval lane and promoted artifacts.
- [x] Kept DAPR deferred until post-B2 stability validation.

## Public Interface Changes (Additive v1)

Implemented additive fields in this wave:
- `scripts/ci/agent-live-battery-scenarios.v1.json`
  - `requiredToolSuccess`, `firstToolCallOracle`
- `scripts/ci/run-agent-live-battery.mjs`
  - `deterministicChecks`, `wouldFailStrictDeterministicGate`, richer `toolTraces`
- `scripts/ci/build-agent-live-scorecard.mjs`
  - `passKReliabilityEmpirical`, `stratifiedReliability`, `deterministicCheckSummary`
- `modules/ipc-common/src/main/proto/indexing.proto` `SearchResponse`
  - `chunk_merge_applied`, `chunk_merge_reason`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchRequest.java`
  - optional `debug`
- `modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeSearchResponse.java`
  - `effectiveMode`, `vectorBlocked`, `vectorBlockedReason`, `hybridFallback`,
    `hybridFallbackReason`, `chunkMergeApplied`, `chunkMergeReason`, hit `debugScores`
- Adapter/controller pass-through in `app-services` and `ui`
- `scripts/search/beir-eval-win.ps1`
  - per-query evidence fields for mode/fallback/merge/vector contribution
  - additive execution options for large-corpus reliability:
    `AddRootTimeoutSec`, `IndexMode`, `IngestBatchSize`, `IngestRequestTimeoutSec`,
    `IngestSkipFiles`, `IngestQueueHighWatermark`, `IngestQueueLowWatermark`,
    `IngestBackpressurePollSec`
- `scripts/bench/diff-search-eval-suite.mjs`
  - report-only evidence checks and uncertainty outputs
- `scripts/bench/run-eval-autonomous-until.ps1`
  - functional autonomous foreground lane runner (deadline-based), replacing tempdoc-named wrapper script.
  - naming policy correction: script names are function-scoped, not tempdoc-scoped.

## Rollout Policy

1. Stage 1: report-only deterministic/judge/search-evidence checks.
2. Stage 2: candidate enforce for deterministic gate after two stable windows and no unresolved false positives.
3. Stage 3: candidate enforce for search evidence checks after long-doc baselines are promoted.

## Execution Evidence (Current)

1. A2 handoff baseline:
   `tmp/agent-evidence/_summaries/handoff-baseline/manifests/run-01.json`,
   `tmp/agent-evidence/_summaries/handoff-baseline/manifests/run-02.json`,
   `tmp/agent-evidence/_summaries/handoff-baseline/manifests/run-03.json`,
   `tmp/agent-evidence/_summaries/handoff-baseline/scorecard.pass3.json`.
2. A4 judge artifacts:
   `tmp/agent-evidence/_summaries/judge/current/...`,
   `tmp/agent-evidence/_summaries/judge/historical/unavailable.json`.
3. A5 E3 artifacts:
   `tmp/agent-evidence/_summaries/e3/budget-0/scorecard.pass5.json`,
   `tmp/agent-evidence/_summaries/e3/budget-512/scorecard.pass5.json`,
   `tmp/agent-evidence/_summaries/e3/budget-2048/scorecard.pass5.json`,
   `tmp/agent-evidence/_summaries/e3/e3-decision-table.json`.
4. B2a `webis-touche2020` ON/OFF run artifacts:
   `tmp/beir-eval/webis-touche2020-chunk-on-stub-final/`,
   `tmp/beir-eval/webis-touche2020-chunk-on-embedding-final/`,
   `tmp/beir-eval/webis-touche2020-chunk-off-stub-final/`,
   `tmp/beir-eval/webis-touche2020-chunk-off-embedding-final/`.
5. B2a comparability decisions:
   `tmp/agent-evidence/_summaries/search/webis-touche2020-stub-on-vs-off.decision.json`,
   `tmp/agent-evidence/_summaries/search/webis-touche2020-embedding-on-vs-off.decision.json`.
6. B2a promoted baselines:
   `scripts/bench/baselines/search-eval-beir-webis-touche2020-baseline.metrics.json`,
   `scripts/bench/baselines/search-eval-beir-webis-touche2020-baseline.metrics.v2.json`,
   `scripts/bench/baselines/search-eval-beir-webis-touche2020-embedding-baseline.metrics.json`,
   `scripts/bench/baselines/search-eval-beir-webis-touche2020-embedding-baseline.metrics.v2.json`,
   `scripts/bench/baselines/search-eval-beir-webis-touche2020-baseline.evidence/`.
7. B2b converter + validation:
   `scripts/search/convert-mldr-en-to-beir.mjs`,
   `scripts/search/test-convert-mldr-en-to-beir.mjs`,
   `tmp/beir-cache/mldr-en/raw/mldr-en/conversion-metadata.json`.
8. B2b `mldr-en` ON/OFF run artifacts:
   `tmp/beir-eval/mldr-en-chunk-on-stub-final/`,
   `tmp/beir-eval/mldr-en-chunk-on-embedding-final/`,
   `tmp/beir-eval/mldr-en-chunk-off-stub-final/`,
   `tmp/beir-eval/mldr-en-chunk-off-embedding-final/`.
9. B2b comparability decisions:
   `tmp/agent-evidence/_summaries/search/mldr-en-stub-on-vs-off.decision.json`,
   `tmp/agent-evidence/_summaries/search/mldr-en-embedding-on-vs-off.decision.json`.
10. B2b promoted baselines:
    `scripts/bench/baselines/search-eval-beir-mldr-en-baseline.metrics.json`,
    `scripts/bench/baselines/search-eval-beir-mldr-en-baseline.metrics.v2.json`,
    `scripts/bench/baselines/search-eval-beir-mldr-en-embedding-baseline.metrics.json`,
    `scripts/bench/baselines/search-eval-beir-mldr-en-embedding-baseline.metrics.v2.json`,
    `scripts/bench/baselines/search-eval-beir-mldr-en-baseline.evidence/`.
11. MLDR query-failure closure evidence:
    `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/TextQueryOps.java`
    switched prefix rewrite to `MultiTermQuery.CONSTANT_SCORE_REWRITE` and
    `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/LuceneIndexRuntimeTest.java`
    added high-fanout regression coverage.
12. Post-fix MLDR ON/OFF rechecks (`MaxQueries=50`, report-only):
    `tmp/beir-eval/mldr-en-chunk-on-stub-recheck2/`,
    `tmp/beir-eval/mldr-en-chunk-on-embedding-recheck2/`,
    `tmp/beir-eval/mldr-en-chunk-off-stub-recheck2/`,
    `tmp/beir-eval/mldr-en-chunk-off-embedding-recheck2/`,
    `tmp/agent-evidence/_summaries/search/mldr-en-stub-on-vs-off.recheck2.decision.json`,
    `tmp/agent-evidence/_summaries/search/mldr-en-embedding-on-vs-off.recheck2.decision.json`.
    Outcome: `queryErrorCount==0` for lexical+hybrid in both modes/profiles.
13. Evidence-fidelity instrumentation rechecks:
    `tmp/beir-eval/webis-touche2020-chunk-on-embedding-smoke-warn-v2/metrics.json`
    confirms explicit all-query fallback warning emission; and
    `tmp/agent-evidence/_summaries/search/webis-touche2020-embedding-on-vs-off.recheck.decision.json`
    confirms per-query evidence checks now detect artifacts (no false "missing artifact" due to BOM).
14. Direction 1 stability window run artifacts (`MaxQueries=50`, query-only):
    `tmp/beir-eval/stability-w01/webis-chunk-on-stub/`,
    `tmp/beir-eval/stability-w01/webis-chunk-on-embedding/`,
    `tmp/beir-eval/stability-w01/webis-chunk-off-stub/`,
    `tmp/beir-eval/stability-w01/webis-chunk-off-embedding/`,
    `tmp/beir-eval/stability-w01/mldr-chunk-on-stub/`,
    `tmp/beir-eval/stability-w01/mldr-chunk-on-embedding/`,
    `tmp/beir-eval/stability-w01/mldr-chunk-off-stub/`,
    `tmp/beir-eval/stability-w01/mldr-chunk-off-embedding/`,
    `tmp/beir-eval/stability-w02/webis-chunk-on-stub/`,
    `tmp/beir-eval/stability-w02/webis-chunk-on-embedding/`,
    `tmp/beir-eval/stability-w02/webis-chunk-off-stub/`,
    `tmp/beir-eval/stability-w02/webis-chunk-off-embedding/`,
    `tmp/beir-eval/stability-w02/mldr-chunk-on-stub/`,
    `tmp/beir-eval/stability-w02/mldr-chunk-on-embedding/`,
    `tmp/beir-eval/stability-w02/mldr-chunk-off-stub/`,
    `tmp/beir-eval/stability-w02/mldr-chunk-off-embedding/`.
15. Direction 1 stability decision packet:
    `tmp/agent-evidence/_summaries/search/stability-window-01/`,
    `tmp/agent-evidence/_summaries/search/stability-window-02/`,
    `tmp/agent-evidence/_summaries/search/stability-two-window-summary.json`,
    `tmp/agent-evidence/_summaries/search/stability-two-window-summary.md`.
    Summary values from packet:
    `totalCases=8`, `totalDecisionCases=8`, `allQueryErrorsZero=true`,
    `allDecisionsComparable=true`, `allDecisionGatesPass=true`,
    `reportOnlyWarningsStable=true`,
    `vectorEvidenceExercisedAnyOnEmbedding=false`,
    `allOnEmbeddingNoEmbeddingServiceAllQueries=true`.
16. Autonomous timed run (until 11:00 local) artifacts:
    `tmp/agent-evidence/_summaries/autonomous-until-11/autonomous-final.json`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/autonomous-ledger.json`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/autonomous-execution-summary.json`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/autonomous-execution-summary.md`.
    Summary values:
    `cyclesCompleted=27` (ledger entries `26`),
    agent manifests `27`, scorecards `26`,
    agent scenario outcomes `passed=100 failed=62 total=162`,
    `passRate=0.6172839506172839`,
    `deterministicWouldFailCount=157`.
17. Autonomous search-lane recovery packet (post-loop bug fix execution):
    `tmp/agent-evidence/_summaries/autonomous-until-11/autonomous-recovery-summary.json`,
    `tmp/beir-eval/autonomous-until-11/recovery/webis-chunk-on-stub/`,
    `tmp/beir-eval/autonomous-until-11/recovery/webis-chunk-on-embedding/`,
    `tmp/beir-eval/autonomous-until-11/recovery/webis-chunk-off-stub/`,
    `tmp/beir-eval/autonomous-until-11/recovery/webis-chunk-off-embedding/`,
    `tmp/beir-eval/autonomous-until-11/recovery/mldr-chunk-on-stub/`,
    `tmp/beir-eval/autonomous-until-11/recovery/mldr-chunk-on-embedding/`,
    `tmp/beir-eval/autonomous-until-11/recovery/mldr-chunk-off-stub/`,
    `tmp/beir-eval/autonomous-until-11/recovery/mldr-chunk-off-embedding/`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/search/recovery/webis-stub-on-vs-off.decision.json`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/search/recovery/webis-embedding-on-vs-off.decision.json`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/search/recovery/mldr-stub-on-vs-off.decision.json`,
    `tmp/agent-evidence/_summaries/autonomous-until-11/search/recovery/mldr-embedding-on-vs-off.decision.json`.
    Recovery outcome: decisions present `4/4`, all `comparable=true`, `gate=pass`, `regressions=0`, `nonComparable=0`, `reportWarnings=1`.
18. Operational caveat captured:
    the initial autonomous wrapper loop had a PowerShell scope bug yielding empty `BaseUrl` for search calls.
    Functional replacement script:
    `scripts/bench/run-eval-autonomous-until.ps1` (tempdoc-named script removed to restore functionality-first naming).
    Also captured: Node `DEP0190` warnings were mitigated in runner spawn handling to avoid shell+args invocation mode.

## Direction Outcomes (2026-02-22)

### Direction 1: Stability windows (report-only)
Status:
- **Completed**

Evidence:
- Window 1 decision set:
  `tmp/agent-evidence/_summaries/search/stability-window-01/`
- Window 2 decision set:
  `tmp/agent-evidence/_summaries/search/stability-window-02/`
- Cross-window summary:
  `tmp/agent-evidence/_summaries/search/stability-two-window-summary.json`,
  `tmp/agent-evidence/_summaries/search/stability-two-window-summary.md`

Observed outcome:
- No query-error regressions across windows (`queryErrorCount==0` in all cases).
- All ON/OFF decisions remained comparable and gate-pass.
- Report-only evidence warning pattern was stable.
- Cross-window packet values: `totalCases=8`, `allDecisionsComparable=true`,
  `allDecisionGatesPass=true`, `reportOnlyWarningsStable=true`.

### Direction 2: Vector-path exercise closure
Status:
- **Resolved as explicit defer (report-only)**

Evidence:
- `tmp/agent-evidence/_summaries/search/stability-two-window-summary.json`
  shows:
  - `vectorEvidenceExercisedAnyOnEmbedding=false`
  - `allOnEmbeddingNoEmbeddingServiceAllQueries=true`
  - `direction2.status=defer-recommended`

Decision:
- Keep search evidence lane report-only until an embedding-capable path is exercised in-window
  (`vector_debug_evidence_rate > 0`), or policy explicitly accepts non-exercised vector evidence.

### Direction 3: Search gate promotion decision
Status:
- **Deferred**

Evidence:
- `tmp/agent-evidence/_summaries/search/stability-two-window-summary.json`
  -> `direction3.status=defer-enforce-candidate`

Decision:
- Do not promote Stage 3 search evidence checks to enforce-candidate yet.
- Preserve report-only policy with explicit vector-path caveat.

## Uncertainty Resolution Matrix

| Uncertainty | Current evidence | Resolution action | Evidence to collect | Decision trigger |
|---|---|---|---|---|
| MLDR `TooManyClauses` failures (`q-en-31`, `q-en-37`) | **Resolved (2026-02-22):** post-fix rechecks show `queryErrorCount=0` across ON/OFF + stub/embedding | Keep regression test and monitor in stability windows | `mldr-en-*-recheck2` metrics + decision artifacts | Closed unless failures reappear |
| Vector path not exercised in embedding profile | **Resolved as defer (2026-02-22):** two stable windows show `NO_EMBEDDING_SERVICE` fallback on all embedding-profile hybrid queries | Keep explicit report-only warning and reopen when vector path can be exercised | `stability-two-window-summary.*` + embedding run warnings | Closed as defer until ANN/vector exercise becomes available |
| Promotion readiness vs. report-only | **Resolved as defer (2026-02-22):** stability windows passed but vector path remained unexercised | Hold enforce-candidate promotion; retain report-only | `stability-window-01/`, `stability-window-02/`, `stability-two-window-summary.*` | Reopen when vector evidence is exercised or policy changes |

## Remaining Closure Work

All actionable waves (A0–B2) complete. All three directions resolved or explicitly deferred.
Tempdoc closed 2026-02-25.

Deferred items (policy decisions, not open implementation work):

1. **Search-lane ANN exercise (future):** Enable embedding-capable ANN path for eval windows
   to exercise vector evidence before reconsidering D2/D3 deferral.
2. **Stage 3 enforce-candidate promotion:** Do not promote until vector-path evidence is
   materially exercised. Gated on ANN service availability in eval context.
3. **DAPR integration:** Deferred until post-B2 stability validation.

**Key constraint on Stage 2 progression:** Autonomous run shows `deterministicWouldFailCount=157/162`
(97%) — `firstToolCallOracle` fails on nearly every run at current model quality. Stage 2
enforce-candidate is effectively gated on model quality improvements (tempdoc 227), not on
infrastructure. The report-only gate decision is correct given this evidence.

---

## Post-Closure Analysis (2026-02-25)

### Oracle coverage gap

Of 22 scenarios in `agent-live-battery-scenarios.v1.json`, only **6 have `firstToolCallOracle`
or `requiredToolSuccess` fields**. The remaining 16 (73%) have no deterministic measurement
at all. This means the enforce gate (`requiredToolSuccess`, which is always-on) currently
applies to less than a third of the battery.

The highest-value follow-on work is not running more stability windows — it is **expanding
`requiredToolSuccess` to the 16 uncovered scenarios.** Unlike `firstToolCallOracle`, this
check is binary and path-agnostic: "did the tool calls that must succeed actually succeed?"
It does not require oracle definition and can be enforced immediately.

### `firstToolCallOracle` design ceiling

`firstToolCallOracle` measures whether the agent's *first* tool call matches an expected
pattern. This has a structural problem: LLM agents are non-deterministic and self-healing.
A model that searches, corrects course, and arrives at the correct answer still fails the
oracle. The false-positive rate on capable multi-step reasoning is inherent to path-matching,
not a calibration problem. This means:

1. The metric cannot be safely enforced without causing valid-trajectory failures.
2. Even dramatic model quality improvements (tempdoc 227) will not close the oracle gap —
   the agent may improve in ways that change *which* tool it calls first.
3. The 97% failure rate is a symptom of the design, not only of the model.

The correct long-term path (per tempdoc 232 §3) is **state-space attainment**: evaluate
whether the agent reached the required knowledge state, not whether it followed the expected
path. This would require redesigning the oracle concept from path-matching to terminal-state
verification (e.g., did the agent's final context contain the required facts, regardless of
how it got there?). Until that redesign happens, `firstToolCallOracle` should be treated as
a diagnostic diagnostic only, not a gate candidate.

### LLM judge longevity

`judge-agent-run.mjs` (A4) requires frontier model API calls and is subject to rubric drift
across judge model versions. It is viable for deep periodic analysis but not for routine CI.
Model identity is stored in artifacts — this is the right safeguard — but replay comparability
degrades as judge models evolve. Treat as an analytical instrument, not a gate.

### What has durable long-term value

| Component | Durability | Reason |
|---|---|---|
| `requiredToolSuccess` (enforced) | High | Binary, path-agnostic, immediately actionable |
| B1 search evidence fidelity fields | High | Permanent additive artifact additions, debug-useful |
| Stability window protocol | Medium | Good decision framework; reusable for future gate promotion |
| `firstToolCallOracle` (report-only) | Low | Design ceiling; cannot be safely enforced as path-matching |
| LLM judge | Medium | Analytical instrument only; cost and drift limit routine CI use |

### Recommended next action

Before opening new oracle-related work: **add `requiredToolSuccess` to the 16 uncovered
scenarios.** This delivers immediate enforce-mode coverage across the full battery with no
design risk.
