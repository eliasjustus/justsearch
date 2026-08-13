// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 window's session list (tempdoc 822 Phase A2; sessions became CONVERSATIONS in F1).
 *
 * A session holds an ordered list of turns — the T3 Code product shape the 822 course correction
 * adopted: the composer talks to the local model, and a session is that conversation. The search
 * axis A2 built this module for is still wired, but it no longer writes here.
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * **WINDOW-LOCAL AND IN-MEMORY ON PURPOSE — this is not a store, and must not become one.** There is
 * no canonical authority for "search sessions" in this codebase yet; whether one exists, and where it
 * lives, is the Phase-D records-substrate question (tempdoc 822 §4b — v3 presentation meeting v2's
 * records model). Until that decision is made, a persisted session list here would be a SECOND
 * authority for the same concept, drifting from whatever Phase D settles on: exactly the
 * representation fork `CLAUDE.md`'s projection-vs-fork rule exists to prevent. So: no localStorage, no
 * new persisted store, no writes into the shared conversation/search stores. The list lives as long as
 * the window is mounted and dies with it, and every function here is pure so the semantics can be
 * tested without a DOM.
 *
 * Two laws from the donor shape the API:
 *
 *  - **Activity never reorders the list** (donor §6.1, adopted as charter law): a session is inserted
 *    once, at the top of its group, and never moves again — not when it is re-run, not when it
 *    finishes. A row cannot slide out from under the pointer while it is being read.
 *  - **The 3-colour status budget** (donor §6.2): colour means act-now / in-motion / broken. A settled
 *    session spends none of it and carries a coarse relative timestamp instead.
 */
import type { Sv3RowStatus } from './fixtures.js';

/**
 * How a turn's response ended, or that it has not ended yet. Four TERMINALS, all distinct, because
 * they are four different things to have happened: `halted` is the reader's own Stop and must never
 * be worded as a failure, and `refused` is the session lock declining the send — neither of them is
 * an answer that went wrong (tempdoc 822 Phase F1; the terminals `sv3-ask.ts` reports).
 */
export type Sv3TurnStatus = 'streaming' | 'complete' | 'halted' | 'refused' | 'failed';

/**
 * Which tier produced the turn (tempdoc 822 Phase F2). An `ask` turn is a grounded answer streamed
 * from `sv3-ask.ts`; an `agent` turn is a delegated RUN hosted by the shared `AgentSessionController`,
 * which renders as a live feed while it runs and as a receipt once it ends. One turn type with a
 * discriminator, not two lists: a conversation interleaves both and the transcript must keep their
 * order.
 */
export type Sv3TurnKind = 'ask' | 'agent';

/** One exchange: what was asked, and what came back. */
export interface Sv3Turn {
  readonly id: string;
  readonly kind: Sv3TurnKind;
  readonly question: string;
  /** Accumulated answer text. Whatever streamed before a halt is KEPT — it was really received. */
  readonly answer: string;
  readonly status: Sv3TurnStatus;
  /**
   * How many sources the backend said it grounded the answer in — `null` until it says so, which is
   * not the same as zero. A turn that was never told cannot claim a number.
   */
  readonly citations: number | null;
  /** The failure's own words, from the stream. Empty for every other status. */
  readonly detail: string;
  /**
   * An `agent` turn's receipt count — how many tool calls the run made (tempdoc 822 Phase F2). It is
   * written ONCE, at the run's terminal, from the same feed projection the cards were rendered from
   * (`sv3-run.ts` {@link Sv3RunFeed.toolCallCount}), so the receipt cannot describe a different set
   * than the feed it summarises. Always 0 on an `ask` turn, which makes no tool calls.
   */
  readonly toolCalls: number;
  readonly askedAt: number;
}

/** One conversation in this window: what it was opened with, and every turn it has taken. */
export interface Sv3Session {
  readonly id: string;
  /**
   * The OPENING question, fixed at creation. Phase F1 replaced A2's "latest query" title: a
   * conversation's turns are a thread, so a row label that re-wrote itself on every turn would
   * change identity under the reader — the same objection as the donor's never-reorder law, applied
   * to the label instead of the position. No auto-titling: the opening question IS the title (the
   * row's single-line ellipsis handles length).
   */
  readonly title: string;
  /** Oldest FIRST — the transcript's render order. */
  readonly turns: readonly Sv3Turn[];
  readonly createdAt: number;
  /** When the session last submitted; the resting row's timestamp. */
  readonly updatedAt: number;
}

/** Addresses one turn inside one session, so a stream can only ever write to the turn it opened. */
export interface Sv3TurnRef {
  readonly sessionId: string;
  readonly turnId: string;
}

