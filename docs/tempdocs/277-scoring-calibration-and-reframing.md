---
title: "277 — Scoring Calibration and PHI Reframing"
---

# 277 — Scoring Calibration and PHI Reframing

**Status:** DONE — all items implemented 2026-03-11
**Created:** 2026-03-11
**Scope:** Calibrate the Process Hygiene Index for type-aware scoring and reframe it as a behavioral diagnostic (not a quality predictor).
**Depends on:** tempdoc 276 (session type classification — DONE, 100% coverage)

---

## Problem

The PHI scoring model in `score-session.mjs` uses global signal ceilings and fixed flag thresholds that penalize legitimate work patterns in certain task types. Tempdoc 276 data analysis (N=135, 100% type coverage) revealed:

1. **Feature sessions score 15 points below global mean** (41.3 vs 56.0) — THRASHING fires on 30% of feature sessions vs 12% globally
2. **Implementation sessions have 6.9x the build_cycle_rate** of global mean — normal build-test cycles penalized as waste
3. **Scores do not predict task completion** (delta=2.3 between complete and partial) — confirmed by external research (METR, Google, Agent-as-a-Judge survey)
4. **The model name "score" implies quality judgment** but it measures process discipline on a different axis

### External Validation (tempdoc 276 research, Feb-Mar 2026)

- **FeatureBench** (ICLR 2026): Feature tasks are 6.7x harder than bug fixes for agents — our penalty is expected
- **SWE-Compass**: 8-type task taxonomy with empirical difficulty hierarchy validates type-aware baselines
- **METR**: Process metrics are behavioral diagnostics, not quality predictors — consensus position
- **BAO** (arXiv 2602.11351): Quality-efficiency is a multi-objective Pareto frontier, not a single score
- **LoCoBench-Agent**: Negative correlation between comprehension and efficiency — thoroughness should not be penalized

---

## Implementation Plan

### C1. Reframe PHI as behavioral diagnostic — DONE

- Module docstring updated: "Process Hygiene Index (PHI)" with explicit note that it does not predict completion (r=0.064)
- Human output renamed: `Score:` → `PHI:`
- `--all` summary shows type baselines header with per-type mean PHI
- Each session shows type-relative delta: `41/100 [feature] (-0 vs type)`

### C2. Type-aware flag suppression — DONE

Based on C4 findings, flags that fire inversely to completion are suppressed per type:
- **WASTEFUL suppressed for `feature`**: `bash_fileop_pct` positively correlates with completion (r=+0.51, d=+1.11). Flag fired on 22% of completed features, 0% of partial.
- **THRASHING suppressed for `implementation`**: Flag fired on 33% of completed sessions, 0% of partial. Heavy edit-build cycles are productive.

Implementation: `suppressForTypes` array on each rule, checked in `classifySession(signals, taskType)`.

### C3. Outcome-informed weight rebalancing — DONE

Rebalanced `DEFAULT_WEIGHTS` based on C4 per-signal outcome correlations:
- `tool_failure_rate`: 0.05 → **0.15** (most consistent predictor, r=-0.19 to -0.43 across types)
- `subagent_density`: 0.15 → **0.10** (no outcome signal in any type, all |r|<0.12)
- `unbounded_read_pct`: 0.25 → **0.20** (mixed signal across types)
- `bash_fileop_pct`: 0.20 → **0.15** (positive for features, negative globally)
- `hot_file_concentration`: 0.05 → **0.10** (strong type-dependent signal, d=-1.30 for feature)

### C4. Outcome-aware signal validation — DONE

Computed per-signal point-biserial correlations with task completion (complete=1, partial=0) within each type. N=116 sessions with both type and completion data (73 complete, 38 partial).

#### Global: composite score does not predict completion

Composite score r=0.064, Cohen's d=0.13. Strongest individual signal: `tool_failure_rate` r=-0.198 (small). No signal exceeds |r|>0.20 globally.

#### Per-type: signals behave completely differently

**Feature (N=20, 9 complete, 10 partial) — large effects found:**

| Signal | r | Cohen's d | Meaning |
|--------|---|-----------|---------|
| `hot_file_concentration` | **-0.57** | -1.30 | Focus on fewer files → **complete** |
| `bash_fileop_pct` | **+0.51** | +1.11 | More bash file-ops → **complete** |
| `unbounded_read_pct` | **+0.46** | +0.99 | More unbounded reads → **complete** |
| `tool_failure_rate` | **-0.43** | -0.91 | More failures → **partial** |

WASTEFUL flag fires on 22% of *completed* feature sessions, 0% of partial. **The flag is inverted for features.**

**Refactor (N=18, 11 complete, 7 partial) — moderate effects:**

| Signal | r | Cohen's d | Meaning |
|--------|---|-----------|---------|
| `tool_failure_rate` | -0.42 | -0.88 | Failures → partial |
| `rapid_reedit_count` | -0.37 | -0.78 | More reedits → partial |

THRASHING fires on 29% of partial refactors, 0% of complete. **Genuinely predictive for refactors.**

**Investigation (N=53, 37 complete, 13 partial):**
All signals |r| < 0.20. Score r=-0.05. Investigation is unpredictable from process signals.

**Implementation (N=8, 3 complete, 5 partial):**
THRASHING fires on 33% of *complete* sessions, 0% of partial. **Inverted — penalizes success.**

#### Flag fire rates by type and outcome

| Type | THRASHING (complete) | THRASHING (partial) | Predictive? |
|------|---------------------|---------------------|-------------|
| feature | 22% | 40% | Weak (partial slightly higher) |
| refactor | 0% | **29%** | **Yes — partial only** |
| implementation | **33%** | 0% | **Inverted** |
| investigation | 5% | 0% | No |

#### Actionable conclusions for C2/C3

1. **Suppress THRASHING for implementation** — fires on completed sessions, penalizes productive build cycles
2. **Suppress WASTEFUL for feature** — `bash_fileop_pct` positively correlates with completion (d=+1.11)
3. **Increase `tool_failure_rate` weight** — most consistent negative predictor across types (r=-0.19 to -0.43)
4. **`hot_file_concentration`** behaves oppositely by type: focus helps investigation, hurts feature. Cannot use global weight.
5. **`subagent_density` predicts nothing** in any type (all |r| < 0.12) — candidate for weight reduction
6. **Composite score is useless globally** (r=0.064) but has moderate signal within refactor (r=0.24, d=0.47)

---

## Key Files

| File | Role |
|------|------|
| `scripts/agent-analytics/score-session.mjs` | PHI scoring model — ceilings, flags, signal extraction |
| `scripts/agent-analytics/evaluate-session.mjs` | Outcome evaluator (provides task_type and completion) |
| `tmp/agent-telemetry/outcomes.ndjson` | Outcome data (339 records, 144 typed) |
| `tmp/agent-telemetry/scores.ndjson` | Score data (135 records) |
| `docs/explanation/21-agent-analytics-pipeline.md` | Pipeline architecture docs |
| `docs/tempdocs/118-agent-efficiency-research.md` | Original research findings |
| `docs/tempdocs/276-session-type-classification.md` | Classification work and data analysis |

---

## Out of Scope

- **Agent-as-a-Judge upgrade** — using agentic evaluators instead of single-pass LLM. Major effort, separate concern.
- **OTel export** — exporting telemetry in OpenTelemetry format. Orthogonal infrastructure.
- **Cost-of-pass metrics** — requires token counting in telemetry (blocked on separate instrumentation).
- **Turn-level credit assignment** — major instrumentation project.
