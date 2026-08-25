#!/usr/bin/env node
/**
 * Tempdoc 861 W4 — THE REAPER: the module that answers "may I kill this?", and the one arm that
 * acts on the answer.
 *
 * This is the riskiest code in 861, and the tempdoc says why: **three independent reapers in this
 * repository have already become the incident they existed to prevent** — `remove-worktree`'s
 * self-match (tempdoc 746 item 5), `ui-shot-cleanup`'s blind `taskkill` (861 §3c-bis), and the
 * dev-runner's own lapsed-lease reap of a stack that was busy doing exactly what its owner started
 * it for (`scripts/dev/dev-runner.cjs:2102-2113`, "Proven gap 2026-07-14"). Three instances in one
 * subsystem is not bad luck; it is the base rate for this kind of code. Every design choice below
 * is a response to one of them.
 *
 * ── The shape: a projection and an arm ──────────────────────────────────────────────────────
 *
 * `reapEligible` is a PURE projection. It kills nothing, signals nothing, writes nothing, and
 * reads no directory. It takes records and evidence and returns four buckets. `executeReap` is the
 * single effectful arm. The split is what lets 861 Phase 5 wire six different reap occasions
 * without any of them owning kill logic — an occasion chooses a column and a capability, and the
 * matrix decides.
 *
 * ── Four rules the structure enforces, rather than asks for ────────────────────────────────
 *
 *  1. **`isVerifiedMatch` is the ONLY kill licence** (861 §6.2/§6.3). Identity is `pid` AND
 *     creation time AND command-line fingerprint, and a REFUSE is not a `false` — it is a third
 *     verdict meaning "I have no evidence", which never licenses anything.
 *
 *  2. **The evidence must be FRESH at the moment of the kill, not at the moment of the decision.**
 *     `executeReap` does not accept a verdict computed by `reapEligible`. It reads the process
 *     table ITSELF, immediately before acting, and re-verifies. That is the F1 TOCTOU discipline
 *     from PR #549's review made structural: the projection's verdict is an *eligibility* claim
 *     with a shelf life, and the only way to stop a caller from spending a stale one is to make it
 *     unspendable. A table that has aged past `maxTableAgeMs` between the two points REFUSES at
 *     execution, by construction, with no cooperation required from the caller.
 *
 *  3. **[A1] A lapsed lease is not, by itself, a licence to kill.** This is the correction that
 *     gated this whole phase. Rev 1 of 861 had "registered, lease lapsed -> reap", which
 *     reproduces the 2026-07-14 defect verbatim. A quiet owner is not an absent owner. So the
 *     lapsed column is SPLIT on owner activity, read through the existing `classifyActivity`
 *     (`ownership-verdict.cjs:82-92`) rather than a second implementation of the same judgement,
 *     and widened by the record's own declared hold exactly as `dev-runner.cjs:2109-2113` widens
 *     its threshold. `known: false` is LEAVE, never stale. See `ownerActivityVerdict`.
 *
 *  4. **[A4] No kill path runs from a PreToolUse hook.** The before-a-build occasion is advisory
 *     only. That is enforced here rather than trusted to the hook: under
 *     `capability: 'advisory'` the projection mints NO `reap` entries at all — every one is
 *     downgraded to `report` carrying `ceiling: 'reap'` — and `executeReap` refuses any entry
 *     whose disposition is not `reap`. An advisory caller therefore holds nothing spendable.
 *
 * ── Scope: this module acts on the `agent-spawns/` register and nothing else ────────────────
 *
 * `foreign/` means a JustSearch BACKEND started outside the dev-runner; `agent-spawns/` means a
 * helper process an agent session spawned. Two meanings, two directories. Widening one until it
 * covered the other is the fork 861 §6.1 separates them to prevent, and a reaper is the worst
 * possible place to blur them — so this module names no foreign-scope symbol at all. The one thing
 * it takes from the shared grammar module is `writeRecordAtomic`, the generic layer's ONE atomic
 * temp+rename writer, because writing a fourth one would be the reuse defect this repo's agent
 * rules name first. The dev-runner's own active run is likewise never this module's to kill; it
 * appears as a matrix dimension so that a record pointing at one is *reported*, not silently
 * skipped.
 */
'use strict';

const { spawnSync } = require('node:child_process');
const fsp = require('node:fs/promises');
const path = require('node:path');

const {
  DEFAULT_THRESHOLDS,
  readSessionActivity,
  classifyActivity,
} = require('./ownership-verdict.cjs');

