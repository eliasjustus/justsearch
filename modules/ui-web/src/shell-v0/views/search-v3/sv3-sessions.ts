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
import type { CitationMatch, RetrievalCitation } from '../../components/chat/citationTypes.js';
import type { Citation } from '../../components/chat/MarkdownBlock.js';

/**
 * What one answer stood on, as ONE record (tempdoc 822 Phase F4). Registered in
 * `governance/execution-surfaces.v1.json` (`sv3-turn-evidence`) as an opaque carrier: the turn keeps
 * the backend's evidence verbatim and projects no field of it. Every number the window says about an
 * answer's sources is read off this record — there is no second count to disagree with it.
 */
export interface Sv3TurnEvidence {
  /** The retrieval set the backend reported (`rag.citations`). */
  readonly sources: readonly RetrievalCitation[];
  /** The per-sentence grounding matches (`rag.citation_matches`), for the shared citations panel. */
  readonly matches: readonly CitationMatch[];
  /** The inline `[n]` marks, resolved by the SHARED `claimsToCitations` — never authored here. */
  readonly marks: readonly Citation[];
  /** The retrieval mode the panel needs to know whether it may grade the sources at all. */
  readonly retrievalMode: string;
}

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
   * The evidence the backend minted for this answer — `null` until it reports any, which is not the
   * same as an empty set. A turn that was never told cannot claim a number.
   */
  readonly evidence: Sv3TurnEvidence | null;
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
   * The reader parked this conversation on the Pinned shelf (tempdoc 822 Phase F3). A flag on the
   * session rather than a separate list, because a pin is a PROPERTY of the conversation: a second
   * list would be a second ordering to keep in step with this one.
   */
  readonly pinned: boolean;
  /**
   * When something in this session last reached a terminal, or null if nothing has. Half of the
   * unread bit (820 W2's `completedAt` vs `lastVisitedAt`): a run or an answer that finished is
   * something to read, and the session says so until the reader has been back.
   */
  readonly completedAt: number | null;
  /**
   * When the reader last had this conversation on screen. `0` means never — an adopted run
   * ({@link adoptRunSession}) is on screen nowhere until it is claimed.
   */
  readonly lastVisitedAt: number;
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
  evidence: null,
  detail: '',
  toolCalls: 0,
  askedAt: now,
});

/**
 * The ONE construction of a session, shared by a submit and by an adopted run, so a session can
 * never exist in two shapes. `visitedAt` is the caller's: a submit is made by a reader who is
 * looking at the window, an adopted run is not.
 */
