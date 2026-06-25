/**
 * Tests for the readiness-reason-codes gate (tempdoc 600 PART IX/X): the producer↔CAUSE_ROWS
 * correspondence check that keeps a raw `Degraded: <code>` from reaching the user.
 *
 * Run: `node scripts/ci/check-readiness-reason-codes.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  extractEnumCodes,
  extractCauseRowCodes,
  checkCorrespondence,
} from './check-readiness-reason-codes.mjs';

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
  'extractEnumCodes pulls IDENT("code") members',
  (() => {
    const c = extractEnumCodes('enum X { FOO("a.b"), BAR("c.d"); }');
    return c.has('a.b') && c.has('c.d') && c.size === 2;
  })(),
);
ok(
  "extractCauseRowCodes pulls code:'...' rows and stops at the array terminator",
  (() => {
    const src = "const CAUSE_ROWS = [\n { code: 'a.b', wording: 'x' },\n { code: 'c.d' },\n];\nfunction reasonFor(code) {}";
    const c = extractCauseRowCodes(src);
    return c.has('a.b') && c.has('c.d') && c.size === 2; // the reasonFor(code) param is NOT matched
  })(),
);

// --- forward: an emittable, non-exempt, unworded code FAILS ---
ok(
  'FORWARD fails: emittable code with no CAUSE_ROWS row and not exempt',
  checkCorrespondence({
    enumCodes: new Set(['index.blocked_legacy', 'new.unworded_code']),
    causeRowCodes: new Set(['index.blocked_legacy']),
    noWordingExempt: [],
    feDerived: [],
  }).some((f) => f.includes('forward') && f.includes('new.unworded_code')),
);
ok(
  'FORWARD passes: the same code when declared noWordingExempt',
  checkCorrespondence({
    enumCodes: new Set(['index.blocked_legacy', 'new.unworded_code']),
    causeRowCodes: new Set(['index.blocked_legacy']),
    noWordingExempt: ['new.unworded_code'],
    feDerived: [],
  }).length === 0,
);

// --- backward: a CAUSE_ROWS code that is neither an enum member nor FE-derived FAILS ---
ok(
  'BACKWARD fails: worded code that is not a real enum member nor FE-derived',
  checkCorrespondence({
    enumCodes: new Set(['index.blocked_legacy']),
    causeRowCodes: new Set(['index.blocked_legacy', 'typo.code']),
    noWordingExempt: [],
    feDerived: [],
  }).some((f) => f.includes('backward') && f.includes('typo.code')),
);
ok(
  'BACKWARD passes: declared FE-derived code',
  checkCorrespondence({
    enumCodes: new Set(['index.blocked_legacy']),
    causeRowCodes: new Set(['index.blocked_legacy', 'no_documents']),
    noWordingExempt: [],
    feDerived: ['no_documents'],
  }).length === 0,
);

// --- the REAL repo state passes (integration sanity) ---
ok(
  'the live repo (LifecycleReasonCode + CAUSE_ROWS + register) corresponds — no failures',
  (() => {
    const reg = JSON.parse(readFileSync('governance/readiness-reason-codes.v1.json', 'utf8'));
    const enumCodes = extractEnumCodes(readFileSync(reg.producer.file, 'utf8'));
    const causeRowCodes = extractCauseRowCodes(readFileSync(reg.consumer.file, 'utf8'));
    return (
      checkCorrespondence({
        enumCodes,
        causeRowCodes,
        noWordingExempt: reg.noWordingExempt.map((e) => e.code),
        feDerived: reg.feDerived.map((e) => e.code),
      }).length === 0
    );
  })(),
);

if (failures.length > 0) {
  console.error(`✗ check-readiness-reason-codes.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-readiness-reason-codes.test OK (${passed} assertions)`);