const {
  IDENTITY,
  DEFAULT_MAX_TABLE_AGE_MS,
  readProcessTable,
  verifyProcessIdentity,
  isVerifiedMatch,
} = require('./process-identity.cjs');

const {
  OWNERSHIP_MODES,
  leaseState,
  markAgentSpawnRecordFailedVerify,
  removeAgentSpawnRecord,
  agentSpawnRecordPath,
} = require('./agent-spawn-record.cjs');

// The generic layer's ONE atomic writer, and the only symbol this module takes from that file.
const { writeRecordAtomic } = require('./process-record.cjs');

/* ── Vocabulary ────────────────────────────────────────────────────────────────────────────── */

/**
 * The four buckets. Deliberately not two: "I may kill this" and "I may not" would collapse the
 * three *reasons* not to kill, and those reasons are what a caller must report differently.
 *
 *   `reap`       — authorized. The only bucket `executeReap` accepts.
 *   `contention` — another session holds a live claim. Report; on a conflict occasion, refuse to
 *                  proceed. NOT garbage, and specifically not the lapsed-lease-alone cell.
 *   `refuse`     — no licence, because the identity evidence is absent, unreadable, or negative.
 *                  The record is RETAINED and marked, never deleted (861 §6.3).
 *   `report`     — never killed as a matter of policy: an `ownerless-singleton`, the dev-runner's
 *                  own active run, the observed tier, and every advisory-downgraded `reap`.
 */
const REAP_DISPOSITIONS = Object.freeze({
  REAP: 'reap',
  CONTENTION: 'contention',
  REFUSE: 'refuse',
  REPORT: 'report',
});

/** The two columns of the §6.3 matrix. */
const OCCASION_KINDS = Object.freeze({
  SWEEP: 'abandonment-sweep',
  CONFLICT: 'conflict',
});

/**
 * [A4] What an occasion is allowed to do with the column's value. The matrix's conflict column is
 * a CEILING, not an instruction: worktree teardown may act up to it, before-a-build may not.
 */
const CAPABILITIES = Object.freeze({
  EXECUTE: 'execute',
  ADVISORY: 'advisory',
});

/**
 * Stable cell identifiers. Each is one cell of the corrected §6.3 matrix, and the table-driven
 * test asserts on these by name — so a cell cannot be quietly dropped without a named test going
 * red, and a reviewer walking the matrix has something to walk.
 */
const CELLS = Object.freeze({
  SAME_SESSION: 'same-session',
  LEASE_LIVE: 'other-session/lease-live',
  LEASE_UNKNOWN: 'other-session/lease-unknown',
  LAPSED_OWNER_ACTIVE: 'other-session/lease-lapsed/owner-active',
  LAPSED_OWNER_UNKNOWN: 'other-session/lease-lapsed/owner-unknown',
  LAPSED_DECLARED_HOLD: 'other-session/lease-lapsed/owner-within-declared-hold',
  LAPSED_OWNER_STALE: 'other-session/lease-lapsed/owner-stale',
  OWNERLESS_SINGLETON: 'ownerless-singleton',
  DEV_RUNNER_OWN_RUN: 'dev-runner-own-active-run',
  IDENTITY_REFUSE: 'identity-refuse',
  IDENTITY_MISMATCH: 'identity-mismatch',
  RECORD_UNREADABLE: 'record-unreadable',
  OBSERVED_ONLY: 'observed-only',
});

/**
 * The owner-activity tri-state-plus-one, the [A1] correction's vocabulary.
 *
 * `unknown` is separate from `stale` for the same reason `classifyActivity` returns
 * `known: false`: an absent signal must not masquerade as a permissive one. `declared-hold` is
 * separate from `active` because the two are distinguishable evidence with the same verdict, and
 * a report that says which one applies is a report a human can act on.
 */
const OWNER_STATES = Object.freeze({
  UNKNOWN: 'unknown',
  ACTIVE: 'active',
  DECLARED_HOLD: 'declared-hold',
  STALE: 'stale',
});

/**
 * Grace beyond the abandonment threshold, mirroring `dev-runner.cjs:2100-2104` including its env
 * override so an integration test can drive abandonment in seconds. Same name, same default,
 * same knob: this module ports the dev-runner's fix, it does not re-invent a second one.
 */
function reaperGraceMs(env = process.env) {
  const v = Number(env.JUSTSEARCH_DEV_REAPER_GRACE_MS);
  return Number.isFinite(v) && v > 0 ? v : 5 * 60_000;
}

