/**
 * Tempdoc 743 Phase 1 — unit tests for record-merge.mjs's cost-upsert wiring
 * (the "survival requirement": costing re-runs at a workflow moment, not just
 * as a one-off audit). Exercises bestEffortUpsertCost / upsertCostRecord /
 * costRecordFromSessionCost against temp dirs only — never against the real
 * main-checkout costs.ndjson.
 *
 * Run with: `node --test scripts/agent-analytics/record-merge.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  costRecordFromSessionCost,
  upsertCostRecord,
  bestEffortUpsertCost,
} from './record-merge.mjs';
import { TELEMETRY_DIR, COSTS_FILE } from './lib/telemetry-io.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'record-merge-test-'));

function assistantLine(model, usage) {
  return { type: 'assistant', message: { model, usage } };
}
function writeTranscript(dir, sessionId, lines) {
  fs.writeFileSync(path.join(dir, `${sessionId}.jsonl`), lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
}

try {
  // --- costRecordFromSessionCost ---
  run('costRecordFromSessionCost maps a computeSessionCost record to the legacy costs.ndjson row shape', () => {
    const sessionCostRec = {
      session_id: 'sess-x',
      total_cost_usd: 1.2345,
      total_tokens: { input: 10, output: 20, cache_write: 0, cache_read: 0 },
      main: { model: 'claude-sonnet-5', turns: 3 },
      subagents: { found: 1, missing: 0, turns: 2 },
      orchestrator_tokens_total: 30,
      worker_tokens_total: 15,
      unknown_model_tokens: {},
    };
    const row = costRecordFromSessionCost(sessionCostRec);
    assert.equal(row.session_id, 'sess-x');
    assert.equal(row.total_cost_usd, 1.2345);
    assert.deepEqual(row.tokens, { input: 10, output: 20, cache_write: 0, cache_read: 0 });
    assert.equal(row.model, 'claude-sonnet-5');
    assert.equal(row.turns, 5); // main 3 + subagent 2
    assert.equal(row.subagent_transcripts_found, 1);
    assert.equal(row.subagent_transcripts_missing, 0);
    assert.equal(row.reason, null);
    assert.equal(row.orchestrator_tokens_total, 30);
    assert.equal(row.worker_tokens_total, 15);
  });

  // --- upsertCostRecord ---
  run('upsertCostRecord creates costs.ndjson and appends a new session row', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    upsertCostRecord({ session_id: 's1', total_cost_usd: 1 }, { root });
    const costsPath = path.join(root, TELEMETRY_DIR, COSTS_FILE);
    const rows = fs.readFileSync(costsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.equal(rows.length, 1);
    assert.equal(rows[0].session_id, 's1');
  });
  run('upsertCostRecord replaces an existing row for the same session_id (idempotent re-run)', () => {
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));
    upsertCostRecord({ session_id: 's1', total_cost_usd: 1 }, { root });
    upsertCostRecord({ session_id: 's2', total_cost_usd: 2 }, { root });
    upsertCostRecord({ session_id: 's1', total_cost_usd: 99 }, { root }); // re-run after new commits
    const costsPath = path.join(root, TELEMETRY_DIR, COSTS_FILE);
    const rows = fs.readFileSync(costsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.equal(rows.length, 2); // no duplicate row for s1
    const s1 = rows.find((r) => r.session_id === 's1');
    assert.equal(s1.total_cost_usd, 99);
  });

  // --- bestEffortUpsertCost ---
  run('bestEffortUpsertCost finds a session transcript, costs it, and upserts', () => {
    const projectsRoot = fs.mkdtempSync(path.join(tmp, 'projects-'));
    const projectDir = path.join(projectsRoot, 'F--justsearch-public-fixture');
    fs.mkdirSync(projectDir, { recursive: true });
    writeTranscript(projectDir, 'sess-merge-1', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'mode' },
      assistantLine('claude-sonnet-5', { input_tokens: 1000, output_tokens: 500, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));

    const result = bestEffortUpsertCost('sess-merge-1', { projectsRoot, root });
    assert.equal(result.ok, true);
    assert.ok(result.costRecord.total_cost_usd > 0);

    const costsPath = path.join(root, TELEMETRY_DIR, COSTS_FILE);
    const rows = fs.readFileSync(costsPath, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
    assert.equal(rows.length, 1);
    assert.equal(rows[0].session_id, 'sess-merge-1');
  });
  run('bestEffortUpsertCost returns ok:false (never throws) when no transcript exists for the session', () => {
    const projectsRoot = fs.mkdtempSync(path.join(tmp, 'projects-empty-'));
    fs.mkdirSync(path.join(projectsRoot, 'F--justsearch-public-fixture'), { recursive: true });
    const root = fs.mkdtempSync(path.join(tmp, 'root-'));

    const result = bestEffortUpsertCost('no-such-session', { projectsRoot, root });
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'transcript_not_found');
    // must not have created a costs.ndjson at all — nothing to upsert
    assert.equal(fs.existsSync(path.join(root, TELEMETRY_DIR, COSTS_FILE)), false);
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`record-merge.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`record-merge.test: ${passed} passed`);
