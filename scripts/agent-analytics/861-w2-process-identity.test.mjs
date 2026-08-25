/**
 * Tempdoc 861 W2 [A2] — process identity: the three ADVERSE tests, plus the happy path.
 *
 * Phase 2's acceptance is not "identity verification works". It is that identity verification
 * REFUSES in every state where the evidence does not support a kill (`green-masked-destructive`:
 * a happy-path-only suite does not close this phase). The three required branches:
 *
 *   (i)   recycled pid          — pid alive, creation time differs  -> MISMATCH (not MATCH)
 *   (ii)  unreadable creation   — field absent or unparseable       -> REFUSE   (not MISMATCH, not MATCH)
 *   (iii) table unavailable     — enumeration failed, returns []    -> REFUSE   (an empty table is
 *                                                                    NO evidence, not exculpatory
 *                                                                    evidence — `getProcessTable`
 *                                                                    fails silently to `[]` by
 *                                                                    design, remove-worktree.cjs:125-131)
 *
 * Each of the three was proven DISCRIMINATING by mutation: with the corresponding guard removed
 * from `process-identity.cjs`, the test fails; restored, it passes. The mutations are recorded in
 * the PR body rather than left as an unverified claim here.
 *
 * These tests are sited under `scripts/agent-analytics/` deliberately: that directory is
 * auto-discovered by `run-all-tests.mjs:31-40` and run as a CI job step, while `scripts/dev/*.test.mjs`
 * runs in CI nowhere (861 §7.6). The MODULE lives in `scripts/dev/lib/`; test location and module
 * location need not match, and for the safety-critical branch of a kill path, CI coverage wins.
 *
 * Sited fixtures only — no process is signalled, and the one live probe is Windows-guarded and
 * read-only.
 *
 * Run with: `node scripts/agent-analytics/861-w2-process-identity.test.mjs`
 */

import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const {
  PROCESS_TABLE_PS_COMMAND,
  IDENTITY,
  normalizeCreationTime,
  readProcessTable,
  coerceProcessTable,
  verifyProcessIdentity,
  isVerifiedMatch,
} = require('../dev/lib/process-identity.cjs');
const { getProcessTable } = require('../dev/remove-worktree.cjs');

let passed = 0;
let skipped = 0;
const failures = [];
function check(label, fn) {
  try {
    fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

/* ── Fixtures ─────────────────────────────────────────────────────────────────────────────── */

// 18-digit FILETIMEs, the real shape measured from `Win32_Process.CreationDate.ToFileTimeUtc()`
// on this host (2026-08-25): e.g. `134320479841300350`.
const T_ORIGINAL = '134320479841300350';
const T_RECYCLED = '134320999999999990';

const RECORD = () => ({
  pid: 4242,
  creationFileTimeUtc: T_ORIGINAL,
  cmdlineFingerprint: 'vite --port 5173',
});

const ROW = (over = {}) => ({
  ProcessId: 4242,
  ParentProcessId: 100,
  Name: 'node.exe',
  CommandLine: 'node C:\\wt\\node_modules\\vite\\bin\\vite.js vite --port 5173',
  CreationFileTimeUtc: T_ORIGINAL,
  ...over,
});

const NOISE = { ProcessId: 100, ParentProcessId: 1, Name: 'cmd.exe', CommandLine: 'cmd.exe /d /s /c npx.cmd vite', CreationFileTimeUtc: T_ORIGINAL };

/* ── Happy path ───────────────────────────────────────────────────────────────────────────── */

check('happy path: pid AND creation time AND fingerprint all agree -> MATCH', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: [NOISE, ROW()] });
  assert.equal(r.verdict, IDENTITY.MATCH);
  assert.deepEqual(r.matched, { pid: true, creationTime: true, fingerprint: true });
  assert.equal(isVerifiedMatch(r), true);
});

/* ── (i) recycled pid ─────────────────────────────────────────────────────────────────────── */

