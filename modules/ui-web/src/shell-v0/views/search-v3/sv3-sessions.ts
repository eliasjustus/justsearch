// SPDX-License-Identifier: Apache-2.0
/**
 * The Search v3 window's session list (tempdoc 822 Phase A2).
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

/** One search thread in this window: the latest query it ran, and how many times it has run. */
export interface Sv3Session {
  readonly id: string;
  /**
   * The LATEST submitted text. Re-querying inside a session replaces it rather than opening a new
   * session, so the row's title is what the session is currently about. No auto-titling: the query
   * IS the title (the row's single-line ellipsis handles length).
   */
  readonly query: string;
  /** How many searches this session has issued — 1 at creation, +1 per re-query or re-run. */
  readonly submits: number;
  readonly createdAt: number;
  /** When the session last issued a search; the resting row's timestamp. */
  readonly updatedAt: number;
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

/**
 * A submitted search: it CREATES a session when none is active, and UPDATES the active one otherwise.
 * The updated session keeps its position — re-querying is not a reason to move a row.
 */
export function submitInSession(
  list: Sv3SessionList,
  query: string,
  now: number,
): Sv3SessionList {
  const text = query.trim();
  if (text === '') return list;
  const active = list.activeId === null ? null : sessionById(list, list.activeId);
  if (active === null) {
    const id = `sv3-session-${list.minted + 1}`;
    const created: Sv3Session = {
      id,
      query: text,
      submits: 1,
      createdAt: now,
      updatedAt: now,
    };
    return { sessions: [created, ...list.sessions], activeId: id, minted: list.minted + 1 };
  }
  return {
    ...list,
    sessions: list.sessions.map((session) =>
      session.id === active.id
        ? { ...session, query: text, submits: session.submits + 1, updatedAt: now }
        : session,
    ),
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
  { searching, now }: Sv3SessionProjection,
): readonly Sv3SessionGroup[] {
  const toRow = (session: Sv3Session): Sv3SessionRowView => {
    const active = session.id === list.activeId;
    // In-motion is the ACTIVE session's alone: the store is process-wide, and only the session that
    // issued the search is the one running. A resting row spends no colour and shows its age.
    const running = active && searching;
    return {
      id: session.id,
      label: session.query,
      status: running ? 'in-motion' : 'resting',
      meta: running ? '' : sv3RelativeTime(session.updatedAt, now),
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
