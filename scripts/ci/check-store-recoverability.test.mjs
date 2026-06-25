/**
 * Tests for the store-recoverability gate (tempdoc 629 #5): StoreCatalog ↔ register parity + the
 * no-hardcoded-cipher-class invariant.
 *
 * Run: `node scripts/ci/check-store-recoverability.test.mjs` (exits non-zero on failure)
 */
import assert from 'node:assert/strict';
import {
  extractCatalogEntries,
  findHardcodedCipherCalls,
  checkParity,
} from './check-store-recoverability.mjs';

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
const SAMPLE_CATALOG = `
  CONVERSATIONS("conversations", StoreRecoverability.AUTHORED, Framing.MIXED),
  INDEX("index", StoreRecoverability.DERIVED, Framing.OPAQUE);
`;
ok('extractCatalogEntries pulls dirName + class', () => {
  const e = extractCatalogEntries(SAMPLE_CATALOG);
  return (
    e.length === 2 &&
    e[0].dirName === 'conversations' &&
    e[0].recoverability === 'AUTHORED' &&
    e[1].recoverability === 'DERIVED'
  );
});

// --- hardcode detection ---
ok('findHardcodedCipherCalls flags a bare StoreRecoverability literal', () => {
  const hits = findHardcodedCipherCalls('var c = storeCipher(StoreRecoverability.AUTHORED);');
  return hits.length === 1 && hits[0] === 'AUTHORED';
});
ok('findHardcodedCipherCalls allows the catalog form', () => {
  return (
    findHardcodedCipherCalls('storeCipher(StoreCatalog.MEMORIES.recoverability())').length === 0
  );
});
ok('findHardcodedCipherCalls ignores the method definition (== comparison)', () => {
  return (
    findHardcodedCipherCalls('return r == StoreRecoverability.AUTHORED ? a : b;').length === 0
  );
});

// --- parity ---
const CAT = [
  { dirName: 'conversations', recoverability: 'AUTHORED' },
  { dirName: 'index', recoverability: 'DERIVED' },
];
ok('checkParity passes when catalog and register match', () => {
  return checkParity(CAT, [...CAT]).length === 0;
});
ok('checkParity fails when the register is missing a catalog store', () => {
  return checkParity(CAT, [{ dirName: 'conversations', recoverability: 'AUTHORED' }]).length === 1;
});
ok('checkParity fails on a class drift', () => {
  const reg = [
    { dirName: 'conversations', recoverability: 'DERIVED' }, // drifted
    { dirName: 'index', recoverability: 'DERIVED' },
  ];
  return checkParity(CAT, reg).some((f) => f.includes('drifted'));
});
ok('checkParity fails on a register row with no catalog entry', () => {
  const reg = [...CAT, { dirName: 'ghost', recoverability: 'AUTHORED' }];
  return checkParity(CAT, reg).some((f) => f.includes('ghost'));
});

if (failures.length > 0) {
  console.error(`✗ check-store-recoverability.test FAILED (${failures.length}):`);
  for (const f of failures) console.error('  - ' + f);
  process.exit(1);
}
console.log(`✓ check-store-recoverability.test OK — ${passed} assertions passed.`);
