// SPDX-License-Identifier: Apache-2.0
/**
 * Fixture data and fixed copy for the Search v3 shell (tempdoc 822 slice 1; narrowed in A1 and A2).
 *
 * The window was fixture-first on purpose: the shell's geometry had to clear the bar before any
 * backend was wired. Phase A1 moved the CONTENT SURFACE onto the shared search store and Phase A2
 * moved the SIDEBAR onto real window-local sessions (`sv3-sessions.ts`), so the fixture sessions are
 * gone. What remains is fixed copy, the row's status vocabulary, and the composer's placeholders.
 */
import type { IconName } from '../../components/Icon.js';
import { reasonFor } from '../../state/readinessNotice.js';

/**
 * The row's status, as the donor's 3-colour budget spends it: colour is reserved for act-now,
 * in-motion and broken. Every other row is `resting` and spends none of it.
 */
export type Sv3RowStatus = 'resting' | 'in-motion' | 'act-now' | 'broken';

export const WINDOW_TITLE = 'Search v3';

export const COMPOSER_PLACEHOLDER = 'Ask or search…';

/**
 * The composer's two forms. HERO is the empty window — nothing has been asked yet, so the composer
 * is the centred subject of the main region and carries its headline. DOCKED is the working window —
 * results own the region and the composer recedes into the bottom band. They are one component in
 * two states, not two components, because the morph between them animates a single moving box.
 */
export type Sv3ComposerState = 'hero' | 'docked';

/** The window opens empty, so the morph is reachable from the default fixture. */
export const COMPOSER_STATE_DEFAULT: Sv3ComposerState = 'hero';

export const HERO_HEADLINE = 'What are you looking for?';

export interface Sv3ComposerScope {
  readonly id: string;
  readonly label: string;
  /**
   * The glyph the label compacts INTO when the composer docks — so a control that has lost its text
   * still says what it is. Named out of the shared `icon()` registry, which is the same Lucide set
   * the donor imports (`lucide-react`), so no icon dependency and no hand-drawn path.
   */
  readonly glyph: IconName;
}

/**
 * Placeholders for the scope controls a wired composer will carry (source set, recency). They exist in
 * this slice for one reason: their labels are what the compaction morph evaporates when the composer
 * docks, leaving the glyphs behind.
 */
export const COMPOSER_SCOPES: readonly Sv3ComposerScope[] = [
  { id: 'scope-sources', label: 'All sources', glyph: 'database' },
  { id: 'scope-recency', label: 'Any time', glyph: 'clock' },
];

/* ── Slice 4: the palette and the empty states ─────────────────────────────────────────────── */

/**
 * The `fixtures` dev handle that used to switch the sidebar between a fixture list and its zero state
 * is gone with the fixtures (Phase A2): both regions now reach their empty state the real way — the
 * sidebar before the window's first search, the content surface on an empty result set.
 */
export interface Sv3EmptyCopy {
  readonly title: string;
  readonly description: string;
}

export const SIDEBAR_EMPTY: Sv3EmptyCopy = {
  title: 'No searches yet',
  description: 'Ask something and the thread will be kept here.',
};

export const MAIN_EMPTY: Sv3EmptyCopy = {
  title: 'Nothing matched',
  description: 'Try fewer words, or widen the scope to all sources.',
};

/**
 * The state {@link MAIN_EMPTY} must never be mistaken for: the search never reached the backend, so
 * nothing is known about the corpus. Its description is the shipped wording for exactly this
 * condition (`state/readinessNotice.ts` `binding.unreachable`), read through the one authority rather
 * than re-phrased here — this window is a second consumer of that vocabulary, not a second author.
 */
export const MAIN_UNREACHABLE: Sv3EmptyCopy = {
  title: 'Search backend unreachable',
  description: reasonFor('binding.unreachable').wording,
};

export interface Sv3Command {
  readonly id: string;
  readonly label: string;
  /** The keyboard hint rendered at the row's trailing edge; absent means the command has none. */
  readonly shortcut?: string;
  /** The CURRENT choice — distinct from the keyboard position, which the palette owns at runtime. */
  readonly selected?: boolean;
}

export interface Sv3CommandGroup {
  readonly id: string;
  readonly label: string;
  readonly commands: readonly Sv3Command[];
}

/**
 * Eight commands over two groups, so the ONE separator between them is exercised. Exactly one command
 * is `selected` — the palette's current choice — which is what makes the donor's two-state distinction
 * visible: the highlight starts on the first row while the selection sits elsewhere.
 */
export const COMMAND_GROUPS: readonly Sv3CommandGroup[] = [
  {
    id: 'cg-search',
    label: 'Search',
    commands: [
      { id: 'cmd-everything', label: 'Search everything', shortcut: 'Ctrl L' },
      { id: 'cmd-scope-sources', label: 'Scope to all sources', selected: true },
      { id: 'cmd-scope-recent', label: 'Scope to the last 7 days' },
      { id: 'cmd-similar', label: 'Find documents like this one' },
      { id: 'cmd-explain', label: 'Explain why this result ranked' },
    ],
  },
  {
    id: 'cg-workspace',
    label: 'Workspace',
    commands: [
      { id: 'cmd-folders', label: 'Manage indexed folders' },
      { id: 'cmd-reindex', label: 'Reindex this folder' },
      { id: 'cmd-diagnostics', label: 'Open indexing diagnostics', shortcut: 'Ctrl D' },
    ],
  },
];

export const COMMANDS: readonly Sv3Command[] = COMMAND_GROUPS.flatMap((g) => g.commands);

export const PALETTE_PLACEHOLDER = 'Type a command or search…';

export const PALETTE_EMPTY = 'No matching commands';

/** The donor's footer gutter: one hint per key group, navigate / act / dismiss. */
export interface Sv3PaletteHint {
  readonly keys: readonly string[];
  readonly label: string;
}

export const PALETTE_HINTS: readonly Sv3PaletteHint[] = [
  { keys: ['↑', '↓'], label: 'Navigate' },
  { keys: ['Enter'], label: 'Run' },
  { keys: ['Esc'], label: 'Close' },
];
