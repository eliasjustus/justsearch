---
title: Corpus Strategy and Eval Alignment
status: done
created: 2026-02-20
updated: 2026-02-20 (implemented, hardened, ci-signal-follow-up)
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation + docs/reference + code.

# 225: Corpus Strategy and Eval Alignment

## Purpose

Create a dedicated investigation/planning packet for corpus strategy across tests, evals, and benchmark lanes.

This tempdoc addresses two observed problems:
1. Different tests/evals currently depend on different corpora, so correctness and comparability depend on hidden corpus assumptions.
2. The "main corpus" using real production files may not be well-validated as the right representative corpus for app-level evaluation.

## Problem Statement (Observed)

Current workflow evolved into at least two practical corpus classes:
1. **Docs corpus** (repo `docs/` folder), mainly used for fast local agent/eval loops.
2. **Lane-specific corpora** (BEIR datasets, RAG truth/corpus resources, perf/track profiles), used by benchmark/eval lanes.

Observed impacts:
1. Some tests require docs corpus assumptions; others require lane-specific corpora/profiles.
2. Corpus mismatch can produce misleading failures (or false pass/fail interpretation) unrelated to model/runtime quality.
3. The "real production files corpus" lacks a clearly documented representativeness contract.

## Baseline State (Evidence-Backed)

| Surface | Current corpus pattern | Evidence |
|---|---|---|
| Agent live battery | Uses repo `docs/` ingestion + watched-root registration for meaningful scenario results | `docs/tempdocs/216-eval-harness-consolidation.md` lines 41-43, 1235-1246, 1443-1453 |
| RAG eval lane | Uses dedicated fixed resources (`rag-eval` truth + vectors) and profile-aware baselines | `docs/tempdocs/216-eval-harness-consolidation.md` lines 254-261, 1257-1268 |
| Search-eval / BEIR | Uses public BEIR dataset corpora (arguana/nfcorpus/scifact) with profile-aware baseline flow | `docs/tempdocs/216-eval-harness-consolidation.md` lines 240-244; `docs/tempdocs/219-runtime-resilience-hardening.md` lines 838-850 |
| Claim A/B benches | Use BEIR-derived corpus artifacts (`docs.ndjson`, `docs/`) for throughput validation | `docs/how-to/validate-performance.md` lines 65-76 |
| Track G / vector lanes | Use dedicated vector corpus workflows and profile knobs | `docs/explanation/20-benchmarking-architecture.md` lines 204-210; `docs/tempdocs/219-runtime-resilience-hardening.md` lines 731, 745 |
| Perf suite | Uses scenario/profile-specific evidence and baseline profiles (including no-UI variants) | `docs/how-to/validate-performance.md` lines 20-27; `docs/tempdocs/219-runtime-resilience-hardening.md` lines 641-647 |

Cross-cutting comparability contract:
1. Comparability keys are strict and mismatches are non-comparable, not regressions.
2. Threshold changes require explicit evidence and ownership.

Canonical source: `docs/reference/issues/benchmarking.md` lines 21-27.

## Critical Analysis of the Observed Issue

Your diagnosis is correct and high priority. The problem is not "which corpus is better" in isolation; it is that corpus selection is currently an implicit dependency in multiple lanes.

Main logic failures in the current state:
1. **Implicit coupling:** lane correctness depends on corpus shape and provenance, but this is not consistently enforced as a first-class contract.
2. **Signal pollution risk:** failures can reflect corpus mismatch rather than product quality regression.
3. **Representativeness ambiguity:** "real production corpus" is directionally valuable but can be biased, narrow, or unstable without an explicit coverage model.
4. **Operator friction:** developers must remember lane-specific corpus assumptions manually.

## Solution A: Multi-Corpus Support (Explicit Lane-to-Corpus Mapping)

User proposal:
1. Keep multiple corpora available.
2. Assign each eval/test lane to its required corpus via watched-folder/profile configuration.

### Strengths

1. Preserves lane fidelity (different lanes have different data-shape needs).
2. Aligns with current reality (profiles already exist in several lanes).
3. Reduces forced compromise from one-size-fits-all corpus decisions.
4. Enables controlled expansion (add a new corpus/profile without destabilizing unrelated lanes).

### Weaknesses / Risks

1. Higher operational complexity (more profiles, baselines, and comparability keys).
2. Drift risk if lane-to-corpus contracts are not centrally governed.
3. Higher maintenance burden for baseline refresh and history windows per profile.
4. Easier to accidentally compare across profiles unless preflight checks are strict.