/* ── [A1] The activity join ────────────────────────────────────────────────────────────────── */

/**
 * The single judgement that separates "abandoned" from "quiet but working" — the whole content of
 * amendment [A1], and the reason Phase 4 was gated until the tempdoc's text was corrected.
 *
 * Ported from `dev-runner.cjs:2090-2114`, which is where this repository already paid for it:
 *
 *   - `classifyActivity` supplies the tri-state. `known: false` means no stamp exists, which is
 *     no evidence of absence, so it returns `unknown` and the matrix reads that as LEAVE.
 *   - `generalStale: false` means the owner made a tool call recently. Quiet is not gone.
 *   - Past the general threshold, the record's OWN declared hold widens the bar, exactly as
 *     `abandonedThresholdMs = max(default + grace, declaredHoldMs + grace)` does at :2110-2113.
 *     A producer that declared a two-hour lease said "I will be busy-but-quiet for this long";
 *     the lease duration IS the declaration, so no second field is needed to carry it.
 *
 * @returns {{state: string, reason: string, abandonedMs: number|null, thresholdMs: number}}
 */
function ownerActivityVerdict(activity, record, now, thresholds = DEFAULT_THRESHOLDS, env = process.env) {
  const grace = reaperGraceMs(env);
  const declaredHoldMs = Number.isFinite(record?.lease?.durationSec) && record.lease.durationSec > 0
    ? record.lease.durationSec * 1000
    : 0;
  const thresholdMs = Math.max(thresholds.abandonedAfterMs + grace, declaredHoldMs + grace);

  const cls = classifyActivity(activity, now, thresholds);
  if (!cls.known) {
    return {
      state: OWNER_STATES.UNKNOWN,
      reason: 'no activity stamp exists for the owning session; absence of a signal is not evidence of an absent owner',
      abandonedMs: null,
      thresholdMs,
    };
  }
  const lastT = new Date(activity.lastActivityAt).getTime();
  const abandonedMs = Number.isFinite(lastT) ? now - lastT : null;
  if (!cls.generalStale) {
    return {
      state: OWNER_STATES.ACTIVE,
      reason: `owning session made a tool call ${abandonedMs === null ? 'recently' : `${Math.round(abandonedMs / 1000)}s ago`}; a quiet owner is not an absent owner`,
      abandonedMs,
      thresholdMs,
    };
  }
  if (abandonedMs === null || abandonedMs <= thresholdMs) {
    return {
      state: OWNER_STATES.DECLARED_HOLD,
      reason: `owning session is silent but within its declared hold (${abandonedMs === null ? 'unreadable stamp' : `${Math.round(abandonedMs / 1000)}s`} <= ${Math.round(thresholdMs / 1000)}s); the declared lease duration is intent`,
      abandonedMs,
      thresholdMs,
    };
  }
  return {
    state: OWNER_STATES.STALE,
    reason: `owning session has been silent ${Math.round(abandonedMs / 1000)}s, beyond its declared hold plus grace (${Math.round(thresholdMs / 1000)}s)`,
    abandonedMs,
    thresholdMs,
  };
}

/* ── Entry construction ────────────────────────────────────────────────────────────────────── */

function killLineFor(pid) {
  return `taskkill /PID ${pid} /F`;
}

/**
 * [A4] The advisory downgrade, applied at exactly one place so it cannot be applied at only some
 * of them. A `reap` under `capability: 'advisory'` becomes a `report` that still says what the
 * ceiling was, so an advisory surface can print "this WOULD be reapable" without holding anything
 * spendable.
 */
function applyCapability(disposition, capability) {
  if (capability === CAPABILITIES.ADVISORY && disposition === REAP_DISPOSITIONS.REAP) {
    return { disposition: REAP_DISPOSITIONS.REPORT, ceiling: REAP_DISPOSITIONS.REAP, downgraded: true };
  }
  return { disposition, ceiling: disposition, downgraded: false };
}

