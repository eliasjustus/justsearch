/**
 * Tempdoc 858 §4.2 item 1 — the task_type lookup in score-session.mjs.
 *
 * Tempdoc 622 moved the LLM judge's task_type into the `inference` block of
 * outcomes.ndjson (`inferenceBlock` in outcome-session.mjs). score-session.mjs kept reading the
 * flat `outcome.task_type`, which resolves to undefined for every post-622 row,
 * so BOTH `suppressForTypes` entries in its RULES array were dead: WASTEFUL kept
 * firing on `feature` sessions and THRASHING on `implementation` ones — exactly
 * the two populations tempdoc 277 C4 measured those rules to be wrong or
 * inverted on. These tests assert the BEHAVIOUR (which flags come out), not just
 * that a task type resolves.
 *
 * Pure functions only — no telemetry directory is read or written.
 *
 * Run with: `node scripts/agent-analytics/score-session.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { resolveTaskType, resolveEntries, classifySession, computeTypeCeilings } from './score-session.mjs';
import { outcomeForSession } from './outcome-session.mjs';
import { repoRoot, TELEMETRY_DIR, OUTCOMES_FILE } from './lib/telemetry-io.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

// Trips WASTEFUL: unbounded_read_pct > 0.10 && bash_fileop_pct > 0.30.
const WASTEFUL_SIGNALS = {
  unbounded_read_pct: 0.20, bash_fileop_pct: 0.50,
  rapid_reedit_count: 0, hot_file_concentration: 0,
};
// Trips THRASHING: rapid_reedit_count > 10 && hot_file_concentration > 0.20.
const THRASHING_SIGNALS = {
  unbounded_read_pct: 0, bash_fileop_pct: 0,
  rapid_reedit_count: 20, hot_file_concentration: 0.50,
};
const BOTH_SIGNALS = {
  unbounded_read_pct: 0.20, bash_fileop_pct: 0.50,
  rapid_reedit_count: 20, hot_file_concentration: 0.50,
};

/** A post-622 outcomes.ndjson row, as `buildOutcome` in outcome-session.mjs writes it. */
const post622 = (taskType) => ({
  session_id: 's-1',
  facts: { merged: { kind: 'fact', value: false }, tempdocs: [] },
  inference: { kind: 'inference', source: 'llm-judge', task_type: taskType, task_completion: 'complete' },
});
/** A pre-622 row, which carried task_type at the top level. */
const legacyFlat = (taskType) => ({ session_id: 's-1', task_type: taskType });

