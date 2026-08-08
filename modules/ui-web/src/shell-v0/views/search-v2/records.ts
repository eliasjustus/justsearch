// SPDX-License-Identifier: Apache-2.0
/**
 * records — the ONE session records array and its projections (tempdoc 818 slices 1-3).
 *
 * The load-bearing novelty of Search v2: transcript, session index, and session name are
 * PROJECTIONS of a single append-only records array (L11, "one fragment many windows"), not
 * three parallel conversation models. A live search is NOT a record — it lives in the deck and
 * becomes a record only by commit (L8: the transcript records commitments, not attention).
 *
 * Three invariants this module owns:
 *  - **L4 (freeze)** — a committed search is an immutable SNAPSHOT captured at commit time. The
 *    hits are copied field-by-field and frozen, so later mutation of the live result set (a new
 *    pass landing, the store re-sorting in place) cannot reach back into the record.
 *  - **L4 (the answer slot's own lifecycle)** — the commit opens ONE `pending-answer` slot. While
 *    the answer streams, the accumulating text lives in VIEW state, outside this array; the slot is
 *    filled exactly once, at a terminal event, by {@link finalizeAnswer} (an `answer`) or
 *    {@link refuseAnswer} (a `refused-answer` — the lock refused it, or the stream failed). Filling
 *    the slot is that record's own lifecycle, not a rewrite of the transcript: every OTHER record is
 *    carried through by identity, so a frozen search or a user turn can never be touched by an
 *    answer landing. There is no third terminal — a slot always ends filled or stays visibly pending.
 *  - **L6 (derived counts)** — every count these projections emit is computed from the set it
 *    describes (`hits.length`, `Σ node.size`, `sentencesMatched/sentencesTotal`), never authored by
 *    a caller.
 *
 * Everything here is a pure function of its arguments: no store reads, no DOM, no clock (the commit
 * wall-clock arrives as `SearchCapture.executedAt`). Ids are derived from the array length so a
 * commit is deterministic and testable.
 *
 * Registered in `governance/execution-surfaces.v1.json` (`sv2-records`) as an opaque carrier of the
 * `RetrievalCitation` sibling evidence record: an answer record STORES the citation set the backend
 * minted; it projects no field of it (the rendering projection is `SearchV2View`).
 */

import type {
  CitationMatch,
  Claim,
  RetrievalCitation,
} from '../../components/chat/citationTypes.js';
// The product's ONE relative-time wording (the results card's frozen header reads the same helper),
// so an elaboration never mints a second way of saying when something happened. `now` is a
// parameter here as it is there, which is what keeps this module clock-free.
import { formatRelative } from '../../utils/relativeTime.js';

/** A hit as CAPTURED — a value copy, deliberately narrower than the live store's hit type. */
export interface FrozenHit {
  readonly id: string;
  readonly title: string;
  readonly path: string;
  readonly snippet: string;
  /** Typed identity (`SearchHit.kind` / `mimeBase`) — the row projection's icon + kind vocabulary. */
  readonly kind?: string;
  readonly mimeBase?: string;
  /** Tempdoc 811 — the corpus the hit came from; drives the row's collection pill. */
  readonly collection?: string;
}

/** Which execution pass produced the captured set (mirrors the store's pass stage). */
export type CaptureMode = 'quick' | 'refined' | 'unknown';

/** A committed search: the frozen retrieval scope of everything that follows it (L5). */
export interface FrozenSearchRecord {
  readonly kind: 'frozen-search';
  readonly id: string;
  readonly query: string;
  readonly hits: readonly FrozenHit[];
  /** The matched population at capture time (the store's true `matchCount`). */
  readonly total: number;
  readonly mode: CaptureMode;
  readonly tookMs: number | null;
  /**
   * How the pass RETRIEVED (`HYBRID` / `VECTOR` / `TEXT` / `UNKNOWN`), captured from the trace at
   * commit. Distinct from {@link CaptureMode}, which says which PASS produced the set.
   */
  readonly retrievalMode: string;
  /** ISO-8601 wall clock of the commit. Passed in — this module reads no clock. */
  readonly executedAt: string;
}

