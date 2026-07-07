// SPDX-License-Identifier: Apache-2.0
/**
 * verdict.ts — the ONE system-health verdict authority (tempdoc 595).
 *
 * 557 §2.B made the FE a CONSUMER of the backend's tri-state readiness, but the
 * derived "is the system healthy / in flux?" VERDICT was still computed inline in
 * ≥4 places over two un-merged axes (the readiness axis in HealthSurface +
 * readinessNotice; the connection/runtime axis in computeStatusTier/Label) — so a
 * cosmetic `retrieval=DEGRADED` rendered as green "Online" in the status bar AND
 * orange "Service degraded" in Health at the same time (595 §1.1/§10.1).
 *
 * This module is the SINGLE place the verdict is derived. It adds the missing
 * epistemic dimension 557 never modelled — **Stability** (settled vs in-flux) —
 * generalizing the one provisional case 557 got right (`ConnectionPhase.stale`),
 * and rolls `(Stability, readiness, reindex, phase)` into ONE typed
 * `SystemHealthVerdict { kind, severity, reasons }` that every surface consumes.
 *
 * The `check-verdict-derivation` gate keeps the readiness→verdict predicate
 * (`retrieval === …`) confined to this seam, so a surface cannot re-fork it.
 *
 * Pure data → data; no DOM, no IO. Inputs are passed in so it is unit-testable.
 */

import type { ConnectionPhase, ReadinessView } from './aiStateStore.js';
import type { Maybe } from './known.js';
import { isReindexCause, severityForCodes, type Severity } from './readinessNotice.js';

export type { Severity };

/**
 * Why the observed-state is provisional (real-now-but-in-flux). Closed union so
 * every consumer handles it totally. Generalizes `ConnectionPhase`'s two
 * provisional cases (`connecting`→`initial-load`, `stale`→`channel-stale`) and
 * adds the transition causes 557 never modelled, each derived from data already
 * on `/api/status` (595 §4.1 / §9.1).
 */
export type ProvisionalCause =
  | 'initial-load'
  | 'channel-stale'
  | 'rebuilding'
  | 'generation-switch'
  | 'worker-restart'
  // 630: a brief window after OS resume while the worker re-reconciles files changed during sleep.
  | 'catching-up'
  // 649: the poll-specific data is stale (aged past the freshness window) BUT the backend is provably
  // reachable via another channel (an SSE heartbeat) — typically the cheap poll starved behind the
  // browser connection-pool limit under load. Calm: data is merely behind, the backend is alive.
  | 'updating';
// NOTE (595 §4.4 refinement): a stalled live JOB feed is NOT a global Stability
// cause. `Stability` also gates the doc-count / folder renderers (§4.3), which must
// NOT go provisional for a jobs-feed stall (the `/api/status` doc count is still
// fresh). Feed-staleness is therefore PANEL-scoped: the Tasks panel observes it
// directly via `indexingJobsBridge.subscribeFeedStalled`, not through this axis.

export type Stability =
  | { readonly kind: 'settled' }
  | { readonly kind: 'provisional'; readonly cause: ProvisionalCause };

export type VerdictKind =
  | 'operational'
  | 'checking'
  | 'connecting'
  | 'transitioning'
  | 'degraded'
  | 'unreachable';

export interface SystemHealthVerdict {
  readonly kind: VerdictKind;
  /** Drives presentation tone — derived ONCE here, not re-guessed per surface. */
  readonly severity: Severity;
  /** Reason codes / cause tokens behind a non-green verdict (for wording). */
  readonly reasons: readonly string[];
}

export interface StabilityInput {
  readonly phase: ConnectionPhase;
  /** `worker.core.indexState` — `'UNAVAILABLE'` is the worker-down fallback. */
  readonly indexState: string | null | undefined;
  /** `worker.migration.*` — the rebuild / generation-switch signal (595 §9.1). */
  readonly migrationState: string | null | undefined;
  readonly activeGenerationId: string | null | undefined;
  readonly buildingGenerationId: string | null | undefined;
  readonly servingSearchGenerationId: string | null | undefined;
  readonly servingIngestGenerationId: string | null | undefined;
  /** 630: `catchingUp` — true for a brief window after an OS resume (post-sleep reconcile). */
  readonly catchingUp?: boolean | null;
  /**
   * 649: is the backend reachable via ANY channel (poll success OR an SSE frame/heartbeat)? When the
   * poll-freshness phase is `stale` but this is true, the data is merely behind (the cheap poll
   * starved under load) while the backend is alive — a calm `updating` state, NOT `channel-stale`.
   */
  readonly reachableViaContact?: boolean;
}

