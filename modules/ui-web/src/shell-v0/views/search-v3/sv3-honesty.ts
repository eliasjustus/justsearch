// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-honesty — the Search v3 window's honesty derivations (tempdoc 822 Phase F7).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The three facts this window must not get wrong, each derived ONCE here and rendered nowhere else:
 * whether the conversation store is readable at all, what an answer actually stood on, and how many
 * documents there are to stand on. Everything is PURE — a snapshot and a turn arrive as arguments —
 * so each can be tested without a DOM, and so no render site can re-derive one of them differently.
 *
 * Nothing here authors a VERDICT of its own: the frame and its wording come from the shared
 * `components/chat/evidenceProjection.ts` authority (tempdoc 559 Authority IV / 577 Move 3), the same
 * one the shipped window reads. This module supplies only the window's inputs to it.
 */
import type { AiState } from '../../state/aiStateStore.js';
// The ONE answer-frame authority. The window derives no grounding verdict and words none: it hands
// over (shape × sources × coverage) and renders what comes back (`views/UnifiedChatView.ts:4974`
// does the same through its own `frameFor`).
import {
  answerFrame,
  answerFrameLabel,
  groundingCoverage,
  groundingDegraded,
  sourcesAreChunkPrecise,
} from '../../components/chat/evidenceProjection.js';
import { SV3_ASK_SHAPE_ID } from './sv3-ask.js';
import type { Sv3Turn } from './sv3-sessions.js';

/* ── The one remedy channel ──────────────────────────────────────────────────────────────────── */

/**
 * Raised by whichever region is showing a remedy the reader took — the locked transcript's "Unlock in
 * Security", the empty corpus's "Add folders in Library". ONE event and ONE handler in the window,
 * because two regions reaching the app's navigation authority themselves would be two places this
 * window can decide where the reader goes (596 §11.4's remedy nav, kept to a single exit).
 */
export const SV3_REMEDY = 'sv3-remedy';

export interface Sv3RemedyDetail {
  /** The surface that OWNS the fix — never one hop short of it. */
  readonly target: string;
}

/* ── The lock (inventory E5) ─────────────────────────────────────────────────────────────────── */

/**
 * Is the conversation store locked, as of this observed-state snapshot?
 *
 * TRI-STATE ON PURPOSE (tempdoc 734, the mechanism at `views/UnifiedChatView.ts:1038-1048`): the
 * poll reports `locked` or `unlocked`, and anything else — a snapshot with no status yet, a field an
 * older backend omits — is NOT "unlocked", it is *not said*. Collapsing the unknown into `false`
 * would unlock the view on the first status frame that happened to omit the field. Before 734 the
 * flag was written once from the initial 423, so a lock taken elsewhere (an idle auto-lock, another
 * tab) left the transcript readable forever; reading it from every snapshot is what closes that, at
 * the poll's own ~10s bound.
 */
export function deriveSv3HistoryLocked(previous: boolean, snapshot: AiState | null): boolean {
  const state = snapshot?.status?.conversationProtection?.state;
  if (state === 'locked') return true;
  if (state === 'unlocked') return false;
  return previous;
}

/* ── The corpus (inventory E10) ──────────────────────────────────────────────────────────────── */

/**
 * How many documents the next question can be answered from — or that the window does not yet know.
 *
 * THREE states, where the shipped landing has two (`views/UnifiedChatView.ts:3021-3039` treats a
 * `null` count exactly like a real `0` and offers the remedy either way). A count that has not been
 * reported yet is not a corpus of zero: telling a reader to add folders when the window simply has
 * no settled poll would be the same over-claim in the other direction from the one tempdoc 811 C-4
 * fixed. `unknown` therefore says nothing at all.
 */
export type Sv3Corpus =
  | { readonly kind: 'unknown' }
  | { readonly kind: 'documents'; readonly count: number }
  | { readonly kind: 'empty' };

export const SV3_CORPUS_UNKNOWN: Sv3Corpus = { kind: 'unknown' };

