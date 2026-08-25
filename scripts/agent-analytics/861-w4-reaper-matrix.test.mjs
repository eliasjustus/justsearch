/**
 * Tempdoc 861 W4 — the reaper's authority matrix, cell by cell.
 *
 * 861 §7.1 Phase 4's acceptance is "a table-driven test covering EVERY cell of §6.3's matrix", and
 * §7.2's closure is "an independent reviewer walks the diff with the §6.3 matrix in hand". So the
 * matrix is literally a table here, each row carries the stable `cell` id the module assigns, and
 * the file finishes by asserting the union of observed cells EQUALS `CELLS` — a cell that stops
 * being produced turns this file red rather than quietly vanishing from the coverage claim.
 *
 * Three columns, because the matrix has two and [A4] splits one of them:
 *   `sweep`    — abandonment sweep, capability `execute`.
 *   `teardown` — conflict, capability `execute` (what `remove-worktree` gets: it may act up to the
 *                ceiling, and a holder it may not remove blocks the teardown).
 *   `build`    — conflict, capability `advisory` (the before-a-build occasion). [A4]: every `reap`
 *                in this column is downgraded to `report`, and the column mints NOTHING spendable.
 *
 * This file is sited under `scripts/agent-analytics/` on purpose, per 861 §7.6: `scripts/dev/*.test.mjs`
 * runs in CI NOWHERE, and the two most safety-critical tests in this change are the authority
 * matrix and the identity branch. Auto-discovery by `run-all-tests.mjs` is what makes them run.
 *
 * Nothing here touches a process it did not create. The kill-path tests live in the sibling
 * `861-w4-reaper-kill.test.mjs`, and even there every victim is a `node -e` child this test spawned.
 *
 * Run with: `node scripts/agent-analytics/861-w4-reaper-matrix.test.mjs`
 */

import assert from 'node:assert/strict';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const REAPER_PATH = path.join(HERE, '..', 'dev', 'lib', 'agent-spawn-reaper.cjs');

const {
  REAP_DISPOSITIONS,
  OCCASION_KINDS,
  CAPABILITIES,
  CELLS,
  OWNER_STATES,
  ownerActivityVerdict,
  reapEligible,
  executeReap,
} = require('../dev/lib/agent-spawn-reaper.cjs');
const { OWNERSHIP_MODES } = require('../dev/lib/agent-spawn-record.cjs');
const { DEFAULT_THRESHOLDS } = require('../dev/lib/ownership-verdict.cjs');

let passed = 0;
const failures = [];
async function check(label, fn) {
  try {
    await fn();
    passed += 1;
  } catch (e) {
    failures.push(`${label}: ${e.message}`);
  }
}

/* ── fixtures ─────────────────────────────────────────────────────────────────────────────── */

const NOW = Date.parse('2026-08-25T12:00:00.000Z');
const OWNER = 'owner-session-aaaaaaaa';
const CALLER = 'caller-session-bbbbbbbb';
const PID = 41064;
const CTIME = '134320479841300350';
const FINGERPRINT = 'vite --port 5174';
const iso = (ms) => new Date(ms).toISOString();

/** An agent-spawns record. The default is the ordinary case: another session, lease live. */
const REC = (over = {}) => ({
  schemaVersion: 1,
  recordId: 'ui-shot-5174',
  producer: 'ui-shot',
  pid: PID,
  creationFileTimeUtc: CTIME,
  cmdlineFingerprint: FINGERPRINT,
  ownership: OWNERSHIP_MODES.SESSION_OWNED,
  probe: { kind: 'port', port: 5174 },
  startedAt: iso(NOW - 3_600_000),
  lease: { durationSec: 1800, renewedAt: iso(NOW - 60_000), expiresAt: iso(NOW + 1_740_000) },
  sessionId: OWNER,
  ...over,
});

