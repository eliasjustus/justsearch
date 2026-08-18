/**
 * Shared transcript discovery + line-stream + turn model over local Claude Code
 * transcripts (tempdoc 743 second wave, Slice 3 — "Shared substrate" design,
 * R6 derisk HELD). `lib/transcript-cost.mjs` (743 Phase 1) is the shape
 * precedent: one small module several readers import from, instead of each
 * hand-rolling its own discovery/parse.
 *
 * Migration is OPPORTUNISTIC, never big-bang (743 design): the seven existing
 * readers (analyze-session.mjs, baseline-economics.mjs, evaluate-session.mjs,
 * friction-timeline.mjs, mine-friction.mjs, cost-session.mjs,
 * context-attribution.mjs) are UNTOUCHED by this slice. Each future wave
 * slice that touches a reader migrates that ONE reader, attaching it as a
 * consumer of this substrate from day one of its own change rather than
 * rewriting everything at once. `lib/transcript-cost.mjs` itself is also
 * untouched here — this module is discovery + line-stream + a minimal turn
 * model, not pricing.
 *
 * PINNED ASSUMPTION (tempdoc 743 V-A5, EMPIRICAL / UNDOCUMENTED): Claude Code
 * stores transcripts at `<projectsRoot>/<encoded-cwd>/<sessionId>.jsonl`,
 * where `<encoded-cwd>` slugifies the working directory (colons and path
 * separators each become `-`; per-worktree paths get their own dir, e.g.
 * `F--justsearch-public--claude-worktrees-<name>`) and `projectsRoot`
 * defaults to `~/.claude/projects`. Subagent transcripts live at
 * `<projectDir>/<sessionId>/subagents/agent-*.jsonl`. This is NOT a
 * documented platform contract — a Claude Code update could change it
 * without notice. Every function below degrades gracefully (empty result,
 * not a throw) when the layout doesn't match, so a stale assumption produces
 * a quiet "found nothing" rather than crashing a caller mid-scan.
 *
 * Explicitly a DELETABLE ADAPTER (743 design, "Shared substrate" section):
 * if Claude Code ships a native session index covering discovery, this
 * module shrinks to just the parser (streamLines/iterateTurns) and the
 * discovery half retires.
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

export const DEFAULT_PROJECTS_ROOT = path.join(os.homedir(), '.claude', 'projects');

/**
 * Every directory under `projectsRoot` whose slug matches /justsearch/i —
 * the main checkout, every worktree, and any jseval subdir each show up as a
 * distinct project dir under Claude Code's project-path slugging (743 Phase
 * 1 finding, reused verbatim as the matching rule here). Returns `[]` (never
 * throws) when `projectsRoot` doesn't exist — a fresh machine or a
 * contributor clone with no transcript history is a normal case, not an
 * error worth surfacing to every caller.
 */
