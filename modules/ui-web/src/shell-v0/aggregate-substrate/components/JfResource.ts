// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — `<jf-resource>` aggregate component.
 *
 * The sanctioned consumption point for rendering a Resource. Surfaces
 * mount `<jf-resource context="list-item" resource-id="..." viewer-
 * audience="USER">` rather than calling `<jf-resource-view>` directly.
 * Internally dispatches to the canonical strategy for the cell, which
 * (for V1) delegates to <jf-resource-view> after applying the
 * audience gate.
 */

import { type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { SignalWatcher } from '@lit-labs/signals';
import {
  dispatchAggregateStrategy,
  type StrategyHost,
} from '../aggregateRegistry.js';
import type { SurfaceContextKind } from '../surfaceContextKinds.js';
import {
  getResource,
  onCatalogChange,
} from '../../../api/registry/ResourceCatalogClient.js';
import type { Audience } from '../../../api/types/registry.js';
import { getViewerAudience } from '../../state/viewerAudienceState.js';

export class JfResource extends SignalWatcher(JfElement) {
  static override properties = {
    resourceId: { type: String, attribute: 'resource-id' },
    context: { type: String },
    /**
     * Optional viewer-audience override. Track DD: `hasAttribute`
     * semantics — when the attribute is absent (the default),
     * `viewerAudienceState` is read. When present + valid, that
     * value wins. "Absent" and "present-but-empty" are
     * distinguished by the DOM API.
     */
    viewerAudience: { type: String, attribute: 'viewer-audience' },
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare resourceId: string;
  declare context: SurfaceContextKind;
  declare viewerAudience: Audience | null;
  declare apiBase: string;

  private catalogUnsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.resourceId = '';
    this.context = 'list-item';
    this.viewerAudience = null;
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.catalogUnsubscribe = onCatalogChange(() => {
      this.requestUpdate();
    });
    // Slice C (§1 signals): audience reactivity via SignalWatcher — render()
    // reads getViewerAudience() (a signal) and re-renders automatically.
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.catalogUnsubscribe) {
      this.catalogUnsubscribe();
      this.catalogUnsubscribe = null;
    }
  }

  private effectiveViewerAudience(): Audience {
    if (this.hasAttribute('viewer-audience')) {
      const v = this.viewerAudience;
      if (v === 'USER' || v === 'OPERATOR' || v === 'AGENT' || v === 'DEVELOPER') {
        return v;
      }
    }
    return getViewerAudience();
  }

  protected override createRenderRoot(): HTMLElement {
    return this;
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.resourceId) return nothing;
    const res = getResource(this.resourceId);
    if (!res) return nothing;
    const strategy = dispatchAggregateStrategy('Resource', this.context);
    if (!strategy) return nothing;
    const host: StrategyHost & { viewerAudience?: Audience } = {
      apiBase: this.apiBase,
      viewerAudience: this.effectiveViewerAudience(),
    };
    const ctx = {} as Record<string, unknown>;
    const result = strategy(res, ctx as never, host);
    return (result ?? nothing) as TemplateResult | typeof nothing;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-resource')
) {
  customElements.define('jf-resource', JfResource);
}
