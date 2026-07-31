/**
 * Unit test for the dead-config scanner + its truth-table verdicts (tempdoc 799 §O.3).
 *
 * The gate's BITE was verified once by hand (inject an unread EnvRegistry knob → gate exits 1 →
 * revert → exits 0). This file makes the parts that decide that outcome permanently covered, so a
 * refactor cannot quietly turn the gate into a reporter — 799 §C.3 found "reachable but advisory"
 * is its own failure mode, distinct from "not wired at all".
 *
 * Run: node scripts/ci/test-config-surface-dead-config.mjs
 */

import assert from 'node:assert/strict';

import { parseRecordComponents } from '../governance/gates/config-surface/dead-config.mjs';
import {
  verdictForDeadKey,
  verdictForUnreadComponent,
} from '../governance/gates/config-surface/truth-table.mjs';

let passed = 0;
const check = (name, fn) => {
  fn();
  passed++;
  console.log(`  ok  ${name}`);
};

console.log('[test-config-surface-dead-config]');

check('an unbaselined dead key FAILS — this is the gate biting', () => {
  const v = verdictForDeadKey({ key: 'justsearch.some.knob', baselined: false });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'config-surface/dead-key');
  assert.match(v.reason, /NOTHING reads it/);
});

check('a baselined dead key is info, not fail — known debt does not block', () => {
  const v = verdictForDeadKey({ key: 'search.rerank.k', baselined: true });
  assert.equal(v.status, 'info');
  assert.equal(v.ruleId, 'config-surface/dead-key-baselined');
});

check('an unbaselined unread component FAILS', () => {
  const v = verdictForUnreadComponent({ component: 'someKnob', baselined: false });
  assert.equal(v.status, 'fail');
  assert.equal(v.ruleId, 'config-surface/unread-component');
});

check('a baselined unread component is info', () => {
  const v = verdictForUnreadComponent({ component: 'simulatedLatencyMs', baselined: true });
  assert.equal(v.status, 'info');
});

check('component parser reads real components and rejects literals', () => {
  const src = `
    public record Worker(int maxContentLength, long maxFileSize) {}
    public record Pacing(int pollBatchSize, int commitEvery) {
      public static final Pacing DEFAULTS = new Pacing(16, 100, true, 8192);
    }
  `;
  const comps = parseRecordComponents(src);
  assert.ok(comps.has('maxContentLength'));
  assert.ok(comps.has('maxFileSize'));
  assert.ok(comps.has('pollBatchSize'));
  // default-value literals must not be mistaken for components — that was the first
  // measurement's noise (it reported `true`, `8192`, `step` as unread "components").
  assert.ok(!comps.has('true'));
  assert.ok(!comps.has('8192'));
});

check('a component whose type is not a type is rejected', () => {
  const comps = parseRecordComponents('public record X(notAType lowercase) {}');
  assert.equal(comps.size, 0);
});

console.log(`[test-config-surface-dead-config] ${passed} assertions passed`);
