/**
 * The Search v3 session list's semantics (tempdoc 822 Phase A2).
 *
 * No DOM here on purpose: the module is pure, so create/update/re-run, the grouping rule and the
 * relative-time formatter are all decidable from values alone. The window-level consequences (one
 * dispatch per click, the hero return, the moving `aria-current`) live in `SearchV3View.sessions.test.ts`.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { beforeEach, describe, it, expect } from 'vitest';
import {
  activeTurns,
  adoptRunSession,
  appendTurnDelta,
  applySv3History,
  applySv3Record,
  focusSession,
  latestTurnRef,
  mergeStoreConversations,
  projectSv3Sessions,
  removeSession,
  renameSession,
  resolveSv3Rename,
  restoreSessionTitle,
  sessionById,
  sv3ShouldGenerateTitle,
  sv3SessionIsLive,
  setTurnEvidence,
  setTurnReasoning,
  settleAgentTurn,
  settleTurn,
  startNewSession,
  submitInSession,
  sv3RelativeTime,
  sv3TurnByMessageId,
  sv3TurnMessageIds,
  sv3TurnSourceCount,
  toggleSessionPin,
  SV3_SESSIONS_EMPTY,
  SV3_UNTITLED_CONVERSATION,
  type Sv3Adoption,
  type Sv3RunGate,
  type Sv3Session,
  type Sv3SessionGroup,
  type Sv3SessionHistory,
  type Sv3SessionList,
  type Sv3SessionProjection,
  type Sv3SessionRowView,
  type Sv3StoreConversation,
  type Sv3Turn,
  type Sv3TurnEvidence,
  type Sv3TurnKind,
  type Sv3TurnRef,
} from './sv3-sessions.js';
// Tempdoc 847 §1.3 — the cold-load case merges the REAL record projection, not a hand-built turn:
// the evidence defect lived in that projection, so a fixture that bypassed it would prove nothing.
import { projectSv3RecordTurns } from './sv3-record.js';
import type { Citation } from '../../components/chat/MarkdownBlock.js';
import type { ThreadEvent } from '../unifiedThreadProjection.js';

/**
 * Conversation IDENTITY moved to the app-wide store in Phase F6 (`createConversationId`), so the pure
 * module is HANDED an id rather than minting one. These stand-ins keep the shape the shipped minter
 * produces per call — unique, opaque to the module — and are reset per test so ids stay deterministic.
 */
let mintCount = 0;
const nextConversationId = (): string => `sv3-session-${++mintCount}`;
const submit = (
  list: Sv3SessionList,
  question: string,
  now: number,
  kind: Sv3TurnKind = 'ask',
): Sv3SessionList => submitInSession(list, question, now, kind, nextConversationId());
const adopt = (list: Sv3SessionList, title: string, now: number): Sv3Adoption =>
  adoptRunSession(list, title, now, nextConversationId());

beforeEach(() => {
  mintCount = 0;
});

/** N retrieval sources, shaped as the backend mints them; the fields the panel reads are real. */
const evidence = (count: number): Sv3TurnEvidence => ({
  sources: Array.from({ length: count }, (_unused, i) => ({
    parentDocId: `f:/docs/note-${i}.md`,
    chunkIndex: i,
    chunkTotal: count,
    startChar: 0,
    endChar: 40,
    score: 0.8,
    excerpt: `excerpt ${i}`,
    startLine: 1,
    endLine: 4,
    headingText: 'Notes',
    headingLevel: 2,
  })),
  matches: [],
  marks: [],
  retrievalMode: 'HYBRID',
});

/** Every row on screen, in shelf order — for the cases that are about a row, not about a shelf. */
const flatRows = (groups: readonly Sv3SessionGroup[]): readonly Sv3SessionRowView[] =>
  groups.flatMap((group) => group.rows);

const T0 = Date.parse('2026-08-13T10:00:00Z');
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** The projection's inputs, with the Phase-F2 act-now axis left unset unless a case is about it. */
const proj = (over: Partial<Sv3SessionProjection> = {}): Sv3SessionProjection => ({
  searching: false,
  awaitingDecisionIn: null,
  now: T0,
  ...over,
});

const rest = proj();

describe('a submit creates a session, or appends a turn to the active one', () => {
  it('creates the first session from the question, with one turn against it', () => {
    const list = submit(SV3_SESSIONS_EMPTY, 'northfield lease', T0);
    expect(list.sessions).toHaveLength(1);
    const [session] = list.sessions;
    // The opening question IS the title — nothing generates a name for it.
    expect(session?.title).toBe('northfield lease');
    expect(session?.turns).toHaveLength(1);
    expect(session?.turns[0]?.question).toBe('northfield lease');
    // A turn opens streaming with nothing claimed about its answer yet.
    expect(session?.turns[0]?.status).toBe('streaming');
    expect(session?.turns[0]?.answer).toBe('');
    expect(session?.turns[0]?.evidence).toBeNull();
    expect(session?.createdAt).toBe(T0);
    expect(session?.updatedAt).toBe(T0);
    expect(list.activeId).toBe(session?.id);
  });

  it('APPENDS a turn to the active session rather than opening a second one', () => {
    const first = submit(SV3_SESSIONS_EMPTY, 'vendor risk', T0);
    const second = submit(first, 'vendor risk register', T0 + 2 * MINUTE);
    expect(second.sessions).toHaveLength(1);
    // A conversation ACCUMULATES: the second question is a turn, and the first one survives it.
    expect(second.sessions[0]?.turns.map((t) => t.question)).toEqual([
      'vendor risk',
      'vendor risk register',
    ]);
    // The title is the OPENING question and does not follow the latest turn (F1 semantics).
    expect(second.sessions[0]?.title).toBe('vendor risk');
    expect(second.sessions[0]?.createdAt).toBe(T0);
    expect(second.sessions[0]?.updatedAt).toBe(T0 + 2 * MINUTE);
    expect(second.sessions[0]?.id).toBe(first.sessions[0]?.id);
    expect(second.activeId).toBe(first.activeId);
    // Turn ids are unique within the session, or a stream could write into the wrong turn.
    expect(new Set(second.sessions[0]?.turns.map((t) => t.id)).size).toBe(2);
  });

  it('opens the NEXT session at the top and never moves it again', () => {
    const one = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submit(startNewSession(one), 'second', T0 + MINUTE);
    expect(two.sessions.map((s) => s.title)).toEqual(['second', 'first']);
    // Activity on the OLDER session must not float it back up (charter law: order is fixed).
    const rerun = submit(focusSession(two, two.sessions[1]?.id ?? '', T0), 'more', T0 + HOUR);
    expect(rerun.sessions.map((s) => s.title)).toEqual(['second', 'first']);
    expect(rerun.sessions[1]?.turns).toHaveLength(2);
  });

  it('takes the id it is HANDED, so the store is the only conversation-identity authority', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'a', T0, 'ask', 'uc-alpha');
    expect(one.sessions[0]?.id).toBe('uc-alpha');
    expect(one.activeId).toBe('uc-alpha');
    // Every turn is addressed within that id, so a stream's ref survives the store owning the name.
    expect(one.sessions[0]?.turns[0]?.id.startsWith('uc-alpha')).toBe(true);
    const two = submitInSession(startNewSession(one), 'b', T0, 'ask', 'uc-beta');
    expect(two.sessions.map((s) => s.id)).toEqual(['uc-beta', 'uc-alpha']);
  });

  it('ignores the handed id when a conversation is already claimed — a turn is not a conversation', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'a', T0, 'ask', 'uc-alpha');
    const follow = submitInSession(one, 'b', T0 + MINUTE, 'ask', 'uc-unused');
    expect(follow.sessions.map((s) => s.id)).toEqual(['uc-alpha']);
    expect(follow.sessions[0]?.turns).toHaveLength(2);
  });

  it('is not a submit at all when the query is blank', () => {
    expect(submit(SV3_SESSIONS_EMPTY, '   ', T0)).toBe(SV3_SESSIONS_EMPTY);
    const one = submit(SV3_SESSIONS_EMPTY, 'a', T0);
    expect(submit(one, '\n\t ', T0 + MINUTE)).toBe(one);
  });

  it('trims the question it stores, so the title is not padded by the field', () => {
    const list = submit(SV3_SESSIONS_EMPTY, '  spaced  ', T0);
    expect(list.sessions[0]?.title).toBe('spaced');
    expect(list.sessions[0]?.turns[0]?.question).toBe('spaced');
  });
});

describe('New search parks the active session without dropping it', () => {
  it('clears the claim and keeps every session, so the next submit opens a new one', () => {
    const one = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const parked = startNewSession(one);
    expect(parked.activeId).toBeNull();
    expect(parked.sessions).toHaveLength(1);

    const two = submit(parked, 'second', T0 + MINUTE);
    expect(two.sessions).toHaveLength(2);
    expect(two.activeId).toBe(two.sessions[0]?.id);
  });
});

describe('focusing a session claims it', () => {
  it('moves the claim, so the next submit lands on the clicked session', () => {
    const one = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submit(startNewSession(one), 'second', T0 + MINUTE);
    const olderId = two.sessions[1]?.id ?? '';
    const focused = focusSession(two, olderId, T0 + MINUTE);
    expect(focused.activeId).toBe(olderId);
    const rerun = submit(focused, 'and the renewal?', T0 + 2 * MINUTE);
    expect(rerun.sessions).toHaveLength(2);
    expect(rerun.sessions[1]?.turns).toHaveLength(2);
    expect(rerun.sessions[0]?.turns).toHaveLength(1);
  });

  it('leaves an unknown id alone rather than clearing the claim', () => {
    const one = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    expect(focusSession(one, 'sv3-session-nope', T0)).toBe(one);
    expect(sessionById(one, 'sv3-session-nope')).toBeNull();
  });
});

