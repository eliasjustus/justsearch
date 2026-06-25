/**
 * Tempdoc 531 — integration tests for the consumer-drift enforcer.
 *
 * Drives `enforceConsumerDrift` end-to-end over scaffolded temp fixture trees,
 * covering the wiring the kernel self-test fixtures don't reach: the changeset
 * escape hatch (slot-retraction / grace-extension), date-bound grace (incl. the
 * inclusive end-of-day boundary), stale-slot, changeset-mismatch, and the
 * baseline-tampering guard (silent-floor-drop / silent-slot-removal).
 *
 * Run with: `node scripts/governance/gates/consumer-drift/enforcer.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceConsumerDrift } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = {
  baseline: { path: 'gates/consumer-drift/slots.json' },
  changesetsDir: 'gates/consumer-drift/.changesets',
};

/**
 * Scaffold a temp fixtureRoot:
 *   { slots, baselineSlots?, files?: {relPath: content}, changesets?: [{id, frontmatter, body}] }
 */
function scaffold({ slots, baselineSlots, discovery, files = {}, changesets = [] }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cd-enforcer-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  const slotsConfig = discovery ? { slots, discovery } : { slots };
  write('gates/consumer-drift/slots.json', JSON.stringify(slotsConfig, null, 2));
  if (baselineSlots) write('_baseline/gates/consumer-drift/slots.json', JSON.stringify({ slots: baselineSlots }, null, 2));
  for (const [rel, content] of Object.entries(files)) write(rel, content);
  for (const cs of changesets) {
    const fm = Object.entries(cs.frontmatter).map(([k, v]) => `${k}: ${v}`).join('\n');
    write(`gates/consumer-drift/.changesets/${cs.id}.md`, `---\n${fm}\n---\n${cs.body ?? 'body'}\n`);
  }
  return root;
}

async function enforce(fixtureRoot, now) {
  return enforceConsumerDrift({
    repoRoot: fixtureRoot,
    gate: GATE,
    baselineRef: 'HEAD', // ignored for prior-state in fixtureMode (reads _baseline/)
    mode: 'gate',
    fixtureMode: true,
    fixtureRoot,
    now,
  });
}

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

function ruleIds(r) {
  return r.findings.map((f) => f.ruleId);
}

const FOO_DECL = 'modules/foo/FooBar.ts';
const FOO_SRC = 'export class FooBar {}\n';
const FOO_CONSUMER = "import { FooBar } from '../foo/FooBar';\nexport const x = new FooBar();\n";

await run('changeset slot-retraction (declaredIn deleted) → pass/retracted', async () => {
  // declaredIn FooBar.ts intentionally NOT written → it's "deleted".
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1 }],
    changesets: [{ id: 'retract-foo', frontmatter: { classification: 'slot-retraction', slot: 'foo' } }],
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/retracted'));
});

await run('changeset grace-extension covers below-min → pass', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 2 }],
    files: { [FOO_DECL]: FOO_SRC }, // declaredIn exists, zero consumers → count 0 < 2
    changesets: [{ id: 'grace-foo', frontmatter: { classification: 'grace-extension', slot: 'foo', tempdoc: '531' } }],
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/grace-extension'));
});

await run('within date grace → info (not fail)', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1, grace: { untilDate: '2099-01-01' } }],
    files: { [FOO_DECL]: FOO_SRC },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/within-grace'));
});

await run('grace untilDate is inclusive through end of that UTC day (D fix)', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1, grace: { untilDate: '2026-07-01' } }],
    files: { [FOO_DECL]: FOO_SRC },
  });
  // Late on the untilDate itself: must still be within grace.
  const r = await enforce(root, '2026-07-01T23:00:00Z');
  assert.ok(ruleIds(r).includes('consumer-drift/within-grace'), `same-day late should be within grace (${ruleIds(r).join(',')})`);
  // The day after: expired → fail.
  const r2 = await enforce(root, '2026-07-02T00:30:00Z');
  assert.equal(r2.verdict, 'fail', `next day should fail (${ruleIds(r2).join(',')})`);
  assert.ok(ruleIds(r2).includes('consumer-drift/below-min'));
});

await run('stale-slot: declaredIn gone, no retraction → fail (and supersedes below-min)', async () => {
  const root = scaffold({
    slots: [{ id: 'qux', symbol: 'Qux', declaredIn: 'modules/qux/Qux.ts', includeGlobs: ['modules/**/*.ts'], expectedMin: 1 }],
    // Qux.ts not written → declaredIn missing; no changeset.
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/stale-slot'));
  // Review L1: stale-slot must SUPERSEDE below-min, not double-fire.
  assert.ok(!ruleIds(r).includes('consumer-drift/below-min'), `below-min should not also fire (${ruleIds(r).join(',')})`);
});

await run('malformed slots.json → fail verdict, not a thrown crash (review M1)', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cd-enforcer-'));
  tmpDirs.push(root);
  const p = path.join(root, 'gates/consumer-drift/slots.json');
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, '{ this is not json', 'utf8');
  const r = await enforce(root); // must not throw
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/malformed-slots'));
});

await run('changeset names unknown slot → warning (changeset-mismatch)', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1 }],
    files: { [FOO_DECL]: FOO_SRC, 'modules/bar/Uses.ts': FOO_CONSUMER }, // foo healthy
    changesets: [{ id: 'ghost', frontmatter: { classification: 'emergency-override', slot: 'does-not-exist', tempdoc: '531' } }],
  });
  const r = await enforce(root);
  assert.ok(ruleIds(r).includes('consumer-drift/changeset-mismatch'), `(${ruleIds(r).join(',')})`);
});

