// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 549 — `<jf-search-trace>` aggregate component.
 *
 * The sanctioned consumption point for rendering the unified wire {@link SearchTrace}
 * record. Each search response carries one trace (the single canonical artifact); the
 * search surface mounts one of these per query to render the explain panel. Supersedes
 * `<jf-search-introspection>`, which retires in Phase E4.
 *
 * Usage:
 *   <jf-search-trace
 *     context="search-explain"
 *     .trace=${response.searchTrace}
 *   ></jf-search-trace>
 *
 * Light DOM so surrounding surface CSS cascades (the search surface owns the visual
 * styling of `.search-explain` / `.search-explain-stages` / etc; this component only
 * emits structural markup).
 */

import { type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { dispatchAggregateStrategy, type StrategyHost } from '../aggregateRegistry.js';
import type { SurfaceContextKind } from '../surfaceContextKinds.js';
import type { SearchTrace } from '../../../api/generated/index.js';

export class JfSearchTrace extends JfElement {
  static override properties = {
    trace: { attribute: false },
    context: { type: String },
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare trace: SearchTrace | null;
  declare context: SurfaceContextKind;
  declare apiBase: string;

  constructor() {
    super();
    this.trace = null;
    this.context = 'search-explain';
    this.apiBase = '';
  }

  protected override createRenderRoot(): HTMLElement {
    return this;
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.trace) return nothing;
    const strategy = dispatchAggregateStrategy('SearchTrace', this.context);
    if (!strategy) return nothing;
    const host: StrategyHost = { apiBase: this.apiBase };
    const ctx = {} as Record<string, unknown>;
    const result = strategy(this.trace, ctx as never, host);
    return (result ?? nothing) as TemplateResult | typeof nothing;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-search-trace')) {
  customElements.define('jf-search-trace', JfSearchTrace);
}
