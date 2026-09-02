import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkDurableStoreRegister,
  checkParity,
  extractCatalogEntries,
  findHardcodedCipherCalls,
} from './check-store-recoverability.mjs';
import { isPersistenceWriteSource } from '../governance/lib/persistence-write-scan.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

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
    ownedPaths: ['conversations/**'],
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
    upgradeHandling: 'READ_IN_PLACE',
    pathVerification: 'LITERAL',
    encryption: 'SEALED_BY_STORE_CIPHER',
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
    discoveredWriteSites: options.discovered ?? ['Store.java'],
    nonDurableWriteSites: options.nonDurable ?? [],
    pendingDurableClassification: options.pending ?? null,
    pathExists: options.pathExists ?? (() => true),
    readSource: options.readSource ?? (() => 'var p = base.resolve("conversations");'),
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

// Tempdoc 879: the register named four paths no code writes and stayed green, because nothing
// compared a declared path to its writer. These pin the comparison and its ONE stated exclusion.
test('a declared path whose segments no source writes is a failure', () => {
  const result = check([readyRow({ ownedPaths: ['settings/ui-settings.json'] })]);
  assert.ok(result.some((failure) => failure.includes('`settings`')));
  assert.ok(result.some((failure) => failure.includes('`ui-settings.json`')));
});

test('glob segments are skipped, literal segments are not', () => {
  assert.deepEqual(check([readyRow({ ownedPaths: ['conversations/*/meta.json'] })]).length, 1);
  assert.deepEqual(check([readyRow({ ownedPaths: ['conversations/**'] })]), []);
});

test('a literal component match is exact, never a substring', () => {
  const result = check([readyRow({ ownedPaths: ['ui/x.json'], catalogDirName: undefined })], {
    catalog: [],
    readSource: () => 'var p = base.resolve("build-gui");',
  });
  assert.ok(result.some((failure) => failure.includes('`ui`')));
});

test('COMPOSED is a stated exclusion that must say why', () => {
  const bare = check([readyRow({ ownedPaths: ['nowhere/at.all'], pathVerification: 'COMPOSED' })]);
  assert.ok(bare.some((failure) => failure.includes('pathVerificationNote')));
  assert.deepEqual(
    check([
      readyRow({
        ownedPaths: ['nowhere/at.all'],
        pathVerification: 'COMPOSED',
        pathVerificationNote: 'SQLite creates this sidecar itself.',
      }),
    ]),
    [],
  );
});

// Tempdoc 879 review: unioning every source's literals made LITERAL satisfiable by ADDING a file
// that happens to contain the missing word, so a row could go green with nothing writing its path.
test('LITERAL needs ONE source to hold the whole path, never a union of two', () => {
  const row = readyRow({
    id: 'plugin-allowlist',
    catalogDirName: undefined,
    ownedPaths: ['ui/plugin-allowlist.json'],
    encryption: 'UNSEALED_GAP',
    encryptionNote: 'Plaintext hashes of plugin artifacts an operator trusted.',
    implementationSources: ['Store.java', 'Neighbour.java'],
  });
  const split = check([row], {
    catalog: [],
    readSource: (absolutePath) =>
      absolutePath.replace(/\\/g, '/').endsWith('Neighbour.java')
        ? 'var settings = base.resolve("ui").resolve("settings.json");'
        : 'this.file = dir.resolve("plugin-allowlist.json");',
  });
  assert.equal(split.length, 1);
  assert.ok(split[0].includes('no SINGLE implementation source'));
  assert.ok(split[0].includes('`ui`'));
  // ... and the same row is green the moment ONE file writes the whole path.
  assert.deepEqual(
    check([row], {
      catalog: [],
      readSource: () => 'var p = base.resolve("ui").resolve("plugin-allowlist.json");',
    }),
    [],
  );
});

test('pathVerification and encryption are required on every row', () => {
  const result = check([readyRow({ pathVerification: undefined, encryption: undefined })]);
  assert.ok(result.some((failure) => failure.includes('pathVerification is required')));
  assert.ok(result.some((failure) => failure.includes('encryption is required')));
});

test('an AUTHORED StoreCatalog store must declare itself sealed', () => {
  const result = check([readyRow({ encryption: 'NOT_APPLICABLE' })]);
  assert.ok(result.some((failure) => failure.includes('SEALED_BY_STORE_CIPHER')));
});

