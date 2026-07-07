// SPDX-License-Identifier: Apache-2.0
/**
 * scopeChipRow — Search-Thread S3 (the shared-row-renderer house pattern, mirroring
 * `searchResults/facetChips.ts`'s `renderFacetChips`).
 *
 * The ONE scope-chip row render, shared by every host that mounts the search-thread scope
 * constraint (SearchSurface and the unified window's retrieve tier). A chip's remove affordance is
 * delegated to the consumer's `onRemove(index)`; the shared concern is the RENDER + the
 * `jf-scope-chip` → `scope-remove` wiring, not what happens after (the consumer's own re-issue
 * seam — see `removeScopeChip` in searchState.ts).
 */
import { html, css, nothing, type TemplateResult, type CSSResult } from 'lit';
import type { SearchScopeChip } from '../state/searchState.js';
import './ScopeChip.js';

/**
 * Render the scope-chip row. Returns `nothing` when there are no active chips.
 * `onRemove(index)` is the full per-consumer handler (remove the chip + decide whether to
 * re-issue the active search).
 */
export function renderScopeChips(
  chips: readonly SearchScopeChip[],
  opts: { onRemove: (index: number) => void },
): TemplateResult | typeof nothing {
  if (chips.length === 0) return nothing;
  return html`<div class="scope-chip-row" data-testid="scope-chip-row" aria-label="Search scope">
    ${chips.map(
      (chip, index) => html`<jf-scope-chip
        .chip=${chip}
        @scope-remove=${() => opts.onRemove(index)}
      ></jf-scope-chip>`,
    )}
  </div>`;
}

/** The one set of styles for the scope-chip row — every consumer adds this to `static styles`. */
export const scopeChipRowStyles: CSSResult = css`
  .scope-chip-row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 0.35rem;
    margin-top: 0.35rem;
  }
`;