check('(i) recycled pid: same pid, DIFFERENT creation time -> MISMATCH, never MATCH', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: [ROW({ CreationFileTimeUtc: T_RECYCLED })] });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
  assert.equal(isVerifiedMatch(r), false);
  assert.equal(r.matched.creationTime, false);
  // Precision: the pid itself DID match. If this assertion inverted, the test would be passing for
  // the wrong reason (e.g. because the fixture pid was wrong rather than because the time differed).
  assert.equal(r.matched.pid, true);
  assert.match(r.reason, /recycled/);
});

check('(i) the recycled process shares the fingerprint too: the conjunction still refuses it', () => {
  // The realistic recycle: a NEW vite on the same reused pid, whose command line contains the very
  // same substring. Fingerprint alone would say yes; only the creation-time term catches it — which
  // is why 861 §6.2 calls the substring fingerprint safe ONLY inside the conjunction.
  const row = ROW({ CreationFileTimeUtc: T_RECYCLED, CommandLine: 'node vite.js vite --port 5173' });
  const r = verifyProcessIdentity({ record: RECORD(), table: [row] });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
});

check('(i) exact equality, no tolerance window: a 100ns difference is still a MISMATCH', () => {
  // Measured, and the reason the creation time is carried as a decimal STRING: as JSON numbers,
  // Number('134320479841300350') === Number('134320479841300351') is TRUE in Node, so these two
  // instants would collapse and the recycled-pid branch would silently stop discriminating.
  assert.equal(Number(T_ORIGINAL) === Number('134320479841300351'), true);
  const r = verifyProcessIdentity({ record: RECORD(), table: [ROW({ CreationFileTimeUtc: '134320479841300351' })] });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
});

check('a pid absent from a successfully-read table is a read NEGATIVE, not an absent one', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: [NOISE] });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
  assert.equal(r.matched.pid, false);
});

/* ── (ii) unreadable creation time ────────────────────────────────────────────────────────── */

check('(ii) record creation time ABSENT -> REFUSE (not MISMATCH, not MATCH)', () => {
  const record = { ...RECORD(), creationFileTimeUtc: undefined };
  const r = verifyProcessIdentity({ record, table: [ROW()] });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.notEqual(r.verdict, IDENTITY.MISMATCH);
  assert.equal(isVerifiedMatch(r), false);
});

check('(ii) record creation time UNPARSEABLE -> REFUSE', () => {
  for (const bad of ['', '  ', 'not-a-time', '20260825T101112Z', '-1', '0', {}, [], true]) {
    const r = verifyProcessIdentity({ record: { ...RECORD(), creationFileTimeUtc: bad }, table: [ROW()] });
    assert.equal(r.verdict, IDENTITY.REFUSE, `expected REFUSE for ${JSON.stringify(bad)}, got ${r.verdict}`);
  }
});

check('(ii) LIVE row creation time absent/unparseable -> REFUSE (pid reuse cannot be ruled out)', () => {
  for (const bad of [null, undefined, 'garbage', '']) {
    const r = verifyProcessIdentity({ record: RECORD(), table: [ROW({ CreationFileTimeUtc: bad })] });
    assert.equal(r.verdict, IDENTITY.REFUSE, `expected REFUSE for live-row ${JSON.stringify(bad)}, got ${r.verdict}`);
    assert.equal(r.matched.pid, true);
    assert.equal(r.matched.creationTime, null); // not evaluated — not "false"
  }
});

check('(ii) an unsafe-integer NUMBER creation time is unreadable evidence, not a rounded compare', () => {
  // A producer that wrote the FILETIME as a JSON number rather than a string. Comparing the rounded
  // double would satisfy "exact equality" in the source while violating it in fact, so it REFUSES.
  assert.equal(Number.isSafeInteger(Number(T_ORIGINAL)), false);
  assert.equal(normalizeCreationTime(Number(T_ORIGINAL)), null);
  const r = verifyProcessIdentity({ record: { ...RECORD(), creationFileTimeUtc: Number(T_ORIGINAL) }, table: [ROW()] });
  assert.equal(r.verdict, IDENTITY.REFUSE);
});

