---
title: "276 — Automated Session Type Classification"
---

# 276 — Automated Session Type Classification

**Status:** DONE — committed to `main` 2026-03-11 (`68e38205`). G5 (provenance) deferred.
**Created:** 2026-03-11
**Scope:** Reduce the `unknown` session type bucket and enable type-aware scoring analysis.

---

## Problem

The scoring model in `score-session.mjs` uses per-type ceiling pooling, but 56% of sessions (75/135) had `task_type: unknown`. This blocked per-type ceilings, path convergence scoring, and cost-of-pass metrics (see tempdoc 274 Data Gap Analysis).

### Root Cause Analysis

**An LLM-as-judge classifier already exists.** `evaluate-session.mjs` sends condensed transcripts to Claude with a structured rubric and extracts `task_type`. The problem was coverage, not capability:

- 226 sessions (67%) were never evaluated — predated the hook system or evaluation was never triggered
- 98 of those still had transcripts on disk but the evaluator couldn't find them (only searched events, not the filesystem)
- Hook infrastructure captured `prompt_length` but discarded prompt text

---

## Implementation Progress

| Item | Status | Notes |
|------|--------|-------|
| G1: Run evaluator + fix coverage | **DONE** | +226 outcomes, +56 typed scoreable sessions. Unknown dropped 56% → 14%. Cost: $2.84. |
| G2: Capture prompt text | **DONE** | `dispatch.mjs` stores `prompt_excerpt` (500 chars), `analyze-session.mjs` extracts `first_prompt` into reports. |
| G4: Auto-evaluate on session end | **DONE** | `auto-evaluate.mjs` hook runs analyze + evaluate (Haiku) on SessionEnd. Async, 210s timeout. |
| G1 (code): Transcript fallback | **DONE** | `evaluate-session.mjs` scans `~/.claude/projects/` for transcripts when events are missing. |
| G1 (code): Windows CLI fix | **DONE** | Resolves `cli.js` directly instead of going through `.cmd` shim. |
| G3: Fallback classifier | **DONE** | Heuristic classifier using session report signals (git commits, file extensions, tool patterns). 19 sessions classified → 100% scoreable coverage. |
| G5: Classification provenance | Not started | Auditing infrastructure. Low priority. |

### Coverage Results (2026-03-11)

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Outcome records | 113 | 339 | +226 |
| Scoreable with type | 60 (44%) | 116 (86%) | **+56 sessions** |
| Unknown (scoreable) | 75 (56%) | 19 (14%) → **0 (0%)** | **-100%** (G3 fallback) |
| Types with N>=20 | 1 | 4 | investigation(64), feature(32), refactor(18), docs(11) |

### Files Changed

| File | Change |
|------|--------|
| `scripts/agent-analytics/hooks/dispatch.mjs` | Store `prompt_excerpt` in `user_prompt_submit` events |
| `scripts/agent-analytics/analyze-session.mjs` | Extract `first_prompt` into session reports |
| `scripts/agent-analytics/evaluate-session.mjs` | Transcript fallback scan, Windows CLI resolution, broader `--all` discovery, G3 heuristic classifier (`--fallback` mode) |
| `scripts/agent-analytics/hooks/auto-evaluate.mjs` | **New** — SessionEnd hook for automatic evaluation |
| `.claude/settings.local.json` | Wire auto-evaluate hook |

---

## Data Analysis (2026-03-11, N=135 scoreable sessions)

With 86% type coverage, the data now supports per-type analysis for the first time.

### Per-Type Score Distributions

| Type | N | Mean | Median | Range | IQR |
|------|---|------|--------|-------|-----|
| investigation | 53 | 50.7 | 49 | 15-96 | 39-64 |
| feature | 20 | **41.3** | 44 | 20-57 | 33-51 |
| refactor | 18 | 51.0 | 52 | 33-79 | 40-58 |
| docs | 10 | 65.2 | 70 | 49-85 | 50-79 |
| implementation | 8 | 61.1 | 66 | 49-70 | 57-69 |
| chore | 6 | 74.3 | 71 | 60-95 | 61-89 |
| unknown | 19 | 77.2 | 80 | 64-90 | 71-83 |
| **global** | **135** | **56.0** | — | **15-96** | — |

