// SPDX-License-Identifier: Apache-2.0
/**
 * messageRouting — tempdoc 613 §3–§6 / §10: the push-event routing model, in its present,
 * non-speculative form.
 *
 * §7 records what this design REJECTS: a UNIVERSAL seam that forces every receipt/toast/advisory
 * through one `f` (Alt-C). Those surfaces already have single authorities — the in-control receipt
 * (Control), the toast + the standing advisory (AdvisoryToastHost / AdvisoryInboxDrawer) — and the
 * horizon→{toast | inbox} choice is ALREADY a derivation from `RenderHint`. Forcing them together
 * would be the over-DRY the AHA principle forbids ("only unify what shares a reason to change").
 *
 * The ONE routing decision with present, multi-surface value is INCLUSION-IN-ACTIVITY (§6, §10):
 * a routine direct-user action the user already caused AND witnessed — navigation — is a transient
 * acknowledgement. It belongs in a toast, NOT as a durable Activity history row. (This is the §10
 * navigation-flood: the Activity feed read `user (494)` vs `system (6)`, drowning the events the
 * user opened Activity to see.) This module is that derivation: classify a unified-activity row's
 * MEANING so the default Activity projection can route routine rows out of the curated feed — while
 * the complete ledger stays reachable in the full view (612's "curated projection, not a second
 * store"). The trigger/horizon facets it reads already exist (the ledger's `originator` + `kind`);
 * the predicate over them is owned here, the projection that applies it is owned by 612.
 */
import { readinessNotice } from './readinessNotice.js';
import type { SystemHealthVerdict } from './verdict.js';
import type { Effect } from '../substrates/effect.js';

/**
 * Tempdoc 613 §3/§6 — the routing derivation `f`: a push message's SURFACE is a function of its
 * meaning, not a per-site choice. The load-bearing facet is `locality`: a direct synchronous ack on
 * the control you just touched (`at-control` — copy/export confirmations) is a RECEIPT (in-element
 * flash, see {@link ReceiptController}); a window/system-scoped ack the user may not be looking at
 * (`window` — navigation, "AI is offline", "Index ready") is a TOAST. The altitude rule "a receipt is
 * not a window toast" is an INVARIANT of this function — it cannot resolve `at-control` to `toast`.
 *
 * (The standing-advisory and Activity surfaces are NOT FE-local-emitted today, so `f` deliberately
 * does not enumerate them here — §7 "encode a facet when a second consumer needs it"; Activity
 * routing is {@link isRoutineActivity}.)
 */
export type PushLocality = 'at-control' | 'window';
export type PushSurface = 'receipt' | 'toast';

export function routePushSurface(locality: PushLocality): PushSurface {
  return locality === 'at-control' ? 'receipt' : 'toast';
}

/** The meaning facets the Activity-inclusion decision reads (612's axis). */
export interface ActivityMeaning {
  /** Trigger: who acted. */
  readonly originator: 'user' | 'agent' | 'system';
  /**
   * A routine direct-user action the user already caused & witnessed (navigation or a local-ack /
   * preference effect) — a transient ack, not history. The default Activity feed excludes these;
   * they remain in the full view.
   */
  readonly routine: boolean;
}

/**
 * Tempdoc 612 §3/§L — every FE {@link Effect} kind classified for Activity curation. `routine` = a
 * witnessed local acknowledgement the user caused AND saw, with no durable-system or audit weight
 * (navigation, pane/modal/selection/scroll/focus chrome, preference toggles, copy, a transient toast).
 * `foreground` = carries weight (durable state change, an undo, a failure) and stays in the curated feed
 * even when user-originated. A closed `Record` over the `Effect` union, so a NEW effect kind fails to
 * compile until deliberately classified here — the §L.5 lesson that an unclassified kind (`save-settings`)
 * silently floods or hides.
 */