/** What the user committed to asking. */
export interface UserTurnRecord {
  readonly kind: 'user-turn';
  readonly id: string;
  readonly text: string;
}

/** The answer slot, opened at commit and filled later by exactly one terminal. */
export interface PendingAnswerRecord {
  readonly kind: 'pending-answer';
  readonly id: string;
}

/** L6 — the grounding facts the backend reported about the answer it just produced. */
export interface AnswerGrounding {
  readonly sentencesMatched: number;
  readonly sentencesTotal: number;
}

/** A landed answer: the filled slot. Immutable, like every other record. */
export interface AnswerRecord {
  readonly kind: 'answer';
  readonly id: string;
  readonly text: string;
  readonly claims: readonly Claim[];
  readonly citations: readonly CitationMatch[];
  readonly sources: readonly RetrievalCitation[];
  /** `rag.meta.retrieval_mode` — how the ANSWER's retrieval ran; null when unreported. */
  readonly retrievalMode: string | null;
  /** `rag.meta.chunks_used` — how much context the answer actually stood on; null when unreported. */
  readonly chunksUsed: number | null;
  /** `rag.citation_matches` grounding stats; null when the backend reported none. */
  readonly grounding: AnswerGrounding | null;
  /** `done.promptTokens` — this turn's context occupancy; null when unreported. */
  readonly promptTokens: number | null;
}

/** Why a slot ended without an answer. `locked` is L9's session gate; `error` is a failed stream. */
export type RefusalReason = 'locked' | 'error';

/**
 * A slot that terminated without an answer. It exists so a refused send can never leave a
 * permanently pending slot (a spinner that resolves to nothing is the same lie as a fabricated
 * answer), and so the refusal is part of the record — append-only, re-run as new (L4).
 */
export interface RefusedAnswerRecord {
  readonly kind: 'refused-answer';
  readonly id: string;
  readonly reason: RefusalReason;
  readonly detail: string;
}

/** How a delegated agent run ended, as the window observed it (tempdoc 818 slice 3). */
export type RunOutcome = 'completed' | 'halted' | 'error';

/**
 * L8 (slice 3) — the RECEIPT of a delegated run. A run's live feed (its steps, tool cards and
 * streaming text) is ATTENTION: it lives in view state, it is fed by the shared
 * `AgentSessionController`, and it disappears when the run ends. What the transcript keeps is
 * exactly ONE record per run, summarising what the run committed to doing. The counts are captured
 * from the run's own observed activity at its terminal, so this record can never contradict the
 * feed it stands for (L6); `tokensUsed` is null when the run ended before the backend reported a
 * total, because a halted run that spent tokens must not be recorded as having spent zero.
 */
export interface AgentRunRecord {
  readonly kind: 'agent-run';
  readonly id: string;
  readonly outcome: RunOutcome;
  readonly toolCallCount: number;
  readonly tokensUsed: number | null;
  /**
   * L14 (slice 5) — when the run reached its terminal, ISO-8601. Elaboration, never the fact: the
   * receipt's outcome and counts rest visible and this is what extends beside them. Null when the
   * caller captured no clock, which renders NOTHING rather than a fabricated time.
   */
  readonly endedAt: string | null;
}

export type SessionRecord =
  | FrozenSearchRecord
  | UserTurnRecord
  | PendingAnswerRecord
  | AnswerRecord
  | RefusedAnswerRecord
  | AgentRunRecord;

/** The live-search facts a commit captures. Structurally satisfied by the store's `SearchHit`. */
export interface SearchCapture {
  readonly query: string;
  readonly hits: ReadonlyArray<{
    readonly id: string;
    readonly title: string;
    readonly path: string;
    readonly snippet?: string;
    readonly kind?: string;
    readonly mimeBase?: string;
    readonly collection?: string;
  }>;
  readonly total: number;
  readonly mode: CaptureMode;
  readonly tookMs: number | null;
  readonly retrievalMode: string;
  readonly executedAt: string;
}

