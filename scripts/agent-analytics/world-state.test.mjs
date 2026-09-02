#!/usr/bin/env node
/**
 * Tests for scripts/agent-analytics/world-state.mjs (tempdoc 743 P-J).
 *
 * Two layers: (1) unit tests against the pure `computeVerdict` function on synthetic worktree
 * rows — no I/O; (2) a smoke test that runs the CLI as a real subprocess against this actual repo
 * checkout, asserting it completes quickly, exits 0, and both output modes (markdown + --json)
 * have the expected sections/shape. The smoke test intentionally does NOT assert on live values
 * (worktree names, session counts) since those vary run-to-run in a shared multi-agent repo —
 * only structure.
 *
 * Run with: `node scripts/agent-analytics/world-state.test.mjs`
 */

import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { computeVerdict, gatherAdrReview } from './world-state.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.join(HERE, 'world-state.mjs');
const REPO_ROOT = path.resolve(HERE, '..', '..');

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.stack || e.message}`);
  }
}

// --- computeVerdict unit tests (synthetic rows) ---

run('computeVerdict: dirty + old last commit -> DIRTY-IDLE', () => {
  const v = computeVerdict({ dirty: true, aheadCount: 2, pushed: false, lastCommitAgeDays: 10 });
  assert.equal(v, 'DIRTY-IDLE');
});

run('computeVerdict: dirty + recent last commit -> ACTIVE (not stale yet)', () => {
  const v = computeVerdict({ dirty: true, aheadCount: 2, pushed: false, lastCommitAgeDays: 0.2 });
  assert.equal(v, 'ACTIVE');
});

run('computeVerdict: clean + ahead>0 + unpushed + old -> STRANDED-FINISHED', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: false, lastCommitAgeDays: 5 });
  assert.equal(v, 'STRANDED-FINISHED');
});

run('computeVerdict: clean + ahead>0 + PUSHED + old -> not stranded (pushed work is not lost)', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: true, lastCommitAgeDays: 5 });
  assert.notEqual(v, 'STRANDED-FINISHED');
});

run('computeVerdict: clean + ahead>0 + unpushed + RECENT -> ACTIVE (not stale yet, still working)', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: false, lastCommitAgeDays: 0.5 });
  assert.equal(v, 'ACTIVE');
});

run('computeVerdict: clean + 0 ahead -> STALE-CANDIDATE regardless of age', () => {
  assert.equal(computeVerdict({ dirty: false, aheadCount: 0, pushed: true, lastCommitAgeDays: 0.1 }), 'STALE-CANDIDATE');
  assert.equal(computeVerdict({ dirty: false, aheadCount: 0, pushed: null, lastCommitAgeDays: 40 }), 'STALE-CANDIDATE');
});

run('computeVerdict: clean + ahead>0 but age unknown (probe failed) -> ACTIVE, never fabricates STRANDED', () => {
  const v = computeVerdict({ dirty: false, aheadCount: 3, pushed: false, lastCommitAgeDays: null });
  assert.equal(v, 'ACTIVE');
});

run('computeVerdict: everything unknown (all probes failed) -> ACTIVE (safe default)', () => {
  const v = computeVerdict({ dirty: null, aheadCount: null, pushed: null, lastCommitAgeDays: null });
  assert.equal(v, 'ACTIVE');
});

// --- gatherAdrReview unit tests (synthetic ADR fixture + a pinned `now`) ---
//
// The live repo currently has ZERO stale ADRs, so asserting only against it would be a
// vacuously green section. These fixtures pin a synthetic old date so the arithmetic is
// exercised in both directions.

function withAdrFixture(files, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'world-state-adr-'));
  try {
    for (const [name, body] of Object.entries(files)) fs.writeFileSync(path.join(dir, name), body, 'utf8');
    return fn(dir);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

const NOW = Date.parse('2026-09-02T00:00:00Z');
const adrDoc = (lastReviewed) =>
  `---\nstatus: accepted\nprobes: [some-probe]\nlast_reviewed: ${lastReviewed}\n---\n\n# ADR\n`;

run('gatherAdrReview: reports exactly the ADR past the window, by name', () => {
  const res = withAdrFixture(
    {
      // 2025-01-01 -> 609 days before NOW, well past 183.
      '0001-old.md': adrDoc('2025-01-01'),
      // 2026-08-01 -> 32 days before NOW, comfortably fresh.
      '0002-fresh.md': adrDoc('2026-08-01'),
    },
    (dir) => gatherAdrReview({ adrDir: dir, now: NOW, thresholdDays: 183 }),
  );
  assert.equal(res.available, true);
  assert.equal(res.scanned, 2);
  assert.equal(res.staleCount, 1, 'exactly one of the two fixtures is past the window');
  assert.deepEqual(res.rows.map((r) => r.adr), ['0001-old.md']);
  assert.equal(res.rows[0].lastReviewed, '2025-01-01');
  assert.equal(res.rows[0].ageDays, 609);
});

run('gatherAdrReview: the window is the threshold argument, not a hardcoded 183', () => {
  const files = { '0001-old.md': adrDoc('2025-01-01'), '0002-fresh.md': adrDoc('2026-08-01') };
  // A 10-day window makes BOTH stale — proves the comparison reads `thresholdDays`.
  const tight = withAdrFixture(files, (dir) => gatherAdrReview({ adrDir: dir, now: NOW, thresholdDays: 10 }));
  assert.equal(tight.staleCount, 2);
  // A 1000-day window makes NEITHER stale.
  const loose = withAdrFixture(files, (dir) => gatherAdrReview({ adrDir: dir, now: NOW, thresholdDays: 1000 }));
  assert.equal(loose.staleCount, 0);
});

