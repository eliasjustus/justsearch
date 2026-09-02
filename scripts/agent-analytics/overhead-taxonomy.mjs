#!/usr/bin/env node
/**
 * Tempdoc 743 Phase 2 — first-cut overhead taxonomy, default window trailing
 * 30 days (tempdoc 886 §12 PR 2 — a bare invocation used to hardcode
 * 2026-06-18..2026-07-16 and so returned 0 sessions on any later date;
 * `--since`/`--until` still override explicitly, unchanged).
 *
 * RESCUED from session scratchpad into the repo (tempdoc 743 second wave,
 * Slice 3, P-L: "Also homes the rescued T1 overhead-taxonomy.mjs ... as
 * consumers of the shared transcript substrate"). The MEASUREMENT CONTRACT
 * is UNCHANGED from the scratchpad original that produced tempdoc 743's T1
 * numbers — every category definition, threshold, and regex below is
 * byte-faithful to that version, because comparability across the
 * pre-rescue and post-rescue numbers matters. The ONLY thing adapted is
 * discovery: the scratchpad original dynamically imported
 * `baseline-economics.mjs` from a DIFFERENT, since-torn-down worktree
 * (743-phase2) via a hardcoded absolute `file://` path — not reproducible
 * from this worktree, and out of scope to touch per this slice's brief
 * ("Do NOT touch ... baseline-economics.mjs ... any existing reader").
 * Discovery is reimplemented locally on top of the new
 * `lib/transcript-store.mjs` shared substrate (project-dir + `.jsonl` +
 * subagent-path enumeration), while PRESERVING baseline-economics.mjs's
 * exact windowing semantics — a session's FIRST TRANSCRIPT LINE timestamp,
 * not file mtime — because that's what the ~30.5B/220-session reference
 * figure was computed against. `--since`/`--until` CLI flags were added
 * (absent from the scratchpad original, which hardcoded the window) purely
 * to make the rescue smoke-testable on a short window; a bare invocation now
 * defaults to trailing 30 days from today rather than reproducing the
 * original 2026-06-18..2026-07-16 run — pass `--since 2026-06-18 --until
 * 2026-07-16` explicitly to reproduce the original T1 figures byte-for-byte.
 *
 * Categories (see task spec for exact definitions):
 *   1. WAITING       — task-notification / ScheduleWakeup-tick triggered turns
 *                      whose assistant response is a short acknowledgment.
 *   2. RE-ORIENTATION — post-compaction continuation turns (+ first 3 assistant
 *                      turns after), and "what was this chat's main goal"-style
 *                      re-orientation prompts.
 *   3. HOOK-FRICTION  — tool_result blocks containing a hook-block message,
 *                      plus the immediately following (retry) assistant turn.
 *   4. CEREMONY vs IMPLEMENTATION — for skill-using sessions, token share
 *                      before vs after the first Edit/Write/NotebookEdit call.
 *
 * Read-only on all transcript inputs. Writes only next to this script.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  discoverProjectDirs, listSubagentPaths, DEFAULT_PROJECTS_ROOT, firstTranscriptTimestamp,
} from './lib/transcript-store.mjs';
import { parseTranscriptTokens } from './lib/transcript-cost.mjs';
import {
  TELEMETRY_DIR, repoRoot,
  loadExclusionKeys, makeExclusionMatcher, fmtScopeExclusion,
} from './lib/telemetry-io.mjs';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
// tmp/agent-telemetry (gitignored, existing convention for every other
// generated report in this dir) rather than scratchpad's own-dir
// convention, which has no equivalent once this script lives in the repo.
const OUT_JSON = path.join(repoRoot, TELEMETRY_DIR, 'overhead-taxonomy.json');

// --- Config --------------------------------------------------------------

const DAY_MS = 24 * 60 * 60 * 1000;
const TRAILING_WINDOW_DAYS = 30; // tempdoc 886 §12 PR 2 — was a hardcoded 2026-06-18..2026-07-16
const EXCLUDED_PATH = path.join(SCRIPT_DIR, 'friction-excluded-sessions.json');

/** ISO date (UTC midnight) `days` before today — same "UTC midnight bound" shape the old hardcoded defaults used. */
function daysAgoIso(days) {
  return new Date(Date.now() - days * DAY_MS).toISOString().slice(0, 10);
}