describe('the relative timestamp is coarse, and never negative', () => {
  it('reads the four rungs and rounds down', () => {
    expect(sv3RelativeTime(T0, T0)).toBe('now');
    expect(sv3RelativeTime(T0, T0 + 59_000)).toBe('now');
    expect(sv3RelativeTime(T0, T0 + MINUTE)).toBe('1m');
    expect(sv3RelativeTime(T0, T0 + 2 * MINUTE + 45_000)).toBe('2m');
    expect(sv3RelativeTime(T0, T0 + 59 * MINUTE)).toBe('59m');
    expect(sv3RelativeTime(T0, T0 + HOUR)).toBe('1h');
    expect(sv3RelativeTime(T0, T0 + 23 * HOUR)).toBe('23h');
    expect(sv3RelativeTime(T0, T0 + DAY)).toBe('1d');
    expect(sv3RelativeTime(T0, T0 + 9 * DAY)).toBe('9d');
  });

  it('reads a backwards clock as "now" rather than as a negative age', () => {
    expect(sv3RelativeTime(T0 + HOUR, T0)).toBe('now');
  });
});

describe('the sidebar projection shelves by STATE and hides what is empty', () => {
  /**
   * A submit OPENS a streaming turn, so a session only comes to rest once its turn has settled.
   * These cases are about grouping and claiming, not about streaming: they use finished turns so a
   * status expectation is not silently satisfied by "still running".
   */
  const ask = (list: Sv3SessionList, text: string, at: number): Sv3SessionList => {
    const next = submit(list, text, at);
    const ref = latestTurnRef(next);
    return ref === null ? next : settleTurn(next, ref, 'complete', at);
  };
  const twoSessions = (): Sv3SessionList => {
    const one = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    return ask(startNewSession(one), 'second', T0 + MINUTE);
  };

  it('renders NO shelf at all before the first question', () => {
    expect(projectSv3Sessions(SV3_SESSIONS_EMPTY, rest)).toEqual([]);
  });

  it('puts settled conversations on ONE Recent shelf, newest first', () => {
    const groups = projectSv3Sessions(twoSessions(), proj({ searching: false, now: T0 + 2 * MINUTE }));
    expect(groups).toHaveLength(1);
    expect(groups[0]?.label).toBe('Recent');
    expect(groups[0]?.rows.map((r) => r.label)).toEqual(['second', 'first']);
    // Age is NOT a shelf any more: a session from last week rests on the same shelf as one from
    // this minute (the state shelves replaced A2's Today/Earlier recency buckets).
    const older = ask(SV3_SESSIONS_EMPTY, 'old', T0 - 3 * DAY);
    const mixed = ask(startNewSession(older), 'new', T0);
    expect(projectSv3Sessions(mixed, rest).map((g) => g.label)).toEqual(['Recent']);
  });

  it('opens the Active shelf for a running session and closes it when it settles', () => {
    const settled = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    const running = submit(startNewSession(settled), 'second', T0 + MINUTE);
    const shelves = projectSv3Sessions(running, proj({ now: T0 + MINUTE }));
    expect(shelves.map((g) => g.label)).toEqual(['Active', 'Recent']);
    expect(shelves[0]?.rows.map((r) => r.label)).toEqual(['second']);
    expect(shelves[1]?.rows.map((r) => r.label)).toEqual(['first']);

    const done = settleTurn(running, latestTurnRef(running) as Sv3TurnRef, 'complete', T0 + 2 * MINUTE);
    const after = projectSv3Sessions(done, proj({ now: T0 + 2 * MINUTE }));
    // The shelf empties rather than persisting as a heading over nothing.
    expect(after.map((g) => g.label)).toEqual(['Recent']);
    expect(after[0]?.rows.map((r) => r.label)).toEqual(['second', 'first']);
  });

  it('opens the Pinned shelf only for pinned, resting sessions', () => {
    const list = twoSessions();
    const pinned = toggleSessionPin(list, list.sessions[1]?.id ?? '');
    const shelves = projectSv3Sessions(pinned, proj({ now: T0 + 2 * MINUTE }));
    expect(shelves.map((g) => g.label)).toEqual(['Pinned', 'Recent']);
    expect(shelves[0]?.rows.map((r) => r.label)).toEqual(['first']);
    expect(shelves[0]?.rows.map((r) => r.pinned)).toEqual([true]);
    expect(shelves[1]?.rows.map((r) => r.label)).toEqual(['second']);

    // Unpinning returns it to the shelf it came from, in the position it always had.
    const back = toggleSessionPin(pinned, list.sessions[1]?.id ?? '');
    const after = projectSv3Sessions(back, proj({ now: T0 + 2 * MINUTE }));
    expect(after.map((g) => g.label)).toEqual(['Recent']);
    expect(after[0]?.rows.map((r) => r.label)).toEqual(['second', 'first']);
  });

  it('keeps a BLOCKED run on Active even when it is pinned (blockers override)', () => {
    // 820 W2's activity-blockers-override: a run waiting on the reader's decision cannot be tucked
    // away on the shelf they parked it on — that is exactly how a blocked run gets forgotten.
    const list = twoSessions();
    const blockedId = list.sessions[1]?.id ?? '';
    const pinned = toggleSessionPin(list, blockedId);
    const shelves = projectSv3Sessions(
      pinned,
      proj({ awaitingDecisionIn: blockedId, now: T0 + 2 * MINUTE }),
    );
    expect(shelves.map((g) => g.label)).toEqual(['Active', 'Recent']);
    expect(shelves[0]?.rows.map((r) => r.label)).toEqual(['first']);
    expect(shelves[0]?.rows.map((r) => r.status)).toEqual(['act-now']);
    // ...and it is still pinned while it sits there, so releasing the block returns it to Pinned.
    expect(shelves[0]?.rows.map((r) => r.pinned)).toEqual([true]);
    expect(projectSv3Sessions(pinned, proj({ now: T0 + 2 * MINUTE })).map((g) => g.label)).toEqual([
      'Pinned',
      'Recent',
    ]);
  });

  it('keeps a WORKING pinned run on Active too, for the same reason', () => {
    const settled = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    const running = submit(startNewSession(settled), 'second', T0 + MINUTE);
    const pinned = toggleSessionPin(running, running.sessions[0]?.id ?? '');
    const shelves = projectSv3Sessions(pinned, proj({ now: T0 + MINUTE }));
    expect(shelves.map((g) => g.label)).toEqual(['Active', 'Recent']);
    expect(shelves[0]?.rows.map((r) => r.status)).toEqual(['in-motion']);
  });

  it('never REORDERS within a shelf — a pin moves a row between shelves and nothing else', () => {
    const one = ask(SV3_SESSIONS_EMPTY, 'a', T0);
    const two = ask(startNewSession(one), 'b', T0 + MINUTE);
    const three = ask(startNewSession(two), 'c', T0 + 2 * MINUTE);
    const rowsOf = (list: Sv3SessionList, label: string): string[] =>
      projectSv3Sessions(list, proj({ now: T0 + 3 * MINUTE }))
        .find((g) => g.label === label)
        ?.rows.map((r) => r.label) ?? [];
    expect(rowsOf(three, 'Recent')).toEqual(['c', 'b', 'a']);
    const pinned = toggleSessionPin(three, three.sessions[1]?.id ?? '');
    expect(rowsOf(pinned, 'Pinned')).toEqual(['b']);
    // The two survivors keep their order; the pinned one did not float to a new position either.
    expect(rowsOf(pinned, 'Recent')).toEqual(['c', 'a']);
    expect(rowsOf(toggleSessionPin(pinned, three.sessions[1]?.id ?? ''), 'Recent')).toEqual([
      'c',
      'b',
      'a',
    ]);
  });

  it('leaves an unknown id unpinned rather than inventing a session', () => {
    const list = twoSessions();
    expect(toggleSessionPin(list, 'sv3-session-nope')).toBe(list);
  });

  it('marks exactly the active session, and only while it is claimed', () => {
    const list = twoSessions();
    const rows = projectSv3Sessions(list, rest)[0]?.rows ?? [];
    expect(rows.filter((r) => r.active).map((r) => r.label)).toEqual(['second']);
    // Parked: no row is current, because no session would take the next submit.
    const parked = projectSv3Sessions(startNewSession(list), rest)[0]?.rows ?? [];
    expect(parked.filter((r) => r.active)).toHaveLength(0);
  });

  it('spends the in-motion colour on the searching session ALONE', () => {
    const list = twoSessions();
    const rows = flatRows(projectSv3Sessions(list, proj({ searching: true, now: T0 + 2 * MINUTE })));
    expect(rows.map((r) => r.status)).toEqual(['in-motion', 'resting']);
    // The running row shows a dot, so it spends no timestamp; the settled one is the other way round.
    expect(rows[0]?.meta).toBe('');
    expect(rows[1]?.meta).toBe('2m');
  });

  it('drops the colour the moment the search settles, without reordering anything', () => {
    const list = twoSessions();
    const running = flatRows(projectSv3Sessions(list, proj({ searching: true, now: T0 + MINUTE })));
    const settled = flatRows(projectSv3Sessions(list, proj({ searching: false, now: T0 + MINUTE })));
    expect(running.map((r) => r.status)).toEqual(['in-motion', 'resting']);
    expect(settled.map((r) => r.status)).toEqual(['resting', 'resting']);
    // The running row was on Active and is now on Recent, but the ORDER of the list is untouched.
    expect(settled.map((r) => r.id)).toEqual(running.map((r) => r.id));
    expect(settled[0]?.meta).toBe('now');
  });

  it('shows the LAST activity, not the creation time, on a session with a second turn', () => {
    const one = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    const rerun = ask(one, 'and then?', T0 + 30 * MINUTE);
    const rows = projectSv3Sessions(rerun, proj({ searching: false, now: T0 + 32 * MINUTE }))[0]?.rows;
    expect(rows?.[0]?.meta).toBe('2m');
  });

  it('spends in-motion on a session whose OWN turn is streaming, claimed or not', () => {
    // The conversational axis is a property of the session, not of the claim: the store flag cannot
    // say who asked, but a streaming turn can.
    const one = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = ask(startNewSession(one), 'second', T0 + MINUTE);
    const shelves = projectSv3Sessions(two, proj({ searching: false, now: T0 + MINUTE }));
    // The streaming one is on ACTIVE even though the reader is claiming the other conversation.
    expect(shelves.map((g) => g.label)).toEqual(['Active', 'Recent']);
    expect(shelves[0]?.rows.map((r) => r.label)).toEqual(['first']);
    expect(shelves[0]?.rows.map((r) => r.status)).toEqual(['in-motion']);
    expect(shelves[0]?.rows[0]?.active).toBe(false);
    expect(shelves[1]?.rows.map((r) => r.label)).toEqual(['second']);
  });

  it('spends the broken colour on a failed or refused turn, and nothing on a halted one', () => {
    const opened = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const ref = latestTurnRef(opened);
    const statusOf = (list: Sv3SessionList): string =>
      flatRows(projectSv3Sessions(list, proj({ searching: false, now: T0 })))[0]?.status ?? '';
    expect(statusOf(settleTurn(opened, ref!, 'failed', T0, 'HTTP 502'))).toBe('broken');
    expect(statusOf(settleTurn(opened, ref!, 'refused', T0))).toBe('broken');
    // Stopping a response is the reader's own act, not a break — it spends no colour.
    expect(statusOf(settleTurn(opened, ref!, 'halted', T0))).toBe('resting');
    expect(statusOf(settleTurn(opened, ref!, 'complete', T0))).toBe('resting');
    // A broken session RESTS: it is settled, so it shelves with the tail rather than staying Active.
    const failed = settleTurn(opened, ref!, 'failed', T0, 'HTTP 502');
    expect(projectSv3Sessions(failed, proj({ now: T0 })).map((g) => g.label)).toEqual(['Recent']);
  });
});

