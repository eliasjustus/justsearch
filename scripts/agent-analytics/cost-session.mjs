#!/usr/bin/env node

/**
 * Per-session cost estimation from Claude Code transcript JSONL files.
 *
 * Parses main session + subagent transcripts, extracts token usage from
 * assistant messages, applies embedded pricing table. Writes cost records
 * to costs.ndjson (upsert by session_id).
 *
 * Usage:
 *   node cost-session.mjs --session-id <id>   # Cost one session
 *   node cost-session.mjs --all               # Cost all sessions
 *   node cost-session.mjs --all --json        # JSON array to stdout
 *   node cost-session.mjs --reconcile [--json] # OTLP-priced vs transcript-priced, per session (886 §12 PR 2)
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  TELEMETRY_DIR, SESSIONS_DIR, COSTS_FILE,
  repoRoot, loadEvents, groupBySession, loadCostsFromOtlp,
} from './lib/telemetry-io.mjs';
import { round, parseSessionTokens, MISSING_MODEL_KEY } from './lib/transcript-cost.mjs';

// --- Session costing ---

function findTranscriptPaths(sessionEvents) {
  const startEvent = sessionEvents.find(e => e.event === 'session_start' && e.transcript_path);
  const mainPath = startEvent?.transcript_path ?? null;

  const subagentPaths = [];
  const seen = new Set();
  for (const e of sessionEvents) {
    if (e.event === 'subagent_stop' && e.agent_transcript_path) {
      if (!seen.has(e.agent_transcript_path)) {
        seen.add(e.agent_transcript_path);
        subagentPaths.push(e.agent_transcript_path);
      }
    }
  }

  return { mainPath, subagentPaths };
}

function costSession(sessionId, sessionEvents) {
  const { mainPath, subagentPaths } = findTranscriptPaths(sessionEvents);

  // Main + subagent transcripts, one dedup scope, per-turn pricing per model.
  // The combine itself lives in transcript-cost.mjs (tempdoc 745 item B, D7) —
  // this used to be a second, independently-maintained copy of the same loop.
  const { main, subagents } = parseSessionTokens({ mainPath, subagentPaths });
  const mainTokens = mainPath ? main : null;
  const subFound = subagents.found;
  const subMissing = subagents.missing;
  const subTokens = subagents.totals;

  // Combined totals — cost is sum of per-turn costs (not single-model approximation)
  const totalCostUsd = (mainTokens?.cost_usd ?? 0) + subTokens.cost_usd;
  const totalTokens = {
    input_tokens: (mainTokens?.input_tokens ?? 0) + subTokens.input_tokens,
    output_tokens: (mainTokens?.output_tokens ?? 0) + subTokens.output_tokens,
    cache_write_tokens: (mainTokens?.cache_write_tokens ?? 0) + subTokens.cache_write_tokens,
    cache_read_tokens: (mainTokens?.cache_read_tokens ?? 0) + subTokens.cache_read_tokens,
  };

  const model = mainTokens?.model ?? sessionEvents.find(e => e.event === 'session_start')?.model ?? null;

  return {
    ts: new Date().toISOString(),
    session_id: sessionId,
    total_cost_usd: mainPath ? round(totalCostUsd) : null,
    tokens: {
      input: totalTokens.input_tokens,
      output: totalTokens.output_tokens,
      cache_write: totalTokens.cache_write_tokens,
      cache_read: totalTokens.cache_read_tokens,
    },
    model,
    turns: (mainTokens?.turns ?? 0) + subTokens.turns,
    subagent_transcripts_found: subFound,
    subagent_transcripts_missing: subMissing,
    reason: mainPath ? null : 'no_transcript_path',
  };
}

// --- I/O ---

function writeCosts(records) {
  const costsPath = path.join(repoRoot, TELEMETRY_DIR, COSTS_FILE);
  fs.mkdirSync(path.dirname(costsPath), { recursive: true });
  const content = records.map(r => JSON.stringify(r)).join('\n') + '\n';
  fs.writeFileSync(costsPath, content, 'utf8');
}

function upsertCost(record) {
  const costsPath = path.join(repoRoot, TELEMETRY_DIR, COSTS_FILE);
  fs.mkdirSync(path.dirname(costsPath), { recursive: true });

  let existing = [];
  try {
    existing = fs.readFileSync(costsPath, 'utf8')
      .split('\n').filter(l => l.trim()).map(l => JSON.parse(l));
  } catch { /* start fresh */ }

  const idx = existing.findIndex(r => r.session_id === record.session_id);
  if (idx !== -1) {
    existing[idx] = record;
  } else {
    existing.push(record);
  }
  writeCosts(existing);
}