/** A lease that expired an hour ago, declaring `durationSec` as the owner's hold. */
const LAPSED = (durationSec = 30) => ({
  durationSec,
  renewedAt: iso(NOW - 3_600_000 - durationSec * 1000),
  expiresAt: iso(NOW - 3_600_000),
});

const ROW = (over = {}) => ({
  ProcessId: PID,
  ParentProcessId: 9444,
  Name: 'node.exe',
  CommandLine: `"C:\\Program Files\\nodejs\\node.exe" node_modules\\vite\\bin\\vite.js ${FINGERPRINT}`,
  CreationFileTimeUtc: CTIME,
  ...over,
});

/** A `readProcessTable()`-shaped result, stamped `ageMs` in the past. */
const TABLE = (rows = [ROW()], ageMs = 0) => ({ ok: true, table: rows, readAt: NOW - ageMs });

const ACTIVITY = {
  /** A tool call 10s ago: the owner is present. */
  fresh: { lastActivityAt: iso(NOW - 10_000), lastDevStackTouchAt: iso(NOW - 10_000) },
  /** Silent for an hour: past any threshold a 30s lease can justify. */
  longSilent: { lastActivityAt: iso(NOW - 3_600_000), lastDevStackTouchAt: null },
  /**
   * Silent 100 minutes, under a record declaring a 2-hour hold. `classifyActivity` calls this
   * `generalStale` (past 5 minutes) — and the declared hold says it is intent, not abandonment.
   * This is the exact shape of the 2026-07-14 defect (`dev-runner.cjs:2105-2113`).
   */
  quietButWorking: { lastActivityAt: iso(NOW - 6_000_000), lastDevStackTouchAt: null },
};

const COLUMNS = {
  sweep: { occasion: OCCASION_KINDS.SWEEP, capability: CAPABILITIES.EXECUTE },
  teardown: { occasion: OCCASION_KINDS.CONFLICT, capability: CAPABILITIES.EXECUTE },
  build: { occasion: OCCASION_KINDS.CONFLICT, capability: CAPABILITIES.ADVISORY },
};

const R = REAP_DISPOSITIONS;

/* ── THE MATRIX ───────────────────────────────────────────────────────────────────────────────
 *
 * Every row is one cell of §6.3 as corrected by [A1] and [A5]. `expect` is
 * {sweep, teardown, build}; `blocks` is the `blocksProceed` flag the teardown column must set.
 */
