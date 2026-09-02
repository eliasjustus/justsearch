/**
 * Tempdoc 910 — module-deps baseline-shift.
 *
 * `module-deps/silent-baseline-shift` was DECLARED in MODULE_DEPS_RULE_DESCRIPTIONS while nothing
 * could emit it: `readFileAtRef` was imported and never called, so raising a pinned number by hand
 * passed as `rebalance-available`. A documented ruleId that cannot fire is worse than an absent one
 * — the rule catalog claims the hole is covered.
 *
 * Run with: `node scripts/governance/gates/module-deps/enforcer.test.mjs`
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceModuleDeps } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = {
  baseline: { path: 'gates/module-deps/baseline.txt' },
  changesetsDir: 'gates/module-deps/.changesets',
  config: { reportPath: 'tmp/arch-preflight/module-deps.json' },
};

function scaffold({ baseline, priorBaseline, modules, changesets = [] }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'module-deps-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  write('gates/module-deps/baseline.txt', baseline);
  if (priorBaseline !== undefined) write(`_baseline/gates/module-deps/baseline.txt`, priorBaseline);
  write('tmp/arch-preflight/module-deps.json', JSON.stringify({ modules }));
  fs.mkdirSync(path.join(root, 'gates/module-deps/.changesets'), { recursive: true });
  for (const cs of changesets) {
    const fm = Object.entries(cs.frontmatter).map(([k, v]) => `${k}: ${v}`).join('\n');
    write(`gates/module-deps/.changesets/${cs.id}.md`, `---\n${fm}\n---\n${cs.body ?? 'body'}\n`);
  }
  return root;
}

async function enforce(root) {
  return enforceModuleDeps({
    repoRoot: root, gate: GATE, baselineRef: 'HEAD', fixtureMode: true, fixtureRoot: root,
  });
}

async function run(label, fn) {
  try { await fn(); passed += 1; } catch (e) { failures.push(`${label}: ${e.message}`); }
}

const ruleIds = (r) => r.findings.map((f) => f.ruleId);
const messages = (r) => r.findings.map((f) => f.message).join(' | ');

// The measurement matches the raised pin, so the live-count check is satisfied either way — which
// is exactly why the baseline-shift check has to be the thing that notices.
const MODULES = [{ name: 'modules/app-services', productionDeps: ['a', 'b', 'c', 'd'] }];

await run('raising a pinned dep count without a changeset fails', async () => {
  const r = await enforce(scaffold({
    priorBaseline: 'modules/app-services 1 2026-07-16\n',
    baseline: 'modules/app-services 4 2026-09-02\n',
    modules: MODULES,
  }));
  assert.equal(r.verdict, 'fail', `verdict (${messages(r)})`);
  assert.ok(ruleIds(r).includes('module-deps/silent-baseline-shift'), messages(r));
  assert.match(messages(r), /baseline raised 1 → 4 without declared changeset/);
});

await run('a declared-growth changeset covers a raised pin', async () => {
  const r = await enforce(scaffold({
    priorBaseline: 'modules/app-services 1 2026-07-16\n',
    baseline: 'modules/app-services 4 2026-09-02\n',
    modules: MODULES,
    changesets: [{ id: '910-x', frontmatter: { classification: 'declared-growth', tempdoc: 910 } }],
  }));
  assert.equal(r.verdict, 'pass', `verdict (${messages(r)})`);
  assert.ok(!ruleIds(r).includes('module-deps/silent-baseline-shift'), messages(r));
  assert.match(messages(r), /'declared-growth' covers/);
});

await run('lowering a pin is always allowed', async () => {
  const r = await enforce(scaffold({
    priorBaseline: 'modules/app-services 9 2026-07-16\n',
    baseline: 'modules/app-services 4 2026-09-02\n',
    modules: MODULES,
  }));
  assert.equal(r.verdict, 'pass', `verdict (${messages(r)})`);
  assert.ok(!ruleIds(r).includes('module-deps/silent-baseline-shift'), messages(r));
});

await run('an unchanged pin produces no shift finding', async () => {
  const r = await enforce(scaffold({
    priorBaseline: 'modules/app-services 4 2026-07-16\n',
    baseline: 'modules/app-services 4 2026-09-02\n',
    modules: MODULES,
  }));
  assert.equal(r.verdict, 'pass', `verdict (${messages(r)})`);
  assert.deepEqual(ruleIds(r).filter((id) => id.includes('baseline-shift')), []);
});

await run('live dep growth past the pin still fails independently', async () => {
  const r = await enforce(scaffold({
    priorBaseline: 'modules/app-services 4 2026-07-16\n',
    baseline: 'modules/app-services 4 2026-09-02\n',
    modules: [{ name: 'modules/app-services', productionDeps: ['a', 'b', 'c', 'd', 'e'] }],
  }));
  assert.equal(r.verdict, 'fail', `verdict (${messages(r)})`);
  assert.ok(ruleIds(r).includes('module-deps/silent-growth'), messages(r));
});

for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`module-deps enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  x ${f}`);
  process.exit(1);
}
console.log(`module-deps enforcer.test: all ${passed} checks passed`);
