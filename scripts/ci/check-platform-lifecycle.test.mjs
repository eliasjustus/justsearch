/**
 * Focused tests for the offline platform lifecycle evidence check (tempdoc 893 §D.1/P.1).
 * Run: node scripts/ci/check-platform-lifecycle.test.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  REGISTER_PATH,
  evaluateRegister,
  extractPin,
  renderReport,
  runCli,
  shouldFail,
  validateRegister,
} from './check-platform-lifecycle.mjs';

const FIXTURE_PATH = 'scripts/ci/fixtures/platform-lifecycle/cases.v1.json';
const fixtures = JSON.parse(readFileSync(FIXTURE_PATH, 'utf8'));

let passed = 0;
const failures = [];
function test(label, body) {
  try {
    body();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.stack ?? error.message}`);
  }
}

function clone(value) {
  return structuredClone(value);
}

function reader(sources) {
  return (path) => {
    if (!Object.prototype.hasOwnProperty.call(sources, path)) throw new Error('fixture source missing');
    return sources[path];
  };
}

function throwsContaining(body, fragment) {
  assert.throws(body, (error) => error instanceof Error && error.message.includes(fragment));
}

for (const fixture of fixtures.extractorCases) {
  test(`singular adapter extraction: ${fixture.label}`, () => {
    assert.equal(extractPin(fixture.pinSource, fixture.source), fixture.expected);
  });
}

test('all five lifecycle policy kinds evaluate without conflating their evidence shapes', () => {
  const result = evaluateRegister(fixtures.evaluationRegister, {
    readSource: reader(fixtures.evaluationSources),
    asOf: fixtures.asOf,
  });
  assert.deepEqual(
    new Set(result.resolutions.map((row) => row.policyKind)),
    new Set(['fixed-date', 'release-relative', 'rolling', 'compatibility-matrix', 'no-published-eol']),
  );
  assert.ok(result.findings.some((f) => f.id === 'fixed' && f.category === 'support' && f.severity === 'failure'));
  assert.ok(result.findings.some((f) => f.id === 'relative' && f.category === 'support' && f.severity === 'failure'));
  assert.ok(result.findings.some((f) => f.id === 'rolling' && f.category === 'evidence' && f.severity === 'failure'));
  assert.ok(!result.findings.some((f) => f.id === 'matrix' && f.category === 'support'));
  assert.ok(!result.findings.some((f) => f.id === 'unknown-eol' && f.category === 'support'));
});

test('report mode stays advisory while gate mode fails lifecycle failures', () => {
  const result = evaluateRegister(fixtures.evaluationRegister, {
    readSource: reader(fixtures.evaluationSources),
    asOf: fixtures.asOf,
  });
  assert.equal(shouldFail('report', result), false);
  assert.equal(shouldFail('gate', result), true);
  const output = renderReport(result);
  assert.match(output, /FAILURE \[support] fixed:/);
  assert.match(output, /FAILURE \[evidence] rolling:/);
});

test('support within warning window and evidence inside grace are warnings, not gate failures', () => {
  const register = clone(fixtures.evaluationRegister);
  register.platforms = register.platforms.filter((row) => row.id === 'fixed' || row.id === 'rolling');
  register.platforms.find((row) => row.id === 'fixed').policy.supportUntil = '2026-10-01';
  register.platforms.find((row) => row.id === 'rolling').reviewBy = '2026-08-20';
  const sources = {
    'fixed.kt': fixtures.evaluationSources['fixed.kt'],
    'rolling.lock': fixtures.evaluationSources['rolling.lock'],
  };
  const result = evaluateRegister(register, { readSource: reader(sources), asOf: fixtures.asOf });
  assert.equal(result.findings.length, 2);
  assert.ok(result.findings.every((finding) => finding.severity === 'warning'));
  assert.equal(shouldFail('gate', result), false);
});

test('evidence overdue exactly at the declared grace is a failure', () => {
  const register = clone(fixtures.evaluationRegister);
  const rolling = register.platforms.find((row) => row.id === 'rolling');
  register.platforms = [rolling];
  rolling.reviewBy = '2026-08-04';
  const result = evaluateRegister(register, {
    readSource: reader({ 'rolling.lock': fixtures.evaluationSources['rolling.lock'] }),
    asOf: fixtures.asOf,
  });
  assert.equal(result.findings[0]?.category, 'evidence');
  assert.equal(result.findings[0]?.severity, 'failure');
});

test('ambiguous pin extraction fails closed', () => {
  const pinSource = { path: 'versions.toml', adapter: 'toml-version', selector: 'lucene' };
  throwsContaining(() => extractPin(pinSource, fixtures.ambiguousSource), 'resolved 2 pins; expected exactly 1');
});

test('missing pin extraction fails closed', () => {
  const pinSource = { path: 'versions.toml', adapter: 'toml-version', selector: 'lucene' };
  throwsContaining(() => extractPin(pinSource, fixtures.missingSource), 'resolved 0 pins; expected exactly 1');
});

test('malformed structured pin source fails closed', () => {
  const pinSource = { path: 'runtime.json', adapter: 'json-pointer', selector: '/version' };
  throwsContaining(() => extractPin(pinSource, fixtures.malformedJsonSource), 'source is malformed JSON');
});

test('unreadable pin source fails closed even in an otherwise valid register', () => {
  throwsContaining(
    () => evaluateRegister(fixtures.evaluationRegister, { readSource: reader({}), asOf: fixtures.asOf }),
    'cannot read pin source',
  );
});

test('unknown schema fields, including a copied pin version, are rejected', () => {
  const register = clone(fixtures.evaluationRegister);
  register.platforms[0].version = '25';
  throwsContaining(() => validateRegister(register), 'unknown field `version`');
});

test('malformed policy discriminants and policy-specific fields are rejected', () => {
  const badKind = clone(fixtures.evaluationRegister);
  badKind.platforms[0].policy = { kind: 'guess' };
  throwsContaining(() => validateRegister(badKind), 'policy.kind must be one of');

  const crossKindField = clone(fixtures.evaluationRegister);
  crossKindField.platforms[2].policy.supportUntil = '2027-01-01';
  throwsContaining(() => validateRegister(crossKindField), 'unknown field `supportUntil`');
});

test('compatibility matrices require both named evidence authorities', () => {
  const register = clone(fixtures.evaluationRegister);
  delete register.platforms.find((row) => row.id === 'matrix').additionalSourceUrls;
  throwsContaining(() => validateRegister(register), 'requires additionalSourceUrls');
});

test('invalid dates, URLs, source paths, and adapters fail schema validation', () => {
  const badDate = clone(fixtures.evaluationRegister);
  badDate.platforms[0].reviewBy = '2026-02-30';
  throwsContaining(() => validateRegister(badDate), 'not a real calendar date');

  const badUrl = clone(fixtures.evaluationRegister);
  badUrl.platforms[0].sourceUrl = 'http://example.com/lifecycle';
  throwsContaining(() => validateRegister(badUrl), 'absolute HTTPS URL');

  const badPath = clone(fixtures.evaluationRegister);
  badPath.platforms[0].pinSource.path = '../outside.txt';
  throwsContaining(() => validateRegister(badPath), 'repository-relative path');

  const badAdapter = clone(fixtures.evaluationRegister);
  badAdapter.platforms[0].pinSource.adapter = 'regex';
  throwsContaining(() => validateRegister(badAdapter), 'adapter must be one of');
});

test('release-relative evidence must be coherent with its source review date', () => {
  const register = clone(fixtures.evaluationRegister);
  register.platforms.find((row) => row.id === 'relative').policy.successorObservedOn = '2026-09-04';
  throwsContaining(() => validateRegister(register), 'must not follow sourceCheckedOn');
});

test('future-dated evidence fails closed rather than postponing review', () => {
  const register = clone(fixtures.evaluationRegister);
  register.platforms = [register.platforms.find((row) => row.id === 'fixed')];
  register.platforms[0].sourceCheckedOn = '2026-09-04';
  register.platforms[0].reviewBy = '2027-01-01';
  throwsContaining(
    () =>
      evaluateRegister(register, {
        readSource: reader({ 'fixed.kt': fixtures.evaluationSources['fixed.kt'] }),
        asOf: fixtures.asOf,
      }),
    'is in the future',
  );
});

test('the real register resolves every seeded platform from its live source', () => {
  const register = JSON.parse(readFileSync(REGISTER_PATH, 'utf8'));
  const result = evaluateRegister(register, {
    readSource: (path) => readFileSync(path, 'utf8'),
    asOf: '2026-09-03',
  });
  assert.equal(result.resolutions.length, 10);
  assert.equal(new Set(result.resolutions.map((row) => row.id)).size, 10);
  assert.deepEqual(
    result.resolutions.map(({ id, pin }) => `${id}=${pin}`),
    [
      'jdk=25',
      'gradle=9.6.1',
      'lucene=10.4.0',
      'tauri=2.11.5',
      'webview2=0.38.2',
      'cuda=12.4',
      'onnx-runtime=1.24.3',
      'node=24.14.0',
      'llama-cpp=b8571',
      'tesseract=5.5.0.20241111',
    ],
  );
  assert.ok(result.findings.some((finding) => finding.id === 'gradle' && finding.category === 'support'));
});

test('CLI returns advisory success in report mode and failure in gate mode', () => {
  const originalLog = console.log;
  const originalError = console.error;
  const output = [];
  console.log = (...args) => output.push(args.join(' '));
  console.error = (...args) => output.push(args.join(' '));
  try {
    assert.equal(runCli(['--mode', 'report', '--as-of', '2026-09-03']), 0);
    assert.equal(runCli(['--mode', 'gate', '--as-of', '2026-09-03']), 1);
  } finally {
    console.log = originalLog;
    console.error = originalError;
  }
  assert.ok(output.some((line) => line.includes('platform-lifecycle report OK')));
  assert.ok(output.some((line) => line.includes('platform-lifecycle gate FAILED')));
});

if (failures.length > 0) {
  console.error(`✗ check-platform-lifecycle.test FAILED (${failures.length} of ${passed + failures.length})`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(`✓ check-platform-lifecycle.test OK (${passed} assertions)`);
