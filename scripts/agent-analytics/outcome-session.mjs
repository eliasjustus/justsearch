#!/usr/bin/env node

/**
 * Per-session OUTCOME record as a JOIN projected from canonical ground truth
 * (tempdoc 622 Layer B). The fact-authoritative writer of outcomes.ndjson.
 *
 * Hard facts come from their canonical owner — git (session-merges link), the
 * build counter, tempdoc frontmatter, governance SARIF — never re-derived by an
 * LLM. Every field is tagged `kind: 'fact'` + `source`. The LLM-judge
 * (evaluate-session -> judge-outcomes.ndjson) is folded in ONLY as the residual
 * `inference` block (kind:'inference'); it never overwrites a hard fact.
 *
 * Usage:
 *   node outcome-session.mjs --session-id <id> [--json]
 *   node outcome-session.mjs --all [--json]
 */

import fs from 'node:fs';
import path from 'node:path';
import {
  TELEMETRY_DIR, OUTCOMES_FILE, JUDGE_OUTCOMES_FILE, SESSION_MERGES_FILE,
  repoRoot, loadEventsFromSource, groupBySession, loadNdjsonArray, loadNdjsonMap,
} from './lib/telemetry-io.mjs';

const fact = (value, source, note) => ({ value, kind: 'fact', source, ...(note ? { note } : {}) });

// --- canonical-source readers ---------------------------------------------

function mergeFact(sessionId, mergeRecords) {
  const hit = mergeRecords.filter(r => r.session_id === sessionId).at(-1);
  if (!hit) return fact(false, 'git/session-merges', 'no recorded merge for this session');
  return { value: true, commit: hit.merge_commit, subject: hit.subject,
    kind: 'fact', source: 'git/session-merges' };
}

function buildFact(sessionId) {
  const p = path.join(repoRoot, TELEMETRY_DIR, `build-fails-${sessionId}.json`);
  if (!fs.existsSync(p)) return fact('unknown', 'build-counter', 'no build-fails file');
  try {
    const j = JSON.parse(fs.readFileSync(p, 'utf8'));
    const status = j.consecutiveFailures === 0 ? 'not_failing' : 'failing';
    return fact(status, 'build-counter',
      `consecutiveFailures=${j.consecutiveFailures} (last-build signal, not a full green)`);
  } catch {
    return fact('unknown', 'build-counter', 'unreadable build-fails file');
  }
}