export interface Sv3SessionList {
  /** Newest FIRST, and fixed: creation order is render order forever. */
  readonly sessions: readonly Sv3Session[];
  /** The session a submit belongs to; null means the next submit opens a new one. */
  readonly activeId: string | null;
  /** Ids minted so far. Nothing is ever removed, but a counter keeps ids unique regardless. */
  readonly minted: number;
}

export const SV3_SESSIONS_EMPTY: Sv3SessionList = { sessions: [], activeId: null, minted: 0 };

export const sessionById = (list: Sv3SessionList, id: string): Sv3Session | null =>
  list.sessions.find((session) => session.id === id) ?? null;

/** Deterministic and position-based, so the caller can address a turn without the list handing back a tuple. */
const turnIdFor = (sessionId: string, index: number): string => `${sessionId}#t${index + 1}`;

const openTurn = (
  sessionId: string,
  index: number,
  question: string,
  now: number,
  kind: Sv3TurnKind,
): Sv3Turn => ({
  id: turnIdFor(sessionId, index),
  kind,
  question,
  answer: '',
  status: 'streaming',
  citations: null,
  detail: '',
  toolCalls: 0,
  askedAt: now,
});

/**
 * A submitted question: it CREATES a session when none is active, and APPENDS a turn to the active
 * one otherwise. The session keeps its position — a new turn is not a reason to move a row.
 *
 * Phase F1 replaced A2's update-in-place semantics (a re-query overwrote the session's single
 * query). A conversation accumulates: overwriting would destroy the transcript the window now
 * renders. The SEARCH axis, which A2's semantics were written for, no longer routes through the
 * session list at all — it is a palette-only dev affordance (`SearchV3View.runSearch`).
 */
export function submitInSession(
  list: Sv3SessionList,
  question: string,
  now: number,
  kind: Sv3TurnKind = 'ask',
): Sv3SessionList {
  const text = question.trim();
  if (text === '') return list;
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  if (active === null) {
    const id = `sv3-session-${list.minted + 1}`;
    const created: Sv3Session = {
      id,
      title: text,
      turns: [openTurn(id, 0, text, now, kind)],
      createdAt: now,
      updatedAt: now,
    };
    return { sessions: [created, ...list.sessions], activeId: id, minted: list.minted + 1 };
  }
  return {
    ...list,
    sessions: list.sessions.map((session) =>
      session.id === active.id
        ? {
            ...session,
            turns: [...session.turns, openTurn(session.id, session.turns.length, text, now, kind)],
            updatedAt: now,
          }
        : session,
    ),
  };
}

/** The turn a just-returned {@link submitInSession} opened, or null if nothing is active. */
export function latestTurnRef(list: Sv3SessionList): Sv3TurnRef | null {
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  const turn = active?.turns.at(-1);
  if (active === undefined || active === null || turn === undefined) return null;
  return { sessionId: active.id, turnId: turn.id };
}

/** The active session's transcript. An empty list is the window's "nothing asked here yet". */
export function activeTurns(list: Sv3SessionList): readonly Sv3Turn[] {
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  return active?.turns ?? [];
}

/**
 * The one way a turn changes. Addressed by REF rather than by "the active session", so a stream that
 * started in one session cannot write into another one the reader has since claimed.
 */
function mapTurn(
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  change: (turn: Sv3Turn) => Sv3Turn,
): Sv3SessionList {
  const session = sessionById(list, ref.sessionId);
  if (session === null || !session.turns.some((t) => t.id === ref.turnId)) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) =>
      s.id === ref.sessionId
        ? { ...s, turns: s.turns.map((t) => (t.id === ref.turnId ? change(t) : t)) }
        : s,
    ),
  };
}

/** Streaming text lands here, delta by delta; a settled turn ignores late deltas rather than reopening. */
export const appendTurnDelta = (list: Sv3SessionList, ref: Sv3TurnRef, delta: string): Sv3SessionList =>
  mapTurn(list, ref, (turn) =>
    turn.status === 'streaming' ? { ...turn, answer: turn.answer + delta } : turn,
  );

export const setTurnCitations = (list: Sv3SessionList, ref: Sv3TurnRef, count: number): Sv3SessionList =>
  mapTurn(list, ref, (turn) => ({ ...turn, citations: count }));

/**
 * The turn reaches its ONE terminal. A turn that already settled stays settled: the stream reports
 * exactly one terminal, and a second write could only be a bug re-wording the first.
 */
export const settleTurn = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  detail = '',
): Sv3SessionList =>
  mapTurn(list, ref, (turn) =>
    turn.status === 'streaming' ? { ...turn, status, detail } : turn,
  );

/**
 * The agent run's terminal (tempdoc 822 Phase F2): the turn settles AND becomes its own receipt in
 * ONE write, because the count and the outcome describe the same ended run and must never be able to
 * arrive separately. `toolCalls` is the feed projection's own count — the same list the cards were
 * rendered from — so the caller has no second counter it could pass instead.
 */