run('gatherAdrReview: missing/unparseable last_reviewed is stale, not fresh', () => {
  const res = withAdrFixture(
    {
      '0001-no-date.md': '---\nstatus: accepted\n---\n\n# ADR\n',
      '0002-no-frontmatter.md': '# ADR\n\nno frontmatter at all\n',
      '0003-garbage-date.md': adrDoc('"not-a-date"'),
      '0004-fresh.md': adrDoc('2026-08-01'),
    },
    (dir) => gatherAdrReview({ adrDir: dir, now: NOW, thresholdDays: 183 }),
  );
  assert.equal(res.staleCount, 3, 'an ADR declaring no usable review date must not read as reviewed');
  assert.deepEqual(res.rows.map((r) => r.adr), ['0001-no-date.md', '0002-no-frontmatter.md', '0003-garbage-date.md']);
});

run('gatherAdrReview: README.md is the index, not a decision — never scanned', () => {
  const res = withAdrFixture(
    { 'README.md': '# Architecture Decision Records\n', '0001-fresh.md': adrDoc('2026-08-01') },
    (dir) => gatherAdrReview({ adrDir: dir, now: NOW, thresholdDays: 183 }),
  );
  assert.equal(res.scanned, 1);
  assert.equal(res.staleCount, 0);
});

run('gatherAdrReview: a missing ADR directory degrades to unavailable, never throws', () => {
  const res = gatherAdrReview({ adrDir: path.join(os.tmpdir(), 'world-state-adr-does-not-exist'), now: NOW });
  assert.equal(res.available, false);
  assert.ok(typeof res.reason === 'string' && res.reason.length > 0);
});

// --- Smoke test: real subprocess against this actual repo checkout ---

run('CLI smoke: markdown mode runs, exits 0, all five sections present, mentions this worktree', () => {
  const out = execFileSync(process.execPath, [CLI], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000 });
  assert.match(out, /^# World state/);
  assert.match(out, /## Worktrees/);
  assert.match(out, /## Live sessions/);
  assert.match(out, /## Tempdoc numbers/);
  assert.match(out, /## Stack/);
  // Tempdoc 861 §6.4 `orientation` occasion — read-only agent-spawns section.
  assert.match(out, /## Agent spawns/);
  // Tempdoc 884 design decision 4 — the ADR review section prints even at zero. A section
  // that vanishes when empty teaches nothing, so assert the COUNT LINE, not just the heading.
  assert.match(out, /## ADR review/);
  assert.match(out, /^\d+ of \d+ ADR\(s\) past the \d+-day review window$/m);
  assert.match(out, /VERDICT/);
});

run('CLI smoke: --json mode runs, exits 0, output is valid JSON with the expected top-level shape', () => {
  const out = execFileSync(process.execPath, [CLI, '--json'], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000 });
  const parsed = JSON.parse(out);
  assert.ok(Array.isArray(parsed.worktrees) && parsed.worktrees.length > 0);
  for (const w of parsed.worktrees) {
    assert.ok(['ACTIVE', 'STRANDED-FINISHED', 'STALE-CANDIDATE', 'DIRTY-IDLE'].includes(w.verdict), `unexpected verdict "${w.verdict}" for worktree "${w.name}"`);
  }
  assert.ok(typeof parsed.sessions.available === 'boolean');
  assert.ok(typeof parsed.tempdocNumbers.nextFree === 'number');
  assert.ok(typeof parsed.stack.available === 'boolean');
  assert.ok(typeof parsed.adrReview.available === 'boolean');
  if (parsed.adrReview.available) {
    assert.ok(parsed.adrReview.scanned > 0, 'the real docs/decisions/ has ADRs; 0 scanned means the path is wrong');
    assert.equal(parsed.adrReview.thresholdDays, 183, 'the window comes from governance/adr-probes.v1.json reviewStaleDays');
    assert.equal(parsed.adrReview.rows.length, parsed.adrReview.staleCount);
  }
  assert.ok(typeof parsed.agentSpawns.available === 'boolean');
  if (parsed.agentSpawns.available) {
    assert.ok(Array.isArray(parsed.agentSpawns.registered));
    assert.ok(Array.isArray(parsed.agentSpawns.observed));
    // [A4]/861 §6.4: orientation never kills — no entry in either list may carry a spendable reap.
    for (const e of [...parsed.agentSpawns.registered, ...parsed.agentSpawns.observed]) {
      assert.notEqual(e.disposition, 'reap', 'orientation is advisory-only; a "reap" disposition here would be a spendable kill list');
    }
  }
});

run('CLI smoke: completes in under 10s (perf budget, tempdoc 743 P-J requirement)', () => {
  const start = Date.now();
  execFileSync(process.execPath, [CLI], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 15000 });
  const elapsedMs = Date.now() - start;
  assert.ok(elapsedMs < 10000, `world-state.mjs took ${elapsedMs}ms, budget is 10000ms`);
});

if (failures.length) {
  console.error(`world-state.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`world-state.test: ${passed} passed`);