// --- Baseline-tampering guard (B) ---
await run('silent expectedMin lowering vs baseline → fail (silent-floor-drop)', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1 }],
    baselineSlots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 3 }],
    files: { [FOO_DECL]: FOO_SRC, 'modules/bar/Uses.ts': FOO_CONSUMER }, // count 1 ≥ live min 1, so only tampering fires
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/silent-floor-drop'));
});

await run('declared expectedMin lowering (grace-extension) → pass', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1 }],
    baselineSlots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 3 }],
    files: { [FOO_DECL]: FOO_SRC, 'modules/bar/Uses.ts': FOO_CONSUMER },
    changesets: [{ id: 'drop-foo', frontmatter: { classification: 'grace-extension', slot: 'foo', tempdoc: '531' } }],
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/declared-floor-drop'));
});

await run('silent slot removal vs baseline → fail (silent-slot-removal)', async () => {
  const root = scaffold({
    slots: [{ id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1 }],
    baselineSlots: [
      { id: 'foo', symbol: 'FooBar', declaredIn: FOO_DECL, includeGlobs: ['modules/**/*.ts'], expectedMin: 1 },
      { id: 'bar', symbol: 'BarBaz', declaredIn: 'modules/bar/BarBaz.ts', includeGlobs: ['modules/**/*.ts'], expectedMin: 1 },
    ],
    files: { [FOO_DECL]: FOO_SRC, 'modules/bar/Uses.ts': FOO_CONSUMER, 'modules/bar/BarBaz.ts': 'export class BarBaz {}\n' },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/silent-slot-removal'));
});

// --- §5.2 catalog-completeness closure (tempdoc 548) ---
const SUB_ROOT = 'modules/ui-web/src/shell-v0/substrates';
const SUB_INDEX = 'export const x = 1;\n';

await run('discovery: a NEW uncovered substrate (not grandfathered) → fail (uncovered-substrate)', async () => {
  const root = scaffold({
    slots: [],
    discovery: { roots: [SUB_ROOT], entryGlob: '*/index.ts', knownUncovered: ['actions'] },
    files: {
      [`${SUB_ROOT}/actions/index.ts`]: SUB_INDEX, // grandfathered
      [`${SUB_ROOT}/brand-new/index.ts`]: SUB_INDEX, // NOT covered, NOT grandfathered → escape
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/uncovered-substrate'), `(${ruleIds(r).join(',')})`);
  // The grandfathered one must NOT fail.
  assert.ok(ruleIds(r).includes('consumer-drift/grandfathered-substrate'));
});

await run('discovery: grandfathered substrate → info, verdict pass', async () => {
  const root = scaffold({
    slots: [],
    discovery: { roots: [SUB_ROOT], entryGlob: '*/index.ts', knownUncovered: ['actions', 'scope'] },
    files: {
      [`${SUB_ROOT}/actions/index.ts`]: SUB_INDEX,
      [`${SUB_ROOT}/scope/index.ts`]: SUB_INDEX,
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/grandfathered-substrate'));
  assert.ok(!ruleIds(r).includes('consumer-drift/uncovered-substrate'));
});

await run('discovery: a slot covering the substrate → silent pass (no uncovered/grandfathered)', async () => {
  const root = scaffold({
    slots: [
      {
        id: 'actions-floor',
        symbol: 'ActionRegistry',
        substrate: 'actions',
        declaredIn: `${SUB_ROOT}/actions/index.ts`,
        includeGlobs: [`${SUB_ROOT}/**/*.ts`],
        expectedMin: 0,
      },
    ],
    discovery: { roots: [SUB_ROOT], entryGlob: '*/index.ts', knownUncovered: [] },
    files: { [`${SUB_ROOT}/actions/index.ts`]: SUB_INDEX },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(!ruleIds(r).includes('consumer-drift/uncovered-substrate'));
  assert.ok(!ruleIds(r).includes('consumer-drift/grandfathered-substrate'));
});

await run('discovery: knownUncovered entry now covered by a slot → stale-grandfather hint (info)', async () => {
  const root = scaffold({
    slots: [
      {
        id: 'actions-floor',
        symbol: 'ActionRegistry',
        substrate: 'actions',
        declaredIn: `${SUB_ROOT}/actions/index.ts`,
        includeGlobs: [`${SUB_ROOT}/**/*.ts`],
        expectedMin: 0,
      },
    ],
    discovery: { roots: [SUB_ROOT], entryGlob: '*/index.ts', knownUncovered: ['actions'] },
    files: { [`${SUB_ROOT}/actions/index.ts`]: SUB_INDEX },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(ruleIds(r).includes('consumer-drift/stale-grandfather'), `(${ruleIds(r).join(',')})`);
});

await run('no discovery block → closure skipped entirely (pre-548 contract preserved)', async () => {
  const root = scaffold({
    slots: [],
    files: { [`${SUB_ROOT}/actions/index.ts`]: SUB_INDEX },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(!ruleIds(r).includes('consumer-drift/uncovered-substrate'));
  assert.ok(!ruleIds(r).includes('consumer-drift/grandfathered-substrate'));
});

// --- cleanup + report ---
for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`consumer-drift enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`consumer-drift enforcer.test: all ${passed} checks passed`);