function parseArgs(argv) {
  const opts = { since: daysAgoIso(TRAILING_WINDOW_DAYS), until: null, projectsRoot: DEFAULT_PROJECTS_ROOT };
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === '--since') opts.since = argv[++i];
    else if (a === '--until') opts.until = argv[++i];
    else if (a === '--projects-root') opts.projectsRoot = argv[++i];
  }
  return opts;
}

// "short acknowledgment" cutoff, in visible assistant-text characters (text
// blocks only, thinking excluded), for a notification/wakeup-triggered
// response run to count as WAITING rather than substantive work triggered by
// a notification. Crude proxy — see LIMITATIONS in the report.
const SHORT_ACK_CHAR_THRESHOLD = 400;

const HOOK_BLOCK_REGEX = /PreToolUse:.*hook|bash-guard|repeat-guard|build-counter blocked/i;

const GOAL_QUERY_PATTERNS = [
  /what was this chat.?s? main goal/i,
  /what (is|was) (the )?(main )?goal of this (chat|session|conversation)/i,
  /what (was|were) this (chat|session|conversation) (about|for)/i,
];

const IMPL_TOOLS = new Set(['Edit', 'Write', 'NotebookEdit', 'MultiEdit']);
const WAIT_TOOLS = new Set(['ScheduleWakeup', 'Monitor']);

// Claude Code harness built-in slash commands — these appear in the SAME
// <command-name>/x</command-name> wire format as project Skill invocations,
// but are not "skills" in the tempdoc-743 sense (a packaged workflow under
// .claude/skills/). Excluded from ceremony-split "skill-using session"
// classification. Not exhaustive/authoritative (no canonical enumeration was
// available at write time) — a best-effort list of known CLI built-ins; see
// LIMITATIONS in the report for the resulting imprecision (e.g. a historical
// skill later renamed/removed, like a session-observed `/passes`, is NOT in
// this exclude list and so is counted as a skill — arguably correct, since it
// WAS a packaged workflow at the time, but unverifiable after the rename).
const BUILTIN_COMMAND_EXCLUDE = new Set([
  'clear', 'compact', 'model', 'login', 'logout', 'resume', 'cost', 'help', 'bug', 'doctor',
  'permissions', 'add-dir', 'agents', 'config', 'context', 'export', 'hooks', 'ide', 'mcp',
  'memory', 'migrate-installer', 'output-style', 'pr-comments', 'privacy-settings',
  'release-notes', 'rewind', 'sandbox', 'statusline', 'terminal-setup', 'todos', 'upgrade',
  'vim', 'feedback', 'usage', 'install-github-app',
]);

// --- Discovery (ADAPTED to lib/transcript-store.mjs — see header note) ---

// `firstTranscriptTimestamp` (the definitional "session start" the original
// ~30.5B/220-session figure was computed against) is imported from
// lib/transcript-store.mjs, not a private copy (tempdoc 886 §12 PR 5b —
// this was the fourth hand-rolled copy of the same scan the PR 5a review
// flagged; confirmed byte-identical to the private copy before deleting it,
// so this is a pure de-duplication, not a behavior change).

/**
 * Session discovery: project-dir + `.jsonl` + subagent-path enumeration
 * comes from `lib/transcript-store.mjs` (discoverProjectDirs,
 * listSubagentPaths); the first-transcript-line windowing and exclusion
 * matching stay local, ported from baseline-economics.mjs, to preserve
 * exact reproducibility of the original figure.
 */