/**
 * The ONE stability derivation. Precedence: a worker-down/restart fallback and an
 * in-progress generation build dominate the connection axis (they are the
 * higher-severity flux).
 */
export function computeStability(i: StabilityInput): Stability {
  // Worker process down/restarting: a SUCCESSFUL poll returned the fallback view
  // (ConnectionPhase stays `connected`, so this is NOT caught by phase — 595 §9.1).
  if ((i.indexState ?? '').toUpperCase() === 'UNAVAILABLE') {
    return { kind: 'provisional', cause: 'worker-restart' };
  }
  const ms = (i.migrationState ?? '').toUpperCase();
  if (ms === 'SWITCHING') return { kind: 'provisional', cause: 'generation-switch' };
  if (ms === 'MIGRATING') return { kind: 'provisional', cause: 'rebuilding' };
  const building = i.buildingGenerationId ?? '';
  if (building !== '' && building !== (i.activeGenerationId ?? '')) {
    return { kind: 'provisional', cause: 'rebuilding' };
  }
  const ss = i.servingSearchGenerationId ?? '';
  const si = i.servingIngestGenerationId ?? '';
  if (ss !== '' && si !== '' && ss !== si) {
    return { kind: 'provisional', cause: 'generation-switch' };
  }
  // 630: post-resume reconcile — lower precedence than a real rebuild/worker-down (above), but
  // more informative than the generic connection-axis cases (below), so name it before them.
  if (i.catchingUp === true) return { kind: 'provisional', cause: 'catching-up' };
  // Connection axis (557's existing provisional cases, generalized).
  if (i.phase === 'connecting') return { kind: 'provisional', cause: 'initial-load' };
  if (i.phase === 'stale') {
    // 649: the poll-data aged out. If the backend is still reachable via another channel (an SSE
    // heartbeat), the data is merely behind (the poll starved under load) — a calm `updating` state.
    // Only when NO channel has had recent contact is this a true lost-channel `channel-stale`.
    return i.reachableViaContact
      ? { kind: 'provisional', cause: 'updating' }
      : { kind: 'provisional', cause: 'channel-stale' };
  }
  return { kind: 'settled' };
}

export interface VerdictInput {
  readonly phase: ConnectionPhase;
  readonly stability: Stability;
  readonly readiness: Maybe<ReadinessView>;
  /**
   * 595 §15.2 (E4) — the backend's OWN signals that a generation rebuild/cutover is
   * stuck (`worker.migration`). Used only to ESCALATE a `rebuilding`/`generation-switch`
   * transition from calm "busy" to "warn" — closing the 593 §D/§F wedge (a wedged
   * rebuild otherwise looks calm forever). Projection only: the ~5 s status poll
   * refreshes the age, so no FE timer is needed.
   */
  readonly migrationPaused?: boolean;
  readonly migrationSwitchingAgeMs?: number;
  readonly migrationSwitchingMaxDurationMs?: number;
  /**
   * 649: is the backend reachable via ANY channel? Distinguishes "no poll has landed yet but the
   * origin is alive (streams beating)" — which should read "Connecting…", not a false "Backend
   * disconnected" — from a genuine unreachable origin (no contact of any kind within the window).
   */
  readonly reachableViaContact?: boolean;
}

/**
 * The ONE verdict. Total over its inputs; precedence: unreachable > transitioning
 * > checking(unknown) > degraded > operational. `transitioning` is ordered above
 * `degraded`/`operational` because a mid-build `indexHealthy` may read true while
 * the old generation serves (595 §9.1). Severity for a degradation comes from the
 * one reason→severity table (`severityForCodes`) so a cosmetic gap (LambdaMART)
 * cannot render with the same alarm as a broken index (595 §10.3).
 *
 * SCOPE (595 §15.3 / slice 456): this is *retrieval / search* health. The AI
 * (inference) component is intentionally EXCLUDED — AI is optional, offline-by-design
 * on fresh installs is not a system fault — and is surfaced by its own AI-Engine card.
 */
