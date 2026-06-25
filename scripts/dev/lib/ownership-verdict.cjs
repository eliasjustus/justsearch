#!/usr/bin/env node
/**
 * Tempdoc 606 — single ownership-verdict authority.
 *
 * ONE pure decision function, `computeOwnershipVerdict`, consumed by BOTH the
 * admission gate (`dev-runner.cjs acquireAdmission`) and the advisory surface
 * (`justsearch-dev-mcp` quick_health/status/start). This is the single-
 * derivation fix for D3 (the conflict verdict was previously re-derived in
 * three places that could disagree).
 *
 * Presence is sensed from owner-session ACTIVITY signals (D1), not the
 * supervisor PID (which is unobtainable for a Claude session and, being
 * renewed by a detached supervisor, is a false liveness signal). Two signals:
 *   - general activity  (any tool call)        → `lastActivityAt`
 *   - dev-stack activity (justsearch-dev call)  → `lastDevStackTouchAt`
 * giving the grades the field evidence demanded:
 *   general stale                      → abandoned   (owner gone/silent)
 *   general fresh + dev-stack stale     → idle-hold   (owner alive, not using)
 *   general fresh + dev-stack fresh     → active      (genuine contention)
 *
 * SAFETY: absence of any activity stamp is treated as UNKNOWN → behaves like an
 * active owner (no auto-takeover). Friction is only reduced on POSITIVE evidence
 * of staleness, so a missed hook write cannot cause a false takeover.
 */

'use strict';

const fs = require('fs');
const path = require('path');

// Thresholds are small multiples of the 10s supervisor tick, generous on the
// idle axis to avoid stealing a warm stack from an owner doing static-work-then-
// verify (606 §Open questions). Overridable per-call via facts.thresholds, and
// globally via env (JUSTSEARCH_DEV_ABANDONED_MS / JUSTSEARCH_DEV_IDLE_MS) so
// integration tests can drive abandonment/idle in seconds (606 validation seam).
function _envMs(name, def) {
  const v = Number(process.env[name]);
  return Number.isFinite(v) && v > 0 ? v : def;
}
const DEFAULT_THRESHOLDS = Object.freeze({
  abandonedAfterMs: _envMs('JUSTSEARCH_DEV_ABANDONED_MS', 5 * 60_000), // no tool call → gone/silent
  idleAfterMs: _envMs('JUSTSEARCH_DEV_IDLE_MS', 15 * 60_000), // alive but no dev-stack touch → idle
});

/** Read the shared per-session activity stamp written by the agent-analytics hooks. */
function readSessionActivity(sessionsDir, sessionId) {
  if (!sessionId) return null;
  try {
    const doc = JSON.parse(fs.readFileSync(path.join(sessionsDir, `${sessionId}.json`), 'utf8'));
    return {
      lastActivityAt: doc.lastActivityAt ?? null,
      lastDevStackTouchAt: doc.lastDevStackTouchAt ?? null,
      ownedEpoch: doc.ownedEpoch ?? null,
    };
  } catch {
    return null;
  }
}

/**
 * Merge a patch into a session's activity stamp (best-effort, atomic-ish). Used by the
 * dev-runner to record `ownedEpoch` at takeover so a later displaced owner can detect it
 * (tempdoc 606 3a notification). Mirrors the hook-base writer's file shape.
 */
function mergeSessionActivity(sessionsDir, sessionId, patch) {
  if (!sessionId) return;
  try {
    fs.mkdirSync(sessionsDir, { recursive: true });
    const file = path.join(sessionsDir, `${sessionId}.json`);
    let cur = {};
    try { cur = JSON.parse(fs.readFileSync(file, 'utf8')) || {}; } catch { /* fresh */ }
    const tmp = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify({ ...cur, ...patch }), 'utf8');
    fs.renameSync(tmp, file);
  } catch { /* best-effort */ }
}

/**
 * Classify owner activity into staleness flags. Returns `known:false` when no
 * stamp exists — callers MUST then treat the owner as active (conservative).
 */
function classifyActivity(activity, now, thresholds = DEFAULT_THRESHOLDS) {
  if (!activity || !activity.lastActivityAt) {
    return { known: false, generalStale: false, devStale: false };
  }
  const genT = new Date(activity.lastActivityAt).getTime();
  const generalStale = Number.isFinite(genT) ? now - genT > thresholds.abandonedAfterMs : false;
  const devT = activity.lastDevStackTouchAt ? new Date(activity.lastDevStackTouchAt).getTime() : null;
  const devAge = devT && Number.isFinite(devT) ? now - devT : Infinity;
  const devStale = devAge > thresholds.idleAfterMs;
  return { known: true, generalStale, devStale };
}