const MATRIX = [
  {
    id: 'registered, same session',
    row: 'Registered, **same session**',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    activity: null,
    cell: CELLS.SAME_SESSION,
    expect: { sweep: R.REAP, teardown: R.REAP, build: R.REPORT },
    blocks: false,
  },
  {
    id: 'registered, other session, lease live',
    row: 'Registered, other session, **lease live**',
    record: REC(),
    activity: ACTIVITY.fresh,
    cell: CELLS.LEASE_LIVE,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
  },
  {
    id: 'registered, other session, lease unreadable (unknown is not lapsed)',
    row: 'Registered, other session, lease unknown — W2 leaseState tri-state',
    record: REC({ lease: { durationSec: 30, renewedAt: iso(NOW), expiresAt: 'not-a-timestamp' } }),
    activity: ACTIVITY.longSilent,
    cell: CELLS.LEASE_UNKNOWN,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
  },
  {
    // [A1], the cell that gated this whole phase.
    id: '[A1] lease lapsed BUT owner activity fresh -> contention, not garbage',
    row: 'Registered, other session, lease lapsed but owner activity fresh',
    record: REC({ lease: LAPSED() }),
    activity: ACTIVITY.fresh,
    cell: CELLS.LAPSED_OWNER_ACTIVE,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.ACTIVE,
  },
  {
    // [A1]'s other arm: classifyActivity's `known:false` is LEAVE, never stale.
    id: '[A1] lease lapsed, owner activity UNKNOWN -> leave (absent signal is not a permissive one)',
    row: 'Registered, other session, lease lapsed, no activity stamp',
    record: REC({ lease: LAPSED() }),
    activity: null,
    cell: CELLS.LAPSED_OWNER_UNKNOWN,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.UNKNOWN,
  },
  {
    // [A1]'s declared-hold arm, ported from dev-runner.cjs:2109-2113.
    id: '[A1] lease lapsed, owner generally stale BUT within its declared hold -> contention',
    row: 'Registered, other session, lease lapsed, within a declared hold',
    record: REC({ lease: LAPSED(7200) }),
    activity: ACTIVITY.quietButWorking,
    cell: CELLS.LAPSED_DECLARED_HOLD,
    expect: { sweep: R.CONTENTION, teardown: R.CONTENTION, build: R.CONTENTION },
    blocks: true,
    ownerState: OWNER_STATES.DECLARED_HOLD,
  },
  {
    id: 'lease lapsed AND owner activity stale -> reap (the ONLY other-session reap)',
    row: 'Registered, other session, **lease lapsed AND owner activity stale**',
    record: REC({ lease: LAPSED() }),
    activity: ACTIVITY.longSilent,
    cell: CELLS.LAPSED_OWNER_STALE,
    expect: { sweep: R.REAP, teardown: R.REAP, build: R.REPORT },
    blocks: false,
    ownerState: OWNER_STATES.STALE,
  },
  {
    // [A5]: a dimension, not a footnote — and "any owner state" means exactly that.
    id: '[A5] ownerless-singleton with a LAPSED lease and a long-silent owner -> never reap',
    row: 'Registered, `ownerless-singleton` (any owner state)',
    record: REC({ ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON, producer: 'otlp-sink', lease: LAPSED(), sessionId: OWNER }),
    activity: ACTIVITY.longSilent,
    cell: CELLS.OWNERLESS_SINGLETON,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    // Never reaped, but still an unreapable HOLDER: a teardown must refuse rather than proceed
    // into the half-deleted worktree shell (861 §6.4 / §2-bis (c)).
    blocks: true,
  },
  {
    id: '[A5] ownerless-singleton owned by the CALLING session -> still never reap',
    row: 'Registered, `ownerless-singleton` — dominates the same-session reap rule',
    record: REC({ ownership: OWNERSHIP_MODES.OWNERLESS_SINGLETON, producer: 'otlp-sink', sessionId: CALLER, lease: LAPSED() }),
    activity: null,
    cell: CELLS.OWNERLESS_SINGLETON,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    blocks: true,
  },
  {
    // [A5]'s never-reap row. The pid belongs to the dev-runner's own active run.
    id: "[A5] the dev-runner's own active run -> never reap, report only",
    row: "Registered, the **dev-runner's own active run**",
    record: REC({ sessionId: CALLER, lease: LAPSED(), producer: 'dev-runner' }),
    activity: ACTIVITY.longSilent,
    devRunnerActive: { runId: '846e7f29-24ba-45ea-ac68-dc398ba99159', pids: [PID] },
    cell: CELLS.DEV_RUNNER_OWN_RUN,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    blocks: true,
  },
  {
    // [A5]'s identity cell, arm (ii): the creation time cannot be read.
    id: '[A5] identity REFUSE — creation time unreadable on the live row',
    row: 'Registered, **identity verification fails or is unavailable**',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: TABLE([ROW({ CreationFileTimeUtc: null })]),
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    // arm (i): the recycled pid.
    id: '[A5] identity MISMATCH — pid recycled (creation times differ)',
    row: 'Registered, identity verification fails — recycled pid',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: TABLE([ROW({ CreationFileTimeUtc: '134320479841399999' })]),
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_MISMATCH,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    // arm (iii): the enumeration itself failed. An empty table is NO evidence, not exculpatory.
    id: '[A5] identity REFUSE — the process table is unavailable',
    row: 'Registered, identity verification is unavailable',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: { ok: false, reason: 'process-table query exited 1' },
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: '[A5] identity REFUSE — the table snapshot has aged past its freshness bound',
    row: 'Registered, identity verification is unavailable (stale snapshot)',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: TABLE([ROW()], 60_000),
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: '[A5] identity REFUSE — a bare unstamped array cannot be age-bounded',
    row: 'Registered, identity verification is unavailable (unstamped table)',
    record: REC({ sessionId: CALLER, lease: LAPSED() }),
    table: [ROW()],
    activity: ACTIVITY.longSilent,
    cell: CELLS.IDENTITY_REFUSE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: 'a record that failed scope validation is no evidence at all',
    row: 'Registered, record unreadable',
    entry: { ok: false, recordId: 'ui-shot-9001', reason: 'unknown agent-spawn schemaVersion 99' },
    activity: null,
    cell: CELLS.RECORD_UNREADABLE,
    expect: { sweep: R.REFUSE, teardown: R.REFUSE, build: R.REFUSE },
    blocks: true,
  },
  {
    id: 'observed only (not registered) -> report with a ready-to-run kill line',
    row: '**Observed** only (not registered)',
    observed: { ProcessId: 55501, Name: 'node.exe', CommandLine: 'node vite.js --port 5199' },
    cell: CELLS.OBSERVED_ONLY,
    expect: { sweep: R.REPORT, teardown: R.REPORT, build: R.REPORT },
    blocks: false,
  },
];

