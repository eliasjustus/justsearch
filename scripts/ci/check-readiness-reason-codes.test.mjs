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
  extractEnumRows,
  extractCauseRowCodes,
  checkCorrespondence,
  checkProducers,
  collectMainSources,
  stripJavaComments,
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

// --- PRODUCER direction (tempdoc 837 §5) ---
const JAVA = (text, path = 'modules/x/src/main/java/X.java') => [{ path, text }];

ok(
  'extractEnumRows returns name+code pairs, and extractEnumCodes projects them',
  (() => {
    const rows = extractEnumRows('enum X { FOO("a.b"), BAR_BAZ("c.d"); }');
    const codes = extractEnumCodes('enum X { FOO("a.b"), BAR_BAZ("c.d"); }');
    return (
      rows.length === 2 &&
      rows[0].name === 'FOO' &&
      rows[0].code === 'a.b' &&
      rows[1].name === 'BAR_BAZ' &&
      rows[1].code === 'c.d' &&
      codes.has('a.b') &&
      codes.size === 2
    );
  })(),
);

ok(
  'PRODUCER fails: a phantom code with no reference anywhere',
  checkProducers({
    enumRows: [
      { name: 'WORKER_LOST', code: 'worker.lost' },
      { name: 'ORT_CUDA_READY', code: 'ort_cuda.ready' },
    ],
    mainSources: JAVA('void f() { emit(LifecycleReasonCode.WORKER_LOST.code()); }'),
    feDerived: [],
  }).some((f) => f.includes('producer') && f.includes('ort_cuda.ready') && f.includes('ORT_CUDA_READY')),
);

ok(
  'PRODUCER passes: a producer that names the enum member',
  checkProducers({
    enumRows: [{ name: 'WORKER_LOST', code: 'worker.lost' }],
    mainSources: JAVA('void f() { emit(LifecycleReasonCode.WORKER_LOST.code()); }'),
    feDerived: [],
  }).length === 0,
);

ok(
  'PRODUCER passes: a string-literal-only producer (defense in depth — no enum name in sight)',
  checkProducers({
    enumRows: [{ name: 'OCR_DISABLED', code: 'ocr.disabled' }],
    mainSources: JAVA('static final String DISABLED = "ocr.disabled";'),
    feDerived: [],
  }).length === 0,
);

ok(
  'PRODUCER fails: a code whose only mention is a javadoc comment (comment-stripping bites)',
  checkProducers({
    enumRows: [{ name: 'INFERENCE_CRASHED', code: 'inference.crashed' }],
    mainSources: JAVA(
      '/** TODO: emit LifecycleReasonCode.INFERENCE_CRASHED ("inference.crashed") one day. */\nclass A {}',
    ),
    feDerived: [],
  }).some((f) => f.includes('producer') && f.includes('inference.crashed')),
);

ok(
  'PRODUCER fails: a code whose only mention is a line comment',
  checkProducers({
    enumRows: [{ name: 'INFERENCE_CRASHED', code: 'inference.crashed' }],
    mainSources: JAVA('class A {\n  // LifecycleReasonCode.INFERENCE_CRASHED is not wired yet\n}'),
    feDerived: [],
  }).some((f) => f.includes('inference.crashed')),
);

ok(
  'PRODUCER fails: a longer sibling name does NOT satisfy a shorter code (word-boundary match)',
  checkProducers({
    enumRows: [
      { name: 'WORKER_LOST', code: 'worker.lost' },
      { name: 'WORKER_LOST_PERMANENTLY', code: 'worker.lost_permanently' },
    ],
    mainSources: JAVA('emit(LifecycleReasonCode.WORKER_LOST_PERMANENTLY.code());'),
    feDerived: [],
  }).some((f) => f.includes('worker.lost`') && !f.includes('worker.lost_permanently')),
);

ok(
  'PRODUCER exempts feDerived codes (FE-only codes have no Java producer by construction)',
  checkProducers({
    enumRows: [{ name: 'NO_DOCUMENTS', code: 'no_documents' }],
    mainSources: JAVA('class A {}'),
    feDerived: ['no_documents'],
  }).length === 0,
);

ok(
  'stripJavaComments preserves a `//` inside a string literal (no false phantom)',
  (() => {
    const stripped = stripJavaComments(
      'log("see http://x " + LifecycleReasonCode.WORKER_LOST.code()); // gone\n',
    );
    return stripped.includes('LifecycleReasonCode.WORKER_LOST') && !stripped.includes('gone');
  })(),
);

ok(
  'stripJavaComments removes block comments and keeps offsets/newlines stable',
  (() => {
    const src = 'a\n/* x\n y */\nb\n';
    const stripped = stripJavaComments(src);
    return (
      stripped.length === src.length &&
      stripped.split('\n').length === src.split('\n').length &&
      !stripped.includes('x') &&
      stripped.includes('a') &&
      stripped.includes('b')
    );
  })(),
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

ok(
  'the live repo has an emit site for EVERY reason code, with an empty effective exemption (837 S1/S2)',
  (() => {
    const reg = JSON.parse(readFileSync('governance/readiness-reason-codes.v1.json', 'utf8'));
    const enumRows = extractEnumRows(readFileSync(reg.producer.file, 'utf8'));
    const mainSources = collectMainSources('modules', reg.producer.file);
    const feDerived = reg.feDerived.map((e) => e.code);
    // No enum member is feDerived, so the producer direction runs with a genuinely empty exemption.
    const exemptedMembers = enumRows.filter((r) => feDerived.includes(r.code));
    return (
      enumRows.length > 0 &&
      mainSources.length > 0 &&
      exemptedMembers.length === 0 &&
      checkProducers({ enumRows, mainSources, feDerived }).length === 0
    );
  })(),
);

ok(
  'the four ORT_CUDA_* phantoms are gone from the enum, CAUSE_ROWS and the register (837 S1)',
  (() => {
    const reg = JSON.parse(readFileSync('governance/readiness-reason-codes.v1.json', 'utf8'));
    const enumSrc = readFileSync(reg.producer.file, 'utf8');
    const tsSrc = readFileSync(reg.consumer.file, 'utf8');
    const regSrc = readFileSync('governance/readiness-reason-codes.v1.json', 'utf8');
    return (
      !enumSrc.includes('ORT_CUDA_') &&
      !tsSrc.includes('ort_cuda.') &&
      !regSrc.includes('ort_cuda.')
    );
  })(),
);

if (failures.length > 0) {
  console.error(`✗ check-readiness-reason-codes.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-readiness-reason-codes.test OK (${passed} assertions)`);
