/**
 * Tests for the consequence-classification gate (tempdoc 805 §G.2): the keyword-fallback claim may
 * live only in the classifier's module, and registered consumers must derive their consequence from
 * the classifier rather than re-deriving it from severity.
 *
 * Run: `node scripts/ci/check-consequence-classification.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import {
  stripComments,
  isAllowed,
  checkContainment,
  checkConsumerCoverage,
} from './check-consequence-classification.mjs';

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

const AUTHORITY = 'modules/ui-web/src/shell-v0/state/readinessNotice.ts';
const PHRASES = ['keyword results', 'keyword-ranked results'];
const ALLOW_FILES = ['modules/ui-web/src/shell-v0/aggregate-substrate/strategies/searchTraceExplain.ts'];

const reader = (map) => (f) => {
  if (!(f in map)) throw new Error('missing');
  return map[f];
};

// --- allowlist resolution ---
ok('the authority module is exempt', isAllowed(AUTHORITY, { authorityFile: AUTHORITY, allowFiles: [], allowTestFiles: false }));
ok(
  'an allowlisted file is exempt (searchTraceExplain reports the TRACE outcome, not a derived claim)',
  isAllowed(ALLOW_FILES[0], { authorityFile: AUTHORITY, allowFiles: ALLOW_FILES, allowTestFiles: false }),
);
ok(
  'a test file is exempt only when testFiles allowance is on',
  isAllowed('a/b.test.ts', { authorityFile: AUTHORITY, allowFiles: [], allowTestFiles: true }) &&
    !isAllowed('a/b.test.ts', { authorityFile: AUTHORITY, allowFiles: [], allowTestFiles: false }),
);
ok(
  'a plain source file is NOT exempt',
  !isAllowed('a/Banner.ts', { authorityFile: AUTHORITY, allowFiles: ALLOW_FILES, allowTestFiles: true }),
);
ok(
  'backslash paths normalize (Windows walk output vs the register\'s forward slashes)',
  isAllowed(AUTHORITY.split('/').join('\\'), { authorityFile: AUTHORITY, allowFiles: [], allowTestFiles: false }),
);

// --- containment ---
ok(
  'PASS: no file outside the authority carries a claim phrase',
  checkContainment({
    files: [AUTHORITY, 'x/Card.ts'],
    phrases: PHRASES,
    authorityFile: AUTHORITY,
    allowFiles: ALLOW_FILES,
    allowTestFiles: true,
    readFile: reader({
      [AUTHORITY]: "body: 'Showing keyword results; relevance ranking may be reduced.'",
      'x/Card.ts': "const caveat = KEYWORD_FALLBACK_CAVEAT;",
    }),
  }).length === 0,
);
ok(
  'BITE: a re-authored claim literal in another module fails',
  (() => {
    const f = checkContainment({
      files: ['x/Card.ts'],
      phrases: PHRASES,
      authorityFile: AUTHORITY,
      allowFiles: ALLOW_FILES,
      allowTestFiles: true,
      readFile: reader({ 'x/Card.ts': "const c = 'Showing keyword-ranked results — semantic ranking is degraded';" }),
    });
    return f.length === 1 && f[0].startsWith('re-authored claim:') && f[0].includes('x/Card.ts');
  })(),
);
ok(
  'a claim phrase inside a COMMENT does not trip the gate (prose about the rule is not the claim)',
  checkContainment({
    files: ['x/Card.ts'],
    phrases: PHRASES,
    authorityFile: AUTHORITY,
    allowFiles: ALLOW_FILES,
    allowTestFiles: true,
    readFile: reader({ 'x/Card.ts': '// never claim "keyword results" here\nconst a = 1;' }),
  }).length === 0,
);
ok(
  'stripComments removes // and /* */ comments',
  (() => {
    const s = stripComments('a /* keyword results */ b // keyword results\nc');
    return !s.includes('keyword results') && s.includes('a') && s.includes('c');
  })(),
);

// --- consumer coverage ---
ok(
  'PASS: a consumer calling the classifier',
  checkConsumerCoverage({
    consumers: ['availability.ts'],
    symbol: 'classifyConsequence',
    caveatExports: ['KEYWORD_FALLBACK_CAVEAT'],
    readFile: reader({ 'availability.ts': 'const c = classifyConsequence(verdict.reasons);' }),
  }).length === 0,
);
ok(
  'PASS: a consumer that only uses an exported caveat constant still counts',
  checkConsumerCoverage({
    consumers: ['availability.ts'],
    symbol: 'classifyConsequence',
    caveatExports: ['KEYWORD_FALLBACK_CAVEAT'],
    readFile: reader({ 'availability.ts': 'caveat: KEYWORD_FALLBACK_CAVEAT,' }),
  }).length === 0,
);
ok(
  'BITE: a consumer that re-derives from severity fails',
  (() => {
    const f = checkConsumerCoverage({
      consumers: ['availability.ts'],
      symbol: 'classifyConsequence',
      caveatExports: ['KEYWORD_FALLBACK_CAVEAT'],
      readFile: reader({ 'availability.ts': "const calm = verdict.severity === 'info';" }),
    });
    return f.length === 1 && f[0].startsWith('fork:');
  })(),
);
ok(
  'a doc-comment mention of the classifier does NOT satisfy coverage',
  (() => {
    const f = checkConsumerCoverage({
      consumers: ['availability.ts'],
      symbol: 'classifyConsequence',
      caveatExports: ['KEYWORD_FALLBACK_CAVEAT'],
      readFile: reader({ 'availability.ts': '// see classifyConsequence(codes) — KEYWORD_FALLBACK_CAVEAT\nconst a = 1;' }),
    });
    return f.length === 1 && f[0].startsWith('fork:');
  })(),
);
ok(
  'unresolved fail: a registered consumer path that does not exist',
  (() => {
    const f = checkConsumerCoverage({
      consumers: ['gone.ts'],
      symbol: 'classifyConsequence',
      caveatExports: ['KEYWORD_FALLBACK_CAVEAT'],
      readFile: reader({}),
    });
    return f.length === 1 && f[0].startsWith('unresolved:');
  })(),
);

if (failures.length > 0) {
  console.error(`✗ check-consequence-classification.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-consequence-classification.test OK (${passed} assertions)`);
