import assert from 'node:assert/strict';

import {
  checkDurableStoreRegister,
  checkParity,
  extractCatalogEntries,
  findHardcodedCipherCalls,
} from './check-store-recoverability.mjs';

let passed = 0;
const failures = [];

function test(label, assertion) {
  try {
    assertion();
    passed += 1;
  } catch (error) {
    failures.push(`${label}: ${error.message}`);
  }
}

const SAMPLE_CATALOG = `
  CONVERSATIONS("conversations", StoreRecoverability.AUTHORED, Framing.MIXED),
  INDEX("index", StoreRecoverability.DERIVED, Framing.OPAQUE);
`;

test('extractCatalogEntries pulls constant, directory, and class', () => {
  assert.deepEqual(extractCatalogEntries(SAMPLE_CATALOG), [
    { constant: 'CONVERSATIONS', dirName: 'conversations', recoverability: 'AUTHORED' },
    { constant: 'INDEX', dirName: 'index', recoverability: 'DERIVED' },
  ]);
});

test('findHardcodedCipherCalls flags a bare recoverability literal', () => {
  assert.deepEqual(
    findHardcodedCipherCalls('var cipher = storeCipher(StoreRecoverability.AUTHORED);'),
    ['AUTHORED'],
  );
});

test('findHardcodedCipherCalls accepts StoreCatalog-derived selection', () => {
  assert.deepEqual(
    findHardcodedCipherCalls('storeCipher(StoreCatalog.MEMORIES.recoverability())'),
    [],
  );
});

const CATALOG = [
  { constant: 'CONVERSATIONS', dirName: 'conversations', recoverability: 'AUTHORED' },
  { constant: 'INDEX', dirName: 'index', recoverability: 'DERIVED' },
];

test('checkParity passes for an exact mirror', () => {
  assert.deepEqual(
    checkParity(CATALOG, [
      { dirName: 'conversations', recoverability: 'AUTHORED' },
      { dirName: 'index', recoverability: 'DERIVED' },
    ]),
    [],
  );
});

test('checkParity reports missing, drifted, and extra rows', () => {
  const result = checkParity(CATALOG, [
    { dirName: 'conversations', recoverability: 'DERIVED' },
    { dirName: 'ghost', recoverability: 'AUTHORED' },
  ]);
  assert.equal(result.length, 3);
  assert.ok(result.some((failure) => failure.includes('conversations')));
  assert.ok(result.some((failure) => failure.includes('index')));
  assert.ok(result.some((failure) => failure.includes('ghost')));
});

function readyRow(overrides = {}) {
  return {
    id: 'conversations',
    catalogDirName: 'conversations',
    owner: 'HEAD',
    root: 'DATA_DIR',
    path: 'conversations/',
    recoverability: 'AUTHORED',
    format: 'JSON envelope v1',
    currentVersion: 1,
    readableLegacyVersions: [0],
    versionAuthority: 'Version.java',
    futureVersionRefusalTest: 'StoreTest.java',
    writeMode: 'FULL_REWRITE',
    atomicity: 'ATOMIC_REPLACE',
    corruptionPolicy: 'FAIL_LOUD',
    reconciliation: 'UPCAST_AND_REWRITE',
    status: 'READY',
    implementationSources: ['Store.java'],
    tests: ['StoreTest.java'],
    fixtures: ['v0.json'],
    ...overrides,
  };
}

function check(rows, options = {}) {
  return checkDurableStoreRegister({
    root: '/repo',
    durableStores: rows,
    knownCompatibilityGaps: options.gaps ?? [],
    catalogEntries: options.catalog ?? [CATALOG[0]],
    discoveredStores: options.discovered ?? ['Store.java'],
    pathExists: options.pathExists ?? (() => true),
  });
}

test('broad register accepts a complete ready row', () => {
  assert.deepEqual(check([readyRow()]), []);
});

test('broad register rejects an uncovered durable Store implementation', () => {
  const result = check([readyRow()], { discovered: ['Store.java', 'NewStore.java'] });
  assert.ok(result.some((failure) => failure.includes('NewStore.java')));
});

test('broad register requires StoreCatalog coverage', () => {
  const result = check([readyRow({ catalogDirName: undefined })]);
  assert.ok(result.some((failure) => failure.includes('StoreCatalog.CONVERSATIONS')));
});

test('READY authored full rewrites must fail loud and write atomically', () => {
  const result = check([
    readyRow({ corruptionPolicy: 'SILENT_EMPTY', atomicity: 'DIRECT_REWRITE' }),
  ]);
  assert.ok(result.some((failure) => failure.includes('SILENT_EMPTY')));
  assert.ok(result.some((failure) => failure.includes('must be atomic')));
});

test('versioned READY rows require a version authority and future-version test', () => {
  const result = check([
    readyRow({ versionAuthority: undefined, futureVersionRefusalTest: undefined }),
  ]);
  assert.ok(result.some((failure) => failure.includes('versionAuthority')));
  assert.ok(result.some((failure) => failure.includes('futureVersionRefusalTest')));
});

test('HARDENING_REQUIRED is accepted only through the explicit gap ratchet', () => {
  const gap = readyRow({
    id: 'legacy',
    catalogDirName: undefined,
    currentVersion: 0,
    status: 'HARDENING_REQUIRED',
    atomicity: 'DIRECT_REWRITE',
    corruptionPolicy: 'SILENT_EMPTY',
  });
  assert.ok(
    check([gap], { gaps: [], catalog: [], discovered: ['Store.java'] }).some((failure) =>
      failure.includes('not ratcheted'),
    ),
  );
  assert.deepEqual(
    check([gap], { gaps: ['legacy'], catalog: [], discovered: ['Store.java'] }),
    [],
  );
});

test('all declared source and evidence paths must resolve', () => {
  const result = check([readyRow()], {
    pathExists: (path) => !path.endsWith('v0.json'),
  });
  assert.ok(result.some((failure) => failure.includes('v0.json')));
});

if (failures.length > 0) {
  console.error(`check-store-recoverability.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-store-recoverability.test OK - ${passed} assertions passed.`);
