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
  focusSession,
  projectSv3Sessions,
  sessionById,
  startNewSession,
  submitInSession,
  sv3RelativeTime,
  SV3_SESSIONS_EMPTY,
  type Sv3SessionList,
} from './sv3-sessions.js';

const T0 = Date.parse('2026-08-13T10:00:00Z');
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const rest = { searching: false, now: T0 };

describe('a submit creates a session, or updates the active one', () => {
  it('creates the first session from the query, with one submit against it', () => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, 'northfield lease', T0);
    expect(list.sessions).toHaveLength(1);
    const [session] = list.sessions;
    // The query IS the title — nothing generates a name for it.
    expect(session?.query).toBe('northfield lease');
    expect(session?.submits).toBe(1);
    expect(session?.createdAt).toBe(T0);
    expect(session?.updatedAt).toBe(T0);
    expect(list.activeId).toBe(session?.id);
  });

  it('UPDATES the active session on a second submit rather than opening a second one', () => {
    const first = submitInSession(SV3_SESSIONS_EMPTY, 'vendor risk', T0);
    const second = submitInSession(first, 'vendor risk register', T0 + 2 * MINUTE);
    expect(second.sessions).toHaveLength(1);
    // Re-querying inside a session is the session changing its mind, not a new thread.
    expect(second.sessions[0]?.query).toBe('vendor risk register');
    expect(second.sessions[0]?.submits).toBe(2);
    expect(second.sessions[0]?.createdAt).toBe(T0);
    expect(second.sessions[0]?.updatedAt).toBe(T0 + 2 * MINUTE);
    expect(second.sessions[0]?.id).toBe(first.sessions[0]?.id);
    expect(second.activeId).toBe(first.activeId);
  });

  it('opens the NEXT session at the top and never moves it again', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const two = submitInSession(startNewSession(one), 'second', T0 + MINUTE);
    expect(two.sessions.map((s) => s.query)).toEqual(['second', 'first']);
    // Activity on the OLDER session must not float it back up (charter law: order is fixed).
    const rerun = submitInSession(focusSession(two, two.sessions[1]?.id ?? ''), 'first', T0 + HOUR);
    expect(rerun.sessions.map((s) => s.query)).toEqual(['second', 'first']);
    expect(rerun.sessions[1]?.submits).toBe(2);
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

  it('trims the query it stores, so the title is not padded by the field', () => {
    const list = submitInSession(SV3_SESSIONS_EMPTY, '  spaced  ', T0);
    expect(list.sessions[0]?.query).toBe('spaced');
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
    const rerun = submitInSession(focused, 'first', T0 + 2 * MINUTE);
    expect(rerun.sessions).toHaveLength(2);
    expect(rerun.sessions[1]?.submits).toBe(2);
    expect(rerun.sessions[0]?.submits).toBe(1);
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
  const twoSessions = (): Sv3SessionList => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    return submitInSession(startNewSession(one), 'second', T0 + MINUTE);
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
    const yesterday = submitInSession(SV3_SESSIONS_EMPTY, 'old', T0 - DAY);
    const today = submitInSession(startNewSession(yesterday), 'new', T0);
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

  it('shows the LAST activity, not the creation time, on a re-run session', () => {
    const one = submitInSession(SV3_SESSIONS_EMPTY, 'first', T0);
    const rerun = submitInSession(one, 'first again', T0 + 30 * MINUTE);
    const rows = projectSv3Sessions(rerun, { searching: false, now: T0 + 32 * MINUTE })[0]?.rows;
    expect(rows?.[0]?.meta).toBe('2m');
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