/**
 * The single decision. Pure: no IO. Returns one of:
 *   { action:'proceed', verdict, disposition?, victim?, criticalOpsInterrupted?,
 *     interruptibleWithLossInterrupted?, grade, recommendedAction, rebuildFirst?, notify? }
 *   { action:'conflict', verdict, reason, criticalOps?, message?, grade, recommendedAction }
 *
 * Faithfully reproduces the pre-606 admission dispositions/reasons for the
 * stale, 542-criticality, and active-owner cases; ADDS the abandoned/idle grades.
 *
 * facts: {
 *   active, callerSessionId, selfCheck=true, supervisorAlive, leaseExpired,
 *   ownerActivity, opLeases:{byCriticality,entries}, takeover='deny',
 *   confirmInterrupt=null, provenance=null, now=Date.now(), thresholds
 * }
 */
function computeOwnershipVerdict(facts) {
  const {
    active,
    callerSessionId = null,
    selfCheck = true,
    supervisorAlive = false,
    leaseExpired = true,
    ownerActivity = null,
    opLeases = null,
    takeover = 'deny',
    confirmInterrupt = null,
    provenance = null,
    now = Date.now(),
    thresholds = DEFAULT_THRESHOLDS,
  } = facts || {};

  const buckets = opLeases?.byCriticality ?? {
    mustComplete: [],
    unsafeToInterrupt: [],
    interruptibleWithLoss: [],
  };
  const entries = opLeases?.entries ?? [];

  if (!active?.runId) {
    return {
      action: 'proceed',
      verdict: 'NO_OWNER',
      grade: 'none',
      recommendedAction: 'No active stack — start fresh.',
    };
  }

  const holder = active.holder ?? null;
  const victim = { runId: active.runId, holder };
  const callerIsOwner = !!(
    selfCheck &&
    callerSessionId &&
    holder?.agentSessionId &&
    holder.agentSessionId === callerSessionId
  );
  const rebuildFirst = !!provenance?.mismatch;

  if (callerIsOwner) {
    return {
      action: 'proceed',
      verdict: 'USE',
      grade: 'self',
      rebuildFirst,
      recommendedAction: rebuildFirst
        ? 'You own this stack, but it runs different code than your worktree — reload/relaunch before trusting it.'
        : 'You own this stack — use it directly.',
    };
  }

  if (leaseExpired || !supervisorAlive) {
    return {
      action: 'proceed',
      verdict: 'RECLAIM_DEAD',
      grade: 'dead',
      disposition: 'stale_reclaim',
      victim,
      recommendedAction: 'Previous stack is dead (supervisor gone / lease expired) — reclaiming.',
    };
  }

  // --- Tempdoc 542 §B Layer 4: criticality-aware dispatch (semantics unchanged) ---
  if (buckets.unsafeToInterrupt.length > 0) {
    if (takeover === 'force') {
      const match = buckets.unsafeToInterrupt.find((e) => e.opId === confirmInterrupt);
      if (!match) {
        return {
          action: 'conflict',
          verdict: 'REQUIRES_CONFIRMATION',
          grade: 'active',
          reason: 'requires_confirmation',
          criticalOps: buckets.unsafeToInterrupt,
          message:
            'One or more UNSAFE_TO_INTERRUPT op-leases are active. ' +
            '`force` requires --confirm-interrupt=<opId> matching a live op.',
          recommendedAction:
            'Pass --confirm-interrupt=<opId> matching a live UNSAFE_TO_INTERRUPT op, or wait.',
        };
      }
      return {
        action: 'proceed',
        verdict: 'WAIT_CRITICAL_OP',
        grade: 'active',
        disposition: 'forcibly_interrupted_critical_op',
        victim,
        criticalOpsInterrupted: entries,
        recommendedAction: 'Force-interrupting a confirmed UNSAFE_TO_INTERRUPT op.',
      };
    }
    return {
      action: 'conflict',
      verdict: 'WAIT_CRITICAL_OP',
      grade: 'active',
      reason: takeover === 'deny' ? 'fresh_owner' : 'handshake_required',
      criticalOps: buckets.unsafeToInterrupt,
      recommendedAction:
        'UNSAFE_TO_INTERRUPT op in flight — wait, or force with --confirm-interrupt=<opId>.',
    };
  }

  if (buckets.mustComplete.length > 0) {
    if (takeover === 'deny') {
      return {
        action: 'conflict',
        verdict: 'WAIT_CRITICAL_OP',
        grade: 'active',
        reason: 'fresh_owner',
        criticalOps: buckets.mustComplete,
        recommendedAction: 'MUST_COMPLETE op in flight — wait for it to finish.',
      };
    }
    if (takeover === 'warn') {
      return {
        action: 'conflict',
        verdict: 'WAIT_CRITICAL_OP',
        grade: 'active',
        reason: 'handshake_required',
        criticalOps: buckets.mustComplete,
        message:
          'One or more MUST_COMPLETE op-leases are active. ' +
          'Either wait for them to finish (recommended) or escalate to --takeover=force ' +
          '(records `forcibly_interrupted_critical_op` disposition).',
        recommendedAction:
          'MUST_COMPLETE op in flight — wait (recommended), or escalate to takeover:force.',
      };
    }
    return {
      action: 'proceed',
      verdict: 'WAIT_CRITICAL_OP',
      grade: 'active',
      disposition: 'forcibly_interrupted_critical_op',
      victim,
      criticalOpsInterrupted: entries,
      recommendedAction: 'Force-interrupting MUST_COMPLETE op(s).',
    };
  }

  // --- No critical op: presence grades (606 D1) ---
  const activity = classifyActivity(ownerActivity, now, thresholds);
  const interruptibleWithLoss =
    buckets.interruptibleWithLoss.length > 0 ? buckets.interruptibleWithLoss : undefined;

  if (activity.known && activity.generalStale) {
    return {
      action: 'proceed',
      verdict: 'TAKEOVER_ABANDONED',
      grade: 'abandoned',
      disposition: 'abandoned_reclaim',
      victim,
      notify: true,
      ...(interruptibleWithLoss ? { interruptibleWithLossInterrupted: interruptibleWithLoss } : {}),
      recommendedAction: 'Owner session is silent (no activity) — safe self-serve takeover.',
    };
  }

  if (activity.known && activity.devStale) {
    // Idle-hold: owner alive but not using the stack. Soft — the agent may
    // self-authorize a `warn` takeover WITHOUT a human round-trip; we do not
    // silently steal on plain `deny` (warm-stack-steal hazard).
    if (takeover === 'deny') {
      return {
        action: 'conflict',
        verdict: 'IDLE_HOLD',
        grade: 'idle',
        reason: 'idle_owner',
        recommendedAction:
          'Owner is alive but has not used the stack recently — safe to take over with ' +
          'takeover:"warn" (no user approval needed).',
      };
    }
    return {
      action: 'proceed',
      verdict: 'IDLE_HOLD',
      grade: 'idle',
      disposition: takeover === 'force' ? 'forced_reclaim' : 'idle_takeover',
      victim,
      notify: true,
      ...(interruptibleWithLoss ? { interruptibleWithLossInterrupted: interruptibleWithLoss } : {}),
      recommendedAction: 'Taking over an idle-held stack (the displaced owner is notified).',
    };
  }

  // Active owner (or unknown activity → treated as active, conservatively).
  if (takeover === 'deny') {
    return {
      action: 'conflict',
      verdict: 'CONTENTION',
      grade: 'active',
      reason: 'fresh_owner',
      recommendedAction: activity.known
        ? 'Owner is actively using the stack — ask the user before takeover:"warn".'
        : 'Stack is owned (activity unknown) — ask the user before takeover:"warn".',
    };
  }
  return {
    action: 'proceed',
    verdict: 'CONTENTION',
    grade: 'active',
    disposition: takeover === 'force' ? 'forced_reclaim' : 'warned_takeover',
    victim,
    notify: true,
    ...(interruptibleWithLoss ? { interruptibleWithLossInterrupted: interruptibleWithLoss } : {}),
    recommendedAction: 'Taking over from an active owner.',
  };
}