function formatHuman(record) {
  const lines = [];
  const sid = record.session_id.substring(0, 8);
  if (record.total_cost_usd == null) {
    lines.push(`Session ${sid}: No transcript found (${record.reason})`);
    return lines.join('\n');
  }

  lines.push(`Session: ${sid} (${record.turns} turns, model: ${record.model})`);
  lines.push(`Cost:    $${record.total_cost_usd.toFixed(4)}`);
  lines.push(`Tokens:  ${fmtK(record.tokens.input)} in, ${fmtK(record.tokens.output)} out, ${fmtK(record.tokens.cache_write)} cache-w, ${fmtK(record.tokens.cache_read)} cache-r`);
  if (record.subagent_transcripts_found > 0 || record.subagent_transcripts_missing > 0) {
    lines.push(`Subagents: ${record.subagent_transcripts_found} found, ${record.subagent_transcripts_missing} missing`);
  }
  return lines.join('\n');
}

function fmtK(n) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

// --- OTLP cost source (tempdoc 622 Layer A — harness-computed cost, no transcripts) ---

/**
 * Map an OTLP per-session cost aggregate (loadCostsFromOtlp) to the canonical
 * costs.ndjson record shape. `total_cost_usd` is the harness's own
 * `claude_code.cost.usage` sum — authoritative, no pricing table.
 *
 * Codex sessions carry token records but NO `claude_code.cost.usage` metric
 * (Codex has no equivalent harness-computed-dollar metric at all — 886 §12
 * PR 3), so `rec.hasCostMetric` (set by `loadCostsFromOtlp` only when it saw
 * that metric for the session) distinguishes "genuinely $0" from "no cost
 * metric exists for this harness" (independent review SHOULD-FIX 2). The
 * latter reports `total_cost_usd: null` + `reason: 'no_cost_metric'` — the
 * same null-not-zero convention `costSession()` already uses for
 * `reason: 'no_transcript_path'` above — instead of silently pricing a
 * Codex session at $0, which would read as "this session was free" when it
 * was actually "we never asked what it cost".
 */
function recordFromOtlp(rec) {
  const subCost = rec.by_source?.subagent?.cost_usd ?? 0;
  const hasCost = rec.hasCostMetric === true;
  return {
    ts: new Date().toISOString(),
    session_id: rec.session_id,
    total_cost_usd: hasCost ? round(rec.cost_usd) : null,
    tokens: {
      input: rec.input_tokens,
      output: rec.output_tokens,
      cache_write: rec.cache_write_tokens,
      cache_read: rec.cache_read_tokens,
    },
    model: rec.model,
    harness: rec.harness ?? null,
    turns: null, // turn count not carried by the cost metric; trace/log spans hold it
    subagent_cost_usd: round(subCost),
    source: 'otlp',
    reason: hasCost ? null : 'no_cost_metric',
  };
}

/**
 * Pure mapping step between `loadCostsFromOtlp`'s Map (or an injected
 * array of the same per-session shape) and the `recordFromOtlp` record
 * list — split out so a test can feed synthetic records without touching
 * `tmp/agent-telemetry/otlp/`, mirroring `reconcileSessions`' injected-input
 * contract.
 */
export function costRecordsFromOtlp(costMapOrArray) {
  const entries = costMapOrArray instanceof Map ? [...costMapOrArray.values()] : costMapOrArray;
  return entries.map(recordFromOtlp);
}

