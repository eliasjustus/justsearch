// SPDX-License-Identifier: Apache-2.0
/**
 * CommandPaletteProjection — Tempdoc 543 §21.C.
 *
 * Palette-side projection layer over the Action substrate. Reads Actions
 * via `listActions()` and layers fuzzy-matching, frecency, mode-prefix,
 * and category grouping ON TOP of the substrate's flat ordering.
 *
 * Per §22.3 architectural decision (validated by Raycast / Linear / VS
 * Code prior art per §22.5.2): Action interface stays minimal; palette
 * concerns (scoring, recency, mode prefixes) live here, not on Action.
 *
 * Back-compat: also includes entries from the legacy CommandRegistry
 * map for ids that haven't migrated to Action yet (TemplateCatalog
 * templates, plugin HostApi.registerCommand callsites).
 */

import { listActions, type Action } from '../substrates/actions/index.js';
import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import {
  getRecentCommandIds as docGetRecent,
} from '../state/UserStateDocument.js';
import {
  fuzzyScore,
  parsePaletteQuery,
  commandLabel,
  __getAllCommandsForPalette,
} from './CommandRegistry.js';
import type { Command } from './CommandRegistry.js';

/** Unified palette entry — projects either an Action or a legacy Command into one shape. */
export interface PaletteEntry {
  /** Original id (Action id OR legacy Command id; the legacy `shell.*` / `op.*` mapping happens via invokeCommand's resolver). */
  readonly id: string;
  /** Display label. */
  readonly label: string;
  /** Optional grouping. */
  readonly category?: string;
  /** Optional icon. */
  readonly icon?: string;
  /** Optional keyboard shortcut hint. */
  readonly shortcut?: string;
  /** Origin discriminator for debugging / future per-origin rendering. */
  readonly origin: 'action' | 'command' | 'intent';
  /**
   * Tempdoc 548 §8 (intent surface, v0): for synthetic intent entries
   * (origin === 'intent'), the free-text payload the entry acts on. The
   * palette routes this to the search surface on select. Absent for
   * command/action entries.
   */
  readonly intentText?: string;
}

/** Scored entry — added by fuzzy + recency. */
export interface ScoredPaletteEntry {
  entry: PaletteEntry;
  score: number;
  matches: number[];
}

/**
 * Project an Action into a PaletteEntry. Returns null when the Action
 * is non-global (appliesTo is non-empty) — addressable-scoped Actions
 * surface via context menus / selection menus, not the palette.
 */
function actionToPaletteEntry(a: Action): PaletteEntry | null {
  if (a.appliesTo != null && a.appliesTo.length > 0) return null;
  return {
    id: legacyCommandIdFor(a.id),
    label: a.title,
    ...(a.category !== undefined ? { category: a.category } : {}),
    ...(a.icon !== undefined ? { icon: a.icon } : {}),
    ...(a.shortcut !== undefined ? { shortcut: a.shortcut } : {}),
    origin: 'action' as const,
  };
}

/**
 * §21.B introduced 'core.action.shell.*' and 'core.action.op.*' ids.
 * Palette entries surface the LEGACY 'shell.*' / 'op.*' ids so that
 * keybinding bindings + recent-command persistence + invokeCommand
 * routing stay back-compatible. invokeCommand's resolver maps back to
 * the Action id at dispatch time.
 */
function legacyCommandIdFor(actionId: string): string {
  if (actionId.startsWith('core.action.shell.')) {
    return actionId.slice('core.action.'.length); // -> 'shell.*'
  }
  if (actionId.startsWith('core.action.op.')) {
    return actionId.slice('core.action.'.length); // -> 'op.*'
  }
  // §28.W10 — Template Actions also surface under their legacy
  // 'template.<id>' palette ids so persisted recent-command lists +
  // keybinding bindings continue to find them.
  if (actionId.startsWith('core.action.template.')) {
    return actionId.slice('core.action.'.length); // -> 'template.*'
  }
  return actionId;
}