/**
 * Tempdoc 606 3a: pull-at-next-action displacement notice. Returns a message if the caller
 * previously owned the stack (recorded `callerOwnedEpoch`) but a later episode (higher
 * `currentEpoch`) is now held by someone else; else null. Pure → unit-testable.
 */
function computeDisplacedNotice(callerOwnedEpoch, currentEpoch, holderSessionId, callerSessionId) {
  const owned = Number(callerOwnedEpoch);
  const cur = Number(currentEpoch);
  if (!Number.isFinite(owned) || owned <= 0 || !Number.isFinite(cur)) return null;
  if (cur > owned && holderSessionId && holderSessionId !== callerSessionId) {
    return `Your dev stack (epoch ${owned}) was taken over — current epoch ${cur}, now held by ${holderSessionId}.`;
  }
  return null;
}

/**
 * Tempdoc 606 3c: map a verdict decision to the takeover policy an arriving agent should use,
 * or null if the stack is not acquirable right now (genuine contention / critical op). Pure.
 */
function recommendedTakeoverFor(decision) {
  if (!decision) return 'deny';
  if (decision.action === 'proceed') return 'deny'; // free / abandoned / dead / self — just start
  if (decision.verdict === 'IDLE_HOLD') return 'warn'; // idle owner — self-authorize, no user round-trip
  return null; // CONTENTION / WAIT_CRITICAL_OP / REQUIRES_CONFIRMATION — not acquirable without escalation
}

module.exports = {
  DEFAULT_THRESHOLDS,
  readSessionActivity,
  mergeSessionActivity,
  classifyActivity,
  computeOwnershipVerdict,
  computeDisplacedNotice,
  recommendedTakeoverFor,
};