/** Every cell the matrix produced, for the coverage assertion at the end. */
const seenCells = new Set();

function runCell(entryRow, columnName) {
  const col = COLUMNS[columnName];
  const records = entryRow.observed
    ? []
    : [entryRow.entry ? entryRow.entry : { ok: true, recordId: entryRow.record.recordId, record: entryRow.record }];
  return reapEligible({
    records,
    observed: entryRow.observed ? [entryRow.observed] : [],
    processTable: entryRow.table === undefined ? TABLE() : entryRow.table,
    occasion: col.occasion,
    capability: col.capability,
    callerSessionId: CALLER,
    now: NOW,
    thresholds: DEFAULT_THRESHOLDS,
    devRunnerActive: entryRow.devRunnerActive ?? null,
    activityFor: () => entryRow.activity ?? null,
    env: {},
  });
}

for (const m of MATRIX) {
  for (const columnName of Object.keys(COLUMNS)) {
    await check(`MATRIX [${columnName}] ${m.id}`, () => {
      const out = runCell(m, columnName);
      assert.equal(out.all.length, 1, 'exactly one entry per matrix cell');
      const e = out.all[0];
      seenCells.add(e.cell);
      assert.equal(e.cell, m.cell, `cell id (row: ${m.row})`);
      assert.equal(e.disposition, m.expect[columnName], `disposition (row: ${m.row}) — reason: ${e.reason}`);
      assert.ok(out[m.expect[columnName]].includes(e), 'the entry must land in the bucket its disposition names');
      if (m.ownerState) assert.equal(e.owner?.state, m.ownerState, 'owner activity state');
      // blocksProceed is a teardown-column-only signal.
      assert.equal(e.blocksProceed, columnName === 'teardown' ? m.blocks : false, 'blocksProceed');
      assert.equal(out.blocksProceed, columnName === 'teardown' ? m.blocks : false, 'aggregate blocksProceed');
    });
  }
}

/* ── the coverage assertion: every cell, or this file goes red ────────────────────────────── */

await check('COVERAGE: the matrix exercised every declared cell of the corrected §6.3 matrix', () => {
  const declared = new Set(Object.values(CELLS));
  const missing = [...declared].filter((c) => !seenCells.has(c));
  const extra = [...seenCells].filter((c) => !declared.has(c));
  assert.deepEqual(missing, [], `cells declared but never exercised: ${missing.join(', ')}`);
  assert.deepEqual(extra, [], `cells produced but not declared: ${extra.join(', ')}`);
  assert.equal(seenCells.size, declared.size);
});