describe('the unread bit says something finished while the reader was elsewhere', () => {
  const unreadOf = (list: Sv3SessionList, label: string): boolean =>
    flatRows(projectSv3Sessions(list, proj({ now: T0 + HOUR }))).find((r) => r.label === label)
      ?.unread ?? false;

  /** Two sessions where the OLDER one is still streaming and the reader has claimed the newer one. */
  const elsewhere = (): { list: Sv3SessionList; ref: Sv3TurnRef; oldId: string } => {
    const one = submit(SV3_SESSIONS_EMPTY, 'the run', T0);
    const ref = latestTurnRef(one) as Sv3TurnRef;
    const two = submit(startNewSession(one), 'something else', T0 + MINUTE);
    return { list: two, ref, oldId: ref.sessionId };
  };

  it('raises the bit when a turn completes in a session that is NOT claimed', () => {
    const { list, ref } = elsewhere();
    expect(unreadOf(list, 'the run')).toBe(false);
    const done = settleTurn(list, ref, 'complete', T0 + 2 * MINUTE);
    expect(unreadOf(done, 'the run')).toBe(true);
    // ...and the session the reader IS in is not marked, because they are looking at it.
    expect(unreadOf(done, 'something else')).toBe(false);
  });

  it('NEVER raises it when the turn completes in the claimed session', () => {
    const one = submit(SV3_SESSIONS_EMPTY, 'here', T0);
    const ref = latestTurnRef(one) as Sv3TurnRef;
    const done = settleTurn(one, ref, 'complete', T0 + MINUTE);
    expect(done.activeId).toBe(ref.sessionId);
    expect(unreadOf(done, 'here')).toBe(false);
    // The completion is still RECORDED — the bit is a comparison, not a missing timestamp.
    expect(done.sessions[0]?.completedAt).toBe(T0 + MINUTE);
    expect(done.sessions[0]?.lastVisitedAt).toBe(T0 + MINUTE);
  });

  it('clears the bit when the reader visits, and claiming is the visit', () => {
    const { list, ref, oldId } = elsewhere();
    const done = settleTurn(list, ref, 'complete', T0 + 2 * MINUTE);
    expect(unreadOf(done, 'the run')).toBe(true);
    const visited = focusSession(done, oldId, T0 + 3 * MINUTE);
    expect(unreadOf(visited, 'the run')).toBe(false);
    // A visit to ANOTHER session leaves it standing — only the session read is read.
    const other = focusSession(done, done.sessions[0]?.id ?? '', T0 + 3 * MINUTE);
    expect(unreadOf(other, 'the run')).toBe(true);
  });

  it('raises it on a failed turn too — a break is news, not silence', () => {
    const { list, ref } = elsewhere();
    expect(unreadOf(settleTurn(list, ref, 'failed', T0 + 2 * MINUTE, 'HTTP 502'), 'the run')).toBe(true);
  });

  it('never raises it on a HALT — the reader stopped that themselves', () => {
    const { list, ref } = elsewhere();
    const halted = settleTurn(list, ref, 'halted', T0 + 2 * MINUTE);
    expect(halted.sessions[1]?.turns[0]?.status).toBe('halted');
    // No completion is recorded at all, so a later visit is not what "clears" a bit that never rose.
    expect(halted.sessions[1]?.completedAt).toBeNull();
    expect(unreadOf(halted, 'the run')).toBe(false);
  });
});

describe('presence: a run this window did not start is adopted as a session', () => {
  it('opens an unclaimed session with one streaming agent turn, at the top of the list', () => {
    const existing = submit(SV3_SESSIONS_EMPTY, 'my own question', T0);
    const { list, ref } = adopt(existing, 'index the vendor folder', T0 + MINUTE);
    const adopted = sessionById(list, ref.sessionId);
    expect(list.sessions.map((s) => s.title)).toEqual(['index the vendor folder', 'my own question']);
    expect(adopted?.turns).toHaveLength(1);
    expect(adopted?.turns[0]?.kind).toBe('agent');
    expect(adopted?.turns[0]?.status).toBe('streaming');
    expect(adopted?.turns[0]?.id).toBe(ref.turnId);
    // The reader is NOT moved into it: an adopted run is news, not a navigation.
    expect(list.activeId).toBe(existing.activeId);
    // Never visited, so its completion can raise the unread bit.
    expect(adopted?.lastVisitedAt).toBe(0);
    expect(adopted?.completedAt).toBeNull();
  });

  it('shelves the adopted run on Active, and marks it unread once it ends unseen', () => {
    const { list, ref } = adopt(SV3_SESSIONS_EMPTY, 'index the vendor folder', T0);
    expect(projectSv3Sessions(list, proj({ now: T0 })).map((g) => g.label)).toEqual(['Active']);
    const ended = settleAgentTurn(list, ref, 'complete', 3, T0 + MINUTE);
    const shelves = projectSv3Sessions(ended, proj({ now: T0 + MINUTE }));
    expect(shelves.map((g) => g.label)).toEqual(['Recent']);
    expect(shelves[0]?.rows[0]?.unread).toBe(true);
    expect(ended.sessions[0]?.turns[0]?.toolCalls).toBe(3);
  });

  it('takes the run’s own conversation id, so a later submit cannot collide with it', () => {
    const { list } = adoptRunSession(SV3_SESSIONS_EMPTY, 'a run', T0, 'uc-run');
    expect(list.sessions[0]?.id).toBe('uc-run');
    const asked = submitInSession(startNewSession(list), 'a question', T0 + MINUTE, 'ask', 'uc-ask');
    expect(asked.sessions.map((s) => s.id)).toEqual(['uc-ask', 'uc-run']);
  });

  it('adopts INTO the conversation the run names when this window already lists it', () => {
    // A run dispatched elsewhere carries its conversation; two rows for one conversation would be
    // the identity fork Phase F6 closed, so the run becomes a TURN in the row that already exists.
    const existing = submitInSession(SV3_SESSIONS_EMPTY, 'my own question', T0, 'ask', 'uc-shared');
    const { list, ref } = adoptRunSession(existing, 'index the vendor folder', T0 + MINUTE, 'uc-shared');
    expect(list.sessions).toHaveLength(1);
    expect(ref.sessionId).toBe('uc-shared');
    const session = sessionById(list, 'uc-shared');
    expect(session?.turns).toHaveLength(2);
    expect(session?.turns[1]?.id).toBe(ref.turnId);
    expect(session?.turns[1]?.kind).toBe('agent');
    expect(session?.turns[1]?.status).toBe('streaming');
    // The title is the conversation's own, not the run's task: the row did not change identity.
    expect(session?.title).toBe('my own question');
  });
});

describe('a turn accumulates its answer and reaches exactly one terminal', () => {
  const opened = (): { list: Sv3SessionList; ref: Sv3TurnRef } => {
    const list = submit(SV3_SESSIONS_EMPTY, 'why did it fail?', T0);
    return { list, ref: latestTurnRef(list) as Sv3TurnRef };
  };

  it('appends deltas in arrival order', () => {
    const { list, ref } = opened();
    const streamed = appendTurnDelta(appendTurnDelta(list, ref, 'Because '), ref, 'the lock held.');
    expect(streamed.sessions[0]?.turns[0]?.answer).toBe('Because the lock held.');
    expect(streamed.sessions[0]?.turns[0]?.status).toBe('streaming');
  });

  it('keeps what streamed before a halt — it was really received', () => {
    const { list, ref } = opened();
    const halted = settleTurn(appendTurnDelta(list, ref, 'Partly'), ref, 'halted', T0 + MINUTE);
    expect(halted.sessions[0]?.turns[0]?.answer).toBe('Partly');
    expect(halted.sessions[0]?.turns[0]?.status).toBe('halted');
  });

  it('ignores a delta and a second terminal after the turn has settled', () => {
    const { list, ref } = opened();
    const done = settleTurn(appendTurnDelta(list, ref, 'Done.'), ref, 'complete', T0 + MINUTE);
    expect(appendTurnDelta(done, ref, ' more').sessions[0]?.turns[0]?.answer).toBe('Done.');
    const twice = settleTurn(done, ref, 'failed', T0 + 2 * MINUTE, 'HTTP 502');
    expect(twice.sessions[0]?.turns[0]?.status).toBe('complete');
    expect(twice.sessions[0]?.turns[0]?.detail).toBe('');
  });

  it('stores the evidence the stream reported, and derives the count from it alone', () => {
    const { list, ref } = opened();
    expect(list.sessions[0]?.turns[0]?.evidence).toBeNull();
    // Never told is not "0 sources", and the derived count says so.
    expect(sv3TurnSourceCount(list.sessions[0]?.turns[0] as Sv3Turn)).toBeNull();

    const three = setTurnEvidence(list, ref, evidence(3));
    const stored = three.sessions[0]?.turns[0] as Sv3Turn;
    expect(stored.evidence?.sources).toHaveLength(3);
    // The MUTATION PROBE for a re-forked count: the number is READ OFF the stored set, so a stored
    // set of three can never be described as any other number.
    expect(sv3TurnSourceCount(stored)).toBe(3);

    // Zero is a REPORTED zero, which is not the same claim as "never said".
    const none = setTurnEvidence(list, ref, evidence(0));
    expect(sv3TurnSourceCount(none.sessions[0]?.turns[0] as Sv3Turn)).toBe(0);
  });

  it('writes only through a ref that exists, so a stale stream cannot invent a turn', () => {
    const { list } = opened();
    const stale: Sv3TurnRef = { sessionId: 'sv3-session-9', turnId: 'sv3-session-9#t1' };
    expect(appendTurnDelta(list, stale, 'x')).toBe(list);
    expect(settleTurn(list, stale, 'complete', T0)).toBe(list);
    expect(settleTurn(list, { sessionId: list.sessions[0]?.id ?? '', turnId: 'nope' }, 'complete', T0))
      .toBe(list);
  });

  it('shows the ACTIVE session\'s turns, and nothing at all when none is claimed', () => {
    const one = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submit(startNewSession(one), 'second', T0 + MINUTE);
    expect(activeTurns(two).map((t) => t.question)).toEqual(['second']);
    expect(activeTurns(startNewSession(two))).toEqual([]);
    expect(activeTurns(SV3_SESSIONS_EMPTY)).toEqual([]);
    // Claiming the older session shows ITS transcript — the list is per-session, not per-window.
    const older = focusSession(two, two.sessions[1]?.id ?? '', T0);
    expect(activeTurns(older).map((t) => t.question)).toEqual(['first']);
  });
});