function runOtlpCost(sessionId, jsonOnly) {
  const costMap = loadCostsFromOtlp();
  const records = costRecordsFromOtlp(costMap)
    .filter(r => !sessionId || r.session_id === sessionId);
  if (sessionId && records.length === 0) {
    console.error(`Session not found in OTLP metrics: ${sessionId}`);
    process.exit(1);
  }
  for (const r of records) upsertCost(r);
  records.sort((a, b) => (b.total_cost_usd ?? 0) - (a.total_cost_usd ?? 0));
  if (jsonOnly) {
    process.stdout.write(JSON.stringify(sessionId ? records[0] : records, null, 2) + '\n');
  } else {
    const priced = records.filter(r => r.total_cost_usd != null);
    const unpriced = records.filter(r => r.total_cost_usd == null);
    const total = priced.reduce((s, r) => s + r.total_cost_usd, 0);
    console.log(`Costed ${records.length} session(s) from OTLP ($${total.toFixed(4)} total, `
      + `${priced.length} priced, ${unpriced.length} no-cost-metric):\n`);
    for (const r of records) {
      const costStr = r.total_cost_usd != null
        ? `$${r.total_cost_usd.toFixed(4)}`
        : `n/a (no cost metric from ${r.harness ?? 'unknown harness'})`;
      console.log(`  ${r.session_id.substring(0, 8)}  ${costStr}  (${r.model ?? 'unknown'}, subagent $${(r.subagent_cost_usd ?? 0).toFixed(4)})`);
    }
    if (unpriced.length) {
      console.log(`\n${unpriced.length} session(s) had token records but no cost metric (harness reports `
        + `no dollar value, excluded from the total above): ${unpriced.map(r => r.session_id.substring(0, 8)).join(', ')}`);
    }
    console.log(`\nCosts written to ${path.join(TELEMETRY_DIR, COSTS_FILE)}`);
  }
}

// --- Reconciliation: OTLP-priced vs transcript-priced (tempdoc 886 §12 PR 2, 858 §9.1) ---

/**
 * Compare the OTLP-costed set (`loadCostsFromOtlp`, harness-computed dollars,
 * no pricing table) against the transcript-priced set (`costSession` over
 * every session `loadEvents`/`groupBySession` knows about) — the two cost
 * sources this pipeline has never reconciled against each other (886 §2.5).
 *
 * Pure and injectable (`otlpRecords`/`transcriptRecords` in, no file IO) so
 * a test can feed synthetic pairs without touching `tmp/agent-telemetry/`.
 * `otlpRecords` accepts either the `Map` `loadCostsFromOtlp()` returns or a
 * plain array of `{session_id, cost_usd, model}`; `transcriptRecords` is an
 * array of `costSession()`-shaped records (`{session_id, total_cost_usd,
 * model, reason}`).
 *
 * Residue causes named per 858 §9.1: a session whose OTLP or transcript
 * model resolved to nothing pricing-worthy (`MISSING_MODEL_KEY`/null on the
 * OTLP side, `MISSING_MODEL_KEY`/null/the literal `<synthetic>` model name
 * on the transcript side — see `lib/transcript-cost.mjs`'s `MISSING_MODEL_KEY`
 * and `context-residency.mjs`'s note on Claude's own `<synthetic>` model
 * turns) gets a residue tag rather than a silent, unexplained delta.
 */