function tempdocFacts(sessionEvents) {
  // tempdocs this session edited (source-agnostic: scan Edit/Write file paths)
  const touched = new Set();
  for (const e of sessionEvents) {
    if (e.event !== 'post_tool_use') continue;
    if (e.tool_name !== 'Edit' && e.tool_name !== 'Write') continue;
    const fp = e.input_summary?.file_path || '';
    const m = fp.replace(/\\/g, '/').match(/docs\/tempdocs\/(\d+)[^/]*\.md$/);
    if (m) touched.add(m[1]);
  }
  const out = [];
  for (const num of touched) {
    const matches = (() => {
      try { return fs.readdirSync(path.join(repoRoot, 'docs', 'tempdocs'))
        .filter(f => f.startsWith(`${num}-`) && f.endsWith('.md')); } catch { return []; }
    })();
    if (matches.length === 0) { out.push({ number: num, status: 'unknown', kind: 'fact', source: 'tempdoc-frontmatter' }); continue; }
    const body = fs.readFileSync(path.join(repoRoot, 'docs', 'tempdocs', matches[0]), 'utf8');
    const fm = body.match(/^---\n([\s\S]*?)\n---/);
    let status = fm ? (fm[1].match(/^status:\s*["']?([^"'\n]+)/m)?.[1]?.trim() ?? null) : null;
    // some tempdocs write a paragraph-length status; keep a short token for the join
    if (status && status.length > 50) status = status.slice(0, 47).trimEnd() + '…';
    const open = (body.match(/^\s*[-*]\s*\[ \]/gm) || []).length;
    const done = (body.match(/^\s*[-*]\s*\[[xX]\]/gm) || []).length;
    out.push({ number: num, status, checkboxes_done: done, checkboxes_total: open + done,
      kind: 'fact', source: 'tempdoc-frontmatter' });
  }
  return out;
}

function gatesFact(window) {
  // governance SARIF is a single shared file, overwritten by each gate run (NOT
  // session-keyed). Honest attribution: only claim this session's gates result if
  // the SARIF was written DURING the session's active window; otherwise it belongs
  // to a different run and we report 'unknown'.
  const file = path.join(repoRoot, 'tmp', 'governance-report.sarif');
  if (!fs.existsSync(file)) return fact('unknown', 'governance-sarif', 'no SARIF present');
  let mtime;
  try { mtime = fs.statSync(file).mtimeMs; } catch { return fact('unknown', 'governance-sarif', 'unstatable SARIF'); }
  if (!window || mtime < window.start || mtime > window.end) {
    return fact('unknown', 'governance-sarif',
      'SARIF mtime outside session window — not attributable to this session');
  }
  try {
    const j = JSON.parse(fs.readFileSync(file, 'utf8'));
    const results = (j.runs || []).flatMap(r => r.results || []);
    return fact(results.length === 0 ? 'pass' : 'fail', 'governance-sarif',
      `${results.length} findings; SARIF mtime within session window`);
  } catch {
    return fact('unknown', 'governance-sarif', 'unreadable SARIF');
  }
}

function inferenceBlock(judge) {
  if (!judge) return null;
  // demote every judge field to kind:'inference'
  return {
    kind: 'inference', source: 'llm-judge',
    task_completion: judge.task_completion ?? null,
    task_completion_rationale: judge.task_completion_rationale ?? null,
    task_type: judge.task_type ?? null,
    tests_added: judge.tests_added ?? null,
    build_passed: judge.build_passed ?? null,
    confidence: judge.confidence ?? null,
  };
}

// --- join ------------------------------------------------------------------

function buildOutcome(sessionId, sessionEvents, mergeRecords, judgeMap) {
  const tsMs = sessionEvents.map(e => Date.parse(e.ts)).filter(n => !Number.isNaN(n));
  const window = tsMs.length ? { start: Math.min(...tsMs), end: Math.max(...tsMs) } : null;
  return {
    session_id: sessionId,
    ts: new Date().toISOString(),
    facts: {
      merged: mergeFact(sessionId, mergeRecords),
      build_last_status: buildFact(sessionId),
      tempdocs: tempdocFacts(sessionEvents),
      gates: gatesFact(window),
    },
    inference: inferenceBlock(judgeMap.get(sessionId)),
  };
}

function upsert(records) {
  const file = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const existing = loadNdjsonMap(file);
  for (const r of records) existing.set(r.session_id, r);
  fs.writeFileSync(file, [...existing.values()].map(r => JSON.stringify(r)).join('\n') + '\n', 'utf8');
}

function main() {
  const args = process.argv.slice(2);
  const json = args.includes('--json');
  const all = args.includes('--all');
  const sidIdx = args.indexOf('--session-id');
  const sessionId = sidIdx !== -1 ? args[sidIdx + 1] : null;
  if (!all && !sessionId) {
    console.error('Usage: node outcome-session.mjs --session-id <id> | --all [--json]');
    process.exit(1);
  }

  // events from both sources (parallel-run transition): union by session
  const events = [...loadEventsFromSource('ndjson'), ...loadEventsFromSource('otlp')];
  const sessions = groupBySession(events);
  const mergeRecords = loadNdjsonArray(path.join(repoRoot, TELEMETRY_DIR, SESSION_MERGES_FILE));
  const judgeMap = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, JUDGE_OUTCOMES_FILE));

  const ids = sessionId ? [sessionId] : [...sessions.keys()];
  const records = ids.map(id => buildOutcome(id, sessions.get(id) || [], mergeRecords, judgeMap));
  upsert(records);

  if (json) {
    process.stdout.write(JSON.stringify(sessionId ? records[0] : records, null, 2) + '\n');
  } else {
    for (const r of records) {
      const t = r.facts.tempdocs.map(t => `${t.number}:${t.status ?? '?'}(${t.checkboxes_done ?? 0}/${t.checkboxes_total ?? 0})`).join(' ') || '—';
      console.log(`${r.session_id.slice(0, 8)}  merged=${r.facts.merged.value}  build=${r.facts.build_last_status.value}  gates=${r.facts.gates.value}  tempdocs=[${t}]  inference=${r.inference ? r.inference.task_completion : 'none'}`);
    }
    console.log(`\nOutcomes (fact-authoritative) written to ${path.join(TELEMETRY_DIR, OUTCOMES_FILE)}`);
  }
}

main();
