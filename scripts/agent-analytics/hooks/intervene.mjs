#!/usr/bin/env node

/**
 * Synchronous PreToolUse intervention hook (matcher: "Read", "Edit").
 *
 * Two behaviors:
 * 1. Auto-injects limit for Read calls on large files (>8KB) without offset/limit.
 * 2. Tracks per-session read and edit counts for compact-save.mjs (no warnings).
 *
 * - Synchronous (async: false) — blocks until it returns
 * - File I/O: reads/writes tiny per-session count caches (~1-2KB each)
 * - Outputs hookSpecificOutput with updatedInput when limit injection is active
 *
 * Note: permissionDecision: 'allow' auto-approves the Read call without user
 * confirmation when limit injection is active.
 */

import fs from 'node:fs';
import path from 'node:path';
import { atomicWriteFileSync, readStdin, runHook, telemetryDir as TELEMETRY_DIR } from '../lib/hook-base.mjs';

// Dynamic file size threshold for auto-injecting limit.
// ~200 lines at ~40 bytes/line. Any file larger gets limit: 200.
// Note: analytics (dispatch.mjs) captures pre-intervention state, so the
// unbounded-read rate in session reports overcounts — reads that get limit
// injected here still appear as "unbounded" in the events stream.
const SIZE_THRESHOLD_BYTES = 8_000;
// Lines to read when auto-limiting a large file. Matches Claude Code's own
// large-file Read default — enough for orientation; re-read with offset for more.
const DEFAULT_LIMIT = 200;
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

export function shouldInjectLimit(toolInput) {
  if (!toolInput?.file_path) return null;
  if (toolInput.offset != null || toolInput.limit != null) return null;
  try {
    const stat = fs.statSync(toolInput.file_path);
    if (stat.size > SIZE_THRESHOLD_BYTES) {
      return {
        updatedInput: { ...toolInput, limit: DEFAULT_LIMIT },
        sizeBytes: stat.size,
      };
    }
  } catch {
    // File doesn't exist or can't be accessed — let Read handle the error
  }
  return null;
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
 * shouldInjectLimit (above) only ever acts when the caller supplies NO offset/limit at all —
 * confirmed live (tempdoc 727 derisk) that an agent-specified offset/limit sails straight
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
  if (!hasExplicitRange) return null; // shouldInjectLimit already owns the no-range case

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

    // --- Edit handling (track only, no warning) ---
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

    // Check if we need to inject a limit for large files
    const injection = shouldInjectLimit(toolInput);

    // Tempdoc 727 F-7c: the caller supplied its own offset/limit (shouldInjectLimit always
    // defers in that case), but that requested slice may itself still be too large.
    const explicitCap = injection ? null : shouldCapExplicitLimit(toolInput);
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

    // Only emit output if we're injecting a limit
    if (injection) {
      const shortPath = (toolInput.file_path || '').split(/[/\\]/).slice(-2).join('/');
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: {
          hookEventName: 'PreToolUse',
          permissionDecision: 'allow',
          updatedInput: injection.updatedInput,
          additionalContext:
            `Note: Read on ${shortPath} (${injection.sizeBytes} bytes) was auto-limited to ${DEFAULT_LIMIT} lines. ` +
            `Re-read with offset to access lines beyond ${DEFAULT_LIMIT} if needed.`,
        },
      }));
    }
    // No output = no modification
  } catch {
    // Parse failure — no modification
  }
}

runHook(import.meta.url, main);
