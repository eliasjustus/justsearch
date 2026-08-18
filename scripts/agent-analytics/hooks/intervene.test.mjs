/**
 * Tempdoc 520 P1b — unit tests for intervene's decision logic: the hot-file
 * cap (previously untested) and the F-7c explicit-limit cap.
 *
 * Run with: `node scripts/agent-analytics/hooks/intervene.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { shouldBlockHotFile, shouldCapExplicitLimit, getOtherPathsWithSameBasename } from './intervene.mjs';
import { telemetryDir } from '../lib/hook-base.mjs';

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

// --- shouldBlockHotFile: unbounded re-reads gated at the cap ---
run('below cap → allow', () => assert.equal(shouldBlockHotFile(9, true), false));
run('at cap → block', () => assert.equal(shouldBlockHotFile(10, true), true));
run('over cap → block', () => assert.equal(shouldBlockHotFile(15, true), true));
run('targeted read never blocks, even over cap', () => assert.equal(shouldBlockHotFile(50, false), false));
run('custom cap respected', () => assert.equal(shouldBlockHotFile(3, true, 3), true));

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'intervene-test-'));
try {
  // --- the blanket >8KB auto-limit was REMOVED 2026-08-18 (owner decision): a large
  // unbounded read must now pass through untouched, so the ONLY read protections left
  // are the hot-file cap (above) and the F-7c explicit-limit cap (below). The
  // "no explicit offset/limit → declines" case asserts the removal held.
  // --- shouldCapExplicitLimit (tempdoc 727 F-7c): agent-specified offset/limit still capped
  // when the ACTUAL requested slice is too dense, using real per-line measurement rather
  // than a file-wide average (a global average would be skewed by outlier-length lines).
  run('small file with explicit limit → no cap regardless of density', () => {
    const small = path.join(tmpDir, 'small-explicit.txt');
    fs.writeFileSync(small, 'x'.repeat(100));
    assert.equal(shouldCapExplicitLimit({ file_path: small, offset: 1, limit: 5 }), null);
  });
  run('large file, no explicit offset/limit → this fn declines (no-range reads are unmanaged)', () => {
    const big = path.join(tmpDir, 'big-noexplicit.txt');
    fs.writeFileSync(big, 'x'.repeat(20_000));
    assert.equal(shouldCapExplicitLimit({ file_path: big }), null);
  });
  run('large file, explicit limit over a few short lines → no cap needed', () => {
    const big = path.join(tmpDir, 'big-shortlines.txt');
    // 5000 short lines ("short line N\n") — a small requested slice stays well under the ceiling.
    fs.writeFileSync(big, Array.from({ length: 5000 }, (_, i) => `short line ${i}`).join('\n'));
    assert.equal(shouldCapExplicitLimit({ file_path: big, offset: 1, limit: 20 }), null);
  });
  run('large file, explicit limit over many DENSE lines → capped down', () => {
    const big = path.join(tmpDir, 'big-denselines.txt');
    // Mirrors the real incident's shape: a handful of very long lines (like a tempdoc's
    // frontmatter status field) followed by many normal lines. Requesting a limit spanning
    // the dense region should be capped even though the file average would look modest.
    const denseLines = Array.from({ length: 5 }, () => 'D'.repeat(20_000));
    const normalLines = Array.from({ length: 2000 }, (_, i) => `normal line ${i}`);
    fs.writeFileSync(big, [...denseLines, ...normalLines].join('\n'));
    const r = shouldCapExplicitLimit({ file_path: big, offset: 1, limit: 100 });
    assert.ok(r, 'expected a cap for a slice dominated by 5 x 20,000-char lines');
    assert.ok(r.updatedInput.limit < 100, `expected a reduced limit, got ${r.updatedInput.limit}`);
  });
  run('VERY large file (multi-MB), small requested slice near the start → bounded read still correct', () => {
    // Tempdoc 727 review Finding B: shouldCapExplicitLimit used to read+split the ENTIRE
    // file for every explicit-limit call over 8KB, defeating offset/limit's purpose on a
    // genuinely huge file. This confirms the bounded read (readLineRangeBounded) still
    // produces the correct answer when the needed lines sit at the very start of a file
    // far larger than the requested slice.
    const huge = path.join(tmpDir, 'huge-smallslice.txt');
    const denseStart = Array.from({ length: 5 }, () => 'D'.repeat(20_000)); // dense first 5 lines
    const millionNormalLines = Array.from({ length: 500_000 }, (_, i) => `normal line ${i}`); // ~7MB tail
    fs.writeFileSync(huge, [...denseStart, ...millionNormalLines].join('\n'));
    const r = shouldCapExplicitLimit({ file_path: huge, offset: 1, limit: 100 });
    assert.ok(r, 'expected a cap: the requested 100-line slice includes the 5 dense lines');
    assert.ok(r.updatedInput.limit < 100, `expected a reduced limit, got ${r.updatedInput.limit}`);
    assert.ok(r.updatedInput.limit >= 5, 'the cap should not undershoot below the 5 lines that are actually dense');
  });
  run('large file, requested slice PAST the dense region → no cap needed', () => {
    const big = path.join(tmpDir, 'big-pastdense.txt');
    const denseLines = Array.from({ length: 5 }, () => 'D'.repeat(20_000));
    const normalLines = Array.from({ length: 2000 }, (_, i) => `normal line ${i}`);
    fs.writeFileSync(big, [...denseLines, ...normalLines].join('\n'));
    // Offset past the 5 dense lines — the requested slice is all short "normal" lines.
    assert.equal(shouldCapExplicitLimit({ file_path: big, offset: 10, limit: 100 }), null);
  });
  run('missing file_path → no cap', () => {
    assert.equal(shouldCapExplicitLimit({ offset: 1, limit: 10 }), null);
  });
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}

// --- getOtherPathsWithSameBasename (tempdoc 727 F-7a): reads the basename index a real
// trackRead() call would have written; tests the reader's contract against a synthetic cache
// file rather than exercising the private writer directly.
{
  const sessionId = `intervene-test-${process.pid}-${Date.now()}`;
  const cacheFile = path.join(telemetryDir, `read-counts-${sessionId}.json`);
  fs.mkdirSync(telemetryDir, { recursive: true });
  try {
    const synthetic = {
      'f:/repo/tempdoc.md': { total: 1, unbounded: 1 },
      'f:/repo/.claude/worktrees/x/tempdoc.md': { total: 1, unbounded: 1 },
      _byBasename: {
        'tempdoc.md': ['f:/repo/tempdoc.md', 'f:/repo/.claude/worktrees/x/tempdoc.md'],
      },
    };
    fs.writeFileSync(cacheFile, JSON.stringify(synthetic));

    run('cross-root basename match found', () => {
      const others = getOtherPathsWithSameBasename(sessionId, 'f:/repo/.claude/worktrees/x/tempdoc.md');
      assert.deepEqual(others, ['f:/repo/tempdoc.md']);
    });
    run('no other path → empty array, not null', () => {
      const others = getOtherPathsWithSameBasename(sessionId, 'f:/repo/only-one.md');
      assert.deepEqual(others, []);
    });
    run('unknown session → empty array', () => {
      const others = getOtherPathsWithSameBasename('no-such-session', 'f:/repo/tempdoc.md');
      assert.deepEqual(others, []);
    });
  } finally {
    fs.rmSync(cacheFile, { force: true });
  }
}

// --- Report ---
if (failures.length > 0) {
  console.error(`intervene.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`intervene.test: all ${passed} checks passed`);