/** What a terminal `done` reports about the answer that just landed. */
export interface AnswerCapture {
  readonly text: string;
  readonly claims: readonly Claim[];
  readonly citations: readonly CitationMatch[];
  readonly sources: readonly RetrievalCitation[];
  readonly retrievalMode: string | null;
  readonly chunksUsed: number | null;
  readonly grounding: AnswerGrounding | null;
  readonly promptTokens: number | null;
}

/** The empty session — the starting records array. */
export const NO_RECORDS: readonly SessionRecord[] = Object.freeze([]);

/**
 * L4 — capture a live search as an immutable record. Each hit is copied into a new frozen object,
 * so the record shares no mutable structure with the source set.
 */
export function freezeSearch(id: string, capture: SearchCapture): FrozenSearchRecord {
  const hits = capture.hits.map((h) =>
    Object.freeze({
      id: h.id,
      title: h.title,
      path: h.path,
      snippet: h.snippet ?? '',
      kind: h.kind,
      mimeBase: h.mimeBase,
      collection: h.collection,
    }),
  );
  return Object.freeze({
    kind: 'frozen-search' as const,
    id,
    query: capture.query,
    hits: Object.freeze(hits),
    total: capture.total,
    mode: capture.mode,
    tookMs: capture.tookMs,
    retrievalMode: capture.retrievalMode,
    executedAt: capture.executedAt,
  });
}

/**
 * The commit: a live search + the question about it become three appended records — the frozen
 * scope, the turn, and the answer slot. Append-only (L4): existing records are never rewritten.
 */
export function commitSearch(
  records: readonly SessionRecord[],
  capture: SearchCapture,
  turnText: string,
): readonly SessionRecord[] {
  const n = records.length;
  return Object.freeze([
    ...records,
    freezeSearch(`r${n}`, capture),
    Object.freeze({ kind: 'user-turn' as const, id: `r${n + 1}`, text: turnText }),
    Object.freeze({ kind: 'pending-answer' as const, id: `r${n + 2}` }),
  ]);
}

/**
 * L8 (slice 3) — the DELEGATE commitment: a turn handed to the agent lands in the transcript before
 * the run is dispatched, and it is the only thing the dispatch appends. Everything the run then does
 * is attention until its terminal, where exactly one {@link appendAgentRun} receipt follows.
 */
export function appendUserTurn(
  records: readonly SessionRecord[],
  text: string,
): readonly SessionRecord[] {
  return Object.freeze([
    ...records,
    Object.freeze({ kind: 'user-turn' as const, id: `r${records.length}`, text }),
  ]);
}

/** What a concluded run reports about itself. Captured by the caller from the run's own activity. */
export interface RunCapture {
  readonly outcome: RunOutcome;
  readonly toolCallCount: number;
  readonly tokensUsed: number | null;
  /** When the run ended (ISO-8601). Optional: a caller with no clock records no time. */
  readonly endedAt?: string;
}

/** L8 (slice 3) — the run's terminal: append its ONE receipt. Append-only, like every commit. */
export function appendAgentRun(
  records: readonly SessionRecord[],
  capture: RunCapture,
): readonly SessionRecord[] {
  return Object.freeze([
    ...records,
    Object.freeze({
      kind: 'agent-run' as const,
      id: `r${records.length}`,
      outcome: capture.outcome,
      toolCallCount: capture.toolCallCount,
      tokensUsed: capture.tokensUsed,
      endedAt: capture.endedAt ?? null,
    }),
  ]);
}

/** The id a {@link commitSearch} opens its answer slot at, given the pre-commit array. */
export function pendingAnswerIdFor(records: readonly SessionRecord[]): string {
  return `r${records.length + 2}`;
}

/** Replace exactly the `pending-answer` at `id`; every other record is carried BY IDENTITY. */
function fillSlot(
  records: readonly SessionRecord[],
  id: string,
  make: () => SessionRecord,
): readonly SessionRecord[] {
  const i = records.findIndex((r) => r.kind === 'pending-answer' && r.id === id);
  if (i < 0) return records;
  const next = [...records];
  next[i] = make();
  return Object.freeze(next);
}