function openSession(
  list: Sv3SessionList,
  title: string,
  now: number,
  kind: Sv3TurnKind,
  visitedAt: number,
): Sv3Session {
  const id = `sv3-session-${list.minted + 1}`;
  return {
    id,
    title,
    turns: [openTurn(id, 0, title, now, kind)],
    createdAt: now,
    updatedAt: now,
    pinned: false,
    completedAt: null,
    lastVisitedAt: visitedAt,
  };
}

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
    const created = openSession(list, text, now, kind, now);
    return {
      sessions: [created, ...list.sessions],
      activeId: created.id,
      minted: list.minted + 1,
    };
  }
  return {
    ...list,
    sessions: list.sessions.map((session) =>
      session.id === active.id
        ? {
            ...session,
            turns: [...session.turns, openTurn(session.id, session.turns.length, text, now, kind)],
            updatedAt: now,
            // Asking in a conversation IS visiting it, so a follow-up cannot leave the session
            // holding an unread bit against a reader who is sitting in it.
            lastVisitedAt: now,
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

export const setTurnEvidence = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  evidence: Sv3TurnEvidence,
): Sv3SessionList => mapTurn(list, ref, (turn) => ({ ...turn, evidence }));

/**
 * How many sources the answer stood on, DERIVED from the one evidence record — `null` when the
 * backend never reported any, which is not "0 sources". Derived rather than stored so a count and
 * the panel beside it cannot describe different sets (tempdoc 822 Phase F4).
 */
export const sv3TurnSourceCount = (turn: Sv3Turn): number | null =>
  turn.evidence === null ? null : turn.evidence.sources.length;

/**
 * A terminal is BOTH a turn write and a session write, done in one pass so they cannot arrive
 * separately: the turn takes its outcome, and the session records that something finished in it.
 *
 * The unread bit is decided HERE, by whether the session was the claimed one at the moment it
 * finished — a reader who was looking at the conversation has already seen the answer, so its
 * `lastVisitedAt` moves with the completion and the bit never rises (820 W2's unread-completion
 * rule). A turn that already settled is left alone: one terminal per turn.
 *
 * A HALT records no completion at all: the reader stopped it themselves (or left the conversation,
 * which stops it for them), so nothing arrived in their absence and a row that woke up over it would
 * be calling their own decision news.
 */
function settleWith(
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  now: number,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  change: (turn: Sv3Turn) => Sv3Turn,
): Sv3SessionList {
  const session = sessionById(list, ref.sessionId);
  const turn = session?.turns.find((t) => t.id === ref.turnId);
  if (session === null || turn === undefined || turn.status !== 'streaming') return list;
  const claimed = session.id === list.activeId;
  const completed = status !== 'halted';
  return {
    ...list,
    sessions: list.sessions.map((s) =>
      s.id === ref.sessionId
        ? {
            ...s,
            turns: s.turns.map((t) => (t.id === ref.turnId ? change(t) : t)),
            completedAt: completed ? now : s.completedAt,
            lastVisitedAt: completed && claimed ? now : s.lastVisitedAt,
          }
        : s,
    ),
  };
}

/**
 * The turn reaches its ONE terminal. A turn that already settled stays settled: the stream reports
 * exactly one terminal, and a second write could only be a bug re-wording the first.
 */
export const settleTurn = (
  list: Sv3SessionList,
  ref: Sv3TurnRef,
  status: Exclude<Sv3TurnStatus, 'streaming'>,
  now: number,
  detail = '',
): Sv3SessionList => settleWith(list, ref, now, status, (turn) => ({ ...turn, status, detail }));

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
  now: number,
  detail = '',
): Sv3SessionList =>
  settleWith(list, ref, now, status, (turn) => ({ ...turn, status, detail, toolCalls }));

/**
 * A run this window did NOT dispatch, given a session so it can be seen (tempdoc 822 Phase F3).
 *
 * The named F2 finding: window-local in-memory sessions orphan a live run on reload — a fresh window
 * showed nothing while the run went on holding server-side. The shared controller, not this window's
 * memory, is the authority on what is running, so a window that finds a live run with no session
 * SYNTHESISES one rather than rendering an empty sidebar next to a working agent.
 *
 * It does NOT claim the session: an adopted run is news, not a navigation, and yanking the reader
 * out of the conversation they are in would be the window deciding where they should be looking.
 * `lastVisitedAt: 0` follows from that — nobody has seen it, so its completion raises the unread bit.
 */
export interface Sv3Adoption {
  readonly list: Sv3SessionList;
  readonly ref: Sv3TurnRef;
}

export function adoptRunSession(list: Sv3SessionList, title: string, now: number): Sv3Adoption {
  const session = openSession(list, title.trim(), now, 'agent', 0);
  return {
    list: { ...list, sessions: [session, ...list.sessions], minted: list.minted + 1 },
    ref: { sessionId: session.id, turnId: turnIdFor(session.id, 0) },
  };
}

/**
 * The reader parks a conversation on the Pinned shelf, or takes it off. Order is untouched by
 * construction: pinning moves a row between SHELVES and never within one, so a row cannot slide
 * out from under the pointer as a consequence of being pinned.
 */
export function toggleSessionPin(list: Sv3SessionList, id: string): Sv3SessionList {
  if (sessionById(list, id) === null) return list;
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === id ? { ...s, pinned: !s.pinned } : s)),
  };
}

/**
 * The New-search affordance: the window returns to its empty state and the NEXT submit opens a new
 * session. Nothing is dropped — the sessions so far stay in the list, they are just no longer active.
 */
