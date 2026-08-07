// SPDX-License-Identifier: Apache-2.0
/**
 * records — the ONE session records array and its projections (tempdoc 818 slice 1).
 *
 * The load-bearing novelty of Search v2: transcript, session index, and session name are
 * PROJECTIONS of a single append-only records array (L11, "one fragment many windows"), not
 * three parallel conversation models. A live search is NOT a record — it lives in the deck and
 * becomes a record only by commit (L8: the transcript records commitments, not attention).
 *
 * Two invariants this module owns:
 *  - **L4 (freeze)** — a committed search is an immutable SNAPSHOT captured at commit time. The
 *    hits are copied field-by-field and frozen, so later mutation of the live result set (a new
 *    pass landing, the store re-sorting in place) cannot reach back into the record.
 *  - **L6 (derived counts)** — every count these projections emit is computed from the set it
 *    describes (`hits.length`, `Σ node.size`), never authored by a caller.
 *
 * Everything here is a pure function of its arguments: no store reads, no DOM, no clock. Ids are
 * derived from the array length so a commit is deterministic and testable.
 */

/** A hit as CAPTURED — a value copy, deliberately narrower than the live store's hit type. */
export interface FrozenHit {
  readonly id: string;
  readonly title: string;
  readonly path: string;
  readonly snippet: string;
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
}

/** What the user committed to asking. */
export interface UserTurnRecord {
  readonly kind: 'user-turn';
  readonly id: string;
  readonly text: string;
}

/** The answer slot, opened at commit and filled later (slice 2 hosts the run). */
export interface PendingAnswerRecord {
  readonly kind: 'pending-answer';
  readonly id: string;
}

export type SessionRecord = FrozenSearchRecord | UserTurnRecord | PendingAnswerRecord;

/** The live-search facts a commit captures. Structurally satisfied by the store's `SearchHit`. */
export interface SearchCapture {
  readonly query: string;
  readonly hits: ReadonlyArray<{
    readonly id: string;
    readonly title: string;
    readonly path: string;
    readonly snippet?: string;
  }>;
  readonly total: number;
  readonly mode: CaptureMode;
  readonly tookMs: number | null;
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
  readonly headerLabel: string;
  readonly mode: CaptureMode;
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
export type TranscriptItem = TranscriptFrozenItem | TranscriptTurnItem | TranscriptPendingItem;

const countOf = (n: number, one: string, many: string): string => `${n} ${n === 1 ? one : many}`;

/** L6 — the frozen block's header, computed from the captured set (never passed in). */
function frozenHeaderLabel(hits: readonly FrozenHit[], total: number): string {
  const n = hits.length;
  return n >= total
    ? countOf(n, 'result', 'results')
    : `${n} of ${countOf(total, 'match', 'matches')}`;
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
        headerLabel: frozenHeaderLabel(r.hits, r.total),
        mode: r.mode,
        tookMs: r.tookMs,
      };
    }
    if (r.kind === 'user-turn') {
      return { kind: 'user-turn', id: r.id, text: r.text };
    }
    return { kind: 'pending-answer', id: r.id, label: 'Answer pending' };
  });
}

/** One index node — a cluster of records opened by a commit. */
export interface IndexNode {
  readonly id: string;
  readonly label: string;
  /** |cluster| — the number of records this node stands for. */
  readonly size: number;
  readonly recordIds: readonly string[];
}

export interface IndexProjection {
  /** L6 — Σ of the node sizes, by construction: the header cannot contradict its own nodes. */
  readonly headerCount: number;
  readonly nodes: readonly IndexNode[];
}

/**
 * The session-index projection (rail mode B). Every frozen search opens a cluster; the records
 * that follow it belong to that cluster until the next commit. Records committed before any
 * frozen search (a bare turn) form a leading cluster, so the clusters partition the array and the
 * header count is exactly Σ sizes.
 */
export function projectIndex(records: readonly SessionRecord[]): IndexProjection {
  const nodes: IndexNode[] = [];
  let open: { id: string; label: string; recordIds: string[] } | null = null;

  const flush = (): void => {
    if (!open) return;
    nodes.push(
      Object.freeze({
        id: open.id,
        label: open.label,
        size: open.recordIds.length,
        recordIds: Object.freeze([...open.recordIds]),
      }),
    );
    open = null;
  };

  for (const r of records) {
    if (r.kind === 'frozen-search') {
      flush();
      open = { id: r.id, label: r.query.trim() || 'Untitled search', recordIds: [r.id] };
      continue;
    }
    if (!open) {
      open = {
        id: r.id,
        label: r.kind === 'user-turn' ? r.text.trim() || 'Untitled turn' : 'Untitled turn',
        recordIds: [],
      };
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
