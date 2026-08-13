// SPDX-License-Identifier: Apache-2.0
/**
 * route — the destination of a draft, as a pure function of visible facts (tempdoc 818 slice 1).
 *
 * Ported verbatim in semantics from the 818 prototype (`docs/tempdocs/818-prototype/index3.html`,
 * the `RUNGS` / `QWORD` / `route()` block). The laws this file exists to make testable:
 *
 *  - **L1** the pill is a PURE function of (draft, ctx) — the same inputs always name the same
 *    destination. The ⇥ flip is a one-shot LENS applied on top ({@link applyFlip}); it is never a
 *    stored destination, which is why it cannot desync from the schema the way a stored mode did.
 *  - **L2** a run in flight claims the ALT slot (steer) and nothing else — the primary destination
 *    is unaffected, so ASK stays reachable mid-run.
 *  - **L10** an empty draft routes NOWHERE: `{ empty: true }`, and the pill previews dimmed.
 */

/** The destination register — the rungs a draft can land on, with their pill copy. */
export type Rung = 'search' | 'ask' | 'extract' | 'agent' | 'chat' | 'steer' | 'workflow';

export interface RungSpec {
  readonly pill: string;
  readonly label: string;
}

// The labels are what a reader sees on the pill's tooltip, so they are written in the reader's
// terms: what this destination will DO with the text, never what the design calls it (818 slice 5's
// copy pass — "rung", "floor" and "chip" are words for the tempdoc, not for the window).
export const RUNGS: Readonly<Record<Rung, RungSpec>> = Object.freeze({
  search: { pill: 'SEARCH', label: 'Searches your files as you type' },
  ask: { pill: 'ASK', label: 'Answers from your files, with sources' },
  extract: { pill: 'EXTRACT', label: 'Pulls the attached fields out of your files' },
  agent: { pill: 'DELEGATE', label: 'Hands the task to the agent — you can watch it and stop it' },
  chat: { pill: 'CHAT', label: 'Answers without looking at your files' },
  steer: { pill: 'STEER', label: 'Redirects the run that is going now' },
  workflow: { pill: 'WORKFLOW', label: 'Runs a saved routine as a delegated task' },
});

/** The visible facts routing reads. Nothing else may influence the destination. */
export interface RouteContext {
  /** A scope chip narrows every destination and changes none (L3). */
  readonly scopePinned: boolean;
  readonly schemaAttached: boolean;
  readonly runInFlight: boolean;
}

export interface RoutedDraft {
  readonly empty: false;
  readonly primary: Rung;
  readonly alt: Rung;
}

export interface EmptyDraft {
  readonly empty: true;
}

export type RouteResult = RoutedDraft | EmptyDraft;

/** Question-shape detector — a leading question word or a trailing '?' reads as a question. */
export const QWORD =
  /^(what|who|when|where|why|how|which|does|do|did|is|are|was|were|can|could|should|would|will)\b/i;

/** L1/L2/L10 — the one routing function. Pure: same (draft, ctx) ⇒ same result, always. */
export function route(draft: string, ctx: RouteContext): RouteResult {
  const d = draft.trim();
  if (!d.length) return { empty: true };
  const q = QWORD.test(d) || d.endsWith('?');
  const primary: Rung = ctx.schemaAttached ? 'extract' : q ? 'ask' : 'search';
  const alt: Rung = ctx.runInFlight ? 'steer' : primary === 'search' ? 'ask' : 'search';
  return { empty: false, primary, alt };
}

/**
 * L1 — the ⇥ flip as a LENS over a routed draft: it swaps the two derived slots for this draft
 * only. It stores nothing, so Escape (or a new draft, or a commit) simply stops applying it.
 */
export function applyFlip(routed: RoutedDraft, flipped: boolean): RoutedDraft {
  return flipped ? { empty: false, primary: routed.alt, alt: routed.primary } : routed;
}