async function discoverSessions({ projectsRoot, sinceMs, untilMs, isExcluded }) {
  const included = [];
  let excludedCount = 0;

  for (const dir of discoverProjectDirs(projectsRoot)) {
    let files;
    try {
      files = fs.readdirSync(dir.path, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const f of files) {
      if (!f.isFile() || !f.name.endsWith('.jsonl')) continue;
      const sessionId = f.name.slice(0, -'.jsonl'.length);
      const mainPath = path.join(dir.path, f.name);

      // eslint-disable-next-line no-await-in-loop -- streaming scan must stay sequential per file
      const startDate = await firstTranscriptTimestamp(mainPath);
      if (!startDate) continue;
      const startMs = startDate.getTime();
      if (startMs < sinceMs) continue;
      if (untilMs != null && startMs > untilMs) continue;

      if (isExcluded(sessionId)) {
        excludedCount += 1;
        continue;
      }

      const subagentPaths = listSubagentPaths(dir.path, sessionId);

      included.push({
        sessionId,
        projectDir: dir.name,
        mainPath,
        subagentPaths,
        startTs: startDate.toISOString(),
      });
    }
  }

  return { sessions: included, excludedCount };
}

// --- Text extraction helpers ----------------------------------------------

function extractVisibleText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.filter((b) => b && b.type === 'text').map((b) => b.text || '').join('');
  }
  return '';
}

function isRealPromptUserEntry(entry) {
  if (entry.type !== 'user') return false;
  const content = entry.message && entry.message.content;
  if (content == null) return false;
  if (typeof content === 'string') return true;
  if (Array.isArray(content)) {
    if (content.length === 0) return false;
    return content.some((b) => b && b.type !== 'tool_result');
  }
  return false;
}

function toolResultTexts(entry) {
  const content = entry.message && entry.message.content;
  if (!Array.isArray(content)) return [];
  const texts = [];
  for (const b of content) {
    if (!b || b.type !== 'tool_result') continue;
    let t = '';
    if (typeof b.content === 'string') t = b.content;
    else if (Array.isArray(b.content)) {
      t = b.content.map((c) => (c && (c.text || (typeof c.content === 'string' ? c.content : ''))) || '').join('\n');
    }
    texts.push(t);
  }
  return texts;
}

// --- Per-session context ---------------------------------------------------

function emptyCtx() {
  return {
    waiting: {
      turns: 0,
      tokens: 0,
      byType: {
        taskNotification: { turns: 0, tokens: 0 },
        wakeupTick: { turns: 0, tokens: 0 },
      },
    },
    reorient: {
      postCompaction: { count: 0, turns: 0, tokens: 0, summaryCharsEst: 0 },
      goalQuery: { count: 0, turns: 0, tokens: 0 },
    },
    hookFriction: { events: 0, tokens: 0 },
    allMessages: [], // { ts, tokensFull, hasImplTool } — chronological, for ceremony split
    usesSkill: false,
    skillNames: new Set(),
  };
}

/**
 * Single-pass linear scan of one transcript file (main or subagent), folding
 * results into `ctx`. Dedup (message.id) and run/trigger state are scoped to
 * this one file, matching transcript-cost.mjs's per-file dedup convention —
 * subagent transcripts are separate conversations, not stitched to the main
 * file's run sequence.
 */