/**
 * L4 — the answer slot's terminal: the `pending-answer` at `id` becomes an `answer` at the SAME id.
 * A no-op (returns the same array by identity) if that slot is already filled or does not exist, so
 * a late/duplicate terminal cannot rewrite a landed record.
 */
export function finalizeAnswer(
  records: readonly SessionRecord[],
  id: string,
  capture: AnswerCapture,
): readonly SessionRecord[] {
  return fillSlot(records, id, () =>
    Object.freeze({
      kind: 'answer' as const,
      id,
      text: capture.text,
      claims: Object.freeze([...capture.claims]),
      citations: Object.freeze([...capture.citations]),
      sources: Object.freeze([...capture.sources]),
      retrievalMode: capture.retrievalMode,
      chunksUsed: capture.chunksUsed,
      grounding: capture.grounding ? Object.freeze({ ...capture.grounding }) : null,
      promptTokens: capture.promptTokens,
    }),
  );
}

/** L4/L9 — the slot's other terminal: nothing was answered, and the record says why. */
export function refuseAnswer(
  records: readonly SessionRecord[],
  id: string,
  reason: RefusalReason,
  detail: string,
): readonly SessionRecord[] {
  return fillSlot(records, id, () =>
    Object.freeze({ kind: 'refused-answer' as const, id, reason, detail }),
  );
}

/** A transcript row: one record, rendered with the labels DERIVED from that record (L6). */
export interface TranscriptFrozenItem {
  readonly kind: 'frozen-search';
  readonly id: string;
  readonly query: string;
  readonly hits: readonly FrozenHit[];
  /** |captured set| — the count the frozen block's header describes. */
  readonly capturedCount: number;
  /** The matched population the captured set was drawn from. */
  readonly matchedTotal: number;
  readonly mode: CaptureMode;
  readonly retrievalMode: string;
  readonly executedAt: string;
  readonly tookMs: number | null;
}
export interface TranscriptTurnItem {
  readonly kind: 'user-turn';
  readonly id: string;
  readonly text: string;
}
export interface TranscriptPendingItem {
  readonly kind: 'pending-answer';
  readonly id: string;
  readonly label: string;
}
export interface TranscriptAnswerItem {
  readonly kind: 'answer';
  readonly id: string;
  readonly text: string;
  readonly claims: readonly Claim[];
  readonly citations: readonly CitationMatch[];
  readonly sources: readonly RetrievalCitation[];
  readonly retrievalMode: string | null;
  /** L6 — derived from the grounding stats; null when the backend reported none (never a fake 0). */
  readonly groundedSentencesLabel: string | null;
}
export interface TranscriptRefusedItem {
  readonly kind: 'refused-answer';
  readonly id: string;
  readonly reason: RefusalReason;
  readonly detail: string;
  readonly label: string;
}
export interface TranscriptRunItem {
  readonly kind: 'agent-run';
  readonly id: string;
  readonly outcome: RunOutcome;
  /** L6 — derived from the record's own counts by {@link runSummaryLabel}. */
  readonly label: string;
  /** L14 — the receipt's timing, elaboration only. Null when the run recorded no end time. */
  readonly endedAt: string | null;
}
export type TranscriptItem =
  | TranscriptFrozenItem
  | TranscriptTurnItem
  | TranscriptPendingItem
  | TranscriptAnswerItem
  | TranscriptRefusedItem
  | TranscriptRunItem;

/** How a run's outcome reads to the person who delegated it. */
const RUN_OUTCOME_WORDING: Readonly<Record<RunOutcome, string>> = Object.freeze({
  completed: 'Run finished',
  halted: 'Run halted by you',
  error: 'Run ended in an error',
});

/**
 * L6 — the run receipt's line, computed from the two counts it describes. An unreported token total
 * omits its clause entirely: a run whose total never landed must not be presented as having used 0
 * tokens, which reads as a measurement rather than a missing one (the same rule as the grounding
 * line above).
 */
