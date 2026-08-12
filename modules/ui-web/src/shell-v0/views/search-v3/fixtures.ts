// SPDX-License-Identifier: Apache-2.0
/**
 * Fixture data for the Search v3 shell (tempdoc 822 slice 1).
 *
 * The window is fixture-first on purpose: the shell's geometry has to clear the bar before any
 * backend is wired, and fixtures keep the slice-1 components free of stores, so what the tests
 * measure is the geometry and nothing else. Slice 2+ replaces these with the shared authorities.
 */

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

export const MAIN_HEADING = 'Results';

export interface Sv3MainRow {
  readonly id: string;
  readonly title: string;
  readonly path: string;
}

export const MAIN_ROWS: readonly Sv3MainRow[] = [
  { id: 'm1', title: 'Northfield supplier agreement.pdf', path: 'Contracts/Northfield.pdf' },
  { id: 'm2', title: 'Q2 vendor review notes.md', path: 'Ops/Reviews/Q2.md' },
  { id: 'm3', title: 'RE: revised payment terms.eml', path: 'Archive/Mail/2025-03.eml' },
  { id: 'm4', title: 'Warehouse lease appendix C.docx', path: 'Legal/Leases/AppendixC.docx' },
  { id: 'm5', title: 'Insurance renewal 2026.pdf', path: 'Admin/Insurance/2026.pdf' },
  { id: 'm6', title: 'Freight reconciliation Q1.xlsx', path: 'Finance/Freight/Q1.xlsx' },
  { id: 'm7', title: 'Supplier onboarding checklist.md', path: 'Ops/Onboarding/Checklist.md' },
  { id: 'm8', title: 'Northfield amendment 2.pdf', path: 'Contracts/Northfield-A2.pdf' },
  { id: 'm9', title: 'Vendor risk register.xlsx', path: 'Risk/Vendors/Register.xlsx' },
  { id: 'm10', title: 'Payment terms policy.md', path: 'Finance/Policy/Terms.md' },
  { id: 'm11', title: 'Site inspection photos index.txt', path: 'Ops/Sites/Index.txt' },
  { id: 'm12', title: 'Annual supplier scorecard.pdf', path: 'Ops/Reviews/Scorecard.pdf' },
];

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
}

/**
 * Placeholders for the scope controls a wired composer will carry (source set, recency). They exist in
 * this slice for one reason: their labels are what the compaction morph evaporates when the composer
 * docks, leaving the glyphs behind.
 */
export const COMPOSER_SCOPES: readonly Sv3ComposerScope[] = [
  { id: 'scope-sources', label: 'All sources' },
  { id: 'scope-recency', label: 'Any time' },
];