function processFile(filePath, ctx) {
  let content;
  try {
    content = fs.readFileSync(filePath, 'utf8');
  } catch {
    return;
  }

  const seenMsgIds = new Set();
  let currentTrigger = null; // { text, followsWakeupTool }
  let currentRunMsgs = [];
  let pendingWakeupFlag = false; // becomes currentTrigger.followsWakeupTool for the NEXT trigger
  let hookRetryPending = false;
  const hookRetryCharged = new Set();

  function finalizeRun(trigger, runMsgs) {
    if (trigger) {
      const runText = runMsgs.map((r) => r.text).join('');
      const runCost = runMsgs.reduce((a, r) => a + r.input + r.cache_read, 0);
      const triggerText = trigger.text;
      const triggerTrim = triggerText.trim();

      const isTaskNotif = /<task-notification>/.test(triggerText);
      const isWakeup = trigger.followsWakeupTool === true;
      const isPostCompaction = triggerTrim.startsWith('This session is being continued from a previous conversation');
      const isGoalQuery = triggerText.length < 400 && GOAL_QUERY_PATTERNS.some((p) => p.test(triggerText));

      if ((isTaskNotif || isWakeup) && runText.length < SHORT_ACK_CHAR_THRESHOLD) {
        ctx.waiting.turns += 1;
        ctx.waiting.tokens += runCost;
        const bucket = isTaskNotif ? 'taskNotification' : 'wakeupTick';
        ctx.waiting.byType[bucket].turns += 1;
        ctx.waiting.byType[bucket].tokens += runCost;
      }
      if (isPostCompaction) {
        ctx.reorient.postCompaction.count += 1;
        ctx.reorient.postCompaction.summaryCharsEst += triggerText.length;
        const first3 = runMsgs.slice(0, 3);
        ctx.reorient.postCompaction.turns += first3.length;
        ctx.reorient.postCompaction.tokens += first3.reduce((a, r) => a + r.input + r.cache_read, 0);
      }
      if (isGoalQuery) {
        ctx.reorient.goalQuery.count += 1;
        ctx.reorient.goalQuery.turns += runMsgs.length;
        ctx.reorient.goalQuery.tokens += runCost;
      }

      const cmdMatch = /<command-name>\/?([a-zA-Z0-9_-]+)/.exec(triggerText);
      if (cmdMatch && !BUILTIN_COMMAND_EXCLUDE.has(cmdMatch[1])) {
        ctx.usesSkill = true;
        ctx.skillNames.add(cmdMatch[1]);
      }
    }

    let lastToolName = null;
    for (const r of runMsgs) {
      if (r.toolNames.length) lastToolName = r.toolNames[r.toolNames.length - 1];
    }
    return WAIT_TOOLS.has(lastToolName);
  }

  for (const line of content.split('\n')) {
    if (!line.trim()) continue;
    let entry;
    try { entry = JSON.parse(line); } catch { continue; }

    if (entry.type === 'user') {
      if (isRealPromptUserEntry(entry)) {
        pendingWakeupFlag = finalizeRun(currentTrigger, currentRunMsgs);
        const text = extractVisibleText(entry.message.content);
        currentTrigger = { text, followsWakeupTool: pendingWakeupFlag };
        currentRunMsgs = [];
      } else {
        const texts = toolResultTexts(entry);
        for (const t of texts) {
          if (HOOK_BLOCK_REGEX.test(t)) {
            ctx.hookFriction.events += 1;
            hookRetryPending = true;
          }
        }
      }
      continue;
    }

    if (entry.type !== 'assistant') continue;
    const msg = entry.message;
    if (!msg) continue;
    const id = msg.id;
    if (id) {
      if (seenMsgIds.has(id)) continue; // duplicate usage snapshot for an already-counted turn
      seenMsgIds.add(id);
    }
    const usage = msg.usage || {};
    const input = usage.input_tokens ?? 0;
    const output = usage.output_tokens ?? 0;
    const cache_write = usage.cache_creation_input_tokens ?? 0;
    const cache_read = usage.cache_read_input_tokens ?? 0;
    const contentArr = Array.isArray(msg.content) ? msg.content : [];
    const text = contentArr.filter((b) => b.type === 'text').map((b) => b.text || '').join('');
    const toolNames = contentArr.filter((b) => b.type === 'tool_use').map((b) => b.name);

    currentRunMsgs.push({ id, input, output, cache_write, cache_read, text, toolNames });

    const hasImplTool = toolNames.some((n) => IMPL_TOOLS.has(n));
    ctx.allMessages.push({ ts: entry.timestamp, tokensFull: input + output + cache_write + cache_read, hasImplTool });

    if (hookRetryPending) {
      hookRetryPending = false;
      if (!id || !hookRetryCharged.has(id)) {
        if (id) hookRetryCharged.add(id);
        ctx.hookFriction.tokens += input + cache_read;
      }
    }
  }

  finalizeRun(currentTrigger, currentRunMsgs);
}