function makeEntry({
  recordId,
  record,
  cell,
  disposition,
  reason,
  occasion,
  capability,
  identity = null,
  owner = null,
  lease = null,
  pid = null,
}) {
  const applied = applyCapability(disposition, capability);
  const effectivePid = pid === null ? (Number.isInteger(record?.pid) ? record.pid : null) : pid;
  return {
    recordId,
    record: record || null,
    cell,
    disposition: applied.disposition,
    ceiling: applied.ceiling,
    downgraded: applied.downgraded,
    reason,
    occasion,
    capability,
    identity,
    owner,
    lease,
    pid: effectivePid,
    // Present on every entry, including the reapable ones: a report that names the process but
    // makes the human reconstruct the command is a report that gets ignored.
    ...(effectivePid !== null ? { killLine: killLineFor(effectivePid) } : {}),
    // What `remove-worktree` needs from the conflict column (861 §6.4): *"refuses to proceed while
    // an unreapable holder remains"*, rather than proceeding into the half-deleted, `.git`-less
    // worktree shell §2-bis (c) documents. So the condition is exactly "a REGISTERED holder this
    // reaper cannot clear" — which covers contention, an identity refusal, AND the two never-reap
    // policy rows, since an `ownerless-singleton` or the dev-runner's own run holding the tree is
    // no less of a lock for being one we may not touch.
    //
    // The observed tier is excluded: those rows are whatever the caller enumerated, and deciding
    // that an unattributed process blocks a teardown is the caller's judgement, not this module's.
    //
    // Never set on an advisory occasion — [A4] leaves before-a-build at report tier, and a hint
    // that blocked a build would be a kill path's cousin: an agent's `gradlew` failing because a
    // hook decided so.
    blocksProceed:
      occasion === OCCASION_KINDS.CONFLICT
      && capability === CAPABILITIES.EXECUTE
      && cell !== CELLS.OBSERVED_ONLY
      && applied.disposition !== REAP_DISPOSITIONS.REAP,
  };
}

/* ── The matrix ────────────────────────────────────────────────────────────────────────────── */

/**
 * One record, one cell. The corrected §6.3 matrix, in the order its rows must be evaluated.
 *
 * **Precedence is part of the specification, not an implementation detail.** Rows 5, 6 and 7 of
 * the printed matrix are dimensions that dominate rows 1-4, and a reader must be able to see which
 * dominates which:
 *
 *   1. `ownerless-singleton` and the dev-runner's own active run are NEVER reaped, whatever the
 *      owner state and whatever the identity evidence says. They are evaluated first precisely so
 *      that no later branch can reach a `reap` for them — a never-reap row placed after the reap
 *      rows is a never-reap row one refactor away from being skipped.
 *   2. Identity next: no licence without evidence, so nothing below can be reached without one.
 *   3. Only then the owner/lease/activity join, which is the only part that can return `reap`.
 *
 * @param {object} args
 * @param {object} args.record
 * @param {string} args.recordId
 * @param {string} args.occasion    one of OCCASION_KINDS
 * @param {string} args.capability  one of CAPABILITIES
 * @param {string|null} args.callerSessionId
 * @param {object} args.identity    a `verifyProcessIdentity` result
 * @param {object|null} args.activity  the owning session's activity stamp
 * @param {{runId: string|null, pids: number[]}} args.devRunnerActive
 */
