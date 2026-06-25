// SPDX-License-Identifier: Apache-2.0
/**
 * journalView — Tempdoc 543-fwd UPDATE 10, #6 (turn grouping).
 *
 * A PURE view-model that folds the flat append-only journal into causation-rooted
 * "turns" for display. The journal itself stays the flat source of truth; this is
 * presentation only, derived from the entries handed in (no global state — the causation
 * root is resolved within the passed set, so it's deterministic + testable).
 *
 * A *turn* is a maximal run of consecutive entries sharing the same causation root (an
 * agent tool-call sequence chains via `causation`; a standalone user/system action is its
 * own root). `grouped` is true when the turn has >1 member — those render under a
 * collapsible header; singletons render as a plain row.
 *
 * NOTE (Fix C): an earlier version collapsed consecutive identical effects into a single
 * row with a repeat count. That silently dropped the per-entry rows for the collapsed-away
 * entries, losing their selection / restore / undo-turn affordances while the header still
 * claimed "N steps". Correctness wins over density: every member entry is preserved as its
 * own row. Scale is handled by render performance (measured fine), not by hiding entries.
 */

import type { JournalEntry, EffectOriginator } from './index.js';

export interface DisplayTurn {
  /** The causation root id shared by every member (resolved within the passed set). */
  readonly rootId: number;
  /** Originator of the turn's first member (turns are single-originator runs). */
  readonly originator: EffectOriginator;
  /** True when the turn has >1 member — render under a collapsible header. */
  readonly grouped: boolean;
  /** Every member entry, in display order — none dropped. */
  readonly members: readonly JournalEntry[];
}

/**
 * Group `entries` (already filtered + in display order) into turns. Pure: the causation
 * root is resolved only via `causation` pointers present in `entries` (a filtered-out
 * ancestor stops the walk at the highest present ancestor).
 */
export function groupJournalForDisplay(
  entries: readonly JournalEntry[],
): DisplayTurn[] {
  const byId = new Map<number, JournalEntry>();
  for (const e of entries) byId.set(e.id, e);

  const rootOf = (start: JournalEntry): number => {
    let cur = start;
    const seen = new Set<number>();
    while (
      cur.causation !== undefined &&
      byId.has(cur.causation) &&
      !seen.has(cur.id)
    ) {
      seen.add(cur.id);
      cur = byId.get(cur.causation) as JournalEntry;
    }
    return cur.id;
  };

  const turns: DisplayTurn[] = [];
  let members: JournalEntry[] = [];
  let rootId = -1;
  let originator: EffectOriginator = 'system';

  const flush = (): void => {
    if (members.length === 0) return;
    turns.push({ rootId, originator, grouped: members.length > 1, members });
    members = [];
  };

  for (const e of entries) {
    const root = rootOf(e);
    if (members.length > 0 && root === rootId) {
      members.push(e);
    } else {
      flush();
      rootId = root;
      originator = e.originator;
      members = [e];
    }
  }
  flush();
  return turns;
}