export function discoverProjectDirs(projectsRoot = DEFAULT_PROJECTS_ROOT) {
  let entries;
  try {
    entries = fs.readdirSync(projectsRoot, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((e) => e.isDirectory() && /justsearch/i.test(e.name))
    .map((e) => ({ name: e.name, path: path.join(projectsRoot, e.name) }));
}

/**
 * Every `<sessionId>.jsonl` main-transcript file across every discovered
 * project dir, with the file's mtime as a cheap window signal (no per-file
 * content scan). `sinceMs`/`untilMs` filter on mtime.
 *
 * NOTE: mtime is not the same signal as "first transcript line's own
 * timestamp" — baseline-economics.mjs's `discoverSessions` deliberately
 * scans into each file for that definitional start-time, because a resumed
 * session's mtime moves forward every time it's touched. Callers needing
 * that precision keep doing their own scan (transcript-store does not
 * duplicate it); this is the fast list for consumers — like the signature
 * census — for whom "was this session active in the window" is good enough.
 * Tolerant of an unreadable project dir (permissions, a directory removed
 * mid-scan): skipped, not thrown.
 */
export function listSessions({ projectsRoot = DEFAULT_PROJECTS_ROOT, sinceMs = null, untilMs = null } = {}) {
  const sessions = [];
  for (const dir of discoverProjectDirs(projectsRoot)) {
    let files;
    try {
      files = fs.readdirSync(dir.path, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const f of files) {
      if (!f.isFile() || !f.name.endsWith('.jsonl')) continue;
      const filePath = path.join(dir.path, f.name);
      let stat;
      try {
        stat = fs.statSync(filePath);
      } catch {
        continue;
      }
      const mtime = stat.mtimeMs;
      if (sinceMs != null && mtime < sinceMs) continue;
      if (untilMs != null && mtime > untilMs) continue;
      sessions.push({
        path: filePath,
        sessionId: f.name.slice(0, -'.jsonl'.length),
        mtime,
        size: stat.size,
        projectDir: dir.name,
      });
    }
  }
  return sessions;
}

/**
 * Subagent transcript paths for one session
 * (`<projectDirPath>/<sessionId>/subagents/agent-*.jsonl`). Returns `[]`
 * when the session has no subagents dir — the common case for a session
 * that never spawned a Task/Agent.
 */
export function listSubagentPaths(projectDirPath, sessionId) {
  const subagentDir = path.join(projectDirPath, sessionId, 'subagents');
  try {
    return fs.readdirSync(subagentDir)
      .filter((n) => n.startsWith('agent-') && n.endsWith('.jsonl'))
      .map((n) => path.join(subagentDir, n));
  } catch {
    return [];
  }
}

/**
 * Read `file` line-by-line (explicit UTF-8), calling
 * `onLine(parsed, lineNumber, rawLine)` for every line that parses as JSON.
 * A line that fails to parse (truncated write mid-session, stray binary) is
 * silently skipped PER LINE — matching every existing reader's
 * `try { JSON.parse(line) } catch { continue }` convention — so one bad line
 * never aborts the read of the rest of the file. `lineNumber` is 1-based.
 * A missing/unreadable file is a silent no-op, not a throw.
 */
export function streamLines(file, onLine) {
  let content;
  try {
    content = fs.readFileSync(file, 'utf8');
  } catch {
    return;
  }
  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i += 1) {
    const raw = lines[i];
    if (!raw.trim()) continue;
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      continue;
    }
    onLine(parsed, i + 1, raw);
  }
}

// --- minimal turn model -----------------------------------------------

function extractVisibleText(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.filter((b) => b && b.type === 'text').map((b) => b.text || '').join('');
  }
  return '';
}

function extractToolUses(content) {
  if (!Array.isArray(content)) return [];
  return content.filter((b) => b && b.type === 'tool_use').map((b) => ({ name: b.name, input: b.input }));
}

function extractToolResultText(block) {
  if (typeof block.content === 'string') return block.content;
  if (Array.isArray(block.content)) {
    return block.content
      .map((c) => (c && (c.text || (typeof c.content === 'string' ? c.content : ''))) || '')
      .join('\n');
  }
  return '';
}

function extractToolResults(content) {
  if (!Array.isArray(content)) return [];
  return content
    .filter((b) => b && b.type === 'tool_result')
    .map((b) => ({ isError: Boolean(b.is_error), text: extractToolResultText(b) }));
}

/**
 * Minimal per-line turn model over one transcript file — one yielded turn
 * per JSONL entry (Claude Code's own storage unit; a single logical
 * assistant "turn" is often several lines, e.g. one per content block on a
 * resumed transcript — callers needing turn-GROUPING fold these themselves,
 * as overhead-taxonomy.mjs's own `finalizeRun` already does for its purpose,
 * not duplicated here). A generator, so a caller scanning for one signature
 * can stop early without paying to parse the rest of a large file.
 *
 * Shape: `{ type, timestamp, isSidechain, isCompactBoundary, toolUses:[{name,input}],
 * toolResults:[{isError,text}], userText, assistantText, usage, model,
 * messageId, requestId }`.
 * `userText`/`assistantText` are `''` (never null) when the entry carries
 * none, so a truthiness check (`if (turn.userText)`) works without a
 * null-guard. `usage` is `entry.message.usage` verbatim on an assistant
 * entry, else `null` — this module does no pricing (see `transcript-cost.mjs`
 * for that).
 *
 * `model` / `messageId` / `requestId` / `isCompactBoundary` were added by
 * tempdoc 841 (cache-efficiency reader). They are ADDITIVE — existing
 * consumers destructure the fields they need and are unaffected. They exist
 * so a cost-shaped reader can dedupe on `(messageId, requestId)` and bucket
 * by model WITHOUT hand-rolling a fourth independent transcript parser, which
 * is the drift this module was created to stop. `isCompactBoundary` marks the
 * entries Claude Code writes around a compaction, so a reader can attribute a
 * prefix reset to compaction rather than guessing.
 */
