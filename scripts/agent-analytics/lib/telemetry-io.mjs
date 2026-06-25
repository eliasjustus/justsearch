/**
 * Shared I/O utilities for the agent-analytics pipeline.
 *
 * Deduplicates event loading, NDJSON parsing, and session grouping
 * used by analyze-session, cost-session, generate-index, and generate-dashboard.
 */

import fs from 'node:fs';
import path from 'node:path';

export const TELEMETRY_DIR = 'tmp/agent-telemetry';
export const EVENTS_FILE = 'events.ndjson';
export const SESSIONS_DIR = 'sessions';
export const SCORES_FILE = 'scores.ndjson';
export const COSTS_FILE = 'costs.ndjson';
export const OUTCOMES_FILE = 'outcomes.ndjson';
// Residual LLM-judge cache (tempdoc 622 §6.3): the judge fills only inference
// fields; outcome-session.mjs is the fact-authoritative writer of OUTCOMES_FILE.
export const JUDGE_OUTCOMES_FILE = 'judge-outcomes.ndjson';
export const SESSION_MERGES_FILE = 'session-merges.ndjson';

// Resolve repo root from this lib's location (lib/ → agent-analytics/ → scripts/ → repo)
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const scriptDir = process.platform === 'win32'
  ? SCRIPT_DIR.replace(/^\/([A-Za-z]:)/, '$1')
  : SCRIPT_DIR;
export const repoRoot = path.resolve(scriptDir, '..', '..', '..');

/**
 * Load all events from events.ndjson (and the rotated .prev file).
 */
export function loadEvents() {
  const events = [];
  const dir = path.join(repoRoot, TELEMETRY_DIR);

  // Load both current and rotated file
  for (const filename of [EVENTS_FILE + '.prev', EVENTS_FILE]) {
    const filePath = path.join(dir, filename);
    if (!fs.existsSync(filePath)) continue;

    const content = fs.readFileSync(filePath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      try {
        events.push(JSON.parse(line));
      } catch {
        // Skip malformed lines
      }
    }
  }

  return events;
}

// --- OTLP ingest seam (tempdoc 622 Layer A) -------------------------------
// The native Claude Code OpenTelemetry stream is the canonical capture authority
// (projection-not-fork). `otlp-sink.py` decodes it to NDJSON under
// tmp/agent-telemetry/otlp/. loadEventsFromOtlp() normalizes those records into
// the SAME event shape dispatch.mjs produced, so the scorers need no changes.

export const OTLP_DIR = 'otlp';

/**
 * Read a rotated OTLP stream (both `<base>.prev.ndjson` and `<base>.ndjson`,
 * oldest first) — mirroring loadEvents' dual-read. otlp-sink.py rotates each
 * stream to `.prev` past 20 MB, so a reader that ignored `.prev` would silently
 * drop rotated sessions (and desync logs vs metrics).
 */
function loadOtlpStream(dir, base) {
  return [
    ...loadNdjsonArray(path.join(dir, `${base}.prev.ndjson`)),
    ...loadNdjsonArray(path.join(dir, `${base}.ndjson`)),
  ];
}

/**
 * Reconstruct the dispatch.mjs-style `input_summary` from an OTLP `tool_input`
 * JSON string, so OTLP-sourced events match the legacy event shape the scorers
 * read (file_path / command / has_offset / *_string_length / …).
 */
function reconstructInputSummary(toolName, toolInputJson) {
  let ti = {};
  try { ti = JSON.parse(toolInputJson || '{}'); } catch { return { tool: toolName }; }
  switch (toolName) {
    case 'Bash':
      return { command: ti.command, description: ti.description ?? null,
               timeout: ti.timeout ?? null, run_in_background: ti.run_in_background ?? false };
    case 'Read':
      return { file_path: ti.file_path, has_offset: ti.offset != null, has_limit: ti.limit != null };
    case 'Edit':
      return { file_path: ti.file_path, old_string_length: (ti.old_string || '').length,
               new_string_length: (ti.new_string || '').length, replace_all: ti.replace_all ?? false };
    case 'Write':
      return { file_path: ti.file_path, content_length: (ti.content || '').length };
    case 'Grep':
      return { pattern: ti.pattern, path: ti.path ?? null, output_mode: ti.output_mode ?? null,
               type: ti.type ?? null, glob: ti.glob ?? null };
    case 'Glob':
      return { pattern: ti.pattern, path: ti.path ?? null };
    default:
      // MCP / Task / other: keep a minimal, non-content summary.
      return ti.subagent_type ? { tool: toolName, subagent_type: ti.subagent_type } : { tool: toolName };
  }
}

