/**
 * Tempdoc 743 Phase 1 — unit tests for baseline-economics.mjs (transcript-first
 * per-session cost -> merge-link join -> windowed report).
 *
 * Run with: `node --test scripts/agent-analytics/baseline-economics.test.mjs`
 * Exits non-zero on any failure (same manual-runner style as fold-observations.test.mjs
 * and note-observation.test.mjs in this directory).
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  parseArgs,
  loadExclusionMatcher,
  loadMerges,
  classifyMerge,
  isoWeekKey,
  discoverSessions,
  computeSessionCost,
  buildReport,
  formatMarkdown,
  DEFAULT_SINCE,
} from './baseline-economics.mjs';
import { parseTranscriptTokens, isKnownModel, MISSING_MODEL_KEY } from './lib/transcript-cost.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}
async function runAsync(label, fn) {
  try { await fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'baseline-econ-test-'));

function writeTranscript(dir, sessionId, lines) {
  const file = path.join(dir, `${sessionId}.jsonl`);
  fs.writeFileSync(file, lines.map((l) => JSON.stringify(l)).join('\n') + '\n', 'utf8');
  return file;
}

function assistantLine(model, usage) {
  return { type: 'assistant', message: { model, usage } };
}
function assistantLineWithId(id, model, usage) {
  return { type: 'assistant', message: { id, model, usage } };
}

async function main() {
  // --- parseArgs ---
  run('parseArgs applies defaults', () => {
    const o = parseArgs([]);
    assert.equal(o.since, DEFAULT_SINCE);
    assert.equal(o.until, null);
    assert.equal(o.json, false);
    assert.equal(o.md, false);
  });
  run('parseArgs reads flags', () => {
    const o = parseArgs(['--since', '2026-07-01', '--until', '2026-07-10', '--merges', '/x/y', '--json', '--projects-root', '/p']);
    assert.equal(o.since, '2026-07-01');
    assert.equal(o.until, '2026-07-10');
    assert.equal(o.merges, '/x/y');
    assert.equal(o.json, true);
    assert.equal(o.projectsRoot, '/p');
  });

  // --- pricing math incl. cache tiers (via shared lib) ---
  run('parseTranscriptTokens computes cost across all four token tiers for a known model', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'pricing-'));
    const file = writeTranscript(dir, 'sess-pricing', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-sonnet-5', {
        input_tokens: 100000, output_tokens: 20000,
        cache_creation_input_tokens: 50000, cache_read_input_tokens: 200000,
      }),
    ]);
    const r = parseTranscriptTokens(file);
    // (0.1*3.0)+(0.02*15.0)+(0.05*3.75)+(0.2*0.30) = 0.3+0.3+0.1875+0.06 = 0.8475
    assert.equal(r.cost_usd.toFixed(4), '0.8475');
    assert.equal(r.turns, 1);
    assert.equal(r.model, 'claude-sonnet-5');
    assert.ok(r.by_model['claude-sonnet-5']);
    assert.equal(r.by_model['claude-sonnet-5'].cost_usd.toFixed(4), '0.8475');
  });
  run('parseTranscriptTokens sums multiple turns and skips unparseable/non-assistant lines', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'pricing2-'));
    const file = writeTranscript(dir, 'sess-multi', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-haiku-4-5', { input_tokens: 1000, output_tokens: 1000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
      assistantLine('claude-haiku-4-5', { input_tokens: 1000, output_tokens: 1000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
      { type: 'user', message: { content: 'not assistant, ignored' } },
    ]);
    fs.appendFileSync(file, 'not even json\n');
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 2);
    assert.equal(r.input_tokens, 2000);
    assert.equal(r.output_tokens, 2000);
    // haiku: input 1.0, output 5.0 per 1M -> per turn (0.001*1.0)+(0.001*5.0)=0.006; x2 = 0.012
    assert.equal(r.cost_usd.toFixed(4), '0.0120');
  });

  // --- Finding 1 regression: multi-content-block lines sharing one message.id ---
  run('parseTranscriptTokens counts one message.id exactly once despite N content-block lines sharing identical usage', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'dedup-'));
    const usage = { input_tokens: 1000, output_tokens: 200, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const file = writeTranscript(dir, 'sess-dedup', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLineWithId('msg-A', 'claude-sonnet-5', usage), // content block 1 (e.g. tool_use)
      assistantLineWithId('msg-A', 'claude-sonnet-5', usage), // content block 2 — SAME turn, duplicate usage snapshot
      assistantLineWithId('msg-A', 'claude-sonnet-5', usage), // content block 3
      assistantLineWithId('msg-B', 'claude-sonnet-5', usage), // a genuinely separate turn
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 2); // msg-A counted once, msg-B counted once — NOT 4
    assert.equal(r.input_tokens, 2000); // 1000 x 2 unique turns, not x4
    assert.equal(r.output_tokens, 400);
    assert.equal(r.by_model['claude-sonnet-5'].turns, 2);
  });
  run('parseTranscriptTokens falls back to counting individually when message.id is absent', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'noid-'));
    const usage = { input_tokens: 100, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 };
    const file = writeTranscript(dir, 'sess-noid', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      { type: 'assistant', message: { model: 'claude-sonnet-5', usage } },
      { type: 'assistant', message: { model: 'claude-sonnet-5', usage } },
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.turns, 2); // no id to dedup on — counted individually (documented fallback)
  });

  // --- Finding 4 regression: model-less usage turns must not be priced at DEFAULT ---
  run('parseTranscriptTokens routes a model-less usage turn into MISSING_MODEL_KEY at $0, not DEFAULT_PRICING', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'missingmodel-'));
    const file = writeTranscript(dir, 'sess-missingmodel', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      { type: 'assistant', message: { id: 'msg-nomodel', usage: { input_tokens: 5000, output_tokens: 1000, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 } } },
    ]);
    const r = parseTranscriptTokens(file);
    assert.equal(r.cost_usd, 0); // NOT priced at DEFAULT_PRICING
    assert.equal(r.turns, 1);
    assert.equal(r.input_tokens, 5000);
    assert.ok(r.by_model[MISSING_MODEL_KEY]);
    assert.equal(r.by_model[MISSING_MODEL_KEY].cost_usd, 0);
    assert.equal(r.by_model[MISSING_MODEL_KEY].input_tokens, 5000);
    assert.equal(isKnownModel(MISSING_MODEL_KEY), false);
  });
  run('computeSessionCost surfaces model-less turns via unknown_model_tokens[MISSING_MODEL_KEY]', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'missingmodel-session-'));
    const mainPath = writeTranscript(dir, 'sess-mm', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      { type: 'assistant', message: { id: 'msg-x', usage: { input_tokens: 100, output_tokens: 50, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 } } },
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-mm', projectDir: 'p', mainPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.deepEqual(rec.unknown_model_tokens, { [MISSING_MODEL_KEY]: 150 });
    assert.equal(rec.total_cost_usd, 0);
  });

  // --- unknown-model bucketing ---
  run('isKnownModel is false for an unlisted model id and true for listed/prefix-matched ones', () => {
    assert.equal(isKnownModel('claude-made-up-9'), false);
    assert.equal(isKnownModel('claude-sonnet-5'), true);
    assert.equal(isKnownModel('claude-opus-4-6-20260101'), true); // prefix match
    assert.equal(isKnownModel(null), true); // absent model is a different failure mode
  });
  run('computeSessionCost buckets an unknown model loudly instead of pricing it silently', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'unknown-'));
    const mainPath = writeTranscript(dir, 'sess-unknown', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-made-up-9', { input_tokens: 1000, output_tokens: 500, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-unknown', projectDir: 'p', mainPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.deepEqual(rec.unknown_model_tokens, { 'claude-made-up-9': 1500 });
    // still priced (via DEFAULT_PRICING fallback), not zeroed out
    assert.ok(rec.total_cost_usd > 0);
  });
  run('computeSessionCost does not flag a known model as unknown', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'known-'));
    const mainPath = writeTranscript(dir, 'sess-known', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-sonnet-5', { input_tokens: 100, output_tokens: 100, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-known', projectDir: 'p', mainPath, subagentPaths: [], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.deepEqual(rec.unknown_model_tokens, {});
  });

  // --- orchestrator/worker split via subagents ---
  run('computeSessionCost splits orchestrator (main) vs worker (subagent) tokens', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'split-'));
    const mainPath = writeTranscript(dir, 'sess-split', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-sonnet-5', { input_tokens: 1000, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const subDir = fs.mkdtempSync(path.join(tmp, 'subs-'));
    const subPath = writeTranscript(subDir, 'agent-a1', [
      { timestamp: '2026-07-10T00:00:01.000Z', type: 'file-history-snapshot' },
      assistantLine('claude-haiku-4-5', { input_tokens: 3000, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-split', projectDir: 'p', mainPath, subagentPaths: [subPath], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.equal(rec.orchestrator_tokens_total, 1000);
    assert.equal(rec.worker_tokens_total, 3000);
    assert.equal(rec.subagents.found, 1);
    assert.equal(rec.subagents.missing, 0);
  });
  run('computeSessionCost counts a missing subagent transcript without throwing', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'missing-sub-'));
    const mainPath = writeTranscript(dir, 'sess-missingsub', [
      { timestamp: '2026-07-10T00:00:00.000Z', type: 'file-history-snapshot' },
    ]);
    const rec = computeSessionCost({
      sessionId: 'sess-missingsub', projectDir: 'p', mainPath,
      subagentPaths: [path.join(dir, 'does-not-exist.jsonl')], startTs: '2026-07-10T00:00:00.000Z',
    });
    assert.equal(rec.subagents.missing, 1);
    assert.equal(rec.subagents.found, 0);
  });

  // --- classifyMerge ---
  run('classifyMerge recognizes conventional-commit prefixes', () => {
    assert.equal(classifyMerge('feat(x): add thing'), 'feat');
    assert.equal(classifyMerge('fix: bug'), 'fix');
    assert.equal(classifyMerge('docs(743): baseline economics'), 'docs');
    assert.equal(classifyMerge('chore!: breaking cleanup'), 'chore');
    assert.equal(classifyMerge('some random merge subject'), 'other');
    assert.equal(classifyMerge(''), 'other');
    assert.equal(classifyMerge(undefined), 'other');
  });

  // --- isoWeekKey ---
  run('isoWeekKey matches known ISO week numbers', () => {
    assert.equal(isoWeekKey(new Date('2026-07-16T00:00:00.000Z')), '2026-W29');
    assert.equal(isoWeekKey(new Date('2026-06-30T00:00:00.000Z')), '2026-W27');
    assert.equal(isoWeekKey(new Date('2026-01-01T00:00:00.000Z')), '2026-W01');
  });

  // --- merge join + per-merge split ---
  run('buildReport splits session cost evenly across its merges and classifies each', () => {
    const sessions = [{
      session_id: 's1', project_dir: 'p', start_ts: '2026-07-01T00:00:00.000Z',
      total_cost_usd: 10, orchestrator_tokens_total: 100, worker_tokens_total: 50,
      subagents: { count: 1 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const merges = [
      { session_id: 's1', merge_commit: 'aaa', subject: 'feat(x): thing', ts: '2026-07-01T01:00:00.000Z' },
      { session_id: 's1', merge_commit: 'bbb', subject: 'fix(y): thing2', ts: '2026-07-01T02:00:00.000Z' },
    ];
    const report = buildReport({ sessions, merges, since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.merges_attributed, 2);
    assert.equal(report.totals.merge_rows_in_window, 2);
    assert.equal(report.totals.merges_unattributable, 0);
    assert.equal(report.totals.merges_excluded_by_scope, 0);
    assert.equal(report.totals.total_cost_usd, 10);
    assert.equal(report.totals.cost_per_merge_attributed, 5);
    assert.equal(report.sessions[0].cost_per_merge, 5);
    assert.equal(report.sessions[0].merges.length, 2);
    assert.equal(report.totals.by_merge_class.feat.count, 1);
    assert.equal(report.totals.by_merge_class.feat.cost_usd, 5);
    assert.equal(report.totals.by_merge_class.fix.count, 1);
    assert.equal(report.totals.sessions_with_zero_merges, 0);
  });
  run('buildReport lists a merge-less session separately with its cost', () => {
    const sessions = [{
      session_id: 's-nomrg', project_dir: 'p', start_ts: '2026-07-02T00:00:00.000Z',
      total_cost_usd: 3.5, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const report = buildReport({ sessions, merges: [], since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.merges_attributed, 0);
    assert.equal(report.totals.cost_per_merge_attributed, null);
    assert.equal(report.totals.sessions_with_zero_merges, 1);
    assert.equal(report.zero_merge_sessions[0].session_id, 's-nomrg');
    assert.equal(report.zero_merge_sessions[0].total_cost_usd, 3.5);
    assert.equal(report.sessions[0].cost_per_merge, null);
  });
  run('buildReport (Finding 2 regression) splits an unmatched merge into "unattributable" and lists its session id', () => {
    const sessions = [{
      session_id: 's-known', project_dir: 'p', start_ts: '2026-07-01T00:00:00.000Z',
      total_cost_usd: 4, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const merges = [
      { session_id: 's-known', merge_commit: 'aaa', subject: 'feat: known', ts: '2026-07-01T01:00:00.000Z' },
      { session_id: 's-ghost', merge_commit: 'bbb', subject: 'fix: no transcript for this session', ts: '2026-07-01T02:00:00.000Z' },
    ];
    const report = buildReport({ sessions, merges, since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.merge_rows_in_window, 2);
    assert.equal(report.totals.merges_attributed, 1);
    assert.equal(report.totals.merges_unattributable, 1);
    assert.deepEqual(report.totals.unattributable_session_ids, ['s-ghost']);
    assert.equal(report.totals.cost_per_merge_attributed, 4); // computed over attributed merges only
    assert.match(report.caveats.join('\n'), /unattributable/);
    assert.match(report.caveats.join('\n'), /s-ghost/);
  });
  run('buildReport (Finding 2 regression) classifies a merge whose session was excluded by scope separately from unattributable', () => {
    const sessions = []; // the excluded session never made it into the costed set
    const merges = [
      { session_id: 'excluded-session', merge_commit: 'ccc', subject: 'chore: excluded work', ts: '2026-07-01T01:00:00.000Z' },
    ];
    const report = buildReport({
      sessions, merges, since: '2026-07-01', until: null, excludedCount: 1,
      isExcludedSessionId: (id) => id === 'excluded-session',
    });
    assert.equal(report.totals.merge_rows_in_window, 1);
    assert.equal(report.totals.merges_attributed, 0);
    assert.equal(report.totals.merges_excluded_by_scope, 1);
    assert.equal(report.totals.merges_unattributable, 0);
    assert.deepEqual(report.totals.unattributable_session_ids, []);
    assert.match(report.caveats.join('\n'), /excluded by the/);
  });
  run('buildReport (Finding 2 regression) excludes a merge outside [since,until] from merge_rows_in_window', () => {
    const sessions = [{
      session_id: 's1', project_dir: 'p', start_ts: '2026-07-01T00:00:00.000Z',
      total_cost_usd: 2, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const merges = [
      { session_id: 's1', merge_commit: 'in', subject: 'feat: in window', ts: '2026-07-05T00:00:00.000Z' },
      { session_id: 's1', merge_commit: 'out', subject: 'feat: out of window', ts: '2026-08-01T00:00:00.000Z' },
    ];
    const report = buildReport({ sessions, merges, since: '2026-07-01', until: '2026-07-31', excludedCount: 0 });
    assert.equal(report.totals.merge_rows_in_window, 1);
    assert.equal(report.totals.merges_attributed, 1);
  });
  run('buildReport surfaces unknown-model tokens at the window-totals level', () => {
    const sessions = [{
      session_id: 's-unk', project_dir: 'p', start_ts: '2026-07-02T00:00:00.000Z',
      total_cost_usd: 1, orchestrator_tokens_total: 10, worker_tokens_total: 0,
      subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: { 'claude-made-up-9': 1500 },
    }];
    const report = buildReport({ sessions, merges: [], since: '2026-07-01', until: null, excludedCount: 0 });
    assert.deepEqual(report.totals.unknown_models, { 'claude-made-up-9': { tokens: 1500, sessions: 1 } });
  });
  run('buildReport computes orchestrator/worker token split percentages', () => {
    const sessions = [{
      session_id: 's-split', project_dir: 'p', start_ts: '2026-07-02T00:00:00.000Z',
      total_cost_usd: 1, orchestrator_tokens_total: 75, worker_tokens_total: 25,
      subagents: { count: 1 }, model_mix: {}, unknown_model_tokens: {},
    }];
    const report = buildReport({ sessions, merges: [], since: '2026-07-01', until: null, excludedCount: 0 });
    assert.equal(report.totals.token_split.orchestrator_pct, 75);
    assert.equal(report.totals.token_split.worker_pct, 25);
  });
  run('buildReport rolls sessions up by ISO week', () => {
    const sessions = [
      { session_id: 'a', project_dir: 'p', start_ts: '2026-06-30T00:00:00.000Z', total_cost_usd: 1, orchestrator_tokens_total: 1, worker_tokens_total: 0, subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {} },
      { session_id: 'b', project_dir: 'p', start_ts: '2026-07-16T00:00:00.000Z', total_cost_usd: 2, orchestrator_tokens_total: 1, worker_tokens_total: 0, subagents: { count: 0 }, model_mix: {}, unknown_model_tokens: {} },
    ];
    const report = buildReport({ sessions, merges: [], since: '2026-06-01', until: null, excludedCount: 0 });
    const weeks = report.weekly.map((w) => w.week);
    assert.deepEqual(weeks, ['2026-W27', '2026-W29']);
  });
  run('formatMarkdown renders without throwing and includes the structural-break caveats', () => {
    const report = buildReport({ sessions: [], merges: [], since: '2026-06-18', until: null, excludedCount: 2 });
    const md = formatMarkdown(report);
    assert.match(md, /Baseline Economics Report/);
    assert.match(md, /2026-07-14/);
    assert.match(md, /2026-07-15/);
    assert.match(md, /2026-06-30/);
    assert.match(md, /excluded by scope filter: 2/);
  });

  // --- exclusion filtering ---
  run('loadExclusionMatcher matches both full-UUID and 8-char-prefix keys', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'excl-'));
    const file = path.join(dir, 'excluded.json');
    fs.writeFileSync(file, JSON.stringify({
      excluded: {
        '11111111-1111-1111-1111-111111111111': 'full match',
        'abcdef12': 'prefix match',
      },
    }));
    const isExcluded = loadExclusionMatcher(file);
    assert.equal(isExcluded('11111111-1111-1111-1111-111111111111'), true);
    assert.equal(isExcluded('abcdef12-3456-7890-abcd-ef1234567890'), true);
    assert.equal(isExcluded('22222222-2222-2222-2222-222222222222'), false);
  });
  run('loadExclusionMatcher returns an always-false matcher for a missing file', () => {
    const isExcluded = loadExclusionMatcher(path.join(tmp, 'does-not-exist.json'));
    assert.equal(isExcluded('anything'), false);
  });

  // --- window filtering + discovery (real small fixture dirs) ---
  await runAsync('discoverSessions includes only sessions whose first timestamp falls in the window, and honors exclusion', async () => {
    const projectsRoot = fs.mkdtempSync(path.join(tmp, 'projects-'));
    const projectDir = path.join(projectsRoot, 'F--justsearch-public-fixture');
    fs.mkdirSync(projectDir, { recursive: true });

    writeTranscript(projectDir, 'in-window', [
      { timestamp: '2026-07-05T00:00:00.000Z', type: 'mode' },
      assistantLine('claude-sonnet-5', { input_tokens: 10, output_tokens: 10, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 }),
    ]);
    writeTranscript(projectDir, 'before-window', [
      { timestamp: '2026-01-01T00:00:00.000Z', type: 'mode' },
    ]);
    writeTranscript(projectDir, 'after-window', [
      { timestamp: '2026-12-01T00:00:00.000Z', type: 'mode' },
    ]);
    writeTranscript(projectDir, 'excluded-in-window', [
      { timestamp: '2026-07-06T00:00:00.000Z', type: 'mode' },
    ]);
    // non-matching project dir (no "justsearch" in slug) must be ignored entirely
    const otherDir = path.join(projectsRoot, 'some-other-project');
    fs.mkdirSync(otherDir, { recursive: true });
    writeTranscript(otherDir, 'unrelated', [{ timestamp: '2026-07-05T00:00:00.000Z', type: 'mode' }]);

    // subagents dir for in-window session
    const subDir = path.join(projectDir, 'in-window', 'subagents');
    fs.mkdirSync(subDir, { recursive: true });
    fs.writeFileSync(path.join(subDir, 'agent-x1.jsonl'), JSON.stringify(assistantLine('claude-haiku-4-5', { input_tokens: 5, output_tokens: 5, cache_creation_input_tokens: 0, cache_read_input_tokens: 0 })) + '\n', 'utf8');

    const sinceMs = new Date('2026-07-01T00:00:00.000Z').getTime();
    const untilMs = new Date('2026-07-31T23:59:59.000Z').getTime();
    const isExcluded = (id) => id === 'excluded-in-window';

    const { sessions, excludedCount } = await discoverSessions({ projectsRoot, sinceMs, untilMs, isExcluded });

    assert.equal(sessions.length, 1);
    assert.equal(sessions[0].sessionId, 'in-window');
    assert.equal(sessions[0].subagentPaths.length, 1);
    assert.equal(excludedCount, 1);
  });

  // --- loadMerges ---
  run('loadMerges parses NDJSON and skips malformed lines', () => {
    const dir = fs.mkdtempSync(path.join(tmp, 'merges-'));
    const file = path.join(dir, 'session-merges.ndjson');
    fs.writeFileSync(file, [
      JSON.stringify({ session_id: 's1', merge_commit: 'a', subject: 'feat: x', ts: '2026-07-01T00:00:00.000Z' }),
      'not json',
      JSON.stringify({ session_id: 's2', merge_commit: 'b', subject: 'fix: y', ts: '2026-07-02T00:00:00.000Z' }),
      '',
    ].join('\n'));
    const rows = loadMerges(file);
    assert.equal(rows.length, 2);
    assert.equal(rows[0].session_id, 's1');
    assert.equal(rows[1].session_id, 's2');
  });
  run('loadMerges returns an empty array for a missing file', () => {
    assert.deepEqual(loadMerges(path.join(tmp, 'no-such-merges.ndjson')), []);
  });
}

main().finally(() => {
  fs.rmSync(tmp, { recursive: true, force: true });
  if (failures.length) {
    console.error(`baseline-economics.test: ${failures.length} FAILED / ${passed} passed`);
    for (const f of failures) console.error('  ✗ ' + f);
    process.exit(1);
  }
  console.log(`baseline-economics.test: ${passed} passed`);
});
