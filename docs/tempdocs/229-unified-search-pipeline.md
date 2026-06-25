---
title: "229: Default Search Relevance Strategy (Adaptive, Corpus-Aware)"
type: tempdoc
status: done
created: 2026-02-21
updated: 2026-02-22 (cycles 10-14 executed with latest-local evidence; static-path handoff ready, adaptive path evidence-blocked)
---

> NOTE: Noncanonical doc (investigation + strategy). May drift. Verify against code before acting.

# 229: Default Search Relevance Strategy (Adaptive, Corpus-Aware)

## Purpose

Define a strategy to make JustSearch's default search as relevant as possible by combining existing retrieval features under one simple user experience.

This tempdoc is intentionally scoped to relevance only.

## Scope and Assumptions

### In scope

- Relevance quality of default search behavior
- Feature-combination quality (which retrieval features help or hurt when combined)
- Corpus-adaptive policy selection (default search may adapt to user corpus characteristics)

### Out of scope (for this tempdoc)

- Throughput/cost optimization as primary objective
- Multi-tier hardware strategy
- Literal competitive claim validation ("best on market")

### Clarifications

1. "Best on market" is a directional product goal, not a literal benchmark claim.
2. Hardware scope for this phase is users with minimum 8GB VRAM.
3. No hardware tiers are introduced in this phase.
4. This is not about reviving the deleted pipeline execution framework.

## Problem Statement (Current Understanding)

JustSearch already contains strong retrieval features (BM25, vector, hybrid fusion, chunk-aware merge, corrections, expansion, reranking, facets, excerpts), but we still do not know:

1. Which combinations are beneficial on average
2. Which combinations are harmful due to interaction effects
3. Whether a single global default policy is enough, or corpus-adaptive behavior is required

The core problem is uncertainty in feature interactions under real query and corpus diversity.

## Goal Definition (Relevance Only)

For this tempdoc, success means:

- One simple default search experience for users
- Higher relevance than current default according to agreed relevance metrics
- Evidence-backed policy selection (not intuition-driven)

No other optimization axis is considered primary in this document.

## Strategy Direction

## 1) Policy-first default search

Treat default search as an explicit retrieval policy that chooses how existing features are combined.

The policy should decide:

- Which retrieval legs run
- In what order they run
- Which post-processing steps run (corrections, merge, rerank)
- Which conditions skip or enable each step

## 2) Corpus-adaptive policy selection

A single static policy may not be optimal across all user corpora.

Therefore, evaluate corpus-adaptive behavior where default policy is selected from a small policy family based on corpus characteristics such as:

- Corpus size and document-length distribution
- Content-type distribution (code/docs/notes/media text)
- Language distribution
- Chunk/vector coverage state
- Query-style distribution (if available from local usage signals)

This is adaptation by corpus profile, not per-user manual tuning.

## 3) Relevance-focused evaluation as the decision engine

Use existing benchmark/eval lanes and expand them to evaluate feature combinations directly.

No policy should be promoted without relevance evidence.

## Existing Capability Surface (Relevant to this strategy)

| Capability | Path | Notes |
|------------|------|-------|
| BM25 text retrieval | Worker (`SearchOrchestrator`) | Includes fuzzy correction chain |
| Vector retrieval | Worker (`SearchOrchestrator`) | Gated by embedding compatibility |
| Hybrid RRF retrieval | Worker (`SearchOrchestrator`) | Has degradation signaling |
| Chunk-aware merge for interactive search | Worker (`SearchOrchestrator`) | From tempdoc 222 |
| Query expansion | Head (`KnowledgeHttpApiAdapter`) | Implemented from tempdoc 223 |
| Interactive reranking | Head (`KnowledgeHttpApiAdapter`) | Text-focused path |
| RAG chunk reranking | Worker (`RagContextOps`) | Separate from interactive path |
| Agent search formatting | Head (`SearchTool`) | Approach A done, Approach B pending (tempdoc 213) |

## Evaluation Model (Relevance Primary)

### Required relevance metrics

- nDCG@k
- Precision@k
- MRR@k
- Query-class segmented relevance (not just pooled means)

### Evaluation structure

1. Baseline policy
2. Single-feature additions (ablation)
3. Pairwise and higher-order combinations of winning features
4. Corpus-profile-specific policy candidates
5. Final selected default/adaptive policy set

### Query classes to preserve in analysis

- Typo/noisy queries
- Morphological variation queries
- Semantic paraphrase queries
- Long-document passage-focused queries
- Technical/code-token queries

Aggregated means are insufficient; class-level behavior must be retained.

## Proposed Tempdoc Phases (Strategy-Level)

### Phase A: Relevance objective lock

- [ ] Freeze relevance metric package and query-class taxonomy
- [ ] Define acceptance criteria for "relevance improvement"

### Phase B: Interaction mapping

- [ ] Run feature-combination ablations using existing eval lanes
- [ ] Build a ranked map of beneficial vs harmful combinations

### Phase C: Adaptive policy hypothesis

- [ ] Define corpus profiles and candidate policies per profile
- [ ] Evaluate whether profile-based policy selection beats single global policy

### Phase D: Default policy decision

- [ ] Select one global policy or a corpus-adaptive policy family based on relevance evidence
- [ ] Document rationale and non-selected alternatives

### Phase E: Stabilization criteria

- [ ] Verify chosen policy is robust across repeated runs and corpus/query slices
- [ ] Record known failure modes and fallback policy behavior

## Fundamental Issues (Still Remaining)

Even with narrowed scope and fixed hardware floor, these are the core theoretical blockers:

1. **Objective-function ambiguity**
   - "Most relevant on average" is underdefined until metric weighting and class weighting are fixed.

2. **Unknown query/corpus distribution**
   - "Average" performance is distribution-dependent; the true production distribution is not yet formalized.

3. **Ground-truth fragility**
   - Personal/local corpora are hard to label consistently; relevance judgments are sparse and subjective.

4. **Non-additive feature interactions**
   - Feature gains are not compositional; order and gating can invert expected improvements.

5. **Adaptive-policy taxonomy gap**
   - Corpus-adaptive direction is clear, but profile schema and policy-mapping function are not yet fixed.

6. **Adaptive cold start**
   - New corpora have weak signals, making early policy selection unstable.

7. **Drift and revalidation uncertainty**
   - Corpora evolve; no formal trigger yet for when selected policy is no longer valid.

8. **Mean-vs-tail relevance conflict**
   - Aggregate metric gains can hide regressions in critical query classes.

9. **Causal attribution uncertainty**
   - Without controlled design, observed gains may be due to variance, not policy quality.

10. **Statistical decision-threshold gap**
    - No formal confidence/decision protocol yet for promoting one policy over another.

11. **Runtime readiness variability**
    - Even at >=8GB VRAM, embedding/reranker readiness can vary at query time.

12. **Offline-to-live transfer gap**
    - Offline benchmark improvements may fail to translate to live user-perceived relevance.

These are not implementation details; they are design-level blockers to policy confidence.

## Research Plan (Blocker-Oriented)

To evaluate whether known methods can resolve the blockers above, the research pass will cover:

1. IR significance and decision protocols for ranking experiments
2. Query-distribution and test-collection robustness guidance
3. Methods for noisy/weak relevance labels
4. Feature-interaction learning approaches (LTR, ensembles, contextual policies)
5. Adaptive policy selection and cold-start handling (bandits/contextual adaptation)
6. Drift detection and policy revalidation methods
7. Mean-vs-tail robustness approaches
8. Offline-to-online evaluation bridges for ranking

Outputs required from the research section:

- Method summary
- Which blockers it addresses
- What remains unsolved
- Suitability for JustSearch constraints

## Internet Research Plan and Findings (2026-02-21)

### Executed research plan

1. Map each blocker to one or more established method families.
2. Prefer primary references (arXiv, Microsoft Research publication pages, DOI-backed papers).
3. Classify each blocker as:
   - solved for practical decision-making,
   - partially solved (mitigated, still structurally open), or
   - unsolved in literature for our context.

### Blocker verdict matrix (critical analysis)

