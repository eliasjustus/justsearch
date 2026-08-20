/**
 * Tempdoc 858 §7 — unit tests for the shared friction scope filter
 * (`loadExclusionKeys` / `makeExclusionMatcher` / `fmtScopeExclusion` in
 * `scripts/agent-analytics/lib/telemetry-io.mjs`).
 *
 * Two things are pinned here.
 *
 * 1. ONE match rule. Four scripts read friction-excluded-sessions.json and
 *    three had forked their own copy, which had silently diverged into two
 *    different rules: baseline-economics/overhead-taxonomy matched
 *    `sessionId.startsWith(key)`, while aggregate-friction/friction-timeline
 *    also matched `key.startsWith(sessionId)`. The shared matcher takes the
 *    bidirectional form because it is the superset, and the equivalence case
 *    below is why that is safe for the two that used the narrower rule.
 *
 * 2. A zero never renders as an observation. The id list is a dated CAPTURE
 *    whose sessions rotate out of ~/.claude/projects, so "excluded: 0" would
 *    state something no consumer checked. The file's `_consumers` clause says
 *    a consumer MUST NOT do that; these assertions are what make it enforceable
 *    rather than aspirational.
 *
 * Uses a temp directory only — never touches tmp/agent-telemetry.
 *
 * Run with: `node scripts/agent-analytics/lib/telemetry-io.scope-filter.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadExclusionKeys, makeExclusionMatcher, fmtScopeExclusion } from './telemetry-io.mjs';

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

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'scope-filter-test-'));

try {
  // --- key loading ---
  run('loadExclusionKeys returns the listed ids', () => {
    const file = path.join(tmpDir, 'excluded.json');
    fs.writeFileSync(file, JSON.stringify({ excluded: { aaaaaaaa: 'r1', bbbbbbbb: 'r2' } }));
    assert.deepEqual(loadExclusionKeys(file), ['aaaaaaaa', 'bbbbbbbb']);
  });

  run('loadExclusionKeys returns [] for a missing or malformed file', () => {
    assert.deepEqual(loadExclusionKeys(path.join(tmpDir, 'nope.json')), []);
    const bad = path.join(tmpDir, 'bad.json');
    fs.writeFileSync(bad, 'not json at all');
    assert.deepEqual(loadExclusionKeys(bad), []);
  });

  // --- one match rule ---
  run('matcher handles both key shapes: full UUID and 8-char prefix', () => {
    const isExcluded = makeExclusionMatcher([
      '11111111-1111-1111-1111-111111111111',
      'abcdef12',
    ]);
    assert.equal(isExcluded('11111111-1111-1111-1111-111111111111'), true);
    assert.equal(isExcluded('abcdef12-3456-7890-abcd-ef1234567890'), true);
    assert.equal(isExcluded('22222222-2222-2222-2222-222222222222'), false);
  });

  run('matcher is bidirectional: a truncated session id still matches a full-UUID key', () => {
    // This is the case only aggregate-friction/friction-timeline used to handle.
    // Their input is a judge-written sessionId that may be truncated, so losing
    // this direction would have silently stopped excluding those sessions.
    const isExcluded = makeExclusionMatcher(['11111111-1111-1111-1111-111111111111']);
    assert.equal(isExcluded('11111111'), true);
  });

  run('bidirectional === unidirectional for transcript-filename ids (why unifying is safe)', () => {
    // baseline-economics/overhead-taxonomy feed ids taken from transcript
    // FILENAMES, which are full UUIDs or `agent-*` sidechain names. Such an id
    // is never a strict prefix of a key (keys are 8 or 36 chars, and at equal
    // length "prefix" collapses to equality), so the extra direction can never
    // fire for them and the unification is a provable no-op.
    const keys = ['11111111-1111-1111-1111-111111111111', 'abcdef12'];
    const uni = (id) => keys.some((k) => id.startsWith(k));
    const bi = makeExclusionMatcher(keys);
    const filenameShapedIds = [
      '11111111-1111-1111-1111-111111111111',
      'abcdef12-3456-7890-abcd-ef1234567890',
      '99999999-9999-9999-9999-999999999999',
      'agent-afca7d8a0eef6ca9b',
      'agent-11111111',
    ];
    for (const id of filenameShapedIds) {
      assert.equal(bi(id), uni(id), `divergence on ${id}`);
    }
  });

  // --- a zero must never render as an observation ---
  run('fmtScopeExclusion: a real exclusion reports the count', () => {
    assert.equal(
      fmtScopeExclusion({ excluded: 3, listed: 31 }),
      'excluded by scope filter: 3',
    );
  });

  run('fmtScopeExclusion: an inert filter says so, with its denominator', () => {
    assert.equal(
      fmtScopeExclusion({ excluded: 0, listed: 31 }),
      'scope filter matched no session here — 0 of 31 listed ids',
    );
  });

  run('fmtScopeExclusion: no bare "excluded: 0" is reachable from any input', () => {
    const inputs = [
      { excluded: 0, listed: 0 },
      { excluded: 0, listed: 31 },
      { excluded: 0, listed: 31, mergesExcluded: 2 },
      { excluded: 0, listed: 0, disabled: true },
      { excluded: 0, listed: 31, disabled: true },
    ];
    for (const i of inputs) {
      const out = fmtScopeExclusion(i);
      assert.doesNotMatch(out, /excluded(?: by scope filter)?: 0\b/, `leaked a bare zero: ${out}`);
    }
  });

  run('fmtScopeExclusion: a disabled filter is distinct from an inert one', () => {
    assert.equal(
      fmtScopeExclusion({ excluded: 0, listed: 31, disabled: true }),
      'scope filter disabled (--include-excluded)',
    );
  });

  run('fmtScopeExclusion: no configured list is distinct from a list that matched nothing', () => {
    assert.equal(fmtScopeExclusion({ excluded: 0, listed: 0 }), 'no scope filter configured');
  });

  run('fmtScopeExclusion: scope-excluded merges cannot be contradicted by the header', () => {
    // F10: sessions excluded = 0 while merge rows excluded > 0 is reachable, and
    // "no exclusion" in the same report as "N merges excluded" is a contradiction.
    assert.equal(
      fmtScopeExclusion({ excluded: 0, listed: 31, mergesExcluded: 2 }),
      'scope filter excluded no session here, but 2 merge row(s) below belong to scope-excluded sessions',
    );
  });

  // --- the committed list ---
  run('the committed exclusion list parses and its captured reasoning survives the id rot', () => {
    const committed = fileURLToPath(new URL('../friction-excluded-sessions.json', import.meta.url));
    const raw = JSON.parse(fs.readFileSync(committed, 'utf8'));
    assert.ok(loadExclusionKeys(committed).length > 0, 'ids are still listed');
    assert.match(raw._basis, /CAPTURED, not derived/);
    assert.match(raw._reasoning, /benchmark-subject/);
  });
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}

// --- Report ---
if (failures.length > 0) {
  console.error(`telemetry-io.scope-filter.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`telemetry-io.scope-filter.test: all ${passed} checks passed`);
