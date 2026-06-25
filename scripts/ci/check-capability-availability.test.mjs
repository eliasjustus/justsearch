/**
 * Tests for the capability-availability gate (tempdoc 613 §10): registered capability-availability display
 * surfaces must project from `projectAvailability`, so they cannot re-fork the reason vocabulary.
 *
 * Run: `node scripts/ci/check-capability-availability.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { stripComments, consumesAuthority, checkCoverage } from './check-capability-availability.mjs';

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

// --- comment stripping + consumer detection ---
ok(
  'a doc-comment mention of the symbol does NOT count as consuming it',
  !consumesAuthority('// see projectAvailability(x) for details\nconst a = 1;', 'projectAvailability'),
);
ok(
  'a real call site counts as consuming the authority',
  consumesAuthority("const av = projectAvailability('documents', s);", 'projectAvailability'),
);
ok(
  'stripComments removes // and /* */ comments',
  (() => {
    const s = stripComments('a /* projectAvailability( */ b // projectAvailability(\nc');
    return !s.includes('projectAvailability') && s.includes('a') && s.includes('c');
  })(),
);

// --- coverage check ---
const reader = (map) => (f) => {
  if (!(f in map)) throw new Error('missing');
  return map[f];
};
ok(
  'PASS: every registered surface calls the authority',
  checkCoverage({
    surfaces: ['a.ts', 'b.ts'],
    symbol: 'projectAvailability',
    readFile: reader({
      'a.ts': "import { projectAvailability } from 'x';\nprojectAvailability('agent', s);",
      'b.ts': "const r = projectAvailability('extract', s);",
    }),
  }).length === 0,
);
ok(
  'FORK fail: a registered surface that hardcodes instead of projecting',
  (() => {
    const f = checkCoverage({
      surfaces: ['fork.ts'],
      symbol: 'projectAvailability',
      readFile: reader({ 'fork.ts': "if (!caps.chat) label = 'Chat unavailable';" }),
    });
    return f.length === 1 && f[0].startsWith('fork:') && f[0].includes('fork.ts');
  })(),
);
ok(
  'unresolved fail: a registered surface path that does not exist',
  (() => {
    const f = checkCoverage({
      surfaces: ['gone.ts'],
      symbol: 'projectAvailability',
      readFile: reader({}),
    });
    return f.length === 1 && f[0].startsWith('unresolved:');
  })(),
);

if (failures.length > 0) {
  console.error(`✗ check-capability-availability.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-capability-availability.test OK (${passed} assertions)`);
