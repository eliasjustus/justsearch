// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 543 §12.3 #5 — Canonical (Operation, hover-preview) strategy.
 *
 * Renders an Operation's hover-preview body. The hover lifecycle
 * (debounce, dismissal, focus restoration) lives in the kernel-
 * rendered popover host (`<jf-hover-preview-host>`) — this strategy
 * contributes the BODY only, per the "plugins request, kernel
 * renders" rule.
 *
 * Body content: operation id + risk/confirm-kind summary + lineage
 * hints when present.
 */

import { html, nothing } from 'lit';
import type { AggregateStrategy } from '../aggregateRegistry.js';
import { registerAggregateStrategy } from '../aggregateRegistry.js';

export const operationHoverPreviewStrategy: AggregateStrategy<
  'Operation',
  'hover-preview'
> = (op) => {
  const lines: string[] = [];
  if (op.lineage.affects.length > 0) {
    lines.push(`Affects: ${op.lineage.affects.join(', ')}`);
  }
  if (op.lineage.supersedes.length > 0) {
    lines.push(`Supersedes: ${op.lineage.supersedes.join(', ')}`);
  }
  return html`
    <div class="op-hover-preview" data-op-id=${op.id}>
      <div class="op-hover-preview__title">${op.id}</div>
      <div class="op-hover-preview__confirm">
        Confirm: ${op.policy.confirm.kind}
      </div>
      ${lines.length > 0
        ? html`<div class="op-hover-preview__lineage">
            ${lines.join(' · ')}
          </div>`
        : nothing}
    </div>
  `;
};

export function registerOperationHoverPreviewStrategy(): () => void {
  return registerAggregateStrategy({
    aggregate: 'Operation',
    context: 'hover-preview',
    rank: 0,
    strategy: operationHoverPreviewStrategy,
    source: 'core',
  });
}
