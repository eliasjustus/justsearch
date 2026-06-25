// SPDX-License-Identifier: Apache-2.0
/**
 * PendingEffect substrate — Tempdoc 543 §14.4 / §21.E.
 *
 * Implements the "propose → review → accept/reject" pattern that the
 * field has converged on (Cursor, Continue.dev, Copilot Workspace,
 * Anthropic Operator — see §22.5.5). Without this substrate, AI-
 * suggested actions either dispatch silently (unsafe) or use ad-hoc
 * per-call confirm dialogs (inconsistent).
 *
 * Lifecycle:
 *   1. `proposeEffect(effect, invokedBy, originator)` registers the
 *      Effect as a Pending and returns a `PendingId`. The Effect is
 *      NOT dispatched yet; nothing renders to chrome via applyEffect.
 *   2. Chrome (PendingEffectQueue) renders the Pending and offers
 *      accept / reject affordances.
 *   3. `acceptPending(id)` dispatches via the supplied applyFn (the
 *      kernel's applyEffect) and records a JournalEntry with
 *      `pendingOutcome: 'accepted'`.
 *   4. `rejectPending(id)` drops the Pending without dispatching, and
 *      records a JournalEntry tagged `pendingOutcome: 'rejected'` so
 *      audit views can see the vetoed proposal.
 *
 * The substrate does NOT couple to chrome — the apply dispatcher is
 * injected at accept time. This keeps the substrate testable headless
 * and allows alternative apply policies (dry-run, snapshot-and-replay).
 */

import type { Effect } from '../effect.js';
import type { Provenance } from '../../primitives/provenance.js';
import {
  recordEffect,
  type EffectOriginator,
  type JournalEntry,
} from '../effects/index.js';
// §25.α5 — shared notify primitive.
import { notifyAllWith } from '../../primitives/notify.js';

export type PendingId = number;

export interface PendingEffect {
  readonly id: PendingId;
  readonly effect: Effect;
  readonly invokedBy: Provenance;
  readonly originator: EffectOriginator;
  readonly proposedAt: string;
  /** Optional human-readable rationale shown alongside the proposal. */
  readonly rationale?: string;
  /**
   * §32 R-E2 — optional agent self-assessed confidence in [0,1]. The
   * PendingEffect queue surfaces it (low-confidence proposals sort first
   * and render a warning) so the user scrutinises uncertain agent actions.
   */
  readonly confidence?: number;
}

const _pending = new Map<PendingId, PendingEffect>();
const _listeners = new Set<(event: PendingEvent) => void>();
let _nextId: PendingId = 1;

export type PendingEvent =
  | { readonly kind: 'proposed'; readonly pending: PendingEffect }
  | {
      readonly kind: 'accepted';
      readonly pending: PendingEffect;
      readonly journalEntry: JournalEntry;
    }
  | { readonly kind: 'rejected'; readonly pending: PendingEffect };

function notify(event: PendingEvent): void {
  notifyAllWith(_listeners, event);
}

export interface ProposeEffectOptions {
  readonly rationale?: string;
  /** §32 R-E2 — agent self-assessed confidence in [0,1]. */
  readonly confidence?: number;
}

/**
 * Register a Pending proposal. Returns the assigned id. The Effect is
 * NOT dispatched; chrome must drive `acceptPending(id)` to apply it.
 */
export function proposeEffect(
  effect: Effect,
  invokedBy: Provenance,
  originator: EffectOriginator = 'agent',
  opts: ProposeEffectOptions = {},
): PendingId {
  const id = _nextId++;
  const pending: PendingEffect = {
    id,
    effect,
    invokedBy,
    originator,
    proposedAt: new Date().toISOString(),
    ...(opts.rationale !== undefined ? { rationale: opts.rationale } : {}),
    ...(opts.confidence !== undefined ? { confidence: opts.confidence } : {}),
  };
  _pending.set(id, pending);
  notify({ kind: 'proposed', pending });
  return id;
}

/**
 * Accept a pending proposal — dispatches the Effect via applyFn (the
 * kernel's applyEffect) AND records a JournalEntry tagged
 * `pendingOutcome: 'accepted'`. Returns the JournalEntry, or null when
 * the id is unknown / already consumed.
 *
 * The applyFn injection keeps the substrate decoupled from chrome.
 * Production callers thread `applyEffect` from substrates/actions/.
 */
export function acceptPending(
  id: PendingId,
  applyFn: (effect: Effect, invokedBy: Provenance) => void,
  causation?: number,
): JournalEntry | null {
  const pending = _pending.get(id);
  if (pending === undefined) return null;
  _pending.delete(id);
  // applyFn dispatches AND records its own JournalEntry via applyEffect.
  // We then add a SECOND entry marked 'accepted' so the audit log shows
  // the lifecycle event. Alternative considered: pass an outcome flag
  // into applyEffect/recordEffect — rejected because PendingEffect is
  // a substrate that should layer ON TOP of recordEffect, not require
  // a recordEffect signature flag for an orthogonal concern.
  // §32 R-E3: optional `causation` chains sequence-accepted entries into
  // the journal DAG (513 lazy-pointer pattern) so consumers can answer
  // "what caused this?".
  //
  // §32 R-P3 (S10 analysis): the two entries are INTENTIONAL, not a duplicate
  // bug. The applyFn entry records WHAT happened (the effect, attributed to
  // applyEffect's default 'user' originator); THIS marker records the pending
  // LIFECYCLE outcome ('accepted', attributed to the pending's originator).
  // The split is LOAD-BEARING: §32 U3 summarizeAgentActivity counts 'agent'
  // entries and relies on the applyFn dispatch NOT being 'agent', so an
  // accepted agent pending counts once (the marker), not twice. Do NOT
  // "dedup" by re-attributing the dispatch entry to the pending's originator.
  applyFn(pending.effect, pending.invokedBy);
  const journalEntry = recordEffect(pending.effect, pending.invokedBy, {
    originator: pending.originator,
    pendingOutcome: 'accepted',
    ...(causation !== undefined ? { causation } : {}),
  });
  notify({ kind: 'accepted', pending, journalEntry });
  return journalEntry;
}

