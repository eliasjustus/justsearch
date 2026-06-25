// SPDX-License-Identifier: Apache-2.0
/**
 * resultRowPresentation — Tempdoc 602 R3 (render single-authority for the result row).
 *
 * The ONE presentation layer shared by the dedicated Search surface
 * (`SearchSurface.renderRow`) and the unified window's retrieve tier
 * (`UnifiedChatView.renderRetrieveRow`). Before R3 each surface formatted the
 * path and highlighted the snippet independently — the Search surface used
 * middle-ellipsis + `<mark>` query highlighting, the retrieve tier showed the
 * raw path with no highlighting — so the SAME result rendered two ways. Folding
 * both into this one module (the sibling of `matchCountLabel.ts`, which 597 R-1
 * extracted for the count, citing this same 602 R3 goal) makes the divergence
 * unrepresentable: both surfaces project the same path text and the same
 * highlighted snippet from the same code.
 *
 * `highlightTerms` is registered in the run-renderers SRR authority
 * (`governance/run-renderers.v1.json` "searchResultRendering"); a third file
 * importing it must register as a consumer (anti-fork teeth).
 */

import { html, css, type TemplateResult } from 'lit';

/**
 * F-21 display helper: truncate long paths with middle-ellipsis so the row
 * doesn't overflow. Preserves the filename + first ~25 chars of the parent;
 * full path remains in the title attribute for hover. Does NOT case-correct
 * (the path comes from the backend already-canonicalized; case-restoration
 * would require a filesystem stat per row).
 */
export const PATH_DISPLAY_MAX = 72;
export function formatDisplayPath(p: string): string {
  if (!p || p.length <= PATH_DISPLAY_MAX) return p;
  const sep = p.lastIndexOf('\\') >= 0 ? '\\' : '/';
  const lastSep = p.lastIndexOf(sep);
  if (lastSep < 0) return p.slice(0, PATH_DISPLAY_MAX - 1) + '…';
  const filename = p.slice(lastSep);
  const head = p.slice(0, Math.max(25, PATH_DISPLAY_MAX - filename.length - 3));
  return `${head}…${filename}`;
}

/**
 * Q11: wrap occurrences of the query's terms in `<mark>` so the snippet shows
 * WHY a result matched. Lit auto-escapes both the plain segments and the marked
 * text, so this is XSS-safe (no unsafeHTML). An empty / sub-2-char query yields
 * no marks (returns the text verbatim).
 */
export function highlightTerms(text: string, query: string): TemplateResult | string {
  const terms = [
    ...new Set(
      query
        .toLowerCase()
        .split(/\s+/)
        .map((t) => t.replace(/[^\p{L}\p{N}]/gu, ''))
        .filter((t) => t.length >= 2),
    ),
  ];
  if (terms.length === 0) return text;
  const escaped = terms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  const re = new RegExp(`(${escaped.join('|')})`, 'gi');
  const termSet = new Set(terms);
  const parts = text.split(re).filter((p) => p !== '');
  return html`${parts.map((p) =>
    termSet.has(p.toLowerCase()) ? html`<mark class="hl">${p}</mark>` : p,
  )}`;
}

/**
 * The query-term highlight mark style. 577 Phase 7: consumes the highlight ROLE
 * (the contrast-floored `--accent-highlight` / `--accent-on-highlight` pair, AA
 * in every theme) — not a hardcoded tint. Unscoped `mark.hl` so it applies in
 * either surface's snippet container (`.snippet` / `.retrieve-row-snippet`).
 */
export const highlightStyles = css`
  mark.hl {
    background: var(--accent-highlight);
    color: var(--accent-on-highlight);
    border-radius: 2px;
    padding: 0 1px;
  }
`;
