/**
 * Tempdoc 918 item 1 — a declared-growth changeset must re-pin in the same diff.
 *
 * Two layers are tested here:
 *   (1) the shared rule module itself — rule id, direction handling, and the four message shapes
 *       the pin-movement clause can take;
 *   (2) the rule AS THE GATES SEE IT — three real enforcers (`ts-any`, `todo-fixme`, `dead-code`)
 *       driven through the four cases the rule has to distinguish:
 *         growth declared + pin advanced to the measured value  → pass
 *         growth declared + pin unchanged                       → FAIL, new rule id
 *         growth declared + pin advanced but below measured     → FAIL, new rule id
 *         no growth declared                                    → untouched (still silent-growth)
 *
 * Run with: `node scripts/governance/lib/declared-growth-repin.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  GROWTH_LICENSING_CLASSIFICATIONS,
  REPIN_REGRESSION_RULE_SUFFIX,
  REPIN_RULE_SUFFIX,
  repinFinding,
  repinRuleDescription,
  repinRuleId,
} from './declared-growth-repin.mjs';
import { enforceTsAny } from '../gates/ts-any/enforcer.mjs';
import { enforceTodoFixme } from '../gates/todo-fixme/enforcer.mjs';
import { enforceDeadCode } from '../gates/dead-code/enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

// --- (1) the shared rule module ------------------------------------------------------------

await run('rule id is <prefix>/declared-growth-without-repin and is described', () => {
  assert.equal(repinRuleId('dead-code'), 'dead-code/declared-growth-without-repin');
  assert.equal(
    repinRuleId('test-to-code', REPIN_REGRESSION_RULE_SUFFIX),
    'test-to-code/declared-regression-without-repin',
  );
  const desc = repinRuleDescription('ts-any');
  assert.deepEqual(Object.keys(desc), ['ts-any/declared-growth-without-repin']);
  assert.match(desc['ts-any/declared-growth-without-repin'], /not advanced to the/);
});

await run('the message names the pin file, the row, the measured value and the remedy', () => {
  const f = repinFinding({
    rulePrefix: 'dead-code', classification: 'declared-growth',
    row: 'modules/ui-web/src/api/schema-types/index.ts', measured: 53, livePin: 51, priorPin: 51,
    baselineFile: 'gates/dead-code/baseline.txt', unit: 'unused exports',
    pinLine: 'modules/ui-web/src/api/schema-types/index.ts 53 <today>',
  });
  assert.equal(f.ruleId, 'dead-code/declared-growth-without-repin');
  assert.equal(f.level, 'error');
  assert.match(f.message, /gates\/dead-code\/baseline\.txt/, 'names the pin file');
  assert.match(f.message, /schema-types\/index\.ts/, 'names the row');
  assert.match(f.message, /Measured 53 unused exports/, 'names the measured value');
  assert.match(f.message, /Remedy, in THIS commit/, 'names the remedy');
  assert.match(f.message, /unchanged in this diff/, 'says the pin did not move');
});

await run('a pin that moved but fell short reads differently from one that never moved', () => {
  const short = repinFinding({
    rulePrefix: 'ts-any', classification: 'declared-growth', row: 'a.ts', measured: 9,
    livePin: 7, priorPin: 5, baselineFile: 'gates/ts-any/baseline.txt',
  });
  assert.match(short.message, /moved 5 → 7 in this diff but still short of the measured value/);
  const unknown = repinFinding({
    rulePrefix: 'ts-any', classification: 'declared-growth', row: 'a.ts', measured: 9,
    livePin: 7, baselineFile: 'gates/ts-any/baseline.txt',
  });
  assert.doesNotMatch(unknown.message, /in this diff/, 'no prior pin ⇒ no claim about movement');
});

await run('a floor gate gets floor wording, not ceiling wording', () => {
  const f = repinFinding({
    rulePrefix: 'test-to-code', classification: 'declared-regression', row: 'modules/core',
    measured: 410, livePin: 500, priorPin: 520, baselineFile: 'gates/test-to-code/baseline.txt',
    suffix: REPIN_REGRESSION_RULE_SUFFIX, direction: 'regression',
  });
  assert.equal(f.ruleId, 'test-to-code/declared-regression-without-repin');
  assert.match(f.message, /moved 520 → 500 in this diff but still short/,
    'lowering a floor is movement TOWARDS the measured value');
  const wrongWay = repinFinding({
    rulePrefix: 'test-to-code', classification: 'declared-regression', row: 'modules/core',
    measured: 410, livePin: 540, priorPin: 520, baselineFile: 'gates/test-to-code/baseline.txt',
    suffix: REPIN_REGRESSION_RULE_SUFFIX, direction: 'regression',
  });
  assert.match(wrongWay.message, /the wrong way/);
});

await run('a set-membership ratchet degrades to "the baseline does not carry this row"', () => {
  const f = repinFinding({
    rulePrefix: 'dead-code-jvm', classification: 'declared-growth',
    row: 'io.justsearch.Foo', baselineFile: 'gates/dead-code-jvm/baseline.txt',
    pinLine: 'io.justsearch.Foo',
  });
  assert.match(f.message, /does not carry this row/);
  assert.match(f.message, /io\.justsearch\.Foo/);
});

await run('the growth-licensing vocabulary excludes baseline-edit-only classifications', () => {
  for (const shrink of [
    'unit-renormalization', 'unused-export-shrink', 'monotonic-shrink', 'dep-shrink',
    'severity-decrease', 'seam-retraction', 'tier-change', 'rule-retired',
    'new-rule-registered', 'slot-retraction', 'grace-extension', 'intentional-divergence',
    'mirror-retirement', 'guard-downgrade',
  ]) {
    assert.ok(!GROWTH_LICENSING_CLASSIFICATIONS.includes(shrink),
      `'${shrink}' licenses a baseline EDIT, not a live exceedance — it must not demand a re-pin`);
  }
  assert.ok(GROWTH_LICENSING_CLASSIFICATIONS.includes('declared-growth'));
  assert.equal(REPIN_RULE_SUFFIX, 'declared-growth-without-repin');
});

// --- (2) the rule as the gates see it ------------------------------------------------------

/**
 * A fixture repo root. `_baseline/<path>` is how `readPriorBaselineText` supplies the PIN AS IT
 * STOOD AT THE PR BASE in fixtureMode — which is what makes "did the pin move in this diff"
 * testable without a git history.
 */