| Blocker | Evidence-backed method families | Verdict | Why blocker remains (if not solved) |
|---------|--------------------------------|---------|-------------------------------------|
| 1. Objective-function ambiguity | Statistical testing and effect-size methods | **Unsolved** | These methods compare systems after an objective is chosen; they do not define product relevance utility. |
| 2. Unknown query/corpus distribution | Heterogeneous benchmarks (BEIR, MAIR), QPP analysis | **Partially solved** | Diversity helps stress-test generalization, but user-local corpus/query distribution remains unknown and evolving. |
| 3. Ground-truth fragility | Incomplete-judgment handling (`bpref`), LLM-assisted judging research, evaluator-reliability studies | **Partially solved** | Label coverage/noise can be reduced, but subjectivity and judge drift remain fundamental for personal corpora. |
| 4. Non-additive feature interactions | LTR (LambdaMART), sparse+dense combination/interpolation studies | **Partially solved** | Interaction learning is possible, but supervision quality and overfitting risks remain. |
| 5. Adaptive-policy taxonomy gap | Contextual-bandit formalization | **Partially solved** | Framework exists, but it does not provide the right corpus feature schema or policy family automatically. |
| 6. Adaptive cold start | LinUCB exploration; replay/offline policy evaluation | **Partially solved** | Early regret/instability is reduced but not eliminated under sparse initial data. |
| 7. Drift/revalidation uncertainty | Drift detection (ADWIN, MD3-style monitoring) | **Partially solved** | Detection can trigger review but cannot determine the new best policy by itself. |
| 8. Mean-vs-tail relevance conflict | Segment-aware evaluation, long-tail benchmark reporting | **Partially solved** | Tail regressions become visible, but require explicit class constraints in objective/promotion rules. |
| 9. Causal attribution uncertainty | Counterfactual LTR/click-metric estimators | **Partially solved** | Bias reduction depends on propensity/logging assumptions and can fail under policy mismatch. |
| 10. Statistical decision-threshold gap | Randomization/significance guidance; multiple-comparison corrections | **Mostly solved** | Literature provides credible promotion protocols if we commit to one test family and correction policy. |
| 11. Runtime readiness variability | Context-aware routing/policy with runtime state features | **Unsolved directly** | Retrieval literature does not remove infra readiness variance; this is product-specific policy design. |
| 12. Offline-to-live transfer gap | Interleaving validation + counterfactual/offline estimators | **Partially solved** | Methods reduce but do not close transfer gap under behavior shift and logging-policy drift. |

### Critical conclusion

1. Research validates the blocker list; no evidence suggests the concerns are artificial.
2. Only blocker 10 (decision-threshold protocol) is near-complete from existing literature.
3. Most blockers are mitigation problems, not elimination problems.
4. The implication for tempdoc 229 is unchanged: relevance-first is viable, but confidence requires explicit objective definition, segmented evaluation, and periodic revalidation.

### Primary sources used

- BEIR benchmark heterogeneity: https://arxiv.org/abs/2104.08663
- MAIR benchmark (long-tail modern IR tasks): https://arxiv.org/abs/2410.10127
- Query performance prediction survey: https://arxiv.org/abs/2302.09947
- `bpref` (incomplete judgments): https://doi.org/10.1145/1008992.1009000
- LLMJudge (LLM relevance judging): https://arxiv.org/abs/2408.08896
- Evaluator reliability limits (LLM vs human): https://arxiv.org/abs/2502.13908
- NIST caution on LLM-as-judge reliability: https://doi.org/10.54195/irrj.19625
- Sparse+dense interpolation behavior: https://arxiv.org/abs/2205.00235
- LambdaMART overview: https://www.microsoft.com/en-us/research/publication/from-ranknet-to-lambdarank-to-lambdamart-an-overview/
- Contextual bandits / LinUCB: https://arxiv.org/abs/1003.0146
- Replay-based offline evaluation: https://arxiv.org/abs/1003.5956
- Unbiased LTR from biased feedback: https://arxiv.org/abs/1608.04468
- Counterfactual LTR framework: https://arxiv.org/abs/1805.00065
- Counterfactual click metric estimation: https://arxiv.org/abs/1403.1891
- Online-vs-counterfactual ranking evaluation analysis: https://arxiv.org/abs/1907.06412
- Interleaved search evaluation validation: https://www.microsoft.com/en-us/research/publication/large-scale-validation-and-analysis-of-interleaved-search-evaluation/
- ADWIN (adaptive windowing concept drift): https://doi.org/10.1137/1.9781611972771.42
- MD3 drift detection: https://arxiv.org/abs/1704.00023
- IR significance test reliability: https://arxiv.org/abs/1905.11096
- Inferential framework at scale (search/recommendation): https://arxiv.org/abs/2305.02461
- Multiple-comparisons in IR experiments: https://arxiv.org/abs/2501.03930

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Overfitting to one benchmark/corpus | Evaluate across multiple corpus/query classes |
| Aggregate relevance masks important regressions | Require per-class reporting and gating |
| Adaptive policy complexity without gain | Compare directly against best global static policy |
| Drift after policy selection | Schedule periodic re-evaluation and policy revalidation |

## Dependencies

| Tempdoc/Doc | Relationship |
|-------------|--------------|
| 194 / 221 | Confirms this is not pipeline-engine work |
| 222 | Chunk-aware interactive retrieval is an important feature candidate |
| 223 | Query expansion is implemented and must be evaluated in combinations |
| 213 | Agent formatting remains related but secondary to this relevance scope |
| 216 / 230 | Existing eval harness/lane infrastructure should be reused and expanded |

## Current Decision State

1. We optimize relevance first.
2. We keep UX simple (single default search experience).
3. We evaluate combinations explicitly instead of assuming additive gains.
4. We allow corpus-adaptive default policy if evidence supports it.
5. We target >=8GB VRAM baseline and defer hardware tiering.

## Fundamental Planning Approach (How We Proceed)

Planning should proceed as a gate-driven decision program, not a feature roadmap.

Core principle:

- Each planning step must reduce one blocker with a written rule or evidence requirement.
- No step is complete until it produces an artifact listed in this tempdoc.
- No implementation starts until theoretical gates are ratified.

Planning order:

1. Ratify the decision contract first (objective, guardrails, stats protocol).
2. Ratify evaluation universe and labeling policy.
3. Plan interaction-mapping experiments and adaptive policy family boundaries.
4. Plan cold-start/readiness fallback behavior.
5. Plan drift/revalidation governance.
6. Plan offline-to-live validation ladder and promotion protocol.

Planning unit:

- One unit of progress = one gate artifact moved from draft -> reviewed -> frozen.
- Avoid broad "phase complete" claims without frozen artifacts.

Planning cadence:

- Run planning in short cycles.
- End each cycle with a checkpoint that answers:
  - what was frozen,
  - what remains ambiguous,
  - what evidence/spec is required next.

Decision posture:

- Prefer the simplest policy that satisfies relevance goals.
- Adaptive policy is optional and must beat static policy under the same gates.
- If evidence is mixed, default to static policy and continue evidence collection.

Definition of planning success:

- A-F gates are ratified with explicit thresholds and rules.
- Final decision memo can be written without introducing new undefined terms.
- Implementation backlog can be generated mechanically from frozen gate artifacts.

## Resolution Program (Blocker-Driven, Tempdoc-Owned)

All decisions, gates, and evidence for this strategy are tracked in this tempdoc. Nothing is considered "resolved" unless the corresponding gate below is satisfied.

### Workstream A: Relevance decision contract

Blockers targeted: 1, 8, 10

Objective: remove ambiguity around what "better relevance" means and how policy promotion is decided.

Required outputs:

- One weighted relevance utility definition (primary metric bundle + weights)
- Query-class guardrails ("must not regress" limits for critical classes)
- One fixed promotion protocol (significance test family, correction for multiple comparisons, minimum effect size)

Gate A (exit criteria):

- Objective function is frozen in writing
- Guardrails are frozen in writing
- Promotion protocol is frozen in writing

### Workstream B: Evaluation universe and label policy

Blockers targeted: 2, 3

Objective: ensure "average relevance" claims are tied to a defensible evaluation universe and label quality policy.

Required outputs:

- Corpus/query scenario portfolio definition (not a single pooled benchmark)
- Query-class taxonomy used in reporting and gating
- Labeling policy with confidence tiers (human anchor labels vs weak/LLM supplemental labels)
- Label uncertainty/sensitivity protocol for policy comparisons

Gate B (exit criteria):

- Scenario portfolio accepted as representative enough for decisions
- Label policy accepted with explicit limits on weak-label usage

### Workstream C: Interaction mapping and policy-space definition

Blockers targeted: 4, 5

Objective: identify which feature combinations help or hurt, and define a bounded adaptive policy family.

Required outputs:

- Feature-combination experiment matrix (baseline, single additions, interaction combos)
- Beneficial vs harmful combination map
- Minimal corpus-profile taxonomy (small, auditable profile set)
- Policy-family definition mapped to each profile

Gate C (exit criteria):

- Combination map is complete for candidate feature set
- Profile taxonomy and policy mapping are fixed for evaluation

### Workstream D: Cold-start and runtime-readiness policy

Blockers targeted: 6, 11

Objective: stabilize early behavior and ensure deterministic behavior under capability/readiness variance.

Required outputs:

- Cold-start default policy rule (safe baseline before enough evidence)
- Minimum-evidence rule before adaptive switching is allowed
- Runtime-readiness decision table (what happens when embedding/reranker legs are unavailable)
- Deterministic fallback ordering

Gate D (exit criteria):

- Cold-start and fallback behavior are fully specified and deterministic

### Workstream E: Drift and revalidation governance

Blockers targeted: 7

Objective: define when the chosen policy is considered stale and must be revalidated.

Required outputs:

- Drift trigger definitions (what signals force re-check)
- Revalidation cadence policy (periodic + event-driven)
- Promotion-retention rule (when policy keeps or loses default status)
- Safe fallback rule when confidence drops below threshold

Gate E (exit criteria):

- Drift and revalidation lifecycle is fully defined and testable by governance checks

### Workstream F: Causal confidence and offline-to-live transfer

Blockers targeted: 9, 12

Objective: reduce false confidence from offline wins and improve confidence that gains transfer to user-perceived relevance.

Required outputs:

- Attribution policy for experiments (how causality claims are made and qualified)
- Staged validation ladder:
  - offline eval,
  - replay/shadow checks,
  - limited live interleaving,
  - broader rollout
- Replication requirement before final promotion

Gate F (exit criteria):

- Policy promotion requires passing all stages in the validation ladder

## Execution Sequence and Promotion Gates

1. Gate A must pass before any comparative policy claims.
2. Gates B and C must pass before selecting static-vs-adaptive strategy.
3. Gate D must pass before any adaptive default can be considered safe.
4. Gate E must pass before policy is considered production-stable.
5. Gate F must pass before asserting live relevance improvement confidence.

No "best on average" claim (directional or otherwise) is accepted until Gates A-F are passed.

## Required Tempdoc Artifacts (Completion Checklist)

- [x] Relevance decision contract (objective, guardrails, stats protocol)
- [x] Scenario portfolio and query-class taxonomy
- [x] Label policy and uncertainty protocol
- [x] Interaction matrix and beneficial/harmful combination map
- [x] Corpus-profile taxonomy and adaptive policy-family map
- [x] Cold-start + readiness fallback decision table
- [x] Drift triggers + revalidation cadence + retention rules
- [x] Staged validation ladder and promotion requirements
- [ ] Final decision memo: best static policy vs adaptive policy family (with rejected alternatives)
  - Blocked until residual-risk evidence rows `RR-U1`, `RR-U2`, `RR-U3`, `RR-U6`, and `RR-U7` are passed.

## Draft Gate Specifications (Theoretical, No Implementation)

These draft values have been superseded by frozen `v1` contracts in Cycle Records. They are retained for traceability.

### Gate A draft: Relevance decision contract

Draft objective function (for policy comparison):

- `Utility = 0.50 * nDCG@10 + 0.30 * MRR@10 + 0.20 * Precision@10`
- Utility is computed as macro-average across query classes (equal class weight).

Draft guardrails:

- No critical query class may regress by more than `1.0%` relative nDCG@10 vs current default.
- Worst-class regression cap across all tracked metrics: `2.0%` relative.
- Any class with directional regression in 2+ metrics is promotion-blocking unless explicitly exempted.

Draft statistical promotion protocol:

- Paired randomization (or paired bootstrap) for metric deltas.
- `alpha = 0.05` with Holm-Bonferroni correction across tested policies.
- Minimum practical effect for promotion: `+1.5%` relative Utility and non-inferiority on all guardrails.

### Gate B draft: Evaluation universe and label policy

Draft scenario portfolio (minimum):

- Corpus scale buckets: small, medium, large
- Content mix buckets: code-heavy, prose-heavy, mixed
- Structure buckets: short-note-heavy, long-document-heavy
- Language buckets: single-language and mixed-language corpora

Draft query-class coverage requirement:

- Keep existing classes (typo, morphology, semantic paraphrase, long-passage, technical token).
- Require balanced reporting by class; pooled mean alone is invalid for decisions.

Draft labeling policy:

- Human-labeled anchor set is primary truth set.
- Weak/LLM labels are supplemental only and cannot independently justify promotion.
- Calibration pass required between human anchor labels and supplemental labels before use.

### Gate C draft: Interaction mapping and policy family

Draft interaction-eval sequence:

1. Baseline vs single-feature additions
2. Pairwise interactions among top single-feature winners
3. Limited higher-order combinations from surviving pairs

Draft harmful-combination rule:

- A combo is "harmful" if it improves pooled Utility but violates any class guardrail.
- A combo is also "harmful" if repeated runs show unstable sign (gain/loss flips) on primary metric.

Draft adaptive-policy taxonomy shape:

- Limit to `3-5` corpus profiles for first version.
- Each profile maps to a small candidate policy subset (`<=3` policies/profile).
- Include one universal static fallback policy outside adaptive mapping.

### Gate D draft: Cold start and runtime readiness

Draft cold-start behavior:

- New corpus starts on safe static baseline policy.
- Adaptive switching is disabled until minimum evidence threshold is reached.

Draft minimum evidence threshold:

- Adaptive switching requires:
  - at least `300` observed queries in corpus context,
  - at least `40` queries per critical class (or class-insufficient fallback),
  - two consecutive windows with same winner direction,
  - corrected significance + practical effect threshold pass.

Draft readiness fallback policy:

- Policy selection must include runtime capability checks (embedding/reranker readiness).
- If capabilities are missing, fallback path is deterministic and pre-ranked.
- Fallback must preserve query-class guardrails as far as possible.

### Gate E draft: Drift and revalidation

Draft drift triggers:

- Significant drop in rolling relevance proxies on canary/monitor sets
- Query-distribution shift crossing predefined distance threshold
- Readiness degradation rate crossing operational threshold

Draft revalidation policy:

- Periodic scheduled revalidation every `21 days` plus event-driven revalidation on drift trigger.
- Default policy status expires after `30 days` without successful revalidation.

### Gate F draft: Causal confidence and offline-to-live transfer

Draft staged validation ladder:

1. Offline benchmark significance + guardrail pass
2. Replay/shadow consistency pass
3. Limited live interleaving pass
4. Gradual rollout with stop criteria

Draft promotion confidence rule:

- Offline win alone is insufficient.
- Promotion requires consistent directionality across at least two adjacent ladder stages.
- Any live-stage guardrail breach forces rollback to prior safe default.
- Final default-policy promotion requires replication on a second corpus portfolio.

## Theoretical Exit Condition for Tempdoc 229

Tempdoc 229 is theoretically complete when:

1. Gates A-F each have frozen decision rules,
2. required artifacts are produced and reviewed,
3. one of two conclusions is supported by evidence:
   - static default policy is best, or
   - bounded corpus-adaptive policy family is best.

At that point, implementation can start as a separate step.

## Ratified Defaults (Planning Cycle Outcome)

The following defaults were frozen in planning `v1` (Cycles 1-4).

1. Relevance Utility weights:
   - `Utility = 0.50 * nDCG@10 + 0.30 * MRR@10 + 0.20 * Precision@10`
   - Macro-averaged across query classes.