describe('renaming a conversation (tempdoc 822 Phase F5)', () => {
  // A FIXED id, not a minted one: these cases name the conversation they rename, and two calls to
  // `opened()` in one case must produce the same conversation for that name to mean anything.
  const opened = (): Sv3SessionList =>
    submitInSession(SV3_SESSIONS_EMPTY, 'northfield lease', T0, 'ask', 'sv3-session-1');
  const id = (list: Sv3SessionList): string => list.sessions[0]?.id ?? '';

  it('resolves an edit into commit / revert / noop, the spec rule', () => {
    // Per the design spec — trim, reject empty, skip an unchanged title.
    expect(resolveSv3Rename('  Lease terms  ', 'northfield lease')).toEqual({
      action: 'commit',
      title: 'Lease terms',
    });
    expect(resolveSv3Rename('   ', 'northfield lease')).toEqual({ action: 'reject-empty' });
    expect(resolveSv3Rename('northfield lease', 'northfield lease')).toEqual({ action: 'noop' });
    expect(resolveSv3Rename(' northfield lease ', 'northfield lease')).toEqual({ action: 'noop' });
  });

  it('commits a trimmed title onto the named session and nothing else', () => {
    const list = renameSession(opened(), id(opened()), '  Lease terms  ');
    expect(list.sessions[0]?.title).toBe('Lease terms');
    // The TURN keeps the question it was actually asked: a title is a label, not a rewrite of history.
    expect(list.sessions[0]?.turns[0]?.question).toBe('northfield lease');
  });

  it('reverts an empty title rather than leaving a nameless row', () => {
    const before = opened();
    expect(renameSession(before, id(before), '   ')).toBe(before);
    expect(renameSession(before, id(before), '')).toBe(before);
  });

  it('changes nothing for an unchanged title or an unknown id', () => {
    const before = opened();
    expect(renameSession(before, id(before), 'northfield lease')).toBe(before);
    expect(renameSession(before, 'sv3-session-999', 'Lease terms')).toBe(before);
  });

  it('KEEPS the chosen title when later turns arrive', () => {
    // Rename wins over the opening-question title by construction: nothing re-derives `title`.
    const renamed = renameSession(opened(), 'sv3-session-1', 'Lease terms');
    const later = submit(renamed, 'and the renewal option?', T0 + MINUTE);
    expect(later.sessions[0]?.title).toBe('Lease terms');
    expect(later.sessions[0]?.turns).toHaveLength(2);
    // And the sidebar shows the chosen name, not the question that opened the conversation.
    expect(flatRows(projectSv3Sessions(later, rest)).map((row) => row.label)).toEqual([
      'Lease terms',
    ]);
  });

  it('renames one conversation without touching its neighbours', () => {
    const first = submit(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submit(startNewSession(first), 'second', T0 + MINUTE);
    const renamed = renameSession(two, 'sv3-session-1', 'Renamed first');
    expect(renamed.sessions.map((s) => s.title)).toEqual(['second', 'Renamed first']);
    // A rename is not a reorder either: creation order is render order forever.
    expect(renamed.sessions.map((s) => s.id)).toEqual(two.sessions.map((s) => s.id));
  });
});

describe('the app-wide conversation store, folded in (tempdoc 822 Phase F6 / inventory A1)', () => {
  const row = (over: Partial<Sv3StoreConversation> & { id: string }): Sv3StoreConversation => ({
    title: null,
    firstUserMessage: '',
    createdAt: T0,
    lastActiveAt: T0,
    ...over,
  });

  it('projects a conversation this window has never seen into a listable session', () => {
    const list = mergeStoreConversations(SV3_SESSIONS_EMPTY, [
      row({ id: 'uc-a', firstUserMessage: 'northfield lease', lastActiveAt: T0 + HOUR }),
    ]);
    expect(list.sessions).toHaveLength(1);
    expect(list.sessions[0]?.id).toBe('uc-a');
    expect(list.sessions[0]?.title).toBe('northfield lease');
    // No turns: the TRANSCRIPT is the canonical record's, fetched when the row is claimed.
    expect(list.sessions[0]?.turns).toEqual([]);
    expect(list.sessions[0]?.updatedAt).toBe(T0 + HOUR);
    // Nothing is claimed by a list arriving — that is the reader's decision, not the store's.
    expect(list.activeId).toBeNull();
  });

  it('prefers the store TITLE over the opening message, because that is where a rename landed', () => {
    const list = mergeStoreConversations(SV3_SESSIONS_EMPTY, [
      row({ id: 'uc-a', title: 'Lease terms', firstUserMessage: 'northfield lease' }),
    ]);
    expect(list.sessions[0]?.title).toBe('Lease terms');
  });

  it('names a locked conversation rather than rendering a row with no label', () => {
    // Tempdoc 562 — the store returns "" for `firstUserMessage` while the conversation store is
    // encrypted, so the honest fallback is a placeholder, not an unreadable empty row.
    const list = mergeStoreConversations(SV3_SESSIONS_EMPTY, [row({ id: 'uc-locked' })]);
    expect(list.sessions[0]?.title).toBe(SV3_UNTITLED_CONVERSATION);
  });

  it('never re-creates, re-orders or re-turns a conversation it already lists', () => {
    const mine = submitInSession(SV3_SESSIONS_EMPTY, 'my question', T0, 'ask', 'uc-mine');
    const merged = mergeStoreConversations(mine, [
      row({ id: 'uc-mine', firstUserMessage: 'my question' }),
      row({ id: 'uc-other', firstUserMessage: 'someone else' }),
    ]);
    // The known one keeps its position, its turns and the claim; the new one is APPENDED, because a
    // conversation this window did not open is not its news and must not move the row being read.
    expect(merged.sessions.map((s) => s.id)).toEqual(['uc-mine', 'uc-other']);
    expect(merged.sessions[0]?.turns).toHaveLength(1);
    expect(merged.activeId).toBe('uc-mine');
  });

  it('adopts a title renamed elsewhere, and leaves the window-local pin alone', () => {
    const mine = toggleSessionPin(
      submitInSession(SV3_SESSIONS_EMPTY, 'my question', T0, 'ask', 'uc-mine'),
      'uc-mine',
    );
    const merged = mergeStoreConversations(mine, [row({ id: 'uc-mine', title: 'Renamed there' })]);
    expect(merged.sessions[0]?.title).toBe('Renamed there');
    // Pin is a window-local preference the store has no field for — it must survive the merge.
    expect(merged.sessions[0]?.pinned).toBe(true);
  });

  it('is identity for an empty list and for a list that changes nothing', () => {
    const mine = submitInSession(SV3_SESSIONS_EMPTY, 'my question', T0, 'ask', 'uc-mine');
    expect(mergeStoreConversations(mine, [])).toBe(mine);
    expect(mergeStoreConversations(mine, [row({ id: 'uc-mine' })])).toBe(mine);
  });

  it('seeds `renamed` from the wire, so a reader\'s name survives a reload (tempdoc 838)', () => {
    // The defect this closes: `renamed` used to be hard-coded false on a merged-in row, because the
    // provenance was not on the wire — so the next ask after a reload auto-titled over the name the
    // reader had chosen. It IS on the wire now.
    const merged = mergeStoreConversations(SV3_SESSIONS_EMPTY, [
      row({ id: 'uc-named', title: 'Renewal postmortem', titleSource: 'user' }),
      row({ id: 'uc-auto', title: 'Renewal Lock Failure', titleSource: 'auto' }),
      row({ id: 'uc-plain', firstUserMessage: 'why did the renewal fail?' }),
    ]);
    expect(merged.sessions.map((s) => s.renamed)).toEqual([true, false, false]);
  });

  it('never auto-titles a conversation whose stored name is the reader\'s', () => {
    // The consequence of the seeding above, at the guard that consumes it: a completed ask in a
    // reloaded conversation the reader named is refused a model-generated title.
    const merged = mergeStoreConversations(SV3_SESSIONS_EMPTY, [
      row({ id: 'uc-named', title: 'Renewal postmortem', titleSource: 'user' }),
      row({ id: 'uc-auto', title: 'Renewal Lock Failure', titleSource: 'auto' }),
    ]);
    const answer = (list: Sv3SessionList, id: string): Sv3SessionList => {
      // Claimed first: the reader opened the row, so the ask lands IN it rather than opening a new
      // conversation that would carry none of the merged row's provenance.
      const asked = submitInSession(focusSession(list, id, T0), 'and what did it cost?', T0, 'ask', id);
      const ref = latestTurnRef(asked) as Sv3TurnRef;
      return settleTurn(appendTurnDelta(asked, ref, 'Nine months.'), ref, 'complete', T0 + MINUTE);
    };
    const named = sessionById(answer(merged, 'uc-named'), 'uc-named') as Sv3Session;
    const auto = sessionById(answer(merged, 'uc-auto'), 'uc-auto') as Sv3Session;
    expect(sv3ShouldGenerateTitle(named)).toBe(false);
    // ...and the guard is a refusal, not a breakage: an auto-named conversation is still nameable.
    expect(sv3ShouldGenerateTitle(auto)).toBe(true);
  });
});

describe('a rename the store refused, put back (tempdoc 838)', () => {
  it('restores the previous title AND the previous flag', () => {
    const named = renameSession(
      submitInSession(SV3_SESSIONS_EMPTY, 'my question', T0, 'ask', 'uc-a'),
      'uc-a',
      'Lease terms',
    );
    expect(sessionById(named, 'uc-a')?.renamed).toBe(true);

    const back = restoreSessionTitle(named, 'uc-a', 'my question', false);
    expect(sessionById(back, 'uc-a')?.title).toBe('my question');
    // The flag goes back too: a refused rename must not outrank auto-titling on the strength of a
    // write that never landed.
    expect(sessionById(back, 'uc-a')?.renamed).toBe(false);
  });

  it('restores a conversation that never had a name — what renameSession would refuse', () => {
    const list = mergeStoreConversations(SV3_SESSIONS_EMPTY, [row838('uc-a')]);
    const attempted = renameSession(list, 'uc-a', 'Lease terms');
    expect(sessionById(attempted, 'uc-a')?.title).toBe('Lease terms');
    // renameSession rejects an empty title by design (it is the reader's decision point); a revert is
    // not a reader decision, so it can put back the placeholder the row actually had.
    const back = restoreSessionTitle(attempted, 'uc-a', SV3_UNTITLED_CONVERSATION, false);
    expect(sessionById(back, 'uc-a')?.title).toBe(SV3_UNTITLED_CONVERSATION);
  });

  it('is identity for an unknown id and for a restore that changes nothing', () => {
    const named = renameSession(
      submitInSession(SV3_SESSIONS_EMPTY, 'my question', T0, 'ask', 'uc-a'),
      'uc-a',
      'Lease terms',
    );
    expect(restoreSessionTitle(named, 'uc-missing', 'anything', false)).toBe(named);
    expect(restoreSessionTitle(named, 'uc-a', 'Lease terms', true)).toBe(named);
  });
});

/**
 * A grounded conversation as the WIRE carries it (tempdoc 847 §1.3) — the assistant event with its
 * persisted `citations` and `claimMatches`, which `projectSv3RecordTurns` turns into the turn's
 * evidence. Used by the cold-load case, which is about the whole record → merge path.
 */
const groundedRecord = (): readonly ThreadEvent[] => [
  {
    id: 'g1',
    occurredAt: '2026-08-13T10:00:00.000Z',
    kind: 'USER_MESSAGE',
    originator: 'user',
    content: 'why did the renewal fail?',
    attributes: {},
  },
  {
    id: 'g2',
    occurredAt: '2026-08-13T10:00:01.000Z',
    kind: 'ASSISTANT_MESSAGE',
    originator: 'agent',
    content: 'The lock held.',
    attributes: {
      citations: [
        {
          parentDocId: 'docs/lease.md',
          chunkIndex: 0,
          chunkTotal: 1,
          startChar: 0,
          endChar: 40,
          score: 0.9,
          excerpt: 'The lock held past the renewal date.',
          startLine: 1,
          endLine: 2,
          headingText: 'Renewal',
          headingLevel: 2,
        },
      ],
      claimMatches: {
        scorer: 'CROSS_ENCODER',
        sentencesTotal: 1,
        sentencesScored: 1,
        matches: [
          {
            sentenceIndex: 0,
            sentenceText: 'The lock held.',
            sourceIndex: 0,
            similarity: 0.94,
            parentDocId: 'docs/lease.md',
          },
        ],
      },
    },
  },
];

/** A store row, for the revert cases above (the merge block has its own local `row`). */
const row838 = (id: string): Sv3StoreConversation => ({
  id,
  title: null,
  firstUserMessage: '',
  createdAt: T0,
  lastActiveAt: T0,
});

describe('the canonical record, applied to a conversation (Phase F6 / inventory D1)', () => {
  const recordTurn = (over: Partial<Sv3Turn> & { id: string }): Sv3Turn => ({
    assistantRecordId: null,
    // A record-projected ask turn is opened by its user message; a case that needs the other
    // provenance (a run's activity arriving before any prompt) overrides it.
    recordOpenedByUser: true,
    kind: 'ask',
    question: 'q',
    answer: 'a',
    status: 'complete',
    evidence: null,
    detail: '',
    toolCalls: 0,
    activity: [],
    askedAt: T0,
    // The record carries none of these (Phase F7): `applySv3Record` keeps whatever the live turn
    // observed, so a record turn arrives empty of them exactly as `sv3-record.ts` mints it.
    standaloneQuestion: '',
    reasoning: [],
    durationMs: null,
    modelLabel: null,
    ...over,
    // A record-projected turn carries the record's own id as its `recordId` (tempdoc 847 §2.4.3),
    // exactly as `sv3-record.ts` mints it — that is the identity the merge reconciles on.
    recordId: over.recordId ?? over.id,
  });

  const settled = (): Sv3SessionList => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'why did it fail?', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(list) as Sv3TurnRef;
    return settleTurn(appendTurnDelta(list, ref, 'local text'), ref, 'complete', T0 + MINUTE);
  };

  it('replaces a settled turn’s content with the record’s, KEEPING the turn’s own id', () => {
    // Tempdoc 847 §1.6b — this expectation is inverted deliberately. It used to pin "takes the
    // record's id as the stable handle", which is the defect: `expandedSources`, `copiedTurnId` and
    // the live run's `turnId` are keyed on the LOCAL id, and every one of those writes silently
    // no-ops the moment a merge swaps it. The record's id is kept, as `recordId`, which is what the
    // merge reconciles on. The answer replacement below is unchanged: the record IS authoritative
    // for the content.
    const before = settled();
    const localId = before.sessions[0]?.turns[0]?.id as string;
    const applied = applySv3Record(before, 'uc-a', [
      recordTurn({ id: 'evt-1', question: 'why did it fail?', answer: 'The lock held.' }),
    ]);
    expect(applied.sessions[0]?.turns).toHaveLength(1);
    expect(applied.sessions[0]?.turns[0]?.id).toBe(localId);
    expect(applied.sessions[0]?.turns[0]?.recordId).toBe('evt-1');
    expect(applied.sessions[0]?.turns[0]?.answer).toBe('The lock held.');
  });

  it('reconciles by RECORD IDENTITY, never by position, once a turn has been stamped', () => {
    // Tempdoc 847 §1.6/§2.4.3 — the length-skew case. Turn A is already reconciled to `evt-1`; a
    // record turn `evt-2` may not land on it just because it is first in the list, and the position
    // fallback must skip a local turn bearing a DIFFERENT recordId.
    const two = submitInSession(settled(), 'and the break clause?', T0 + HOUR, 'ask', 'uc-unused');
    const stamped = applySv3Record(two, 'uc-a', [recordTurn({ id: 'evt-1', answer: 'first' })]);
    const settledSecond = settleTurn(
      stamped,
      { sessionId: 'uc-a', turnId: stamped.sessions[0]?.turns[1]?.id as string },
      'complete',
      T0 + HOUR,
    );
    const applied = applySv3Record(settledSecond, 'uc-a', [
      recordTurn({ id: 'evt-2', question: 'and the break clause?', answer: 'second' }),
    ]);
    const turns = applied.sessions[0]?.turns ?? [];
    // `evt-2` reconciled with the SECOND turn; the first kept its own answer and its own record id.
    expect(turns.map((t) => t.recordId)).toEqual(['evt-1', 'evt-2']);
    expect(turns.map((t) => t.answer)).toEqual(['first', 'second']);
  });

  it('does NOT touch a streaming turn — the live feed owns the in-flight one', () => {
    // F2's activeTurnId discipline: the run's `turnId` must stay valid across a refresh, so the
    // record cannot re-id or re-word the turn a stream is still writing to.
    const live = submitInSession(SV3_SESSIONS_EMPTY, 'why did it fail?', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(live) as Sv3TurnRef;
    const applied = applySv3Record(appendTurnDelta(live, ref, 'partial'), 'uc-a', [
      recordTurn({ id: 'evt-1', answer: 'the record thinks it finished' }),
    ]);
    expect(applied.sessions[0]?.turns[0]?.id).toBe(ref.turnId);
    expect(applied.sessions[0]?.turns[0]?.answer).toBe('partial');
    expect(applied.sessions[0]?.turns[0]?.status).toBe('streaming');
  });

  it('keeps the live evidence when the record carries NONE, so a refresh never blanks the panel', () => {
    // The precise intent, stated in the name because the fixture is what makes it true: the record
    // turn below has `evidence: null` (see `recordTurn`), so this case pins ONE end of the merge —
    // a record that reports no evidence at all may not overwrite what the live turn observed. It
    // never defended `prior.evidence ?? recorded.evidence` in the case where BOTH sides carry an
    // evidence object; the two cases beneath it are about that (847 F-12).
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'q', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(list) as Sv3TurnRef;
    const withEvidence = settleTurn(
      setTurnEvidence(list, ref, evidence(3)),
      ref,
      'complete',
      T0 + MINUTE,
    );
    const recorded = recordTurn({ id: 'evt-1' });
    expect(recorded.evidence).toBeNull();
    const applied = applySv3Record(withEvidence, 'uc-a', [recorded]);
    expect(sv3TurnSourceCount(applied.sessions[0]?.turns[0] as Sv3Turn)).toBe(3);
  });

  it('takes the RECORD’s marks when the live turn has an evidence object holding none (847 F-12)', () => {
    // The defect. The live arm publishes an evidence record the moment `rag.meta` names a retrieval
    // mode — before any source or mark exists — so `prior.evidence ?? recorded.evidence` saw a
    // non-null object and discarded the record's complete evidence. The post-`done` refresh IS the
    // repair for a turn whose citation events did not land, and it was thrown away every time it
    // arrived, for the session's whole lifetime: a grounded answer rendered zero citation marks
    // until the page was reloaded, at which point the same record produced them.
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'q', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(list) as Sv3TurnRef;
    const liveButEmpty: Sv3TurnEvidence = {
      sources: [],
      matches: [],
      marks: [],
      retrievalMode: 'HYBRID',
    };
    const settledLive = settleTurn(
      setTurnEvidence(list, ref, liveButEmpty),
      ref,
      'complete',
      T0 + MINUTE,
    );
    const full = evidence(2);
    const recordEvidence: Sv3TurnEvidence = {
      ...full,
      matches: [
        {
          sentenceIndex: 0,
          sentenceText: 'The lock held.',
          sourceIndex: 0,
          similarity: 0.94,
          parentDocId: full.sources[0]?.parentDocId ?? '',
        },
      ],
      marks: [
        {
          sentenceText: 'The lock held.',
          similarity: 0.94,
          sentenceIndex: 0,
          label: 1,
          detail: {
            parentDocId: full.sources[0]?.parentDocId ?? '',
            startLine: 1,
            endLine: 4,
            startChar: 0,
            endChar: 40,
            excerpt: 'excerpt 0',
          },
          hover: { excerpt: 'excerpt 0', title: 'note-0.md', headingText: 'Notes' },
        },
      ],
    };
    const applied = applySv3Record(settledLive, 'uc-a', [
      recordTurn({ id: 'evt-1', evidence: recordEvidence }),
    ]);
    const merged = applied.sessions[0]?.turns[0] as Sv3Turn;
    expect(merged.evidence?.marks).toHaveLength(1);
    expect(merged.evidence?.matches).toHaveLength(1);
    expect(sv3TurnSourceCount(merged)).toBe(2);
    // The live turn's own observation is still the one thing it DID observe.
    expect(merged.evidence?.retrievalMode).toBe('HYBRID');
  });

  it('keeps the LIVE marks when the turn observed some — the record does not overwrite them', () => {
    // The other side of the same rule, so the fix cannot be read as "the record always wins": a turn
    // that watched its own citation events keeps them, which is what rule 2 has always said.
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'q', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(list) as Sv3TurnRef;
    const mark: Citation = {
      sentenceText: 'The live sentence.',
      similarity: 0.91,
      sentenceIndex: 0,
      label: 1,
      detail: {
        parentDocId: 'f:/docs/note-0.md',
        startLine: 1,
        endLine: 4,
        startChar: 0,
        endChar: 40,
        excerpt: 'excerpt 0',
      },
      hover: { excerpt: 'excerpt 0', title: 'note-0.md', headingText: 'Notes' },
    };
    const liveMark: Sv3TurnEvidence = { ...evidence(1), marks: [mark] };
    const settledLive = settleTurn(
      setTurnEvidence(list, ref, liveMark),
      ref,
      'complete',
      T0 + MINUTE,
    );
    const applied = applySv3Record(settledLive, 'uc-a', [
      recordTurn({
        id: 'evt-1',
        evidence: { ...evidence(5), marks: [{ ...mark, sentenceText: 'recorded' }] },
      }),
    ]);
    const merged = applied.sessions[0]?.turns[0] as Sv3Turn;
    expect(merged.evidence?.marks).toHaveLength(1);
    expect(merged.evidence?.marks[0]?.sentenceText).toBe('The live sentence.');
    expect(sv3TurnSourceCount(merged)).toBe(1);
  });

  it('takes the RECORD’s thinking on a cold load, and keeps the LIVE blocks in session (848)', () => {
    // The merge rule at the heart of tempdoc 848's reload story, and the line 847's `applySv3Record`
    // refactor rewrites — pinned here so a later extraction cannot break it silently.
    const cold = applySv3Record(SV3_SESSIONS_EMPTY, 'uc-a', []);
    expect(cold).toBe(SV3_SESSIONS_EMPTY);

    const list = submitInSession(SV3_SESSIONS_EMPTY, 'q', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(list) as Sv3TurnRef;
    const settledNoThinking = settleTurn(list, ref, 'complete', T0 + MINUTE);
    const hydrated = applySv3Record(settledNoThinking, 'uc-a', [
      recordTurn({ id: 'evt-1', reasoning: [{ text: 'recorded thinking', durationMs: 900 }] }),
    ]);
    expect(hydrated.sessions[0]?.turns[0]?.reasoning).toEqual([
      { text: 'recorded thinking', durationMs: 900 },
    ]);

    const withLive = settleTurn(
      setTurnReasoning(list, ref, [{ text: 'live thinking', durationMs: 1200 }]),
      ref,
      'complete',
      T0 + MINUTE,
    );
    const refreshed = applySv3Record(withLive, 'uc-a', [
      recordTurn({ id: 'evt-1', reasoning: [{ text: 'recorded thinking', durationMs: 900 }] }),
    ]);
    expect(refreshed.sessions[0]?.turns[0]?.reasoning).toEqual([
      { text: 'live thinking', durationMs: 1200 },
    ]);
  });

  it('never re-words a HALT as a completion — the reader’s own act is not in the record', () => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'q', T0, 'ask', 'uc-a');
    const ref = latestTurnRef(list) as Sv3TurnRef;
    const halted = settleTurn(list, ref, 'halted', T0 + MINUTE);
    const applied = applySv3Record(halted, 'uc-a', [recordTurn({ id: 'evt-1' })]);
    expect(applied.sessions[0]?.turns[0]?.status).toBe('halted');
  });

  it('KEEPS a turn the record has not been told about yet, at the tail', () => {
    const two = submitInSession(settled(), 'and the break clause?', T0 + HOUR, 'ask', 'uc-unused');
    const applied = applySv3Record(two, 'uc-a', [recordTurn({ id: 'evt-1', question: 'why did it fail?' })]);
    expect(applied.sessions[0]?.turns.map((t) => t.question)).toEqual([
      'why did it fail?',
      'and the break clause?',
    ]);
    expect(applied.sessions[0]?.turns[1]?.status).toBe('streaming');
  });

  it('COLD LOAD — a conversation with no local turns takes the record’s evidence (847 §1.3)', () => {
    // The path no test covered: a page load projects the store's conversations with `turns: []`, so
    // every record turn passes through with no local prior. The record turns here are the REAL
    // projection of REAL wire events rather than hand-built ones, because the defect lived in that
    // projection (`evidence: null`, hardcoded) and a hand-built fixture would have carried evidence
    // the window could never have produced.
    const cold = mergeStoreConversations(SV3_SESSIONS_EMPTY, [row838('uc-cold')]);
    expect(sessionById(cold, 'uc-cold')?.turns).toEqual([]);
    const applied = applySv3Record(cold, 'uc-cold', projectSv3RecordTurns(groundedRecord()));
    const restored = applied.sessions[0]?.turns[0] as Sv3Turn;
    expect(restored.evidence).not.toBeNull();
    expect(sv3TurnSourceCount(restored)).toBe(1);
    expect(restored.evidence?.marks).toHaveLength(1);
    expect(restored.recordId).toBe('g1');
  });

  it('never DUPLICATES an unreconciled turn when the record’s order disagrees with the local one', () => {
    // The record can reconcile a LATER record turn to an EARLIER local turn (a record whose event
    // order differs from the order this window minted its turns in). The output cursor must not
    // rewind when that happens, or the trailing keep-pass re-walks locals it already emitted and
    // appends the unreconciled one a second time — a turn appearing twice in the transcript.
    const list: Sv3SessionList = {
      activeId: 'uc-a',
      sessions: [
        {
          id: 'uc-a',
          title: 'q',
          renamed: false,
          pinned: false,
          completedAt: null,
          lastVisitedAt: 0,
          history: null,
          contextUsage: null,
          createdAt: T0,
          updatedAt: T0,
          turns: [
            recordTurn({ id: 'local-P', recordId: 'evt-2', question: 'P', answer: 'P' }),
            recordTurn({ id: 'local-U', recordId: null, question: 'U', answer: 'U' }),
            recordTurn({ id: 'local-Q', recordId: 'evt-1', question: 'Q', answer: 'Q' }),
          ],
        },
      ],
    };
    const applied = applySv3Record(list, 'uc-a', [
      recordTurn({ id: 'evt-1', question: 'Q', answer: 'Q from the record' }),
      recordTurn({ id: 'evt-2', question: 'P', answer: 'P from the record' }),
    ]);
    const ids = applied.sessions[0]?.turns.map((t) => t.id) ?? [];
    expect(ids).toHaveLength(3);
    expect(ids.filter((id) => id === 'local-U')).toEqual(['local-U']);
    // Each local turn kept its own id and took its OWN record turn's content.
    expect(applied.sessions[0]?.turns.map((t) => [t.id, t.answer])).toEqual([
      ['local-U', 'U'],
      ['local-Q', 'Q from the record'],
      ['local-P', 'P from the record'],
    ]);
  });

  it('changes nothing for an EMPTY record or an unknown conversation', () => {
    // `fetchUnifiedThread` returns empty on failure by contract (727 F-8), so "the record said
    // nothing" must never be readable as "there is nothing".
    const before = settled();
    expect(applySv3Record(before, 'uc-a', [])).toBe(before);
    expect(applySv3Record(before, 'uc-nope', [recordTurn({ id: 'evt-1' })])).toBe(before);
  });
});