check('normalizeCreationTime canonicalizes without loss, and refuses non-evidence', () => {
  assert.equal(normalizeCreationTime(T_ORIGINAL), T_ORIGINAL);
  assert.equal(normalizeCreationTime(` ${T_ORIGINAL} `), T_ORIGINAL);
  assert.equal(normalizeCreationTime('0134320479841300350'), T_ORIGINAL); // leading zeros canonicalize
  assert.equal(normalizeCreationTime(null), null);
  assert.equal(normalizeCreationTime(NaN), null);
  assert.equal(normalizeCreationTime(12345), '12345'); // a safe integer is readable evidence
});

/* ── (iii) process table unavailable ──────────────────────────────────────────────────────── */

check('(iii) EMPTY table -> REFUSE: no evidence is not exculpatory evidence', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: [] });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.notEqual(r.verdict, IDENTITY.MISMATCH);
  assert.match(r.reason, /NO evidence/);
});

check('(iii) missing / null / failed-tri-state table -> REFUSE in every form', () => {
  for (const table of [undefined, null, { ok: false, reason: 'powershell exited 1' }, { ok: true, table: [] }, 'nonsense', 42]) {
    const r = verifyProcessIdentity({ record: RECORD(), table });
    assert.equal(r.verdict, IDENTITY.REFUSE, `expected REFUSE for table=${JSON.stringify(table)}, got ${r.verdict}`);
  }
});

check('(iii) a SUCCESSFUL tri-state table is unwrapped and used', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: { ok: true, table: [ROW()] } });
  assert.equal(r.verdict, IDENTITY.MATCH);
});

check('(iii) readProcessTable never degrades to [] — it reports the failure', () => {
  const failing = () => ({ status: 1, stdout: '', stderr: 'boom' });
  const r1 = readProcessTable({ platform: 'win32', exec: failing });
  assert.equal(r1.ok, false);
  const r2 = readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: '[]' }) });
  assert.equal(r2.ok, false, 'a zero-row enumeration on a running host is a failed query');
  const r3 = readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: 'not json' }) });
  assert.equal(r3.ok, false);
  const r4 = readProcessTable({ platform: 'linux' });
  assert.equal(r4.ok, false);
  const r5 = readProcessTable({ platform: 'win32', exec: () => { throw new Error('spawn EPERM'); } });
  assert.equal(r5.ok, false);
  const r6 = readProcessTable({ platform: 'win32', exec: () => ({ status: 0, stdout: JSON.stringify(ROW()) }) });
  assert.equal(r6.ok, true, 'a single-object CIM result is wrapped into a one-row table');
  assert.equal(r6.table.length, 1);
});

check('coerceProcessTable maps every unusable shape onto ok:false', () => {
  assert.equal(coerceProcessTable([]).ok, false);
  assert.equal(coerceProcessTable(null).ok, false);
  assert.equal(coerceProcessTable({ ok: true, table: [] }).ok, false);
  assert.equal(coerceProcessTable([ROW()]).ok, true);
});

/* ── The conjunction, and the shape of the verdict ────────────────────────────────────────── */

check('fingerprint is the third conjunct: pid + creation time alone do NOT license a match', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: [ROW({ CommandLine: 'node some-other-server.js' })] });
  assert.equal(r.verdict, IDENTITY.MISMATCH);
  assert.deepEqual(r.matched, { pid: true, creationTime: true, fingerprint: false });
});

check('an unavailable CommandLine cannot be read as a passing fingerprint -> REFUSE', () => {
  const r = verifyProcessIdentity({ record: RECORD(), table: [ROW({ CommandLine: null })] });
  assert.equal(r.verdict, IDENTITY.REFUSE);
  assert.equal(r.matched.fingerprint, null);
});

check('a record missing pid or fingerprint refuses rather than falling through', () => {
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), pid: 0 }, table: [ROW()] }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), pid: '4242' }, table: [ROW()] }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({ record: { ...RECORD(), cmdlineFingerprint: '  ' }, table: [ROW()] }).verdict, IDENTITY.REFUSE);
  assert.equal(verifyProcessIdentity({}).verdict, IDENTITY.REFUSE);
});

