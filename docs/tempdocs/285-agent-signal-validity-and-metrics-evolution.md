---
title: "285: Agent Signal Validity and Metrics Evolution"
type: tempdoc
status: done
created: 2026-03-13
depends-on: [264, 274, 276, 277]
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and tests before promotion.

# 285: Agent Signal Validity and Metrics Evolution

## Purpose

Tempdocs 264, 274, 276, and 277 built and calibrated the PHI analytics pipeline. Their findings
converge on one conclusion: **PHI detects friction patterns but does not predict outcomes**
(r=0.064, N=116). This tempdoc takes the next step — evolving the metrics to produce signals
that are causally meaningful and actionable.

This is not a rewrite of PHI. It is a targeted set of improvements informed by:
- empirical data from 135 scored sessions, 107K+ tool calls, $3,401 total spend
- internet research on signal validity in software engineering metrics
- published findings from METR, Google, Microsoft, DX, and academic papers

## Problem

### What the data shows

Aggregate data from `score-session.mjs`, `analyze-trends.mjs`, `cost-session.mjs`, and
`correlate-signals.mjs` (run 2026-03-13, N=135 scored sessions, 426 total):

| Metric | Value |
|--------|-------|
| Total tool calls | 107,605 |
| Total failures | 2,503 (2.3%) |
| Total failure cascades | 243 |
| Total compactions | 534 |
| Total redundant reads (wasteful) | 1,772 |
| Total rapid re-edit clusters | 747 |
| Total cost | $3,401 |
| Mean context efficiency | 79% |
| Sessions below 70% efficiency | 22 |
| THRASHING sessions | 15 |
| WASTEFUL sessions | 8 |

### What the signals miss

1. **Absolute metrics are weak predictors.** Microsoft's foundational research (Nagappan & Ball,
   ICSE 2005) showed absolute code churn is a poor predictor, but relative churn (normalized by
   LOC, files, time) predicts defects at 89% accuracy. Our `rapid_reedit_count` is absolute.

2. **Task completion is too coarse an outcome.** METR's research found pass/fail scoring
   overestimates agent performance. Binary completion hides cost waste, quality issues, and
   downstream rework.

3. **Task difficulty is a confounding variable.** Hard tasks naturally have more re-edits AND more
   failures. Aggregating across task types produces Simpson's paradox — within-type correlations
   (up to r=-0.57) disappear in global aggregation (r=0.064).

4. **Some signals measure compliance, not dysfunction.** `build_cycle_rate` flags behavior that
   CLAUDE.md explicitly requires ("compile after every file change"). High build rate may indicate
   discipline, not waste. The real waste signal is failed builds, not total builds.

5. **Threshold-based flagging produces unknown false positive rates.** Research on static vs.
   dynamic thresholds shows threshold alerting "often generates too many false positives that lead
   to wasted human time." Our 23 flagged sessions (15 THRASHING + 8 WASTEFUL) have no validated
   false positive rate.

6. **Edit volume doesn't distinguish convergence from oscillation.** An agent editing a file 20
   times might be making 20 improvements (healthy) or going in circles (thrashing). The
   distinguishing signal is direction — edits that revert previous edits vs. edits that extend.

## Research basis

### Primary sources

Academic:
- Nagappan & Ball, "Use of Relative Code Churn Measures to Predict System Defect Density"
  (ICSE 2005) — relative > absolute metrics
- Rousseeuw & Hubert, "Anomaly Detection by Robust Statistics" (2017) — MAD-based detection
- ACM TOSEM, "Cleaning Up Confounding: Instrumental Variables in Software Engineering" (2024)
  — endogeneity in observational data
- SWE-bench Verified / SWE-bench Pro — task difficulty stratification

Industry:
- METR developer productivity study (RCT, N=246) — process ≠ outcome
- METR transcript analysis (Feb 2026) — alternative evaluation methods
- Google QUANTS framework — multi-metric, no single-metric models
- DX Core 4 — speed, effectiveness, quality, impact
- SPACE framework — satisfaction, performance, activity, communication, efficiency
- Anthropic, "Demystifying Evals for AI Agents" — unit-test-based grading

Goodhart's Law:
- Jellyfish, "Goodhart's Law in Software Engineering"
- Hilel Wayne, "Goodhart's Law in Software Engineering"
- Core risk: optimizing re-edit count → agents make larger, riskier edits

### Key findings from our own data

**Hot files** — most re-read files across all sessions:

| File | Total reads | Sessions | Unbounded |
|------|-------------|----------|-----------|
| `LuceneIndexRuntime.java` | 821 | 21 | 0 |
| `SearchOrchestrator.java` | 668 | 23 | 40 |
| `AgentLoopService.java` | 659 | 10 | 63 |
| `server.mjs` | 306 | 10 | 25 |
| `dev-runner.cjs` | 273 | 8 | 23 |

**Worst thrashing sessions:**

| Session | Rapid re-edits | Hours | Cost | Worst file |
|---------|---------------|-------|------|------------|
| a7eb9fce | 42 | 56.3h | $30+ | — |
| 333b7ebf | 28 | 122.3h | $140 | — |
| 67cd7060 | 27 | 34.6h | $323 | — |

**Worst build-cycle sessions:**

| Session | Build rate | Hours | Failure cascades |
|---------|-----------|-------|-----------------|
| e8a89f89 | 56.5% | 42.8h | 19 |
| 1c4f7eb4 | 55.0% | 43.1h | 6 |
| bdbf2360 | 31.3% | 18.0h | 14 |

**Cost concentration:** Top 10 sessions = $2,167 (64% of $3,401 total spend).

### Signal validity assessment

| Signal | Verdict | Rationale |
|--------|---------|-----------|
| `tool_failure_rate` | **Strongest, keep** | Most consistent predictor (r=-0.19 to -0.43). Unambiguous waste. |
| `rapid_reedit_count` | **Real but needs refinement** | Absolute count is weak. Needs normalization + oscillation detection. |
| `build_cycle_rate` | **Misleading as-is** | Measures compliance with "compile after every change" rule. Replace with failed build rate. |
| `hot_file_concentration` | **Type-dependent** | Helpful for investigation (focus), harmful for features. Only meaningful combined with rapid_reedit. |
| `unbounded_read_pct` | **Mild, cost signal** | Wastes tokens, doesn't cause failures. Useful for cost reduction. |
| `bash_fileop_pct` | **Noise** | No causal mechanism linking tool preference to outcomes. |
| `subagent_density` | **Noise** | All |r|<0.12. Count doesn't matter; success rate might. |

## Implementation plan

### Phase 1: New signals that measure actual waste

#### 1a. Failed build rate signal — HIGH confidence

**Current state:** `build_cycle_rate` in `extractSignals()` already computes
`failed_build_count / code_edits` — it is already a failed-build metric despite its name. The
session report field `bash_commands.failed_build_count` tracks builds with non-zero exit codes
(populated by `dispatch.mjs` via `build-counter.mjs`).

**What to change:** Add a complementary `failed_build_pct` signal: `failed_builds / total_builds`
(not per-edit, but per-build-attempt). This captures compilation success rate directly. A session
that runs 50 builds with 2 failures (4%) is healthy; a session with 50 builds and 40 failures
(80%) is stuck.

**Data source:** `dispatch.mjs` records exit codes for all Bash/gradlew calls. `analyze-session.mjs`
already counts failed builds. Need to also count total builds (successful + failed) — check whether
this field exists in the session report or needs to be added.

**File:** `scripts/agent-analytics/score-session.mjs`

#### 1b. Edit oscillation detection — LOW confidence, BLOCKED

**Blocker:** `dispatch.mjs` deliberately strips edit content. It records `old_string_length` and
`new_string_length` but NOT the actual `old_string`/`new_string` values. This is a deliberate
privacy/size design choice. Without content, Levenshtein distance or revert detection is impossible.

**The original plan was wrong.** The tempdoc assumed edit content was available. It is not.

**Possible approaches (each has tradeoffs):**

1. **Content hash** — Record a hash (e.g., SHA-256) of `old_string` and `new_string` in
   `dispatch.mjs`. Enables exact revert detection (edit N's `new_string_hash` === edit N-2's
   `old_string_hash`) without storing content. No fuzzy matching. Adds ~64 bytes per edit event.
   Requires schema extension in `dispatch.mjs` and `analyze-session.mjs`.

2. **Length-ratio heuristic** — Flag as potential oscillation when edit N's `new_string_length` ≈
   edit N-2's `old_string_length` (within 10%). Very noisy, high false positive rate. Not
   recommended as primary signal.

3. **Start recording content** — Privacy/size concern. events.ndjson would grow substantially.
   Content is useful for other analysis (e.g., LLM-as-judge on edit quality) but changes the
   pipeline's storage model.

4. **Record content diff size** — Record the net lines added/removed per edit. A sequence of
   +10, -10, +10, -10 strongly suggests oscillation. Lightweight (~8 bytes per event).

