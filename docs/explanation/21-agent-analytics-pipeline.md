---
title: Agent Analytics Pipeline
type: explanation
status: in-progress
description: "Behavioral tracking pipeline: hooks, event capture, session analysis, cost estimation, and LLM-as-judge evaluation. The HTML dashboard and process-hygiene scoring layers are retired."
---

# Agent Analytics Pipeline

The agent analytics pipeline tracks behavioral patterns — how agents use tools, which files they re-read, when they make rapid re-edits, and how often they misuse Bash for file operations. It also estimates per-session costs from transcript token data and optionally evaluates task outcomes via LLM-as-judge.

All scripts live under `scripts/agent-analytics/`. All data lives under `tmp/agent-telemetry/` (gitignored). The composite process-hygiene score that once sat on top of the session reports is retired — what it measured, and why it cannot be re-measured, is recorded below under [Retired: process-hygiene scoring](#retired-process-hygiene-scoring).

> **Liveness is per-layer, not per-pipeline.** This doc describes several layers with different
> health. The **hook layer** (`hooks/`, `lib/`) is wired in `.claude/settings.json` and fires on
> every session — verify with `governance/agent-hooks.v1.json` and the `hook-integrity` gate. The
> **analysis scripts** (`analyze-session`, `cost-session`, `baseline-economics`,
> `cache-efficiency`, …) are maintainer CLI tools run on demand; nothing invokes them on a
> schedule, so an empty or stale store under `tmp/agent-telemetry/` is expected, not a fault. The
> **HTML dashboard layer** (`generate-dashboard.mjs`, `dashboard.html`) and the **process-hygiene
> scoring layer** (`score-session.mjs`, `correlate-signals.mjs`, `scores.ndjson`,
> `scripts/ci/check-agent-quality-trend.mjs`) are **retired** — deleted, not deprecated. Do not
> infer that a layer is live because this doc documents it.
>
> **Removed (tempdoc 638):** the run-centric workflow-telemetry layer and its session-to-workflow attribution bridge (`scripts/lib/workflow-telemetry.mjs`, `scripts/bench/report-workflow-attribution.mjs`, the `tmp/workflow-telemetry/runs/` artifacts, and the workflow-telemetry contract) were deleted. The session-centric agent analytics pipeline described below is unaffected by that removal.

The `hooks/export-session-env.mjs` `SessionStart` hook still writes `JUSTSEARCH_AGENT_SESSION_ID` into `CLAUDE_ENV_FILE` so that session attribution is available to downstream tooling.

## Architecture

```text
Claude Code hooks ──> NDJSON event stream ──> Session reports ──> Trend reports
                      (append-only)           (per session)       (on demand)
                                                    └──> Outcome evaluations (LLM-as-judge, optional)
```

### Storage

```text
tmp/agent-telemetry/
  events.ndjson                       # Append-only hook event stream
  costs.ndjson                        # Per-session cost estimates from transcript tokens
  outcomes.ndjson                     # Opt-in REPORT of the outcome JOIN (outcome-session.mjs --write) — not maintained state
  judge-outcomes.ndjson               # LLM-as-judge outcome evaluations (evaluate-session.mjs, optional)
  session-index.json                  # Aggregated session index (costs + reports)
  read-counts-{sessionId}.json        # Per-session read count cache (for compact-save.mjs)
  edit-counts-{sessionId}.json        # Per-session edit count cache (for compact-save.mjs)
  turn-count-{sessionId}.txt          # Per-session tool-call counter (dispatch.mjs, cleaned on session end)
  sessions/
    {sessionId}.json                  # Per-session analysis report
  reports/
    trend-{date}.json                 # Cross-session trend analysis
    trend-{date}.md                   # Human-readable version
    comparison-{date}.json            # Before/after comparison (--cutoff mode)
    comparison-{date}.md              # Human-readable version
```

## Event Capture

Every NDJSON line:

```json
{
  "schema_version": 1,
  "ts": "2026-02-03T12:00:00.000Z",
  "event": "pre_tool_use",
  "session_id": "...",
  "tool_name": "Read",
  "tool_use_id": "toolu_...",
  "tool_call_number": 42,
  "input_summary": {
    "file_path": "modules/ui-web/src/components/BrainView.tsx",
    "has_offset": false,
    "has_limit": false
  }
}
```

`tool_call_number` is a monotonically increasing per-session counter, tracked via file-based counters in `tmp/agent-telemetry/turn-count-{sessionId}.txt`. Counter files are cleaned up on `session_end`.

Content is never stored. Tool inputs are summarized to analytics-relevant fields only:

| Tool | Extract | Strip |
|------|---------|-------|
| Read | file_path, has_offset, has_limit | — |
| Edit | file_path, old_string length, new_string length | content |
| Write | file_path, content length | content |
| Bash | command first 200 chars | full output |
| Grep/Glob | pattern, path, output_mode | — |
| Task | subagent_type, prompt length | full prompt |
| mcp__* | tool name only | all inputs |

## Session Report Schema (v1)

```json
{
  "schema": "agent-session-report.v1",
  "session_id": "...",
  "started_at": "...",
  "ended_at": "...",
  "duration_seconds": 1234,
  "model": "...",
  "tool_calls": { "total": 150, "by_type": {}, "failure_count": 8 },
  "file_reads": { "total": 40, "unique_files": 15, "unbounded_count": 22, "by_file": [] },
  "file_edits": { "total": 25, "by_file": [{"file": "...", "count": 9, "timestamps": []}] },
  "compactions": { "count": 2, "triggers": ["auto", "auto"] },
  "subagents": { "count": 3, "by_type": {"Explore": 2, "Plan": 1} },
  "subagent_tool_calls": {
    "total": 440, "by_type": {},
    "transcripts_found": 71, "transcripts_missing": 69,
    "transcript_coverage": 0.507,
    "file_reads": { "total": 250, "unbounded_count": 180, "by_file": [] }
  },
  "data_completeness": { "available": true, "hook_tool_calls": 1353, "transcript_in_window": 938, "capture_rate": 1.443 },
  "bash_commands": { "total": 30, "file_op_count": 2, "failed_build_count": 1 },
  "compaction_rereads": { "total_rereads": 5, "by_compaction": [] },
  "failure_cascades": { "count": 1, "cascades": [] },
  "context_efficiency": { "first_reads": 15, "rereads_changed": 3, "rereads_unchanged": 8, "proximity": {}, "score_informational": 0.72 },
  "read_redundancy": { "total_rereads": 10, "structural": 6, "wasteful": 4, "by_reason": {} }
}
```

`subagent_tool_calls` is populated by parsing SubagentStop `agent_transcript_path` JSONL files (~50% coverage due to transcript cleanup). `data_completeness` compares hook event count against time-windowed transcript tool calls. The last 4 fields (`compaction_rereads`, `failure_cascades`, `context_efficiency`, `read_redundancy`) are informational enrichments.

## Retired: process-hygiene scoring

A composite 0–100 **Process Hygiene Index** (PHI) once sat on top of the session reports:
`score-session.mjs` normalised ten behavioural signals into a weighted score, applied two boolean
classification rules (WASTEFUL, THRASHING) with per-task-type suppressions, and flagged MAD-based
outliers; `correlate-signals.mjs` correlated those signals against judged outcomes;
`scripts/ci/check-agent-quality-trend.mjs` watched a trend baseline over `scores.ndjson`. All of it
was deleted in tempdoc 858 §7, along with `scores.ndjson`, the `score` / `flags` / `anomalies_count`
fields of `session-index.json`, and the score `evaluate-session.mjs` loaded and handed to the
judge — which that script had deliberately kept out of the judge's prompt anyway, to avoid
anchoring it, so removing it changes no verdict. The judge, the session reports and every report
built on them are unaffected.

**The grounds are that it could not be scored, not that it scored badly.** Each of these is
individually sufficient:

- **Nothing consumed it.** The HTML dashboard was deleted the day before (tempdoc 858 D1), and
  `check-agent-quality-trend.mjs` was wired to no workflow.
- **Its own calibration was inert.** Both per-type suppressions read a field that moved in the
  tempdoc 622 refactor, so neither fired for months; the path was fixed 2026-08-19 and the metric
  still ran uncalibrated because `task_type` originates in the LLM-judge cache, which had scored
  almost nothing.
- **Re-measuring is not possible at this corpus size.** A Pearson correlation at this
  instrument's own `|r| > 0.30` threshold needs 44 joined pairs before an `r` is distinguishable
  from zero at α = 0.05. The events store held 10 distinct sessions and the judge cache 2.

### The finding, and why the numbers no longer describe anything runnable

Recorded here because the machinery is gone and the measurement is the part worth keeping
(tempdoc 844 §4.3). Source: **tempdoc 277 §C4** — not tempdoc 118, which several retired files
cited and which is unresolvable for a reader of this repo.

| Result | Value |
|---|---|
| Composite score vs. task completion | r = 0.064, Cohen's d = 0.13, N = 116 (73 complete / 38 partial) |
| No individual signal, globally | \|r\| > 0.20 |
| `bash_fileop_pct` on **feature** sessions | r = +0.51, d = +1.11 — positively associated with completion |
| THRASHING rule on **implementation** sessions | fired on 33% of completed, 0% of partial — inverted |

So the composite was non-predictive while two components carried signal in the opposite direction
to the rule that used them, which is why 277's response was per-type suppression rather than
abandonment.

**Caveat, and it is the load-bearing part: these numbers describe a metric that no longer exists in
that form.** Between the measurement and the retirement the signal set went from 7 to 10, weights
and normalisation ceilings were re-derived, per-type hierarchical pooling was added, anomaly
detection moved from IQR to MAD, and `bash_fileop_pct` — the r = +0.51 headline — was **redefined
with no record in any tempdoc**. A fresh measurement would not re-test 277; it would measure a
different metric that happens to share the names.

**If you are about to propose a process-hygiene score, this is what to take from it.** The
composite is the part that failed: aggregating signals into one number destroyed the per-type
structure that carried the actual effect. The signal-level results survive as hypotheses worth
re-testing, not as findings to build on — re-derive them against your own definitions, and
declare a sufficiency floor from what you intend to conclude before you collect anything.

## Context Attribution

`context-attribution.mjs` classifies every content block in Claude Code transcript JSONL files by category: tool outputs (broken down by tool name), assistant text, thinking, user messages, and system messages. Uses chars/4 as a token estimate — sufficient for proportional analysis without a tokenizer.

Key findings across real sessions (N=41): tool outputs consume 80–85% of context (median), with `Read` alone at ~51%. Directly actionable for tuning `intervene.mjs` read limits.

## Implementation Components

| File | Purpose |
|------|---------|
| `hooks/dispatch.mjs` | Async entry point for all hook events. Reads stdin JSON, validates session_id, branches on event type, appends one NDJSON line. |
| `hooks/export-session-env.mjs` | Sync SessionStart hook. Writes `JUSTSEARCH_AGENT_SESSION_ID` into `CLAUDE_ENV_FILE` for later Bash commands in the same Claude session. |
| `hooks/intervene.mjs` | Sync PreToolUse hook (matcher: Read, Edit). Auto-injects `limit: 200` for files >8KB. Tracks per-session read/edit counts for compact-save orientation data. |
| `hooks/bash-guard.mjs` | Sync PreToolUse hook (matcher: Bash). Decision logic in exported `evaluateBashCommand`. Blocks destructive git (force-push everywhere; checkout/switch/reset/clean/restore in main, except single-file `git checkout -- <path>`), long `sleep`, and redirects *bare* (flagless, unchained) `cat`/`grep`/`head`/`tail`/`rg` to Read/Grep (flagged forms + pipelines allowed). |
| `hooks/repeat-guard.mjs` | Sync PreToolUse hook (matcher: all). Blocks 3+ consecutive identical tool calls. Per-tool fingerprinting with MCP/internal tool support. Excludes build commands (deferred to build-counter). Buffer written atomically. |
| `hooks/build-counter.mjs` | Sync hook (matcher: Bash). Counts consecutive build failures on **PostToolUse** (synchronous, replacing the former async dispatch write) and blocks build commands on **PreToolUse** after 3+ failures. One-shot advisory pattern. |
| `hooks/subagent-guide.mjs` | Sync SubagentStart hook. Injects codebase context (large files list, docs index path) into subagent prompts. |
| `hooks/compact-save.mjs` | Sync PreCompact hook. Produces orientation data from read/edit caches that survives compaction. |
| `hooks/compact-restore.mjs` | Sync hook on **SessionStart** (restores orientation state as a session-stamped `.claude/rules/compaction-state.md`) **and SessionEnd** (deletes that file so it never bleeds into the next session's pre-hook rules load — tempdoc 520 P0d). |
| `lib/hook-base.mjs` | Shared hook plumbing (tempdoc 520 P1a): `readStdin`/`readJsonStdin`, `repoRoot`/`telemetryDir`, `atomicWriteFileSync`, `isDirectRun`, the `runHook` entrypoint, and the `JUSTSEARCH_DISABLE_HOOKS` kill switch (`hooksDisabled`). |
| `lib/event-writer.mjs` | Synchronous NDJSON append. Rotates `events.ndjson` at 10 MB (one `.prev` generation). |
| `lib/input-summarizer.mjs` | Extracts analytics fields from tool inputs. Strips content per the capture table above. |
| `lib/telemetry-io.mjs` | Shared I/O utilities: `loadEvents`, `groupBySession`, `loadNdjsonArray`, `loadNdjsonMap`, `loadSessionReports`, `round`. |
| `analyze-session.mjs` | Aggregates events into session reports. Enrichments: compaction rereads, failure cascades, context efficiency, read redundancy. CLI: `--list`, `--session-id`, `--all`. |
| `analyze-trends.mjs` | Cross-session trend analysis with 6 detectors. `--cutoff` for before/after comparison. |
| `cost-session.mjs` | Per-session cost estimation from transcript JSONL. Per-turn pricing by actual model. CLI: `--session-id`, `--all`, `--json`. |
| `cache-efficiency.mjs` | Prompt-cache **efficiency** across the corpus, as opposed to the cost totals `cost-session`/`baseline-economics` produce. Answers *why* a cache write was paid: classifies each into extension / invalidation / cold start, attributes invalidations (compaction, model switch, TTL expiry, or an honest `in-ttl-undetermined` residual), reports the TTL tier by agent kind, delegation economics, and pricing coverage. Exports its classifiers (`classifyWrite`, `invalidationCause`) for test. CLI: `--since <ISO>`, `--json`. |
| `context-attribution.mjs` | Context window attribution: classifies transcript content blocks by category (tool outputs by tool name, assistant text, thinking, user messages, system). Chars/4 ≈ tokens. CLI: `--session-id`, `--all`, `--json`, `--top N`. |
| `generate-index.mjs` | Aggregates session reports + costs into `session-index.json`. |
| `evaluate-session.mjs` | LLM-as-judge outcome evaluation, written to `judge-outcomes.ndjson`. Condenses transcripts, sends to `claude` CLI. CLI: `--session-id`, `--all`, `--force`, `--dry-run`, `--model`, `--json`. |
| `outcome-session.mjs` | Per-session outcome JOIN over canonical owners (git merge link, build counter, tempdoc frontmatter, governance SARIF), **computed on demand and printed**. The judge's verdict is carried as a residual `inference` block and never overwrites a fact. `--write` emits `outcomes.ndjson` as a timestamped report, not an authority. |
| `test-pipeline.mjs` | Legacy standalone assertion script. **Not wired to anything** — `run-all-tests.mjs` discovers `*.test.mjs` only, so this file runs solely when invoked by hand, and it has known stale failures (observations store retired — tempdoc 872; see git history of `docs/observations.md`). Treat it as historical coverage, not as a gate. |

### Hook Configuration

In `.claude/settings.local.json`:
- Hook entries across multiple event types
- `export-session-env.mjs` runs first on `SessionStart` so later Bash commands inherit `JUSTSEARCH_AGENT_SESSION_ID`
- Analytics hooks (`dispatch.mjs`) use `"async": true` — never block the agent
- Intervention hooks (`intervene.mjs`, `bash-guard.mjs`, `repeat-guard.mjs`, `build-counter.mjs`) are synchronous with matchers — only fire for matched tool calls
- `build-counter.mjs` is also wired synchronously on `PostToolUse` (Bash, gradlew) to record pass/fail — so the next `PreToolUse` check reads a fresh count (tempdoc 520 P0f closed the prior async-write/sync-read race)
- `compact-restore.mjs` is wired on both `SessionStart` and `SessionEnd`
- 5s timeout for hot-path hooks; 30s for SessionEnd
- **Kill switch:** `JUSTSEARCH_DISABLE_HOOKS=1` disables all session-affecting hooks via `hook-base.runHook` / `hooksDisabled` (tempdoc 520 P1c)

### Hook Interaction

Hooks fire in registration order. For Bash tool calls, the chain is:
dispatch.mjs (async) → bash-guard.mjs → build-counter.mjs → repeat-guard.mjs.
If a sync hook exits 2 (block), subsequent hooks likely do not fire (short-circuit).

For `SessionStart`, the chain is:
export-session-env.mjs → dispatch.mjs (async) → compact-restore.mjs.

Subagent attribution in phase 1 is parent-owned. `subagent-guide.mjs` includes the parent
`session_id` and instructs subagents to pass `--session-id <parent-session-id>` when invoking
maintained workflow wrappers or DAG runners.

Known interaction design decisions:
- **repeat-guard excludes build commands** (`/gradlew/i`). Without this, repeat-guard
  blocks the 3rd consecutive build before build-counter reaches its failure threshold.
  Build-counter has purpose-built one-shot advisory logic; repeat-guard defers to it.
- **build-counter reads state written by dispatch.mjs** (async PostToolUse). The async
  write may not complete before the next PreToolUse. At worst, the advisory fires one
  call late.
- **Parallel tool calls** produce race conditions on state files (last writer wins).
  Practical impact is low — parallel calls are typically different tools.

### Process Overhead

Each PreToolUse spawns Node.js processes: 1 async (dispatch) + 1-3 sync depending on
tool type. At ~30-80ms per Node startup on Windows, Bash calls incur ~120-320ms of hook
overhead. This is small relative to LLM inference time (seconds per turn).

### Error Isolation

`dispatch.mjs` wraps each event handler in try/catch. A crash in one tool type's summarizer must not prevent other events from recording. Errors are logged to `tmp/agent-telemetry/errors.log`, not stderr.

## Design Constraints

- **No external services.** All data local, file-based, under `tmp/`.
- **No new dependencies.** Uses Node.js stdlib only.
- **Async hooks for analytics, sync only for intervention.**
- **Transcript parsing is best-effort.** ~50% of subagent transcripts are cleaned up by Claude Code. `transcript_coverage` quantifies the gap.
- **Manual fallback for analysis.** `SessionEnd` may not fire on crashes — analyzers work as CLI tools.

## Risks

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Hook stops firing silently ([#6305](https://github.com/anthropics/claude-code/issues/6305)) | Medium | `data_completeness` detects missing events |
| Transcript cleanup eliminates subagent data (~50%) | Medium | `transcript_coverage` quantifies; accepted |
| SessionStart hooks break stdin on Windows ([#23083](https://github.com/anthropics/claude-code/issues/23083)) | Medium | Monitor |
| 100% CPU hang with parallel instances + hooks ([#22172](https://github.com/anthropics/claude-code/issues/22172)) | Medium | Avoid parallel Claude Code instances |
| `intervene.mjs` Node startup adds latency (30-80ms per Read) | Low | Acceptable; scoped via matcher |
| Event rotation drops history a report was built from | Medium | Skip-if-degraded guard in `writeReport()`; rotation limit 10 MB |
| Memory pressure from large session analysis | Low | Fine at 50+ sessions; may need streaming at 100+ |

## Known Limitations

- **Self-monitoring paradox.** The agent exhibiting waste is also the one reading pipeline output. Mitigated by `.claude/rules/` (loaded at session start) rather than requiring mid-session analytics reads.
- **intervene.mjs effectiveness is untestable.** Analytics capture pre-intervention state. We know the hook fires but can't directly measure context savings.
- **There is no composite quality number for a session.** The process-hygiene score that used to supply one is retired; the reasoning and its measurement are in [Retired: process-hygiene scoring](#retired-process-hygiene-scoring). Do not reintroduce one without reading that section first.

## Test Suite

The wired entry point is `node scripts/agent-analytics/run-all-tests.mjs`, which discovers and runs
every `*.test.mjs` under `scripts/agent-analytics/` and exits non-zero if any file fails. That is
the suite to run and the one CI runs.

`test-pipeline.mjs` is a separate legacy script that **`run-all-tests.mjs` does not discover** (it
is not a `*.test.mjs` file), so nothing invokes it and it has accumulated stale failures — formerly
tracked as the `obs:test-pipeline` condition in the observations store (retired, tempdoc 872; see
git history of `docs/observations.md`). Its group table below is retained as a
map of what that historical coverage aimed at; do not read it as a passing suite, and do not cite
an assertion count from it. The group numbering has deliberate gaps where a group was deleted with
its subject: 19 (dashboard generation) with the dashboard, and 2 (scoring logic), 17 (Z-score
anomaly detection) and 21 (golden score dataset) with process-hygiene scoring.

| Group | Key Assertions |
|-------|---------------|
| Hook output (1) | Large-file `limit:200` injection, skip when limit present, edit tracking, hot-file read cap |
| Trend analysis (3) | Path sanitization, subagent merging, tempdoc exclusion |
| Tests 4-11 | File size limiting, edit tracking, bash blocking, compact save/restore, subagent guidance |
| Compaction rereads (12) | Boundary detection, chained segments |
| Failure cascades (13) | Sliding window, interrupts excluded |
| Context efficiency (14) | First-read scoring, edit-proximity weighting |
| Read redundancy (15) | Structural vs wasteful classification |
| Cost estimation (16) | Per-turn pricing, missing transcript handling |
| Session index (18) | Schema validation |
| LLM-as-judge (20) | Dry-run validation, condensation, upsert dedup |
| Repeat guard (22) | Consecutive blocking, break-and-resume, multi-tool fingerprinting, build exclusion, MCP/internal tools |
| Build counter (23) | Threshold blocking, one-shot advisory, dispatch state tracking, SessionEnd cleanup |
| Hook chain (24) | repeat-guard + build-counter interaction, build deferral, non-build blocking |
