#!/usr/bin/env node

/**
 * PreToolUse intervention hook (matcher: "Read", "Edit") + a PostToolUse advisory
 * (matcher: "Edit|Write", scoped to `docs/tempdocs/**` — see governance/agent-hooks.v1.json).
 * Same shape as build-counter.mjs: one script, two manifest bindings, branching on
 * `input.hook_event_name`.
 *
 * PreToolUse behaviors (synchronous, can block):
 * 1. Blocks a file's UNBOUNDED re-reads once they pass HOT_FILE_CAP this session.
 * 2. Caps an agent-supplied offset/limit whose slice would still blow the Read tool's
 *    own ~25k-token ceiling (tempdoc 727 F-7c).
 * 3. Tracks per-session read and edit counts for compact-save.mjs (no warnings).
 *
 * PostToolUse behavior (advisory only, tempdoc 930 §18.1 row 8 / §19.3 F4):
 * 4. After an Edit/Write to a `docs/tempdocs/NNN-*` file, if that tempdoc number now exceeds
 *    the size cap (`scripts/ci/check-tempdoc-size.mjs`), surfaces the same three-remedy
 *    message at write time instead of leaving it for CI to catch at merge time. Never blocks —
 *    PostToolUse can't undo the write anyway, and CI's `check-tempdoc-size.mjs` is the actual
 *    gate; this is purely earlier feedback.
 *
 * REMOVED 2026-08-18: the blanket "auto-limit any >8KB file to 200 lines" injection.
 * It was tuned for a much smaller context budget than the sessions that now run here
 * (Opus 5 at 1M), and it taxed EVERY large read — including the first, orienting one —
 * to save context that is no longer scarce, while costing extra round-trips to re-read
 * with offsets. The two targeted protections above remain: they bound genuinely
 * pathological reads (the same file over and over; a single slice over the platform's
 * hard ceiling) rather than routine ones. Owner decision, this session.
 *
 * - Synchronous (async: false) — blocks until it returns
 * - File I/O: reads/writes tiny per-session count caches (~1-2KB each)
 * - Outputs hookSpecificOutput with updatedInput when the F-7c cap fires
 *
 * Note: permissionDecision: 'allow' auto-approves the Read call without user
 * confirmation when that cap is applied.
 */

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync, readStdin, runHook, telemetryDir as TELEMETRY_DIR, repoRoot as REPO_ROOT } from '../lib/hook-base.mjs';
import { CAP_LINES, tempdocSize, remedyMessage } from '../../ci/check-tempdoc-size.mjs';

// Below this size a file is too small for any slice of it to approach the Read
// tool's token ceiling, so the F-7c explicit-limit cap skips it outright.
const SIZE_THRESHOLD_BYTES = 8_000;
// Unbounded re-reads of one file per session before the hook blocks and asks
// for offset/limit. 10 tolerates legitimate iterative work while catching the
// "keep re-reading the whole file" context-waste pattern; compaction resets it.
const HOT_FILE_CAP = 10;

function normalizePath(p) {
  return p.replace(/\\/g, '/');
}

/** Block an unbounded read once it has been re-read `cap` times this session. */
export function shouldBlockHotFile(unboundedCount, isUnbounded, cap = HOT_FILE_CAP) {
  return !!isUnbounded && unboundedCount >= cap;
}

const TEMPDOC_NUMBER_RE = /(?:^|\/)docs\/tempdocs\/(\d+)-/;

/** The tempdoc NUMBER a path belongs to (its main file or a `NNN-*` sidecar), or null. */
export function tempdocNumberFromPath(filePath) {
  if (!filePath) return null;
  const m = TEMPDOC_NUMBER_RE.exec(normalizePath(filePath));
  return m ? m[1] : null;
}

/**
 * PostToolUse advisory: after an Edit/Write under `docs/tempdocs/`, has that tempdoc number
 * crossed the size cap? Returns the hint string to surface, or null (under cap / not a
 * tempdoc path / repo lookup failure — fail-open, this is advisory-only).
 */
export function tempdocSizeHint(repoRoot, filePath) {
  const number = tempdocNumberFromPath(filePath);
  if (!number) return null;
  let size;
  try {
    size = tempdocSize(repoRoot, number);
  } catch {
    return null;
  }
  if (size.total <= CAP_LINES) return null;
  return (
    `Write-time size-cap advisory (this is not a block — check-tempdoc-size.mjs is the CI gate):\n` +
    remedyMessage(repoRoot, number, size, CAP_LINES)
  );
}

