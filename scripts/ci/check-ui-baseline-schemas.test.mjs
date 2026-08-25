/**
 * Tests for the ui-baseline-schemas gate.
 *
 * Its failure mode is the one it exists to prevent, one level up: a checker that reports clean
 * having checked NOTHING. (That is not hypothetical — the first draft compared `import.meta.url`
 * against a hand-built `file://` string, which never matches on Windows, so it exited 0 in silence.)
 * So every case below is a negative control on a synthetic governance dir: each defect the gate
 * claims to catch is planted, and the gate must report it.
 *
 * Run: `node scripts/ci/check-ui-baseline-schemas.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { checkUiBaselineSchemas, ENFORCED } from './check-ui-baseline-schemas.mjs';

let passed = 0;
const failures = [];
const ok = (label, cond) => {
  try {
    assert.ok(cond, label);
    passed += 1;
  } catch (e) {
    failures.push(e.message);
  }
};

const SCHEMA = {
  $schema: 'http://json-schema.org/draft-07/schema#',
  type: 'object',
  required: ['version', 'surfaces'],
  additionalProperties: false,
  properties: {
    $schema: { type: 'string' },
    version: { const: 1 },
    surfaces: {
      type: 'array',
      items: {
        type: 'object',
        required: ['surface', 'uiShotStep'],
        additionalProperties: false,
        properties: { surface: { type: 'string' }, uiShotStep: { type: 'string' } },
      },
    },
  },
};

/** Build a throwaway governance dir; `files` is {name: object|string}. Returns its path. */
function makeGov(files) {
  const dir = mkdtempSync(join(tmpdir(), 'ui-baseline-schemas-'));
  for (const [name, body] of Object.entries(files)) {
    writeFileSync(join(dir, name), typeof body === 'string' ? body : JSON.stringify(body, null, 2), 'utf8');
  }
  return dir;
}

const REG = 'ui-a11y-baseline.v1.json';
const only = [REG];
const good = {
  $schema: './ui-a11y-baseline.schema.json',
  version: 1,
  surfaces: [{ surface: 'chat', uiShotStep: 'chat-bands' }],
};
const dirs = [];
const run = (files, registers = only) => {
  const d = makeGov(files);
  dirs.push(d);
  return checkUiBaselineSchemas(d, registers);
};

// --- the gate must be SILENT on a conformant register (else every case below is vacuous) ---
ok(
  'clean: a conformant register reports nothing',
  run({ [REG]: good, 'ui-a11y-baseline.schema.json': SCHEMA }).length === 0,
);

// --- the defect this gate was written for: a pointer that resolves to no file ---
{
  const f = run({ [REG]: good });
  ok('dangling $schema is caught', f.length === 1 && /resolves to no file/.test(f[0]));
}

// --- a register with no pointer at all is not silently exempt ---
{
  const f = run({ [REG]: { version: 1, surfaces: [] }, 'ui-a11y-baseline.schema.json': SCHEMA });
  ok('missing $schema is caught', f.length === 1 && /no \$schema pointer/.test(f[0]));
}

// --- a remote pointer cannot be validated offline, so it must not read as validated ---
{
  const f = run({
    [REG]: { ...good, $schema: 'https://example.com/x.json' },
    'ui-a11y-baseline.schema.json': SCHEMA,
  });
  ok('remote $schema is caught', f.length === 1 && /is remote/.test(f[0]));
}

// --- the actual point: a document that violates its own schema ---
{
  const f = run({
    [REG]: { ...good, surfaces: [{ surface: 'chat' }] }, // uiShotStep missing
    'ui-a11y-baseline.schema.json': SCHEMA,
  });
  ok('a schema violation is caught', f.length === 1 && /uiShotStep/.test(f[0]));
}
{
  const f = run({
    [REG]: { ...good, stray: true },
    'ui-a11y-baseline.schema.json': SCHEMA,
  });
  ok('an unknown top-level key is caught', f.length === 1 && /additional properties/.test(f[0]));
}

// --- the rule draft-07 cannot express ---
{
  const f = run({
    [REG]: {
      ...good,
      surfaces: [
        { surface: 'chat', uiShotStep: 'chat-bands' },
        { surface: 'library', uiShotStep: 'chat-bands' },
      ],
    },
    'ui-a11y-baseline.schema.json': SCHEMA,
  });
  ok('a duplicate uiShotStep is caught', f.length === 1 && /duplicate uiShotStep/.test(f[0]));
}
{
  // Precision guard for the rule above: two rows sharing a SURFACE are legitimate and must pass —
  // otherwise the duplicate check would be rejecting the register's normal shape.
  const f = run({
    [REG]: {
      ...good,
      surfaces: [
        { surface: 'chat', uiShotStep: 'chat-bands' },
        { surface: 'chat', uiShotStep: 'chat-occlusion' },
      ],
    },
    'ui-a11y-baseline.schema.json': SCHEMA,
  });
  ok('two rows sharing a surface are NOT flagged', f.length === 0);
}

// --- malformed inputs must be errors, never silent passes ---
{
  const f = run({ [REG]: '{ not json', 'ui-a11y-baseline.schema.json': SCHEMA });
  ok('an unparseable register is caught', f.length === 1 && /not valid JSON/.test(f[0]));
}
{
  const f = run({ [REG]: good, 'ui-a11y-baseline.schema.json': '{ not json' });
  ok('an unparseable schema is caught', f.length === 1 && /not valid JSON/.test(f[0]));
}
{
  const f = run({ [REG]: good, 'ui-a11y-baseline.schema.json': { type: 'nonsense-keyword-value' } });
  ok('an uncompilable schema is caught', f.length === 1 && /does not compile/.test(f[0]));
}
{
  const f = run({ 'ui-a11y-baseline.schema.json': SCHEMA });
  ok('a missing enforced register is caught', f.length === 1 && /is missing/.test(f[0]));
}

// --- the enforced set must not silently empty out ---
ok('both UI baselines are enforced', ENFORCED.length === 2 && ENFORCED.every((r) => r.startsWith('ui-')));

// --- and the REAL repo state is green (the gate is wired to something, not just testable) ---
ok('the shipped registers pass', checkUiBaselineSchemas().length === 0);

for (const d of dirs) rmSync(d, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`check-ui-baseline-schemas.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  ✗ ${f}`);
  process.exit(1);
}
console.log(`check-ui-baseline-schemas.test: all ${passed} checks passed`);