2. Class guardrails:
   - Critical-class regression cap: `1.0%` relative nDCG@10.
   - Worst-class regression cap: `2.0%` relative across tracked metrics.
   - No hard no-regression class is designated in `v1`.

3. Adaptive-switch minimum evidence:
   - At least `300` observed queries in corpus context.
   - At least `40` queries in each critical class (or explicit class-insufficient fallback).
   - Two consecutive evaluation windows with the same winning policy direction.
   - Corrected significance pass plus practical effect threshold pass.

4. Revalidation cadence:
   - Time-based: every `21 days`.
   - Event-based: immediate revalidation on drift trigger.
   - Retention rule: default policy status expires after `30 days` without successful revalidation.

5. Promotion posture:
   - Default to static policy unless adaptive policy family beats static under Gates A-F.
   - Final default-policy promotion requires replication on a second corpus portfolio.

## Frozen Validation Thresholds (v1.1 Addendum)

The following thresholds were ratified in Cycle 5 and are now frozen for planning `v1.1`.

1. U1 objective-alignment close target:
   - Spearman rank-correlation between human relevance ordering and Utility ordering must be `>= 0.70`.
   - Confidence lower bound for the same statistic must be `>= 0.60`.

2. Gate B minimum per-bucket support:
   - Every required evaluation bucket must have at least `40` queries.

3. Adaptive stability threshold (Gate D):
   - Maximum allowed adaptive policy-switch rate is `<= 1 switch per 200 queries`.

4. Live-stage minimum sample size (Gate F):
   - Each live ladder stage requires at least `1000` queries before final promotion can be declared.

## Fundamental Uncertainty Register (Planning-Level)

These are the highest-impact uncertainties that must be closed before implementation planning can be considered stable.

| ID | Uncertainty | Why it matters | Resolution method (theoretical) | Closure criterion |
|----|-------------|----------------|----------------------------------|-------------------|
| U1 | Objective validity (Utility weights) | Wrong objective causes systematic mis-optimization | Preference-alignment study between human relevance ordering and metric utility ordering | Spearman `rho >= 0.70` with confidence lower bound `>= 0.60` on anchor query set |
| U2 | Evaluation representativeness | "Average gain" can be invalid if portfolio misses real distributions | Coverage audit of scenario portfolio vs target query/corpus hypothesis, including tail slices | Every required bucket has `>= 40` queries and no known high-risk slice is unrepresented |
| U3 | Label trustworthiness | Noisy labels can promote the wrong policy | Human-anchor labeling + supplemental-label calibration + sensitivity analysis | Promotion winner and sign are unchanged under the frozen perturbation envelope |
| U4 | Adaptive-switch thresholds | Too-low threshold causes churn/regret; too-high threshold blocks useful adaptation | Threshold sweep with replay/simulation and stability scoring | One threshold profile is selected as Pareto-best for quality gain vs switch stability |
| U5 | Profile taxonomy sufficiency | Poor profile schema breaks adaptive policy selection | Separability test of profile definitions vs policy winner differences | Final profile set is minimal and still shows robust winner separation |
| U6 | Drift trigger calibration | Bad triggers cause either stale policy lock-in or excessive revalidation churn | Backtest trigger rules against historical quality-shift episodes | Trigger precision `>= 0.70` and false-alarm rate `<= 0.15` on backtest set |
| U7 | Offline-to-live confidence transfer | Offline winner may fail in live usage | Staged validation ladder with consistency checks across stages | Winner keeps direction and passes guardrails across ladder with `>= 1000` queries per live stage |
| U8 | Guardrail strictness tradeoff | Overly strict guardrails block gains; loose guardrails allow tail harm | Frontier analysis of global Utility gain vs class-risk exposure | Chosen guardrails sit at agreed risk-performance frontier knee point |

## Residual-Risk Closure Program

This program governs closure of residual planning risk before final promotion decisions:

1. Freeze thresholds and pass/fail criteria (`v1.1`) before collecting closure evidence.
2. Produce one named evidence artifact per residual-risk uncertainty.
3. Evaluate each artifact against its exact pass/fail rule from the matrix below.
4. If a rule fails, apply its escalation/retry rule and keep uncertainty status as `residual-risk` or `blocked`.
5. Only close uncertainties when evidence passes and is linked in the status board.

Artifact verdict to uncertainty-status mapping (frozen in Cycle 8):

1. `RR-U1` fail -> `residual-risk`
2. `RR-U2` fail -> `blocked`
3. `RR-U3` fail -> `residual-risk`
4. `RR-U6` fail -> `residual-risk`
5. `RR-U7` fail -> `blocked`

## Residual-Risk Evidence Matrix

| Matrix ID | Uncertainty | Evidence Artifact | Data Requirement | Evaluation Method | Pass/Fail Rule | Escalation/Retry Rule | Owner |
|-----------|-------------|-------------------|------------------|-------------------|----------------|-----------------------|-------|
| RR-U1 | U1 | `rr-u1-objective-alignment-report.v1` | Human-labeled anchor query set with at least `200` paired ranking judgments (preferred). If unavailable, proxy LLM/self-judged set may be used for directional evidence only. | Spearman rank-correlation with confidence interval estimation | Pass if `rho >= 0.70` and lower bound `>= 0.60`; else fail | If fail, keep Gate A `v1.1` frozen, mark U1 `residual-risk`, and run one additional alignment iteration with expanded anchor set (+`25%`). If only proxy labels are available, U1 cannot be marked `closed` and remains `residual-risk`. | Relevance Evaluation Owner |
| RR-U2 | U2 | `rr-u2-coverage-audit.v1` | Full scenario portfolio coverage table across all required buckets | Coverage completeness audit (manifest audit metadata is advisory traceability only) | Pass if every required bucket has `>= 40` queries; else fail | If fail, mark U2 `blocked` until missing buckets are filled; no promotion decisions using incomplete portfolio | Evaluation Dataset Owner |
| RR-U3 | U3 | `rr-u3-label-sensitivity-report.v1` | Human-anchor labels plus supplemental labels on same query slice | Sensitivity analysis with perturbation envelope (`10%` random label flips + one-grade relevance noise) | Pass if policy winner identity and improvement sign do not change under perturbation envelope; else fail | If fail, reduce supplemental-label influence and re-run calibration; keep U3 as `residual-risk` | Label Quality Owner |
| RR-U6 | U6 | `rr-u6-drift-trigger-backtest.v1` | Historical time-window dataset containing known quality-shift intervals | Backtest precision/false-alarm analysis of drift triggers | Pass if trigger precision `>= 0.70` and false-alarm rate `<= 0.15`; else fail | If fail, keep U6 `residual-risk`, retune trigger thresholds, and require repeat backtest before retention policy activation | Search Governance Owner |
| RR-U7 | U7 | `rr-u7-transfer-consistency-report.v1` | Staged ladder results with `>= 1000` queries for each live stage | Cross-stage directionality and guardrail consistency check | Pass if winner direction is consistent and non-zero and guardrails pass in all required ladder stages; else fail | If fail, rollback to static default path and keep adaptive path in `blocked` status until new staged run completes | Online Evaluation Owner |

## Execution State and Handoff

Cycles 1-14 have been executed sequentially and recorded below.

Implementation remains out of scope for this tempdoc; this document now serves as the evidence-backed planning handoff state.

## Cycle Ledger