// Tempdoc 727 F-7c: chars-per-token estimate, calibrated live against a real dense tempdoc
// (docs/tempdocs/624-agentic-retrieval-eval-rebuild.md — heavy on hyphenated technical terms,
// parenthetical citations, and punctuation, which tokenizes less efficiently than plain
// prose). A first pass at 3.5 chars/token undershot the real ceiling (a 737-line slice
// estimated at 20,000 tokens actually reported 28,220) — the measured real ratio for that
// slice was ~2.59 chars/token. 2.3 stays conservative below that measured value (one real
// data point, not a guaranteed universal constant — err toward capping earlier, not later)
// and a safety margin below the platform's own ~25,000-token Read ceiling.
const CHARS_PER_TOKEN_ESTIMATE = 2.3;
const SAFE_TOKEN_CEILING = 18_000;

/**
 * Confirmed live (tempdoc 727 derisk): an agent-specified offset/limit sails straight
 * through this hook untouched even when that requested slice is itself still large enough to
 * hit the platform's own Read ceiling. A file-wide average bytes-per-line estimate is not
 * reliable for deciding this: a real tempdoc's YAML frontmatter can contain a handful of
 * individual "lines" that are each thousands of characters (confirmed live against
 * docs/tempdocs/624-agentic-retrieval-eval-rebuild.md), which skews any global per-file
 * average far off the actual density of the requested slice. This measures the ACTUAL
 * requested line range's real character count instead of estimating from the whole file.
 */
/**
 * Read only enough of `filePath` off disk to cover lines `[1, offset - 1 + limit]`, rather
 * than the whole file — an earlier version of this function read+split the entire file on
 * every call, which defeats the purpose of offset/limit for a huge file deliberately read in
 * small slices (tempdoc 727 review Finding B). Grows the read geometrically (starting from a
 * generous per-line byte estimate) until enough lines are collected or EOF is hit, so total
 * bytes read stay bounded by roughly the requested range, not the file's full size.
 *
 * `limit === null` means "read to true EOF" (no limit given, just an offset) — that request is
 * inherently for "the rest of the file," so it reads the whole remainder; this only bounds the
 * case Finding B was about (a limit IS given).
 */
function readLineRangeBounded(filePath, offset, limit, fileSizeBytes) {
  if (limit == null) {
    return fs.readFileSync(filePath, 'utf8').split('\n');
  }

  const neededLines = offset - 1 + limit;
  const GENEROUS_BYTES_PER_LINE = 200; // generous starting estimate; doubled on undershoot
  let readBytes = Math.min(fileSizeBytes, neededLines * GENEROUS_BYTES_PER_LINE);
  const fd = fs.openSync(filePath, 'r');
  try {
    for (;;) {
      const buf = Buffer.alloc(readBytes);
      const bytesRead = fs.readSync(fd, buf, 0, readBytes, 0);
      const text = buf.toString('utf8', 0, bytesRead);
      const lines = text.split('\n');
      const hitEof = bytesRead >= fileSizeBytes;
      if (lines.length > neededLines || hitEof) {
        return lines;
      }
      readBytes = Math.min(fileSizeBytes, readBytes * 2);
    }
  } finally {
    fs.closeSync(fd);
  }
}

export function shouldCapExplicitLimit(toolInput) {
  if (!toolInput?.file_path) return null;
  const hasExplicitRange = toolInput.offset != null || toolInput.limit != null;
  // A read with NO offset/limit is left alone entirely (the blanket auto-limit was removed
  // 2026-08-18); the platform's own Read truncation is the backstop for that case.
  if (!hasExplicitRange) return null;

  let stat;
  try {
    stat = fs.statSync(toolInput.file_path);
  } catch {
    return null; // let Read handle a missing/inaccessible file
  }
  if (stat.size <= SIZE_THRESHOLD_BYTES) return null;

  const offset = toolInput.offset != null ? Math.max(1, toolInput.offset) : 1;
  let lines;
  try {
    lines = readLineRangeBounded(toolInput.file_path, offset, toolInput.limit ?? null, stat.size);
  } catch {
    return null;
  }

  const requestedLimit = toolInput.limit != null ? toolInput.limit : lines.length - offset + 1;
  const slice = lines.slice(offset - 1, offset - 1 + requestedLimit);
  const sliceChars = slice.reduce((sum, l) => sum + l.length + 1, 0); // +1 per line for the stripped '\n'
  const estimatedTokens = sliceChars / CHARS_PER_TOKEN_ESTIMATE;
  if (estimatedTokens <= SAFE_TOKEN_CEILING) return null;

  const safeLimit = Math.max(1, Math.floor(requestedLimit * (SAFE_TOKEN_CEILING / estimatedTokens)));
  return {
    updatedInput: { ...toolInput, limit: safeLimit },
    sizeBytes: stat.size,
    requestedLimit,
    safeLimit,
    estimatedTokens: Math.round(estimatedTokens),
  };
}