/**
 * The row's DISCARD, as semantics (tempdoc 831). The affordance's own cases live in
 * `Sv3SessionRow.actions.test.ts` and the window's write-through in `SearchV3View.sessions.test.ts`;
 * what is decided here is which conversations may leave the list at all, and what leaves with them.
 */
describe('a conversation can be discarded, unless work is in flight in it', () => {
  const ask = (list: Sv3SessionList, text: string, at: number): Sv3SessionList => {
    const next = submit(list, text, at);
    const ref = latestTurnRef(next);
    return ref === null ? next : settleTurn(next, ref, 'complete', at);
  };
  const twoSettled = (): Sv3SessionList => {
    const one = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    return ask(startNewSession(one), 'second', T0 + MINUTE);
  };
  const open: Sv3RunGate = { searching: false, awaitingDecisionIn: null };

  it('deletes the record and leaves every other row exactly where it was', () => {
    const list = twoSettled();
    const goneId = list.sessions[1]?.id ?? '';
    const after = removeSession(list, goneId, open);
    expect(after.sessions.map((s) => s.title)).toEqual(['second']);
    expect(sessionById(after, goneId)).toBeNull();
    // The survivor is the SAME record, not a rebuilt one: a discard is not a reason to re-derive
    // anyone else's turns, pin or unread bit.
    expect(after.sessions[0]).toBe(list.sessions[0]);
  });

  it('drops the claim with the conversation it was pointing at, and only then', () => {
    const list = twoSettled();
    const activeId = list.activeId ?? '';
    expect(removeSession(list, activeId, open).activeId).toBeNull();
    // Discarding a DIFFERENT row leaves the reader where they were.
    expect(removeSession(list, list.sessions[1]?.id ?? '', open).activeId).toBe(activeId);
  });

  it('refuses a conversation whose turn is still streaming', () => {
    // The run outlives the row: deleting it would leave a stream writing to a session nobody can
    // see, and would have told the reader the work was gone while it was still running.
    const running = submit(twoSettled(), 'and then?', T0 + 2 * MINUTE);
    const id = running.activeId ?? '';
    expect(removeSession(running, id, open)).toBe(running);
    // It becomes discardable the moment the turn reaches a terminal — including a failure.
    const failed = settleTurn(running, latestTurnRef(running) as Sv3TurnRef, 'failed', T0 + 3 * MINUTE);
    expect(removeSession(failed, id, open).sessions.map((s) => s.title)).toEqual(['first']);
  });

  it('refuses a conversation whose delegated run is parked on the reader', () => {
    const list = twoSettled();
    const parkedId = list.sessions[1]?.id ?? '';
    const gate: Sv3RunGate = { searching: false, awaitingDecisionIn: parkedId };
    expect(removeSession(list, parkedId, gate)).toBe(list);
    // Parked is not finished — but it only protects ITS OWN conversation, not the whole list.
    expect(removeSession(list, list.sessions[0]?.id ?? '', gate).sessions.map((s) => s.title)).toEqual([
      'first',
    ]);
  });

  it('refuses the ACTIVE conversation while the process-wide search flag is up, and no other', () => {
    // The store cannot say who asked, so the busy flag may only ever speak for the claimed row —
    // the same limit the in-motion colour is under.
    const list = twoSettled();
    const gate: Sv3RunGate = { searching: true, awaitingDecisionIn: null };
    expect(removeSession(list, list.activeId ?? '', gate)).toBe(list);
    expect(removeSession(list, list.sessions[1]?.id ?? '', gate).sessions.map((s) => s.title)).toEqual(
      ['second'],
    );
  });

  it('invents nothing for an unknown id', () => {
    const list = twoSettled();
    expect(removeSession(list, 'sv3-session-nope', open)).toBe(list);
  });

  it('answers the row projection with the SAME judgement it refuses a removal by', () => {
    // The anti-drift case: a row that offers a discard the list would decline (or withholds one it
    // would allow) is the two-authority defect this predicate exists to make impossible. Every
    // live shape is walked, and each is checked BOTH ways.
    const settled = twoSettled();
    const streaming = submit(settled, 'and then?', T0 + 2 * MINUTE);
    const cases: readonly { list: Sv3SessionList; gate: Sv3RunGate }[] = [
      { list: settled, gate: { searching: false, awaitingDecisionIn: null } },
      { list: settled, gate: { searching: true, awaitingDecisionIn: null } },
      { list: settled, gate: { searching: false, awaitingDecisionIn: settled.sessions[1]?.id ?? '' } },
      { list: streaming, gate: { searching: false, awaitingDecisionIn: null } },
    ];
    let sawLive = false;
    for (const { list, gate } of cases) {
      for (const row of flatRows(projectSv3Sessions(list, { ...gate, now: T0 + 3 * MINUTE }))) {
        expect(row.live).toBe(sv3SessionIsLive(list, row.id, gate));
        // ...and the colour agrees too: live IS act-now or in-motion, never a resting row.
        expect(row.live).toBe(row.status === 'act-now' || row.status === 'in-motion');
        expect(removeSession(list, row.id, gate) === list).toBe(row.live);
        if (row.live) sawLive = true;
      }
    }
    // A matrix that never produced a live row would have passed on vacuity alone.
    expect(sawLive).toBe(true);
  });
});