function classifySpawnRecord({
  record,
  recordId,
  occasion,
  capability,
  callerSessionId = null,
  now = Date.now(),
  identity,
  activity = null,
  thresholds = DEFAULT_THRESHOLDS,
  devRunnerActive = null,
  env = process.env,
}) {
  const base = { recordId, record, occasion, capability, identity };

  // ── 1. Never-reap dimensions ────────────────────────────────────────────────────────────
  if (record?.ownership === OWNERSHIP_MODES.OWNERLESS_SINGLETON) {
    // Declared, not exempted (861 §6.2). Its claim is renewed by its own probe, so its lease
    // state is irrelevant here — and deliberately not consulted, because consulting it would
    // invite a future edit that reaps a lapsed one.
    return makeEntry({
      ...base,
      cell: CELLS.OWNERLESS_SINGLETON,
      disposition: REAP_DISPOSITIONS.REPORT,
      reason: `record declares ownership=${OWNERSHIP_MODES.OWNERLESS_SINGLETON}; a daemon whose purpose is outliving every session is never reaped, only reported`,
    });
  }

  const activePids = Array.isArray(devRunnerActive?.pids) ? devRunnerActive.pids : [];
  const matchesActiveRun =
    (Number.isInteger(record?.pid) && activePids.includes(record.pid))
    || (devRunnerActive?.runId && record?.devRunnerRunId && record.devRunnerRunId === devRunnerActive.runId);
  if (matchesActiveRun) {
    return makeEntry({
      ...base,
      cell: CELLS.DEV_RUNNER_OWN_RUN,
      disposition: REAP_DISPOSITIONS.REPORT,
      reason: `pid ${record?.pid} belongs to the dev-runner's own active run (${devRunnerActive?.runId ?? 'unknown runId'}); the dev-runner owns its own lifecycle and this reaper never touches it`,
    });
  }

  // ── 2. Identity ─────────────────────────────────────────────────────────────────────────
  if (!isVerifiedMatch(identity)) {
    const mismatch = identity?.verdict === IDENTITY.MISMATCH;
    return makeEntry({
      ...base,
      cell: mismatch ? CELLS.IDENTITY_MISMATCH : CELLS.IDENTITY_REFUSE,
      disposition: REAP_DISPOSITIONS.REFUSE,
      reason: mismatch
        ? `identity verification returned MISMATCH: ${identity?.reason}. The record is retained and marked; only pruning by age removes it`
        : `identity verification returned REFUSE: ${identity?.reason ?? 'no verdict'}. Absent evidence never licenses a kill`,
    });
  }

  // ── 3. Owner, lease, activity ───────────────────────────────────────────────────────────
  const lease = leaseState(record, now);

  if (callerSessionId && record?.sessionId && record.sessionId === callerSessionId) {
    // "A session may always reap its own registered spawns" (861 §6.3) — which is what makes the
    // mid-session build case unambiguous: the session asking to build started the Vite.
    return makeEntry({
      ...base,
      cell: CELLS.SAME_SESSION,
      disposition: REAP_DISPOSITIONS.REAP,
      reason: `record is owned by the calling session ${callerSessionId}; a session may always reap its own spawns`,
      lease,
    });
  }

  if (lease === 'live') {
    return makeEntry({
      ...base,
      cell: CELLS.LEASE_LIVE,
      disposition: REAP_DISPOSITIONS.CONTENTION,
      reason: `another session (${record?.sessionId ?? 'unattributed'}) holds a live lease until ${record?.lease?.expiresAt}; this is contention, mirroring the dev-stack's OWNER_CONFLICT model`,
      lease,
    });
  }
  if (lease === 'unknown') {
    // W2's `leaseState` returns a tri-state on purpose: a record whose lease cannot be read is a
    // record about which nothing is known, and collapsing that into `lapsed` would license a kill
    // on absent evidence — the same defect `classifyActivity`'s `known: false` prevents.
    return makeEntry({
      ...base,
      cell: CELLS.LEASE_UNKNOWN,
      disposition: REAP_DISPOSITIONS.CONTENTION,
      reason: `record carries no readable lease expiry (${JSON.stringify(record?.lease?.expiresAt)}); unknown is not lapsed`,
      lease,
    });
  }

  // MUTATION-SENTINEL [A1]: the activity join. Removing it collapses the split lapsed column back
  // to rev 1's "lapsed -> reap", which is the 2026-07-14 reap-while-working defect verbatim.
  const owner = ownerActivityVerdict(activity, record, now, thresholds, env);

  if (owner.state === OWNER_STATES.UNKNOWN) {
    return makeEntry({
      ...base,
      cell: CELLS.LAPSED_OWNER_UNKNOWN,
      disposition: REAP_DISPOSITIONS.CONTENTION,
      reason: `lease lapsed, but ${owner.reason}`,
      lease,
      owner,
    });
  }
  if (owner.state === OWNER_STATES.ACTIVE) {
    return makeEntry({
      ...base,
      cell: CELLS.LAPSED_OWNER_ACTIVE,
      disposition: REAP_DISPOSITIONS.CONTENTION,
      reason: `lease lapsed, but ${owner.reason}`,
      lease,
      owner,
    });
  }
  if (owner.state === OWNER_STATES.DECLARED_HOLD) {
    return makeEntry({
      ...base,
      cell: CELLS.LAPSED_DECLARED_HOLD,
      disposition: REAP_DISPOSITIONS.CONTENTION,
      reason: `lease lapsed, but ${owner.reason}`,
      lease,
      owner,
    });
  }

  return makeEntry({
    ...base,
    cell: CELLS.LAPSED_OWNER_STALE,
    disposition: REAP_DISPOSITIONS.REAP,
    reason: `lease lapsed AND ${owner.reason}; both conditions hold, which is the only combination that reaps another session's spawn`,
    lease,
    owner,
  });
}

/* ── The pure projection ───────────────────────────────────────────────────────────────────── */