test('a row living entirely inside an AUTHORED catalog directory must declare itself sealed', () => {
  const result = check([
    readyRow({
      id: 'run-events',
      catalogDirName: undefined,
      ownedPaths: ['conversations/*/events.ndjson'],
      encryption: 'UNSEALED_GAP',
      encryptionNote: 'plaintext events',
    }),
  ]);
  assert.ok(result.some((failure) => failure.includes('SEALED_BY_STORE_CIPHER')));
});

test('the open-gap disposition must name what the plaintext file contains', () => {
  const row = readyRow({ id: 'ui-settings', catalogDirName: undefined, encryption: 'UNSEALED_GAP' });
  assert.ok(
    check([row], { catalog: [] }).some((failure) => failure.includes('encryptionNote')),
  );
  assert.deepEqual(
    check([{ ...row, encryptionNote: 'Plaintext UI preferences incl. the local model path.' }], {
      catalog: [],
    }),
    [],
  );
});

// Tempdoc 879 review: NOT_APPLICABLE was the ONE disposition nobody had to justify, so it was
// doing double duty for "nothing sensitive here" and "the user's own document text, unsealed".
test('the nothing-to-seal disposition must say what the file holds', () => {
  const row = readyRow({
    id: 'process-locks',
    catalogDirName: undefined,
    encryption: 'NOT_APPLICABLE',
  });
  assert.ok(
    check([row], { catalog: [] }).some((failure) =>
      failure.includes('encryption NOT_APPLICABLE requires an encryptionNote'),
    ),
  );
  assert.deepEqual(
    check([{ ...row, encryptionNote: 'PID and lock timestamp only; no user content.' }], {
      catalog: [],
    }),
    [],
  );
});

test('the derived-store disposition must name the user content it leaves in the clear', () => {
  const row = readyRow({
    id: 'index-generations',
    catalogDirName: undefined,
    encryption: 'UNSEALED_DERIVED_OS_DISK_ENCRYPTION',
  });
  assert.ok(
    check([row], { catalog: [] }).some((failure) =>
      failure.includes('encryption UNSEALED_DERIVED_OS_DISK_ENCRYPTION requires an encryptionNote'),
    ),
  );
  assert.deepEqual(
    check(
      [{ ...row, encryptionNote: 'Lucene stored fields hold the user document text verbatim.' }],
      { catalog: [] },
    ),
    [],
  );
});

test('all declared source and evidence paths must resolve', () => {
  const result = check([readyRow()], {
    pathExists: (path) => !path.endsWith('v0.json'),
  });
  assert.ok(result.some((failure) => failure.includes('v0.json')));
});

test('write-site scanner finds Java writers without Store names or Path constructors', () => {
  assert.equal(
    isPersistenceWriteSource(
      'modules/app-x/src/main/java/io/justsearch/x/DialogJournal.java',
      'class DialogJournal { void save() { Files.writeString(dataDir.resolve("x"), "x"); } }',
    ),
    true,
  );
});

test('write-site scanner finds Rust app-data writers', () => {
  assert.equal(
    isPersistenceWriteSource(
      'modules/shell/src-tauri/src/state.rs',
      'fn save(app_data_dir: &Path) { std::fs::write(app_data_dir.join("x"), b"x"); }',
    ),
    true,
  );
});

test('classified non-durable write sites satisfy coverage explicitly', () => {
  assert.deepEqual(
    check([readyRow()], {
      discovered: ['Store.java', 'Diagnostics.java'],
      nonDurable: [{ path: 'Diagnostics.java', reason: 'writes one JVM temp probe file it deletes.' }],
    }),
    [],
  );
});

test('an unknown storage root fails, so a root cannot be invented or misspelled', () => {
  const result = check([readyRow({ root: 'APP_DATA' })]);
  assert.ok(result.some((f) => f.includes('unknown root')), result.join(' | '));
});

for (const root of [
  'DATA_DIR',
  'AI_HOME',
  'PROGRAM_DATA_OR_DATA_DIR',
  'USER_INDEXED_ROOTS',
]) {
  test(`the enumerated root ${root} is accepted`, () => {
    const result = check([readyRow({ root })]);
    assert.ok(!result.some((f) => f.includes('unknown root')), result.join(' | '));
  });
}

test('the root enum does NOT catch a wrong-but-enumerated root — stated, not implied', () => {
  // ai-install-attempt-memory shipped as DATA_DIR while writing under AI_HOME (review of PR #604).
  // Both are members, so this check is silent on it. Pinned so nobody reads the enum as stronger
  // than it is and stops looking for the real answer.
  const result = check([readyRow({ root: 'AI_HOME' })]);
  assert.ok(!result.some((f) => f.includes('unknown root')));
});