**Research findings (2026-03-13):**

External research resolved this uncertainty. Three viable approaches exist:

**A. Content hash fingerprinting (recommended).** Record SHA-256 of `old_string` and `new_string`
in `dispatch.mjs`. An oscillation is detected when edit N's `new_string_hash` matches a prior
edit's `old_string_hash` for the same file within a time window. This gives exact revert detection
with zero false positives, without storing content. Storage cost: 64 bytes per edit event.
This is the same approach Git uses internally (SHA-1 of blob content). Used by Pluralsight Flow
and CodeScene for churn detection.

**B. Size-symmetry heuristic (works with current schema, no changes needed).** If edit A replaces
N chars with M chars, and edit B (same file, within T minutes) replaces M chars with N chars,
the exact symmetry `(A.old_len == B.new_len && A.new_len == B.old_len)` is a strong revert
signal. Analogous to Wikipedia vandalism detection bots (ClueBot NG). False positives are rare
because coincidental exact size matches within short windows are uncommon. Can also track
cumulative `net_delta` per file — return to zero suggests oscillation.

**C. Git-based post-hoc detection.** Use `git log` after sessions to compare blob hashes across
commits. Exact but requires git history and only works for committed changes.

**Decision:** Implement both A and B:
- B (size-symmetry) can be implemented immediately with no schema changes — it works on the
  existing `old_string_length` / `new_string_length` data for all historical sessions
- A (content hash) requires a schema extension in `dispatch.mjs` but gives higher precision
  for future sessions

**Confidence upgrade: LOW → MEDIUM.** Size-symmetry is implementable now; content hash is a
schema extension that requires testing but is straightforward.

#### 1c. Downstream rework tracking — MEDIUM confidence

Track whether session B fixes what session A produced — the strongest outcome variable for
multi-agent setups.

**Approach:** For each session, extract files modified from `file_edits.by_file[]`. If a later
session modifies the same files within 48 hours, record a `downstream_rework` event.

**False positive risk:** File-level granularity is too coarse. If session A implements feature X
in `Foo.java` and session B adds feature Y to the same file, that's independent work, not rework.
Function/class-level detection would require parsing, which is complex.

**Mitigations:**
- Weight by edit overlap: if both sessions edit the same region (line ranges), confidence is
  higher than if they edit different regions of the same file
- Exclude files known to be multi-touch (lockfiles, build scripts, proto files)
- Use session report's `file_edits.by_file[].timestamps` to detect temporal proximity

**File:** `scripts/agent-analytics/analyze-trends.mjs` (cross-session analysis)

**Research findings (2026-03-13):**

External research identified three approaches, in order of implementation complexity:

**A. Line-range overlap detection (recommended first step).** Record `start_line` and `end_line`
of each edit in the event stream. Two edits to the same file from different sessions are "related"
only if their line ranges overlap or are adjacent (within a 5-line margin). This catches 70-80%
of rework cases. Requires adding line range to the edit event schema in `dispatch.mjs`.
Used by MergeBetter for proactive conflict detection.

**B. Function-name attribution.** Extract the enclosing function name from the file at edit time
using a lightweight regex heuristic (e.g., `(public|private).*\s+(\w+)\s*\(` for Java). Store
`enclosing_symbol` alongside each edit. Two edits are related if they share the same symbol.
Does not require storing content.

**C. Context-hash matching (Pluralsight Flow approach).** Hash the N lines surrounding each edit
(4 lines above and below) without storing content. If surrounding-context hashes match between
two edits from different sessions, the second is likely rework.

**D. Co-change history (long-term).** Build a co-change matrix from historical data. If files or
functions historically change together, flag cross-session edits to co-changing artifacts as
potential rework. Requires accumulated data volume. Used by CodeScene.

**Decision:** Start with file-level overlap (current plan) plus exclusion list (lockfiles, build
scripts, proto files). Add line-range overlap (A) as a second phase if file-level produces too
many false positives. This requires adding `start_line`/`end_line` to the edit event schema.

**Confidence remains MEDIUM.** File-level is implementable now but noisy. Line-range requires
schema extension.

### Phase 2: Normalize existing signals

#### 2a. Relative rapid re-edit rate — HIGH confidence