const EFFECT_ACTIVITY_CLASS: Record<Effect['kind'], 'routine' | 'foreground'> = {
  navigate: 'routine',
  'open-pane': 'routine',
  'close-pane': 'routine',
  'open-modal': 'routine',
  'close-modal': 'routine',
  'set-selection': 'routine',
  'clear-selection': 'routine',
  'focus-element': 'routine',
  'scroll-to': 'routine',
  'copy-to-clipboard': 'routine',
  'set-form-value': 'routine',
  'set-search-query': 'routine',
  'set-search-filter': 'routine',
  'set-appearance': 'routine',
  'set-ui-mode': 'routine',
  // §L.5 R1: a `save-settings` payload carries only UI preferences today (the settings POST is
  // ledger-silent; security state lives in grants/operations, not here) — routine, with the forward
  // governance caveat logged to docs/observations.md. Revisit if a security key is ever persisted here.
  'save-settings': 'routine',
  toast: 'routine',
  noop: 'routine',
  // Weight-bearing — durable/system state change, an undo, or a data failure: stays foreground.
  'apply-presentation': 'foreground',
  'invoke-operation': 'foreground', // also becomes an authoritative operation row downstream
  'undo-operation': 'foreground',
  'data-result': 'foreground',
  'data-error': 'foreground',
};

function isRoutineEffectKind(effectKind: string | undefined): boolean {
  if (effectKind === undefined) return false;
  return EFFECT_ACTIVITY_CLASS[effectKind as Effect['kind']] === 'routine';
}

/**
 * Is a unified-activity row a ROUTINE, already-witnessed direct-user action? Reads the ledger's existing
 * facets: `kind` (+ the FE-effect `effectKind`) and `originator`. Routine = a backend navigation row, OR
 * a witnessed local-ack / preference EFFECT (see {@link EFFECT_ACTIVITY_CLASS}). Only DIRECT-USER rows can
 * be routine — agent/system rows explain background effects and stay (612: approvals/denials/causal chains
 * remain visible). OPERATION rows are graded separately by their declared significance
 * ({@link isRoutineOperation}); this predicate never routes an operation.
 */
export function isRoutineActivity(kind: string, originator: string, effectKind?: string): boolean {
  if (originator !== 'user') return false;
  if (kind === 'navigation') return true; // backend navigation row
  // An ingested FE effect row carries the real kind in `effectKind`; a raw FE journal row carries it as
  // `kind` itself. Either way, route by the effect-kind classification.
  return isRoutineEffectKind(kind === 'effect' ? effectKind : kind);
}

/** Declared-significance facets of an Operation (resolved from the OperationCatalog at the projection site). */
export interface OperationSignificance {
  readonly risk: string; // RiskTier: LOW | MEDIUM | HIGH
  readonly confirmKind: string; // NONE | INLINE | TYPED
  readonly audit: string; // NONE | METADATA_ONLY | FULL_PAYLOAD
  readonly affectsCount: number;
}

/**
 * Tempdoc 612 §3 — is a direct-user OPERATION row routine (insignificant) by its DECLARED facets? An
 * operation is routine only when it is read-only-ish and accountability-free: LOW risk, no confirmation,
 * not fully audited, and mutating no Resource. Anything destructive / confirmed / fully-audited /
 * Resource-mutating is causal or audit-relevant and stays foreground (the "user did X → system did Y"
 * chain). The caller resolves the facets from the registry and applies the user-originator + non-FAILURE
 * guards; this predicate is pure over the facet bundle.
 */
export function isRoutineOperation(s: OperationSignificance): boolean {
  return (
    s.risk === 'LOW' &&
    s.confirmKind === 'NONE' &&
    s.audit !== 'FULL_PAYLOAD' &&
    s.affectsCount === 0
  );
}

/**
 * Tempdoc 613 §6 R-3 — "don't push what's already pulled". A PUSH transient (toast) that would
 * RE-STATE a degradation cause is a redundant double-surface when the PULL degradation banner
 * already states it. The banner is up exactly when `readinessNotice(verdict)` mints a notice — the
 * ONE shared signal both surfaces read (the 595 verdict + the readinessNotice cause vocabulary), so
 * a cause-push and the banner cannot disagree about whether the cause is visible. A cause-push site
 * suppresses itself when this returns true.
 */
export function causePushSuppressedByBanner(verdict: SystemHealthVerdict): boolean {
  return readinessNotice(verdict) !== null;
}