export function computeVerdict(i: VerdictInput): SystemHealthVerdict {
  if (i.phase === 'disconnected') {
    // 649: "disconnected" is the POLL-freshness verdict (no poll ever landed + grace elapsed). If the
    // backend is nonetheless reachable via another channel (streams heartbeating), it is alive but the
    // first poll hasn't arrived — that is "Connecting…", not a false "Backend disconnected". Only when
    // there is NO positive contact of any kind do we raise the unreachable alarm.
    if (i.reachableViaContact === true) {
      return { kind: 'connecting', severity: 'info', reasons: [] };
    }
    // Tempdoc 637 #1: carry an FE-derived reason code so the unreachable state words itself
    // through the CAUSE_ROWS vocabulary (a loud banner), instead of a silent empty result one
    // layer up. `binding.unreachable` is declared FE-derived in readiness-reason-codes.v1.json.
    return { kind: 'unreachable', severity: 'error', reasons: ['binding.unreachable'] };
  }
  if (i.stability.kind === 'provisional') {
    if (i.stability.cause === 'initial-load') {
      return { kind: 'connecting', severity: 'info', reasons: [] };
    }
    const cause = i.stability.cause;
    // E4: escalate a STUCK generation rebuild/cutover, using the backend's own
    // paused flag / age-vs-max-duration (the FE only projects them).
    if (cause === 'rebuilding' || cause === 'generation-switch') {
      const age = i.migrationSwitchingAgeMs ?? 0;
      const maxDur = i.migrationSwitchingMaxDurationMs ?? 0;
      const overdue = age > 0 && maxDur > 0 && age > maxDur;
      if (i.migrationPaused === true) {
        return { kind: 'transitioning', severity: 'warn', reasons: [cause, 'paused'] };
      }
      if (overdue) {
        return { kind: 'transitioning', severity: 'warn', reasons: [cause, 'overdue'] };
      }
    }
    // Tempdoc 649 — `channel-stale` is genuine lost contact (no poll AND no stream frame within the
    // window), worded "Reconnecting…". Unlike `updating` (reachable, data merely behind → calm "Catching
    // up…"), this is a connectivity problem the user should see as a WARNING, not calm. Severity 'warn'
    // gives the proper ramp: updating(busy→info) < channel-stale(warn→amber) < unreachable(error→red),
    // so the calm and the alarming in-flux states are visually distinct on every surface.
    if (cause === 'channel-stale') {
      return { kind: 'transitioning', severity: 'warn', reasons: [cause] };
    }
    return { kind: 'transitioning', severity: 'busy', reasons: [cause] };
  }
  // Settled: roll up the readiness axis.
  if (!i.readiness.known) return { kind: 'checking', severity: 'info', reasons: [] };
  const r = i.readiness.value;
  // Tempdoc 600 Design A: the reindex/compat cause is no longer a boolean shortcut — it arrives as
  // a real reason code on the `retrieval` composite (index.blocked_legacy / .schema_mismatch / …),
  // so it flows through the ONE degraded path below and is named by the CAUSE_ROWS vocabulary.
  // Tempdoc 627: a supervised Worker restart in flight surfaces `worker.recovering` on the retrieval
  // composite (alongside downstream consequences like index.not_healthy). Treat its presence as a calm
  // "Restarting…" transitioning state, not an alarming "Service degraded" — a routine self-heal should
  // not read as a failure. (`includes`, not sole-reason: the restart is the root, the rest is downstream.)
  if (r.retrieval === 'degraded' && r.reasonCodes.includes('worker.recovering')) {
    return { kind: 'transitioning', severity: 'busy', reasons: ['worker-restart'] };
  }
  if (r.retrieval === 'degraded') {
    return { kind: 'degraded', severity: severityForCodes(r.reasonCodes), reasons: r.reasonCodes };
  }
  if (r.retrieval === 'unknown') return { kind: 'checking', severity: 'info', reasons: [] };
  return { kind: 'operational', severity: 'ok', reasons: [] };
}

