// SPDX-License-Identifier: Apache-2.0
/**
 * queryTrail — the omnibox history's RECENT half (tempdoc 818 slice 5, L12).
 *
 * L12 says the rail never yields item-by-item, so the query trail is not a rail occupant: it lives
 * in the input band, the omnibox convention's own home for history. Its PINNED half needs no module
 * — it is the shared `pinnedSearchState` projection, read as-is.
 *
 * The RECENT half has no shared authority, so this module states honestly where it comes from and in
 * what order:
 *  1. **this session's committed searches first** — the frozen records' own queries, newest first.
 *     A committed search is a fact the window already holds; sourcing the trail from anywhere else
 *     while those exist would put a second, weaker copy of the session's own history on screen.
 *  2. **then a small localStorage trail of SUBMITTED queries** — the ones that ran but were never
 *     committed, which no record captures and which are exactly what a user reaches back for.
 * De-duplicated by trimmed text (the committed one wins, being the stronger fact) and capped at
 * {@link RECENT_TRAIL_CAP}: a history longer than a glance stops being a shortcut.
 *
 * Same promotion path as `railSizing.ts`: the durable home for a per-user trail is
 * `UserStateDocument`; `localStorage` is the two-window phase's cheap edge (§5).
 */

/** How many recents the dropdown offers. A glance, not an archive. */
export const RECENT_TRAIL_CAP = 8;

const TRAIL_KEY = 'justsearch.searchV2.queryTrail.v1';

/**
 * The Recent section's rows: this session's committed queries first, then the stored trail, deduped
 * by trimmed text and capped. Pure — the caller supplies both sources.
 */
export function mergeRecents(
  committed: readonly string[],
  stored: readonly string[],
  cap: number = RECENT_TRAIL_CAP,
): readonly string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const q of [...committed, ...stored]) {
    const text = q.trim();
    if (!text || seen.has(text)) continue;
    seen.add(text);
    out.push(text);
    if (out.length >= cap) break;
  }
  return out;
}

/** The dropdown's filter: substring, case-insensitive. An empty needle matches everything. */
export function filterTrail(rows: readonly string[], needle: string): readonly string[] {
  const text = needle.trim().toLowerCase();
  if (!text) return rows;
  return rows.filter((r) => r.toLowerCase().includes(text));
}

/** The stored trail, newest first. Unreadable storage yields an empty trail, never an exception. */
export function readTrail(): readonly string[] {
  try {
    const raw = localStorage.getItem(TRAIL_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((q): q is string => typeof q === 'string' && q.trim().length > 0);
  } catch {
    return [];
  }
}

/** Record a query the user actually SUBMITTED. Newest first, deduped, capped. */
export function recordSubmittedQuery(query: string): readonly string[] {
  const text = query.trim();
  if (!text) return readTrail();
  const next = mergeRecents([text], readTrail(), RECENT_TRAIL_CAP);
  try {
    localStorage.setItem(TRAIL_KEY, JSON.stringify(next));
  } catch {
    // Unavailable storage costs the memory, never the search.
  }
  return next;
}