export function reconcileSessions({ otlpRecords, transcriptRecords }) {
  const otlpMap = otlpRecords instanceof Map ? otlpRecords : new Map(otlpRecords.map((r) => [r.session_id, r]));
  const transcriptMap = new Map(transcriptRecords.map((r) => [r.session_id, r]));

  const common = [];
  const otlpOnly = [];
  const transcriptOnly = [];

  const isUnpricedTranscriptModel = (m) => !m || m === MISSING_MODEL_KEY || m === '<synthetic>';
  const isUnpricedOtlpModel = (m) => !m || m === MISSING_MODEL_KEY;

  for (const [sid, o] of otlpMap) {
    const t = transcriptMap.get(sid);
    if (!t || t.total_cost_usd == null) {
      otlpOnly.push({ session_id: sid, otlp_cost_usd: round(o.cost_usd ?? 0, 4), model: o.model ?? null });
      continue;
    }
    const otlpCost = o.cost_usd ?? 0;
    const transcriptCost = t.total_cost_usd ?? 0;
    const deltaPct = otlpCost !== 0 ? round(((transcriptCost - otlpCost) / otlpCost) * 100, 2) : null;
    const residue = [];
    if (isUnpricedOtlpModel(o.model)) residue.push('otlp:unknown-model');
    if (isUnpricedTranscriptModel(t.model)) residue.push('transcript:unknown-model');
    common.push({
      session_id: sid,
      otlp_cost_usd: round(otlpCost, 4),
      transcript_cost_usd: round(transcriptCost, 4),
      delta_pct: deltaPct,
      residue,
    });
  }

  for (const [sid, t] of transcriptMap) {
    if (otlpMap.has(sid)) continue;
    if (t.total_cost_usd == null) continue; // no transcript found either — nothing to reconcile
    transcriptOnly.push({ session_id: sid, transcript_cost_usd: round(t.total_cost_usd, 4), model: t.model ?? null });
  }

  common.sort((a, b) => Math.abs(b.delta_pct ?? 0) - Math.abs(a.delta_pct ?? 0));
  return { common, otlpOnly, transcriptOnly };
}

function runReconcile(jsonOnly) {
  const otlpMap = loadCostsFromOtlp();
  const events = loadEvents();
  const sessions = groupBySession(events);
  const transcriptRecords = [...sessions.entries()].map(([sid, sessionEvents]) => costSession(sid, sessionEvents));

  const { common, otlpOnly, transcriptOnly } = reconcileSessions({ otlpRecords: otlpMap, transcriptRecords });

  if (jsonOnly) {
    process.stdout.write(JSON.stringify({ common, otlpOnly, transcriptOnly }, null, 2) + '\n');
    return;
  }

  console.log(`cost-session --reconcile: ${common.length} sessions in both sources, `
    + `${otlpOnly.length} OTLP-only, ${transcriptOnly.length} transcript-only\n`);
  console.log('session   otlp$      transcript$   delta%    residue');
  for (const r of common) {
    const residue = r.residue.length ? r.residue.join(',') : '-';
    console.log(`${r.session_id.substring(0, 8)}  $${r.otlp_cost_usd.toFixed(4).padStart(9)}  `
      + `$${r.transcript_cost_usd.toFixed(4).padStart(9)}  ${(r.delta_pct == null ? 'n/a' : `${r.delta_pct}%`).padStart(8)}  ${residue}`);
  }
  if (otlpOnly.length) {
    console.log('\nOTLP-only (no transcript-priced record for this session):');
    for (const r of otlpOnly) console.log(`  ${r.session_id.substring(0, 8)}  $${r.otlp_cost_usd.toFixed(4)}  (${r.model ?? 'unknown'})`);
  }
  if (transcriptOnly.length) {
    console.log('\ntranscript-only (no OTLP-metric record for this session):');
    for (const r of transcriptOnly) console.log(`  ${r.session_id.substring(0, 8)}  $${r.transcript_cost_usd.toFixed(4)}  (${r.model ?? 'unknown'})`);
  }
}