| Cycle | Status | Started | Completed | Checkpoint Outcome |
|-------|--------|---------|-----------|--------------------|
| Cycle 1 | completed | 2026-02-21 | 2026-02-21 | Gate A frozen (`v1`) |
| Cycle 2 | completed | 2026-02-21 | 2026-02-21 | Gate B frozen (`v1`) |
| Cycle 3 | completed | 2026-02-21 | 2026-02-21 | Gates C and D frozen (`v1`) |
| Cycle 4 | completed | 2026-02-21 | 2026-02-21 | Gates E and F frozen (`v1`) |
| Cycle 5 | completed | 2026-02-21 | 2026-02-21 | Threshold addendum ratified (`v1.1`) |
| Cycle 6 | completed | 2026-02-21 | 2026-02-21 | Residual-risk evidence protocol frozen |
| Cycle 7 | completed | 2026-02-21 | 2026-02-21 | Final decision/handoff package frozen |
| Cycle 8 | completed | 2026-02-21 | 2026-02-21 | RR tooling remediation and uncertainty-closure contract realignment |
| Cycle 9 | completed | 2026-02-21 | 2026-02-21 | U1 human-anchor availability policy frozen (proxy evidence allowed, no closure without anchors) |
| Cycle 10 | completed | 2026-02-22 | 2026-02-22 | Evidence readiness frozen (`ready`: U1/U3/U6; `blocked`: U2/U7) |
| Cycle 11 | completed | 2026-02-22 | 2026-02-22 | Primary RR pack executed (all 5 artifacts generated; overall verdict `fail`) |
| Cycle 12 | completed | 2026-02-22 | 2026-02-22 | Mechanical status mapping + one retry on U3/U6 completed; statuses frozen |
| Cycle 13 | completed | 2026-02-22 | 2026-02-22 | Final decision memo executed: static default selected; adaptive path blocked-by-evidence |
| Cycle 14 | completed | 2026-02-22 | 2026-02-22 | Handoff finalized with `ready-now` static backlog and explicit adaptive blockers |

## Gate Freeze Register

| Gate | Version | Status | Finalized In | Frozen Decisions |
|------|---------|--------|--------------|------------------|
| A | v1.1 | frozen | Cycle 5 | Utility weights `0.50/0.30/0.20`, guardrails `1.0%/2.0%`, no hard class, `alpha=0.05`, Holm-Bonferroni, min `+1.5%` Utility effect, U1 close target `rho>=0.70` and LB `>=0.60` (`RR-U1`) |
| B | v1.1 | frozen | Cycle 5 | Portfolio dimensions fixed (scale/content/structure/language/query-class), human-anchor-first labeling policy, weak/LLM supplemental-only rule, minimum per-bucket support `>=40` (`RR-U2`) |
| C | v1 | frozen | Cycle 3 | Interaction mapping sequence fixed, harmful-combination rule fixed, profile taxonomy bounded to `3-5` profiles and `<=3` policies/profile |
| D | v1.1 | frozen | Cycle 5 | Cold-start static baseline fixed, adaptive switch threshold fixed (`300/40` + 2 stable windows), adaptive stability threshold `<=1 switch/200 queries`, deterministic readiness fallback order fixed |
| E | v1 | frozen | Cycle 4 | Drift trigger families fixed, revalidation cadence fixed (`21-day` periodic + event-based), retention expiry fixed (`30 days`) |
| F | v1.1 | frozen | Cycle 5 | Staged validation ladder fixed, offline-only promotions disallowed, replication on second corpus portfolio required, minimum live-stage sample `>=1000` queries (`RR-U7`) |

Cycles 10-14 gate register update:

- No gate-contract revisions were required; gates remained frozen.
- Cycle 10-14 work attached evidence and status outcomes to existing frozen contracts.

## Uncertainty Status Board

| ID | Status | Finalized In | Notes |
|----|--------|--------------|-------|
| U1 | residual-risk | Cycle 12 | `RR-U1` primary artifact passed at `tmp/rr229/evidence/20260222-113354/rr-u1-objective-alignment-report.v1.json`, but evidence is proxy-only (no human-anchor judgments), so U1 cannot be `closed` per Cycle 9 policy |
| U2 | blocked | Cycle 12 | `RR-U2` failed at `tmp/rr229/evidence/20260222-113354/rr-u2-coverage-audit.v1.json` (`3/15` buckets passing; multiple missing/underfilled required buckets) |
| U3 | residual-risk | Cycle 12 | `RR-U3` failed primary and retry artifacts (`tmp/rr229/evidence/20260222-113354/rr-u3-label-sensitivity-report.v1.json`, `tmp/rr229/evidence/20260222-113354/retry-1/rr-u3-label-sensitivity-report.v1.retry1.json`) with unstable winner/sign |
| U4 | closed | Cycle 3 | Adaptive switch thresholds frozen (`300/40` + stability windows) |
| U5 | closed | Cycle 3 | Minimal profile taxonomy shape and policy bounds frozen |
| U6 | residual-risk | Cycle 12 | `RR-U6` failed primary and retry artifacts (`tmp/rr229/evidence/20260222-113354/rr-u6-drift-trigger-backtest.v1.json`, `tmp/rr229/evidence/20260222-113354/retry-1/rr-u6-drift-trigger-backtest.v1.retry1.json`) with precision `0` and FAR `0.368421` |
| U7 | blocked | Cycle 12 | `RR-U7` returned `blocked` at `tmp/rr229/evidence/20260222-113354/rr-u7-transfer-consistency-report.v1.json` due missing required live stages (`limited_interleaving`, `gradual_rollout`) |
| U8 | closed | Cycle 1 | Guardrail strictness decided and frozen at planning level |

Exact status vocabulary (frozen):

- `closed`: pass/fail criterion met and evidenced.
- `residual-risk`: criterion defined, with unresolved risk remaining after available evidence (pending evidence or failed evidence with retry exhausted/insufficient for closure).
- `blocked`: criterion cannot be fully evaluated or promoted due to missing prerequisite artifact/stage.

## Cycle 1 Record

Targeted uncertainties: `U1`, `U8`  
Target gate: `A`

Protocol trace:

1. Marked Cycle 1 `in_progress` in ledger.
2. Added Gate A decisions and rationale.
3. Added evidence references.
4. Updated Gate A status to `frozen`.
5. Updated U1/U8 statuses.
6. Updated open-question set.
7. Marked Cycle 1 `completed`.

Gate A contract `v1`:

- Utility frozen: `0.50 * nDCG@10 + 0.30 * MRR@10 + 0.20 * Precision@10` (macro by query class).
- Guardrails frozen: `1.0%` critical-class cap, `2.0%` worst-class cap.
- Hard no-regression class: none in `v1`.
- Promotion stats protocol frozen: paired randomization (or paired bootstrap), `alpha=0.05`, Holm-Bonferroni, min `+1.5%` Utility effect and non-inferiority on guardrails.

Decision rationale:

- Balances rank quality and early precision while explicitly protecting tails through class guardrails.
- Avoids hard-class absolutism in v1 to prevent unnecessary global-quality stagnation.

Evidence references:

- Internet Research Findings section in this tempdoc.
- IR significance and correction references listed in "Primary sources used."

Cycle outcome:

- Gate A frozen.
- U8 closed.
- U1 marked residual-risk pending empirical preference-alignment validation.

## Cycle 2 Record

Targeted uncertainties: `U2`, `U3`  
Target gate: `B`

Protocol trace:

1. Marked Cycle 2 `in_progress` in ledger.
2. Added Gate B decisions and rationale.
3. Added evidence references.
4. Updated Gate B status to `frozen`.
5. Updated U2/U3 statuses.
6. Updated open-question set.
7. Marked Cycle 2 `completed`.

Gate B contract `v1`:

- Evaluation portfolio dimensions frozen:
  - scale: small/medium/large
  - content: code-heavy/prose-heavy/mixed
  - structure: short-note-heavy/long-document-heavy
  - language: single-language/mixed-language
  - query classes: typo/morphology/paraphrase/long-passage/technical-token
- Coverage policy frozen:
  - every dimension bucket must be represented,
  - every tracked query class must be represented,
  - pooled averages are invalid without class-level reporting.
- Label policy frozen:
  - human-anchor set is authoritative,
  - weak/LLM labels are supplemental only,
  - calibration against anchor set required before use in policy decisions,
  - sensitivity analysis must show decision stability under plausible label-noise perturbations.

Decision rationale:

- Locks representativeness and label-governance rules before any policy comparisons.

Evidence references:

- BEIR/MAIR and QPP references in "Primary sources used."
- LLM-judge reliability references in "Primary sources used."

Cycle outcome:

- Gate B frozen.
- U2 and U3 marked residual-risk pending empirical coverage and noise-stability checks.

## Cycle 3 Record

Targeted uncertainties: `U4`, `U5`  
Target gates: `C`, `D`

Protocol trace:

1. Marked Cycle 3 `in_progress` in ledger.
2. Added Gate C/D decisions and rationale.
3. Added evidence references.
4. Updated Gate C/D statuses to `frozen`.
5. Updated U4/U5 statuses.
6. Updated open-question set.
7. Marked Cycle 3 `completed`.

Gate C contract `v1`:

- Interaction sequence frozen:
  1. baseline vs single-feature additions,
  2. pairwise interactions among top single winners,
  3. limited higher-order combos from surviving pairs.
- Harmful-combination rule frozen:
  - any combo that violates guardrails is harmful even if pooled Utility increases,
  - unstable sign across repeated runs on primary metric is harmful.
- Profile taxonomy constraints frozen:
  - `3-5` profiles total,
  - `<=3` candidate policies per profile,
  - one universal static fallback policy.

Gate D contract `v1`:

- Cold-start rule frozen: new corpus starts on safe static baseline.
- Adaptive enablement rule frozen:
  - `>=300` observed queries in corpus context,
  - `>=40` queries per critical class or class-insufficient fallback rule,
  - two consecutive windows with same winner direction,
  - corrected significance + practical effect threshold pass.
- Runtime-readiness fallback order frozen:
  1. full hybrid path (BM25 + vector + rerank) when ready,
  2. hybrid without rerank when reranker unavailable,
  3. BM25 + correction/expansion when vector leg unavailable,
  4. BM25-only safe fallback when degraded.

Decision rationale:

- Converts adaptation to a bounded, auditable policy family with deterministic degradation behavior.

Evidence references:

- Contextual bandit and replay/offline-eval references in "Primary sources used."
- Sparse+dense interaction references in "Primary sources used."

Cycle outcome:

- Gates C and D frozen.
- U4 and U5 closed at planning level.

## Cycle 4 Record

Targeted uncertainties: `U6`, `U7`  
Target gates: `E`, `F`

Protocol trace:

1. Marked Cycle 4 `in_progress` in ledger.
2. Added Gate E/F decisions and rationale.
3. Added evidence references.
4. Updated Gate E/F statuses to `frozen`.
5. Updated U6/U7 statuses.
6. Updated open-question set.
7. Marked Cycle 4 `completed`.

Gate E contract `v1`:

- Drift trigger families frozen:
  - rolling relevance-proxy degradation on monitoring sets,
  - query-distribution shift signal crossing configured threshold,
  - runtime-readiness degradation rate crossing configured threshold.
- Revalidation schedule frozen:
  - periodic revalidation every `21 days`,
  - immediate event-driven revalidation on drift trigger.
- Retention rule frozen:
  - policy default status expires after `30 days` without successful revalidation.

Gate F contract `v1`:

- Validation ladder frozen:
  1. offline benchmark pass,
  2. replay/shadow pass,
  3. limited live interleaving pass,
  4. gradual rollout pass.
- Promotion confidence rules frozen:
  - offline-only win is insufficient,
  - adjacent-stage directionality consistency is required,
  - guardrail breach in live stage forces rollback.
- Final-promotion replication rule frozen:
  - winner must replicate on a second corpus portfolio.

Decision rationale:

- Encodes policy lifecycle governance that explicitly bridges offline and live uncertainty.

Evidence references:

- ADWIN/MD3 drift references in "Primary sources used."
- Interleaving and counterfactual evaluation references in "Primary sources used."

Cycle outcome:

- Gates E and F frozen.
- U6 and U7 marked residual-risk pending empirical calibration and live consistency validation.

## Cycle 5 Record

Targeted uncertainties: `U1`, `U2`, `U4`, `U7`  
Target gates: `A`, `B`, `D`, `F`

Protocol trace:

1. Marked Cycle 5 `in_progress` in ledger.
2. Added frozen validation thresholds (`v1.1` addendum).
3. Added gate patch revisions and traceability references.
4. Updated affected gate versions to `v1.1`.
5. Updated uncertainty notes with matrix-link placeholders.
6. Replaced numeric open-threshold ambiguity with ratified values.
7. Marked Cycle 5 `completed`.

Decisions frozen in Cycle 5:

- U1 close target: Spearman `rho >= 0.70` and lower bound `>= 0.60`.
- Gate B minimum support: `>= 40` queries per required bucket.
- Adaptive stability threshold: `<= 1 switch / 200 queries`.
- Live-stage minimum sample: `>= 1000` queries per stage.

Decision rationale:

- Removes all remaining numeric governance ambiguity from planning.
- Keeps thresholds strict enough to reduce false promotions while preserving operational feasibility.

Evidence references:

- Existing "Primary sources used" references in this tempdoc.
- Frozen Validation Thresholds (`v1.1`) section.

Cycle acceptance outcome:

- No unresolved numeric thresholds remain in the tempdoc.
- All four thresholds are traceable: Gate register -> Cycle 5 record -> Uncertainty status board.

## Cycle 6 Record

Targeted uncertainties: `U1`, `U2`, `U3`, `U6`, `U7`  
Target artifacts: residual-risk evidence protocol

Protocol trace:

1. Marked Cycle 6 `in_progress` in ledger.
2. Added residual-risk closure program.
3. Added residual-risk evidence matrix with pass/fail rules and owner roles.
4. Updated uncertainty status notes to reference exact matrix IDs.
5. Confirmed allowed status vocabulary (`closed`, `residual-risk`, `blocked`) only.
6. Updated governance notes for escalation/retry behavior.
7. Marked Cycle 6 `completed`.

Decisions frozen in Cycle 6:

- Every residual risk has one named evidence artifact and one unambiguous pass/fail rule.
- Matrix IDs `RR-U1`, `RR-U2`, `RR-U3`, `RR-U6`, `RR-U7` are canonical for closure tracking.

Decision rationale:

- Converts residual risk from narrative concern into explicit governance workflow.

Evidence references:

- Residual-Risk Evidence Matrix section.

Cycle acceptance outcome:

- Every residual risk now has a closure protocol.
- No residual risk remains without named evidence ownership.

## Cycle 7 Record

Targeted outcome: final decision/handoff freeze  
Target artifacts: decision memo template + implementation handoff package

Protocol trace:

1. Marked Cycle 7 `in_progress` in ledger.
2. Added final decision memo template and completion criteria.
3. Added implementation handoff package with ordered backlog and dependency rules.
4. Updated checklist state for final decision memo to conditional/blocking mode.
5. Updated open-questions section to ratified-closed state.
6. Verified stop/go compatibility with residual-risk evidence protocol.
7. Marked Cycle 7 `completed`.

Decision rationale:

- Removes implementer-side policy decisions by predefining decision memo structure and backlog dependencies.

Evidence references:

- Gate Freeze Register (`v1.1` rows).
- Residual-Risk Evidence Matrix (`RR-U1`..`RR-U7` rows).

Cycle acceptance outcome:

- Handoff package is planning-complete.
- Remaining uncertainty is explicitly blocked by evidence, not by missing policy decisions.

## Cycle 8 Record

Targeted uncertainties: `U1`, `U2`, `U6`, `U7`  
Target artifacts: RR evidence tooling remediation and contract realignment

Protocol trace:

1. Marked Cycle 8 remediation decisions and status mapping rules.
2. Realigned RR-U2 closure semantics to bucket-only pass/fail (`>=40` per required bucket); manifest audit retained as advisory metadata.
3. Realigned RR-U1 objective-alignment method to tie-aware Spearman behavior.
4. Realigned RR-U6 derived-mode trigger construction to independent signals (no truth-label leakage into trigger labels).
5. Realigned RR-U7 directionality rule to require non-zero consistent direction across required stages.
6. Patched rr229 pack summary semantics (`ok` now reflects true overall verdict).
7. Added/expanded tests for boundary behavior, derived-mode leakage guard, zero-direction failure, and orchestrator fail-path reporting.

Fixed issues list (Cycle 8):

1. RR-U2 no longer blocks on missing manifests when bucket thresholds pass.
2. RR-U1 no longer uses lexical tie-breaking for utility-score ties.
3. RR-U6 derived-mode no longer includes `quality_failure` in alarm derivation.
4. RR-U7 can no longer pass with zero-direction required stages.
5. rr229 pack summary now reports `ok=false` on non-pass outcomes.
6. RR-U6 derived-mode coverage now includes fixture-based tests.
7. RR-U1 threshold-boundary behavior now has explicit regression tests.

