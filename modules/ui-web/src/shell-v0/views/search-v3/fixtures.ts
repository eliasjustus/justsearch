// SPDX-License-Identifier: Apache-2.0
/**
 * Fixture data and fixed copy for the Search v3 shell (tempdoc 822 slice 1; narrowed in Phase A1).
 *
 * The window was fixture-first on purpose: the shell's geometry had to clear the bar before any
 * backend was wired. Phase A1 moved the CONTENT SURFACE onto the shared search store, so the rows
 * here are the SIDEBAR's alone (sessions become real in A2); what remains besides them is fixed copy.
 */
import type { IconName } from '../../components/Icon.js';
import { reasonFor } from '../../state/readinessNotice.js';

/**
 * The row's status, as the donor's 3-colour budget spends it: colour is reserved for act-now,
 * in-motion and broken. Every other row is `resting` and spends none of it.
 */
export type Sv3RowStatus = 'resting' | 'in-motion' | 'act-now' | 'broken';

export interface Sv3SidebarRow {
  readonly id: string;
  readonly label: string;
  readonly status: Sv3RowStatus;
  /** Shown in the status slot when the row spends no colour — a timestamp, tabular. */
  readonly meta: string;
  readonly active?: boolean;
  readonly selected?: boolean;
  readonly receded?: boolean;
  readonly unread?: boolean;
  /** Orthogonal to the fill ladder: the row's work is running, so the row dims until hovered. */
  readonly inFlight?: boolean;
}

export interface Sv3SidebarGroup {
  readonly id: string;
  readonly label: string;
  readonly rows: readonly Sv3SidebarRow[];
}

/**
 * Ten rows over three groups, covering each visual state once: active, selected, in-motion (with
 * in-flight dim), act-now, broken, unread, plain rest, and a receded tail with one title long
 * enough to prove the single-line ellipsis. The order is FIXED — activity never reorders the list.
 */
export const SIDEBAR_GROUPS: readonly Sv3SidebarGroup[] = [
  {
    id: 'g-pinned',
    label: 'Pinned',
    rows: [
      {
        id: 'r1',
        label: 'Northfield supplier agreement',
        status: 'resting',
        meta: '2m',
        active: true,
      },
      { id: 'r2', label: 'Vendor risk register', status: 'resting', meta: '18m', selected: true },
    ],
  },
  {
    id: 'g-today',
    label: 'Today',
    rows: [
      { id: 'r3', label: 'Q2 vendor review notes', status: 'in-motion', meta: '', inFlight: true },
      { id: 'r4', label: 'Revised payment terms', status: 'act-now', meta: '', unread: true },
      { id: 'r5', label: 'Warehouse lease — appendix C', status: 'broken', meta: '' },
      { id: 'r6', label: 'Freight cost reconciliation', status: 'resting', meta: '1h', unread: true },
      { id: 'r7', label: 'Insurance renewal correspondence', status: 'resting', meta: '4h' },
    ],
  },
  {
    id: 'g-earlier',
    label: 'Earlier',
    rows: [
      {
        id: 'r8',
        label: 'Supplier onboarding checklist',
        status: 'resting',
        meta: 'Mar 4',
        receded: true,
      },
      {
        id: 'r9',
        label: 'Site inspection photo index',
        status: 'resting',
        meta: 'Feb 27',
        receded: true,
      },
      {
        id: 'r10',
        label: 'Annual supplier scorecard and remediation follow-up notes',
        status: 'resting',
        meta: 'Feb 12',
        receded: true,
      },
    ],
  },
];

/** Every row in render order — the flat projection the sidebar's tests count against. */
export const SIDEBAR_ROWS: readonly Sv3SidebarRow[] = SIDEBAR_GROUPS.flatMap((g) => g.rows);

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

/* ── Slice 4: the fixture SET, the palette, and the empty states ───────────────────────────── */

/**
 * Which fixture set the SIDEBAR renders. `empty` is the dev handle for its zero-state pass. The
 * content surface left this handle behind in Phase A1: its rows are the search response's, so its
 * zero state is a real empty result set rather than an emptied fixture list.
 */
export type Sv3FixtureSet = 'default' | 'empty';

export const FIXTURE_SET_DEFAULT: Sv3FixtureSet = 'default';

export const sidebarGroupsFor = (set: Sv3FixtureSet): readonly Sv3SidebarGroup[] =>
  set === 'empty' ? [] : SIDEBAR_GROUPS;

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