function normalizeInputRecords(records) {
  const out = [];
  for (const item of records || []) {
    if (item && typeof item === 'object' && typeof item.ok === 'boolean') {
      out.push({ ok: item.ok, recordId: item.recordId, record: item.record || null, reason: item.reason || null });
      continue;
    }
    out.push({ ok: true, recordId: item?.recordId ?? null, record: item, reason: null });
  }
  return out;
}

/**
 * The projection 861 Phase 5 wires six occasions onto. PURE: no kills, no signals, no writes, no
 * directory reads. Every fact it needs arrives as an argument.
 *
 * The freshness bound is honoured HERE too, not only at execution: `verifyProcessIdentity` is
 * given the caller's `processTable` result verbatim, so an aged or bare-array table lands every
 * record in `refuse` rather than producing an eligibility list nobody may spend. That is the
 * intended behaviour — an occasion that hands over a stale snapshot gets a refusal it can see,
 * not a licence it must remember not to use.
 *
 * @param {object} args
 * @param {Array} args.records  agent-spawn records, or `readAgentSpawnRegister` entries.
 * @param {object|Array} args.processTable  a `readProcessTable()` result.
 * @param {Array} [args.observed]  process-table rows matching no record (861 §6.3's observed tier).
 * @param {function} [args.activityFor]  `(sessionId) => activity|null`. Defaults to reading the
 *   dev-runner's session stamps from `sessionsDir` when one is supplied, and to `null` otherwise
 *   — which is `unknown`, which is LEAVE. A missing wiring therefore fails safe.
 * @returns {{reap: Array, contention: Array, refuse: Array, report: Array, all: Array, blocksProceed: boolean}}
 */
function reapEligible({
  records = [],
  processTable = null,
  observed = [],
  occasion = OCCASION_KINDS.SWEEP,
  // The default is the SAFE one, matching W2's `acceptUnstampedTable: false`: an occasion that
  // forgot to declare itself gets a report-only projection, not a list of things it may kill.
  // Killing requires naming `CAPABILITIES.EXECUTE` out loud.
  capability = CAPABILITIES.ADVISORY,
  callerSessionId = null,
  now = Date.now(),
  thresholds = DEFAULT_THRESHOLDS,
  devRunnerActive = null,
  sessionsDir = null,
  activityFor = null,
  maxTableAgeMs = DEFAULT_MAX_TABLE_AGE_MS,
  acceptUnstampedTable = false,
  env = process.env,
} = {}) {
  const lookupActivity = typeof activityFor === 'function'
    ? activityFor
    : (sessionId) => (sessionsDir && sessionId ? readSessionActivity(sessionsDir, sessionId) : null);

  const all = [];
  for (const item of normalizeInputRecords(records)) {
    if (!item.ok || !item.record) {
      all.push(makeEntry({
        recordId: item.recordId,
        record: null,
        cell: CELLS.RECORD_UNREADABLE,
        disposition: REAP_DISPOSITIONS.REFUSE,
        reason: `record could not be read or failed scope validation (${item.reason ?? 'no reason given'}); an unreadable record is no evidence, and no evidence never licenses a kill`,
        occasion,
        capability,
      }));
      continue;
    }
    const record = item.record;
    const recordId = item.recordId ?? record.recordId ?? null;
    const identity = verifyProcessIdentity({
      record,
      table: processTable,
      maxTableAgeMs,
      acceptUnstampedTable,
      now,
    });
    const activity = record.sessionId ? lookupActivity(record.sessionId) : null;
    all.push(classifySpawnRecord({
      record,
      recordId,
      occasion,
      capability,
      callerSessionId,
      now,
      identity,
      activity,
      thresholds,
      devRunnerActive,
      env,
    }));
  }

  for (const row of observed || []) {
    const pid = Number(row?.ProcessId ?? row?.pid);
    all.push(makeEntry({
      recordId: null,
      record: null,
      cell: CELLS.OBSERVED_ONLY,
      disposition: REAP_DISPOSITIONS.REPORT,
      reason: `pid ${Number.isInteger(pid) ? pid : '?'} (${row?.Name ?? row?.name ?? 'unknown'}) matches no record; the observed tier is never auto-killed, only reported with a ready-to-run kill line`,
      occasion,
      capability,
      pid: Number.isInteger(pid) && pid > 0 ? pid : null,
    }));
  }

  const buckets = {
    reap: all.filter((e) => e.disposition === REAP_DISPOSITIONS.REAP),
    contention: all.filter((e) => e.disposition === REAP_DISPOSITIONS.CONTENTION),
    refuse: all.filter((e) => e.disposition === REAP_DISPOSITIONS.REFUSE),
    report: all.filter((e) => e.disposition === REAP_DISPOSITIONS.REPORT),
    all,
  };
  buckets.blocksProceed = all.some((e) => e.blocksProceed);
  return buckets;
}

