// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511 + 511-followup — Canonical (Operation, button) strategy.
 *
 * Renders an Operation as a button using `<jf-op-button>` (slice
 * 509's catalog-driven button). The strategy adds:
 *   - audience gate via `operationVisibleTo` (returns `nothing` on
 *     deny);
 *   - tooltip aggregation from `lineage.affects` and
 *     `lineage.supersedes`;
 *   - first-class forwarding of `policy.confirm.kind` to OpButton
 *     (which forwards to ActionButton's `confirm-kind` prop, so the
 *     wire's ceremony drives behavior — not risk-as-proxy).
 *
 * The OPERATION_BUTTON_ROLES record classifies every Operation
 * field by its role here:
 *   - 'visual':  mutation produces a rendered-output diff
 *                (this strategy reads it directly)
 *   - 'gate':    field gates the strategy's return (audience)
 *   - 'routing': passed downstream by id; OpButton fetches the
 *                full Operation from the catalog
 *   - 'elided':  intentionally unconsumed by this cell
 *
 * The behavioral Pass-8 test in `operationButton.test.ts` verifies
 * each role: 'visual' / 'gate' mutations MUST produce a diff;
 * 'routing' / 'elided' mutations MUST NOT. A strategy that claims
 * 'visual' for a field it never reads fails the behavioral test.
 */

import { html, nothing } from 'lit';
import type {
  Operation,
  Audience,
} from '../../../api/types/registry.js';
import type { AggregateStrategy } from '../aggregateRegistry.js';
import {
  registerAggregateStrategy,
} from '../aggregateRegistry.js';
import { assertFieldRoles, type FieldRoles } from '../assertExhaustive.js';
import { operationVisibleTo } from '../queryPrimitives.js';
import '../../components/OpButton.js';

/**
 * Field-role classification — drives the behavioral Pass-8 test.
 * Every Operation key must be listed; TypeScript fails the literal
 * if a key is missing.
 */
export const OPERATION_BUTTON_ROLES: FieldRoles<Operation> = {
  id: 'visual',                 // operation-id attr on jf-op-button
  policy: 'visual',             // policy.confirm.kind → confirm-kind attr
  lineage: 'visual',            // affects/supersedes → title tooltip
  audience: 'gate',             // operationVisibleTo
  // Routed via id to OpButton's catalog lookup (which reads
  // presentation.labelKey internally). The strategy doesn't read
  // them directly, so mutation MUST NOT change strategy output.
  presentation: 'routing',
  intf: 'routing',
  provenance: 'routing',
  // Elided in this cell. Reintroduce when a styling/visibility
  // concern claims the field; the behavioral test catches lies.
  availability: 'elided',
  executors: 'elided',
  consumers: 'elided',
};
assertFieldRoles<Operation>(OPERATION_BUTTON_ROLES);

/**
 * Strategy host extension. The button strategy needs to know the
 * viewer's audience to apply the visibility gate, and the API base
 * URL so the inner OpButton can dispatch invocations. Surfaces pass
 * these when mounting `<jf-operation>`.
 */
export interface OperationButtonHostExtensions {
  viewerAudience?: Audience;
}

const DEFAULT_VIEWER_AUDIENCE: Audience = 'USER';

/**
 * Canonical (Operation, button) strategy.
 *
 * Returns nothing when the viewer's audience doesn't qualify for the
 * operation — the audience gate. Surfaces that want the button to
 * always render (e.g., a debug surface) should pass viewerAudience
 * = 'DEVELOPER' which clears all gates.
 *
 * Internally mounts `<jf-op-button>` (slice 509's catalog-driven
 * button). OpButton handles label-derivation, risk-styling, and
 * invocation. The strategy layers on:
 *   - audience gate (operationVisibleTo) — returns nothing on deny
 *   - data attributes for every metadata field (provenance.tier,
 *     audit, undoSupported, rateLimitMs, executors, availability,
 *     consumers, interface, confirm.kind, lineage) so future styling
 *     passes have the wire metadata on the DOM
 *   - tooltip aggregation (description + lineage.affects/supersedes)
 *
 * `policy.confirm.kind` is exposed via `data-confirm-kind` for now;
 * full wire-driven ceremony selection requires extending OpButton +
 * ActionButton to accept the confirm-kind prop from the strategy
 * (tracked as a follow-up; OpButton currently derives ceremony from
 * risk alone).
 */
export const operationButtonStrategy: AggregateStrategy<
  'Operation',
  'button'
> = (op, _ctx, host) => {
  const viewerAudience =
    (host as typeof host & OperationButtonHostExtensions).viewerAudience ??
    DEFAULT_VIEWER_AUDIENCE;
  if (!operationVisibleTo(op, viewerAudience)) {
    return nothing;
  }

  const tooltipParts: string[] = [];
  if (op.lineage.affects.length > 0) {
    tooltipParts.push(`Affects: ${op.lineage.affects.join(', ')}`);
  }
  if (op.lineage.supersedes.length > 0) {
    tooltipParts.push(`Supersedes: ${op.lineage.supersedes.join(', ')}`);
  }
  const title = tooltipParts.join('\n');

  // Confirm-kind is forwarded as a first-class attribute (511-followup
  // Track B). OpButton reads `confirm-kind` and forwards to the inner
  // ActionButton so the wire's policy.confirm.kind drives ceremony
  // selection — not the legacy risk-as-proxy-for-ceremony shortcut.
  return html`
    <jf-op-button
      operation-id=${op.id}
      api-base=${host.apiBase}
      confirm-kind=${op.policy.confirm.kind}
      title=${title}
    ></jf-op-button>
  `;
};

/**
 * Registration. Called by the substrate's bootstrap module. Returns
 * the unregister handle for tests.
 */
export function registerOperationButtonStrategy(): () => void {
  return registerAggregateStrategy({
    aggregate: 'Operation',
    context: 'button',
    rank: 0,
    strategy: operationButtonStrategy,
    source: 'core',
  });
}

// Avoid unused-name warning when the helper isn't imported elsewhere.
void operationButtonStrategy;