/** Project a legacy Command into a PaletteEntry. */
function commandToPaletteEntry(c: Command): PaletteEntry {
  return {
    id: c.id,
    // 557 P2 — localize via the display projector when a labelKey is set.
    label: commandLabel(c),
    ...(c.category !== undefined ? { category: c.category } : {}),
    ...(c.icon !== undefined ? { icon: c.icon } : {}),
    ...(c.shortcut !== undefined ? { shortcut: c.shortcut } : {}),
    origin: 'command' as const,
  };
}

/**
 * §21.C — main palette-search entry point. Reads Action substrate +
 * legacy Command map, applies mode/when filters + fuzzy scoring +
 * recency bonus + category-aware sort.
 */
export function searchPaletteEntries(query: string): ScoredPaletteEntry[] {
  const parsed = parsePaletteQuery(query);
  const ctx = getShellContext();

  // 1. Pool: Action substrate (canonical) + legacy Commands (back-compat for
  //    templates + plugin HostApi commands not yet migrated).
  const pool: PaletteEntry[] = [];
  for (const a of listActions({ scope: ctx as unknown as Record<string, unknown> })) {
    const entry = actionToPaletteEntry(a);
    if (entry !== null) pool.push(entry);
  }
  for (const c of legacyCommandsForPalette(ctx as unknown as Record<string, unknown>)) {
    pool.push(commandToPaletteEntry(c));
  }

  // 2. Mode-prefix filter.
  const modeFiltered = pool.filter((e) => {
    if (parsed.mode === 'surfaces') return e.id.startsWith('shell.go-to-');
    if (parsed.mode === 'settings')
      return e.category === 'Settings' || e.id.startsWith('settings.');
    return true;
  });

  // 3. Recency-aware ranking.
  const recentIds = docGetRecent();
  const recentSet = new Set(recentIds);

  if (parsed.text.length === 0) {
    // Empty query: recent first (in recency order), then everything else.
    const recent: ScoredPaletteEntry[] = [];
    const rest: ScoredPaletteEntry[] = [];
    for (const entry of modeFiltered) {
      const scored = {
        entry,
        score: recentSet.has(entry.id) ? 100 : 0,
        matches: [],
      };
      if (recentSet.has(entry.id)) recent.push(scored);
      else rest.push(scored);
    }
    recent.sort(
      (a, b) => recentIds.indexOf(a.entry.id) - recentIds.indexOf(b.entry.id),
    );
    return [...recent, ...rest];
  }

  // 4. Fuzzy match + recency bonus.
  const results: ScoredPaletteEntry[] = [];
  for (const entry of modeFiltered) {
    const result = fuzzyScore(parsed.text, entry.label);
    if (result) {
      const recencyBonus = recentSet.has(entry.id) ? 20 : 0;
      results.push({
        entry,
        score: result.score + recencyBonus,
        matches: result.matches,
      });
    }
  }
  results.sort((a, b) => b.score - a.score);

  // Tempdoc 548 §8 (intent surface, v0): always offer a "search for this"
  // fallback so the palette doubles as search-as-you-type. Appended last
  // (score 0) so exact command/action matches keep their ranking; the
  // entry's behavior (route free-text → search surface) is wired in the
  // palette component. Broader fusion (a `query` intent kind + the
  // one-turn cited-answer engine) is the flagged §8 follow-up.
  results.push({
    entry: {
      id: 'intent.search',
      label: `Search for "${parsed.text}"`,
      category: 'Search',
      origin: 'intent',
      intentText: parsed.text,
    },
    score: 0,
    matches: [],
  });
  return results;
}

/** Legacy Command pool filtered by when-clause. */
function legacyCommandsForPalette(
  ctx: Record<string, unknown>,
): readonly Command[] {
  return __getAllCommandsForPalette().filter((c) =>
    evaluateWhen(c.when, ctx),
  );
}