/* ── [A4]: the advisory column mints nothing spendable ────────────────────────────────────── */

await check('[A4] the advisory (before-a-build) column produces ZERO reap entries across the whole matrix', () => {
  let reaps = 0;
  let downgraded = 0;
  for (const m of MATRIX) {
    const out = runCell(m, 'build');
    reaps += out.reap.length;
    downgraded += out.all.filter((e) => e.downgraded).length;
  }
  assert.equal(reaps, 0, 'a PreToolUse-tier occasion must never hold a reap entry');
  assert.ok(downgraded >= 2, `at least the two reap rows must be visibly downgraded, saw ${downgraded}`);
});

await check('[A4] a downgraded entry still names its ceiling, so an advisory surface can say "this WOULD be reapable"', () => {
  const out = runCell(MATRIX[0], 'build');
  const e = out.all[0];
  assert.equal(e.disposition, R.REPORT);
  assert.equal(e.ceiling, R.REAP);
  assert.equal(e.downgraded, true);
});

await check('[A4] executeReap refuses a downgraded entry — the advisory occasion holds nothing spendable', async () => {
  const out = runCell(MATRIX[0], 'build');
  let execCalls = 0;
  const res = await executeReap(out.all[0], {
    dir: null,
    readTable: () => TABLE(),
    exec: () => { execCalls += 1; return { status: 0 }; },
    now: () => NOW,
  });
  assert.equal(res.refused, true);
  assert.equal(res.killed, false);
  assert.equal(execCalls, 0, 'no kill may be attempted for a non-reap entry');
  assert.match(res.reason, /advisory-downgraded/);
});

await check('[A4] the DEFAULT capability is advisory — an occasion that forgot to declare itself gets nothing spendable', () => {
  const rec = REC({ sessionId: CALLER, lease: LAPSED() });
  const out = reapEligible({
    records: [{ ok: true, recordId: rec.recordId, record: rec }],
    processTable: TABLE(),
    callerSessionId: CALLER,
    now: NOW,
    activityFor: () => null,
    env: {},
  });
  assert.equal(out.reap.length, 0, 'killing must require naming CAPABILITIES.EXECUTE out loud');
  assert.equal(out.report[0].ceiling, R.REAP);
});

/* ── §6.4: a teardown refuses while an UNREAPABLE holder remains ──────────────────────────── */

await check('§6.4 blocksProceed: a never-reap holder still blocks a teardown, and a reapable one does not', () => {
  const singleton = MATRIX.find((m) => m.id.startsWith('[A5] ownerless-singleton with a LAPSED'));
  const reapable = MATRIX.find((m) => m.id.startsWith('registered, same session'));
  assert.equal(runCell(singleton, 'teardown').blocksProceed, true, 'an unreapable holder blocks');
  assert.equal(runCell(reapable, 'teardown').blocksProceed, false, 'a holder the teardown can clear does not');
  assert.equal(runCell(singleton, 'sweep').blocksProceed, false, 'a sweep has nothing to proceed with');
  assert.equal(runCell(singleton, 'build').blocksProceed, false, '[A4]: an advisory occasion never blocks');
});

await check('§6.4 blocksProceed: the observed tier never blocks — that judgement belongs to the caller', () => {
  assert.equal(runCell(MATRIX.at(-1), 'teardown').blocksProceed, false);
});

/* ── the observed tier carries a ready-to-run kill line ───────────────────────────────────── */

await check('the observed tier is reported with a ready-to-run kill line, never killed', () => {
  const out = runCell(MATRIX.at(-1), 'sweep');
  assert.equal(out.reap.length, 0);
  assert.equal(out.report[0].killLine, 'taskkill /PID 55501 /F');
});

/* ── the [A1] join, unit-level ────────────────────────────────────────────────────────────── */