export const settleAgentTurn = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  toolCalls: number,
  detail = '',
): Sv3SessionList =>
  mapTurn(list, ref, (turn) =>
    turn.status === 'streaming' ? { ...turn, status, detail, toolCalls } : turn,
  );

/**
 * The New-search affordance: the window returns to its empty state and the NEXT submit opens a new
 * session. Nothing is dropped — the sessions so far stay in the list, they are just no longer active.
 */
export const startNewSession = (list: Sv3SessionList): Sv3SessionList => ({
  ...list,
  activeId: null,
});

/** Clicking a row claims it; an unknown id changes nothing rather than clearing the claim. */
export function focusSession(list: Sv3SessionList, id: string): Sv3SessionList {
  if (sessionById(list, id) === null) return list;
  return { ...list, activeId: id };
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * A coarse relative timestamp, rendered on RENDER and never ticked — a live clock in a sidebar is
 * continuous motion at rest, which the donor's duty-cycle law rules out. Coarse also means honest:
 * "2m" claims nothing a second-resolution label would have to keep re-proving.
 */
export function sv3RelativeTime(then: number, now: number): string {
  const delta = now - then;
  // A clock that went backwards (skew, DST) reads as "now" rather than as a negative age.
  if (delta < MINUTE) return 'now';
  if (delta < HOUR) return `${Math.floor(delta / MINUTE)}m`;
  if (delta < DAY) return `${Math.floor(delta / HOUR)}h`;
  return `${Math.floor(delta / DAY)}d`;
}

/** The row as the sidebar renders it — a projection of a session, never a second copy of one. */
export interface Sv3SessionRowView {
  readonly id: string;
  readonly label: string;
  readonly status: Sv3RowStatus;
  readonly meta: string;
  readonly active: boolean;
}

export interface Sv3SessionGroup {
  readonly id: string;
  readonly label: string;
  readonly rows: readonly Sv3SessionRowView[];
}

const isSameDay = (a: number, b: number): boolean => {
  const left = new Date(a);
  const right = new Date(b);
  return (
    left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate()
  );
};

export interface Sv3SessionProjection {
  /** The shared store's in-flight flag: the ACTIVE session is the one that asked. */
  readonly searching: boolean;
  /**
   * The session whose delegated run is parked on a typed decision, or null (tempdoc 822 Phase F2).
   * Named by ID rather than passed as a flag for the active row, because the shared controller is
   * product-wide: only the session that OPENED the run may wear its act-now colour.
   */
  readonly awaitingDecisionIn: string | null;
  readonly now: number;
}

/**
 * Sessions → sidebar groups. A group is rendered only when it holds rows, so a window whose sessions
 * are all from this visit shows ONE label ("Today") rather than a shelf of empty headings.
 *
 * There is no "Pinned" group: nothing in this window can pin a session yet. The donor's pin lives in
 * the row's action slot, which arrives with the status→action slot swap (Phase C) — a group keyed on
 * a flag nothing can set would be scaffolding, not grouping.
 */
export function projectSv3Sessions(
  list: Sv3SessionList,
  { searching, awaitingDecisionIn, now }: Sv3SessionProjection,
): readonly Sv3SessionGroup[] {
  const toRow = (session: Sv3Session): Sv3SessionRowView => {
    const active = session.id === list.activeId;
    const last = session.turns.at(-1);
    // ACT-NOW outranks in-motion: a run parked on the reader's decision is not making progress, and
    // the one colour that means "you are the blocker" must win over the one that means "it is busy".
    const awaiting = awaitingDecisionIn === session.id;
    // Two axes reach the same three colours. The CONVERSATIONAL one is the session's own: its last
    // turn is streaming, or it broke — a property of THIS session, true whichever row is claimed.
    // The SEARCH one is the process-wide store flag, which only the active session can own (the
    // store cannot say who asked), and which A2's semantics already limited that way.
    const running = last?.status === 'streaming' || (active && searching);
    const broken = last?.status === 'failed' || last?.status === 'refused';
    return {
      id: session.id,
      label: session.title,
      status: awaiting ? 'act-now' : running ? 'in-motion' : broken ? 'broken' : 'resting',
      meta: awaiting || running ? '' : sv3RelativeTime(session.updatedAt, now),
      active,
    };
  };
  const today = list.sessions.filter((s) => isSameDay(s.createdAt, now)).map(toRow);
  const earlier = list.sessions.filter((s) => !isSameDay(s.createdAt, now)).map(toRow);
  const groups: Sv3SessionGroup[] = [];
  if (today.length > 0) groups.push({ id: 'sv3-group-today', label: 'Today', rows: today });
  if (earlier.length > 0) groups.push({ id: 'sv3-group-earlier', label: 'Earlier', rows: earlier });
  return groups;
}