/**
 * Turn identity (tempdoc 852 S1). Three properties, asserted as mechanisms:
 *
 *  - **A message id resolves to the turn that OWNS it, never to the turn at its index.** The two
 *    orders really do differ — a turn holds a user message and one or more assistant messages, and
 *    `/history` counts rows `/api/thread` never emits — so the cases below construct the skew and
 *    assert the index answer is the wrong one.
 *  - **Only an id the CONVERSATION STORE minted is reported.** `GET /api/thread/{id}` interleaves
 *    two planes (`InteractionThreadController.java:66-73`): store rows, and every agent run's events
 *    projected read-time from `AgentRunStore` — which mint user messages (`${runId}:user`),
 *    assistant messages (`${conversationId}:assistant:${stamp}`), workflow node outputs and search
 *    events that exist as messages NOWHERE. The cases construct them and assert `null`.
 *  - **The legacy `idx-N` id survives**, because it is a real store id and rejecting it would make
 *    every pre-513 conversation unbranchable.
 */
describe('a turn’s backend message identity (tempdoc 852 §2.3)', () => {
  const TS = Date.parse('2026-08-19T09:00:00.000Z');
  let tick = 0;
  /** A `FileConversationStore.enrichMessage` id: the UUID it mints before every write (`:213-219`). */
  const stored = (n: number): string => `11111111-2222-4333-8444-55555555555${n}`;
  const event = (
    id: string,
    kind: ThreadEvent['kind'],
    content: string,
    attributes: Record<string, unknown> = {},
  ): ThreadEvent => ({
    id,
    occurredAt: new Date(TS + tick++ * 1000).toISOString(),
    kind,
    originator: kind === 'USER_MESSAGE' ? 'user' : 'agent',
    content,
    attributes,
  });
  const message = (
    id: string,
    role: 'user' | 'assistant',
    content: string,
    attributes: Record<string, unknown> = {},
  ): ThreadEvent =>
    event(id, role === 'user' ? 'USER_MESSAGE' : 'ASSISTANT_MESSAGE', content, attributes);
  const oneSource = [
    {
      parentDocId: 'docs/lease.md',
      chunkIndex: 0,
      chunkTotal: 1,
      startChar: 0,
      endChar: 10,
      score: 0.9,
      excerpt: 'The lock held.',
      startLine: 1,
      endLine: 1,
      headingText: 'Renewal',
      headingLevel: 2,
    },
  ];

  beforeEach(() => {
    tick = 0;
  });

  it('names BOTH of an ask turn’s messages, while still dropping its render list', () => {
    // Tempdoc 852 §2.3a — the projection used to throw the whole activity list away for an ask turn,
    // and every assistant id with it. The RENDERING rule is right and is asserted here too: a
    // one-item activity list would be a second way to draw the same paragraph. Only the identity is
    // kept.
    const [turn] = projectSv3RecordTurns([
      message(stored(0), 'user', 'why did the renewal fail?'),
      message(stored(1), 'assistant', 'The lock held.'),
    ]);
    expect(turn?.kind).toBe('ask');
    expect(turn?.activity).toEqual([]);
    expect(sv3TurnMessageIds(turn as Sv3Turn)).toEqual({
      userMsgId: stored(0),
      assistantMsgId: stored(1),
    });
  });

  it('takes the LAST STORED assistant message, never a run event that arrived after it', () => {
    // The two planes interleave inside ONE turn: the agent's read-time projected message is not a
    // message anybody can address, so a plain last-wins would replace the real id with it.
    const [turn] = projectSv3RecordTurns([
      message(stored(0), 'user', 'why?'),
      message(stored(1), 'assistant', 'One moment.'),
      message(stored(2), 'assistant', 'The lock held.'),
      message('uc-a:assistant:1755600000000', 'assistant', 'And the run says so too.'),
    ]);
    expect(sv3TurnMessageIds(turn as Sv3Turn).assistantMsgId).toBe(stored(2));
  });

  it('names NOTHING for a turn the record has not spoken for', () => {
    // A live turn carries the positional `${sessionId}#t${n}` handle, which addresses a turn inside
    // this tab and nothing on the backend. An affordance that needs a backend id is unavailable
    // until the record arrives, and this is what says so instead of handing over the local handle.
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'why?', T0, 'ask', 'uc-live');
    const turn = list.sessions[0]?.turns[0] as Sv3Turn;
    expect(turn.id).toBe('uc-live#t1');
    expect(sv3TurnMessageIds(turn)).toEqual({ userMsgId: null, assistantMsgId: null });
  });

  it('reports the legacy idx-N id, which IS a real store id', () => {
    // PREMISE: `FileConversationStore.loadHistory` back-fills `idx-N` for pre-513 messages precisely
    // "so the FE can pass it back to the branch endpoint" (`:159-165`). Rejecting it as foreign
    // would make every legacy conversation unbranchable.
    const [turn] = projectSv3RecordTurns([
      message('idx-4', 'user', 'why?'),
      message('idx-5', 'assistant', 'The lock held.'),
    ]);
    expect(sv3TurnMessageIds(turn as Sv3Turn)).toEqual({
      userMsgId: 'idx-4',
      assistantMsgId: 'idx-5',
    });
  });

  it('names NOTHING for an AGENT RUN’s turn — its messages are a projection, not store rows', () => {
    // Both halves of a run turn are minted by the run plane: the user message by
    // `AgentRunQueryService` (`${runId}:user`, `:346-350`) and the answer by
    // `AgentInteractionMapper` (`${conversationId}:assistant:${stamp}`, `:69`) — `AgentSession`
    // keeps the run's messages in its own state and writes none of them to the conversation store.
    // `POST …/messages/{id}/exclude` would therefore mis-target on either.
    const [turn] = projectSv3RecordTurns([
      message('run-7:user', 'user', 'index the vendor folder'),
      event('c1:proposed', 'TOOL_ACTIVITY', '', { callId: 'c1', toolName: 'core_search' }),
      message('uc-a:assistant:1755600000000', 'assistant', 'Indexed.'),
    ]);
    expect(turn?.kind).toBe('agent');
    expect(sv3TurnMessageIds(turn as Sv3Turn)).toEqual({ userMsgId: null, assistantMsgId: null });
  });

  it('names no USER message for a turn the record opened on something that is not one', () => {
    // `projectSv3RecordTurns` opens a turn on whatever arrives before any user item — a run whose
    // prompt was never recorded (the behaviour `sv3-record.test.ts` pins). That item's id becomes the
    // turn's `recordId` without being anybody's message, so provenance decides here, not shape.
    const [turn] = projectSv3RecordTurns([
      event('uc-a:search:1755600000000', 'SEARCH', 'renewal', {}),
      message(stored(3), 'assistant', 'Found it.'),
    ]);
    expect(turn?.question).toBe('');
    expect(sv3TurnMessageIds(turn as Sv3Turn)).toEqual({
      userMsgId: null,
      // ...and the STORED assistant message in the same turn is still addressable: provenance is
      // decided per message, not written off per turn.
      assistantMsgId: stored(3),
    });
  });

  it('names no USER message when the turn opens on a STORED ASSISTANT row — shape alone is not provenance', () => {
    // The case the id's shape cannot decide, and it is reachable: a sealed first line becomes a
    // `role:"locked"` placeholder (`FileConversationStore.java:149-157`) that `chatTurn` drops
    // (`:247-259`), so the thread can OPEN on a stored assistant row. Its id is a real store UUID —
    // and it is an assistant message, not the user message `?fromMsgId=` and a branch point mean.
    const [turn] = projectSv3RecordTurns([
      message(stored(0), 'assistant', 'The lock held.'),
      message(stored(1), 'user', 'and the second one?'),
      message(stored(2), 'assistant', 'The same lock.'),
    ]);
    expect(sv3TurnMessageIds(turn as Sv3Turn)).toEqual({
      userMsgId: null,
      assistantMsgId: stored(0),
    });
  });

  it('names NOTHING for chatTurn’s own synthesised fallback id', () => {
    // PREMISE, and the reason this is a guard rather than a live expectation: a store-backed
    // conversation cannot produce this shape. `enrichMessage` mints a UUID before every write
    // (`FileConversationStore.java:213-219`), `loadHistory` back-fills `idx-N` on read (`:162-165`)
    // and a sealed line becomes an `idx-N-locked` placeholder (`:149-155`) — so `chatTurn`'s
    // `conversationId + ":chat:" + msg.hashCode()` fallback
    // (`InteractionThreadController.java:260-262`) is unreachable for anything the store holds. It
    // is reachable for a row that came from somewhere else, and that id exists in no store.
    const [turn] = projectSv3RecordTurns([
      message('uc-a:chat:-1873452901', 'user', 'why?'),
      message('uc-a:chat:88123', 'assistant', 'The lock held.'),
    ]);
    expect(sv3TurnMessageIds(turn as Sv3Turn)).toEqual({ userMsgId: null, assistantMsgId: null });
  });

  it('resolves a /history message id to the turn that OWNS it, not to the turn at its index', () => {
    // The skew, constructed: `/history` returns every row it read, INCLUDING the `role:"locked"`
    // placeholder a sealed line becomes (`FileConversationStore.java:149-157`), while
    // `/api/thread`'s `chatTurn` returns null for every role that is not user/assistant
    // (`InteractionThreadController.java:247-259`). So the two arrays differ in length AND in
    // position — and turns group messages besides, so the nth message is not the nth turn either.
    const historyMessages = [
      { id: stored(0), role: 'user' },
      { id: stored(1), role: 'assistant' },
      { id: 'idx-2-locked', role: 'locked' },
      { id: stored(3), role: 'user' },
      { id: stored(4), role: 'assistant' },
    ];
    const turns = projectSv3RecordTurns([
      message(stored(0), 'user', 'why did the renewal fail?'),
      message(stored(1), 'assistant', 'The lock held.'),
      message(stored(3), 'user', 'and the second one?'),
      message(stored(4), 'assistant', 'The same lock.'),
    ]);
    expect(turns).toHaveLength(2);
    // What `/history` carries and only it: a floor and an exclusion, each naming a MESSAGE.
    expect(sv3TurnByMessageId(turns, stored(3))).toBe(turns[1]);
    expect(sv3TurnByMessageId(turns, stored(4))).toBe(turns[1]);
    expect(sv3TurnByMessageId(turns, stored(0))).toBe(turns[0]);
    // ...and the index answer is a DIFFERENT, entirely plausible-looking turn: the first turn's
    // assistant message sits at position 1 of the history array, so a pairing by position would
    // attach the first exchange's floor to the second one, silently.
    expect(historyMessages.findIndex((m) => m.id === stored(1))).toBe(1);
    expect(sv3TurnByMessageId(turns, stored(1))).toBe(turns[0]);
    expect(sv3TurnByMessageId(turns, stored(1))).not.toBe(turns[1]);
    // A row the thread endpoint dropped resolves to nothing — the honest answer, and never a
    // neighbour picked up by position.
    expect(sv3TurnByMessageId(turns, 'idx-2-locked')).toBeNull();
    expect(historyMessages.findIndex((m) => m.id === 'idx-2-locked')).toBe(2);
  });

  it('attaches the record’s evidence and ids to the turn bearing its id when the ORDERS disagree', () => {
    // The merge's own half of the same property (847 §2.4.3, extended to the assistant id): a second
    // refresh whose record emits the newer exchange FIRST must still land each record turn's
    // evidence and message ids on the turn carrying its `recordId`.
    let list = submitInSession(SV3_SESSIONS_EMPTY, 'why did the renewal fail?', T0, 'ask', 'uc-h');
    const first = latestTurnRef(list) as Sv3TurnRef;
    list = settleTurn(list, first, 'complete', T0 + MINUTE);
    list = applySv3Record(list, 'uc-h', projectSv3RecordTurns([
      message(stored(0), 'user', 'why did the renewal fail?'),
      message(stored(1), 'assistant', 'The lock held.'),
    ]));
    list = submitInSession(list, 'and the second one?', T0 + 2 * MINUTE, 'ask', 'uc-h');
    const second = latestTurnRef(list) as Sv3TurnRef;
    list = settleTurn(list, second, 'complete', T0 + 3 * MINUTE);
    tick = 10;
    list = applySv3Record(list, 'uc-h', projectSv3RecordTurns([
      message(stored(3), 'user', 'and the second one?', {}),
      message(stored(4), 'assistant', 'The same lock.', { citations: oneSource }),
      message(stored(0), 'user', 'why did the renewal fail?'),
      message(stored(1), 'assistant', 'The lock held.'),
    ]));
    const turns = list.sessions[0]?.turns as readonly Sv3Turn[];
    const opener = sv3TurnByMessageId(turns, stored(0)) as Sv3Turn;
    const follow = sv3TurnByMessageId(turns, stored(3)) as Sv3Turn;
    expect(opener.question).toBe('why did the renewal fail?');
    expect(follow.question).toBe('and the second one?');
    expect(sv3TurnMessageIds(opener)).toEqual({
      userMsgId: stored(0),
      assistantMsgId: stored(1),
    });
    expect(sv3TurnMessageIds(follow)).toEqual({
      userMsgId: stored(3),
      assistantMsgId: stored(4),
    });
    // The evidence rode with the id, not with the position: only the second exchange was grounded.
    expect(follow.evidence?.sources).toHaveLength(1);
    expect(opener.evidence).toBeNull();
  });
});