await check('[A1] ownerActivityVerdict: known:false is UNKNOWN, never STALE', () => {
  const v = ownerActivityVerdict(null, REC({ lease: LAPSED() }), NOW, DEFAULT_THRESHOLDS, {});
  assert.equal(v.state, OWNER_STATES.UNKNOWN);
});

await check('[A1] ownerActivityVerdict: the declared hold widens the threshold exactly as dev-runner.cjs:2110-2113 does', () => {
  const grace = 5 * 60_000;
  const short = ownerActivityVerdict(ACTIVITY.longSilent, REC({ lease: LAPSED(30) }), NOW, DEFAULT_THRESHOLDS, {});
  assert.equal(short.thresholdMs, DEFAULT_THRESHOLDS.abandonedAfterMs + grace, 'a 30s lease cannot beat the default');
  const long = ownerActivityVerdict(ACTIVITY.longSilent, REC({ lease: LAPSED(7200) }), NOW, DEFAULT_THRESHOLDS, {});
  assert.equal(long.thresholdMs, 7200 * 1000 + grace, 'a declared 2h hold IS the threshold');
  assert.equal(long.state, OWNER_STATES.DECLARED_HOLD, 'one hour of silence is inside a two-hour declared hold');
  assert.equal(short.state, OWNER_STATES.STALE, 'one hour of silence is far outside a 30s hold plus grace');
});

/* ── MUTATION PROOF: the [A1] activity join is load-bearing ───────────────────────────────── */

/**
 * The join is the amendment. A test that merely asserts "lapsed + fresh owner -> contention" is
 * satisfied by any implementation that happens to return contention, including one that never
 * consults activity at all — so this compiles a MUTANT of the module with the join replaced by a
 * constant `stale`, which is exactly rev 1's "lapsed -> reap", and asserts the reap-while-working
 * cell flips. If the join is ever dropped, this goes red by name.
 */
