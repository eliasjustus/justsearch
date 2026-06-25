// SPDX-License-Identifier: Apache-2.0
// Tempdoc 561 #6 — the ONE search-evidence projection, shared by the live tool card
// (ToolCallCard) and the record render (UnifiedChatView.renderToolActivity) so the agent's search
// output renders as the SAME structured evidence cards live AND from the record (no revert to the
// raw monospace dump after reconciliation). Deliberately carries NO relevance score — the ranking
// score is uncalibrated (559 §5 / §18 C-6), so surfacing it as a "% relevance" would fabricate
// calibration. The excerpt is the producer-owned word-boundary region (no FE re-windowing).
import { html, nothing, type TemplateResult } from 'lit';
import { filenameOf } from './evidenceProjection.js';

/** True when the structured data carries a non-empty search-evidence list. */
export function hasSearchEvidence(structuredData: unknown): boolean {
  const sd = structuredData as Record<string, unknown> | undefined;
  const r = sd?.['searchResults'];
  return Array.isArray(r) && r.length > 0;
}

/** Render the search results as structured evidence cards (filename · location · excerpt). */
export function renderSearchEvidence(structuredData: unknown): TemplateResult | typeof nothing {
  const sd = structuredData as Record<string, unknown> | undefined;
  const results = sd?.['searchResults'];
  if (!Array.isArray(results) || results.length === 0) return nothing;
  return html`<div class="tool-evidence" data-testid="tool-search-evidence">
    ${(results as Array<Record<string, unknown>>).map((r) => {
      const path = typeof r['path'] === 'string' ? (r['path'] as string) : '';
      const rawTitle = typeof r['title'] === 'string' ? (r['title'] as string) : '';
      const title = rawTitle || filenameOf(path) || '(untitled)';
      const excerpt = typeof r['excerpt'] === 'string' ? (r['excerpt'] as string) : '';
      const line = typeof r['line'] === 'number' ? (r['line'] as number) : 0;
      return html`<div class="evidence-card">
        <div class="evidence-head">
          <span class="evidence-file" title=${path || title}>${title}</span>
          ${line > 0 ? html`<span class="evidence-loc">line ${line}</span>` : nothing}
        </div>
        ${excerpt ? html`<div class="evidence-excerpt">${excerpt}</div>` : nothing}
      </div>`;
    })}
  </div>`;
}

/** The shared evidence-card CSS — each host (shadow DOM) includes it in its own styles. */
export const SEARCH_EVIDENCE_CSS = `
  .tool-evidence {
    margin-top: 0.5rem;
    display: flex;
    flex-direction: column;
    gap: 0.4rem;
  }
  .evidence-card {
    padding: 0.5rem 0.75rem;
    background: var(--surface-secondary);
    border: 1px solid var(--border-subtle);
    border-radius: 0.375rem;
    font-size: var(--font-size-sm);
    color: var(--text-primary);
  }
  .evidence-head {
    display: flex;
    align-items: baseline;
    gap: 0.5rem;
    justify-content: space-between;
  }
  .evidence-file {
    font-weight: 500;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .evidence-loc {
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
    flex-shrink: 0;
  }
  .evidence-excerpt {
    margin-top: 0.3rem;
    font-style: italic;
    color: var(--text-secondary);
    font-size: var(--font-size-sm);
    line-height: 1.4;
  }
`;
