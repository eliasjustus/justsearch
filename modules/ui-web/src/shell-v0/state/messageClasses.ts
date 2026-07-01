// SPDX-License-Identifier: Apache-2.0
/**
 * messageClasses — tempdoc 613 §5.2/§8: the ONE closed vocabulary of FE-LOCAL message classes.
 *
 * The `emitEphemeralToast` channel (559 Authority III) previously took a free-form `classId`
 * string, so the routing-relevant policy of a local transient (does it supersede? what tone?) was
 * chosen ad hoc per call site and the class set could drift. This module closes that:
 *
 *  - **Collapse** — `EphemeralToastSpec.classId` is typed as {@link LocalMessageClass} (the keys
 *    below), so an undeclared classId fails to typecheck; a free-form local class is unrepresentable.
 *  - **Declared policy** — each class declares its policy once (the load-bearing axes are
 *    `supersede` and `defaultSeverity`; the local toast channel is always `EPHEMERAL`). The emit
 *    helper resolves a call's `supersede`/`severity` from the class, so the class — not the call
 *    site — is the single source for that behavior.
 *  - **Gate** — `scripts/ci/check-message-classes.mjs` (register
 *    `governance/message-classes.v1.json`) enforces forward+backward correspondence between the
 *    classes declared here and the classes actually emitted, the proven 602 §D.0 pattern.
 *
 * NOTE — scope: this is the FE-LOCAL EMIT vocabulary only. The WIRE `AdvisoryEvent.classId` stays a
 * `string` (it also carries backend advisory classes — `operation.completed`, `health.recoverable`,
 * … — whose chrome lives in `AdvisoryClassChrome.ts`). Widening a `LocalMessageClass` into that
 * string field is safe.
 */
import type { MessageSeverity } from '../components/advisory/ephemeralToast.js';
import type { NoticeTone, NoticeLive } from '../components/SystemNotice.js';

export interface LocalMessageClassPolicy {
  /** The local toast channel is always EPHEMERAL (toast-only, no inbox/unread). */
  readonly renderHint: 'EPHEMERAL';
  /**
   * Single-occupancy: a superseding emit drops any live toast of the SAME class (tempdoc 602 R4).
   * `true` for recurring same-class transients (navigation) where only the latest matters.
   */
  readonly supersede: boolean;
  /** Tone applied when a call omits `severity`. Omitted ⇒ the caller supplies the tone per message. */
  readonly defaultSeverity?: MessageSeverity;
  /**
   * Tempdoc 613 §4/§5.1 — the routing facet `f` (`routePushSurface`) reads. Every class on the TOAST
   * channel is `'window'`. An `'at-control'` class is a RECEIPT (in-control flash, ReceiptController)
   * and must NOT travel this channel — `emitEphemeralToast` rejects it, so "a receipt is not a window
   * toast" is structural, not a convention.
   */
  readonly locality: 'window' | 'at-control';
}

/**
 * The closed set. Adding a local message class is one row here (the gate's coverage extends
 * automatically); a class with no row is unrepresentable at the emit site (the union type).
 */
export const LOCAL_MESSAGE_CLASSES = {
  // The generic catch-all: unrelated one-off window notices. No supersede (they must not collapse
  // into each other); tone is per-message.
  'core.ephemeral': { renderHint: 'EPHEMERAL', supersede: false, locality: 'window' },
  // Surface navigation breadcrumb — only the latest matters, so it supersedes its own class.
  'core.navigation': { renderHint: 'EPHEMERAL', supersede: true, defaultSeverity: 'info', locality: 'window' },
  // "Index ready — all systems operational" settle announcement (595/StatusDeck).
  'core.verdict.settled': {
    renderHint: 'EPHEMERAL',
    supersede: false,
    defaultSeverity: 'success',
    locality: 'window',
  },
  // Tempdoc 609 §R (T1.4) — ambient "Draft kept" reassurance when leaving a surface with an unsaved
  // draft (instance-retention keeps it). Supersede: only the latest matters; never stacks.
  'core.draft-kept': {
    renderHint: 'EPHEMERAL',
    supersede: true,
    defaultSeverity: 'info',
    locality: 'window',
  },
  // Tempdoc 663 Design pass 3 — "AI install complete" one-shot toast (mirrors `core.verdict.settled`'s
  // exact policy shape for the AI-engine axis).
  'core.ai-engine.settled': {
    renderHint: 'EPHEMERAL',
    supersede: false,
    defaultSeverity: 'success',
    locality: 'window',
  },
  // Tempdoc 663 Design pass 3 — "AI install failed" one-shot toast. Supersede: a repeated failure
  // notice about the same ongoing problem replaces rather than stacks.
  'core.ai-engine.failed': {
    renderHint: 'EPHEMERAL',
    supersede: true,
    defaultSeverity: 'error',
    locality: 'window',
  },
} as const satisfies Record<string, LocalMessageClassPolicy>;

export type LocalMessageClass = keyof typeof LOCAL_MESSAGE_CLASSES;

/** The class applied when an emit omits `classId` (the generic catch-all). Gate-exempt from BACKWARD. */
export const DEFAULT_MESSAGE_CLASS: LocalMessageClass = 'core.ephemeral';

export function policyFor(classId: LocalMessageClass): LocalMessageClassPolicy {
  return LOCAL_MESSAGE_CLASSES[classId];
}

/**
 * Tempdoc 613 §14 — the toast's rendered TREATMENT is a projection of its declared `severity`, not a
 * render-site literal. The toast host previously hardcoded its dwell (`TOAST_DURATION_MS`, fixed) and keyed
 * its announcement politeness on `renderHint` (not severity), so a local ERROR toast announced *politely*
 * and auto-vanished in 5s — the NN/g "a toast is a bad way to show an error" anti-pattern. This one
 * derivation makes that unrepresentable: error/warning announce assertively (`alert`); an error persists
 * (sticky) until the user dismisses it. The `tone` axis absorbs the former `severityToTone`.
 *
 * Reuses the 559 `<jf-system-notice tone live>` seam ({@link NoticeTone}/{@link NoticeLive} → colour +
 * role/aria-live); this is the *which value* policy, the notice owns the *how it renders*. The sibling
 * mappers `toneClassToNotice` (input: stream-chrome toneClass) and `verdictTone` (input: verdict) are
 * different input domains and stay where they are — this is the severity→presentation projection only.
 */
export interface SeverityPresentation {
  /** Severity → the 559 NoticeTone (left-accent colour token). */
  readonly tone: NoticeTone;
  /** Severity → the 559 NoticeLive (role + aria-live): error/warning announce assertively. */
  readonly live: NoticeLive;
  /** Error stays until dismissed (no auto-dismiss timer) — an error must not silently auto-vanish. */
  readonly sticky: boolean;
}

export function presentationForSeverity(sev: MessageSeverity | undefined): SeverityPresentation {
  switch (sev) {
    case 'error':
      return { tone: 'error', live: 'alert', sticky: true };
    case 'warning':
      return { tone: 'warning', live: 'alert', sticky: false };
    case 'success':
      return { tone: 'success', live: 'status', sticky: false };
    default:
      // 'info' and unset both read as a neutral, polite, auto-dismissing notice.
      return { tone: 'neutral', live: 'status', sticky: false };
  }
}