Evidence-tooling version note:

- RR tooling is now at remediation state `Cycle-8` (contract-aligned with matrix rules and closure mapping).

Cycle acceptance outcome:

- Tooling contract matches frozen decisions.
- Residual-risk statuses remain evidence-pending until real artifact runs are attached.

## Cycle 9 Record

Targeted uncertainty: `U1`  
Target artifact/policy: human-anchor availability handling for `RR-U1`

Protocol trace:

1. Ratified that model/self-judging cannot fully replace human-anchor alignment evidence for U1 closure.
2. Allowed proxy LLM/self-judged evidence for directional signal when human anchors are unavailable.
3. Froze governance rule that proxy-only evidence cannot mark U1 `closed`.
4. Kept final adaptive/static decision rule unchanged for fully closed evidence paths.

Decision rationale:

- Preserves methodological integrity of "objective alignment" while still allowing progress under real labeling constraints.
- Avoids false confidence from evaluator circularity and keeps risk accounting explicit.

Cycle acceptance outcome:

- U1 handling is decision-complete under both availability conditions:
  - human anchors available -> standard RR-U1 closure path;
  - human anchors unavailable -> proxy directional evidence path with U1 retained as `residual-risk`.

## Cycle 10 Record

Targeted uncertainties: `U1`, `U2`, `U3`, `U6`, `U7`  
Checkpoint goal: evidence-readiness freeze under `Latest Local` policy

Protocol trace:

1. Built manifest selection artifact: `tmp/rr229/inputs/rr229-latest-local-manifest-selection.v1.json`.
2. Built canonical RR inputs:
   - `tmp/rr229/inputs/rr-u1-proxy-judgments.v1.json`
   - `tmp/rr229/inputs/rr-u2-bucket-observations.v1.json`
   - `tmp/rr229/inputs/rr-u3-proxy-label-comparisons.v1.json`
   - `tmp/rr229/inputs/rr-u7-stage-ladder.v1.json`
3. Built readiness artifact: `tmp/rr229/inputs/rr229-evidence-readiness-manifest.v1.json`.
4. Classified row readiness from manifest:
   - `RR-U1`: `ready`
   - `RR-U2`: `blocked` (`missing_required_buckets(content.code,content.prose,language.mixed,query_class.typo,query_class.morphology,query_class.long_passage)`)
   - `RR-U3`: `ready`
   - `RR-U6`: `ready`
   - `RR-U7`: `blocked` (`missing_required_stages(limited_interleaving,gradual_rollout)`)
5. Froze Cycle 10 checkpoint and pinned inputs for Cycle 11.

Evidence references:

- `tmp/rr229/inputs/rr229-latest-local-manifest-selection.v1.json`
- `tmp/rr229/inputs/rr229-evidence-readiness-manifest.v1.json`

Cycle acceptance outcome:

- Readiness state successfully frozen before primary evidence run.
- No implicit inputs were used for Cycle 11.

## Cycle 11 Record

Targeted uncertainties: `U1`, `U2`, `U3`, `U6`, `U7`  
Checkpoint goal: primary RR evidence run

Protocol trace:

1. Executed RR pack with pinned inputs to `tmp/rr229/evidence/20260222-113354`.
2. Accepted non-zero process exit (`1`) because non-pass artifacts are expected behavior under current evidence gaps.
3. Verified all mandatory artifacts were generated.
4. Recorded per-row verdicts from pack report.

Primary evidence artifact set:

1. `tmp/rr229/evidence/20260222-113354/rr-u1-objective-alignment-report.v1.json` -> `pass`
2. `tmp/rr229/evidence/20260222-113354/rr-u2-coverage-audit.v1.json` -> `fail`
3. `tmp/rr229/evidence/20260222-113354/rr-u3-label-sensitivity-report.v1.json` -> `fail`
4. `tmp/rr229/evidence/20260222-113354/rr-u6-drift-trigger-backtest.v1.json` -> `fail`
5. `tmp/rr229/evidence/20260222-113354/rr-u7-transfer-consistency-report.v1.json` -> `blocked`
6. `tmp/rr229/evidence/20260222-113354/rr229-pack-report.v1.json` -> overall `fail`

Cycle acceptance outcome:

- Cycle 11 complete: all five RR rows emitted machine-readable verdict artifacts.

## Cycle 12 Record

Targeted uncertainties: `U1`, `U2`, `U3`, `U6`, `U7`  
Checkpoint goal: mechanical status transition + one retry for eligible failed rows

Primary mapping applied (frozen Cycle 8 rules):

1. `U1`: primary `RR-U1` verdict `pass`, but proxy-only evidence policy (Cycle 9) prevents closure -> `residual-risk`.
2. `U2`: `RR-U2` verdict `fail` -> `blocked`.
3. `U3`: `RR-U3` verdict `fail` -> `residual-risk`.
4. `U6`: `RR-U6` verdict `fail` -> `residual-risk`.
5. `U7`: `RR-U7` verdict `blocked` -> `blocked`.

Allowed one-retry runs executed:

1. `RR-U3` retry (reduced supplemental weight): `tmp/rr229/evidence/20260222-113354/retry-1/rr-u3-label-sensitivity-report.v1.retry1.json` -> `fail`.
2. `RR-U6` retry (same historical source, no additional tunable trigger input available in latest-local set): `tmp/rr229/evidence/20260222-113354/retry-1/rr-u6-drift-trigger-backtest.v1.retry1.json` -> `fail`.

Cycle acceptance outcome:

- Status transitions finalized with no policy deviation from frozen mapping.
- Post-Cycle 12 status snapshot: `U1 residual-risk`, `U2 blocked`, `U3 residual-risk`, `U6 residual-risk`, `U7 blocked`.

## Cycle 13 Record

Targeted outcome: final decision memo execution from frozen rules  
Checkpoint goal: static-vs-adaptive default decision

Decision memo execution result:

1. Adaptive eligibility check failed:
   - `RR-U2` failed (coverage blocker),
   - `RR-U7` blocked (missing live ladder stages),
   - `RR-U3` and `RR-U6` remained failed after retry,
   - `RR-U1` remained proxy-only (cannot close U1 by policy).
2. Decision path selected: **static default path**.
3. Adaptive path status: **blocked-by-evidence** until blocked matrix rows are resolved.
4. Memo completion state: `blocked` (required RR checklist not satisfied).

Rejected alternative:

- Rejected: adaptive default promotion in current cycle.
- Rejection basis: `RR-U1` proxy-only limitation + non-pass outcomes on `RR-U2`, `RR-U3`, `RR-U6`, and `RR-U7`.

Cycle acceptance outcome:

- Cycle 13 complete with deterministic decision output and explicit blocking IDs.

## Cycle 14 Record

Targeted outcome: implementation handoff finalization  
Checkpoint goal: zero decision-gap handoff for implementation planning

Handoff partition frozen:

1. `ready-now`:
   - static default implementation planning and rollout path,
   - gate-enforced relevance reporting/governance for static path,
   - deterministic runtime fallback behavior for static path.
2. `blocked-by-evidence`:
   - adaptive default promotion path (`RR-U2`, `RR-U7` blockers),
   - adaptive policy robustness closure (`RR-U3`, `RR-U6` unresolved failures),
   - adaptive objective-closure requirement (`RR-U1` human-anchor evidence missing).

Cycle acceptance outcome:

- Another engineer can proceed with static-path implementation planning without adding policy decisions.
- Adaptive-path work is explicitly blocked by evidence IDs, not ambiguity.

## Final Decision Memo (Executed Cycle 13)

Decision scope:

- Compared static default policy vs bounded corpus-adaptive policy family under frozen Gates A-F.

Frozen decision rule applied:

- Adaptive is eligible only if all required RR rows pass with closure-valid evidence.
- Otherwise, static default is selected and adaptive is deferred.

Required evidence checklist (Cycle 13 execution):

