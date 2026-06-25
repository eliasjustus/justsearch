/**
 * Tempdoc 560 §5 — unit tests for the contribution-surface enforcer's barrel-purity classifier
 * (the keystone that locks in §4c). Proves the gate FIRES on a reintroduced hand-authored second
 * shape authority and stays QUIET on the legit generated-derivation forms — the discriminating
 * boundary the regex/line-scan must get right (the wrong-gate lesson: prove the gate fires in the
 * target scenario, not just that it exists).
 *
 * Run with: `node scripts/governance/gates/contribution-surface/enforcer.test.mjs`
 * Exits non-zero on any failure.
 */

import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { enforceContributionSurface } from './enforcer.mjs';

let passed = 0;
const failures = [];
const tmpDirs = [];

const GATE = {
  config: { register: 'governance/contribution-surfaces.v1.json', registry: 'governance/registry.v1.json' },
};

function scaffold({ barrel = '', register = {}, files = {} }) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'contribsurface-'));
  tmpDirs.push(root);
  const write = (rel, content) => {
    const abs = path.join(root, rel);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, content, 'utf8');
  };
  const reg = {
    version: 1,
    scan: {
      barrel: 'modules/ui-web/src/api/types/registry.ts',
      generatedDir: 'modules/ui-web/src/api/generated/schema-types',
      registryController: 'modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java',
      barrelImportAllow: ['../generated/', 'zod'],
    },
    surfaces: [],
    grandfatheredPending: [],
    registryAllowlist: [],
    ...register,
  };
  write('governance/contribution-surfaces.v1.json', JSON.stringify(reg, null, 2));
  write('modules/ui-web/src/api/types/registry.ts', barrel);
  for (const [rel, content] of Object.entries(files)) write(rel, content);
  return root;
}

