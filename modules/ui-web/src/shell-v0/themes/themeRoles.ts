// SPDX-License-Identifier: Apache-2.0
/**
 * themeRoles — Tempdoc 567 §8 / A2 — the semantic colour ROLE catalog.
 *
 * A role pairs an accent BACKGROUND token with its FOREGROUND token; the foreground is auto-derived to
 * meet a WCAG contrast floor over the (hue-authored) background — see {@link ../themes/contrast} and the
 * Theme Editor's Roles section. Role foregrounds are the second half of the authorable surface ("seeds +
 * roles"): even though they are derived rather than hand-edited, `saveTheme` admits them alongside the
 * seeds (a theme legitimately carries its baked role foregrounds).
 */
import { WCAG_AA } from './contrast.js';

export interface ThemeRole {
  /** Stable id (also the suffix of the fg token: `accent-on-<id>`). */
  readonly id: string;
  /** Human label for the editor. */
  readonly label: string;
  /** The accent background token (without the `--` prefix). */
  readonly bgToken: string;
  /** The foreground token whose value is auto-derived (without the `--` prefix). */
  readonly fgToken: string;
  /**
   * The text-grade token (without the `--` prefix): the role colour legible as TEXT on the app
   * SURFACE — distinct from `bgToken` (a fill, not legible as text). The #3 amber bug was a fill used
   * as text; the text grade makes the legible-text choice explicit (tempdoc 576 §6 rung-1).
   */
  readonly textToken: string;
  /** The WCAG contrast floor the derived foreground must clear over the background. */
  readonly floor: number;
}

export const ROLE_CATALOG: readonly ThemeRole[] = [
  { id: 'command', label: 'Command', bgToken: 'accent-command', fgToken: 'accent-on-command', textToken: 'text-command', floor: WCAG_AA },
  { id: 'chat', label: 'Chat', bgToken: 'accent-chat', fgToken: 'accent-on-chat', textToken: 'text-chat', floor: WCAG_AA },
  { id: 'success', label: 'Success', bgToken: 'accent-success', fgToken: 'accent-on-success', textToken: 'text-success', floor: WCAG_AA },
  { id: 'warning', label: 'Warning', bgToken: 'accent-warning', fgToken: 'accent-on-warning', textToken: 'text-warning', floor: WCAG_AA },
  { id: 'danger', label: 'Danger', bgToken: 'accent-danger', fgToken: 'accent-on-danger', textToken: 'text-danger', floor: WCAG_AA },
  { id: 'tint', label: 'Tint', bgToken: 'accent-tint', fgToken: 'accent-on-tint', textToken: 'text-tint', floor: WCAG_AA },
  // Tempdoc 577 Phase 7 (570 §18.1 G) — the search window's missing roles: the query-term
  // match fill (was a hardcoded --accent-tint-30) and this-is-clickable link text.
  { id: 'highlight', label: 'Highlight', bgToken: 'accent-highlight', fgToken: 'accent-on-highlight', textToken: 'text-highlight', floor: WCAG_AA },
  { id: 'link', label: 'Link', bgToken: 'accent-link', fgToken: 'accent-on-link', textToken: 'text-link', floor: WCAG_AA },
];

/** The set of role foreground token names — admitted by `saveTheme` alongside the seeds. */
export const ROLE_FG_TOKEN_NAMES: ReadonlySet<string> = new Set(ROLE_CATALOG.map((r) => r.fgToken));
