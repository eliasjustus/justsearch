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
  appendTurnDelta,
  focusSession,
  latestTurnRef,
  projectSv3Sessions,
  sessionById,
  setTurnCitations,
  settleTurn,
  startNewSession,
  submitInSession,
  sv3RelativeTime,
  SV3_SESSIONS_EMPTY,
  type Sv3SessionList,
  type Sv3TurnRef,
} from './sv3-sessions.js';

const T0 = Date.parse('2026-08-13T10:00:00Z');
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const rest = { searching: false, now: T0 };

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
    const rerun = submitInSession(focusSession(two, two.sessions[1]?.id ?? ''), 'more', T0 + HOUR);
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
    const focused = focusSession(two, olderId);
    expect(focused.activeId).toBe(olderId);
    const rerun = submitInSession(focused, 'and the renewal?', T0 + 2 * MINUTE);
    expect(rerun.sessions).toHaveLength(2);
    expect(rerun.sessions[1]?.turns).toHaveLength(2);
    expect(rerun.sessions[0]?.turns).toHaveLength(1);
  });

  it('leaves an unknown id alone rather than clearing the claim', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    expect(focusSession(one, 'sv3-session-nope')).toBe(one);
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

describe('the sidebar projection groups by recency and hides what is empty', () => {
  /**
   * A submit OPENS a streaming turn, so a session only comes to rest once its turn has settled.
   * These cases are about grouping and claiming, not about streaming: they use finished turns so a
   * status expectation is not silently satisfied by "still running".
   */
  const ask = (list: Sv3SessionList, text: string, at: number): Sv3SessionList => {
    const next = submitInSession(list, text, at);
    const ref = latestTurnRef(next);
    return ref === null ? next : settleTurn(next, ref, 'complete');
  };
  const twoSessions = (): Sv3SessionList => {
    const one = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    return ask(startNewSession(one), 'second', T0 + MINUTE);
  };

  it('renders NO group at all before the first search', () => {
    expect(projectSv3Sessions(SV3_SESSIONS_EMPTY, rest)).toEqual([]);
  });

  it('puts this visit under one Today label, newest first', () => {
    const groups = projectSv3Sessions(twoSessions(), { searching: false, now: T0 + 2 * MINUTE });
    expect(groups).toHaveLength(1);
    expect(groups[0]?.label).toBe('Today');
    expect(groups[0]?.rows.map((r) => r.label)).toEqual(['second', 'first']);
  });

  it('opens the Earlier group only once a session is no longer from today', () => {
    const yesterday = ask(SV3_SESSIONS_EMPTY, 'old', T0 - DAY);
    const today = ask(startNewSession(yesterday), 'new', T0);
    const groups = projectSv3Sessions(today, rest);
    expect(groups.map((g) => g.label)).toEqual(['Today', 'Earlier']);
    expect(groups[0]?.rows.map((r) => r.label)).toEqual(['new']);
    expect(groups[1]?.rows.map((r) => r.label)).toEqual(['old']);

    // ...and with only old sessions, TODAY is the group that disappears — the rule is emptiness,
    // not a fixed first label.
    expect(projectSv3Sessions(yesterday, rest).map((g) => g.label)).toEqual(['Earlier']);
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
    const rows = projectSv3Sessions(list, { searching: true, now: T0 + 2 * MINUTE })[0]?.rows ?? [];
    expect(rows.map((r) => r.status)).toEqual(['in-motion', 'resting']);
    // The running row shows a dot, so it spends no timestamp; the settled one is the other way round.
    expect(rows[0]?.meta).toBe('');
    expect(rows[1]?.meta).toBe('2m');
  });

  it('drops the colour the moment the search settles, without reordering anything', () => {
    const list = twoSessions();
    const running = projectSv3Sessions(list, { searching: true, now: T0 + MINUTE })[0]?.rows ?? [];
    const settled = projectSv3Sessions(list, { searching: false, now: T0 + MINUTE })[0]?.rows ?? [];
    expect(running.map((r) => r.status)).toEqual(['in-motion', 'resting']);
    expect(settled.map((r) => r.status)).toEqual(['resting', 'resting']);
    expect(settled.map((r) => r.id)).toEqual(running.map((r) => r.id));
    expect(settled[0]?.meta).toBe('now');
  });

  it('shows the LAST activity, not the creation time, on a session with a second turn', () => {
    const one = ask(SV3_SESSIONS_EMPTY, 'first', T0);
    const rerun = ask(one, 'and then?', T0 + 30 * MINUTE);
    const rows = projectSv3Sessions(rerun, { searching: false, now: T0 + 32 * MINUTE })[0]?.rows;
    expect(rows?.[0]?.meta).toBe('2m');
  });

  it('spends in-motion on a session whose OWN turn is streaming, claimed or not', () => {
    // The conversational axis is a property of the session, not of the claim: the store flag cannot
    // say who asked, but a streaming turn can.
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = ask(startNewSession(one), 'second', T0 + MINUTE);
    const rows = projectSv3Sessions(two, { searching: false, now: T0 + MINUTE })[0]?.rows ?? [];
    expect(rows.map((r) => r.label)).toEqual(['second', 'first']);
    expect(rows.map((r) => r.status)).toEqual(['resting', 'in-motion']);
    expect(rows[1]?.active).toBe(false);
  });

  it('spends the broken colour on a failed or refused turn, and nothing on a halted one', () => {
    const opened = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const ref = latestTurnRef(opened);
    const statusOf = (list: Sv3SessionList): string =>
      projectSv3Sessions(list, { searching: false, now: T0 })[0]?.rows[0]?.status ?? '';
    expect(statusOf(settleTurn(opened, ref!, 'failed', 'HTTP 502'))).toBe('broken');
    expect(statusOf(settleTurn(opened, ref!, 'refused'))).toBe('broken');
    // Stopping a response is the reader's own act, not a break — it spends no colour.
    expect(statusOf(settleTurn(opened, ref!, 'halted'))).toBe('resting');
    expect(statusOf(settleTurn(opened, ref!, 'complete'))).toBe('resting');
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
    const halted = settleTurn(appendTurnDelta(list, ref, 'Partly'), ref, 'halted');
    expect(halted.sessions[0]?.turns[0]?.answer).toBe('Partly');
    expect(halted.sessions[0]?.turns[0]?.status).toBe('halted');
  });

  it('ignores a delta and a second terminal after the turn has settled', () => {
    const { list, ref } = opened();
    const done = settleTurn(appendTurnDelta(list, ref, 'Done.'), ref, 'complete');
    expect(appendTurnDelta(done, ref, ' more').sessions[0]?.turns[0]?.answer).toBe('Done.');
    const twice = settleTurn(done, ref, 'failed', 'HTTP 502');
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
    expect(settleTurn(list, stale, 'complete')).toBe(list);
    expect(settleTurn(list, { sessionId: list.sessions[0]?.id ?? '', turnId: 'nope' }, 'complete'))
      .toBe(list);
  });

  it('shows the ACTIVE session\'s turns, and nothing at all when none is claimed', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submitInSession(startNewSession(one), 'second', T0 + MINUTE);
    expect(activeTurns(two).map((t) => t.question)).toEqual(['second']);
    expect(activeTurns(startNewSession(two))).toEqual([]);
    expect(activeTurns(SV3_SESSIONS_EMPTY)).toEqual([]);
    // Claiming the older session shows ITS transcript — the list is per-session, not per-window.
    const older = focusSession(two, two.sessions[1]?.id ?? '');
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