test('a row without currentVersion fails — updater.rs cannot deserialise the register without it', () => {
  // The regression that broke #604's CI on two jobs. Six HARDENING_REQUIRED rows omitted
  // currentVersion; the JS gate only demanded it for READY + READ_IN_PLACE rows, but updater.rs
  // takes `current_version: u32` with NO serde default, so one such row makes the whole embedded
  // register unparseable and validate_store_compatibility rejects EVERY release descriptor.
  const row = readyRow();
  delete row.currentVersion;
  const result = check([row]);
  assert.ok(result.some((f) => f.includes('currentVersion is required on every row')), result.join(' | '));
});

test('a row without readableLegacyVersions fails', () => {
  const row = readyRow();
  delete row.readableLegacyVersions;
  const result = check([row]);
  assert.ok(result.some((f) => f.includes('readableLegacyVersions is required on every row')), result.join(' | '));
});

test('currentVersion 0 is valid — the convention for bytes with no version envelope', () => {
  const result = check([readyRow({ currentVersion: 0, status: 'HARDENING_REQUIRED', upgradeHandling: 'RESET' })]);
  assert.ok(!result.some((f) => f.includes('currentVersion')), result.join(' | '));
});

test('a non-integer currentVersion fails rather than being coerced', () => {
  const result = check([readyRow({ currentVersion: '1' })]);
  assert.ok(result.some((f) => f.includes('currentVersion is required on every row')), result.join(' | '));
});

test('the REAL register satisfies what updater.rs deserialises', () => {
  // Cargo cannot build in this worktree (the Tauri build script needs staged headless resources),
  // so the Rust contract is asserted here instead — and the field list is PARSED OUT of updater.rs
  // rather than restated, so it cannot drift from the struct it mirrors. serde has no defaults on
  // LocalDurableStore, so one row missing one field makes include_str! unparseable and
  // validate_store_compatibility rejects every release descriptor.
  const rust = fs.readFileSync(
    path.resolve(REPO_ROOT, 'modules/shell/src-tauri/src/updater.rs'),
    'utf8',
  );
  const block = /struct LocalDurableStore \{([\s\S]*?)\n\}/.exec(rust);
  assert.ok(block, 'LocalDurableStore struct not found — updater.rs moved; re-point this test');
  const fields = [...block[1].matchAll(/^\s*(\w+):\s*([\w<>]+),/gm)].map(([, name, type]) => ({
    // #[serde(rename_all = "camelCase")] on the struct.
    json: name.replace(/_([a-z])/g, (_m, c) => c.toUpperCase()),
    numeric: /^u\d+$/.test(type),
  }));
  assert.ok(fields.length >= 5, `expected the struct to declare fields, parsed ${fields.length}`);

  const real = JSON.parse(
    fs.readFileSync(path.resolve(REPO_ROOT, 'governance/store-recoverability.v1.json'), 'utf8'),
  );
  for (const row of real.durableStores) {
    for (const field of fields) {
      const value = row[field.json];
      const ok = field.numeric
        ? Number.isInteger(value) && value >= 0
        : typeof value === 'string' && value.length > 0;
      assert.ok(ok, `${row.id}: ${field.json} is required by updater.rs LocalDurableStore`);
    }
    assert.ok(Array.isArray(row.readableLegacyVersions), `${row.id}: readableLegacyVersions`);
  }
});

test('a non-durable classification without a reason fails', () => {
  // The entry is a CLAIM that nothing here survives. Every other row in this register justifies its
  // claims; a bare path grows the allowlist by assertion, which is how the register acquires
  // entries nobody can re-check.
  const result = check([readyRow()], {
    discovered: ['Store.java', 'Diagnostics.java'],
    nonDurable: [{ path: 'Diagnostics.java' }],
  });
  assert.ok(result.some((f) => f.includes('reason is required')), result.join(' | '));
});

test('a non-durable entry with a blank reason fails the same way', () => {
  const result = check([readyRow()], {
    discovered: ['Store.java', 'Diagnostics.java'],
    nonDurable: [{ path: 'Diagnostics.java', reason: '   ' }],
  });
  assert.ok(result.some((f) => f.includes('reason is required')), result.join(' | '));
});

// The gate only inspects sites the scanner discovers (check-store-recoverability.mjs:194), so an
// idiom the detector misses is an unregistered writer nothing can notice. These pin both edges.
const JAVA_SRC = 'modules/x/src/main/java/io/justsearch/X.java';