describe('the /history companion load (tempdoc 852 §2.3c)', () => {
  const history: Sv3SessionHistory = {
    parentSessionId: 'uc-parent',
    branchPointMessageId: 'm1',
    parentFirstUserMessage: 'the original question',
    contextFloor: 'm3',
    contextFloorSummary: 'Everything above was compacted.',
    excludedMessageIds: ['m4'],
    excludedSourceIds: ['docs/lease.md0'],
    locked: false,
  };

  it('is null until the load happens — “not told” is not “no floor and no parent”', () => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'why?', T0, 'ask', 'uc-a');
    expect(list.sessions[0]?.history).toBeNull();
    expect(mergeStoreConversations(SV3_SESSIONS_EMPTY, [
      { id: 'uc-b', title: 'b', firstUserMessage: '', createdAt: T0, lastActiveAt: T0 },
    ]).sessions[0]?.history).toBeNull();
  });

  it('records what only /history carries, on the conversation it names', () => {
    let list = submitInSession(SV3_SESSIONS_EMPTY, 'why?', T0, 'ask', 'uc-a');
    list = submitInSession(startNewSession(list), 'other', T0, 'ask', 'uc-b');
    const applied = applySv3History(list, 'uc-a', history);
    expect(sessionById(applied, 'uc-a')?.history).toEqual(history);
    // The OTHER conversation is untouched: a companion load is about one conversation.
    expect(sessionById(applied, 'uc-b')?.history).toBeNull();
    // ...and the transcript is not the load's to write — it carries no messages at all.
    expect(sessionById(applied, 'uc-a')?.turns).toEqual(sessionById(list, 'uc-a')?.turns);
  });

  it('drops a load for a conversation this window is not listing', () => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'why?', T0, 'ask', 'uc-a');
    expect(applySv3History(list, 'uc-gone', history)).toBe(list);
  });
});

describe('the fixture sessions are swept, not merely unused', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const sources = readdirSync(here).filter((name) => name.endsWith('.ts'));

  it('leaves no reference to the retired fixture list anywhere in the window', () => {
    // Retire-with-a-sweep: a fixture that outlives its reason is false authority — the next reader
    // cannot tell whether the sidebar renders sessions or a fixture list. Tests included: a case
    // still asserting against SIDEBAR_GROUPS would be asserting about nothing that is on screen.
    const retired = ['SIDEBAR_GROUPS', 'SIDEBAR_ROWS', 'sidebarGroupsFor', 'Sv3FixtureSet'];
    const offenders: string[] = [];
    for (const name of sources) {
      // This file names the retired symbols in order to forbid them — the one allowed mention.
      if (name === 'sv3-sessions.test.ts') continue;
      const text = readFileSync(join(here, name), 'utf8');
      for (const symbol of retired) {
        if (text.includes(symbol)) offenders.push(`${name} still references ${symbol}`);
      }
    }
    expect(offenders).toEqual([]);
    // The scan is worth something only if it actually read the window's files.
    expect(sources.length).toBeGreaterThan(10);
  });
});
