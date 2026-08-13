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
import { CITATIONS_LABEL, SOURCES_LABEL } from './fixtures.js';
import type { Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';

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
 *  - the TAIL is the turn's own measured duration and, when it is still needed, the model recorded AT
 *    ITS TERMINAL — never the model that happens to be loaded when the transcript is re-read. Since
 *    Phase F11 the model's ordinary home is the composer, so the stamp is named here only when it
 *    differs from what the composer is currently saying ({@link sv3TailModelLabel}).
 *
 * The LABEL arrives as one string and is rendered as two ({@link splitSv3FrameLabel}): the verdict
 * rests, the elaboration extends (L14). Both halves are the authority's own substrings.
 *
 * A turn the backend never sent retrieval evidence for gets NO label: `evidence === null` is "never
 * told", and framing it as "searched your documents and found nothing to cite" would be inventing a
 * search that may not have happened. Its tail still renders — the duration is measured, not reported.
 */
export interface Sv3AnswerFrame {
  /** `"Partly grounded"` — the authority's verdict half, which RESTS. `null` needs no line at all. */
  readonly verdict: string | null;
  /** `"some statements are not backed by your documents"` — the elaboration half, or `''`. */
  readonly elaboration: string;
  /** `"45.7 s"` (+ `" · <model>"` only when the model differs), or `''` when nothing was known. */
  readonly tail: string;
}

/** The separator the shared authority words every two-part frame label with (U+2014, spaced). */
const FRAME_LABEL_SEPARATOR = ' — ';

/**
 * The authority's label is `"<verdict> — <elaboration>"` for every frame it words
 * (`components/chat/evidenceProjection.ts:171-188`). This RE-WORDS NOTHING: both halves are the
 * authority's own substrings, and the whole string survives verbatim wherever the reader is given
 * the full line (the tail's accessible name and its `title`).
 *
 * Fail-safe direction matters: a label the authority ever words WITHOUT the em dash rests ENTIRELY,
 * because the failure the window can afford is more text, never less.
 */
export function splitSv3FrameLabel(label: string): { verdict: string; elaboration: string } {
  const at = label.indexOf(FRAME_LABEL_SEPARATOR);
  if (at < 0) return { verdict: label, elaboration: '' };
  return {
    verdict: label.slice(0, at),
    elaboration: label.slice(at + FRAME_LABEL_SEPARATOR.length),
  };
}

/**
 * Which model to name IN THE TAIL, given the one this turn was stamped with at its terminal and the
 * one the composer is currently naming (tempdoc 822 Phase F11).
 *
 * The model moved into the composer, and a composer names the CURRENT model — so for an old answer
 * written by a different one, the composer would be mislabelling it. That is the exact defect the
 * per-turn stamp exists to avoid (see the note above), so the stamp REAPPEARS in the tail precisely
 * when, and only when, it would otherwise be contradicted:
 *
 *  - same model  → `null`: the composer already says it, and saying it twice is the duplicated fact.
 *  - different   → the STAMPED one: this answer was not written by what the composer names.
 *  - current unknown → the stamped one: "not said" is not "the same".
 */
export function sv3TailModelLabel(stamped: string | null, current: string | null): string | null {
  if (stamped === null || stamped === '') return null;
  return stamped === current ? null : stamped;
}

export function projectSv3AnswerFrame(
  turn: Sv3Turn,
  currentModelLabel: string | null,
): Sv3AnswerFrame | null {
  // Only a COMPLETED ask carries a frame. A halted, refused or failed turn has its own note saying
  // what became of it, and framing the grounding of an answer that never landed would be a claim
  // about text the reader does not have.
  if (turn.kind !== 'ask' || turn.status !== 'complete') return null;
  const tail = sv3ReceiptTail(
    turn.durationMs,
    sv3TailModelLabel(turn.modelLabel, currentModelLabel),
  );
  const evidence = turn.evidence;
  if (evidence === null) {
    return tail === '' ? null : { verdict: null, elaboration: '', tail };
  }
  const sourceCount = evidence.sources.length;
  const frame = answerFrame(
    SV3_ASK_SHAPE_ID,
    sourceCount,
    // Tempdoc 822 §3b — the coverage counts the RESOLVED MARKS, not the raw match list: a claim the
    // resolver dropped (no verified ref, or one addressing no source) renders no mark, so counting
    // it would claim a verification the reader cannot see. The frame degrades because the evidence
    // degraded — the same read the shipped window makes.
    groundingCoverage(evidence.marks, turn.answer),
    sourcesAreChunkPrecise(evidence.sources),
    // Settled by construction: this projection refuses anything but a completed turn above, so the
    // matcher has finished and a zero-cite answer can no longer excuse itself as "marks pending".
    true,
  );
  const label = answerFrameLabel(frame, groundingDegraded(SV3_ASK_SHAPE_ID, sourceCount));
  if (label === null && tail === '') return null;
  if (label === null) return { verdict: null, elaboration: '', tail };
  return { ...splitSv3FrameLabel(label), tail };
}

/* ── The tail's sources disclosure (tempdoc 822 Phase F11) ───────────────────────────────────── */

/** What the tail's disclosure may be called for this turn, or `null` when there is nothing to open. */
export type Sv3SourcesTrigger = typeof SOURCES_LABEL | typeof CITATIONS_LABEL;

/**
 * The window's ONE disclosure affordance for a turn's evidence, and the word it may use.
 *
 * The word is not decorative: with a retrieval set the panel holds SOURCES, but with only
 * per-sentence matches it holds citation-matches and nothing was reported as retrieved — calling
 * that "Sources" would claim a retrieval the window was never told about. The trigger's presence
 * mirrors `CitationsPanel.render`'s own both-empty short-circuit, so a trigger can never open onto
 * an empty panel.
 */
export function sv3SourcesTrigger(evidence: Sv3TurnEvidence | null): Sv3SourcesTrigger | null {
  if (evidence === null) return null;
  if (evidence.sources.length > 0) return SOURCES_LABEL;
  return evidence.matches.length > 0 ? CITATIONS_LABEL : null;
}

/**
 * Whether the disclosure carries its COUNT on the resting surface (`5 Sources`) or only in its
 * accessible name (`Sources`, `aria-label="Sources: 5"`).
 *
 * `false` is the owner's literal direction (tempdoc 822 F11 §4 choice 1(i)). It is a named constant
 * rather than an inlined `false` because the alternative — the quiet count, which keeps a resting
 * honesty fact L14 placed deliberately — must stay a ONE-LINE flip, and both branches are tested.
 */
export const SV3_SOURCES_COUNT_IN_TRIGGER = false;

/** How many the disclosure would be speaking for — sources when there are any, else the matches. */
export function sv3SourcesTriggerCount(evidence: Sv3TurnEvidence | null): number {
  if (evidence === null) return 0;
  return evidence.sources.length > 0 ? evidence.sources.length : evidence.matches.length;
}

/** The disclosure's VISIBLE label, under the constant above. Its accessible name always has the count. */
export function sv3SourcesTriggerLabel(
  trigger: Sv3SourcesTrigger,
  count: number,
  countInTrigger: boolean = SV3_SOURCES_COUNT_IN_TRIGGER,
): string {
  return countInTrigger ? `${count} ${trigger}` : trigger;
}