### Non-Negotiable Controls (if adopted)

1. One canonical corpus registry with stable `corpus_profile_id`.
2. Every lane manifest includes `corpus_profile_id` + corpus signature/fingerprint.
3. Diff/gate tools fail preflight on profile mismatch.
4. Baseline naming and promotion are profile-scoped only.
5. CI summaries must always show selected corpus profile and comparability status.

## Solution B: Single Central Corpus

User proposal:
1. Standardize all relevant tests/evals on one "main corpus."

### Strengths

1. Lower mental overhead (one default corpus story).
2. Easier onboarding and fewer moving pieces.
3. Fewer baseline/profile artifacts to maintain.

### Weaknesses / Risks

1. Likely invalid for heterogeneous lanes (text ranking, vector lanes, RAG truth eval, agent scenarios, perf smoke all have different corpus assumptions and data structures).
2. One corpus can underfit critical behavior classes and mask regressions in specialized lanes.
3. Harder to preserve strict comparability when one corpus cannot satisfy all lane contracts equally.
4. Incentivizes lowest-common-denominator metrics over lane-specific correctness.

## Comparative Verdict

| Dimension | Multi-corpus | Single corpus |
|---|---|---|
| Technical correctness across heterogeneous lanes | High | Medium-Low |
| Operational simplicity | Medium-Low | High |
| Risk of misleading regressions from corpus mismatch | Low (if governed) | Medium-High |
| Long-term extensibility | High | Medium-Low |
| Near-term implementation cost | Medium | Low-Medium |

## Recommended Direction

Adopt **governed multi-corpus** as the base model, with a **single documented default corpus** for developer convenience.

This keeps correctness for specialized lanes while still providing a simple default workflow.

Practical policy:
1. One default corpus profile for quick local loops.
2. Additional mandatory profiles for lanes that require specialized corpus/truth data.
3. Any lane running in gate mode must declare and lock its corpus profile.

## Proposed Work Plan

### Phase 1: Corpus Contract Definition

1. Define `corpus_profile_id`, profile metadata, and required signature fields.
2. Classify each lane/test into one required profile (or allowed profile set).
3. Document representativeness criteria for the "production corpus" profile (coverage, freshness, stability, privacy constraints).

### Phase 2: Wiring and Guardrails

1. Add profile emission to lane manifests where missing.
2. Add preflight checks in diff/gate scripts to block mixed-profile comparisons.
3. Add CI summary surface showing profile used + comparability verdict.

### Phase 3: Baseline and History Governance

1. Move baseline naming/promotion to explicit profile ownership.
2. Ensure scorecards and history windows are profile-aware by default.
3. Track per-profile non-comparable incidents and stale baseline age.

### Phase 4: Production-Corpus Validation

1. Validate whether the production corpus profile is representative for intended app workloads.
2. If not representative, split into multiple production subprofiles with explicit use-cases.
3. Publish acceptance criteria for when a profile can be used for release-relevant gating.

## Pre-Implementation Research: Best Allrounder Corpus

### External Evidence (Primary Sources)

1. **BEIR** is intentionally heterogeneous (18 datasets across diverse tasks/domains) and designed for zero-shot robustness checks, not single-domain tuning.
2. **MTEB** reports no single embedding method dominates all tasks, which implies no single corpus/domain is a universal proxy.
3. **MMTEB** further expands this with 500+ tasks across 250+ languages and explicitly includes long-document and code retrieval.
4. **LoTTE** adds long-tail, domain-specific retrieval from StackExchange (writing/recreation/science/technology/lifestyle), closer to real mixed technical usage than single-domain corpora.
5. **MIRACL** provides multilingual retrieval coverage (18 languages), useful as a multilingual canary slice if needed.
6. **BRIGHT** shows reasoning-intensive retrieval is materially harder than standard benchmark retrieval, so a pure keyword/semantic corpus can miss important failures.
7. **CodeSearchNet** demonstrates code search has distinct query-document characteristics (natural language to code), which generic text corpora do not represent well.

### Research Implications for This Repo

1. There is no defensible "one dataset wins" answer for an allrounder corpus.
2. A practical allrounder must be **composite** (multi-component) even if exposed as a single profile ID.
3. Shape compatibility matters as much as topic coverage:
   - Claim B/Lane T needs text files with sentinel behavior.
   - Claim A needs NDJSON docs.
   - Lane V/Track G needs vector NDJSON.
   - Search eval needs judged query/qrels resources.
   - Agent battery needs browsable docs roots.
