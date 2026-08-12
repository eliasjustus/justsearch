// SPDX-License-Identifier: Apache-2.0
/**
 * Fixture data for the Search v3 shell (tempdoc 822 slice 1).
 *
 * The window is fixture-first on purpose: the shell's geometry has to clear the bar before any
 * backend is wired, and fixtures keep the slice-1 components free of stores, so what the tests
 * measure is the geometry and nothing else. Slice 2+ replaces these with the shared authorities.
 */

/** Sidebar group label + rows — six, enough to see the two-level inset and the hover pill. */
export const SIDEBAR_GROUP_LABEL = 'Recent';

export interface Sv3SidebarRow {
  readonly id: string;
  readonly label: string;
}

export const SIDEBAR_ROWS: readonly Sv3SidebarRow[] = [
  { id: 'r1', label: 'Northfield supplier agreement' },
  { id: 'r2', label: 'Q2 vendor review notes' },
  { id: 'r3', label: 'Revised payment terms' },
  { id: 'r4', label: 'Warehouse lease — appendix C' },
  { id: 'r5', label: 'Insurance renewal correspondence' },
  { id: 'r6', label: 'Freight cost reconciliation' },
];

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
