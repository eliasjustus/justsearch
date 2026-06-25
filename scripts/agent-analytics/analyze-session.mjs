#!/usr/bin/env node

/**
 * Session analyzer for agent telemetry data.
 *
 * Reads events from tmp/agent-telemetry/events.ndjson and generates
 * per-session reports matching the schema in tempdoc-118.
 *
 * Usage:
 *   node analyze-session.mjs --list              # List available sessions
 *   node analyze-session.mjs --session-id <id>   # Analyze specific session
 *   node analyze-session.mjs --all               # Analyze all sessions
 */

import fs from 'node:fs';
import path from 'node:path';
import { loadEventsFromSource, groupBySession, TELEMETRY_DIR, EVENTS_FILE, SESSIONS_DIR, repoRoot } from './lib/telemetry-io.mjs';

// File operation patterns in bash commands (word boundaries via regex).
// Only flags BARE commands — piped (|), redirected (> <), or chained (&& || ;)
// commands are legitimate shell usage, not tool misuse.
// Commands prefixed with `git` are excluded separately (git grep, git log --grep, etc.)
const BASH_FILE_OP_PATTERNS = [
  /\bcat\s/, /\bhead\s/, /\btail\s/, /\bgrep\s/, /\bfind\s/,
  /\becho\s*>/, /\bsed\s/, /\bawk\s/
];
const PIPE_SPLIT = /\s*(?:\|\||&&|;|\|)\s*/;
const GIT_PREFIX = /^\s*git\s/;

/**
 * For simple cat/head/tail commands, extract the target file path (last non-flag token).
 * Returns null for ambiguous commands (grep, find, sed, awk) where the path position varies.
 */
function extractBashTarget(firstSegment) {
  const tokens = firstSegment.trim().split(/\s+/);
  if (!/^(cat|head|tail)\b/.test(tokens[0])) return null;
  for (let i = tokens.length - 1; i > 0; i--) {
    if (!tokens[i].startsWith('-')) return tokens[i];
  }
  return null;
}

function parseArgs() {
  const args = process.argv.slice(2);
  const result = { list: false, all: false, sessionId: null, source: 'ndjson' };

  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--list') {
      result.list = true;
    } else if (args[i] === '--all') {
      result.all = true;
    } else if (args[i] === '--session-id' && args[i + 1]) {
      result.sessionId = args[++i];
    } else if (args[i] === '--source' && args[i + 1]) {
      result.source = args[++i]; // 'ndjson' (default) | 'otlp' — tempdoc 622 Layer A seam
    }
  }

  return result;
}

/**
 * Normalize an absolute file path to a repo-relative path.
 * Falls back to basename if the path doesn't start with repoRoot.
 */
function relPath(filePath) {
  if (!filePath) return null;
  // Normalize separators to forward slashes for comparison
  const norm = filePath.replace(/\\/g, '/');
  const rootNorm = repoRoot.replace(/\\/g, '/');
  if (norm.startsWith(rootNorm + '/')) {
    return norm.substring(rootNorm.length + 1);
  }
  if (norm.startsWith(rootNorm)) {
    return norm.substring(rootNorm.length);
  }
  // Not under repo root — return as-is
  return norm;
}

/**
 * Parse a subagent JSONL transcript and extract tool_use blocks.
 * Transcript format is undocumented — parse defensively.
 */
function parseSubagentTranscript(transcriptPath) {
  const toolUses = [];

  try {
    if (!fs.existsSync(transcriptPath)) {
      return { toolUses: [], error: 'file_not_found' };
    }

    const content = fs.readFileSync(transcriptPath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let entry;
      try {
        entry = JSON.parse(line);
      } catch {
        continue;
      }

      if (entry.type !== 'assistant') continue;
      const contentBlocks = entry.message?.content;
      if (!Array.isArray(contentBlocks)) continue;

      for (const block of contentBlocks) {
        if (block.type === 'tool_use' && typeof block.name === 'string') {
          toolUses.push({ name: block.name, input: block.input ?? {} });
        }
      }
    }

    return { toolUses, error: null };
  } catch (err) {
    return { toolUses: [], error: err.message };
  }
}

/**
 * Analyze tool calls from subagent transcripts.
 * Collects transcript paths from subagent_stop events, parses each,
 * and aggregates tool call counts and file read patterns.
 */