function scaffold(files) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'repin-'));
  tmpDirs.push(root);
  for (const [rel, content] of Object.entries(files)) {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  }
  return root;
}

const CHANGESET = '---\nclassification: declared-growth\ntempdoc: 918\n---\nDeclared for the test.\n';

// -- ts-any: `<path> <count> <date>` per-file counts over ui-web sources.

const TS_GATE = {
  id: 'ts-any',
  baseline: { path: 'gates/ts-any/baseline.txt' },
  changesetsDir: 'gates/ts-any/.changesets',
  config: { sourceGlobs: ['modules/ui-web/src/**/*.{ts,tsx}'], excludeGlobs: [] },
};
const SRC = 'modules/ui-web/src/x.ts';
/** Three `any`-casts, so the measured count is 3. */
const THREE_ANY = 'const a = 1 as any;\nconst b = 2 as any;\nlet c: any;\n';

async function tsAnyOn(files) {
  const root = scaffold(files);
  return enforceTsAny({
    repoRoot: root, gate: TS_GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

const ids = (r) => r.findings.map((f) => f.ruleId);

await run('ts-any: growth declared + pin advanced to the measured value → pass', async () => {
  const r = await tsAnyOn({
    [SRC]: THREE_ANY,
    'gates/ts-any/baseline.txt': `${SRC} 3 2026-09-03\n`,
    '_baseline/gates/ts-any/baseline.txt': `${SRC} 1 2026-09-01\n`,
    'gates/ts-any/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass', `expected pass, got ${r.verdict}: ${ids(r).join(', ')}`);
  assert.ok(!ids(r).includes('ts-any/declared-growth-without-repin'));
});

await run('ts-any: growth declared + pin unchanged → FAIL with the new rule id', async () => {
  const r = await tsAnyOn({
    [SRC]: THREE_ANY,
    'gates/ts-any/baseline.txt': `${SRC} 1 2026-09-01\n`,
    '_baseline/gates/ts-any/baseline.txt': `${SRC} 1 2026-09-01\n`,
    'gates/ts-any/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'fail');
  assert.deepEqual(ids(r), ['ts-any/declared-growth-without-repin']);
  const m = r.findings[0].message;
  assert.match(m, /Measured 3 any-casts/);
  assert.match(m, /the pin in gates\/ts-any\/baseline\.txt is 1, unchanged in this diff/);
});

await run('ts-any: growth declared + pin advanced but below measured → FAIL', async () => {
  const r = await tsAnyOn({
    [SRC]: THREE_ANY,
    'gates/ts-any/baseline.txt': `${SRC} 2 2026-09-03\n`,
    '_baseline/gates/ts-any/baseline.txt': `${SRC} 1 2026-09-01\n`,
    'gates/ts-any/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'fail');
  assert.deepEqual(ids(r), ['ts-any/declared-growth-without-repin']);
  assert.match(r.findings[0].message, /moved 1 → 2 in this diff but still short/);
});

await run('ts-any: no growth declared → untouched, still silent-growth', async () => {
  const r = await tsAnyOn({
    [SRC]: THREE_ANY,
    'gates/ts-any/baseline.txt': `${SRC} 1 2026-09-01\n`,
    '_baseline/gates/ts-any/baseline.txt': `${SRC} 1 2026-09-01\n`,
  });
  assert.equal(r.verdict, 'fail');
  assert.deepEqual(ids(r), ['ts-any/silent-growth'],
    'without a changeset the pre-existing rule is what must fire, not the new one');
});

await run('ts-any: a shrink under a declared changeset still rebalances, not fails', async () => {
  const r = await tsAnyOn({
    [SRC]: THREE_ANY,
    'gates/ts-any/baseline.txt': `${SRC} 5 2026-09-03\n`,
    '_baseline/gates/ts-any/baseline.txt': `${SRC} 5 2026-09-01\n`,
    'gates/ts-any/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass');
  assert.deepEqual(ids(r), ['ts-any/rebalance-available']);
});

// -- todo-fixme: the same four cases through a truth-table-driven gate.

const TODO_GATE = {
  id: 'todo-fixme',
  baseline: { path: 'gates/todo-fixme/baseline.txt' },
  changesetsDir: 'gates/todo-fixme/.changesets',
  config: { sourceGlobs: ['modules/ui-web/src/**/*.{ts,tsx}'], excludeGlobs: [] },
};
const TODO_SRC = 'modules/ui-web/src/y.ts';
const TWO_TODOS = '// TODO: one\n// FIXME: two\n';

async function todoOn(files) {
  const root = scaffold(files);
  return enforceTodoFixme({
    repoRoot: root, gate: TODO_GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

await run('todo-fixme: pin advanced → pass; pin unchanged → the new rule id', async () => {
  const advanced = await todoOn({
    [TODO_SRC]: TWO_TODOS,
    'gates/todo-fixme/baseline.txt': `${TODO_SRC} 2 2026-09-03\n`,
    '_baseline/gates/todo-fixme/baseline.txt': `${TODO_SRC} 0 2026-09-01\n`,
    'gates/todo-fixme/.changesets/918.md': CHANGESET,
  });
  assert.equal(advanced.verdict, 'pass', ids(advanced).join(', '));

  const stalled = await todoOn({
    [TODO_SRC]: TWO_TODOS,
    'gates/todo-fixme/baseline.txt': `${TODO_SRC} 1 2026-09-01\n`,
    '_baseline/gates/todo-fixme/baseline.txt': `${TODO_SRC} 1 2026-09-01\n`,
    'gates/todo-fixme/.changesets/918.md': CHANGESET,
  });
  assert.equal(stalled.verdict, 'fail');
  assert.ok(ids(stalled).includes('todo-fixme/declared-growth-without-repin'), ids(stalled).join(', '));
  assert.match(stalled.findings[0].message, /Measured 2 TODO\/FIXME markers/);
});

await run('todo-fixme: a pin raised under a changeset with the live count AT it still passes', async () => {
  // This is the case the rule must NOT break: the author declared the growth AND advanced the pin,
  // so the baseline-shift rule sees a covered raise and the count rule sees no exceedance.
  const r = await todoOn({
    [TODO_SRC]: TWO_TODOS,
    'gates/todo-fixme/baseline.txt': `${TODO_SRC} 2 2026-09-03\n`,
    '_baseline/gates/todo-fixme/baseline.txt': `${TODO_SRC} 0 2026-09-01\n`,
    'gates/todo-fixme/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass');
  assert.ok(!ids(r).some((i) => i.endsWith('silent-baseline-shift')),
    'the changeset is what licenses the pin advance — that half must keep working');
});

// -- dead-code: per-export rows only (no whole-file row ⇒ no TypeScript needed).

const DEAD_GATE = {
  id: 'dead-code',
  baseline: { path: 'gates/dead-code/baseline.txt' },
  changesetsDir: 'gates/dead-code/.changesets',
  config: { reportPath: 'tmp/knip-report.json', projectRoot: 'modules/ui-web' },
};
const DEAD_ROW = 'modules/ui-web/src/api/schema-types/index.ts';
const knipReport = (n) => JSON.stringify({
  issues: [{ file: DEAD_ROW, exports: Array.from({ length: n }, (_, i) => ({ name: `e${i}` })) }],
});

async function deadOn(files) {
  const root = scaffold(files);
  return enforceDeadCode({
    repoRoot: root, gate: DEAD_GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

await run('dead-code: reproduces the #614 shape — changeset, no re-pin → FAIL', async () => {
  const r = await deadOn({
    'tmp/knip-report.json': knipReport(53),
    'gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
    '_baseline/gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
    'gates/dead-code/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'fail');
  assert.ok(ids(r).includes('dead-code/declared-growth-without-repin'), ids(r).join(', '));
  assert.match(r.findings[0].message, /Measured 53 unused exports/);
  assert.match(r.findings[0].message, /#614→#613\/#615/, 'cites the incident it prevents');
});

await run('dead-code: the same PR with the pin advanced → pass', async () => {
  const r = await deadOn({
    'tmp/knip-report.json': knipReport(53),
    'gates/dead-code/baseline.txt': `${DEAD_ROW} 53 2026-09-03\n`,
    '_baseline/gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
    'gates/dead-code/.changesets/918.md': CHANGESET,
  });
  assert.equal(r.verdict, 'pass', ids(r).join(', '));
});

await run('dead-code: pin advanced WITHOUT a changeset is still a silent baseline shift', async () => {
  const r = await deadOn({
    'tmp/knip-report.json': knipReport(53),
    'gates/dead-code/baseline.txt': `${DEAD_ROW} 53 2026-09-03\n`,
    '_baseline/gates/dead-code/baseline.txt': `${DEAD_ROW} 51 2026-09-01\n`,
  });
  assert.equal(r.verdict, 'fail');
  assert.ok(ids(r).includes('dead-code/silent-baseline-shift'), ids(r).join(', '));
});

// --- cleanup + report ----------------------------------------------------------------------

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`declared-growth-repin.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`declared-growth-repin.test: all ${passed} checks passed`);