/* ── The effectful arm ─────────────────────────────────────────────────────────────────────── */

function taskkillByPid(pid, { exec = spawnSync, platform = process.platform } = {}) {
  if (platform !== 'win32') {
    return { ok: false, reason: `kill is implemented for win32 only (platform=${platform})`, status: null };
  }
  // By pid, never `/T`. A tree kill would reach processes this module has verified nothing about,
  // which is exactly the blind `taskkill` that made `ui-shot-cleanup` an incident (861 §3c-bis).
  let res;
  try {
    res = exec('taskkill.exe', ['/PID', String(pid), '/F'], { encoding: 'utf8' });
  } catch (err) {
    return { ok: false, reason: `taskkill threw: ${String(err?.message || err).slice(0, 200)}`, status: null };
  }
  const status = res?.status ?? null;
  return {
    ok: status === 0,
    status,
    stdout: (res?.stdout || '').trim().slice(0, 400),
    stderr: (res?.stderr || '').trim().slice(0, 400),
    ...(status === 0 ? {} : { reason: `taskkill exited ${status}` }),
  };
}

/**
 * Kill one eligible entry — the ONLY code path in 861 that ends a process.
 *
 * The sequence, and why each step is where it is:
 *
 *   1. **Refuse anything that is not `reap`.** Includes every advisory-downgraded entry, which is
 *      how [A4]'s "no kill from a PreToolUse hook" is structural rather than trusted.
 *   2. **Read the process table FRESH, here, now.** Not the caller's table, not the projection's
 *      verdict. This is the TOCTOU seam: eligibility was decided at some earlier instant, and the
 *      only defence against spending a stale decision is to re-derive the licence at the moment of
 *      use. A caller that hands over an aged snapshot (or whose fresh read fails) gets a REFUSE.
 *   3. **Re-verify identity** — pid AND creation time AND fingerprint, the creation-time re-check
 *      sitting between eligibility and the kill.
 *   4. **On any non-match: mark the record failed-verify and RETAIN it** (861 §6.3 / [A5]). Never
 *      delete: the diagnostic trail must survive the refusal, and `pruneAgentSpawnRecords` is what
 *      keeps that retention bounded.
 *   5. **`taskkill /PID <pid> /F`**, by pid only.
 *   6. **Confirm** with a second fresh read, then stamp the record with the outcome. The stamp is
 *      written BEFORE any removal, so the trail exists even if removal fails. An UNCONFIRMED kill
 *      always retains the record — an outcome nobody could verify is the one most worth keeping.
 *
 * @param {object} entry  a `reapEligible` entry from the `reap` bucket.
 * @param {object} args
 * @param {string} args.dir  the agent-spawns register directory.
 * @param {function} [args.readTable]  injectable fresh-table reader; defaults to `readProcessTable`.
 * @param {object} [args.actor]  `{ sessionId, source }` — recorded as by-whom.
 * @param {boolean} [args.removeOnConfirmedKill]  default true.
 */
