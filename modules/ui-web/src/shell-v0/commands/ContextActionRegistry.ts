// SPDX-License-Identifier: Apache-2.0
/**
 * ContextActionRegistry — Tempdoc 508 §4.2 — context-menu action
 * contribution.
 *
 * Plugins contribute actions for specific contexts (file rows, search
 * results, surface headers). The context-menu opener filters by context
 * name and renders matching actions.
 *
 * Tempdoc 543 §28.W7 — uses the shared `createRegistry` primitive.
 */

import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import type { Provenance } from '../primitives/provenance.js';
import { buildEvaluationContext } from '../substrates/evaluationContext/index.js';
import type { Addressable } from '../substrates/addressable.js';
import { createRegistry } from '../primitives/registry.js';

export interface ContextActionContribution {
  readonly id: string;
  /** Context where the action applies (e.g., 'file', 'search-result'). */
  readonly context: string;
  readonly label: string;
  readonly icon?: string;
  readonly priority: number;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /**
   * Tempdoc 508 §11.1 / §13.1 — `when` predicate evaluated against
   * the live ShellContext at list-time. Absent = always visible (per
   * existing context-string match). Set this to scope by selection
   * capabilities, focus state, etc.
   */
  readonly when?: string;
  readonly handler: (payload: unknown) => void | Promise<void>;
  /**
   * Per-payload enable predicate. §13.1 wires the registry's
   * `listContextActions` to filter on this; previously consumers
   * had to call it themselves. `when` and `enabled` are
   * complementary: `when` gates by global shell state, `enabled` by
   * per-invocation payload.
   */
  readonly enabled?: (payload: unknown) => boolean;
}

const _registry = createRegistry<ContextActionContribution>();

export const registerContextAction = _registry.register;
export const unregisterContextAction = _registry.unregister;
export const onContextActionChange = _registry.subscribe;
export const __resetForTest = _registry.__resetForTest;

/**
 * List context actions matching the given context. §13.1 adds two
 * filters on top of the string-equality `context` match:
 *   - `when` against the live EvaluationContext (Scope ∪ TargetFacts)
 *   - `enabled(payload)` when a payload is provided (per-invocation)
 *
 * Tempdoc 543 §13.2.1 — when `addressable` is provided, evaluator
 * input is the layered EvaluationContext (Scope ∪ per-Addressable
 * TargetFacts via registered projector). When absent, falls back to
 * the Scope-only projection (identical behavior to pre-substrate).
 */
export function listContextActions(
  context?: string,
  payload?: unknown,
  addressable?: Addressable | null,
): ContextActionContribution[] {
  const ctx = buildEvaluationContext({
    scope: getShellContext() as unknown as Record<string, unknown>,
    addressable: addressable ?? null,
  });
  const all = Array.from(_registry.list());
  const filtered = all.filter((a) => {
    if (context !== undefined && a.context !== context) return false;
    if (!evaluateWhen(a.when, ctx)) return false;
    if (payload !== undefined && a.enabled && !a.enabled(payload)) return false;
    return true;
  });
  return filtered.sort((a, b) => a.priority - b.priority);
}