function analyzeSubagentToolCalls(subagentStopEvents) {
  const byType = {};
  let total = 0;
  let transcriptsFound = 0;
  let transcriptsMissing = 0;
  const fileReadMap = new Map();

  const seen = new Set();
  let noPathCount = 0;
  // Subagent outcome classification (tempdoc 285, step 3)
  let outcomeSuccess = 0;
  let outcomeFailure = 0;
  let outcomePartial = 0;

  for (const event of subagentStopEvents) {
    const tPath = event.agent_transcript_path;
    if (!tPath) { noPathCount++; continue; }
    if (seen.has(tPath)) continue;
    seen.add(tPath);

    const { toolUses, error } = parseSubagentTranscript(tPath);

    if (error) {
      transcriptsMissing++;
      continue;
    }

    transcriptsFound++;

    // Classify subagent outcome from transcript tool calls
    let subHasEdits = false;
    let subToolCount = 0;

    for (const tu of toolUses) {
      total++;
      subToolCount++;
      byType[tu.name] = (byType[tu.name] ?? 0) + 1;

      if (tu.name === 'Edit' || tu.name === 'Write' || tu.name === 'NotebookEdit') {
        subHasEdits = true;
      }

      if (tu.name === 'Read') {
        const filePath = tu.input?.file_path;
        const file = relPath(filePath) ?? 'unknown';
        const isUnbounded = tu.input && tu.input.offset == null && tu.input.limit == null;

        if (!fileReadMap.has(file)) {
          fileReadMap.set(file, { count: 0, unbounded: 0, unbounded_large: 0 });
        }
        const entry = fileReadMap.get(file);
        entry.count++;
        if (isUnbounded) {
          entry.unbounded++;
          try {
            const absPath = path.resolve(repoRoot, file);
            if (fs.statSync(absPath).size > 12_000) entry.unbounded_large++;
          } catch { /* file may have been deleted */ }
        }
      }
    }

    // Classify subagent outcome:
    // - 0 tool calls = failure (agent did nothing)
    // - Read-only agent types (Explore, Plan, claude-code-guide) with tool calls = success
    //   (these agents are not expected to produce edits)
    // - General-purpose agents with edits = success
    // - General-purpose agents with tool calls but no edits = partial
    const agentType = event.agent_type ?? 'unknown';
    const isReadOnlyType = ['Explore', 'Plan', 'claude-code-guide'].includes(agentType);
    if (subToolCount === 0) {
      outcomeFailure++;
    } else if (subHasEdits || isReadOnlyType) {
      outcomeSuccess++;
    } else {
      outcomePartial++;
    }
  }

  const fileReadsTotal = byType['Read'] ?? 0;
  const fileReadsUnbounded = [...fileReadMap.values()].reduce((sum, e) => sum + e.unbounded, 0);
  const fileReadsUnboundedLarge = [...fileReadMap.values()].reduce((sum, e) => sum + e.unbounded_large, 0);
  const fileReadsByFile = [...fileReadMap.entries()]
    .map(([file, data]) => ({ file, count: data.count, unbounded: data.unbounded }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 20);

  const totalTranscripts = transcriptsFound + transcriptsMissing;
  const transcriptCoverage = totalTranscripts > 0
    ? Math.round((transcriptsFound / totalTranscripts) * 1000) / 1000
    : null;

  return {
    total,
    by_type: byType,
    transcripts_found: transcriptsFound,
    transcripts_missing: transcriptsMissing,
    transcripts_no_path: noPathCount,
    transcript_coverage: transcriptCoverage,
    file_reads: {
      total: fileReadsTotal,
      unbounded_count: fileReadsUnbounded,
      unbounded_large_count: fileReadsUnboundedLarge,
      by_file: fileReadsByFile,
    },
    outcomes: {
      success: outcomeSuccess,
      failure: outcomeFailure,
      partial: outcomePartial,
    },
  };
}

/**
 * Compare hook event count against the session's main JSONL transcript
 * to estimate what fraction of tool calls our hooks captured.
 */
function estimateDataCompleteness(sessionId, events, hookToolCallCount) {
  // Find transcript path from session_start event
  const startEvent = events.find(e => e.event === 'session_start' && e.transcript_path);
  let transcriptPath = startEvent?.transcript_path ?? null;

  // Fallback: infer from cwd-based project hash
  if (!transcriptPath) {
    const homeDir = process.env.USERPROFILE || process.env.HOME;
    const cwd = startEvent?.cwd ?? repoRoot;
    if (homeDir && cwd) {
      // Claude Code project hash: drive letter + path with separators replaced by --
      const normalized = cwd.replace(/\\/g, '/').replace(/^\//, '');
      const projectHash = normalized.replace(/[/:]/g, '-');
      transcriptPath = path.join(homeDir, '.claude', 'projects', projectHash, `${sessionId}.jsonl`);
    }
  }

  if (!transcriptPath) {
    return { available: false, reason: 'no_transcript_path' };
  }

  try {
    if (!fs.existsSync(transcriptPath)) {
      return { available: false, reason: 'transcript_not_found' };
    }

    // Determine the time window hooks were active (first to last event)
    const timestamps = events.map(e => e.ts).filter(Boolean).sort();
    const windowStart = timestamps[0] ?? null;
    const windowEnd = timestamps[timestamps.length - 1] ?? null;
    // Use numeric comparison to avoid ISO format mismatch (Z vs +00:00)
    const windowStartMs = windowStart ? Date.parse(windowStart) : null;
    const windowEndMs = windowEnd ? Date.parse(windowEnd) : null;

    const content = fs.readFileSync(transcriptPath, 'utf8');
    let transcriptTotal = 0;
    let transcriptInWindow = 0;

    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      let entry;
      try {
        entry = JSON.parse(line);
      } catch {
        continue;
      }

      if (entry.type !== 'assistant') continue;

      const contentBlocks = entry.message?.content;
      if (!Array.isArray(contentBlocks)) continue;

      let toolCount = 0;
      for (const block of contentBlocks) {
        if (block.type === 'tool_use') toolCount++;
      }
      if (toolCount === 0) continue;

      transcriptTotal += toolCount;

      // Time-window filter: only count tool calls during the hook-active period
      const ts = entry.timestamp;
      const tsMs = ts ? Date.parse(ts) : NaN;
      if (windowStartMs && windowEndMs && !isNaN(tsMs) && tsMs >= windowStartMs && tsMs <= windowEndMs) {
        transcriptInWindow += toolCount;
      }
    }

    const captureRate = transcriptInWindow > 0
      ? Math.round((hookToolCallCount / transcriptInWindow) * 1000) / 1000
      : null;

    return {
      available: true,
      transcript_total: transcriptTotal,
      transcript_in_window: transcriptInWindow,
      hook_tool_calls: hookToolCallCount,
      capture_rate: captureRate,
      window_start: windowStart,
      window_end: windowEnd,
    };
  } catch (err) {
    return { available: false, reason: 'read_error', error: err.message };
  }
}

// --- Task-type classification (Tier 2b #16 from tempdoc 118) ---

/**
 * Classify session task type from git commits within the session time window.
 * Uses Conventional Commits prefix (feat/fix/refactor/docs/test/chore) with
 * a 60% dominance threshold. Returns structured object with type, count, and
 * prefix breakdown for future within-type normalization.
 */
// --- Phase 1 enrichment functions (Items 2, 3, 7, 8 from tempdoc 118) ---

/**
 * Detect files re-read after compaction events (context loss measurement).
 * Splits event stream at pre_compact boundaries into adjacent segments,
 * then intersects file sets between each pair.
 */
function analyzeCompactionRereads(events) {
  const compactIndices = [];
  for (let i = 0; i < events.length; i++) {
    if (events[i].event === 'pre_compact') compactIndices.push(i);
  }

  if (compactIndices.length === 0) {
    return { total_rereads: 0, by_compaction: [] };
  }

  // Build segment boundaries: [0, compact0, compact1, ..., end]
  const boundaries = [0, ...compactIndices, events.length];
  const byCompaction = [];
  let totalRereads = 0;

  for (let k = 0; k < compactIndices.length; k++) {
    const segBefore = events.slice(boundaries[k], boundaries[k + 1]);
    const segAfter = events.slice(boundaries[k + 1], boundaries[k + 2]);

    const filesBefore = new Set();
    for (const e of segBefore) {
      if (e.event === 'pre_tool_use' && e.tool_name === 'Read') {
        const file = relPath(e.input_summary?.file_path);
        if (file) filesBefore.add(file);
      }
    }

    const filesAfter = new Set();
    for (const e of segAfter) {
      if (e.event === 'pre_tool_use' && e.tool_name === 'Read') {
        const file = relPath(e.input_summary?.file_path);
        if (file) filesAfter.add(file);
      }
    }

    const rereadFiles = [...filesBefore].filter(f => filesAfter.has(f)).sort();
    totalRereads += rereadFiles.length;

    const compactEvent = events[compactIndices[k]];
    byCompaction.push({
      compaction_index: k,
      compaction_ts: compactEvent.ts,
      trigger: compactEvent.trigger ?? 'unknown',
      files_reread: rereadFiles,
      reread_count: rereadFiles.length,
    });
  }

  return { total_rereads: totalRereads, by_compaction: byCompaction };
}

/**
 * Detect tool failure cascades — sequences of 3+ failures within 60 seconds.
 * Excludes user interrupts. Non-overlapping cascades (same pattern as
 * rapid-reedit detection in score-session.mjs).
 */
function detectFailureCascades(events) {
  const CASCADE_WINDOW_MS = 60_000;
  const MIN_CASCADE_LENGTH = 3;

  const failures = events
    .filter(e => e.event === 'post_tool_use_failure' && !e.is_interrupt)
    .sort((a, b) => a.ts.localeCompare(b.ts));

  if (failures.length < MIN_CASCADE_LENGTH) {
    return { count: 0, cascades: [] };
  }

  const times = failures.map(f => new Date(f.ts).getTime());
  const cascades = [];
  let i = 0;

  while (i < times.length) {
    let j = i + 1;
    while (j < times.length && times[j] - times[i] <= CASCADE_WINDOW_MS) j++;
    if (j - i >= MIN_CASCADE_LENGTH) {
      const cascadeFailures = failures.slice(i, j);
      cascades.push({
        start_ts: cascadeFailures[0].ts,
        end_ts: cascadeFailures[cascadeFailures.length - 1].ts,
        failure_count: cascadeFailures.length,
        tools_involved: cascadeFailures.map(f => f.tool_name),
        duration_seconds: Math.round((times[j - 1] - times[i]) / 1000),
      });
      i = j;
    } else {
      i++;
    }
  }

  return { count: cascades.length, cascades };
}

/**
 * Analyze context efficiency — informational metric that measures
 * how much reading is exploration vs redundant re-reading.
 *
 * Design: first reads are free. Re-reads of unchanged files are weighted
 * by proximity to the edit surface. Avoids penalizing good exploration
 * (confirmed by ContextBench/LoCoBench-Agent research).
 *
 * NOT used in scoring — informational only.
 */
function analyzeContextEfficiency(events, fileEditMap) {
  const readTimeline = []; // { file, ts }
  for (const e of events) {
    if (e.event === 'pre_tool_use' && e.tool_name === 'Read') {
      const file = relPath(e.input_summary?.file_path);
      if (file) readTimeline.push({ file, ts: e.ts, tsMs: new Date(e.ts).getTime() });
    }
  }

  if (readTimeline.length === 0) {
    return { first_reads: 0, rereads_changed: 0, rereads_unchanged: 0,
      proximity: { same_dir: 0, same_module: 0, unrelated: 0 },
      score_informational: 1.0 };
  }

  // Build edit timeline for quick "was file edited between t1 and t2?" lookups
  const editTimelines = new Map(); // file -> sorted array of tsMs
  for (const [file, data] of fileEditMap) {
    editTimelines.set(file, data.timestamps.map(t => new Date(t).getTime()).sort((a, b) => a - b));
  }

  function wasEditedBetween(file, afterMs, beforeMs) {
    const times = editTimelines.get(file);
    if (!times) return false;
    return times.some(t => t > afterMs && t < beforeMs);
  }

  // Collect edited file directories and modules for proximity matching
  const editedDirs = new Set();
  const editedModules = new Set();
  for (const file of fileEditMap.keys()) {
    const dir = file.replace(/\\/g, '/').replace(/\/[^/]+$/, '');
    editedDirs.add(dir);
    const moduleMatch = file.replace(/\\/g, '/').match(/^(modules\/[^/]+)/);
    if (moduleMatch) editedModules.add(moduleMatch[1]);
  }

  function getProximity(file) {
    const norm = file.replace(/\\/g, '/');
    const dir = norm.replace(/\/[^/]+$/, '');
    if (editedDirs.has(dir)) return 'same_dir';
    const moduleMatch = norm.match(/^(modules\/[^/]+)/);
    if (moduleMatch && editedModules.has(moduleMatch[1])) return 'same_module';
    return 'unrelated';
  }

  const PROXIMITY_WEIGHTS = { same_dir: 0.2, same_module: 0.5, unrelated: 1.0 };

  const lastReadMs = new Map(); // file -> tsMs of previous read
  let firstReads = 0;
  let rereadChanged = 0;
  let rereadUnchanged = 0;
  const proximity = { same_dir: 0, same_module: 0, unrelated: 0 };
  let weightedWaste = 0;

  for (const { file, tsMs } of readTimeline) {
    const prevMs = lastReadMs.get(file);
    lastReadMs.set(file, tsMs);

    if (prevMs == null) {
      firstReads++;
      continue;
    }

    // Re-read — was the file edited between previous read and this one?
    if (wasEditedBetween(file, prevMs, tsMs)) {
      rereadChanged++;
    } else {
      rereadUnchanged++;
      const prox = getProximity(file);
      proximity[prox]++;
      weightedWaste += PROXIMITY_WEIGHTS[prox];
    }
  }

  const totalReads = readTimeline.length;
  const score = totalReads > 0
    ? Math.round((1 - weightedWaste / totalReads) * 1000) / 1000
    : 1.0;

  return {
    first_reads: firstReads,
    rereads_changed: rereadChanged,
    rereads_unchanged: rereadUnchanged,
    proximity,
    score_informational: Math.max(0, Math.min(1, score)),
  };
}

/**
 * Classify re-reads as structural (justified) or wasteful.
 *
 * Structural: precedes an Edit of same file (within 120s), or follows compaction.
 * Wasteful: >5 min gap since last read, no intervening edit.
 * Ambiguous: neither clear category — classified as structural (benefit of doubt).
 *
 * NOT used in scoring — informational only.
 */
/**
 * Classify file reads by documentation category.
 *
 * Categories:
 *   canonical_docs — docs/explanation/, docs/reference/, docs/how-to/, docs/decisions/
 *   tempdocs       — docs/tempdocs/
 *   claude_rules   — CLAUDE.md, .claude/rules/
 *
 * Returns counts, unique file counts, and a per-file breakdown for doc reads.
 */
function classifyDocReads(fileReadMap) {
  const CANONICAL_PREFIXES = [
    'docs/explanation/', 'docs/reference/', 'docs/how-to/', 'docs/decisions/',
  ];
  const categories = {
    canonical_docs: { reads: 0, files: new Set(), items: [] },
    tempdocs:       { reads: 0, files: new Set(), items: [] },
    claude_rules:   { reads: 0, files: new Set(), items: [] },
  };

  for (const [file, data] of fileReadMap) {
    let cat = null;
    if (CANONICAL_PREFIXES.some(p => file.startsWith(p))) {
      cat = 'canonical_docs';
    } else if (file.startsWith('docs/tempdocs/')) {
      cat = 'tempdocs';
    } else if (file === 'CLAUDE.md' || file.startsWith('.claude/rules/')) {
      cat = 'claude_rules';
    }
    if (!cat) continue;

    const bucket = categories[cat];
    bucket.reads += data.count;
    bucket.files.add(file);
    bucket.items.push({ file, count: data.count });
  }

  const total = categories.canonical_docs.reads
    + categories.tempdocs.reads
    + categories.claude_rules.reads;

  const result = { total };
  for (const [cat, bucket] of Object.entries(categories)) {
    result[cat] = {
      reads: bucket.reads,
      unique_files: bucket.files.size,
      by_file: bucket.items.sort((a, b) => b.count - a.count).slice(0, 10),
    };
  }
  return result;
}

function classifyReadRedundancy(events, fileEditMap, compactEvents) {
  const EDIT_WINDOW_MS = 120_000;
  const WASTEFUL_GAP_MS = 5 * 60_000;

  const compactTimes = compactEvents.map(e => new Date(e.ts).getTime()).sort((a, b) => a - b);

  // Build edit timeline per file
  const editTimelines = new Map();
  for (const [file, data] of fileEditMap) {
    editTimelines.set(file, data.timestamps.map(t => new Date(t).getTime()).sort((a, b) => a - b));
  }

  function hasEditWithin(file, afterMs, windowMs) {
    const times = editTimelines.get(file);
    if (!times) return false;
    return times.some(t => t > afterMs && t <= afterMs + windowMs);
  }

  function hasEditBetween(file, fromMs, toMs) {
    const times = editTimelines.get(file);
    if (!times) return false;
    return times.some(t => t > fromMs && t < toMs);
  }

  function hasCompactionBetween(fromMs, toMs) {
    return compactTimes.some(t => t > fromMs && t < toMs);
  }

  // Walk through Read events, classify re-reads
  const lastReadMs = new Map(); // file -> tsMs
  const reasons = { precedes_edit: 0, post_compaction: 0, ambiguous: 0, long_gap_no_edit: 0 };
  let structural = 0;
  let wasteful = 0;
  let totalRereads = 0;

  for (const e of events) {
    if (e.event !== 'pre_tool_use' || e.tool_name !== 'Read') continue;
    const file = relPath(e.input_summary?.file_path);
    if (!file) continue;

    const tsMs = new Date(e.ts).getTime();
    const prevMs = lastReadMs.get(file);
    lastReadMs.set(file, tsMs);

    if (prevMs == null) continue; // First read — not a re-read
    totalRereads++;

    // Classify
    if (hasEditWithin(file, tsMs, EDIT_WINDOW_MS)) {
      reasons.precedes_edit++;
      structural++;
    } else if (hasCompactionBetween(prevMs, tsMs)) {
      reasons.post_compaction++;
      structural++;
    } else if (tsMs - prevMs > WASTEFUL_GAP_MS && !hasEditBetween(file, prevMs, tsMs)) {
      reasons.long_gap_no_edit++;
      wasteful++;
    } else {
      reasons.ambiguous++;
      structural++; // Benefit of doubt
    }
  }

  return { total_rereads: totalRereads, structural, wasteful, by_reason: reasons };
}

function analyzeSession(sessionId, events) {
  // Sort by timestamp (copy to avoid mutating input)
  events = [...events].sort((a, b) => a.ts.localeCompare(b.ts));

  // Find session metadata (use first start for timing, last for model in case of resumes)
  const startEvents = events.filter(e => e.event === 'session_start');
  const startEvent = startEvents[0]; // First for timing
  const lastStartEvent = startEvents[startEvents.length - 1] ?? startEvent; // Last for model
  const endEvent = events.find(e => e.event === 'session_end');

  const startedAt = startEvent?.ts ?? events[0]?.ts ?? null;
  const endedAt = endEvent?.ts ?? events[events.length - 1]?.ts ?? null;

  let durationSeconds = null;
  if (startedAt && endedAt) {
    durationSeconds = Math.round((new Date(endedAt) - new Date(startedAt)) / 1000);
  }

  // Tool calls aggregation
  const toolCallEvents = events.filter(e => e.event === 'pre_tool_use');
  const toolCallsByType = {};
  for (const e of toolCallEvents) {
    const tool = e.tool_name ?? 'unknown';
    toolCallsByType[tool] = (toolCallsByType[tool] ?? 0) + 1;
  }

  const failureEvents = events.filter(e => e.event === 'post_tool_use_failure');

  // File reads analysis
  const readEvents = toolCallEvents.filter(e => e.tool_name === 'Read');
  const fileReadMap = new Map(); // file -> { count, unbounded, unbounded_large }
  const SIZE_THRESHOLD = 12_000; // Match intervene.mjs threshold

  for (const e of readEvents) {
    const filePath = e.input_summary?.file_path;
    const file = relPath(filePath) ?? 'unknown';
    // Only count as unbounded if we have input_summary but no offset/limit
    const summary = e.input_summary;
    const isUnbounded = summary && !summary.has_offset && !summary.has_limit;

    if (!fileReadMap.has(file)) {
      fileReadMap.set(file, { count: 0, unbounded: 0, unbounded_large: 0 });
    }
    const entry = fileReadMap.get(file);
    entry.count++;
    if (isUnbounded) {
      entry.unbounded++;
      try {
        const absPath = path.resolve(repoRoot, file);
        if (fs.statSync(absPath).size > SIZE_THRESHOLD) entry.unbounded_large++;
      } catch { /* file may have been deleted */ }
    }
  }

  const fileReadsTotal = readEvents.length;
  const fileReadsUnbounded = [...fileReadMap.values()].reduce((sum, e) => sum + e.unbounded, 0);
  const fileReadsUnboundedLarge = [...fileReadMap.values()].reduce((sum, e) => sum + e.unbounded_large, 0);
  const fileReadsByFile = [...fileReadMap.entries()]
    .map(([file, data]) => ({ file, count: data.count, unbounded: data.unbounded }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 20); // Top 20

  // Documentation reads categorization
  const docReads = classifyDocReads(fileReadMap);

  // File edits analysis
  const editEvents = toolCallEvents.filter(e => e.tool_name === 'Edit');
  const fileEditMap = new Map(); // file -> { count, timestamps }

  for (const e of editEvents) {
    const filePath = e.input_summary?.file_path;
    const file = relPath(filePath) ?? 'unknown';

    if (!fileEditMap.has(file)) {
      fileEditMap.set(file, { count: 0, timestamps: [] });
    }
    const entry = fileEditMap.get(file);
    entry.count++;
    entry.timestamps.push(e.ts);
  }

  const fileEditsByFile = [...fileEditMap.entries()]
    .map(([file, data]) => ({ file, count: data.count, timestamps: data.timestamps }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 20); // Top 20

  // Compactions
  const compactEvents = events.filter(e => e.event === 'pre_compact');
  const compactionTriggers = compactEvents.map(e => e.trigger ?? 'unknown');

  // Subagents - use unique agent_ids from both start and stop events
  // (handles case where start events were missed during hook setup)
  const subagentStartEvents = events.filter(e => e.event === 'subagent_start');
  const subagentStopEvents = events.filter(e => e.event === 'subagent_stop');
  const allSubagentEvents = [...subagentStartEvents, ...subagentStopEvents];
  const uniqueAgentIds = new Set(allSubagentEvents.map(e => e.agent_id).filter(Boolean));

  const subagentsByType = {};
  // Prefer type from start event, fall back to stop event
  for (const agentId of uniqueAgentIds) {
    const startEvent = subagentStartEvents.find(e => e.agent_id === agentId);
    const stopEvent = subagentStopEvents.find(e => e.agent_id === agentId);
    const rawType = startEvent?.agent_type ?? stopEvent?.agent_type ?? null;
    const agentType = rawType === '' ? 'task' : (rawType ?? 'unknown');
    subagentsByType[agentType] = (subagentsByType[agentType] ?? 0) + 1;
  }

  // Subagent tool calls from transcripts
  const subagentToolCalls = analyzeSubagentToolCalls(subagentStopEvents);

  // Hook failures (tempdoc 592): hooks whose main() threw at runtime, recorded by
  // runHook's no-silent-downgrade contract. A blocking-hook failure means a guard
  // was OFF for that call — the highest-signal entry in a session report.
  const hookFailureEvents = events.filter(e => e.event === 'hook_failure');
  const hookFailuresByHook = {};
  for (const e of hookFailureEvents) {
    const key = e.hookId ?? 'unknown';
    hookFailuresByHook[key] = (hookFailuresByHook[key] ?? 0) + 1;
  }
  const blockingHookFailures = hookFailureEvents.filter(e => e.role === 'blocking').length;

  // Data completeness estimation
  const dataCompleteness = estimateDataCompleteness(sessionId, events, toolCallEvents.length);

  // Search patterns (Grep and Glob)
  const searchEvents = toolCallEvents.filter(e => e.tool_name === 'Grep' || e.tool_name === 'Glob');
  const searchPatternMap = new Map(); // key -> { tool, pattern, path, count }

  for (const e of searchEvents) {
    const pattern = e.input_summary?.pattern ?? '';
    const searchPath = relPath(e.input_summary?.path) ?? e.input_summary?.path ?? '';
    const key = `${e.tool_name}:${pattern}:${searchPath}`;

    if (!searchPatternMap.has(key)) {
      searchPatternMap.set(key, {
        tool: e.tool_name,
        pattern,
        path: searchPath,
        count: 0,
      });
    }
    searchPatternMap.get(key).count++;
  }

  const searchPatterns = [...searchPatternMap.values()]
    .filter(p => p.count > 1) // Only duplicates
    .sort((a, b) => b.count - a.count)
    .slice(0, 20); // Top 20

  // Bash commands
  const bashEvents = toolCallEvents.filter(e => e.tool_name === 'Bash');
  let bashFileOpCount = 0;
  let bashBuildCount = 0;
  // Build a set of all files the agent actually Read or Edited, for exploratory detection.
  const accessedFiles = new Set([...fileReadMap.keys(), ...fileEditMap.keys()]);
  let bashExploratoryCount = 0;
  for (const e of bashEvents) {
    const cmd = e.input_summary?.command ?? '';
    if (/gradlew/i.test(cmd)) bashBuildCount++;
    // Test the first segment of piped/chained commands against file-op patterns.
    // In `cat file | grep x`, cat is the source (misuse). In `./gradlew | tee`, gradlew is not.
    // Redirects (> <) are NOT split — they're part of the command syntax (e.g. `echo x > file`).
    const firstSegment = cmd.split(PIPE_SPLIT)[0];
    if (GIT_PREFIX.test(firstSegment)) continue;
    if (BASH_FILE_OP_PATTERNS.some(pattern => pattern.test(firstSegment))) {
      bashFileOpCount++;
      // For cat/head/tail: check if the target file was ever Read or Edited.
      // Counts as "exploratory" if it was accessed only via bash (never with proper tools).
      const target = extractBashTarget(firstSegment);
      if (target !== null) {
        const normTarget = relPath(target) ?? target.replace(/\\/g, '/');
        if (!accessedFiles.has(normTarget)) {
          bashExploratoryCount++;
        }
      }
    }
  }

  // Count failed builds from post_tool_use_failure events
  let failedBuildCount = 0;
  for (const e of failureEvents) {
    if (e.tool_name === 'Bash') {
      const cmd = e.input_summary?.command ?? '';
      if (/gradlew/i.test(cmd)) failedBuildCount++;
    }
  }

  return {
    schema: 'agent-session-report.v1',
    session_id: sessionId,
    started_at: startedAt,
    ended_at: endedAt,
    duration_seconds: durationSeconds,
    model: lastStartEvent?.model ?? null,

    tool_calls: {
      total: toolCallEvents.length,
      by_type: toolCallsByType,
      failure_count: failureEvents.length,
    },

    file_reads: {
      total: fileReadsTotal,
      unique_files: fileReadMap.size,
      unbounded_count: fileReadsUnbounded,
      unbounded_large_count: fileReadsUnboundedLarge,
      by_file: fileReadsByFile,
    },

    doc_reads: docReads,

    file_edits: {
      total: editEvents.length,
      by_file: fileEditsByFile,
    },

    compactions: {
      count: compactEvents.length,
      triggers: compactionTriggers,
    },

    // tempdoc 592 — hook-execution failures recorded by runHook's contract.
    hook_failures: {
      total: hookFailureEvents.length,
      blocking: blockingHookFailures,
      by_hook: hookFailuresByHook,
    },

    subagents: {
      count: uniqueAgentIds.size,
      by_type: subagentsByType,
    },

    subagent_tool_calls: subagentToolCalls,

    data_completeness: dataCompleteness,

    search_patterns: searchPatterns,

    bash_commands: {
      total: bashEvents.length,
      file_op_count: bashFileOpCount,
      build_count: bashBuildCount,
      failed_build_count: failedBuildCount,
      bash_exploratory_count: bashExploratoryCount,
      bash_exploratory_pct: bashFileOpCount > 0
        ? Math.round((bashExploratoryCount / bashFileOpCount) * 1000) / 1000
        : 0,
    },

    // First user prompt excerpt for classification (tempdoc 276 G2)
    first_prompt: events.find(e => e.event === 'user_prompt_submit' && e.prompt_excerpt)?.prompt_excerpt ?? null,

    // Phase 1 enrichments (tempdoc 118 roadmap)
    compaction_rereads: analyzeCompactionRereads(events),
    failure_cascades: detectFailureCascades(events),
    context_efficiency: analyzeContextEfficiency(events, fileEditMap),
    read_redundancy: classifyReadRedundancy(events, fileEditMap, compactEvents),
  };
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, { recursive: true });
}

/**
 * Write a session report, but only if it has at least as many tool calls as
 * the existing report. This prevents event rotation from silently degrading
 * reports: when old events are lost, re-analysis produces a less complete
 * report that would overwrite the higher-fidelity original.
 *
 * Returns { path, skipped } — skipped is true if the write was suppressed.
 */
function writeReport(report, { force = false } = {}) {
  const dir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
  ensureDir(dir);
  const filePath = path.join(dir, `${report.session_id}.json`);

  if (!force) {
    try {
      const existing = JSON.parse(fs.readFileSync(filePath, 'utf8'));
      const oldTools = existing.tool_calls?.total ?? 0;
      const newTools = report.tool_calls?.total ?? 0;
      if (oldTools > newTools && oldTools > 0) {
        return { path: filePath, skipped: true, oldTools, newTools };
      }
    } catch { /* no existing report — write freely */ }
  }

  fs.writeFileSync(filePath, JSON.stringify(report, null, 2) + '\n', 'utf8');
  return { path: filePath, skipped: false };
}

function listSessions(sessionMap) {
  console.log(`\nFound ${sessionMap.size} sessions:\n`);

  const summaries = [...sessionMap.entries()].map(([sid, events]) => {
    const startEvent = events.find(e => e.event === 'session_start');
    const toolCount = events.filter(e => e.event === 'pre_tool_use').length;
    return {
      session_id: sid,
      started_at: startEvent?.ts ?? events[0]?.ts ?? 'unknown',
      model: startEvent?.model ?? 'unknown',
      event_count: events.length,
      tool_calls: toolCount,
    };
  });

  // Sort by start time descending
  summaries.sort((a, b) => b.started_at.localeCompare(a.started_at));

  for (const s of summaries) {
    console.log(`  ${s.session_id}`);
    console.log(`    Started: ${s.started_at}`);
    console.log(`    Model: ${s.model}`);
    console.log(`    Events: ${s.event_count}, Tool calls: ${s.tool_calls}`);
    console.log();
  }
}

function main() {
  const args = parseArgs();

  if (!args.list && !args.all && !args.sessionId) {
    console.log('Usage:');
    console.log('  node analyze-session.mjs --list              List available sessions');
    console.log('  node analyze-session.mjs --session-id <id>   Analyze specific session');
    console.log('  node analyze-session.mjs --all               Analyze all sessions');
    process.exit(1);
  }

  console.log(`Loading events (source: ${args.source})...`);
  const events = loadEventsFromSource(args.source);
  console.log(`Loaded ${events.length} events`);

  const sessionMap = groupBySession(events);
  console.log(`Found ${sessionMap.size} sessions`);

  if (args.list) {
    listSessions(sessionMap);
    return;
  }

  if (args.sessionId) {
    if (!sessionMap.has(args.sessionId)) {
      console.error(`Session not found: ${args.sessionId}`);
      process.exit(1);
    }
    const report = analyzeSession(args.sessionId, sessionMap.get(args.sessionId));
    const result = writeReport(report, { force: true }); // explicit session → always write
    console.log(`\nReport written to: ${result.path}`);
    console.log(JSON.stringify(report, null, 2));
    return;
  }

  if (args.all) {
    let count = 0;
    let skipped = 0;
    for (const [sid, sessionEvents] of sessionMap) {
      const report = analyzeSession(sid, sessionEvents);
      const result = writeReport(report);
      if (result.skipped) {
        console.log(`  SKIP ${sid.substring(0, 8)}: existing report has ${result.oldTools} tools, new has ${result.newTools} (partial data from event rotation)`);
        skipped++;
      }
      count++;
    }
    const outDir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
    console.log(`\nGenerated ${count - skipped} session reports in ${outDir}`);
    if (skipped > 0) {
      console.log(`Skipped ${skipped} sessions (existing reports have more complete data)`);
    }
  }
}

main();
