// SPDX-License-Identifier: Apache-2.0
/**
 * token() — the typed design-token accessor (tempdoc 557 §2.C / C2).
 *
 * Returns a `var(--name)` reference for use in `css\`\`` / inline styles. The
 * argument is a {@link TokenName} (the closed, codegenned vocabulary), so a
 * misspelled token is a COMPILE error — never a silent ghost that resolves to
 * a hardcoded fallback (the §2.C defect class that broke every non-Dark theme).
 *
 * Migration intent (C3): used for JS-context token references (dynamic style
 * strings). Inside `css\`\`` templates, design tokens stay LITERAL CSS — Lit's
 * css tag rejects string interpolation, and the theme-token-closure gate already
 * guarantees the name is defined. The C3 codemod instead strips dead hardcoded
 * fallbacks so the token's value lives only in tokens.css / the active palette.
 */
import type { TokenName } from './token-names.generated.js';

export type { TokenName };

/**
 * A `var(--name)` reference for a known design token. Pass a `fallback` only at
 * a genuine system boundary (e.g. a value a theme is not guaranteed to set);
 * within the app the closure gate guarantees the token is defined, so the
 * fallback is normally omitted.
 */
export function token(name: TokenName, fallback?: string): string {
  return fallback === undefined ? `var(${name})` : `var(${name}, ${fallback})`;
}