// --- Read-count tracking ---

function readCountFilePath(sessionId) {
  return path.join(TELEMETRY_DIR, `read-counts-${sessionId}.json`);
}

function loadReadCounts(sessionId) {
  try {
    const data = fs.readFileSync(readCountFilePath(sessionId), 'utf8');
    return JSON.parse(data);
  } catch {
    return {};
  }
}

function saveReadCounts(sessionId, counts) {
  try {
    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    atomicWriteFileSync(readCountFilePath(sessionId), JSON.stringify(counts));
  } catch {
    // Best-effort — don't block the hook
  }
}

// --- Edit-count tracking ---

function editCountFilePath(sessionId) {
  return path.join(TELEMETRY_DIR, `edit-counts-${sessionId}.json`);
}

function loadEditCounts(sessionId) {
  try {
    const data = fs.readFileSync(editCountFilePath(sessionId), 'utf8');
    return JSON.parse(data);
  } catch {
    return {};
  }
}

function saveEditCounts(sessionId, counts) {
  try {
    fs.mkdirSync(TELEMETRY_DIR, { recursive: true });
    atomicWriteFileSync(editCountFilePath(sessionId), JSON.stringify(counts));
  } catch {
    // Best-effort — don't block the hook
  }
}

// --- Cache cleanup ---

const STALE_CACHE_MS = 24 * 60 * 60 * 1000; // 24 hours

function pruneStaleCountFiles() {
  try {
    const files = fs.readdirSync(TELEMETRY_DIR)
      .filter(f =>
        f.startsWith('read-counts-') || f.startsWith('edit-counts-') ||
        f.startsWith('repeat-buffer-') || f.startsWith('build-fails-') ||
        f.startsWith('turn-count-')
      );
    const now = Date.now();
    for (const file of files) {
      const filePath = path.join(TELEMETRY_DIR, file);
      const stat = fs.statSync(filePath);
      if (now - stat.mtimeMs > STALE_CACHE_MS) {
        fs.unlinkSync(filePath);
      }
    }
  } catch {
    // Best-effort — don't block the hook
  }
}

// Tempdoc 727 F-7a: reserved key for the basename→[full paths] index (below), stored
// alongside the per-path counts in the same read-counts-<sessionId>.json file rather than a
// second cache file — one source of truth for "what has this session read." Prefixed with an
// underscore so it can't collide with a normalized file path (all real paths contain `/`).
// Review Finding D: living in the SAME cache file means compact-save.mjs's read-counts reset
// wipes this index too on every compaction (deliberate for its original hot-file-cap purpose)
// — cross-root recognition in edit-reread-hint.mjs only covers reads since the last compaction.
const BASENAME_INDEX_KEY = '_byBasename';

function basenameOf(normPath) {
  const idx = normPath.lastIndexOf('/');
  return idx === -1 ? normPath : normPath.slice(idx + 1);
}

function trackRead(sessionId, filePath, isUnbounded) {
  if (!sessionId || !filePath) return { total: 0, unbounded: 0 };
  const counts = loadReadCounts(sessionId);
  const norm = normalizePath(filePath);
  const isFirst = Object.keys(counts).length === 0;

  // Backward compat: old format stored just a number per file
  if (typeof counts[norm] === 'number') {
    counts[norm] = { total: counts[norm], unbounded: counts[norm] };
  }
  if (!counts[norm]) counts[norm] = { total: 0, unbounded: 0 };

  counts[norm].total += 1;
  if (isUnbounded) counts[norm].unbounded += 1;

  // Tempdoc 727 F-7a: index this read by basename too, so a later Edit-before-fresh-Read
  // failure on a DIFFERENT full path with the same basename (the worktree-copy vs.
  // main-checkout-copy case) can be recognized as "you read this file, just under a
  // different root" rather than staying silent or restating the platform's own generic error.
  const byBasename = counts[BASENAME_INDEX_KEY] ?? (counts[BASENAME_INDEX_KEY] = {});
  const base = basenameOf(norm);
  const paths = byBasename[base] ?? (byBasename[base] = []);
  if (!paths.includes(norm)) paths.push(norm);

  saveReadCounts(sessionId, counts);

  // Prune stale cache files on first read of a new session
  if (isFirst) pruneStaleCountFiles();

  return counts[norm];
}

