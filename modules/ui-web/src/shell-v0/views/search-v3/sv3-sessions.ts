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
 * **THE PERSISTENCE BOUNDARY, AS OF PHASE F6 — partially resolved.** Phase A2 deferred the whole
 * authority question to Phase D because no canonical "search session" store existed. F6 answers the
 * half that did have an authority: a session IS a conversation, and the product's app-wide
 * conversation authority is `state/conversationListStore.ts`. So:
 *
 *  - **MOVED OUT (the store's now, not ours).** Conversation IDENTITY (`createConversationId`, minted
 *    by the store and handed in — this module mints nothing), EXISTENCE (the store's list is what a
 *    reload projects sessions back from, via {@link mergeStoreConversations}), and TITLE (a rename
 *    writes through to `setConversationTitle`; the store persists it). The TRANSCRIPT moved further
 *    still: turns are a projection of the canonical `/api/thread/{id}` record ({@link applySv3Record},
 *    fed by the shared `fetchUnifiedThread` + `projectUnifiedThread`), so this module holds the
 *    in-flight turn and nothing else is authored here.
 *  - **STAYED WINDOW-LOCAL (deliberately, and still Phase-D's question).** `pinned` and the unread
 *    bit (`completedAt`/`lastVisitedAt`) are reader PREFERENCES about a row in THIS window, and the
 *    conversation store has no field for either. Persisting them here would mint the second authority
 *    A2 refused; they should move to whatever preference store Phase D settles on, alongside the
 *    sidebar's width/collapsed pair. The SHELF projection stays here for the same reason — it is a
 *    presentation of the two window-local prefs plus the live run.
 *
 * Everything here is still PURE: ids, records, and store rows arrive as arguments, so the semantics
 * can be tested without a DOM and this module still performs no IO of its own.
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
// Type-only, and deliberately the LIVE feed's own item type: a turn's record-projected activity and
// a running turn's live feed are the same three shapes, so the content surface has ONE renderer for
// both and a settled run cannot be drawn by a second one (tempdoc 822 Phase F6 / inventory D1).
import type { Sv3RunFeedItem } from './sv3-run.js';

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
  /**
   * What HAPPENED in this turn, in the canonical record's own order (tempdoc 822 Phase F6, inventory
   * D1 / 561 P-A): the agent's prose, its tool calls and its notes INTERLEAVED, never re-sorted into
   * two lists. Empty on a turn the record has not spoken for yet — an in-flight run renders from the
   * live controller feed instead, and yields to this the moment the record catches up. Empty forever
   * on an `ask` turn, whose whole response is {@link answer}.
   */
  readonly activity: readonly Sv3RunFeedItem[];
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
}

export const SV3_SESSIONS_EMPTY: Sv3SessionList = { sessions: [], activeId: null };

/** A conversation the store lists but this window has no title for — never a nameless row. */
export const SV3_UNTITLED_CONVERSATION = 'Untitled conversation';

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
  activity: [],
  askedAt: now,
});

/**
 * The ONE construction of a session, shared by a submit and by an adopted run, so a session can
 * never exist in two shapes. `visitedAt` is the caller's: a submit is made by a reader who is
 * looking at the window, an adopted run is not.
 *
 * `id` is HANDED IN (tempdoc 822 Phase F6): the app-wide conversation store mints conversation ids
 * (`state/conversationListStore.ts:195` `createConversationId`) and a session is a conversation, so
 * a local counter here would be a second identity authority — the fork the header refuses.
 */
