// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 — `<jf-operation>` aggregate component.
 *
 * The only sanctioned consumption point for rendering an Operation.
 * Surfaces declare *where and which* Operation appears via this
 * component; the canonical strategy (registered for the
 * `(Operation, <context>)` cell) decides *how* it renders.
 *
 * Attributes:
 *   - operation-id (string): looked up via OperationCatalogClient.
 *   - context (SurfaceContextKind): which strategy to dispatch.
 *   - viewer-audience (Audience): OPTIONAL override. Default reads
 *     from `viewerAudienceState` (UserStateDocument-backed) so the
 *     audience gate has a single user-controlled input source.
 *     Surfaces only set this when they need to force a tier (e.g., a
 *     debug surface forcing 'DEVELOPER' to show everything).
 *   - api-base (string): forwarded to inner ActionButton, used for invocation.
 *
 * Pass-through events: `action-invoke` from the inner button bubbles
 * naturally. The component does not invoke the operation; the
 * surrounding surface listens for `action-invoke` and dispatches the
 * call (mirrors the existing `<jf-row-actions>` pattern).
 *
 * Re-rendering: subscribes to OperationCatalogClient's
 * `onCatalogChange` so a catalog refresh re-renders the button.
 */

import { type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { SignalWatcher } from '@lit-labs/signals';
import {
  renderAggregateMulti,
  type StrategyHost,
} from '../aggregateRegistry.js';
import type { SurfaceContextKind } from '../surfaceContextKinds.js';
import {
  getOperation,
  onCatalogChange,
} from '../../../api/registry/OperationCatalogClient.js';
import type { Audience } from '../../../api/types/registry.js';
import { getViewerAudience } from '../../state/viewerAudienceState.js';
import '../../components/ActionButton.js';

export class JfOperation extends SignalWatcher(JfElement) {
  static override properties = {
    operationId: { type: String, attribute: 'operation-id' },
    context: { type: String },
    /**
     * Optional viewer-audience override. When the attribute is
     * absent (the default), the audience-gate reads from the
     * `viewerAudienceState` store. When set to a valid Audience
     * value, that value wins. The reading happens via
     * `hasAttribute('viewer-audience')` (per Track DD) — no
     * empty-string sentinel; "omit the attribute" and "set it to
     * empty" are distinguished by the DOM API.
     */
    viewerAudience: { type: String, attribute: 'viewer-audience' },
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare operationId: string;
  declare context: SurfaceContextKind;
  declare viewerAudience: Audience | null;
  declare apiBase: string;

  private catalogUnsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.operationId = '';
    this.context = 'button';
    this.viewerAudience = null;
    this.apiBase = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.catalogUnsubscribe = onCatalogChange(() => {
      this.requestUpdate();
    });
    // Slice C (§1 signals): audience reactivity is now via SignalWatcher —
    // render() reads getViewerAudience() (a signal) and re-renders
    // automatically. The former manual subscribeViewerAudience +
    // requestUpdate is removed (the attribute-override case still works:
    // effectiveViewerAudience() reads the signal only when no override).
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.catalogUnsubscribe) {
      this.catalogUnsubscribe();
      this.catalogUnsubscribe = null;
    }
  }

  /**
   * Resolve the effective viewer audience. Explicit `viewer-audience`
   * attribute (present + valid value) wins; otherwise reads from
   * `viewerAudienceState`. `hasAttribute` distinguishes "absent"
   * from "present-but-empty" cleanly — Track DD replaced an empty-
   * string sentinel pattern.
   */
  private effectiveViewerAudience(): Audience {
    if (this.hasAttribute('viewer-audience')) {
      const v = this.viewerAudience;
      if (v === 'USER' || v === 'OPERATOR' || v === 'AGENT' || v === 'DEVELOPER') {
        return v;
      }
    }
    return getViewerAudience();
  }

  /**
   * Light DOM rendering — `<jf-operation>` is a thin shell that mounts
   * the strategy's output. Using light DOM (no shadow root) lets the
   * surrounding surface's styles cascade into the rendered
   * ActionButton, which is necessary for theming.
   */
  protected override createRenderRoot(): HTMLElement {
    return this;
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.operationId) return nothing;
    const op = getOperation(this.operationId);
    if (!op) return nothing;
    const host: StrategyHost & { viewerAudience?: Audience } = {
      apiBase: this.apiBase,
      viewerAudience: this.effectiveViewerAudience(),
    };
    // Tempdoc 543 §20.7 B2 — route through renderAggregateMulti
    // (Slice 6 substrate). For all current Operation contexts the
    // dispatch policy is the default 'winner' so the result array
    // has 0 or 1 element; rendering results[0] preserves the prior
    // single-winner semantics exactly. Future merge contexts gain
    // composition for free without touching JfOperation.
    const ctx = {} as Record<string, unknown>;
    const results = renderAggregateMulti(
      'Operation',
      this.context,
      op,
      ctx as never,
      host,
    );
    return results[0] ?? nothing;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-operation')
) {
  customElements.define('jf-operation', JfOperation);
}