check('REFUSE is a THIRD value, not false — the classifyActivity precedent', () => {
  // `classifyActivity` returns `{ known:false, generalStale:false }` so an absent signal cannot
  // masquerade as a permissive one (ownership-verdict.cjs:83-85). Same discipline here: a caller
  // spelling the check `verdict !== MISMATCH` must not thereby get permission.
  assert.equal(IDENTITY.REFUSE === false, false);
  assert.equal(IDENTITY.REFUSE === IDENTITY.MISMATCH, false);
  const refused = verifyProcessIdentity({ record: RECORD(), table: [] });
  assert.equal(typeof refused.verdict, 'string');
  assert.notEqual(refused.verdict, IDENTITY.MISMATCH);
  assert.equal(isVerifiedMatch(refused), false);
  // Only MATCH is a licence.
  for (const v of [IDENTITY.REFUSE, IDENTITY.MISMATCH]) assert.equal(isVerifiedMatch({ verdict: v }), false);
  assert.equal(isVerifiedMatch({ verdict: IDENTITY.MATCH }), true);
});

/* ── The projection [A2] ──────────────────────────────────────────────────────────────────── */

check('[A2] the CIM projection adds the creation time and keeps every column its consumers use', () => {
  for (const col of ['ProcessId', 'ParentProcessId', 'Name', 'CommandLine']) {
    assert.ok(PROCESS_TABLE_PS_COMMAND.includes(col), `projection lost ${col}, which remove-worktree's holder scan needs`);
  }
  assert.ok(PROCESS_TABLE_PS_COMMAND.includes('CreationDate'), 'projection must not project CreationDate away (861 [A2])');
  assert.ok(PROCESS_TABLE_PS_COMMAND.includes('ToFileTimeUtc'), 'creation time must be normalized in PowerShell, not compared as a locale-dependent CIM datetime string');
});

/* ── Windows-guarded live probe (read-only) ───────────────────────────────────────────────── */

if (process.platform === 'win32') {
  check('[win32] readProcessTable really enumerates, and this process verifies against its own row', () => {
    const result = readProcessTable();
    assert.equal(result.ok, true, `live enumeration failed: ${result.reason}`);
    const self = result.table.find((r) => Number(r.ProcessId) === process.pid);
    assert.ok(self, 'this process is missing from its own process table');
    const t = normalizeCreationTime(self.CreationFileTimeUtc);
    assert.ok(t !== null, `live creation time unreadable: ${JSON.stringify(self.CreationFileTimeUtc)}`);
    assert.ok(t.length >= 17, `expected an 18-digit FILETIME, got ${t}`);
    assert.equal(Number.isSafeInteger(Number(t)), false, 'a live FILETIME must exceed MAX_SAFE_INTEGER — the reason it travels as a string');

    const record = { pid: process.pid, creationFileTimeUtc: t, cmdlineFingerprint: '861-w2-process-identity' };
    assert.equal(verifyProcessIdentity({ record, table: result }).verdict, IDENTITY.MATCH);
    // Same live row, one tick earlier: the recycled-pid branch against real evidence.
    const shifted = (BigInt(t) - 1n).toString();
    assert.equal(verifyProcessIdentity({ record: { ...record, creationFileTimeUtc: shifted }, table: result }).verdict, IDENTITY.MISMATCH);
  });

  check('[win32] remove-worktree getProcessTable now carries the creation time', () => {
    const table = getProcessTable();
    assert.ok(table.length > 0, 'live holder-scan table was empty');
    const self = table.find((r) => Number(r.ProcessId) === process.pid);
    assert.ok(self, 'this process is missing from getProcessTable()');
    assert.ok(normalizeCreationTime(self.CreationFileTimeUtc) !== null, 'getProcessTable still projects the creation time away (861 [A2])');
    assert.equal(typeof self.CommandLine, 'string', 'the holder scan lost CommandLine');
  });
} else {
  skipped += 2;
}

if (failures.length > 0) {
  console.error(`861-w2-process-identity.test: ${failures.length} FAILED, ${passed} passed, ${skipped} skipped`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w2-process-identity.test: all ${passed} checks passed${skipped ? ` (${skipped} skipped: not win32)` : ''}`);