4. RAG eval remains a special case because its truth and corpus are explicitly frozen and already SHA-guarded; forcing it into a generic allrounder corpus would weaken comparability.

### Candidate Options (Weighted)

Scoring scale: 1 (weak) to 5 (strong).  
Weights: lane fit 30%, representativeness 25%, reproducibility 20%, judged-eval support 15%, operational cost 10%.

| Option | Lane fit | Representativeness | Reproducibility | Judged-eval support | Operational cost | Weighted score |
|---|---:|---:|---:|---:|---:|---:|
| Docs-only (`docs/`) | 2 | 2 | 4 | 1 | 5 | 2.55 |
| Single BEIR dataset | 3 | 2 | 5 | 4 | 4 | 3.40 |
| Production-files-only snapshot | 3 | 5 | 1 | 1 | 2 | 2.70 |
| **Composite allrounder profile (recommended)** | **5** | **4** | **4** | **4** | **3** | **4.20** |

### Recommended Allrounder Design (Pre-Implementation)

Define one default composite profile:
1. `corpus_profile_id = allrounder-core.v1`

Composition (component-based under one profile):
1. **Docs component**: repo docs slice needed for current agent battery scenarios.
2. **Technical prose component**: long-tail technical/community content (LoTTE-style domains).
3. **Judged retrieval component**: BEIR triad already used (`arguana`, `scifact`, `nfcorpus`) for stable ranking metrics.
4. **Code retrieval component**: CodeSearchNet-style natural-language-to-code pairs.
5. **Reasoning slice**: BRIGHT subset for reasoning-intensive retrieval checks.
6. **Optional multilingual canary**: small MIRACL slice (non-blocking at first).

Important architectural constraint:
1. "Single allrounder profile" should mean single **profile ID + governance**, not a single physical corpus file.  
2. Each lane reads the projection it needs (text, NDJSON, vectors, judged truth) from that profile.

### Governance Requirements (To Make This Safe)

1. Emit `corpus_profile_id`, `corpus_signature`, and `corpus_components[]` in every lane manifest.
2. Require profile/signature match for baseline comparability.
3. Track component-level licenses in a corpus BOM to avoid silent legal drift.
4. Maintain two tiers:
   - `allrounder-core.v1.small` for fast local loops.
   - `allrounder-core.v1.gate` for release-relevant evidence.

### Long-Term Confidence (Post-Research)

Current confidence in this direction: **7/10**.

Primary uncertainty themes before closure:
1. Source-of-truth for production corpus/profile governance.
2. Representativeness criteria for allrounder profile quality.
3. Runtime budget and stability envelope for gate usage.

## Uncertainty Closure (Locked Decisions)

Date locked: **2026-02-20**

### UC-001: Source-of-Truth (Closed)

Decision:
1. Canonical registry file for corpus governance will be:
   `scripts/bench/corpora/corpus-profiles.v1.json`
2. Registry schema will be:
   `scripts/bench/schemas/corpus-profiles.v1.schema.json`
3. Every profile record must include:
   - `corpus_profile_id`
   - `profile_revision`
   - `tier` (`small|gate|frozen`)
   - `owner_role`
   - `components[]` with pinned source/version/checksum
   - `signature_algo` (`sha256`)
   - `corpus_signature`
   - `license_bom_ref`
4. `corpus_signature` is computed from canonicalized profile JSON and must change on any component/version change.

Closure criteria:
1. All gate-capable lanes emit `corpus_profile_id` and `corpus_signature`.
2. Diff/gate scripts treat mismatched profile/signature as `non-comparable`.
3. Baseline promotion refuses artifacts missing these fields.

Status: **CLOSED (implemented).**

### UC-002: Representativeness Contract (Closed)

Decision:
1. `allrounder-core.v1` is a composite profile, not a single physical corpus.
2. `allrounder-core.v1.gate` must satisfy minimum coverage across dimensions:
   - content-type mix: prose, technical docs, code-adjacent artifacts
   - retrieval intent mix: navigational, factual, troubleshooting, config/API, comparative
   - document length mix: short, medium, long documents
   - judged-eval anchor coverage: BEIR triad (`arguana`, `scifact`, `nfcorpus`)
   - code retrieval slice: CodeSearchNet-style NL->code pairs
   - reasoning slice: BRIGHT-style hard retrieval subset