async function enforce(root) {
  return enforceContributionSurface({ repoRoot: root, gate: GATE, fixtureMode: true, fixtureRoot: root });
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
const hasPurity = (r) => ruleIds(r).includes('contribution-surface/registry-barrel-purity');
const hasImport = (r) => ruleIds(r).includes('contribution-surface/registry-barrel-import-purity');

// ── The legit generated-derivation forms — the gate must stay QUIET. ──
await run('derived (Omit<Wire>&{}), NonNullable<Wire[…]>, union, scalar, allowlisted iface → pass', async () => {
  const root = scaffold({
    register: { registryAllowlist: [{ name: 'FooCatalog', kind: 'envelope', reason: 'fixture' }] },
    barrel:
      `import type { FooWire } from '../generated/schema-types/foo';\n` +
      `import { z } from 'zod';\n` +
      `export type Foo = Omit<FooWire, 'x'> & { y: string };\n` +
      `export type FooHistory = NonNullable<FooWire['history']>;\n` +
      `export type Privacy = FooWire['privacy'];\n` +
      `export type Category = 'A' | 'B' | 'C';\n` +
      `export type FooRef = string;\n` +
      `export interface FooCatalog { schemaVersion: string; entries: Foo[]; }\n` +
      `export const fooSchema = z.object({ y: z.string() });\n` +
      `export { fooSchema as alias };\n`,
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'pass', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(!hasPurity(r) && !hasImport(r), `should be pure (${ruleIds(r).join(',')})`);
});

// ── A bare hand-authored interface — the §4c regression — must FIRE. ──
await run('reintroduced bare `interface Sneaky {}` (not allowlisted) → fail (barrel-purity)', async () => {
  const root = scaffold({
    barrel:
      `import type { FooWire } from '../generated/schema-types/foo';\n` +
      `export type Foo = Omit<FooWire, 'x'> & { y: string };\n` +
      `export interface Sneaky { id: string; label: string; }\n`,
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(hasPurity(r), `(${ruleIds(r).join(',')})`);
  assert.ok(r.findings.some((f) => f.message.includes('Sneaky')), 'names the offending decl');
});

// ── A bare object-literal type alias — also a second authority — must FIRE. ──
await run('bare `type X = { … }` object-literal alias → fail (barrel-purity)', async () => {
  const root = scaffold({ barrel: `export type X = {\n  a: number;\n  b: string;\n};\n` });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(hasPurity(r), `(${ruleIds(r).join(',')})`);
});

// ── The DISCRIMINATION: Omit<Wire>&{} is allowed; `= {` is not. Same `{ … }` suffix, different RHS head. ──
await run('discrimination: `= Omit<Wire> & {}` passes, `= {}` fails', async () => {
  const ok = await enforce(
    scaffold({
      barrel:
        `import type { FooWire } from '../generated/schema-types/foo';\n` +
        `export type A = Omit<FooWire, 'k'> & { extra: string };\n`,
    }),
  );
  assert.ok(!hasPurity(ok), `Omit<Wire>&{} must pass (${ruleIds(ok).join(',')})`);
  const bad = await enforce(scaffold({ barrel: `export type A = { extra: string };\n` }));
  assert.ok(hasPurity(bad), `bare object alias must fail (${ruleIds(bad).join(',')})`);
});

// ── Allowlisted hand object → quiet; the SAME object un-allowlisted → fires. ──
await run('allowlist gates the exemption (allowlisted iface passes; un-allowlisted fires)', async () => {
  const withAllow = await enforce(
    scaffold({
      register: { registryAllowlist: [{ name: 'OperationInvocation', kind: 'helper', reason: 'fixture' }] },
      barrel: `export interface OperationInvocation { target: string; defaultArgsJson: string; }\n`,
    }),
  );
  assert.ok(!hasPurity(withAllow), `allowlisted iface passes (${ruleIds(withAllow).join(',')})`);
  const without = await enforce(
    scaffold({ barrel: `export interface OperationInvocation { target: string; defaultArgsJson: string; }\n` }),
  );
  assert.ok(hasPurity(without), `un-allowlisted iface fires (${ruleIds(without).join(',')})`);
});

// ── Import purity: a laundered import from a non-generated module fires; generated/zod stay quiet. ──
await run('laundered import from `./hand-types` → fail (import-purity); generated/zod pass', async () => {
  const bad = await enforce(
    scaffold({ barrel: `import { Bar } from './hand-types';\nexport type X = Bar;\n` }),
  );
  assert.ok(hasImport(bad), `(${ruleIds(bad).join(',')})`);
  const ok = await enforce(
    scaffold({
      barrel:
        `import type { FooWire } from '../generated/schema-types/foo';\n` +
        `import { z } from 'zod';\n` +
        `export type Foo = NonNullable<FooWire['x']>;\n`,
    }),
  );
  assert.ok(!hasImport(ok), `generated + zod imports pass (${ruleIds(ok).join(',')})`);
});

// ── A doc-comment containing `export interface` must NOT false-trip (block comments are stripped). ──
await run('a JSDoc mentioning `export interface Foo {}` does not false-trip', async () => {
  const root = scaffold({
    barrel:
      `/**\n * Example: \`export interface Foo { a: string }\` — this is documentation only.\n */\n` +
      `import type { FooWire } from '../generated/schema-types/foo';\n` +
      `export type Foo = Omit<FooWire, 'x'> & { y: string };\n`,
  });
  const r = await enforce(root);
  assert.ok(!hasPurity(r), `doc comment must not trip (${ruleIds(r).join(',')})`);
});

// ── Grandfather-drift: a pending primitive that GAINED a generated module → must promote. ──
await run('grandfather-drift: a pending primitive with a generated module → fail (promote)', async () => {
  const root = scaffold({
    register: { grandfatheredPending: [{ id: 'prompt', servedRaw: 'handlePrompts', potentialModule: 'prompt' }] },
    barrel: `export const x = 1;\n`,
    files: {
      'modules/ui-web/src/api/generated/schema-types/prompt.ts': 'export type PromptWire = { id: string };\n',
      'modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java':
        'class RegistryController { void handlePrompts() {} }\n',
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(
    ruleIds(r).includes('contribution-surface/grandfather-drift'),
    `(${ruleIds(r).join(',')})`,
  );
});

// ── Grandfather-coverage: a pending primitive whose handler vanished → fail. ──
await run('grandfather-coverage: a missing servedRaw handler → fail', async () => {
  const root = scaffold({
    register: { grandfatheredPending: [{ id: 'prompt', servedRaw: 'handlePrompts', potentialModule: 'prompt' }] },
    barrel: `export const x = 1;\n`,
    files: {
      'modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java':
        'class RegistryController { /* handlePrompts removed */ }\n',
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(
    ruleIds(r).includes('contribution-surface/grandfather-coverage'),
    `(${ruleIds(r).join(',')})`,
  );
});

// ── Multi-barrel (array scan.barrel): a hand interface in the SECOND barrel fires, tagged by file. ──
await run('array scan.barrel: bare interface in diagnostic.ts fires, tagged `diagnostic.ts:`', async () => {
  const root = scaffold({
    register: {
      scan: {
        barrel: [
          'modules/ui-web/src/api/types/registry.ts',
          'modules/ui-web/src/api/types/diagnostic.ts',
        ],
        generatedDir: 'modules/ui-web/src/api/generated/schema-types',
        registryController: 'modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java',
        barrelImportAllow: ['../generated/', 'zod', './registry'],
      },
    },
    barrel:
      `import type { FooWire } from '../generated/schema-types/foo';\n` +
      `export type Foo = Omit<FooWire, 'x'> & { y: string };\n`,
    files: {
      'modules/ui-web/src/api/types/diagnostic.ts':
        `import type { Presentation } from './registry.js';\n` +
        `export interface Sneaky { id: string; p: Presentation; }\n`,
    },
  });
  const r = await enforce(root);
  assert.equal(r.verdict, 'fail', `verdict (${ruleIds(r).join(',')})`);
  assert.ok(hasPurity(r), `(${ruleIds(r).join(',')})`);
  assert.ok(
    r.findings.some((f) => f.message.includes('diagnostic.ts:') && f.message.includes('Sneaky')),
    `tags the offending barrel + decl (${r.findings.map((f) => f.message).join(' | ')})`,
  );
  // The legit `./registry` sibling-barrel import must NOT trip import-purity.
  assert.ok(!hasImport(r), `sibling-barrel import (./registry) is allowed (${ruleIds(r).join(',')})`);
});

// ── Sibling-barrel import is gated by barrelImportAllow: an unlisted sibling fires. ──
await run('array scan.barrel: an import from an UNLISTED sibling (./other) fires import-purity', async () => {
  const root = scaffold({
    register: {
      scan: {
        barrel: [
          'modules/ui-web/src/api/types/registry.ts',
          'modules/ui-web/src/api/types/diagnostic.ts',
        ],
        generatedDir: 'modules/ui-web/src/api/generated/schema-types',
        registryController: 'modules/ui/src/main/java/io/justsearch/ui/api/RegistryController.java',
        barrelImportAllow: ['../generated/', 'zod', './registry'],
      },
    },
    barrel: `export const x = 1;\n`,
    files: {
      'modules/ui-web/src/api/types/diagnostic.ts':
        `import type { Bar } from './other-hand-module';\nexport type Y = Bar;\n`,
    },
  });
  const r = await enforce(root);
  assert.ok(hasImport(r), `unlisted sibling import fires (${ruleIds(r).join(',')})`);
  assert.ok(
    r.findings.some((f) => f.message.includes('diagnostic.ts:') && f.message.includes('other-hand-module')),
    `tags the barrel + module (${r.findings.map((f) => f.message).join(' | ')})`,
  );
});

// ── cleanup + report ──
for (const d of tmpDirs) {
  try {
    fs.rmSync(d, { recursive: true, force: true });
  } catch {
    /* best-effort */
  }
}

if (failures.length > 0) {
  console.error(`contribution-surface enforcer tests: ${passed} passed, ${failures.length} FAILED`);
  for (const f of failures) console.error('  ✗ ' + f);
  process.exit(1);
}
console.log(`contribution-surface enforcer tests: ${passed} passed`);
