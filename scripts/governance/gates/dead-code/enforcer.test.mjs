/**
 * Tempdoc 910 item 1 — whole-file-finding normalization in the dead-code gate.
 *
 * The property under test: knip reports an entirely-unused module as ONE
 * `files[]` entry and a partly-used module as one entry PER unused export, and
 * the ratchet stores a single number per path. Unless the whole-file shape is
 * normalized to a per-export count, importing one symbol from a whole-file-
 * unused module flips its row 1 -> N and reads as `dead-code/silent-growth`
 * with no new dead code.
 *
 * Run with: `node scripts/governance/gates/dead-code/enforcer.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { enforceDeadCode } from './enforcer.mjs';
import { countDeclaredExports, loadTypeScript } from './export-count.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const PROJECT = 'modules/ui-web';
const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');

/**
 * Absolute path to the real `typescript` entry point, resolved exactly the way
 * the enforcer resolves it in a real run (from the knip project's install).
 * Fixture roots live in the OS temp dir, where no `node_modules` chain reaches
 * the repo, so each fixture gets a one-line re-export stub pointing here — the
 * test then depends on the same install the gate does, and on nothing ambient.
 */
const REAL_TYPESCRIPT = (() => {
  for (const anchor of [path.join(REPO_ROOT, PROJECT, 'package.json'), path.join(REPO_ROOT, 'package.json')]) {
    try {
      return createRequire(anchor).resolve('typescript');
    } catch { /* try the next anchor */ }
  }
  return null;
})();
const GATE = {
  baseline: { path: 'gates/dead-code/baseline.txt' },
  config: { reportPath: 'tmp/knip-report.json', projectRoot: PROJECT },
};

/** A module with four exports: two types, two functions. */
const FOUR_EXPORT_MODULE = [
  'export type FolderBrowseResponse = { folders: string[] };',
  'export type FolderFilesResponse = { files: string[] };',
  'export function fetchFolders(): FolderBrowseResponse { return { folders: [] }; }',
  'export function fetchFolderFiles(): FolderFilesResponse { return { files: [] }; }',
  '',
].join('\n');

function knipRow(file, extra) {
  return {
    file,
    binaries: [], catalog: [], dependencies: [], devDependencies: [], duplicates: [],
    enumMembers: [], exports: [], files: [], namespaceMembers: [],
    optionalPeerDependencies: [], types: [], unlisted: [], unresolved: [],
    ...extra,
  };
}

/** knip's shape for "this entire module has no consumer": one `files[]` entry. */
const wholeFileRow = (file) => knipRow(file, { files: [{ name: file }] });

/** knip's shape for "this module is used, these exports are not". */
const perExportRow = (file, exports, types = []) =>
  knipRow(file, {
    exports: exports.map((name) => ({ name })),
    types: types.map((name) => ({ name })),
  });

/**
 * Scaffold a temp repo root: `{ baseline, report, files }`.
 * `files` keys are PROJECT-relative, matching how knip reports paths.
 */
function scaffold({ baseline = '', report, files = {}, withTypeScript = true }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dead-code-enforcer-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  write('gates/dead-code/baseline.txt', baseline);
  write('tmp/knip-report.json', JSON.stringify(report));
  for (const [rel, content] of Object.entries(files)) write(path.join(PROJECT, rel), content);
  // The normalizer resolves `typescript` from the knip project dir first.
  write(path.join(PROJECT, 'package.json'), JSON.stringify({ name: 'fixture' }));
  if (withTypeScript) {
    write(path.join(PROJECT, 'node_modules/typescript/package.json'),
      JSON.stringify({ name: 'typescript', version: '0.0.0-fixture', main: 'index.cjs' }));
    write(path.join(PROJECT, 'node_modules/typescript/index.cjs'),
      `module.exports = require(${JSON.stringify(REAL_TYPESCRIPT)});\n`);
  }
  return root;
}

async function enforce(root) {
  return enforceDeadCode({ repoRoot: root, gate: GATE });
}