Replace absolute `rapid_reedit_count` with relative measures:
- `reedit_per_total_edits` — re-edits / total edits (Nagappan & Ball's approach)
- `reedit_per_hour` — re-edits / session duration

Per Microsoft's research, these relative measures should produce stronger correlations than the
absolute count (which currently shows r=-0.028).

**File:** `scripts/agent-analytics/score-session.mjs`

#### 2b. Subagent success rate — MEDIUM confidence, needs event schema extension

Replace `subagent_density` (count per hour, |r|<0.12) with `subagent_failure_rate` — fraction of
subagent calls that fail or produce no useful output. This measures delegation quality, not volume.

**Blocker:** The `SubagentStop` event in `dispatch.mjs` does not record outcome (success/failure).
The event contains `agent_id`, `agent_type`, and `agent_transcript_path` but no result or exit
status. Without a defined "subagent failed" signal, this metric has no data source.

**Possible approaches:**
1. **Transcript-based inference** — Read subagent transcripts (paths are recorded) and check for
   error patterns or empty output. Expensive (file I/O per subagent) but accurate.
2. **Duration heuristic** — Very short subagent runs (< 5s) with no tool calls likely failed.
   Noisy.
3. **Event schema extension** — Add `outcome` or `tool_calls_count` to SubagentStop events in
   `dispatch.mjs`. Only works for future sessions.

**Research findings (2026-03-13):**

External research on multi-agent orchestration frameworks (LangGraph, CrewAI, OpenHands, Anthropic
Agent SDK) clarified what constitutes subagent failure and what metadata to record.

**Failure taxonomy** (from orchestration framework consensus):

| Failure mode | Detection signal | Severity |
|---|---|---|
| Crash/error | Non-zero exit, exception in transcript | Hard failure |
| Timeout | Duration exceeds threshold | Hard failure |
| Empty output | Result text empty or trivially short | Likely failure |
| Refusal | `stop_reason === "refusal"` | Hard failure |
| No meaningful work | Zero tool calls in transcript | Soft failure |
| Wrong work | Edits outside requested scope | Quality issue |

**Recommended approach: transcript-based inference (option 1).** Parse the subagent transcript
JSONL at `transcript_path` after completion. Heuristic with ~85-90% accuracy:
- Zero tool calls → failure
- Final message contains error indicators ("unable to", "couldn't find") with zero edits → failure
- File edits present → success
- Tool calls but no edits → partial (may be intentional for search tasks)

**Recommended metadata to add to SubagentStop event:**
- `outcome`: "success" | "failure" | "partial" | "timeout"
- `duration_ms`: wall-clock duration
- `tool_call_count`: total tool invocations
- `file_edit_count`: files modified (proxy for "did real work")
- `result_length`: chars in final response

**Delegation overhead measurement** (from multi-agent optimization research): coordination
overhead adds 50-200ms per delegation. Three-agent chains can 3x both cost and latency vs.
single-agent. Breakeven: delegation wins when subtask would consume >20% of parent's context.

**Decision:** Implement transcript-based inference for retroactive analysis of all existing
sessions. Add outcome metadata to `SubagentStop` events in `dispatch.mjs` for future sessions.

**Confidence upgrade: MEDIUM → MEDIUM-HIGH.** Transcript parsing is feasible now with existing
data. Schema extension for future sessions is straightforward.

**File:** `scripts/agent-analytics/score-session.mjs`

### Phase 3: Better anomaly detection

#### 3a. MAD-based anomaly detection — HIGH confidence

Replace threshold-based THRASHING/WASTEFUL flags with MAD (Median Absolute Deviation) based
anomaly detection. Modified Z-score: `Z = 0.6745 * (x - median) / MAD`. Flag sessions with
|Z| > 3 on any dimension.

**Advantages over current thresholds:**
- Adapts to the data distribution (no arbitrary cutoffs)
- Robust to outliers (50% breakdown point vs. 0% for mean/stddev)
- Produces calibrated anomaly scores, not binary flags

**File:** `scripts/agent-analytics/score-session.mjs`

#### 3b. Multivariate anomaly detection — DEFERRED

Use Isolation Forest across all signals simultaneously. Captures sessions unusual in their
*combination* of signals, not just extreme on one dimension.

**Status:** Deferred. No JS Isolation Forest implementation exists in the project. Adding an npm
dependency or Python dependency violates the zero-dependency pipeline design. A from-scratch
implementation (~200 lines) is feasible but needs testing and validation effort disproportionate
to the benefit over MAD-based detection.

MAD-based detection in 3a is sufficient for v1. Revisit if MAD produces too many false positives
or misses multivariate anomalies that are individually normal on each dimension.

### Phase 4: Stratified correlation recomputation

#### 4a. Stratify by task difficulty proxy — HIGH confidence

Recompute all signal correlations within difficulty strata:
- Files modified in final diff
- Modules touched
- Session duration (proxy for task complexity)
- Tempdoc item count (if identifiable)

**File:** `scripts/agent-analytics/correlate-signals.mjs`

This tests whether the global r < 0.11 hides within-stratum signals (Simpson's paradox
hypothesis). Tempdoc 277 already found per-type correlations up to r=-0.57, so further
stratification by difficulty within type may reveal more.

#### 4b. Add cost and time as outcome variables — HIGH confidence

Currently `correlate-signals.mjs` only correlates against task completion (binary). Add:
- Cost (USD) as continuous outcome
- Duration (hours) as continuous outcome
- Failed build count as continuous outcome

Continuous outcomes have more statistical power than binary ones for the same N.

### Phase 5: Reporting and dashboard updates

#### 5a. Update `generate-dashboard.mjs`

Add panels for:
- Failed build rate distribution
- Edit oscillation trend
- Downstream rework heatmap
- MAD-based anomaly scores (replacing threshold flags)

#### 5b. Update `analyze-trends.mjs`

Add to trend report:
- Aggregate failed build rate over time
- Cross-session rework rate
- Anomaly detection summary (MAD scores)

### Phase 6: Documentation

#### 6a. Update canonical analytics pipeline doc

**File:** `docs/explanation/21-agent-analytics-pipeline.md`

Document new signals, their rationale, and the research basis for each change.

#### 6b. Update workflow quality validation how-to

**File:** `docs/how-to/validate-workflow-quality.md`

Add guidance on interpreting new signals and anomaly scores.

## Confidence summary (post-research, 2026-03-13)

| Item | Confidence | Status | Approach after research |
|------|-----------|--------|------------------------|
| 1a. Failed build pct | HIGH | **DONE** | `failed_build_pct` signal added to `score-session.mjs` |
| 1b. Edit oscillation | MEDIUM | Ready (v1) | Size-symmetry heuristic works now; content hash as v2 |
| 1c. Downstream rework | MEDIUM | Ready (v1) | File-level with exclusion list; line-range as v2 |
| 2a. Relative re-edit rate | HIGH | **DONE** | `reedit_per_edit` signal added to `score-session.mjs` |
| 2b. Subagent success rate | MEDIUM-HIGH | **DONE** | Transcript-based inference in `analyze-session.mjs` + `score-session.mjs` |
| 3a. MAD-based anomaly | HIGH | **DONE** | `detectAnomalies()` replaced with MAD-based modified Z-score |
| 3b. Isolation Forest | LOW | DEFERRED | No JS implementation; disproportionate effort |
| 4a. Stratified correlation | HIGH | **DONE** | Per-type stratification in `correlate-signals.mjs` |
| 4b. Cost/time outcomes | HIGH | **DONE** | Cost and duration correlation tables in `correlate-signals.mjs` |
| 5a-5b. Dashboard/trends | HIGH | **DONE** | Dashboard heatmap + trends `build_failure_rate` detector |
| 6a-6b. Docs | HIGH | **DONE** | Updated `docs/explanation/21-agent-analytics-pipeline.md` |

**Research questions — ALL RESOLVED (2026-03-13):**

1. ~~How do code churn studies detect oscillation/revert patterns without full content?~~
   Resolved: size-symmetry heuristic (works with current schema) + content hash fingerprinting
   (schema extension). Pluralsight Flow, CodeScene, and Wikipedia vandalism detection all use
   similar approaches.

2. ~~How do code review tools distinguish rework from independent work on the same file?~~
   Resolved: line-range overlap detection (MergeBetter), function-name attribution, context-hash
   matching (Pluralsight Flow), co-change history (CodeScene). Start with file-level + exclusion
   list, upgrade to line-range if needed.

3. ~~How do multi-agent orchestration frameworks measure subagent success rates?~~
   Resolved: transcript-based inference achieves ~85-90% accuracy using tool call count, edit
   count, and error pattern detection. OpenHands, LangGraph, and Anthropic Agent SDK all define
   explicit terminal states. Recommended metadata: outcome, duration_ms, tool_call_count,
   file_edit_count, result_length.

## Anti-goals

- **Do not make these metrics optimization targets.** Per Goodhart's Law research, if agents are
  optimized to minimize re-edits, they will make larger riskier edits. Use for diagnostics only.
- **Do not add a database.** All analysis remains file-based (NDJSON + JSON). No SQLite, no
  external services.
- **Do not break existing pipeline compatibility.** New signals are additive. Existing consumers
  of `score-session.mjs` output must continue to work.
- **Do not remove existing signals.** Even weak signals (`bash_fileop_pct`, `subagent_density`)
  remain available for analysis. Only their weights and flag thresholds change.

## Verification

Per-phase verification:

- Phase 1: Run `node scripts/agent-analytics/test-pipeline.mjs` — existing tests pass + new
  signal extraction tests
- Phase 2: Recompute `correlate-signals.mjs` — relative metrics should show |r| > absolute
- Phase 3: Compare MAD-flagged sessions against threshold-flagged sessions — document overlap
  and divergence
- Phase 4: Stratified correlations show within-stratum |r| > global |r| (Simpson's paradox
  confirmation)
- Phase 5: Dashboard renders without errors, new panels populated
- Phase 6: Canonical docs updated, no drift from implementation

## References

- Nagappan & Ball, ICSE 2005 — relative churn measures
  (<https://www.microsoft.com/en-us/research/publication/use-of-relative-code-churn-measures-to-predict-system-defect-density/>)
- Rousseeuw & Hubert, 2017 — robust anomaly detection
  (<https://arxiv.org/pdf/1707.09752>)
- ACM TOSEM — instrumental variables for SE
  (<https://dl.acm.org/doi/10.1145/3674730>)
- METR developer productivity RCT
  (<https://metr.org/blog/2025-07-10-early-2025-ai-experienced-os-dev-study/>)
- METR transcript analysis
  (<https://metr.org/notes/2026-02-17-exploratory-transcript-analysis-for-estimating-time-savings-from-coding-agents/>)
- Google QUANTS / engineering productivity
  (<https://getdx.com/blog/how-google-measures-developer-productivity/>)
- DX Core 4
  (<https://getdx.com/research/measuring-developer-productivity-with-the-dx-core-4/>)
- SPACE framework
  (<https://getdx.com/blog/space-metrics/>)
- DX — measuring AI code assistants
  (<https://getdx.com/research/measuring-ai-code-assistants-and-agents/>)
- Anthropic — demystifying evals for AI agents
  (<https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents/>)
- SWE-bench Verified
  (<https://openai.com/index/introducing-swe-bench-verified/>)
- Goodhart's Law in SE
  (<https://jellyfish.co/blog/goodharts-law-in-software-engineering-and-how-to-avoid-gaming-your-metrics/>)
- Static vs. dynamic threshold alerting
  (<https://last9.io/blog/static-threshold-vs-dynamic-threshold-alerting/>)
- GitLab — measuring AI effectiveness
  (<https://about.gitlab.com/blog/measuring-ai-effectiveness-beyond-developer-productivity-metrics/>)

Oscillation and churn detection:
- Pluralsight Flow — code churn and rework definition
  (<https://www.pluralsight.com/resources/blog/software-development/code-churn>)
- CodeScene — code churn as rolling averages
  (<https://codescene.io/docs/guides/technical/code-churn.html>)
- Cumulative code churn impact on maintainability
  (<https://www.researchgate.net/publication/308734081_Cumulative_code_churn_Impact_on_maintainability>)

Rework detection and change coupling:
- Semantic coupling and co-change prediction (Springer)
  (<https://link.springer.com/article/10.1007/s10664-017-9569-2>)
- MergeBetter — proactive conflict detection via line-level overlap
  (<https://mergebetter.com/>)
- Merge conflict prediction using lightweight git features
  (<https://arxiv.org/pdf/1907.06274>)

Multi-agent subtask measurement:
- Galileo — defining success in multi-agent AI systems
  (<https://galileo.ai/blog/success-multi-agent-ai>)
- OpenHands SDK — AgentFinishAction/AgentRejectAction pattern
  (<https://arxiv.org/html/2511.03690v1>)
- Task-aware delegation cues for LLM agents
  (<https://arxiv.org/html/2603.11011v1>)
- Optimizing latency and cost in multi-agent systems
  (<https://www.hockeystack.com/applied-ai/optimizing-latency-and-cost-in-multi-agent-systems>)
- Anthropic — building agents with Claude Agent SDK
  (<https://www.anthropic.com/engineering/building-agents-with-the-claude-agent-sdk>)
- Claude Code subagent docs
  (<https://code.claude.com/docs/en/sub-agents>)

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Research/reference doc on multi-agent latency/cost optimization (arXiv links + Claude Code subagent docs). Research-phase artifact; lessons absorbed into agent-lessons.md and slice-execution.md.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