export function* iterateTurns(file) {
  let content;
  try {
    content = fs.readFileSync(file, 'utf8');
  } catch {
    return;
  }
  for (const raw of content.split('\n')) {
    if (!raw.trim()) continue;
    let entry;
    try {
      entry = JSON.parse(raw);
    } catch {
      continue;
    }

    const turn = {
      type: entry.type ?? null,
      timestamp: entry.timestamp ?? null,
      isSidechain: Boolean(entry.isSidechain),
      isCompactBoundary: Boolean(
        entry.isCompactSummary || entry.subtype === 'compact_boundary' || entry.compactMetadata,
      ),
      toolUses: [],
      toolResults: [],
      userText: '',
      assistantText: '',
      usage: null,
      model: entry.message?.model ?? null,
      messageId: entry.message?.id ?? null,
      requestId: entry.requestId ?? null,
    };

    const msgContent = entry.message?.content;
    if (entry.type === 'user') {
      turn.userText = extractVisibleText(msgContent);
      turn.toolResults = extractToolResults(msgContent);
    } else if (entry.type === 'assistant') {
      turn.assistantText = extractVisibleText(msgContent);
      turn.toolUses = extractToolUses(msgContent);
      turn.usage = entry.message?.usage ?? null;
    }

    yield turn;
  }
}

// --- duplicate-user-message artifact filter (tempdoc 743 evidence-lane F-1) --

const INTERRUPT_MARKER_RE = /\[Request interrupted by user\]/;

function turnHasInterruptMarker(turn) {
  if (INTERRUPT_MARKER_RE.test(turn.userText || '')) return true;
  return (turn.toolResults || []).some((r) => INTERRUPT_MARKER_RE.test(r.text || ''));
}

function normalizeForDup(text) {
  return text.trim();
}

/**
 * Adjacent near-identical USER-text turns are a transcript-STORAGE artifact,
 * NOT a real repeated founder message — UNLESS an explicit
 * `[Request interrupted by user]` marker precedes the repeat, in which case
 * it IS real: the founder's request was interrupted before the agent acted
 * on it and they said it again (tempdoc 743 evidence-lane finding F-1 /
 * instrument note F.1 — "friction miners counting user repeats without this
 * filter will over-count founder interventions"). Verified against 57 real
 * occurrences across this machine's transcript corpus: the marker is almost
 * always ITS OWN standalone user turn carrying only the literal text
 * `[Request interrupted by user]` (56/57) — which already breaks adjacency
 * on its own, since it becomes the new `lastUserText` and so the next turn
 * is no longer compared against the pre-interrupt text — with one observed
 * case (1/57) where it instead arrives as a `tool_result` on a turn with no
 * visible text (an aborted tool call's own result); that shape needs the
 * explicit `interruptSeen` carry-forward below, since an empty-`userText`
 * turn does not update `lastUserText`. Both shapes are handled; a marker
 * embedded in the SAME turn as the repeat's own text was never observed but
 * is handled too (order of checks below runs the marker check first).
 *
 * Takes a chronological array of turns (as yielded by `iterateTurns`, or any
 * `{type, userText, toolResults}` shape) and returns a NEW array with
 * artifact duplicates removed. Non-user turns, and user turns with no
 * visible text (pure tool-result entries), pass through unchanged and do
 * NOT reset adjacency — a tool-only entry sitting between two real prompts
 * must not defeat the dedup. Every place this module's consumers count or
 * classify user messages MUST run turns through this filter first.
 */
export function dedupeAdjacentUserTurns(turns) {
  const out = [];
  let lastUserText = null;
  let interruptSeen = false;

  for (const turn of turns) {
    if (turnHasInterruptMarker(turn)) interruptSeen = true;

    if (turn.type !== 'user' || !turn.userText) {
      out.push(turn);
      continue;
    }

    const isDuplicate = lastUserText !== null && normalizeForDup(turn.userText) === normalizeForDup(lastUserText);
    if (isDuplicate && !interruptSeen) {
      continue; // storage artifact — drop; lastUserText/interruptSeen carry forward unchanged
    }

    out.push(turn);
    lastUserText = turn.userText;
    interruptSeen = false; // the marker (if any) is "spent" on this transition
  }

  return out;
}