### Key Finding 1: Feature sessions are systematically penalized

Feature sessions score **15 points below the global mean** (41.3 vs 56.0). This is the most important finding:

- THRASHING flag fires on **30%** of feature sessions vs 12% globally (2.5x)
- But 9/20 feature sessions completed successfully — the low score reflects that feature work legitimately requires more iteration (reedits, build cycles)
- This confirms the "Pareto frontier" concern from tempdoc 274 research (LoCoBench): thoroughness correlates negatively with efficiency scores

### Key Finding 2: Implementation sessions have distinctive signal profiles

| Signal | Implementation mean | Global mean | Ratio |
|--------|-------------------|-------------|-------|
| `rapid_reedit_count` | 12.1 | 5.5 | 2.2x |
| `build_cycle_rate` | 0.179 | 0.026 | 6.9x |

These are legitimate work patterns (heavy editing + frequent build-test cycles) being penalized as waste.

### Key Finding 3: Scores do NOT predict task completion

| Completion | N | Mean score | Median |
|-----------|---|-----------|--------|
| complete | 73 | 52.7 | 50 |
| partial | 38 | 50.4 | 50 |

Delta of **2.3 points** — essentially zero. Per-type deltas are also tiny:
- investigation: complete=49.1 vs partial=51.1 (delta=-1.9)
- feature: complete=41.4 vs partial=40.1 (delta=+1.3)
- refactor: complete=53.5 vs partial=47.0 (delta=+6.5 — only type with meaningful separation)

This confirms tempdoc 118's finding (|r| < 0.30) at larger N. The scoring model measures **process hygiene, not outcome quality**.

### Key Finding 4: The `unknown` bucket scores artificially high

Unknown sessions average 77.2 (21 points above global mean). All 19 have `task_completion: unknown` — they were never evaluated. Their high scores likely reflect low-activity sessions (few tool calls = few waste signals = high score), not genuinely good performance.

---

## Actionable Opportunities

Based on the data analysis, four improvements are now possible:

### O1. Type-aware ceiling adjustment (high impact, medium effort)

Feature and implementation sessions are penalized for legitimate work patterns. The per-type hierarchical pooling partially adjusts, but the global ceilings are still anchored on investigation-heavy data (39% of typed sessions).

Concrete adjustments warranted:
- `rapid_reedit_count`: implementation mean is 12.1 vs global ceiling 40 — ceiling is fine, but the per-type ceiling for implementation should be derived from implementation data (currently pooled toward global due to N=8)
- `build_cycle_rate`: implementation mean is 0.179 vs global ceiling 0.25 — implementation sessions routinely hit 70%+ of the ceiling as normal behavior
- THRASHING flag: fires on 30% of feature sessions, suggesting the threshold isn't type-aware. Feature work may need a higher redit threshold before being flagged

### O2. Type-aware flag thresholds (medium impact, small effort)

THRASHING fires disproportionately on feature sessions (30% vs 12% global). Options:
- Raise the `rapid_reedit_count` threshold for feature/implementation types
- Or: change THRASHING to be type-relative (fire when reedits exceed 2x the type mean, not a fixed threshold)

### O3. Score interpretation with type context (low effort, immediate value)

Since scores don't predict outcomes, they should be presented with type context in the dashboard/reports. A feature session scoring 41 is typical for its type; an investigation session scoring 41 is below average. The dashboard could show per-type percentile bands.

### O4. Outcome-aware signal validation (research, medium effort)

With 73 `complete` and 38 `partial` outcomes across typed sessions, we can now check which signals actually differentiate successful vs unsuccessful sessions *within each type*. The refactor type shows the only meaningful delta (+6.5 points for complete vs partial) — this could reveal which signals have genuine predictive power for refactor work.

---

## External Research (2026-03-11)

Internet research across arXiv, vendor blogs, and industry reports (Feb-Mar 2026) validates our findings and approach.

### Task-type difficulty is empirically confirmed