try {
  // --- resolveTaskType: both row shapes ---

  run('resolveTaskType reads task_type out of the post-622 inference block', () => {
    assert.equal(resolveTaskType(post622('feature')), 'feature');
  });

  run('resolveTaskType still resolves a legacy flat-shaped row', () => {
    assert.equal(resolveTaskType(legacyFlat('refactor')), 'refactor');
  });

  run('resolveTaskType prefers the inference block when a row carries both', () => {
    const both = { ...legacyFlat('stale'), inference: { task_type: 'feature' } };
    assert.equal(resolveTaskType(both), 'feature');
  });

  run('resolveTaskType yields null for a row with neither, and does not throw on a missing row', () => {
    assert.equal(resolveTaskType({ session_id: 's-1', facts: {}, inference: null }), null);
    assert.equal(resolveTaskType({}), null);
    assert.equal(resolveTaskType(undefined), null);   // outcomesMap.get() miss
    assert.equal(resolveTaskType(null), null);
  });

  // --- The behaviour the lookup gates: emitted flags ---

  run('WASTEFUL is suppressed for a post-622 feature row (tempdoc 277 C4: r=+0.51 with completion)', () => {
    assert.deepEqual(classifySession(WASTEFUL_SIGNALS, resolveTaskType(post622('feature'))), []);
  });

  run('THRASHING is suppressed for a post-622 implementation row (C4: 33% of completed, 0% of partial)', () => {
    assert.deepEqual(classifySession(THRASHING_SIGNALS, resolveTaskType(post622('implementation'))), []);
  });

  run('the same signals DO flag when the row carries no task type — the suppression is what changed, not the rule', () => {
    assert.deepEqual(classifySession(WASTEFUL_SIGNALS, resolveTaskType(post622(null))), ['WASTEFUL']);
    assert.deepEqual(classifySession(THRASHING_SIGNALS, resolveTaskType({})), ['THRASHING']);
  });

  run('each suppression is type-specific — a feature row suppresses only WASTEFUL, not THRASHING', () => {
    // Guards against a fix that resolves the type but suppresses too broadly.
    assert.deepEqual(classifySession(BOTH_SIGNALS, resolveTaskType(post622('feature'))), ['THRASHING']);
    assert.deepEqual(classifySession(BOTH_SIGNALS, resolveTaskType(post622('implementation'))), ['WASTEFUL']);
  });

  run('a task type with no suppression rule leaves both flags standing', () => {
    assert.deepEqual(
      classifySession(BOTH_SIGNALS, resolveTaskType(post622('investigation'))),
      ['WASTEFUL', 'THRASHING'],
    );
  });

  // --- Regression guard: the exact defect, stated as a test ---

  run('the flat-only read that shipped resolves null on a post-622 row and lets both flags fire', () => {
    const row = post622('feature');
    const flatOnly = row.task_type ?? null;      // the flat read that shipped
    assert.equal(flatOnly, null);
    assert.deepEqual(classifySession(WASTEFUL_SIGNALS, flatOnly), ['WASTEFUL']);
    // ...and the fixed lookup on the same row does not.
    assert.deepEqual(classifySession(WASTEFUL_SIGNALS, resolveTaskType(row)), []);
  });

  // --- computeTypeCeilings no longer collapses to one null type ---

  run('computeTypeCeilings partitions by resolved type instead of collapsing to zero groups', () => {
    const rows = [post622('feature'), post622('feature'), post622('implementation')];
    const entries = rows.map(r => ({ signals: WASTEFUL_SIGNALS, taskType: resolveTaskType(r) }));
    const ceilings = computeTypeCeilings(entries);
    assert.equal(ceilings.size, 2);
    assert.deepEqual([...ceilings.keys()].sort(), ['feature', 'implementation']);

    // The pre-fix read produced no groups at all, so every session was scored
    // against the global ceilings.
    const flatEntries = rows.map(r => ({ signals: WASTEFUL_SIGNALS, taskType: r.task_type ?? null }));
    assert.equal(computeTypeCeilings(flatEntries).size, 0);
  });
  // --- The recompute path (tempdoc 858 §3: consumers recompute, not read) ---
  //
  // main() no longer reads outcomes.ndjson; it evaluates
  // `resolveTaskType(outcomeForSession(id, { inputs }))`. These exercise that
  // exact composition against synthetic join inputs, so no telemetry file is
  // touched and the answer demonstrably cannot have come from one.

  const OUTCOMES_PATH = path.join(repoRoot, TELEMETRY_DIR, OUTCOMES_FILE);
  const PINNED = 1_700_000_000_000;

  /** Synthetic join inputs — the shape loadJoinInputs() returns, no disk needed. */
  const joinInputs = () => ({
    sessions: new Map([
      ['judged', [{ event: 'post_tool_use', tool_name: 'Edit', ts: '2026-08-19T00:00:00Z' }]],
      ['unjudged', [{ event: 'post_tool_use', tool_name: 'Edit', ts: '2026-08-19T01:00:00Z' }]],
    ]),
    mergeRecords: [],
    judgeMap: new Map([
      ['judged', { ts: '2026-08-01T12:00:00.000Z', task_completion: 'complete', task_type: 'feature' }],
    ]),
  });

  run('a task type reaches the suppression through the recompute path, with no outcomes.ndjson read', () => {
    const existedBefore = fs.existsSync(OUTCOMES_PATH);
    const taskType = resolveTaskType(outcomeForSession('judged', { inputs: joinInputs(), nowMs: PINNED }));
    assert.equal(taskType, 'feature');
    // ...and the suppression it gates actually fires end-to-end.
    assert.deepEqual(classifySession(WASTEFUL_SIGNALS, taskType), []);
    // The view is computed, not cached: the call neither needs nor creates the file.
    assert.equal(fs.existsSync(OUTCOMES_PATH), existedBefore);
  });

  run('a session the judge never scored yields null rather than throwing — a data gap, not a wiring one', () => {
    const taskType = resolveTaskType(outcomeForSession('unjudged', { inputs: joinInputs(), nowMs: PINNED }));
    assert.equal(taskType, null);
    // Pins the honest consequence: with no judge entry the rules are unsuppressed.
    assert.deepEqual(classifySession(WASTEFUL_SIGNALS, taskType), ['WASTEFUL']);
  });

  run('an unknown session id recomputes to a null inference instead of throwing', () => {
    // main() maps over session REPORTS, which can name a session the event
    // store has no rows for.
    const rec = outcomeForSession('never-seen', { inputs: joinInputs(), nowMs: PINNED });
    assert.equal(rec.inference, null);
    assert.equal(resolveTaskType(rec), null);
  });

  run('resolveEntries gives each report its OWN task type from one hoisted load', () => {
    // Guards the --all loop optimisation specifically: hoisting loadJoinInputs()
    // out of the loop must not let one session's type stand in for another's.
    // Asserted through resolveEntries, which IS the loop main() runs — asserting
    // it through outcomeForSession instead would pass even with the loop keyed
    // on a fixed report.
    const reports = [
      { session_id: 'judged', tool_calls: { total: 50 } },
      { session_id: 'unjudged', tool_calls: { total: 50 } },
      { session_id: 'never-seen', tool_calls: { total: 50 } },
    ];
    const entries = resolveEntries(reports, joinInputs());
    assert.deepEqual(entries.map(e => e.taskType), ['feature', null, null]);
    assert.deepEqual(entries.map(e => e.report.session_id), ['judged', 'unjudged', 'never-seen']);
    // Signals are still extracted per report, not shared.
    assert.equal(entries.length, 3);
    assert.ok(entries.every(e => typeof e.signals.unbounded_read_pct === 'number'));
  });

  run('the module reads no outcomes store at all — the structural invariant §3 sets', () => {
    // Same reasoning as the sibling guard in correlate-signals.test.mjs: the
    // sourcing decision lives on one line of main() and is not reachable from a
    // unit test, so pin it in the source.
    const src = fs.readFileSync(new URL('./score-session.mjs', import.meta.url), 'utf8');
    const code = src.replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '');   // strip comments
    assert.doesNotMatch(code, /OUTCOMES_FILE|outcomes\.ndjson/,
      'score-session must not read the outcomes report; recompute via outcomeForSession');
    assert.match(code, /resolveEntries\(reports, loadJoinInputs\(\)\)/,
      'the --all pass must hoist loadJoinInputs() out of the loop');
  });

  run('resolveEntries throws when `inputs` is omitted rather than silently reloading per session', () => {
    // outcomeForSession self-loads on a missing `inputs`, so an unguarded
    // omission would be an invisible N-times-the-corpus reparse.
    const reports = [{ session_id: 'judged', tool_calls: { total: 50 } }];
    assert.throws(() => resolveEntries(reports), /`inputs` is required/);
    assert.throws(() => resolveEntries(reports, null), /hoisted out of the loop/);
  });

  run('resolveEntries feeds computeTypeCeilings real groups end-to-end', () => {
    const reports = [
      { session_id: 'judged', tool_calls: { total: 50 } },
      { session_id: 'unjudged', tool_calls: { total: 50 } },
    ];
    const ceilings = computeTypeCeilings(resolveEntries(reports, joinInputs()));
    assert.deepEqual([...ceilings.keys()], ['feature']);
  });
} finally {
  if (failures.length) {
    console.error(`score-session.test: ${failures.length} failure(s)`);
    for (const f of failures) console.error(`  ${f}`);
    process.exit(1);
  }
  console.log(`score-session.test: ${passed} passed`);
}
