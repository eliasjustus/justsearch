/**
 * Tempdoc 743 — output-path resolution — and tempdoc 829 R8 — fail-loud message
 * builders — unit tests for otlp-sink-ensure.
 *
 * The hook's only pure, spawn-free surfaces are: the main-checkout-rooted output
 * path computed at module load (`SINK_OUT_DIR`, derived from `mainRepoRoot`,
 * tempdoc 743), and (829 R8) the death-warning/staleness-notice message builders
 * plus the launch-log tail reader. `startSink()`/`main()` shell out to `spawn()`
 * and a live socket probe, so they are deliberately NOT exercised here (would
 * require a real child process/port); that path is covered by the manual
 * negative probe recorded in the PR (simulated sink death via a broken PYTHON
 * binary and a real ModuleNotFoundError repro).
 *
 * Run with: `node scripts/agent-analytics/hooks/otlp-sink-ensure.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  mainRepoRoot,
  SINK_OUT_DIR,
  SINK_SCRIPT,
  SINK_PORT,
  buildDeathWarning,
  buildStalenessNotice,
  tailFileLines,
  newestFileMtimeMs,
  STALE_DATA_MS,
} from './otlp-sink-ensure.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- tempdoc 743: output-path resolution ---

run('mainRepoRoot is absolute', () => {
  assert.ok(path.isAbsolute(mainRepoRoot), `expected absolute path, got ${mainRepoRoot}`);
});

run('SINK_OUT_DIR is absolute (never a relative default)', () => {
  assert.ok(path.isAbsolute(SINK_OUT_DIR), `expected absolute path, got ${SINK_OUT_DIR}`);
});

run('SINK_OUT_DIR is rooted under mainRepoRoot, not the invoking worktree', () => {
  const rel = path.relative(mainRepoRoot, SINK_OUT_DIR);
  assert.ok(
    !rel.startsWith('..') && !path.isAbsolute(rel),
    `expected SINK_OUT_DIR under mainRepoRoot; mainRepoRoot=${mainRepoRoot} SINK_OUT_DIR=${SINK_OUT_DIR}`
  );
});

run('SINK_OUT_DIR ends with tmp/agent-telemetry/otlp', () => {
  const expected = path.join('tmp', 'agent-telemetry', 'otlp');
  assert.ok(
    SINK_OUT_DIR.endsWith(expected),
    `expected SINK_OUT_DIR to end with ${expected}, got ${SINK_OUT_DIR}`
  );
});

run('SINK_OUT_DIR does not resolve against a worktree tmp/ dir', () => {
  // The defect this fix closes: `.claude/worktrees/<name>/tmp/...` must never
  // appear in the resolved sink output path.
  assert.ok(
    !SINK_OUT_DIR.includes(path.join('.claude', 'worktrees')),
    `SINK_OUT_DIR must not be worktree-rooted, got ${SINK_OUT_DIR}`
  );
});

run('SINK_SCRIPT and SINK_PORT are still defined (no accidental removal)', () => {
  assert.ok(SINK_SCRIPT && SINK_SCRIPT.endsWith('otlp-sink.py'));
  assert.equal(SINK_PORT, 4318);
});

// --- 829 R8: buildDeathWarning ---

run('death warning names the manual repro command and the pip fix', () => {
  const msg = buildDeathWarning({ python: 'python', sinkScript: 'scripts/agent-analytics/otlp-sink.py', tailLines: [] });
  assert.ok(msg.includes('FAILED to start'));
  assert.ok(msg.includes('NOT being captured'));
  assert.ok(msg.includes('python scripts/agent-analytics/otlp-sink.py'));
  assert.ok(msg.includes('pip install opentelemetry-proto'));
});

run('death warning appends the launch-log tail when present', () => {
  const msg = buildDeathWarning({
    python: 'python',
    sinkScript: 'otlp-sink.py',
    tailLines: ['Traceback (most recent call last):', "ModuleNotFoundError: No module named 'opentelemetry'"],
  });
  assert.ok(msg.includes('Last stderr:'));
  assert.ok(msg.includes("ModuleNotFoundError: No module named 'opentelemetry'"));
});

run('death warning omits the tail suffix when there is no captured stderr', () => {
  const msg = buildDeathWarning({ python: 'python', sinkScript: 'otlp-sink.py', tailLines: [] });
  assert.ok(!msg.includes('Last stderr:'));
});

// --- buildStalenessNotice ---

run('staleness notice is null with no data yet (fresh, not stale)', () => {
  assert.equal(buildStalenessNotice({ newestMtimeMs: null, nowMs: Date.now(), outDir: 'x', staleMs: STALE_DATA_MS }), null);
});

run('staleness notice is null just under the threshold', () => {
  const now = Date.now();
  const notice = buildStalenessNotice({ newestMtimeMs: now - (STALE_DATA_MS - 1000), nowMs: now, outDir: 'x', staleMs: STALE_DATA_MS });
  assert.equal(notice, null);
});

run('staleness notice fires at/over the threshold and reports the age in days', () => {
  const now = Date.now();
  const eightDaysMs = 8 * 24 * 60 * 60 * 1000;
  const notice = buildStalenessNotice({ newestMtimeMs: now - eightDaysMs, nowMs: now, outDir: '/tmp/otlp', staleMs: STALE_DATA_MS });
  assert.ok(notice);
  assert.ok(notice.includes('8d old'));
  assert.ok(notice.includes('/tmp/otlp'));
});

// --- tailFileLines ---

const TMP_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'otlp-sink-ensure-test-'));

run('tailFileLines returns [] for a missing file', () => {
  assert.deepEqual(tailFileLines(path.join(TMP_DIR, 'does-not-exist.log')), []);
});

run('tailFileLines returns [] for an empty file', () => {
  const p = path.join(TMP_DIR, 'empty.log');
  fs.writeFileSync(p, '');
  assert.deepEqual(tailFileLines(p), []);
});

run('tailFileLines returns the last N non-empty lines', () => {
  const p = path.join(TMP_DIR, 'multi.log');
  fs.writeFileSync(p, 'line1\nline2\nline3\nline4\n');
  assert.deepEqual(tailFileLines(p, { n: 2 }), ['line3', 'line4']);
});

run('tailFileLines respects maxBytes (only reads the tail of a large file)', () => {
  const p = path.join(TMP_DIR, 'large.log');
  const filler = 'x'.repeat(200) + '\n';
  fs.writeFileSync(p, filler.repeat(50) + 'final-line\n');
  const lines = tailFileLines(p, { n: 3, maxBytes: 64 });
  assert.ok(lines.includes('final-line'));
});

// --- newestFileMtimeMs ---

run('newestFileMtimeMs returns null for a missing directory', () => {
  assert.equal(newestFileMtimeMs(path.join(TMP_DIR, 'missing-dir')), null);
});

run('newestFileMtimeMs returns null for an empty directory', () => {
  const d = path.join(TMP_DIR, 'empty-dir');
  fs.mkdirSync(d);
  assert.equal(newestFileMtimeMs(d), null);
});

run('newestFileMtimeMs picks the most recently modified file', () => {
  const d = path.join(TMP_DIR, 'dated-dir');
  fs.mkdirSync(d);
  const older = path.join(d, 'a.jsonl');
  const newer = path.join(d, 'b.jsonl');
  fs.writeFileSync(older, '{}');
  const now = Date.now();
  fs.utimesSync(older, new Date(now - 60_000), new Date(now - 60_000));
  fs.writeFileSync(newer, '{}');
  fs.utimesSync(newer, new Date(now), new Date(now));
  assert.equal(newestFileMtimeMs(d), fs.statSync(newer).mtimeMs);
});

fs.rmSync(TMP_DIR, { recursive: true, force: true });

// --- Report ---
if (failures.length > 0) {
  console.error(`otlp-sink-ensure.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`otlp-sink-ensure.test: all ${passed} checks passed`);