| Row | Evidence artifact | Outcome | Checklist state |
|-----|-------------------|---------|-----------------|
| `RR-U1` | `tmp/rr229/evidence/20260222-113354/rr-u1-objective-alignment-report.v1.json` | `pass` (proxy-only) | `blocked-for-closure` (Cycle 9 policy: no close without human-anchor judgments) |
| `RR-U2` | `tmp/rr229/evidence/20260222-113354/rr-u2-coverage-audit.v1.json` | `fail` | `blocked` |
| `RR-U3` | `tmp/rr229/evidence/20260222-113354/rr-u3-label-sensitivity-report.v1.json` + retry | `fail` | `blocked` |
| `RR-U6` | `tmp/rr229/evidence/20260222-113354/rr-u6-drift-trigger-backtest.v1.json` + retry | `fail` | `blocked` |
| `RR-U7` | `tmp/rr229/evidence/20260222-113354/rr-u7-transfer-consistency-report.v1.json` | `blocked` | `blocked` |

Decision outcome:

1. Selected default path: `static-default`.
2. Adaptive path: `blocked-by-evidence`.
3. Memo status: `blocked` (required RR checklist did not pass).

Rejected alternative:

- Rejected: adaptive default promotion in this cycle.
- Reason: non-pass outcomes and closure-policy constraints across `RR-U1`, `RR-U2`, `RR-U3`, `RR-U6`, and `RR-U7`.

## Implementation Handoff Package

### Ready-Now (Static Path)

1. Implement Gate A decision contract reporting for static default selection.
2. Implement Gate B portfolio/label-policy enforcement for static policy evaluation lanes.
3. Implement deterministic runtime fallback behavior for static policy (Gate D fallback table).
4. Implement Gate E revalidation governance for static default retention (`21`-day cadence, `30`-day expiry).
5. Implement static-path promotion/retention wiring using existing Gate F blockers (offline-only still disallowed for adaptive promotion).

### Blocked-By-Evidence (Adaptive Path)

1. Adaptive coverage closure blocked by `RR-U2` until required bucket support reaches `>=40` per bucket.
2. Adaptive transfer closure blocked by `RR-U7` until full ladder includes `limited_interleaving` and `gradual_rollout` with `>=1000` queries/stage and allowed live sources.
3. Adaptive robustness closure blocked by unresolved `RR-U3` and `RR-U6` failures after one retry.
4. Adaptive objective closure blocked by `RR-U1` human-anchor requirement (proxy-only evidence cannot close U1).

Handoff guarantee:

- Static-path implementation planning can proceed now without adding policy decisions.
- Adaptive-path implementation remains explicitly blocked by matrix IDs, not by unresolved planning ambiguity.

## RR Evidence Tooling (Implemented)

Planning/evidence tooling for `RR-U1`, `RR-U2`, `RR-U3`, `RR-U6`, and `RR-U7` is implemented and remediated through Cycle 9, then executed in Cycles 10-14 with latest-local evidence. Tooling does not guarantee closure by itself; closure remains evidence- and rule-dependent.

Implemented builders:

- `scripts/governance/bench-meta/build-rr-u1-objective-alignment-report.mjs`
- `scripts/governance/bench-meta/build-rr-u2-coverage-audit.mjs`
- `scripts/governance/bench-meta/build-rr-u3-label-sensitivity-report.mjs`
- `scripts/governance/resilience/build-rr-u6-drift-trigger-backtest.mjs`
- `scripts/governance/bench-meta/build-rr-u7-transfer-consistency-report.mjs`

Implemented schemas:

- `scripts/bench/schemas/rr-u1-objective-alignment-report.v1.schema.json`
- `scripts/bench/schemas/rr-u2-coverage-audit.v1.schema.json`
- `scripts/bench/schemas/rr-u3-label-sensitivity-report.v1.schema.json`
- `scripts/governance/resilience/rr-u6-drift-trigger-backtest.v1.schema.json`
- `scripts/bench/schemas/rr-u7-transfer-consistency-report.v1.schema.json`

Implemented orchestrator:

- `scripts/governance/bench-meta/run-rr229-pack-win.ps1`
  - Wave A parallel: `RR-U2`, `RR-U3`, `RR-U6`
  - Wave B: `RR-U1`
  - Wave C: `RR-U7`
  - Pack report artifact: `<outDir>/rr229-pack-report.v1.json`

Implemented verification tests:

- `scripts/governance/bench-meta/test-build-rr-u1-objective-alignment-report.mjs`
- `scripts/governance/bench-meta/test-build-rr-u2-coverage-audit.mjs`
- `scripts/governance/bench-meta/test-build-rr-u3-label-sensitivity-report.mjs`
- `scripts/governance/resilience/test-build-rr-u6-drift-trigger-backtest.mjs`
- `scripts/governance/bench-meta/test-build-rr-u7-transfer-consistency-report.mjs`
- `scripts/governance/bench-meta/test-run-rr229-pack-win.mjs`

Evidence status after Cycle 10-14 execution:

1. Real evidence artifacts are now generated and linked for all RR rows (`RR-U1`..`RR-U7`).
2. Current status outcomes are: `U1 residual-risk`, `U2 blocked`, `U3 residual-risk`, `U6 residual-risk`, `U7 blocked`.
3. Static path is selected for implementation planning; adaptive remains evidence-blocked pending future closure runs.

## Next High-Level Directions (Post Cycle 14)

1. Proceed with static-path implementation planning and execution using the `Ready-Now (Static Path)` handoff partition.
2. Treat adaptive-path work as evidence-gated and blocked until `RR-U2` and `RR-U7` blockers are resolved.
3. Run a dedicated evidence-refresh pass (new local data, no fabrication) to target closure of `RR-U2`, `RR-U3`, `RR-U6`, and `RR-U7`.
4. Acquire human-anchor judgments before attempting any `U1` closure claim; proxy-only path remains directional only.
5. Re-run Cycle 13 decision memo after blocker evidence refresh; only then reassess adaptive eligibility.

## Sequential Update Protocol Compliance

| Protocol Step | Cycle 1 | Cycle 2 | Cycle 3 | Cycle 4 | Cycle 5 | Cycle 6 | Cycle 7 | Cycle 8 | Cycle 9 |
|---------------|---------|---------|---------|---------|---------|---------|---------|---------|---------|
| 1. Mark cycle `in_progress` in ledger | done | done | done | done | done | done | done | done | done |
| 2. Add cycle decisions/rationale in `Cycle N Record` | done | done | done | done | done | done | done | done | done |
| 3. Add evidence references for decisions | done | done | done | done | done | done | done | done | done |
| 4. Update `Gate Freeze Register` | done | done | done | done | done | done | done | done | done |
| 5. Update `Uncertainty Status Board` | done | done | done | done | done | done | done | done | done |
| 6. Update `Open Questions` | done | done | done | done | done | done | done | done | done |
| 7. Mark cycle `completed` in ledger | done | done | done | done | done | done | done | done | done |

## Sequential Update Protocol Commitments (Cycles 10-14)

| Protocol Step | Cycle 10 | Cycle 11 | Cycle 12 | Cycle 13 | Cycle 14 |
|---------------|----------|----------|----------|----------|----------|
| 1. Mark cycle `in_progress` in ledger | done | done | done | done | done |
| 2. Add cycle decisions/rationale in `Cycle N Record` | done | done | done | done | done |
| 3. Add evidence references for decisions | done | done | done | done | done |
| 4. Update `Gate Freeze Register` | done | done | done | done | done |
| 5. Update `Uncertainty Status Board` | done | done | done | done | done |
| 6. Update `Open Questions` | done | done | done | done | done |
| 7. Mark cycle `completed` in ledger | done | done | done | done | done |

## Stop/Go Rules for Planning

1. If a cycle cannot close targeted uncertainties or explicitly classify them as `residual-risk`/`blocked` with rationale, freeze does not happen and defaults remain provisional.
2. No implementation scoping is allowed while any of U1-U8 lacks a defined closure criterion.
3. If adaptive path remains uncertain after Cycle 14 or any required matrix row fails, proceed with static default policy planning only.
4. Implementation planning may proceed for the static path when A-F are frozen and remaining adaptive gaps are explicitly evidence-blocked.

## Open Questions

None (policy and evidence-handshake flow ratified through Cycle 14; remaining adaptive work is evidence-blocked, not decision-ambiguous).

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 81 days at audit time.