export function runSummaryLabel(
  outcome: RunOutcome,
  toolCallCount: number,
  tokensUsed: number | null,
): string {
  const calls = `${toolCallCount} tool ${toolCallCount === 1 ? 'call' : 'calls'}`;
  const tokens = tokensUsed === null ? '' : ` · ${tokensUsed.toLocaleString()} tokens`;
  return `${RUN_OUTCOME_WORDING[outcome]} · ${calls}${tokens}`;
}

/**
 * L6 — the grounding line, computed from the two counts it describes. Null (render nothing) when
 * the backend reported no citation-matching pass: an ungrounded answer must not be presented as
 * "0 of 0 grounded", which reads as a measured verdict rather than an absent measurement.
 */
export function groundedSentencesLabel(g: AnswerGrounding | null): string | null {
  if (!g || g.sentencesTotal <= 0) return null;
  const noun = g.sentencesTotal === 1 ? 'sentence' : 'sentences';
  return `${g.sentencesMatched} of ${g.sentencesTotal} ${noun} grounded in your files`;
}

/** The transcript projection: records in commit order, each with its derived labels. */
export function projectTranscript(records: readonly SessionRecord[]): readonly TranscriptItem[] {
  return records.map((r): TranscriptItem => {
    if (r.kind === 'frozen-search') {
      return {
        kind: 'frozen-search',
        id: r.id,
        query: r.query,
        hits: r.hits,
        capturedCount: r.hits.length,
        matchedTotal: r.total,
        mode: r.mode,
        retrievalMode: r.retrievalMode,
        executedAt: r.executedAt,
        tookMs: r.tookMs,
      };
    }
    if (r.kind === 'user-turn') {
      return { kind: 'user-turn', id: r.id, text: r.text };
    }
    if (r.kind === 'answer') {
      return {
        kind: 'answer',
        id: r.id,
        text: r.text,
        claims: r.claims,
        citations: r.citations,
        sources: r.sources,
        retrievalMode: r.retrievalMode,
        groundedSentencesLabel: groundedSentencesLabel(r.grounding),
      };
    }
    if (r.kind === 'agent-run') {
      return {
        kind: 'agent-run',
        id: r.id,
        outcome: r.outcome,
        label: runSummaryLabel(r.outcome, r.toolCallCount, r.tokensUsed),
        endedAt: r.endedAt,
      };
    }
    if (r.kind === 'refused-answer') {
      return {
        kind: 'refused-answer',
        id: r.id,
        reason: r.reason,
        detail: r.detail,
        // One verb per action (818 slice 5 copy pass): the affordance says Ask, so the pending state
        // says Asking and a refusal says what was not asked.
        label: r.reason === 'locked' ? 'Not asked — the session is locked' : 'Not answered',
      };
    }
    return { kind: 'pending-answer', id: r.id, label: 'Asking your files…' };
  });
}

/** One index node — a cluster of records opened by a commit. */
export interface IndexNode {
  readonly id: string;
  readonly label: string;
  /** |cluster| — the number of records this node stands for. */
  readonly size: number;
  readonly recordIds: readonly string[];
  /**
   * L14 (slice 5) — the node's ELABORATION: what the opening record was, in detail. Null when the
   * record has nothing to elaborate. It is not the node's identity (that is `label` + `size`, both
   * resting-visible); it is what extends on hover and on keyboard focus.
   */
  readonly detail: string | null;
}

export interface IndexProjection {
  /** L6 — Σ of the node sizes, by construction: the header cannot contradict its own nodes. */
  readonly headerCount: number;
  readonly nodes: readonly IndexNode[];
}

/** How a capture's own pass reads to the person who ran it — never the raw enum. */
const CAPTURE_MODE_WORDING: Readonly<Record<CaptureMode, string | null>> = Object.freeze({
  quick: 'Quick pass',
  refined: 'Refined pass',
  unknown: null,
});

