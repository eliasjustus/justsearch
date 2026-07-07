/**
 * Tempdoc 680 — unit tests for observations-triage.mjs (read-model + probe janitor).
 * Run: `node scripts/agent-analytics/observations-triage.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { readModel, runProbes, checkExpectedStateExits, STORE_FILE, EXPECTED_STATE_FILE } from './observations-triage.mjs';
import { parseStore } from './lib/observations-store.mjs';

let passed = 0;
const failures = [];
function run(label, fn) {
  try { fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'obs-triage-test-'));

const STORE = [
  '---', 'title: Observations', '---', '', '# Observations', '', '## Rules', '', 'x', '',
  '## Conditions', '',
  '### obs:gone — a condition whose probe now passes',
  '`kind: environment` `anchor: a.ts` `seen: 3` `first: 2026-06-01` `last: 2026-07-01` `probe: node -e "process.exit(0)"`',
  '- [ ] occurrence (2026-06-01)',
  '',
  '### obs:still — a condition whose probe still fails',
  '`kind: defect` `anchor: b.ts` `seen: 5` `first: 2026-06-01` `last: 2026-07-02` `probe: node -e "process.exit(1)"`',
  '- [ ] occurrence (2026-06-01)',
  '',
  '### obs:slowone — an expensive probe',
  '`kind: environment` `anchor: c.ts` `seen: 1` `first: 2026-06-01` `last: 2026-06-01` `probe: slow: node -e "process.exit(0)"`',
  '- [ ] occurrence (2026-06-01)',
  '',
  '### obs:unconfirmed — fold-proposed kind',
  '`kind: defect?` `anchor: d.ts` `seen: 1` `first: 2026-07-05` `last: 2026-07-05`',
  '- [ ] occurrence (2026-07-05)',
  '',
  '## Parked', '',
  '### obs:parked-one — parked with probe (must be skipped)',
  '`kind: follow-up` `anchor: e.ts` `seen: 1` `first: 2026-06-01` `last: 2026-06-01` `probe: node -e "process.exit(0)"` `status: parked (external trigger)`',
  '- [ ] occurrence (2026-06-01)',
  '',
].join('\n');

function freshRoot() {
  const root = fs.mkdtempSync(path.join(tmp, 'root-'));
  fs.mkdirSync(path.join(root, path.dirname(STORE_FILE)), { recursive: true });
  fs.writeFileSync(path.join(root, STORE_FILE), STORE, 'utf8');
  return root;
}

try {
  run('readModel counts depth/open/parked/kind-confirmations and ranks by seen', () => {
    const m = readModel(parseStore(STORE));
    assert.equal(m.depth, 5);
    assert.equal(m.open, 4);
    assert.equal(m.parked.length, 1);
    assert.equal(m.needsKindConfirm, 1);
    assert.equal(m.topBySeen[0].slug, 'still'); // seen 5 ranks first
    assert.equal(m.probeable, 3);
  });
  run('runProbes proposes retirement on probe exit 0, re-affirms on nonzero, skips slow + parked', () => {
    const root = freshRoot();
    const r = runProbes({ root, today: '2026-07-06' });
    assert.equal(r.ran, 2); // gone + still; slowone skipped, parked skipped
    assert.deepEqual(r.retireProposed, ['gone']);
    assert.deepEqual(r.stillTrue, ['still']);
    assert.equal(r.skippedSlow, 1);
    const store = parseStore(fs.readFileSync(path.join(root, STORE_FILE), 'utf8'));
    assert.match(store.groups.find((g) => g.slug === 'gone').fields.status, /^proposed-retire \(probe passed 2026-07-06\)/);
    assert.equal(store.groups.find((g) => g.slug === 'still').fields.status ?? '', '');
    assert.match(store.groups.find((g) => g.slug === 'parked-one').fields.status, /^parked/); // untouched
  });
  run('runProbes with slow=true includes slow probes', () => {
    const root = freshRoot();
    const r = runProbes({ root, slow: true, today: '2026-07-06' });
    assert.equal(r.ran, 3);
    assert.ok(r.retireProposed.includes('slowone'));
  });
  run('runProbes never deletes a condition (proposal-only)', () => {
    const root = freshRoot();
    runProbes({ root, today: '2026-07-06' });
    const store = parseStore(fs.readFileSync(path.join(root, STORE_FILE), 'utf8'));
    assert.equal(store.groups.length, 5);
  });
  run('checkExpectedStateExits reports fired exit probes, report-only', () => {
    const root = freshRoot();
    const file = path.join(root, EXPECTED_STATE_FILE);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const data = {
      entries: [
        { id: 'fired', exitProbe: 'node -e "process.exit(0)"' },
        { id: 'holds', exitProbe: 'node -e "process.exit(1)"' },
        { id: 'slow-skip', exitProbe: 'slow: node -e "process.exit(0)"' },
      ],
    };
    fs.writeFileSync(file, JSON.stringify(data), 'utf8');
    const r = checkExpectedStateExits({ root });
    assert.equal(r.checked, 2);
    assert.deepEqual(r.exitFired, ['fired']);
    assert.deepEqual(JSON.parse(fs.readFileSync(file, 'utf8')), data); // untouched
  });
  run('checkExpectedStateExits tolerates a missing expected-state file', () => {
    const root = freshRoot();
    const r = checkExpectedStateExits({ root });
    assert.equal(r.checked, 0);
  });
} finally {
  fs.rmSync(tmp, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`observations-triage.test: ${failures.length} FAILED / ${passed} passed`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`observations-triage.test: ${passed} passed`);