async function run(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

const ruleIds = (r) => r.findings.map((f) => f.ruleId);
const messages = (r) => r.findings.map((f) => f.message).join(' | ');

if (!REAL_TYPESCRIPT) {
  console.error('dead-code enforcer.test: `typescript` is not installed — run `npm ci --prefix modules/ui-web`.');
  process.exit(1);
}

// --- The core property: the two knip shapes produce the same number. ---

await run('whole-file and per-export findings for the same module count the same', async () => {
  const file = 'src/api/domains/browse.ts';
  const files = { [file]: FOUR_EXPORT_MODULE };

  // No baseline at all, so every measured count shows up as growth against the
  // implicit pin of 0 — which makes the counts themselves readable from the
  // findings, in both report shapes.
  const whole = await enforce(scaffold({ report: { issues: [wholeFileRow(file)] }, files }));
  const perExport = await enforce(scaffold({
    report: {
      issues: [perExportRow(file, ['fetchFolders', 'fetchFolderFiles'],
        ['FolderBrowseResponse', 'FolderFilesResponse'])],
    },
    files,
  }));

  const countOf = (r) => {
    const m = /: 0 → (\d+) unused exports/.exec(messages(r));
    assert.ok(m, `expected a growth message carrying a count, got: ${messages(r)}`);
    return Number(m[1]);
  };
  assert.equal(countOf(whole), 4, 'whole-file finding must normalize to the module export count');
  assert.equal(countOf(perExport), 4, 'per-export finding counts exports + types');
  assert.equal(countOf(whole), countOf(perExport),
    'the two knip shapes must be the same unit — this equality is the whole point of the gate fix');
});

await run('importing one symbol from a whole-file-unused module is not growth', async () => {
  const file = 'src/api/domains/browse.ts';
  const files = { [file]: FOUR_EXPORT_MODULE };
  // Pin taken from the whole-file state...
  const baseline = 'src/api/domains/browse.ts 4 2026-09-02\n';
  // ...and the module then gains a consumer, so knip switches to per-export.
  const r = await enforce(scaffold({
    baseline,
    report: { issues: [perExportRow(file, ['fetchFolderFiles'], ['FolderFilesResponse'])] },
    files,
  }));
  assert.equal(r.verdict, 'pass', `verdict (${messages(r)})`);
  assert.ok(!ruleIds(r).includes('dead-code/silent-growth'),
    'a 1 -> N unit flip must never read as growth');
  assert.ok(ruleIds(r).includes('dead-code/rebalance-available'),
    'gaining a consumer is a shrink, and the ratchet should offer to take it');
});

await run('a genuinely new dead export in a whole-file-unused module still fails', async () => {
  const file = 'src/api/domains/browse.ts';
  const r = await enforce(scaffold({
    baseline: 'src/api/domains/browse.ts 4 2026-09-02\n',
    report: { issues: [wholeFileRow(file)] },
    files: { [file]: FOUR_EXPORT_MODULE + 'export const newlyDeadThing = 1;\n' },
  }));
  assert.equal(r.verdict, 'fail', `verdict (${messages(r)})`);
  assert.ok(ruleIds(r).includes('dead-code/silent-growth'), messages(r));
  assert.match(messages(r), /4 → 5 unused exports/);
});

await run('a module with no exports keeps a floor of 1', async () => {
  const file = 'scripts/capture-evidence-bundle.mjs';
  const r = await enforce(scaffold({
    baseline: '',
    report: { issues: [wholeFileRow(file)] },
    files: { [file]: 'const x = 1;\nconsole.log(x);\n' },
  }));
  // Floored at 1 rather than 0: a 0 would drop the row out of the ratchet
  // entirely and lose the "this whole file is dead" signal.
  assert.match(messages(r), /: 0 → 1 unused exports/, messages(r));
});

await run('a bare `export * from` barrel is NOT credited with its transitive surface', async () => {
  // Measured against knip 6.20.0 (2026-09-02): a named import THROUGH a pure
  // star barrel removes the barrel's row entirely — knip attributes the
  // still-unused names to the origin module. Crediting the barrel with its
  // transitive surface would have pinned src/api/index.ts at 105 instead of 1.
  const barrel = 'src/api/index.ts';
  const r = await enforce(scaffold({
    baseline: '',
    report: { issues: [wholeFileRow(barrel)] },
    files: {
      [barrel]: "export * from './domains/browse';\n",
      'src/api/domains/browse.ts': FOUR_EXPORT_MODULE,
    },
  }));
  assert.match(messages(r), /: 0 → 1 unused exports/, messages(r));
});

await run('a whole-file finding for a file not on disk fails closed', async () => {
  const r = await enforce(scaffold({
    baseline: '',
    report: { issues: [wholeFileRow('src/api/domains/vanished.ts')] },
    files: {},
  }));
  assert.equal(r.verdict, 'fail', `verdict (${messages(r)})`);
  assert.ok(ruleIds(r).includes('dead-code/whole-file-uncounted'), messages(r));
  assert.match(messages(r), /is not on disk/, 'must fail for the missing-file reason, not a toolchain one');
  // The message must carry the remedy, not just the symptom.
  assert.match(messages(r), /npm ci --prefix modules\/ui-web/);
});

await run('a whole-file finding with no TypeScript install fails closed rather than counting 1', async () => {
  const r = await enforce(scaffold({
    baseline: 'src/api/domains/browse.ts 4 2026-09-02\n',
    report: { issues: [wholeFileRow('src/api/domains/browse.ts')] },
    files: { 'src/api/domains/browse.ts': FOUR_EXPORT_MODULE },
    withTypeScript: false,
  }));
  assert.equal(r.verdict, 'fail', `verdict (${messages(r)})`);
  assert.ok(ruleIds(r).includes('dead-code/whole-file-uncounted'), messages(r));
  assert.match(messages(r), /TypeScript compiler unavailable/);
  // Falling back to 1 would have passed here (1 <= pinned 4) and silently
  // reinstated the trap on the very next import.
  assert.ok(!ruleIds(r).includes('dead-code/within-baseline'));
});

// --- The counter itself. ---

await run('countDeclaredExports counts each declaration form once', async () => {
  const { ts, error } = loadTypeScript({ repoRoot: REPO_ROOT, projectRoot: PROJECT });
  assert.ok(ts, `typescript must be loadable: ${error}`);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'dead-code-count-'));
  tmpDirs.push(root);
  const write = (name, content) => {
    const abs = path.join(root, name);
    fs.writeFileSync(abs, content, 'utf8');
    return abs;
  };

  assert.equal(countDeclaredExports(ts, write('a.ts',
    'export const a = 1, b = 2;\nexport function c() {}\nexport class D {}\n')), 4);
  assert.equal(countDeclaredExports(ts, write('b.ts',
    'export type T = 1;\nexport interface I {}\nexport enum E { X }\n')), 3);
  assert.equal(countDeclaredExports(ts, write('c.ts',
    "export { a, b as c } from './x';\nexport type { T } from './y';\n")), 3);
  assert.equal(countDeclaredExports(ts, write('d.ts',
    "export * from './x';\nexport * from './y';\n")), 0,
  'bare star re-exports introduce no binding knip attributes to this module');
  assert.equal(countDeclaredExports(ts, write('e.ts',
    "export * as ns from './x';\n")), 1);
  assert.equal(countDeclaredExports(ts, write('f.ts',
    'export default class {}\n')), 1, 'an anonymous default is still one export');
  assert.equal(countDeclaredExports(ts, write('g.ts',
    'const v = 1;\nexport default v;\n')), 1);
  assert.equal(countDeclaredExports(ts, write('h.ts',
    'const notExported = 1;\nfunction alsoNot() {}\n')), 0);
});

// --- cleanup + report ---
for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`dead-code enforcer.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`dead-code enforcer.test: all ${passed} checks passed`);