/** The ONE human headline for the verdict (Health badge + footer read this). */
export function verdictHeadline(v: SystemHealthVerdict): string {
  switch (v.kind) {
    case 'operational':
      return 'All systems operational';
    case 'checking':
      return 'Checking…';
    case 'connecting':
      return 'Connecting…';
    case 'unreachable':
      return 'Backend disconnected';
    case 'transitioning':
      // E4: a stuck rebuild/cutover escalates its wording (the reasons carry the flag).
      if (v.reasons.includes('paused')) return 'Rebuild paused';
      if (v.reasons.includes('overdue')) return 'Rebuilding… (taking longer than expected)';
      switch (v.reasons[0]) {
        case 'channel-stale':
          return 'Reconnecting…';
        case 'worker-restart':
          return 'Restarting…';
        case 'generation-switch':
          return 'Switching index…';
        case 'catching-up':
          return 'Catching up…';
        case 'updating':
          // 649: reachable but the poll is behind (e.g. starved under load). Calm — the backend is
          // alive, the view is merely catching up. Shares 562's reachable+stale vocabulary.
          return 'Catching up…';
        case 'rebuilding':
        default:
          return 'Rebuilding…';
      }
    case 'degraded':
      if (v.reasons.some(isReindexCause)) return 'Reindex required';
      // §10.3: a cosmetic degradation (info) must not read as a hard failure.
      return v.severity === 'info' ? 'Reduced capability' : 'Service degraded';
  }
}

/**
 * The ONE tone projection of severity (the Health badge reads this). Values are a
 * subset of `NoticeTone` ('neutral'|'info'|'success'|'warning'|'error'); a cosmetic
 * degradation / transition maps to the calm `info` tone, never the `warning` alarm.
 */
export function verdictTone(
  severity: Severity,
): 'success' | 'info' | 'warning' | 'error' {
  switch (severity) {
    case 'ok':
      return 'success';
    case 'info':
    case 'busy':
      return 'info';
    case 'warn':
      return 'warning';
    case 'error':
      return 'error';
  }
}

/** The ONE consequence sentence for the verdict (the Health footer reads this). */
export function verdictBody(v: SystemHealthVerdict): string {
  switch (v.kind) {
    case 'connecting':
      return 'System health is not available yet.';
    case 'checking':
      return 'Confirming retrieval status…';
    case 'unreachable':
      return 'The backend is not reachable. Showing last-known state.';
    case 'transitioning':
      if (v.reasons.includes('paused')) {
        return 'The index rebuild is paused — open Health to resume or investigate.';
      }
      if (v.reasons.includes('overdue')) {
        return 'The index rebuild is taking longer than expected — open Health to check.';
      }
      switch (v.reasons[0]) {
        case 'channel-stale':
          return 'Reconnecting to the backend; holding last-known values.';
        case 'worker-restart':
          return 'The knowledge server is restarting; counts will settle shortly.';
        case 'generation-switch':
          return 'Switching to the freshly-built index; this completes shortly.';
        case 'catching-up':
          return 'Re-checking your files for changes made while your computer was asleep.';
        case 'updating':
          // 649: honest, generic wording — the backend is busy/reachable and the view is behind, with
          // NO claim about sleep (distinct from `catching-up`) or a lost connection (not `channel-stale`).
          return 'The backend is busy; showing last-known values while the view catches up.';
        case 'rebuilding':
        default:
          return 'The index is being rebuilt; document counts and results will settle when it finishes.';
      }
    case 'degraded':
      if (v.reasons.some(isReindexCause)) {
        return 'A reindex is required to restore full search quality.';
      }
      return v.severity === 'info'
        ? 'An optional capability is unavailable; search still works.'
        : 'Retrieval is degraded. See recent events for detail.';
    case 'operational':
      return 'No recoverable conditions active.';
  }
}

export interface VerdictPresentation {
  readonly tone: ReturnType<typeof verdictTone>;
  readonly headline: string;
  readonly body: string;
  /**
   * The screen-reader announcement of a verdict CHANGE (595 §15.1 / E1). `text` is
   * the concise headline (WCAG 4.1.3 — brief); `politeness` maps to the ONE
   * live-region authority (`<jf-system-notice live>`): `alert` (assertive) only for
   * an error verdict, else `status` (polite).
   */
  readonly announce: { readonly text: string; readonly politeness: 'status' | 'alert' };
}

/**
 * The ONE verdict-presentation projection (595 §15.1). Every surface CONSUMES this —
 * the Health badge (tone + headline), the footer (headline + body), the status bar
 * (headline for the provisional kinds, via computeStatusLabel), and the a11y
 * announcer (announce). It mirrors 557's `present()` display projector, extended
 * from the verdict's VALUE to its PRESENTATION, so the wording lives in one place.
 */
export function presentVerdict(v: SystemHealthVerdict): VerdictPresentation {
  const headline = verdictHeadline(v);
  return {
    tone: verdictTone(v.severity),
    headline,
    body: verdictBody(v),
    announce: { text: headline, politeness: v.severity === 'error' ? 'alert' : 'status' },
  };
}