async function executeReap(entry, {
  dir,
  readTable = readProcessTable,
  maxTableAgeMs = DEFAULT_MAX_TABLE_AGE_MS,
  now = Date.now,
  exec = spawnSync,
  platform = process.platform,
  actor = null,
  removeOnConfirmedKill = true,
} = {}) {
  const recordId = entry?.recordId ?? entry?.record?.recordId ?? null;
  const record = entry?.record ?? null;

  if (entry?.disposition !== REAP_DISPOSITIONS.REAP) {
    return {
      killed: false,
      confirmed: false,
      refused: true,
      recordId,
      reason: `executeReap accepts only entries whose disposition is "${REAP_DISPOSITIONS.REAP}"; this one is "${entry?.disposition}"${entry?.downgraded ? ' (advisory-downgraded: this occasion may not kill)' : ''}`,
    };
  }
  if (!record || !Number.isInteger(record.pid) || record.pid <= 0) {
    return { killed: false, confirmed: false, refused: true, recordId, reason: 'entry carries no record with a usable pid' };
  }

  // Step 2 — the fresh read. Inside this function, unconditionally, every time.
  const fresh = readTable();
  const at = now();
  const identity = verifyProcessIdentity({ record, table: fresh, maxTableAgeMs, now: at });

  if (!isVerifiedMatch(identity)) {
    let marked = null;
    if (dir && recordId) {
      marked = await markAgentSpawnRecordFailedVerify({
        dir,
        recordId,
        verdict: identity.verdict,
        reason: identity.reason,
        now: at,
      }).catch((err) => ({ marked: false, reason: String(err?.message || err).slice(0, 200) }));
    }
    return {
      killed: false,
      confirmed: false,
      refused: true,
      recordId,
      pid: record.pid,
      identity,
      marked,
      retained: true,
      reason: `re-verification at the moment of the kill did not return ${IDENTITY.MATCH}: ${identity.reason}`,
    };
  }

  const kill = taskkillByPid(record.pid, { exec, platform });

  // Step 6 — confirm. A MISMATCH here is the success signal: the pid is gone from the table.
  const after = readTable();
  const post = verifyProcessIdentity({ record, table: after, maxTableAgeMs, now: now() });
  const confirmed = post.verdict === IDENTITY.MISMATCH;

  const stamp = {
    at: new Date(at).toISOString(),
    by: {
      sessionId: actor?.sessionId ?? null,
      source: actor?.source ?? 'agent-spawn-reaper',
      occasion: entry.occasion ?? null,
      cell: entry.cell ?? null,
    },
    kill: { ok: kill.ok, status: kill.status ?? null, ...(kill.reason ? { reason: kill.reason } : {}) },
    confirmed,
    confirmation: post.reason,
  };

  let stamped = false;
  if (dir && recordId) {
    try {
      const file = agentSpawnRecordPath(dir, recordId);
      const current = JSON.parse(await fsp.readFile(file, 'utf8'));
      // Deliberately the raw atomic write rather than `writeAgentSpawnRecord`: this stamps a
      // record that is about to be retired, and re-validating it here would discard the outcome
      // trail for exactly the odd-shaped record most worth keeping it for. Same reasoning W2
      // records at `markAgentSpawnRecordFailedVerify`.
      await writeRecordAtomic(file, { ...current, reaped: stamp });
      stamped = true;
    } catch {
      stamped = false;
    }
  }

  let recordRemoved = false;
  if (confirmed && removeOnConfirmedKill && dir && recordId) {
    const res = await removeAgentSpawnRecord({ dir, recordId }).catch(() => ({ removed: false }));
    recordRemoved = Boolean(res?.removed);
  }

  return {
    killed: kill.ok,
    confirmed,
    refused: false,
    recordId,
    pid: record.pid,
    identity,
    kill,
    stamp,
    stamped,
    recordRemoved,
    // Retained whenever the kill could not be confirmed: an unverifiable outcome is evidence.
    retained: !recordRemoved,
  };
}

/* ── Resolving the dev-runner's own active run (for the never-reap dimension) ──────────────── */

/**
 * Collect the pids the dev-runner's own active run owns, so `classifySpawnRecord` can refuse to
 * touch them. Best-effort by design: an unreadable `active.json` yields `{ runId: null, pids: [] }`,
 * which is the CONSERVATIVE direction only because every other never-reap protection still
 * applies — so callers that can supply the facts should, and this is the fallback, not the
 * authority.
 */
async function readDevRunnerActiveRun({ stateRoot } = {}) {
  const empty = { runId: null, pids: [] };
  if (!stateRoot) return empty;
  let active;
  try {
    active = JSON.parse(await fsp.readFile(path.join(stateRoot, 'active.json'), 'utf8'));
  } catch {
    return empty;
  }
  const runId = typeof active?.runId === 'string' ? active.runId : null;
  if (!runId) return empty;
  let run;
  try {
    run = JSON.parse(await fsp.readFile(path.join(stateRoot, 'runs', runId, 'run.json'), 'utf8'));
  } catch {
    return { runId, pids: [] };
  }
  const pids = Object.values(run?.pids || {}).filter((p) => Number.isInteger(p) && p > 0);
  return { runId, pids };
}

module.exports = {
  REAP_DISPOSITIONS,
  OCCASION_KINDS,
  CAPABILITIES,
  CELLS,
  OWNER_STATES,
  reaperGraceMs,
  ownerActivityVerdict,
  classifySpawnRecord,
  reapEligible,
  executeReap,
  taskkillByPid,
  readDevRunnerActiveRun,
};