/**
 * Load events from the decoded OTLP log stream (and span durations) and
 * normalize them to the legacy analytics event schema.
 */
export function loadEventsFromOtlp() {
  const dir = path.join(repoRoot, TELEMETRY_DIR, OTLP_DIR);
  const logs = loadOtlpStream(dir, 'logs');

  // The rich tool input lives on the `tool_result` (post) log, but the legacy
  // analyze-session reads `input_summary` off the `pre_tool_use` event. Pre-pass:
  // map tool_use_id -> reconstructed input_summary so we can attach it to BOTH the
  // pre_tool_use (tool_decision) and post_tool_use (tool_result) events.
  const inputByUseId = new Map();
  // model is carried by metrics, not logs; thread it onto a synthetic session_start.
  const modelBySession = (() => {
    const m = new Map();
    for (const rec of loadOtlpStream(dir, 'metrics')) {
      for (const p of rec.points || []) {
        const a = p.attributes || {};
        if (a['session.id'] && a.model && !m.has(a['session.id'])) m.set(a['session.id'], a.model);
      }
    }
    return m;
  })();
  for (const r of logs) {
    const a = r.attributes || {};
    if (a['event.name'] === 'tool_result' && a.tool_use_id) {
      inputByUseId.set(a.tool_use_id, reconstructInputSummary(a.tool_name, a.tool_input));
    }
  }

  const events = [];
  const seenSessionStart = new Set();
  for (const r of logs) {
    const a = r.attributes || {};
    const sid = a['session.id'];
    const ts = a['event.timestamp'];
    if (!sid) continue;
    // synthesize one session_start carrying the model, so reports populate `model`
    if (!seenSessionStart.has(sid)) {
      seenSessionStart.add(sid);
      events.push({ event: 'session_start', session_id: sid, ts,
        model: modelBySession.get(sid) ?? null, schema_version: 1, source: 'otlp' });
    }
    switch (a['event.name']) {
      case 'user_prompt':
        events.push({ event: 'user_prompt_submit', session_id: sid, ts,
          prompt_length: a.prompt_length != null ? Number(a.prompt_length) : null,
          prompt_excerpt: a.prompt ? String(a.prompt).slice(0, 200) : null,
          schema_version: 1, source: 'otlp' });
        break;
      case 'tool_result': {
        const ok = String(a.success) === 'true';
        events.push({ event: ok ? 'post_tool_use' : 'post_tool_use_failure',
          session_id: sid, ts, tool_name: a.tool_name,
          duration_ms: a.duration_ms != null ? Number(a.duration_ms) : undefined,
          tool_use_id: a.tool_use_id,
          input_summary: inputByUseId.get(a.tool_use_id) ?? reconstructInputSummary(a.tool_name, a.tool_input),
          ...(ok ? {} : { error_summary: a.error || '(tool failed)' }),
          schema_version: 1, source: 'otlp' });
        break;
      }
      case 'tool_decision':
        // attach the joined input_summary so analyze-session's pre_tool_use readers
        // (file_reads / unbounded / hot-file / edits) see the file_path + flags.
        events.push({ event: 'pre_tool_use', session_id: sid, ts, tool_name: a.tool_name,
          tool_use_id: a.tool_use_id, input_summary: inputByUseId.get(a.tool_use_id) ?? null,
          schema_version: 1, source: 'otlp' });
        break;
      case 'subagent_completed':
        events.push({ event: 'subagent_stop', session_id: sid, ts, schema_version: 1, source: 'otlp' });
        break;
      default:
        break; // hook_*, mcp_server_connection, api_request, plugin_loaded — not scored
    }
  }
  return events;
}

