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
import { describe, it, expect } from 'vitest';
import {
  activeTurns,
  adoptRunSession,
  appendTurnDelta,
  focusSession,
  latestTurnRef,
  projectSv3Sessions,
  sessionById,
  setTurnCitations,
  settleAgentTurn,
  settleTurn,
  startNewSession,
  submitInSession,
  sv3RelativeTime,
  toggleSessionPin,
  SV3_SESSIONS_EMPTY,
  type Sv3SessionGroup,
  type Sv3SessionList,
  type Sv3SessionProjection,
  type Sv3SessionRowView,
  type Sv3TurnRef,
} from './sv3-sessions.js';

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
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'northfield lease', T0);
    expect(list.sessions).toHaveLength(1);
    const [session] = list.sessions;
    // The opening question IS the title — nothing generates a name for it.
    expect(session?.title).toBe('northfield lease');
    expect(session?.turns).toHaveLength(1);
    expect(session?.turns[0]?.question).toBe('northfield lease');
    // A turn opens streaming with nothing claimed about its answer yet.
    expect(session?.turns[0]?.status).toBe('streaming');
    expect(session?.turns[0]?.answer).toBe('');
    expect(session?.turns[0]?.citations).toBeNull();
    expect(session?.createdAt).toBe(T0);
    expect(session?.updatedAt).toBe(T0);
    expect(list.activeId).toBe(session?.id);
  });

  it('APPENDS a turn to the active session rather than opening a second one', () => {
    const first = submitInSession(SV3_SESSIONS_EMPTY, 'vendor risk', T0);
    const second = submitInSession(first, 'vendor risk register', T0 + 2 * MINUTE);
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
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submitInSession(startNewSession(one), 'second', T0 + MINUTE);
    expect(two.sessions.map((s) => s.title)).toEqual(['second', 'first']);
    // Activity on the OLDER session must not float it back up (charter law: order is fixed).
    const rerun = submitInSession(focusSession(two, two.sessions[1]?.id ?? '', T0), 'more', T0 + HOUR);
    expect(rerun.sessions.map((s) => s.title)).toEqual(['second', 'first']);
    expect(rerun.sessions[1]?.turns).toHaveLength(2);
  });

  it('mints an id per session, so two sessions are never the same row', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'a', T0);
    const two = submitInSession(startNewSession(one), 'b', T0);
    expect(new Set(two.sessions.map((s) => s.id)).size).toBe(2);
    expect(two.minted).toBe(2);
  });

  it('is not a submit at all when the query is blank', () => {
    expect(submitInSession(SV3_SESSIONS_EMPTY, '   ', T0)).toBe(SV3_SESSIONS_EMPTY);
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'a', T0);
    expect(submitInSession(one, '\n\t ', T0 + MINUTE)).toBe(one);
  });

  it('trims the question it stores, so the title is not padded by the field', () => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, '  spaced  ', T0);
    expect(list.sessions[0]?.title).toBe('spaced');
    expect(list.sessions[0]?.turns[0]?.question).toBe('spaced');
  });
});

describe('New search parks the active session without dropping it', () => {
  it('clears the claim and keeps every session, so the next submit opens a new one', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const parked = startNewSession(one);
    expect(parked.activeId).toBeNull();
    expect(parked.sessions).toHaveLength(1);

    const two = submitInSession(parked, 'second', T0 + MINUTE);
    expect(two.sessions).toHaveLength(2);
    expect(two.activeId).toBe(two.sessions[0]?.id);
  });
});

describe('focusing a session claims it', () => {
  it('moves the claim, so the next submit lands on the clicked session', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submitInSession(startNewSession(one), 'second', T0 + MINUTE);
    const olderId = two.sessions[1]?.id ?? '';
    const focused = focusSession(two, olderId, T0 + MINUTE);
    expect(focused.activeId).toBe(olderId);
    const rerun = submitInSession(focused, 'and the renewal?', T0 + 2 * MINUTE);
    expect(rerun.sessions).toHaveLength(2);
    expect(rerun.sessions[1]?.turns).toHaveLength(2);
    expect(rerun.sessions[0]?.turns).toHaveLength(1);
  });

  it('leaves an unknown id alone rather than clearing the claim', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
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
    const next = submitInSession(list, text, at);
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
    const running = submitInSession(startNewSession(settled), 'second', T0 + MINUTE);
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
    const running = submitInSession(startNewSession(settled), 'second', T0 + MINUTE);
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
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
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
    const opened = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
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
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'the run', T0);
    const ref = latestTurnRef(one) as Sv3TurnRef;
    const two = submitInSession(startNewSession(one), 'something else', T0 + MINUTE);
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
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'here', T0);
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
    const existing = submitInSession(SV3_SESSIONS_EMPTY, 'my own question', T0);
    const { list, ref } = adoptRunSession(existing, 'index the vendor folder', T0 + MINUTE);
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
    const { list, ref } = adoptRunSession(SV3_SESSIONS_EMPTY, 'index the vendor folder', T0);
    expect(projectSv3Sessions(list, proj({ now: T0 })).map((g) => g.label)).toEqual(['Active']);
    const ended = settleAgentTurn(list, ref, 'complete', 3, T0 + MINUTE);
    const shelves = projectSv3Sessions(ended, proj({ now: T0 + MINUTE }));
    expect(shelves.map((g) => g.label)).toEqual(['Recent']);
    expect(shelves[0]?.rows[0]?.unread).toBe(true);
    expect(ended.sessions[0]?.turns[0]?.toolCalls).toBe(3);
  });

  it('mints its own id, so a later submit cannot collide with it', () => {
    const { list } = adoptRunSession(SV3_SESSIONS_EMPTY, 'a run', T0);
    const asked = submitInSession(startNewSession(list), 'a question', T0 + MINUTE);
    expect(new Set(asked.sessions.map((s) => s.id)).size).toBe(2);
    expect(asked.minted).toBe(2);
  });
});

describe('a turn accumulates its answer and reaches exactly one terminal', () => {
  const opened = (): { list: Sv3SessionList; ref: Sv3TurnRef } => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'why did it fail?', T0);
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

  it('records the citation count the stream reported, and leaves it unknown otherwise', () => {
    const { list, ref } = opened();
    expect(list.sessions[0]?.turns[0]?.citations).toBeNull();
    expect(setTurnCitations(list, ref, 3).sessions[0]?.turns[0]?.citations).toBe(3);
    // Zero is a REPORTED zero, which is not the same claim as "never said".
    expect(setTurnCitations(list, ref, 0).sessions[0]?.turns[0]?.citations).toBe(0);
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
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submitInSession(startNewSession(one), 'second', T0 + MINUTE);
    expect(activeTurns(two).map((t) => t.question)).toEqual(['second']);
    expect(activeTurns(startNewSession(two))).toEqual([]);
    expect(activeTurns(SV3_SESSIONS_EMPTY)).toEqual([]);
    // Claiming the older session shows ITS transcript — the list is per-session, not per-window.
    const older = focusSession(two, two.sessions[1]?.id ?? '', T0);
    expect(activeTurns(older).map((t) => t.question)).toEqual(['first']);
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