async function loadMutant(replace, replaceWith) {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  assert.ok(src.includes(replace), `mutation target not found in the reaper source: ${replace}`);
  const libDir = path.dirname(REAPER_PATH).replace(/\\/g, '/');
  const mutated = src
    .replace(replace, replaceWith)
    .replace(/require\('\.\//g, `require('${libDir}/`);
  const file = path.join(await fsp.mkdtemp(path.join(os.tmpdir(), '861-w4-mutant-')), 'mutant.cjs');
  await fsp.writeFile(file, mutated, 'utf8');
  return { mod: require(file), dir: path.dirname(file) };
}

const mutantDirs = [];

await check('MUTATION [A1]: dropping the activity join turns the reap-while-working cell into a reap', async () => {
  const working = MATRIX.find((m) => m.id.startsWith('[A1] lease lapsed BUT owner activity fresh'));
  assert.ok(working, 'the reap-while-working row must exist');

  // The real module: contention.
  const real = runCell(working, 'sweep');
  assert.equal(real.all[0].disposition, R.CONTENTION);
  assert.equal(real.all[0].cell, CELLS.LAPSED_OWNER_ACTIVE);

  // The mutant: the join is replaced by a constant "stale", i.e. rev 1's lapsed-alone rule.
  const { mod, dir } = await loadMutant(
    'ownerActivityVerdict(activity, record, now, thresholds, env)',
    "{ state: 'stale', reason: 'MUTANT: activity join dropped', abandonedMs: null, thresholdMs: 0 }",
  );
  mutantDirs.push(dir);
  const mutated = mod.reapEligible({
    records: [{ ok: true, recordId: working.record.recordId, record: working.record }],
    processTable: TABLE(),
    occasion: OCCASION_KINDS.SWEEP,
    capability: CAPABILITIES.EXECUTE,
    callerSessionId: CALLER,
    now: NOW,
    activityFor: () => working.activity,
    env: {},
  });
  assert.equal(
    mutated.all[0].disposition,
    R.REAP,
    'the mutant must reap — otherwise this test proves nothing about the join being load-bearing',
  );
  assert.equal(mutated.all[0].cell, CELLS.LAPSED_OWNER_STALE);
  assert.notEqual(real.all[0].disposition, mutated.all[0].disposition, 'real and mutant must disagree');
});

await check('MUTATION [A1]: the same mutant also breaks the declared-hold cell', async () => {
  const hold = MATRIX.find((m) => m.id.includes('within its declared hold'));
  const { mod, dir } = await loadMutant(
    'ownerActivityVerdict(activity, record, now, thresholds, env)',
    "{ state: 'stale', reason: 'MUTANT: activity join dropped', abandonedMs: null, thresholdMs: 0 }",
  );
  mutantDirs.push(dir);
  const mutated = mod.reapEligible({
    records: [{ ok: true, recordId: hold.record.recordId, record: hold.record }],
    processTable: TABLE(),
    occasion: OCCASION_KINDS.SWEEP,
    capability: CAPABILITIES.EXECUTE,
    callerSessionId: CALLER,
    now: NOW,
    activityFor: () => hold.activity,
    env: {},
  });
  assert.equal(runCell(hold, 'sweep').all[0].disposition, R.CONTENTION);
  assert.equal(mutated.all[0].disposition, R.REAP);
});

/* ── scope: the reaper never reads the `foreign/` register ────────────────────────────────── */

await check('SCOPE: the reaper requires exactly the agent-spawns-scope modules, plus one generic helper', async () => {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  const specs = [...src.matchAll(/require\('([^']+)'\)/g)].map((m) => m[1]).sort();
  assert.deepEqual(specs, [
    './agent-spawn-record.cjs',
    './ownership-verdict.cjs',
    './process-identity.cjs',
    './process-record.cjs',
    'node:child_process',
    'node:fs/promises',
    'node:path',
  ], 'an unexpected require is a scope widening');
});

await check('SCOPE: the ONLY symbol taken from the shared-grammar module is the generic atomic writer', async () => {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  const m = src.match(/const \{([^}]*)\} = require\('\.\/process-record\.cjs'\)/);
  assert.ok(m, 'the process-record.cjs import must be a destructuring one, so this test can read it');
  const names = m[1].split(',').map((s) => s.trim()).filter(Boolean).sort();
  assert.deepEqual(names, ['writeRecordAtomic'], 'importing anything else from that file reaches into the foreign scope');
});

await check('SCOPE: no foreign-scope identifier appears anywhere in the reaper', async () => {
  const src = await fsp.readFile(REAPER_PATH, 'utf8');
  const forbidden = /\bFOREIGN_[A-Z_]+\b|\breadForeignRegister\b|\bresolveForeignRegisterDir\b|\bvalidateForeignRecord\b|\bprobeForeignRuns\b|\bFOREIGN_REGISTER_DIRNAME\b/;
  assert.equal(forbidden.test(src), false, 'the reaper must not name a foreign-scope symbol');
});

await check('SCOPE: reapEligible is synchronous — a function that read a register directory could not be', () => {
  assert.equal(reapEligible.constructor.name, 'Function', 'reapEligible must not be async: it is a pure projection');
  assert.equal(executeReap.constructor.name, 'AsyncFunction', 'executeReap is the effectful arm');
});

/* ── cleanup + report ─────────────────────────────────────────────────────────────────────── */

for (const dir of mutantDirs) await fsp.rm(dir, { recursive: true, force: true });

if (failures.length > 0) {
  console.error(`861-w4-reaper-matrix.test: ${failures.length} FAILED, ${passed} passed`);
  for (const f of failures) console.error(`  \u2717 ${f}`);
  process.exit(1);
}
console.log(`861-w4-reaper-matrix.test: all ${passed} checks passed (${seenCells.size} matrix cells covered)`);