/**
 * Tempdoc 727 F-7a: full normalized paths read this session sharing `filePath`'s basename,
 * EXCLUDING `filePath` itself — used by edit-reread-hint.mjs to recognize a cross-root
 * re-read miss. Returns `[]` if nothing else with this basename was read (including when
 * `filePath` was never read at all, or is the only path read under this basename).
 */
export function getOtherPathsWithSameBasename(sessionId, filePath) {
  if (!sessionId || !filePath) return [];
  const counts = loadReadCounts(sessionId);
  const norm = normalizePath(filePath);
  const base = basenameOf(norm);
  const paths = counts[BASENAME_INDEX_KEY]?.[base] ?? [];
  return paths.filter(p => p !== norm);
}

function trackEdit(sessionId, filePath) {
  if (!sessionId || !filePath) return 0;
  const counts = loadEditCounts(sessionId);
  const norm = normalizePath(filePath);
  if (!counts[norm]) counts[norm] = [];
  counts[norm].push(Date.now());
  saveEditCounts(sessionId, counts);
  return counts[norm].length;
}

// --- Main ---

async function main() {
  const raw = await readStdin();

  try {
    const input = JSON.parse(raw);
    const toolInput = input.tool_input;
    const sessionId = input.session_id;

    // --- PostToolUse: tempdoc size-cap advisory (Edit|Write, docs/tempdocs/** only — see
    // the manifest's `if` predicate). Checked FIRST and returns unconditionally: PostToolUse
    // calls also carry tool_name 'Edit', and the PreToolUse-only tracking branch below must
    // not double-count them.
    if (input.hook_event_name === 'PostToolUse') {
      if (input.tool_name === 'Edit' || input.tool_name === 'Write') {
        const hint = tempdocSizeHint(REPO_ROOT, toolInput?.file_path);
        if (hint) {
          process.stdout.write(JSON.stringify({
            hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: hint },
          }));
        }
      }
      return;
    }

    // --- Edit handling (PreToolUse; track only, no warning) ---
    if (input.tool_name === 'Edit') {
      trackEdit(sessionId, toolInput?.file_path);
      return;
    }

    // --- Read handling ---
    if (input.tool_name !== 'Read') return;

    // Track read count (for compact-save.mjs and hot-file cap)
    const isUnbounded = toolInput?.offset == null && toolInput?.limit == null;
    const readCounts = trackRead(sessionId, toolInput?.file_path, isUnbounded);

    // Hot-file cap: block unbounded reads after threshold (unbounded count only).
    // Targeted reads (with offset/limit) don't count toward the cap and always pass.
    // Compaction resets read counts (compact-save.mjs:114), giving a fresh budget.
    if (shouldBlockHotFile(readCounts.unbounded, isUnbounded)) {
      const shortPath = (toolInput.file_path || '').split(/[/\\]/).slice(-2).join('/');
      let totalLines = null;
      try {
        const content = fs.readFileSync(toolInput.file_path, 'utf8');
        totalLines = content.split('\n').length;
      } catch { /* best-effort */ }
      const sizeHint = totalLines != null ? ` (${totalLines} total lines)` : '';
      process.stderr.write(
        `This file (${shortPath}) has had ${readCounts.unbounded} unbounded reads this session${sizeHint}. ` +
        `Use offset and limit to read only the section you need.`
      );
      process.exit(2);
    }

    // Tempdoc 727 F-7c: the caller supplied its own offset/limit, but that requested
    // slice may itself still be too large for the Read tool's own token ceiling.
    const explicitCap = shouldCapExplicitLimit(toolInput);
    if (explicitCap) {
      const shortPath = (toolInput.file_path || '').split(/[/\\]/).slice(-2).join('/');
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: {
          hookEventName: 'PreToolUse',
          permissionDecision: 'allow',
          updatedInput: explicitCap.updatedInput,
          additionalContext:
            `Note: the requested limit (${explicitCap.requestedLimit} lines) on ${shortPath} ` +
            `(${explicitCap.sizeBytes} bytes) was estimated at ~${explicitCap.estimatedTokens} tokens — ` +
            `over the safe ceiling — so it was capped to ${explicitCap.safeLimit} lines. ` +
            `Re-read with a later offset for more.`,
        },
      }));
      return;
    }
    // No output = no modification. An unbounded read of a large file now passes
    // through untouched — the platform's Read truncation handles it.
  } catch {
    // Parse failure — no modification
  }
}

runHook(import.meta.url, main);