3. Agent battery docs-root dependency remains explicit as a required component.
4. RAG eval remains out-of-band on `rag-eval-frozen.v1` (truth/corpus SHA locked).

Representativeness acceptance gate:
1. `coverage_report` artifact exists for each profile revision and passes all required dimensions.
2. Missing required dimensions blocks `gate` classification for that profile revision.

Status: **CLOSED (implemented).**

### UC-003: Runtime Budget and Stability Envelope (Closed)

Decision:
1. Two operating tiers are mandatory:
   - `allrounder-core.v1.small`: developer loop profile
   - `allrounder-core.v1.gate`: release-evidence profile
2. Initial SLO targets:
   - `small` pack p95 wall time <= 30 minutes
   - `gate` pack p95 wall time <= 180 minutes
   - failure rate <= 5% over a 10-run calibration window for each tier
3. A profile cannot be promoted to `gate` if it violates time or stability envelope.

Closure criteria:
1. Calibration artifact records p50/p95 and failure rate for both tiers.
2. Scorecard/governance summary surfaces runtime envelope pass/fail.

Status: **CLOSED (implemented).**

### UC-004: Lane Policy and Enforcement Scope (Closed)

Decision:
1. Lane-to-profile policy is now explicit:

| Lane | Required profile policy | Gate class |
|---|---|---|
| `agent-battery` | `allrounder-core.v1.small` (dev), `allrounder-core.v1.gate` (release evidence) | gate-relevant |
| `search-rank` / BEIR gate | `allrounder-core.v1.gate` judged component required | gate-relevant |
| `claim-a` | `allrounder-core.v1.small` or `.gate` projection (NDJSON) | report + regression signal |
| `claim-b` | `allrounder-core.v1.gate` text projection with sentinel | report + regression signal |
| `track-g` | `allrounder-core.v1.gate` vector projection required | gate-relevant |
| `perf` | `allrounder-core.v1.small` (local) and `.gate` (release evidence) | gate-relevant |
| `rag-eval` | `rag-eval-frozen.v1` only | gate-relevant |

2. `claim-d` remains corpus-independent and out of corpus policy scope.

Closure criteria:
1. Lane manifests declare profile IDs that satisfy this table.
2. CI wrappers fail preflight when lane/profile pairing violates policy.

Status: **CLOSED (implemented).**

### UC-005: Legal/BOM Constraint Under Local-Only Mode (Closed)

Decision:
1. Corpus policy assumes local-only operation (no redistribution of corpora or derived artifacts outside repo-local evidence paths) unless explicitly overridden.
2. A lightweight BOM is still required to track component source/license and prevent accidental promotion of non-permitted artifacts into shared/release paths.

Closure criteria:
1. `license_bom_ref` present on every corpus profile entry.
2. CI/release profile checks block components flagged local-only from `gate` publish paths.

Status: **CLOSED (implemented).**

## Implementation Closure Checklist

To convert the locked decisions above into full closure:
1. [x] Add corpus registry + schema + validator.
2. [x] Add manifest fields (`corpus_profile_id`, `corpus_signature`, optional `corpus_components`).
3. [x] Add diff/gate preflight mismatch blocking.
4. [x] Add baseline promotion profile/signature checks.
5. [x] Add representativeness `coverage_report` generator + validator.
6. [x] Add runtime calibration artifacts and threshold checks for `small` and `gate`.
7. [x] Wire lane-policy preflight checks into CI wrappers.

## Acceptance Criteria for This Tempdoc

This tempdoc is complete when:
1. [x] A lane-by-lane corpus dependency matrix exists with explicit profile IDs.
2. [x] A decision is made on governance model (governed multi-corpus vs single corpus).
3. [x] "Production corpus representativeness" has objective criteria and validation method.
4. [x] A migration plan exists to remove hidden corpus assumptions from gate-critical paths.

## Implementation Completion (2026-02-20)

Implemented artifacts and enforcement:
1. Corpus governance contracts:
   - `scripts/bench/corpora/corpus-profiles.v1.json`
   - `scripts/bench/corpora/lane-corpus-policy.v1.json`
   - `scripts/bench/corpora/license-bom.v1.json`
   - validators and shared governance lib under `scripts/bench/corpora/`