/**
 * L14 — a frozen record's TIMINGS, the elaboration beside the results card's own header. The card
 * already states the query, the counts, the retrieval mode and when it ran; this states only what it
 * does not, so the extended line is elaboration rather than a second copy of a resting fact. Null
 * when neither the pass nor a latency was captured — an absent measurement renders nothing.
 */
export function frozenTimingLabel(mode: CaptureMode, tookMs: number | null): string | null {
  const parts = [CAPTURE_MODE_WORDING[mode], tookMs === null ? null : `${tookMs} ms`].filter(
    (p): p is string => p !== null,
  );
  return parts.length > 0 ? parts.join(' · ') : null;
}

/**
 * L14 — the elaboration a session-index node carries, derived from the record that OPENS it. Every
 * branch states a fact the record already holds; a record with nothing further to say elaborates
 * into nothing rather than into filler.
 */
export function indexNodeDetail(r: SessionRecord, now: number): string | null {
  if (r.kind === 'frozen-search') {
    const timing = frozenTimingLabel(r.mode, r.tookMs);
    const when = formatRelative(new Date(r.executedAt).getTime(), now);
    return timing ? `${timing} · ${when}` : when;
  }
  if (r.kind === 'agent-run') {
    const ended = r.endedAt === null ? null : formatRelative(new Date(r.endedAt).getTime(), now);
    return ended ? `${runSummaryLabel(r.outcome, r.toolCallCount, r.tokensUsed)} · ${ended}` : null;
  }
  if (r.kind === 'answer') return groundedSentencesLabel(r.grounding);
  if (r.kind === 'refused-answer') return r.detail || null;
  return null;
}

/** The label a non-frozen record carries when it happens to OPEN a cluster (no commit before it). */
function leadingNodeLabel(r: SessionRecord): string {
  if (r.kind === 'user-turn') return r.text.trim() || 'Untitled turn';
  if (r.kind === 'answer' || r.kind === 'pending-answer') return 'Answer';
  if (r.kind === 'refused-answer') return 'Not asked';
  if (r.kind === 'agent-run') return 'Delegated run';
  return 'Untitled turn';
}

/**
 * The session-index projection (rail mode B). Every frozen search opens a cluster; the records
 * that follow it belong to that cluster until the next commit. Records committed before any
 * frozen search (a bare turn) form a leading cluster, so the clusters partition the array and the
 * header count is exactly Σ sizes.
 */
export function projectIndex(records: readonly SessionRecord[], now: number): IndexProjection {
  const nodes: IndexNode[] = [];
  let open: { id: string; label: string; recordIds: string[]; detail: string | null } | null = null;

  const flush = (): void => {
    if (!open) return;
    nodes.push(
      Object.freeze({
        id: open.id,
        label: open.label,
        size: open.recordIds.length,
        recordIds: Object.freeze([...open.recordIds]),
        detail: open.detail,
      }),
    );
    open = null;
  };

  for (const r of records) {
    if (r.kind === 'frozen-search') {
      flush();
      open = {
        id: r.id,
        label: r.query.trim() || 'Untitled search',
        recordIds: [r.id],
        detail: indexNodeDetail(r, now),
      };
      continue;
    }
    if (!open) {
      open = { id: r.id, label: leadingNodeLabel(r), recordIds: [], detail: indexNodeDetail(r, now) };
    }
    open.recordIds.push(r.id);
  }
  flush();

  return Object.freeze({
    headerCount: nodes.reduce((sum, n) => sum + n.size, 0),
    nodes: Object.freeze(nodes),
  });
}

/** The name a session with no committed record carries. */
export const UNNAMED_SESSION = 'New session';

/**
 * L8 corollary — a session is NAMED by its first committed record. No name is authored, stored,
 * or generated: it is read off the records array, so "New chat is unreachable in state X" is
 * unrepresentable (there is no state to gate it on).
 */
export function projectSessionName(records: readonly SessionRecord[]): string {
  for (const r of records) {
    if (r.kind === 'frozen-search' && r.query.trim()) return r.query.trim();
    if (r.kind === 'user-turn' && r.text.trim()) return r.text.trim();
  }
  return UNNAMED_SESSION;
}