for (const idiom of [
  'Files.newBufferedWriter(p, UTF_8)',
  'Files.createFile(p)',
  'new FileOutputStream(f)',
  'new FileWriter(f)',
  'new PrintWriter(f)',
  'new ObjectOutputStream(out)',
]) {
  test(`detects durable write idiom: ${idiom}`, () => {
    assert.equal(isPersistenceWriteSource(JAVA_SRC, `var p = dataDir.resolve("s"); ${idiom};`), true);
  });
}

test('read-only mode-dependent APIs are not write sites', () => {
  // Both appear in production against durable paths and are strictly reads; flagging them would
  // force read-only files into nonDurableWriteSites, which registers false authority.
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'FileChannel.open(dbPath, StandardOpenOption.READ);'),
    false,
  );
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'new RandomAccessFile(dataDir.resolve("t").toFile(), "r");'),
    false,
  );
});

// -------------------- discovery is by CALL, not by vocabulary --------------------
//
// The retired rule ANDed the mutation idiom with a "durable anchor" word (`dataDir`, `telemetry`,
// `StoreCatalog`, …) matched against the raw file TEXT — comments included. That produced both
// error directions at once: `ExtractionSandboxFactory` was discovered because a javadoc sentence
// happened to contain an anchor word, while `ExtractionSandboxCommand`, which writes a real
// argfile, was invisible because its prose did not. Discovery decided by reading English.
//
// The two checks below are the exact shapes of that defect, in both directions.

test('DISCOVERED BY CALL: a write with no anchor vocabulary anywhere is a write site', () => {
  // The ExtractionSandboxCommand shape: a real argfile write, and not one anchor word in the file.
  assert.equal(
    isPersistenceWriteSource(
      'modules/worker-services/src/main/java/io/justsearch/x/SandboxCommand.java',
      'class SandboxCommand { void argfile(Path file, StringBuilder body) {\n' +
        '  Files.writeString(file, body.toString(), StandardCharsets.UTF_8);\n} }',
    ),
    true,
  );
});

test('NOT DISCOVERED BY PROSE: a javadoc naming a write and an anchor is not a write site', () => {
  // The ExtractionSandboxFactory shape, inverted: everything the old rule keyed on is present, and
  // all of it is prose. A file that only TALKS about writing must not be classified as writing.
  assert.equal(
    isPersistenceWriteSource(
      JAVA_SRC,
      '/**\n' +
        ' * Reads the snapshot. The writer uses Files.writeString under dataDir; see StoreCatalog\n' +
        ' * and the telemetry runtimeDir notes. This class never writes.\n' +
        ' */\n' +
        'class Reader { String read(Path p) throws IOException { return Files.readString(p); } }',
    ),
    false,
  );
});

test('NOT DISCOVERED BY PROSE: a commented-out write does not count', () => {
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'class X { void f() { // Files.write(dataDir, b);\n } }'),
    false,
  );
});

test('a write to a caller-supplied destination is STILL a write site (fail-closed)', () => {
  // The retired anchor let this through on the theory that user-chosen export targets are governed
  // elsewhere. Deciding that by vocabulary made the exemption invisible; it is now one explicit
  // nonDurableWriteSites line with a reason, which a reader can disagree with.
  assert.equal(isPersistenceWriteSource(JAVA_SRC, 'new FileWriter(userChosenExportTarget);'), true);
});

test('mode-bearing write forms ARE discovered when the mode says write', () => {
  // These were dropped entirely because matching them by name flagged their read-only twins. The
  // mode literal distinguishes them, so the argfile/marker writes that used them stop being a gap.
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'var raf = new RandomAccessFile(signalPath.toFile(), "rw");'),
    true,
  );
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'FileChannel.open(lib, StandardOpenOption.WRITE);'),
    true,
  );
});

test('a string literal is not a comment: `"// Files.write(x)"` still reads as prose-free code', () => {
  // The comment stripper must not mistake `//` inside a literal for a comment start, or it would
  // swallow the rest of a line that may contain the real call.
  assert.equal(
    isPersistenceWriteSource(JAVA_SRC, 'class X { void f() { log("// nope"); Files.write(p, b); } }'),
    true,
  );
});

if (failures.length > 0) {
  console.error(`check-store-recoverability.test FAILED (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}

console.log(`check-store-recoverability.test OK - ${passed} assertions passed.`);