export const startNewSession = (list: Sv3SessionList): Sv3SessionList => ({
  ...list,
  activeId: null,
});

/**
 * Clicking a row claims it; an unknown id changes nothing rather than clearing the claim.
 *
 * A claim IS the visit that clears the unread bit — the reader now has the conversation on screen,
 * so a bit that survived the click would be claiming they had not seen what they are looking at.
 */
export function focusSession(list: Sv3SessionList, id: string, now: number): Sv3SessionList {
  if (sessionById(list, id) === null) return list;
  return {
    ...list,
    activeId: id,
    sessions: list.sessions.map((s) => (s.id === id ? { ...s, lastVisitedAt: now } : s)),
  };
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
  readonly pinned: boolean;
  /** Something finished here while the reader was elsewhere, and they have not been back since. */
  readonly unread: boolean;
}

export interface Sv3SessionGroup {
  readonly id: string;
  readonly label: string;
  readonly rows: readonly Sv3SessionRowView[];
}

/**
 * The three shelves, in render order (tempdoc 822 Phase F3). ACTIVE first because it is the shelf
 * that can be waiting on the reader; Pinned is where they parked things; Recent is the tail.
 *
 * "Recent" rather than the donor's "Settled": a settled RUN is a lifecycle word for something that
 * was working and stopped, and most rows here are conversations that simply ended — "Recent" says
 * the true thing about the shelf (it is the tail in creation order) without implying a run.
 *
 * Snooze is deliberately absent: it needs a menu and a wake timer (820 W2's "raise a hand on fresh
 * blockage"), and a shelf nothing can put a row on would be scaffolding.
 */
type Sv3Shelf = 'active' | 'pinned' | 'recent';

const SHELVES: readonly { readonly shelf: Sv3Shelf; readonly id: string; readonly label: string }[] =
  [
    { shelf: 'active', id: 'sv3-shelf-active', label: 'Active' },
    { shelf: 'pinned', id: 'sv3-shelf-pinned', label: 'Pinned' },
    { shelf: 'recent', id: 'sv3-shelf-recent', label: 'Recent' },
  ];

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
 * Sessions → sidebar SHELVES (tempdoc 822 Phase F3; replaces A2's Today/Earlier recency buckets —
 * the donor's real grouping is state, and the sidebar-comparison finding 4 resolves toward it).
 * A shelf is rendered only when it holds rows, so a window with one running conversation shows one
 * heading rather than a column of empty ones.
 *
 * Two rules the shelves must not break:
 *
 *  - **A run that is working, or blocked on the reader, is on ACTIVE regardless of pin state.** This
 *    is 820 W2's activity-blockers-override: a run waiting on your decision cannot be tucked away on
 *    the shelf where you once parked it. Pin is the reader's intent about a resting conversation; it
 *    does not get to hide a live one.
 *  - **A shelf move is never a reorder.** Rows keep the list's fixed creation order inside every
 *    shelf, so pinning a row changes which heading it sits under and nothing else.
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
      pinned: session.pinned,
      // Unread is a comparison, not a flag anything sets: something finished after the last visit.
      unread: session.completedAt !== null && session.completedAt > session.lastVisitedAt,
    };
  };
  const shelfOf = (session: Sv3Session, row: Sv3SessionRowView): Sv3Shelf => {
    // The colour budget already decided this: act-now means the run is blocked on the reader and
    // in-motion means it is working. Both are ACTIVE, and both outrank the pin (blockers-override).
    if (row.status === 'act-now' || row.status === 'in-motion') return 'active';
    return session.pinned ? 'pinned' : 'recent';
  };
  const rows = new Map<Sv3Shelf, Sv3SessionRowView[]>([
    ['active', []],
    ['pinned', []],
    ['recent', []],
  ]);
  for (const session of list.sessions) {
    const row = toRow(session);
    rows.get(shelfOf(session, row))?.push(row);
  }
  return SHELVES.filter((shelf) => (rows.get(shelf.shelf)?.length ?? 0) > 0).map((shelf) => ({
    id: shelf.id,
    label: shelf.label,
    rows: rows.get(shelf.shelf) ?? [],
  }));
}