/**
 * Reject a pending proposal — drops it without dispatching. Records a
 * JournalEntry tagged `pendingOutcome: 'rejected'` so audit views can
 * see the vetoed proposal (the inverse derivation runs but the Effect
 * never actually rendered; the entry's presence reflects user intent).
 */
export function rejectPending(id: PendingId): boolean {
  const pending = _pending.get(id);
  if (pending === undefined) return false;
  _pending.delete(id);
  recordEffect(pending.effect, pending.invokedBy, {
    originator: pending.originator,
    pendingOutcome: 'rejected',
  });
  notify({ kind: 'rejected', pending });
  return true;
}

export function listPending(): readonly PendingEffect[] {
  return Array.from(_pending.values());
}

export function getPending(id: PendingId): PendingEffect | undefined {
  return _pending.get(id);
}

export function getPendingCount(): number {
  return _pending.size;
}

export function subscribePending(
  listener: (event: PendingEvent) => void,
): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

// ============================================================
// §25.δ6 — Effect[] composition (sequence-level Pending)
// ============================================================

/**
 * Group several proposed Effects under a single approval queue entry.
 * Real-world UX (Cursor, Continue.dev) groups related changes so the
 * user accepts/rejects them together — accepting half a refactor is
 * usually worse than accepting none of it.
 *
 * `proposeEffectSequence` registers a sequence id and N child Pending
 * ids. `acceptSequence(seqId, applyFn)` dispatches all in registration
 * order, recording each. `rejectSequence(seqId)` drops all, recording
 * each as rejected.
 *
 * Atomicity: per-effect; a handler error during accept aborts the
 * remaining and records the abort. Macro-style "all or nothing" with
 * rollback is intentionally out of scope (it needs every effect to
 * carry a non-null inverse, which set-selection / focus / etc. don't
 * guarantee).
 */
export type PendingSequenceId = number;
const _sequences = new Map<PendingSequenceId, ReadonlyArray<PendingId>>();
let _nextSeqId = 1;

export interface ProposeSequenceItem {
  readonly effect: Effect;
  readonly originator?: EffectOriginator;
  readonly rationale?: string;
}

export function proposeEffectSequence(
  items: ReadonlyArray<ProposeSequenceItem>,
  invokedBy: Provenance,
): { sequenceId: PendingSequenceId; pendingIds: ReadonlyArray<PendingId> } {
  const seqId = _nextSeqId++;
  const pendingIds: PendingId[] = [];
  for (const item of items) {
    const opts: ProposeEffectOptions = {};
    if (item.rationale !== undefined) (opts as { rationale: string }).rationale = item.rationale;
    pendingIds.push(
      proposeEffect(
        item.effect,
        invokedBy,
        item.originator ?? 'agent',
        opts,
      ),
    );
  }
  _sequences.set(seqId, pendingIds);
  return { sequenceId: seqId, pendingIds };
}

/**
 * Accept every Pending in the sequence in order. Returns the count of
 * accepted entries. Stops if applyFn throws — remaining stay pending,
 * caller can retry / reject.
 */
export function acceptSequence(
  seqId: PendingSequenceId,
  applyFn: (effect: Effect, invokedBy: Provenance) => void,
): number {
  const ids = _sequences.get(seqId);
  if (!ids) return 0;
  let accepted = 0;
  // §32 R-E3 — chain the accepted entries via `causation` so the journal
  // records the sequence as a DAG (each step caused by the previous),
  // not N unrelated entries. Powers "what caused this?" in the audit log.
  let prevEntryId: number | undefined;
  for (const id of ids) {
    if (!_pending.has(id)) continue;
    const entry = acceptPending(id, applyFn, prevEntryId);
    if (entry === null) continue;
    prevEntryId = entry.id;
    accepted++;
  }
  _sequences.delete(seqId);
  return accepted;
}

/** Reject every Pending in the sequence. Returns the count rejected. */
export function rejectSequence(seqId: PendingSequenceId): number {
  const ids = _sequences.get(seqId);
  if (!ids) return 0;
  let rejected = 0;
  for (const id of ids) {
    if (rejectPending(id)) rejected++;
  }
  _sequences.delete(seqId);
  return rejected;
}

export function listPendingSequences(): readonly PendingSequenceId[] {
  return Array.from(_sequences.keys());
}

export function getSequenceMembers(
  seqId: PendingSequenceId,
): ReadonlyArray<PendingId> | undefined {
  return _sequences.get(seqId);
}

/** Test-only reset. */
export function __resetPendingForTest(): void {
  _pending.clear();
  _sequences.clear();
  _listeners.clear();
  _nextId = 1;
  _nextSeqId = 1;
}