/**
 * Aggregate per-session cost + token usage from the decoded OTLP metric stream.
 * Returns Map<session_id, {cost_usd, input_tokens, output_tokens,
 * cache_read_tokens, cache_write_tokens, model, by_source}>. This is the
 * canonical cost authority (the harness computes `claude_code.cost.usage`
 * directly) — it replaces transcript re-derivation (tempdoc 622 §9.3 fork).
 */
export function loadCostsFromOtlp() {
  const dir = path.join(repoRoot, TELEMETRY_DIR, OTLP_DIR);
  const metrics = loadOtlpStream(dir, 'metrics');
  const TOKEN_FIELD = { input: 'input_tokens', output: 'output_tokens',
    cacheRead: 'cache_read_tokens', cacheCreation: 'cache_write_tokens' };
  const map = new Map();
  const ensure = (sid) => {
    if (!map.has(sid)) map.set(sid, { session_id: sid, cost_usd: 0,
      input_tokens: 0, output_tokens: 0, cache_read_tokens: 0, cache_write_tokens: 0,
      model: null, by_source: {} });
    return map.get(sid);
  };
  for (const m of metrics) {
    for (const p of m.points || []) {
      const a = p.attributes || {};
      const sid = a['session.id'];
      if (!sid) continue;
      const rec = ensure(sid);
      if (a.model) rec.model = a.model;
      const src = a.query_source || 'main';
      rec.by_source[src] ??= { cost_usd: 0, output_tokens: 0 };
      if (m.name === 'claude_code.cost.usage') {
        rec.cost_usd += p.value || 0;
        rec.by_source[src].cost_usd += p.value || 0;
      } else if (m.name === 'claude_code.token.usage') {
        const field = TOKEN_FIELD[a.type];
        if (field) rec[field] += p.value || 0;
        if (a.type === 'output') rec.by_source[src].output_tokens += p.value || 0;
      }
    }
  }
  for (const rec of map.values()) rec.cost_usd = round(rec.cost_usd, 4);
  return map;
}

/**
 * Pluggable event ingest. `'ndjson'` (default) reads the legacy dispatch.mjs
 * stream; `'otlp'` reads the native-OTel-derived stream (tempdoc 622 Layer A).
 * During the parallel-run transition both sources exist; callers pick one.
 */
export function loadEventsFromSource(source = 'ndjson') {
  if (source === 'otlp') return loadEventsFromOtlp();
  return loadEvents();
}

/**
 * Group events by session_id into a Map<string, Event[]>.
 */
export function groupBySession(events) {
  const sessions = new Map();
  for (const event of events) {
    const sid = event.session_id;
    if (!sid) continue;
    if (!sessions.has(sid)) sessions.set(sid, []);
    sessions.get(sid).push(event);
  }
  return sessions;
}

/**
 * Load an NDJSON file into an array of parsed records.
 */
export function loadNdjsonArray(filePath) {
  const records = [];
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      try { records.push(JSON.parse(line)); } catch { /* skip */ }
    }
  } catch { /* file doesn't exist */ }
  return records;
}

/**
 * Load an NDJSON file into a Map keyed by session_id.
 */
export function loadNdjsonMap(filePath) {
  const map = new Map();
  try {
    const content = fs.readFileSync(filePath, 'utf8');
    for (const line of content.split('\n')) {
      if (!line.trim()) continue;
      try {
        const record = JSON.parse(line);
        if (record.session_id) map.set(record.session_id, record);
      } catch { /* skip */ }
    }
  } catch { /* file doesn't exist */ }
  return map;
}

/**
 * Load all session reports from the sessions directory.
 * Returns Map<session_id, report>.
 */
export function loadSessionReports() {
  const dir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
  const map = new Map();
  try {
    for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.json'))) {
      try {
        const report = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));
        if (report.session_id) map.set(report.session_id, report);
      } catch { /* skip */ }
    }
  } catch { /* no dir */ }
  return map;
}

/**
 * Round a number to the given number of decimal places.
 */
export function round(n, decimals = 3) {
  const f = 10 ** decimals;
  return Math.round(n * f) / f;
}