- **FeatureBench** (arXiv 2602.10975, ICLR 2026): Claude 4.5 Opus scores 74.4% on SWE-bench but only 11.0% on feature-level tasks. Feature work is fundamentally harder for agents — our 15-point feature penalty is expected, not a scoring bug.
- **MSR '26 PR analysis** (arXiv 2602.08915): 7,156 PRs from 5 agents. Task type is the dominant factor in acceptance: docs 82.1% vs features 66.1%. No single agent wins all categories.
- **SWE-Compass** (arXiv 2511.05459): 8-type task taxonomy with empirical difficulty hierarchy. Feature Implementation and Bug Fixing are harder than Refactoring/Docs.

### Process metrics ≠ outcome quality — consensus position

- **METR Developer Productivity Study** (updated Feb 2026): AI tools slowed experienced devs by 19% despite self-reported 20% speedup. Process perception ≠ outcome. METR redesigning methodology because their process signals were unreliable.
- **Google Vertex AI trajectory metrics**: `trajectory_precision`, `trajectory_recall` offered as *complementary* to outcome metrics, not predictive.
- **Agent-as-a-Judge survey** (arXiv 2601.05111): Process signals useful for diagnosis but correlation with task success not established as strong. Agent judges that execute code achieve 0.3% disagreement with humans vs 31% for single-pass LLM judges.
- **BAO framework** (arXiv 2602.11351): Frames quality-efficiency as multi-objective optimization. Proposes "behavior regularization" to push the Pareto frontier rather than choosing a fixed point.

### Fallback classification from partial signals is viable

- **ICSE 2025 Conventional Commits**: CodeLlama classifies commits into 10 types at 76% F1 from metadata alone. "Refactor" and "chore" are hardest categories.
- **Intent detection** (arXiv 2410.01627): LLMs with in-context learning classify intent from short text competitively with fine-tuned models. Validates G3 using `prompt_excerpt`.
- **Tangled code changes** (arXiv 2505.08263): 0.88 F1 from just diffs + commit messages — partial signals sufficient.

### Agent telemetry ecosystem converging

- **OpenTelemetry**: 70%+ enterprise AI deployments use OTel. Microsoft building native OTel into VS Code/Copilot with same hook events we use.
- **Datadog AI Agents Console**: Real-time Claude Code telemetry — per-user spend, model costs, session analytics.
- **Community hooks**: `claude-code-hooks-multi-agent-observability`, `claude_telemetry` (OTel wrapper) use the same hook lifecycle we built on.
- **SWE-CI** (arXiv 2603.03823, Mar 2026): First benchmark for long-term maintenance over 233 days/71 commits. Introduces EvoScore — relevant to our multi-session tempdoc workflows.

### Implications for our work

| Item | External Evidence | Assessment |
|------|------------------|------------|
| O1 (type-aware ceilings) | FeatureBench, SWE-Compass, LoCoBench effort-adjusted scoring | State of art — proceed |
| O2 (type-aware thresholds) | BAO behavior regularization, adaptive thresholds paper | Well-supported |
| O3 (score interpretation) | MSR '26 per-category reporting, SWE-bench per-type breakdowns | Industry standard |
| G3 (fallback classifier) | Intent detection from short text, Conventional Commits 76% F1 | Very viable from `prompt_excerpt` |
| PHI reframing | METR, Google, Agent-as-a-Judge — process metrics are diagnostic, not predictive | Reframe as behavioral diagnostic |

---

## Remaining Work

G5 (classification provenance) is the only remaining item. With 100% scoreable coverage and the auto-evaluate hook (G4) preventing regression, G5 is auditing infrastructure — low priority. No external precedent exists for classification provenance in agent eval systems.

---

## Out of Scope

- **Improving the LLM judge rubric** — current rubric works well when transcripts are available
- **Token counting in telemetry** — prerequisite for cost-of-pass but orthogonal to classification
- **Turn-level credit assignment** — major instrumentation project, not related
- **Agent-as-a-Judge upgrade** — using agentic evaluators (code execution, test running) instead of single-pass LLM. Higher accuracy (0.3% vs 31% disagreement) but major effort increase. Future consideration.
- **OTel export** — our hooks emit similar signals to the OTel standard. Export adapter possible but orthogonal.
