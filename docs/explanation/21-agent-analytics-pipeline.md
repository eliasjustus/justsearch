---
title: Agent Analytics Pipeline
type: explanation
status: stable
description: "Behavioral tracking pipeline: hooks, event capture, session analysis, scoring, and LLM-as-judge evaluation."
---

# Agent Analytics Pipeline

The agent analytics pipeline tracks behavioral patterns — how agents use tools, which files they re-read, when they make rapid re-edits, and how often they misuse Bash for file operations. It also estimates per-session costs from transcript token data and optionally evaluates task outcomes via LLM-as-judge.

All scripts live under `scripts/agent-analytics/`. All data lives under `tmp/agent-telemetry/` (gitignored). Investigation findings and validity analysis are recorded in noncanonical tempdoc 118.

> **Removed (tempdoc 638):** the run-centric workflow-telemetry layer and its session-to-workflow attribution bridge (`scripts/lib/workflow-telemetry.mjs`, `scripts/bench/report-workflow-attribution.mjs`, the `tmp/workflow-telemetry/runs/` artifacts, and the workflow-telemetry contract) were deleted. The session-centric agent analytics pipeline described below is unaffected and remains live.

The `hooks/export-session-env.mjs` `SessionStart` hook still writes `JUSTSEARCH_AGENT_SESSION_ID` into `CLAUDE_ENV_FILE` so that session attribution is available to downstream tooling.

## Architecture

```text
Claude Code hooks ──> NDJSON event stream ──> Session reports ──> Trend reports
                      (append-only)           (per session)       (on demand)
                                                    ├──> Session scores (wide events)
                                                    └──> Outcome evaluations (LLM-as-judge, optional)
```

### Storage

