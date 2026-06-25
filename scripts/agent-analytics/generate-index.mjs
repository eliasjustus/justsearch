#!/usr/bin/env node

/**
 * Generate a session index — aggregate session reports, scores, and costs
 * into a sortable summary JSON.
 *
 * Usage:
 *   node generate-index.mjs             # Generate session-index.json
 *   node generate-index.mjs --json      # JSON to stdout (no file write)
 */

import fs from 'node:fs';
import path from 'node:path';
import {
  TELEMETRY_DIR, SESSIONS_DIR, SCORES_FILE, COSTS_FILE,
  repoRoot, loadNdjsonMap, round,
} from './lib/telemetry-io.mjs';

const INDEX_FILE = 'session-index.json';

function buildIndex() {
  const sessionsDir = path.join(repoRoot, TELEMETRY_DIR, SESSIONS_DIR);
  const scores = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, SCORES_FILE));
  const costs = loadNdjsonMap(path.join(repoRoot, TELEMETRY_DIR, COSTS_FILE));

  const entries = [];

  let files;
  try {
    files = fs.readdirSync(sessionsDir).filter(f => f.endsWith('.json'));
  } catch {
    return [];
  }

  for (const file of files) {
    try {
      const report = JSON.parse(fs.readFileSync(path.join(sessionsDir, file), 'utf8'));
      if (report.schema !== 'agent-session-report.v1') continue;

      const sid = report.session_id;
      const score = scores.get(sid);
      const cost = costs.get(sid);

      entries.push({
        session_id: sid,
        started_at: report.started_at,
        duration_hours: round((report.duration_seconds ?? 0) / 3600),
        model: report.model ?? null,
        tool_calls: report.tool_calls?.total ?? 0,
        failure_count: report.tool_calls?.failure_count ?? 0,
        compactions: report.compactions?.count ?? 0,
        subagents: report.subagents?.count ?? 0,
        score: score?.score ?? null,
        flags: score?.flags ?? [],
        anomalies_count: score?.anomalies?.length ?? 0,
        cost_usd: cost?.total_cost_usd ?? null,
        compaction_rereads: report.compaction_rereads?.total_rereads ?? null,
        failure_cascades: report.failure_cascades?.count ?? null,
        context_efficiency: report.context_efficiency?.score_informational ?? null,
        read_redundancy_wasteful: report.read_redundancy?.wasteful ?? null,
        doc_reads_total: report.doc_reads?.total ?? null,
        doc_reads_canonical: report.doc_reads?.canonical_docs?.reads ?? null,
        doc_reads_tempdocs: report.doc_reads?.tempdocs?.reads ?? null,
        doc_reads_claude_rules: report.doc_reads?.claude_rules?.reads ?? null,
      });
    } catch { /* skip unparseable */ }
  }

  // Sort by started_at descending (most recent first)
  entries.sort((a, b) => (b.started_at ?? '').localeCompare(a.started_at ?? ''));

  return entries;
}

function main() {
  const args = process.argv.slice(2);
  const jsonOnly = args.includes('--json');

  const entries = buildIndex();

  const index = {
    schema: 'agent-session-index.v1',
    generated_at: new Date().toISOString(),
    session_count: entries.length,
    entries,
  };

  if (jsonOnly) {
    process.stdout.write(JSON.stringify(index, null, 2) + '\n');
    return;
  }

  // Write file
  const indexPath = path.join(repoRoot, TELEMETRY_DIR, INDEX_FILE);
  fs.mkdirSync(path.dirname(indexPath), { recursive: true });
  fs.writeFileSync(indexPath, JSON.stringify(index, null, 2), 'utf8');

  // Human summary
  console.log(`Session Index: ${entries.length} sessions\n`);
  for (const e of entries) {
    const sid = e.session_id.substring(0, 8);
    const score = e.score != null ? `${e.score}/100` : 'N/A';
    const cost = e.cost_usd != null ? `$${e.cost_usd.toFixed(2)}` : '';
    const flags = e.flags.length > 0 ? ` [${e.flags.join(', ')}]` : '';
    const anomalies = e.anomalies_count > 0 ? ` {${e.anomalies_count}a}` : '';
    console.log(`  ${sid}  ${score}  ${e.duration_hours}h  ${cost}${flags}${anomalies}`);
  }

  console.log(`\nWritten to ${path.join(TELEMETRY_DIR, INDEX_FILE)}`);
}

main();
