/**
 * Tempdoc 745 item A — unit tests for `loadOtlpStream`'s archive-aware read
 * path (`scripts/agent-analytics/lib/telemetry-io.mjs`).
 *
 * otlp-sink.py used to rotate a stream to a single `<base>.prev.ndjson` and
 * `os.remove()` it on the next rotation, destroying anything older than
 * "current + one rotation". The fix archives to timestamped
 * `<base>.<timestamp>[_NN].ndjson` files and only prunes per a per-stream
 * RETENTION policy (metrics/traces = never). `loadOtlpStream` is the sole
 * read chokepoint every downstream consumer (loadEventsFromOtlp,
 * loadCostsFromOtlp, and transitively analyze-session.mjs / cost-session.mjs
 * / outcome-session.mjs) goes through, so this asserts it actually finds and
 * concatenates the new archive files, oldest first, plus the legacy
 * `.prev.ndjson` this repo may still have on disk from before the fix.
 *
 * Uses a temp directory only — never touches tmp/agent-telemetry/otlp (the
 * live sink's real output directory).
 *
 * Run with: `node scripts/agent-analytics/lib/telemetry-io.otlp.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { loadOtlpStream } from './telemetry-io.mjs';

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

function writeNdjson(filePath, records) {
  fs.writeFileSync(filePath, records.map((r) => JSON.stringify(r)).join('\n') + '\n');
}

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'telemetry-io-otlp-test-'));

try {
  run('reads only the current file when no prev/archives exist', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'current-only-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [{ n: 1 }]);
    const out = loadOtlpStream(dir, 'metrics');
    assert.deepEqual(out, [{ n: 1 }]);
  });

  run('legacy .prev.ndjson (pre-745 on-disk data) is still read, oldest first', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'legacy-prev-'));
    writeNdjson(path.join(dir, 'logs.prev.ndjson'), [{ n: 'prev' }]);
    writeNdjson(path.join(dir, 'logs.ndjson'), [{ n: 'current' }]);
    const out = loadOtlpStream(dir, 'logs');
    assert.deepEqual(out, [{ n: 'prev' }, { n: 'current' }]);
  });

  run('timestamped archives are found and ordered chronologically before current', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'archives-'));
    writeNdjson(path.join(dir, 'metrics.2026-07-16T100000Z.ndjson'), [{ n: 'first' }]);
    writeNdjson(path.join(dir, 'metrics.2026-07-16T120000Z.ndjson'), [{ n: 'second' }]);
    writeNdjson(path.join(dir, 'metrics.ndjson'), [{ n: 'current' }]);
    const out = loadOtlpStream(dir, 'metrics');
    assert.deepEqual(out, [{ n: 'first' }, { n: 'second' }, { n: 'current' }]);
  });

  run('legacy .prev, archives, and current all concatenate oldest-first', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'all-three-'));
    writeNdjson(path.join(dir, 'traces.prev.ndjson'), [{ n: 'legacy-prev' }]);
    writeNdjson(path.join(dir, 'traces.2026-07-16T100000Z.ndjson'), [{ n: 'archive-1' }]);
    writeNdjson(path.join(dir, 'traces.2026-07-16T110000Z_01.ndjson'), [{ n: 'archive-2-collision' }]);
    writeNdjson(path.join(dir, 'traces.ndjson'), [{ n: 'current' }]);
    const out = loadOtlpStream(dir, 'traces');
    assert.deepEqual(out, [
      { n: 'legacy-prev' },
      { n: 'archive-1' },
      { n: 'archive-2-collision' },
      { n: 'current' },
    ]);
  });

  run('archive glob for base="logs" does not match metrics archives', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'no-cross-stream-'));
    writeNdjson(path.join(dir, 'metrics.2026-07-16T100000Z.ndjson'), [{ n: 'metrics-archive' }]);
    writeNdjson(path.join(dir, 'logs.ndjson'), [{ n: 'logs-current' }]);
    const out = loadOtlpStream(dir, 'logs');
    assert.deepEqual(out, [{ n: 'logs-current' }]);
  });

  run('archive glob for base="logs" does not match a "logs-other" stream', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'no-prefix-collision-'));
    writeNdjson(path.join(dir, 'logs-other.2026-07-16T100000Z.ndjson'), [{ n: 'other-stream' }]);
    writeNdjson(path.join(dir, 'logs.ndjson'), [{ n: 'logs-current' }]);
    const out = loadOtlpStream(dir, 'logs');
    assert.deepEqual(out, [{ n: 'logs-current' }]);
  });

  run('missing directory returns an empty array (no throw)', () => {
    const missing = path.join(tmpDir, 'does-not-exist');
    const out = loadOtlpStream(missing, 'metrics');
    assert.deepEqual(out, []);
  });

  run('missing current/archive files (empty dir) returns an empty array', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'empty-'));
    const out = loadOtlpStream(dir, 'metrics');
    assert.deepEqual(out, []);
  });
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`telemetry-io.otlp.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`telemetry-io.otlp.test: all ${passed} checks passed`);