```text
tmp/agent-telemetry/
  events.ndjson                       # Append-only hook event stream
  scores.ndjson                       # Wide events — one line per scored session
  costs.ndjson                        # Per-session cost estimates from transcript tokens
  outcomes.ndjson                     # LLM-as-judge outcome evaluations (optional)
  session-index.json                  # Aggregated session index (scores + costs + reports)
  dashboard.html                      # Self-contained HTML dashboard with Chart.js
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

`subagent_tool_calls` is populated by parsing SubagentStop `agent_transcript_path` JSONL files (~50% coverage due to transcript cleanup). `data_completeness` compares hook event count against time-windowed transcript tool calls. The last 4 fields (`compaction_rereads`, `failure_cascades`, `context_efficiency`, `read_redundancy`) are informational enrichments, not used in scoring.

## Scoring Model

Extracts signals from each session report. Score = `round(100 * (1 - weighted_sum_of_normalized_signals))`, clamped to [0, 100].

| Signal | Ceiling | Weight | Description |
|--------|---------|--------|-------------|
| `unbounded_read_pct` | 0.30 | 0.16 | Fraction of Read calls on large files (>12KB) without offset/limit |
| `rapid_reedit_count` | 40 | 0.16 | Rapid re-edit clusters on code files (>=3 edits/file/120s, tempdocs excluded) |
| `bash_fileop_pct` | 0.6 | 0.12 | Fraction of Bash commands that are file operations (first pipeline segment) |
| `tool_failure_rate` | 0.10 | 0.12 | Failed tool calls / total tool calls |
| `subagent_density` | 50 | 0.08 | Subagents per hour |
| `subagent_failure_rate` | 0.50 | 0.08 | Fraction of subagents with zero tool calls (transcript-inferred) |
| `build_cycle_rate` | 0.25 | 0.07 | Failed builds per code edit |
| `hot_file_concentration` | 0.8 | 0.07 | Top-3 files' share of total reads |
| `failed_build_pct` | 0.30 | 0.07 | Failed builds / total builds (build success rate) |
| `reedit_per_edit` | 0.35 | 0.07 | Rapid re-edit clusters / total code edits (normalized re-edit intensity) |

Weights rebalanced for 10 signals (Mar 2026, tempdoc 285). `tool_failure_rate` weight increased (most consistent predictor, r=-0.19 to -0.43 across task types). `subagent_density` reduced (no outcome signal, all |r|<0.12). Three new signals added: `failed_build_pct` (build success rate independent of edit count), `reedit_per_edit` (normalized rapid re-edit rate — per Nagappan & Ball 2005, relative measures predict better than absolute), `subagent_failure_rate` (transcript-based subagent outcome inference). The score is best interpreted as a **process hygiene index** — it measures context waste and tool discipline, not work quality. See tempdoc 277 for outcome-aware validation (r=0.064 with completion, N=116).

### Boolean Classification Rules

| Rule | Condition |
|------|-----------|
| **WASTEFUL** | `unbounded_read_pct > 0.10` AND `bash_fileop_pct > 0.30` |
| **THRASHING** | `rapid_reedit_count > 10` AND `hot_file_concentration > 0.20` |

Both rules support per-type suppression (tempdoc 277): WASTEFUL suppressed for `feature` tasks, THRASHING suppressed for `implementation` tasks.

### Anomaly Detection

MAD-based modified Z-score replaces IQR-based detection (tempdoc 285). For each signal across all sessions, computes the median and MAD (Median Absolute Deviation). Sessions with |Z| > 3 on any signal are flagged as anomalous, where Z = 0.6745 × (value − median) / MAD. MAD has a 50% breakdown point (robust to outliers) vs. 25% for IQR.

### Context Attribution

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
| `score-session.mjs` | Per-session composite scoring. Per-type ceilings via hierarchical partial pooling in `--all` mode. MAD-based anomaly detection. CLI: `--session-id`, `--all`, `--json`, `--weights`. |
| `cost-session.mjs` | Per-session cost estimation from transcript JSONL. Per-turn pricing by actual model. CLI: `--session-id`, `--all`, `--json`. |
| `context-attribution.mjs` | Context window attribution: classifies transcript content blocks by category (tool outputs by tool name, assistant text, thinking, user messages, system). Chars/4 ≈ tokens. CLI: `--session-id`, `--all`, `--json`, `--top N`. |
| `generate-index.mjs` | Aggregates session reports + scores + costs into `session-index.json`. |
| `generate-dashboard.mjs` | Self-contained HTML dashboard with Chart.js flag trend (primary), signal heatmap, process hygiene index (demoted), sortable session table. |
| `evaluate-session.mjs` | LLM-as-judge outcome evaluation. Condenses transcripts, sends to `claude` CLI. CLI: `--session-id`, `--all`, `--force`, `--dry-run`, `--model`, `--json`. |
| `test-pipeline.mjs` | 305+ assertions across 25 groups. Synthetic data, try/finally cleanup, spawnSync crash detection. |

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
export-session-env.mjs â†’ dispatch.mjs (async) â†’ compact-restore.mjs.

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
| Event rotation causes score instability | Medium | Skip-if-degraded guard in `writeReport()`; rotation limit 10 MB |
| Memory pressure from large session analysis | Low | Fine at 50+ sessions; may need streaming at 100+ |

## Known Limitations

- **Self-monitoring paradox.** The agent exhibiting waste is also the one reading pipeline output. Mitigated by `.claude/rules/` (loaded at session start) rather than requiring mid-session analytics reads.
- **intervene.mjs effectiveness is untestable.** Analytics capture pre-intervention state. We know the hook fires but can't directly measure context savings.
- **Process score does not predict task outcomes.** LLM-as-judge evaluation confirmed a 2.8-point gap between complete and partial sessions. The score measures process compliance, not work quality. See tempdoc 118.

## Test Suite

305+ assertions across 25 groups. Run: `node scripts/agent-analytics/test-pipeline.mjs`

| Group | Key Assertions |
|-------|---------------|
| Hook output (1) | Large-file `limit:200` injection, skip when limit present, edit tracking, hot-file read cap |
| Scoring logic (2) | 10 signal calculations, 2 boolean rules, tempdoc exclusion, score clamping |
| Trend analysis (3) | Path sanitization, subagent merging, tempdoc exclusion |
| Tests 4-11 | File size limiting, edit tracking, bash blocking, compact save/restore, subagent guidance |
| Compaction rereads (12) | Boundary detection, chained segments |
| Failure cascades (13) | Sliding window, interrupts excluded |
| Context efficiency (14) | First-read scoring, edit-proximity weighting |
| Read redundancy (15) | Structural vs wasteful classification |
| Cost estimation (16) | Per-turn pricing, missing transcript handling |
| Z-score anomaly (17) | MAD-based detection, outlier identification |
| Session index (18) | Schema validation |
| Dashboard (19) | HTML generation, Chart.js, embedded data |
| LLM-as-judge (20) | Dry-run validation, condensation, upsert dedup |
| Repeat guard (22) | Consecutive blocking, break-and-resume, multi-tool fingerprinting, build exclusion, MCP/internal tools |
| Build counter (23) | Threshold blocking, one-shot advisory, dispatch state tracking, SessionEnd cleanup |
| Hook chain (24) | repeat-guard + build-counter interaction, build deferral, non-build blocking |