// --- Ceremony split ---------------------------------------------------------

function computeCeremonySplit(ctx) {
  if (!ctx.usesSkill) return null;
  const sorted = ctx.allMessages.slice().sort((a, b) => new Date(a.ts) - new Date(b.ts));
  const firstImplIdx = sorted.findIndex((m) => m.hasImplTool);
  let before = 0;
  let after = 0;
  if (firstImplIdx === -1) {
    before = sorted.reduce((a, m) => a + m.tokensFull, 0);
  } else {
    for (let i = 0; i < sorted.length; i += 1) {
      if (i <= firstImplIdx) before += sorted[i].tokensFull;
      else after += sorted[i].tokensFull;
    }
  }
  return {
    before_tokens: before,
    after_tokens: after,
    total_tokens: before + after,
    has_impl_call: firstImplIdx !== -1,
    ceremony_pct: (before + after) > 0 ? round((before / (before + after)) * 100, 1) : null,
    skills: [...ctx.skillNames],
  };
}

function round(n, decimals = 2) {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}

function sumTok(r) {
  return r.input_tokens + r.output_tokens + r.cache_write_tokens + r.cache_read_tokens;
}

// --- Main ------------------------------------------------------------------

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  console.error(`overhead-taxonomy: discovering sessions ${opts.since}..${opts.until ?? 'now'}...`);
  const exclusionKeys = loadExclusionKeys(EXCLUDED_PATH);
  const isExcluded = makeExclusionMatcher(exclusionKeys);
  const sinceMs = new Date(opts.since).getTime();
  const untilMs = opts.until ? new Date(opts.until).getTime() : null;
  const { sessions, excludedCount } = await discoverSessions({
    projectsRoot: opts.projectsRoot, sinceMs, untilMs, isExcluded,
  });
  console.error(`overhead-taxonomy: ${sessions.length} sessions in window (${fmtScopeExclusion({ excluded: excludedCount, listed: exclusionKeys.length })})`);

  const sessionRows = [];
  let windowTotalTokens = 0;

  let i = 0;
  for (const s of sessions) {
    i += 1;
    if (i % 25 === 0) console.error(`  ...${i}/${sessions.length}`);

    const ctx = emptyCtx();
    processFile(s.mainPath, ctx);
    for (const p of s.subagentPaths) processFile(p, ctx);

    // Session total tokens via the shared lib parser, for the window-total
    // cross-check against the ~30.5B baseline figure (usage-based, dedup by
    // message.id — same methodology as baseline-economics.mjs).
    const main = parseTranscriptTokens(s.mainPath);
    let subTotal = { input_tokens: 0, output_tokens: 0, cache_write_tokens: 0, cache_read_tokens: 0 };
    for (const p of s.subagentPaths) {
      const r = parseTranscriptTokens(p);
      if (r.error) continue;
      subTotal.input_tokens += r.input_tokens;
      subTotal.output_tokens += r.output_tokens;
      subTotal.cache_write_tokens += r.cache_write_tokens;
      subTotal.cache_read_tokens += r.cache_read_tokens;
    }
    const sessionTotalTokens = sumTok(main) + sumTok(subTotal);
    windowTotalTokens += sessionTotalTokens;

    const ceremony = computeCeremonySplit(ctx);

    sessionRows.push({
      session_id: s.sessionId,
      project_dir: s.projectDir,
      start_ts: s.startTs,
      session_total_tokens: sessionTotalTokens,
      waiting: ctx.waiting,
      reorient: ctx.reorient,
      hook_friction: ctx.hookFriction,
      ceremony,
      waiting_share_pct: sessionTotalTokens > 0 ? round((ctx.waiting.tokens / sessionTotalTokens) * 100, 2) : null,
    });
  }

  // --- Aggregate ------------------------------------------------------------

  const agg = {
    waiting: { turns: 0, tokens: 0, byType: { taskNotification: { turns: 0, tokens: 0 }, wakeupTick: { turns: 0, tokens: 0 } } },
    reorient: { postCompaction: { count: 0, turns: 0, tokens: 0, summaryCharsEst: 0 }, goalQuery: { count: 0, turns: 0, tokens: 0 } },
    hookFriction: { events: 0, tokens: 0 },
  };
  const ceremonySessions = [];
  let ceremonyBeforeTotal = 0;
  let ceremonyAfterTotal = 0;

  for (const row of sessionRows) {
    agg.waiting.turns += row.waiting.turns;
    agg.waiting.tokens += row.waiting.tokens;
    agg.waiting.byType.taskNotification.turns += row.waiting.byType.taskNotification.turns;
    agg.waiting.byType.taskNotification.tokens += row.waiting.byType.taskNotification.tokens;
    agg.waiting.byType.wakeupTick.turns += row.waiting.byType.wakeupTick.turns;
    agg.waiting.byType.wakeupTick.tokens += row.waiting.byType.wakeupTick.tokens;

    agg.reorient.postCompaction.count += row.reorient.postCompaction.count;
    agg.reorient.postCompaction.turns += row.reorient.postCompaction.turns;
    agg.reorient.postCompaction.tokens += row.reorient.postCompaction.tokens;
    agg.reorient.postCompaction.summaryCharsEst += row.reorient.postCompaction.summaryCharsEst;
    agg.reorient.goalQuery.count += row.reorient.goalQuery.count;
    agg.reorient.goalQuery.turns += row.reorient.goalQuery.turns;
    agg.reorient.goalQuery.tokens += row.reorient.goalQuery.tokens;

    agg.hookFriction.events += row.hook_friction.events;
    agg.hookFriction.tokens += row.hook_friction.tokens;

    if (row.ceremony) {
      ceremonySessions.push(row);
      ceremonyBeforeTotal += row.ceremony.before_tokens;
      ceremonyAfterTotal += row.ceremony.after_tokens;
    }
  }

  const reorientTotalTurns = agg.reorient.postCompaction.turns + agg.reorient.goalQuery.turns;
  const reorientTotalTokens = agg.reorient.postCompaction.tokens + agg.reorient.goalQuery.tokens;

  const categoryTable = [
    {
      category: 'WAITING',
      turns: agg.waiting.turns,
      tokens: agg.waiting.tokens,
      share_pct: windowTotalTokens > 0 ? round((agg.waiting.tokens / windowTotalTokens) * 100, 3) : null,
    },
    {
      category: 'RE-ORIENTATION',
      turns: reorientTotalTurns,
      tokens: reorientTotalTokens,
      share_pct: windowTotalTokens > 0 ? round((reorientTotalTokens / windowTotalTokens) * 100, 3) : null,
    },
    {
      category: 'HOOK-FRICTION',
      turns: agg.hookFriction.events,
      tokens: agg.hookFriction.tokens,
      share_pct: windowTotalTokens > 0 ? round((agg.hookFriction.tokens / windowTotalTokens) * 100, 3) : null,
    },
  ];

  const top10Waiting = sessionRows
    .filter((r) => r.waiting_share_pct != null && r.waiting.turns > 0)
    .sort((a, b) => b.waiting_share_pct - a.waiting_share_pct)
    .slice(0, 10);

  const ceremonyAggTotal = ceremonyBeforeTotal + ceremonyAfterTotal;

  const report = {
    window: { since: opts.since, until: opts.until },
    generated_at: new Date().toISOString(),
    sessions_in_window: sessions.length,
    sessions_excluded_by_scope: excludedCount,
    // The denominator: 0-of-N is an inert filter, not a clean window (858 §7).
    scope_filter_ids_listed: exclusionKeys.length,
    window_total_tokens: windowTotalTokens,
    category_table: categoryTable,
    waiting_detail: agg.waiting,
    reorient_detail: {
      ...agg.reorient,
      note: 'postCompaction.summaryCharsEst is a chars/4-fallback size estimate of the pasted compaction summary text itself — informational only, NOT added into postCompaction.tokens (that cost is already embedded in the first assistant turn\'s input_tokens; adding it again would double-count).',
      summaryTokensEstChars4: Math.round(agg.reorient.postCompaction.summaryCharsEst / 4),
    },
    hook_friction_detail: agg.hookFriction,
    ceremony_sessions_count: ceremonySessions.length,
    ceremony_aggregate: {
      before_tokens: ceremonyBeforeTotal,
      after_tokens: ceremonyAfterTotal,
      total_tokens: ceremonyAggTotal,
      ceremony_pct: ceremonyAggTotal > 0 ? round((ceremonyBeforeTotal / ceremonyAggTotal) * 100, 1) : null,
    },
    ceremony_per_session: ceremonySessions.map((r) => ({
      session_id: r.session_id.slice(0, 8),
      start_ts: r.start_ts,
      ...r.ceremony,
    })),
    top10_by_waiting_share: top10Waiting.map((r) => ({
      session_id: r.session_id.slice(0, 8),
      full_session_id: r.session_id,
      start_ts: r.start_ts,
      project_dir: r.project_dir,
      waiting_turns: r.waiting.turns,
      waiting_tokens: r.waiting.tokens,
      session_total_tokens: r.session_total_tokens,
      waiting_share_pct: r.waiting_share_pct,
    })),
    all_sessions: sessionRows,
  };

  fs.writeFileSync(OUT_JSON, JSON.stringify(report, null, 2));
  console.error(`overhead-taxonomy: wrote ${OUT_JSON}`);

  // --- Console summary --------------------------------------------------
  console.log('\n=== WINDOW ===');
  console.log(`sessions_in_window=${sessions.length}  ${fmtScopeExclusion({ excluded: excludedCount, listed: exclusionKeys.length })}`);
  console.log(`window_total_tokens=${windowTotalTokens.toLocaleString()} (~${(windowTotalTokens / 1e9).toFixed(2)}B)`);

  console.log('\n=== CATEGORY TABLE ===');
  console.table(categoryTable.map((c) => ({
    category: c.category, turns: c.turns, tokens: c.tokens.toLocaleString(), share_pct: c.share_pct,
  })));

  console.log('\n=== WAITING breakdown ===');
  console.log(JSON.stringify(agg.waiting.byType, null, 2));

  console.log('\n=== RE-ORIENTATION breakdown ===');
  console.log(JSON.stringify(report.reorient_detail, null, 2));

  console.log('\n=== HOOK-FRICTION ===');
  console.log(JSON.stringify(agg.hookFriction, null, 2));

  console.log(`\n=== CEREMONY (skill-using sessions: ${ceremonySessions.length}) ===`);
  console.log(JSON.stringify(report.ceremony_aggregate, null, 2));
  console.table(report.ceremony_per_session.map((c) => ({
    session: c.session_id, before: c.before_tokens, after: c.after_tokens, ceremony_pct: c.ceremony_pct, has_impl: c.has_impl_call, skills: c.skills.join(','),
  })));

  console.log('\n=== TOP 10 BY WAITING SHARE ===');
  console.table(report.top10_by_waiting_share.map((r) => ({
    session: r.session_id, waiting_turns: r.waiting_turns, waiting_tokens: r.waiting_tokens.toLocaleString(),
    session_total: r.session_total_tokens.toLocaleString(), waiting_share_pct: r.waiting_share_pct,
  })));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((err) => { console.error(err); process.exit(1); });
}