2. Lane wrappers and CI/promotion paths enforce lane/profile pairing and stamp corpus identity fields.
3. Diff/policy paths enforce corpus comparability and emit non-comparable decisions on mismatch.
4. Baseline corpus metadata backfill migration executed for committed baseline artifacts.
5. Representativeness and runtime envelope artifacts implemented and surfaced in scorecard.
6. RR219 comparability governance extended to include corpus identity policy checks.
7. Workflow inputs/summaries updated to expose corpus profile state and corpus governance verdicts.

## Post-Implementation Hardening (2026-02-20)

Remaining non-long-run gaps were closed with additional guardrails:
1. `validate-suite-artifact` now supports `rag-eval` lane validation (`rag-eval.v1` and `bench-suite.v2` `suite_kind=rag-eval`) so rag baseline promotion fails with lane-appropriate validation errors, not usage fallback.
2. Corpus report builders now support explicit `--manifest` inputs, allowing deterministic coverage/runtime checks from curated manifest sets even when shared summary directories contain unrelated artifacts.
3. Scorecard now supports explicit `--coverage-report` and `--runtime-calibration` inputs, preventing stale/mixed artifact pickup during targeted verification.
4. Added regression tests for wrapper invalid-corpus preflight behavior and new corpus governance report builder explicit-input behavior.
5. Added `validate-suite-history` rag-eval matching regression coverage and schema-level contract checks for generated coverage/runtime artifacts.
6. Added consolidated quickcheck runner `scripts/ci/run-corpus-governance-quickcheck.ps1` and wired it as a dedicated pre-merge CI job in `.github/workflows/ci.yml`.

## CI Signal Hardening Follow-Up (2026-02-20)

Additional implementation pass to remove unresolved CI noise and UI build integrity risks:
1. Reworked Vite manual chunking in `modules/ui-web/vite.config.js` to deterministic path-based chunk assignment and explicit `react`/`react-dom`/`scheduler` colocation.
2. Removed the detected UI circular dependency chains by:
   - direct store imports in browse keyboard/view paths,
   - removing selector re-exports from `useSystemStore`,
   - switching selector consumers to `systemSelectors`,
   - breaking `useSearchStore` ↔ `useSearch` coupling via shared search-filter definitions,
   - replacing barrel self-imports in brain sections with direct component imports.
3. Added non-blocking npm audit reporting via `scripts/ci/report-npm-audit.mjs` (JSON artifact + step-summary markdown, warn-only policy).
4. Updated CI installs to `npm ci --no-audit --no-fund` and added explicit warn-only audit summary steps in fast/full jobs.
5. Hardened lockfile freshness diff checks to LF-safe git invocation in CI and local gate scripts (`core.autocrlf/core.safecrlf` step-local overrides).
6. Set `dorny/test-reporter@v2` to `only-summary: true` in fast/full jobs to avoid oversized check-run summary trimming.

## Open Questions

1. Do we keep the initial runtime SLO thresholds (30m small / 180m gate) or tighten after first calibration cycle?
2. Do we enforce optional multilingual canary slice from day one, or phase it in after core lane stability?

## Initial Decision Log

| Date | Decision | Why |
|---|---|---|
| 2026-02-20 | Open dedicated corpus strategy tempdoc | Corpus dependency became cross-lane reliability risk and comparability risk |
| 2026-02-20 | Evaluate both user-proposed directions directly | Need explicit tradeoff analysis before implementation |
| 2026-02-20 | Preliminary recommendation: governed multi-corpus with default profile | Better fit for heterogeneous lane requirements with manageable operator UX |
| 2026-02-20 | Lock uncertainty closures UC-001..UC-005 | Remove policy ambiguity before implementation; convert blockers into explicit contracts |

## References

1. `docs/tempdocs/216-eval-harness-consolidation.md`
2. `docs/tempdocs/219-runtime-resilience-hardening.md`
3. `docs/how-to/validate-performance.md`
4. `docs/explanation/20-benchmarking-architecture.md`
5. `docs/reference/issues/benchmarking.md`
6. https://arxiv.org/abs/2104.08663
7. https://arxiv.org/abs/2210.07316
8. https://arxiv.org/abs/2502.13595
9. https://raw.githubusercontent.com/stanford-futuredata/ColBERT/main/LoTTE.md
10. https://project-miracl.github.io/
11. https://arxiv.org/abs/2407.12883
12. https://arxiv.org/abs/1909.09436
13. https://embeddings-benchmark.github.io/mteb/overview/available_tasks/retrieval/

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 87 days at audit time.