// --- Main ---

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--reconcile')) {
    runReconcile(args.includes('--json'));
    return;
  }
  const jsonOnly = args.includes('--json');
  const all = args.includes('--all');
  const sourceIdx = args.indexOf('--source');
  const source = sourceIdx !== -1 ? args[sourceIdx + 1] : 'transcript';
  const sessionIdx = args.indexOf('--session-id');
  const sessionId = sessionIdx !== -1 ? args[sessionIdx + 1] : null;

  if (!all && !sessionId) {
    console.error('Usage: node cost-session.mjs --session-id <id> | --all [--json] [--source otlp]');
    process.exit(1);
  }

  // OTLP source: harness-computed cost, no transcript parsing (622 §9.3 fork killed).
  if (source === 'otlp') {
    runOtlpCost(sessionId, jsonOnly);
    return;
  }

  console.error('Loading events...');
  const events = loadEvents();
  const sessions = groupBySession(events);
  console.error(`Found ${sessions.size} sessions`);

  if (sessionId) {
    const sessionEvents = sessions.get(sessionId);
    if (!sessionEvents) {
      console.error(`Session not found: ${sessionId}`);
      process.exit(1);
    }
    const result = costSession(sessionId, sessionEvents);
    upsertCost(result);

    if (jsonOnly) {
      process.stdout.write(JSON.stringify(result, null, 2) + '\n');
    } else {
      console.log(formatHuman(result));
      console.log(`\nCost appended to ${path.join(TELEMETRY_DIR, COSTS_FILE)}`);
    }
    return;
  }

  // --all mode
  // Only cost sessions that have session reports (skip tiny/trivial ones)
  const reportsDir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
  const reportIds = new Set();
  try {
    for (const f of fs.readdirSync(reportsDir).filter(f => f.endsWith('.json'))) {
      reportIds.add(f.replace('.json', ''));
    }
  } catch { /* no reports dir */ }
  if (reportIds.size === 0) {
    console.error(
      `cost-session: no session reports under ${path.relative(repoRoot, reportsDir)} — ` +
        '--all will cost 0 sessions. Their producer (analyze-session.mjs) was deleted in tempdoc ' +
          '930, so this filter is now historical: pass --session-id, or re-run against a checkout ' +
          'whose reports dir predates the deletion.',
    );
  }

  // Load existing costs so we can preserve records for sessions whose transcripts
  // have rotated out of events.ndjson or been deleted by Claude Code.
  const costsPath = path.join(repoRoot, TELEMETRY_DIR, COSTS_FILE);
  const existingCosts = new Map();
  try {
    fs.readFileSync(costsPath, 'utf8')
      .split('\n').filter(l => l.trim())
      .forEach(l => { const r = JSON.parse(l); existingCosts.set(r.session_id, r); });
  } catch { /* start fresh */ }

  const freshResults = [];
  for (const [sid, sessionEvents] of sessions) {
    if (!reportIds.has(sid)) continue; // Skip sessions without reports
    const result = costSession(sid, sessionEvents);
    // If transcript is gone (cost=0/null) and we have a prior non-zero record, keep prior.
    const prior = existingCosts.get(sid);
    if (!result.total_cost_usd && prior?.total_cost_usd > 0) {
      freshResults.push(prior);
    } else {
      freshResults.push(result);
    }
  }

  // Merge: start from existing records (preserving sessions outside current event window),
  // then overlay fresh results.
  const merged = new Map(existingCosts);
  for (const r of freshResults) merged.set(r.session_id, r);
  const results = [...merged.values()];

  writeCosts(results);

  // Sort by cost descending
  results.sort((a, b) => (b.total_cost_usd ?? 0) - (a.total_cost_usd ?? 0));

  if (jsonOnly) {
    process.stdout.write(JSON.stringify(results, null, 2) + '\n');
  } else {
    const withCost = results.filter(r => r.total_cost_usd != null && r.total_cost_usd > 0);
    const totalCost = withCost.reduce((s, r) => s + r.total_cost_usd, 0);
    console.log(`Costed ${results.length} sessions ($${totalCost.toFixed(2)} total across ${withCost.length} with data):\n`);
    for (const r of results) {
      const cost = r.total_cost_usd != null ? `$${r.total_cost_usd.toFixed(4)}` : 'N/A';
      console.log(`  ${r.session_id.substring(0, 8)}  ${cost}  (${r.turns} turns, ${r.model ?? 'unknown'})`);
    }
    console.log(`\nCosts written to ${path.join(TELEMETRY_DIR, COSTS_FILE)}`);
  }
}

// Guarded direct-run entry point (886 §12 PR 2) — was an unconditional
// `main();`, which ran the CLI (and could `process.exit(1)` on missing args)
// on every `import`, including from a test file that only wants
// `reconcileSessions`. Every other script in this directory already guards
// this way; cost-session.mjs was the one exception.
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) main();
