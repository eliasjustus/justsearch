/**
 * Tests for the message-classes gate (tempdoc 613 §5.2/§8): the LOCAL_MESSAGE_CLASSES↔emit
 * correspondence check that keeps a free-form/typo'd local message class off the toast channel.
 *
 * Run: `node scripts/ci/check-message-classes.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import {
  extractDeclaredClasses,
  extractEmittedClassIds,
  checkCorrespondence,
} from './check-message-classes.mjs';

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

// --- extraction ---
ok(
  'extractDeclaredClasses pulls the policy-row keys, stops at the `} as const` terminator',
  (() => {
    const src =
      "export const LOCAL_MESSAGE_CLASSES = {\n" +
      "  'core.ephemeral': { renderHint: 'EPHEMERAL', supersede: false },\n" +
      "  'core.navigation': { renderHint: 'EPHEMERAL', supersede: true, defaultSeverity: 'info' },\n" +
      "} as const satisfies Record<string, X>;\n" +
      "export const DEFAULT_MESSAGE_CLASS = 'core.ephemeral';\n"; // must NOT be picked up
    const c = extractDeclaredClasses(src);
    return c.has('core.ephemeral') && c.has('core.navigation') && c.size === 2;
  })(),
);
ok(
  "extractEmittedClassIds pulls `classId: '...'` literals, ignores `=== '...'` compares",
  (() => {
    const src =
      "emitEphemeralToast({ classId: 'core.navigation', message: 'x' });\n" +
      "if (event.classId === 'operation.completed') {}\n"; // a wire compare, not an emit
    const c = extractEmittedClassIds(src);
    return c.has('core.navigation') && !c.has('operation.completed') && c.size === 1;
  })(),
);

// --- correspondence ---
ok(
  'PASS: emitted ⊆ declared, every non-default declared class is emitted',
  checkCorrespondence({
    declared: new Set(['core.ephemeral', 'core.navigation', 'core.verdict.settled']),
    emitted: new Set(['core.navigation', 'core.verdict.settled']),
    defaultClass: 'core.ephemeral',
    emitExempt: [],
  }).length === 0,
);
ok(
  'FORWARD fail: an emitted classId not declared',
  (() => {
    const f = checkCorrespondence({
      declared: new Set(['core.ephemeral', 'core.navigation']),
      emitted: new Set(['core.navigation', 'core.typo']),
      defaultClass: 'core.ephemeral',
      emitExempt: [],
    });
    return f.length === 1 && f[0].startsWith('forward:') && f[0].includes('core.typo');
  })(),
);
ok(
  'BACKWARD fail: a declared class never emitted (and not the default)',
  (() => {
    const f = checkCorrespondence({
      declared: new Set(['core.ephemeral', 'core.navigation', 'core.dead']),
      emitted: new Set(['core.navigation']),
      defaultClass: 'core.ephemeral',
      emitExempt: [],
    });
    return f.length === 1 && f[0].startsWith('backward:') && f[0].includes('core.dead');
  })(),
);
ok(
  'default class is BACKWARD-exempt (never emitted as a literal)',
  checkCorrespondence({
    declared: new Set(['core.ephemeral', 'core.navigation']),
    emitted: new Set(['core.navigation']),
    defaultClass: 'core.ephemeral',
    emitExempt: [],
  }).length === 0,
);

if (failures.length > 0) {
  console.error(`✗ check-message-classes.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-message-classes.test OK (${passed} assertions)`);