/**
 * 811 C-4 — `searchableDocumentCount` is the default-search-scope population and is the number the
 * reader's next question will actually run against; `documentCount` is the fallback for a backend
 * that does not report it. A reported `0` is real and gets the remedy.
 */
export function projectSv3Corpus(snapshot: AiState | null): Sv3Corpus {
  const index = snapshot?.lastSettledIndex;
  if (index == null) return SV3_CORPUS_UNKNOWN;
  const count = index.searchableDocumentCount ?? index.documentCount;
  if (typeof count !== 'number') return SV3_CORPUS_UNKNOWN;
  return count > 0 ? { kind: 'documents', count } : { kind: 'empty' };
}

/* ── The answer frame (inventory C1) ─────────────────────────────────────────────────────────── */

/**
 * The quiet receipt tail: how long it took, and which model produced it. Ported from the shipped
 * window's `formatReceiptTail` (`views/UnifiedChatView.ts:5003-5017`) — the same thresholds, so a
 * duration does not read one way in one window and another way in the next.
 *
 * NEVER FABRICATED: each part is omitted when the window was not told it, which is why both
 * arguments are nullable rather than defaulted. A negative duration (a clock that moved backwards) is
 * dropped for the same reason.
 */
export function sv3ReceiptTail(durationMs: number | null, modelLabel: string | null): string {
  const parts: string[] = [];
  if (durationMs !== null && durationMs >= 0) {
    parts.push(durationMs >= 1000 ? `${(durationMs / 1000).toFixed(1)} s` : `${durationMs} ms`);
  }
  if (modelLabel !== null && modelLabel !== '') parts.push(modelLabel);
  return parts.join(' · ');
}

/**
 * The answer's honest frame line: what it is based on, how long it took, which model wrote it.
 *
 * 810 §T-B singles this out as owner-valued credit worth preserving — *"Based on your documents —
 * per-sentence grounding not verified · 45.7 s · Qwen_Qwen3.5-9B"* — and the whole line is derived,
 * never composed from a template with a hopeful value in it:
 *
 *  - the LABEL is the shared authority's, taken from the shape's declared grounding class refined by
 *    what this run actually produced. It is `null` for a fully-grounded answer, because the inline
 *    marks already say so and a banner repeating them would be the twice-rendered fact 814 §D5 bans.
 *  - the TAIL is the turn's own measured duration and the model recorded AT ITS TERMINAL, not the
 *    model that happens to be loaded when the transcript is re-read.
 *
 * A turn the backend never sent retrieval evidence for gets NO label: `evidence === null` is "never
 * told", and framing it as "searched your documents and found nothing to cite" would be inventing a
 * search that may not have happened. Its tail still renders — the duration is measured, not reported.
 */
export interface Sv3AnswerFrame {
  /** The shared authority's wording, or `null` when a fully-grounded answer needs no line. */
  readonly label: string | null;
  /** `"45.7 s · Qwen3"`, or `''` when neither part was known. */
  readonly tail: string;
}

export function projectSv3AnswerFrame(turn: Sv3Turn): Sv3AnswerFrame | null {
  // Only a COMPLETED ask carries a frame. A halted, refused or failed turn has its own note saying
  // what became of it, and framing the grounding of an answer that never landed would be a claim
  // about text the reader does not have.
  if (turn.kind !== 'ask' || turn.status !== 'complete') return null;
  const tail = sv3ReceiptTail(turn.durationMs, turn.modelLabel);
  const evidence = turn.evidence;
  if (evidence === null) return tail === '' ? null : { label: null, tail };
  const sourceCount = evidence.sources.length;
  const frame = answerFrame(
    SV3_ASK_SHAPE_ID,
    sourceCount,
    groundingCoverage(evidence.matches, turn.answer),
    sourcesAreChunkPrecise(evidence.sources),
    // Settled by construction: this projection refuses anything but a completed turn above, so the
    // matcher has finished and a zero-cite answer can no longer excuse itself as "marks pending".
    true,
  );
  const label = answerFrameLabel(frame, groundingDegraded(SV3_ASK_SHAPE_ID, sourceCount));
  if (label === null && tail === '') return null;
  return { label, tail };
}
