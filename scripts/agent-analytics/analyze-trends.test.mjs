/**
 * Tempdoc 858 §6 — insufficiency is a first-class field in analyze-trends.
 *
 * The report's conclusions are its threshold verdicts ("⚠ Mean exceeds 30%
 * threshold. Many reads are loading entire files.") and, in --cutoff mode, the
 * before/after deltas. Those are cross-session generalisations; below
 * MIN_TREND_SESSIONS the report describes the sessions it read and nothing
 * beyond them (derivation on `MIN_TREND_SESSIONS`). The observed starved
 * run loaded 1 session and printed its verdicts anyway.
 *
 * Pure functions only — no telemetry directory is read or written.
 *
 * Run with: `node scripts/agent-analytics/analyze-trends.test.mjs`
 */

import assert from 'node:assert/strict';
import { computeTrends, formatMarkdown, formatComparisonMarkdown, MIN_TREND_SESSIONS } from './analyze-trends.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

/**
 * A session report whose unbounded-read rate (0.5) is over the 30% verdict
 * threshold and whose bash file-op rate (0.5) is over the 40% one, so both
 * verdicts are live and their suppression is observable.
 */
function report(i) {
  return {
    schema: 'agent-session-report.v1',
    session_id: `session-${i}0000000`,
    started_at: `2026-08-${String(i + 1).padStart(2, '0')}T00:00:00Z`,
    duration_seconds: 3600,
    tool_calls: { total: 100, failure_count: 5 },
    file_reads: { total: 20, unbounded_large_count: 10, by_file: [] },
    bash_commands: { total: 20, file_op_count: 10, build_count: 4, failed_build_count: 1 },
    file_edits: { total: 5, by_file: [] },
    subagents: { count: 0 },
    compactions: { count: 0 },
  };
}
const reports = (n, offset = 0) => Array.from({ length: n }, (_, i) => report(i + offset));

try {
  run('below the floor: insufficient set, counts and means still computed', () => {
    const t = computeTrends(reports(1), 3);
    assert.equal(t.sessions_analyzed, 1);
    assert.equal(t.sessions_skipped, 3);
    assert.equal(t.min_sessions, MIN_TREND_SESSIONS);
    assert.equal(t.insufficient, true);
    // Measurements survive — only the verdict is gated.
    assert.equal(t.unbounded_read_rate.mean, 0.5);
    assert.equal(t.bash_file_op_rate.mean, 0.5);
    assert.equal(t.failure_rate.rate, 0.05);
    assert.equal(t.build_failure_rate.total_builds, 4);
  });

  run('at the floor: not insufficient', () => {
    const t = computeTrends(reports(MIN_TREND_SESSIONS), 0);
    assert.equal(t.sessions_analyzed, MIN_TREND_SESSIONS);
    assert.equal(t.insufficient, false);
  });

  run('one session below the floor is still insufficient', () => {
    assert.equal(computeTrends(reports(MIN_TREND_SESSIONS - 1), 0).insufficient, true);
  });

  run('the starved report suppresses both threshold verdicts but prints the means they rest on', () => {
    const md = formatMarkdown(computeTrends(reports(1), 0));
    assert.match(md, /Insufficient sessions \(1 < 5\)/);
    assert.doesNotMatch(md, /Mean exceeds 30% threshold/);
    assert.doesNotMatch(md, /Mean exceeds 40% threshold/);
    assert.match(md, /\*\*Mean \(combined\):\*\* 50\.0%/);
    assert.match(md, /\*\*Mean:\*\* 50\.0%/);
    assert.match(md, /Sessions analyzed: 1/);
  });

  run('at the floor the same means DO produce both verdicts — the gate is the sample, not the data', () => {
    const md = formatMarkdown(computeTrends(reports(MIN_TREND_SESSIONS), 0));
    assert.doesNotMatch(md, /Insufficient sessions/);
    assert.match(md, /Mean exceeds 30% threshold/);
    assert.match(md, /Mean exceeds 40% threshold/);
  });

  run('a sufficient window whose means are under the thresholds still draws no verdict', () => {
    // Precision guard: the verdicts must key off the means, not off sufficiency.
    const quiet = reports(MIN_TREND_SESSIONS).map(r => ({
      ...r,
      file_reads: { total: 20, unbounded_large_count: 1, by_file: [] },
      bash_commands: { ...r.bash_commands, file_op_count: 1 },
    }));
    const md = formatMarkdown(computeTrends(quiet, 0));
    assert.doesNotMatch(md, /Insufficient sessions/);
    assert.doesNotMatch(md, /Mean exceeds/);
  });

  run('comparison mode flags a starved arm by name and keeps the deltas visible', () => {
    const before = computeTrends(reports(1), 0);
    const after = computeTrends(reports(MIN_TREND_SESSIONS, 1), 0);
    const md = formatComparisonMarkdown(before, after, '2026-08-02T00:00:00Z');
    assert.match(md, /Insufficient sessions in an arm \(before=1; need ≥ 5 each\)/);
    assert.match(md, /Before: 1 sessions \| After: 5 sessions/);
    assert.match(md, /Delta/);
  });

  run('comparison mode names both arms when both are starved, and neither when neither is', () => {
    const starved = formatComparisonMarkdown(
      computeTrends(reports(1), 0), computeTrends(reports(2, 1), 0), '2026-08-02T00:00:00Z');
    assert.match(starved, /before=1, after=2/);

    const healthy = formatComparisonMarkdown(
      computeTrends(reports(MIN_TREND_SESSIONS), 0),
      computeTrends(reports(MIN_TREND_SESSIONS, 5), 0),
      '2026-08-06T00:00:00Z');
    assert.doesNotMatch(healthy, /Insufficient sessions in an arm/);
  });
} finally {
  if (failures.length) {
    console.error(`analyze-trends.test: ${failures.length} failure(s)`);
    for (const f of failures) console.error(`  ${f}`);
    process.exit(1);
  }
  console.log(`analyze-trends.test: ${passed} passed`);
}