function openSession(
  id: string,
  title: string,
  now: number,
  kind: Sv3TurnKind,
  visitedAt: number,
): Sv3Session {
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
  kind: Sv3TurnKind,
  /** The store-minted conversation id, used only when this submit OPENS a conversation. */
  newId: string,
): Sv3SessionList {
  const text = question.trim();
  if (text === '') return list;
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  if (active === null) {
    const created = openSession(newId, text, now, kind, now);
    return {
      sessions: [created, ...list.sessions],
      activeId: created.id,
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

export function adoptRunSession(
  list: Sv3SessionList,
  title: string,
  now: number,
  /**
   * The conversation the run belongs to. When the run already names one this window is listing —
   * the store put it there, or the reader was in it — the run is ADOPTED INTO that conversation
   * rather than beside it: two rows for one conversation would be the identity fork Phase F6 closed.
   */
  conversationId: string,
): Sv3Adoption {
  const existing = sessionById(list, conversationId);
  if (existing !== null) {
    const turnId = turnIdFor(existing.id, existing.turns.length);
    return {
      list: {
        ...list,
        sessions: list.sessions.map((s) =>
          s.id === existing.id
            ? {
                ...s,
                turns: [...s.turns, openTurn(s.id, s.turns.length, title.trim(), now, 'agent')],
                updatedAt: now,
              }
            : s,
        ),
      },
      ref: { sessionId: existing.id, turnId },
    };
  }
  const session = openSession(conversationId, title.trim(), now, 'agent', 0);
  return {
    list: { ...list, sessions: [session, ...list.sessions] },
    ref: { sessionId: session.id, turnId: turnIdFor(session.id, 0) },
  };
}

/* ── The app-wide conversation store, projected in (tempdoc 822 Phase F6 / inventory A1) ─────── */

/**
 * One row of `state/conversationListStore.ts`'s list, as this module consumes it. A structural type
 * rather than the store's `Conversation` class-of-record, so this module stays pure and a test can
 * hand it rows without standing the store up — the same construction `sv3-run.ts`'s `Sv3RunSource`
 * uses for the run controller.
 */
export interface Sv3StoreConversation {
  readonly id: string;
  readonly title: string | null;
  /** Lock-safe (tempdoc 562): the store returns "" while the conversation store is encrypted. */
  readonly firstUserMessage: string;
  readonly createdAt: number;
  readonly lastActiveAt: number;
}

/**
 * The store's conversations, folded into this window's list — the half of A1 that makes a session
 * SURVIVE THE PROCESS. On a cold mount the local list is empty and this IS the session list; on a
 * warm one it adds whatever the product gained elsewhere and re-titles what was renamed elsewhere.
 *
 * Two rules, both the donor's never-reorder law applied to a merge:
 *
 *  - **A known conversation is never re-created and never moved.** It is matched by id, and only its
 *    TITLE is taken from the store (the authority a rename writes through to). Its turns, pin and
 *    unread bit — the parts the store has no field for — are left exactly as they were.
 *  - **A new conversation is APPENDED, not prepended.** A conversation this window did not open is
 *    not its news; putting it at the top would move every row the reader was looking at. On a cold
 *    mount that appends into an empty list, so the store's own newest-first order is the render order.
 *
 * A store row arrives with no turns: the TRANSCRIPT is the canonical record's ({@link applySv3Record}),
 * fetched when the conversation is claimed, not carried on the list row.
 */
export function mergeStoreConversations(
  list: Sv3SessionList,
  conversations: readonly Sv3StoreConversation[],
): Sv3SessionList {
  if (conversations.length === 0) return list;
  const byId = new Map(conversations.map((c) => [c.id, c] as const));
  let changed = false;
  const sessions = list.sessions.map((session) => {
    const row = byId.get(session.id);
    if (row === undefined) return session;
    const title = titleFor(row, session.title);
    if (title === session.title) return session;
    changed = true;
    return { ...session, title };
  });
  const known = new Set(list.sessions.map((s) => s.id));
  const added = conversations
    .filter((c) => !known.has(c.id))
    .map<Sv3Session>((c) => ({
      id: c.id,
      title: titleFor(c, ''),
      turns: [],
      createdAt: c.createdAt,
      updatedAt: c.lastActiveAt,
      pinned: false,
      completedAt: null,
      lastVisitedAt: 0,
    }));
  if (!changed && added.length === 0) return list;
  return { ...list, sessions: [...sessions, ...added] };
}

/**
 * The store's title wins when it HAS one (that is where a rename was written through to); otherwise
 * the conversation keeps the name it already had, and a nameless one falls back to its opening
 * message — which the store blanks while the conversation store is locked, so the last resort is a
 * placeholder rather than an unclickable empty row.
 */
function titleFor(row: Sv3StoreConversation, current: string): string {
  if (row.title !== null && row.title !== '') return row.title;
  // The PLACEHOLDER is not a name. A conversation restored from the per-tab pointer exists before
  // the list that can name it arrives, so treating its stand-in as an established title would leave
  // the row reading "Untitled conversation" forever — the merge must still be allowed to name it.
  // A conversation the reader really named this is unaffected: the store carries that title above.
  if (current !== '' && current !== SV3_UNTITLED_CONVERSATION) return current;
  return row.firstUserMessage !== '' ? row.firstUserMessage : SV3_UNTITLED_CONVERSATION;
}

/**
 * The canonical thread record, projected onto a conversation's turns (tempdoc 822 Phase F6;
 * inventory D1 / tempdoc 561 P-A: *the window is not the authority*).
 *
 * `recordTurns` comes from {@link file://./sv3-record.ts}, which is a pure projection of the SHARED
 * `fetchUnifiedThread` + `projectUnifiedThread` pair — so this window renders the same record the
 * shipped window does, and its history outlives the controller that produced it.
 *
 * THREE THINGS THE RECORD IS NOT ALLOWED TO DO, each because it cannot know the thing it would
 * overwrite:
 *
 *  1. **Touch a STREAMING turn.** The live feed is the authority for the in-flight run and for
 *     nothing else (F2's activeTurnId discipline); a turn still streaming keeps its local id, so the
 *     run's `turnId` stays valid across a refresh, and yields to the record once it settles.
 *  2. **Blank the evidence.** F4's citation marks are resolved from the live stream by the shared
 *     `claimsToCitations`; the record does not carry that resolution, so a refresh keeps whatever the
 *     turn already stood on rather than emptying the panel beside it.
 *  3. **Re-word a HALT.** "The reader pressed Stop" is not in the record — to the backend a halted
 *     answer just ended. Overwriting it with `complete` would call the reader's own decision a
 *     success (the four-terminal rule {@link Sv3TurnStatus} exists to keep them distinct).
 *
 * An EMPTY record leaves the list untouched: `fetchUnifiedThread` returns empty on failure by
 * contract (tempdoc 727 F-8), so "the record said nothing" must never be read as "there is nothing".
 */
export function applySv3Record(
  list: Sv3SessionList,
  sessionId: string,
  recordTurns: readonly Sv3Turn[],
): Sv3SessionList {
  const session = sessionById(list, sessionId);
  if (session === null || recordTurns.length === 0) return list;
  const local = session.turns;
  const merged: Sv3Turn[] = recordTurns.map((recorded, index) => {
    const prior = local[index];
    if (prior === undefined) return recorded;
    if (prior.status === 'streaming') return prior;
    return {
      ...recorded,
      evidence: prior.evidence ?? recorded.evidence,
      status: prior.status === 'halted' ? 'halted' : recorded.status,
      detail: prior.status === 'halted' ? prior.detail : recorded.detail,
      toolCalls: recorded.toolCalls > 0 ? recorded.toolCalls : prior.toolCalls,
    };
  });
  // A turn the record has not been told about yet — the in-flight one, or one dispatched while the
  // fetch was in the air — is KEPT at the tail. The record is authoritative for what it holds, never
  // for what has not reached it.
  for (let index = recordTurns.length; index < local.length; index++) {
    const trailing = local[index];
    if (trailing !== undefined) merged.push(trailing);
  }
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === sessionId ? { ...s, turns: merged } : s)),
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
 * What an edited title should DO, decided before anything is written (tempdoc 822 Phase F5). The
 * donor's own three-way rule, ported verbatim from `apps/web/src/components/chat/ChatHeader.tsx:80-88`
 * ("trim, reject empty (the caller toasts), and skip the mutation when nothing changed") — it is one
 * function there precisely because the donor's sidebar rename and header rename must not diverge, and
 * it is one function here for the same reason.
 *
 * `reject-empty` is a REVERT, not an error state: the conversation keeps the title it had. A window
 * that let a row become nameless would have traded a label the reader can find for one they cannot.
 */
export type Sv3RenameResolution =
  | { readonly action: 'commit'; readonly title: string }
  | { readonly action: 'reject-empty' }
  | { readonly action: 'noop' };

export function resolveSv3Rename(title: string, originalTitle: string): Sv3RenameResolution {
  const trimmed = title.trim();
  if (trimmed.length === 0) return { action: 'reject-empty' };
  if (trimmed === originalTitle) return { action: 'noop' };
  return { action: 'commit', title: trimmed };
}

/**
 * The reader names a conversation. The title was fixed at creation from the opening question and is
 * never re-derived afterwards ({@link Sv3Session.title}), so a rename is permanent by CONSTRUCTION
 * rather than by a precedence flag: there is no auto-titling pass for it to outrank, and a later turn
 * writes `turns`/`updatedAt` and nothing else.
 *
 * An empty or unchanged title leaves the list untouched — {@link resolveSv3Rename} is the one place
 * that decides, so a caller cannot commit a blank by taking a different route in. Phase F6: the
 * caller WRITES THE OUTCOME THROUGH to `setConversationTitle`, so the name survives the process and
 * every surface listing the conversation shows the one the reader chose; the decision still happens
 * exactly here.
 */
export function renameSession(list: Sv3SessionList, id: string, title: string): Sv3SessionList {
  const session = sessionById(list, id);
  if (session === null) return list;
  const resolution = resolveSv3Rename(title, session.title);
  if (resolution.action !== 'commit') return list;
  return {
    ...list,
    sessions: list.sessions.map((s) => (s.id === id ? { ...s, title: resolution.title } : s)),
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
