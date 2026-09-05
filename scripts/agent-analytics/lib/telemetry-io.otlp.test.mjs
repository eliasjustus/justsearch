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
 * loadCostsFromOtlp, and transitively cost-session.mjs and
 * context-attribution.mjs) goes through, so this asserts it actually finds and
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
import { loadOtlpStream, loadCostsFromOtlp, readOtlpLedger } from './telemetry-io.mjs';

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

  // --- readOtlpLedger: the compact stream (tempdoc 930 F2) ----------------
  // ledger.ndjson is the body-free projection of logs.ndjson, kept for 90
  // archives where logs keeps 2. Its whole reason to exist is that a reader
  // reaches BACK past the current file, so the archive-aware read is the
  // property under test, not an incidental one.

  run('readOtlpLedger concatenates archive then current, oldest first', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'ledger-'));
    writeNdjson(path.join(dir, 'ledger.2026-09-04T100000Z.ndjson'), [
      { signal: 'ledger', event: 'api_request', 'session.id': 'sess-1', attributes: { cost_usd: 0.01 } },
    ]);
    writeNdjson(path.join(dir, 'ledger.ndjson'), [
      { signal: 'ledger', event: 'tool_result', 'session.id': 'sess-1', attributes: { tool_name: 'Read' } },
    ]);
    const out = readOtlpLedger(dir);
    assert.equal(out.length, 2);
    assert.equal(out[0].event, 'api_request', 'the archive must come first');
    assert.equal(out[1].event, 'tool_result');
    assert.equal(out[0].attributes.cost_usd, 0.01);
  });

  run('readOtlpLedger does not pick up the logs stream it projects from', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'ledger-vs-logs-'));
    writeNdjson(path.join(dir, 'logs.ndjson'), [{ signal: 'log', body: 'claude_code.api_request' }]);
    writeNdjson(path.join(dir, 'logs.2026-09-04T100000Z.ndjson'), [{ signal: 'log' }]);
    writeNdjson(path.join(dir, 'ledger.ndjson'), [{ signal: 'ledger', event: 'api_request' }]);
    const out = readOtlpLedger(dir);
    assert.deepEqual(out, [{ signal: 'ledger', event: 'api_request' }]);
  });

  run('readOtlpLedger on a directory with no ledger stream returns empty', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'ledger-absent-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [{ n: 1 }]);
    assert.deepEqual(readOtlpLedger(dir), []);
  });

  // --- loadCostsFromOtlp: gen_ai.usage dedup (tempdoc 886 §12 PR 3) --------
  // otlp-sink.py's gen_ai.usage normalised records are written ADDITIVELY
  // alongside the original claude_code.token.usage points they derive from
  // (both land in the same metrics.ndjson stream). loadCostsFromOtlp must
  // prefer the normalised twin and skip its already-consumed origin point,
  // never summing both — these fixtures pair a raw claude_code point with
  // its gen_ai twin for the SAME session and assert the total reflects one
  // count, not two.

  run('gen_ai.usage twin is not double-counted against its claude_code.token.usage origin', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'dedup-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [
      {
        signal: 'metric', name: 'claude_code.token.usage', kind: 'sum',
        points: [{
          attributes: { type: 'cacheRead', model: 'claude-opus-5', 'session.id': 'sess-1', query_source: 'main' },
          value: 1000, time_unix_nano: 42,
        }],
        resource: {},
      },
      {
        signal: 'metric', name: 'gen_ai.usage', normalized: true,
        attributes: {
          type: 'cacheRead', model: 'claude-opus-5', 'session.id': 'sess-1', query_source: 'main',
          'gen_ai.system': 'claude-code', 'gen_ai.request.model': 'claude-opus-5', 'gen_ai.token.kind': 'cache_read',
        },
        value: 1000, time_unix_nano: 42, resource: {},
      },
    ]);
    const map = loadCostsFromOtlp(dir);
    const rec = map.get('sess-1');
    assert.ok(rec, 'session should be present');
    assert.equal(rec.cache_read_tokens, 1000, 'the twin must not be summed on top of its origin point');
    assert.equal(rec.harness, 'claude-code');
    assert.equal(rec.model, 'claude-opus-5');
  });

  run('gen_ai.usage-only session (e.g. a Codex session) accumulates tokens with no claude_code fallback', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'genai-only-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [
      {
        signal: 'metric', name: 'gen_ai.usage', normalized: true,
        attributes: {
          token_type: 'cached_input', model: 'gpt-5-codex', 'session.id': 'sess-codex', query_source: 'main',
          'gen_ai.system': 'codex-cli', 'gen_ai.request.model': 'gpt-5-codex', 'gen_ai.token.kind': 'cache_read',
        },
        value: 250, time_unix_nano: 7, resource: {},
      },
      {
        signal: 'metric', name: 'gen_ai.usage', normalized: true,
        attributes: {
          token_type: 'output', model: 'gpt-5-codex', 'session.id': 'sess-codex', query_source: 'main',
          'gen_ai.system': 'codex-cli', 'gen_ai.request.model': 'gpt-5-codex', 'gen_ai.token.kind': 'output',
        },
        value: 80, time_unix_nano: 8, resource: {},
      },
    ]);
    const map = loadCostsFromOtlp(dir);
    const rec = map.get('sess-codex');
    assert.ok(rec, 'session should be present');
    assert.equal(rec.cache_read_tokens, 250);
    assert.equal(rec.output_tokens, 80);
    assert.equal(rec.harness, 'codex-cli');
    assert.equal(rec.by_source.main.output_tokens, 80);
  });

  // --- Codex raw-input/cache_read pairing (independent review SHOULD-FIX 1) ---
  // Codex's `input` gen_ai.usage point is flagged `gen_ai.input_includes_cache_read`
  // because its raw value already includes the cached portion (the OpenAI
  // convention). loadCostsFromOtlp must resolve FRESH input (input - cache_read)
  // by pairing it with the `cache_read` point sharing the same session +
  // time_unix_nano, not sum the raw value straight into input_tokens (which
  // would double-count the cached portion in any input+output+cache_write+
  // cache_read summer, e.g. baseline-economics.mjs:361, overhead-taxonomy.mjs:441).

  run('Codex raw input paired with its cache_read sibling resolves to FRESH input tokens', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'codex-input-pair-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [
      {
        signal: 'metric', name: 'gen_ai.usage', normalized: true,
        attributes: {
          token_type: 'input', model: 'gpt-5-codex', 'session.id': 'sess-pair', query_source: 'main',
          'gen_ai.system': 'codex-cli', 'gen_ai.request.model': 'gpt-5-codex', 'gen_ai.token.kind': 'input',
          'gen_ai.input_includes_cache_read': true,
        },
        value: 15874, time_unix_nano: 100, resource: {},
      },
      {
        signal: 'metric', name: 'gen_ai.usage', normalized: true,
        attributes: {
          token_type: 'cached_input', model: 'gpt-5-codex', 'session.id': 'sess-pair', query_source: 'main',
          'gen_ai.system': 'codex-cli', 'gen_ai.request.model': 'gpt-5-codex', 'gen_ai.token.kind': 'cache_read',
        },
        value: 11648, time_unix_nano: 100, resource: {},
      },
    ]);
    const map = loadCostsFromOtlp(dir);
    const rec = map.get('sess-pair');
    assert.ok(rec, 'session should be present');
    assert.equal(rec.input_tokens, 4226, 'fresh input = raw input (15874) - cache_read (11648)');
    assert.equal(rec.cache_read_tokens, 11648);
    assert.equal(rec.input_includes_cache_read, false, 'the pair resolved — nothing left unresolved to flag');
  });

  run('Codex raw input with NO cache_read sibling is kept RAW and the session is flagged, not silently subtracted', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'codex-input-alone-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [
      {
        signal: 'metric', name: 'gen_ai.usage', normalized: true,
        attributes: {
          token_type: 'input', model: 'gpt-5-codex', 'session.id': 'sess-alone', query_source: 'main',
          'gen_ai.system': 'codex-cli', 'gen_ai.request.model': 'gpt-5-codex', 'gen_ai.token.kind': 'input',
          'gen_ai.input_includes_cache_read': true,
        },
        value: 15874, time_unix_nano: 200, resource: {},
      },
    ]);
    const map = loadCostsFromOtlp(dir);
    const rec = map.get('sess-alone');
    assert.ok(rec, 'session should be present');
    assert.equal(rec.input_tokens, 15874, 'no cache_read sibling arrived — kept raw, not fabricated-subtracted');
    assert.equal(rec.cache_read_tokens, 0);
    assert.equal(rec.input_includes_cache_read, true, 'flagged so a caller knows input_tokens is not fresh here');
  });

  run('archive with no gen_ai.usage records at all still sums claude_code.token.usage (pre-886-PR3 data)', () => {
    const dir = fs.mkdtempSync(path.join(tmpDir, 'legacy-archive-'));
    writeNdjson(path.join(dir, 'metrics.ndjson'), [
      {
        signal: 'metric', name: 'claude_code.token.usage', kind: 'sum',
        points: [{
          attributes: { type: 'output', model: 'claude-sonnet-5', 'session.id': 'sess-old', query_source: 'main' },
          value: 55, time_unix_nano: 1,
        }],
        resource: {},
      },
    ]);
    const map = loadCostsFromOtlp(dir);
    const rec = map.get('sess-old');
    assert.ok(rec, 'session should be present');
    assert.equal(rec.output_tokens, 55, 'no behaviour change for pre-upgrade archives with no gen_ai.usage twin');
    assert.equal(rec.harness, null, 'legacy data carries no gen_ai.system attribution');
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
