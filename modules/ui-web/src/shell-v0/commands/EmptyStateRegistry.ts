// SPDX-License-Identifier: Apache-2.0
/**
 * EmptyStateRegistry — Tempdoc 508 §11.6 / §13.6 — contribution
 * axis for "what to show when this view has nothing to render."
 *
 * Empty states are not ad-hoc per surface; they're a structural
 * registry. Each surface that supports an empty state declares a
 * stable context name (e.g., 'palette-no-results',
 * 'search-no-results', 'library-empty') and queries the registry
 * for matching contributions. Plugins contribute fallback cards
 * (Raycast-style "Search web for X", "Ask AI about X", etc.) by
 * registering against the same context.
 *
 * Visibility is gated by §11.1 `when` expressions evaluated against
 * the live ShellContext.
 *
 * Tempdoc 543 §28.W7 — uses the shared `createRegistry` primitive.
 */

import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import type { Provenance } from '../primitives/provenance.js';
import { createRegistry } from '../primitives/registry.js';

export interface EmptyStateInput {
  /** Context name — e.g., 'palette-no-results'. */
  readonly context: string;
  /** User's current query string, if applicable. */
  readonly query?: string;
  /** Surface emitting the empty state, if applicable. */
  readonly surface?: string;
}

export interface EmptyStateContribution {
  readonly id: string;
  /** Context name this contribution renders for. */
  readonly context: string;
  /** Render priority (ascending). */
  readonly priority: number;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /**
   * §11.1 when-clause gating. Absent = always visible (subject to
   * context match). When set, evaluated against the live ShellContext.
   */
  readonly when?: string;
  /**
   * Render the contribution. Receives the input that triggered the
   * empty state. Returns an HTMLElement (mounted into the empty-
   * state container) or a string (rendered as text content).
   */
  readonly render: (input: EmptyStateInput) => HTMLElement | string;
}

const _registry = createRegistry<EmptyStateContribution>();

export const registerEmptyState = _registry.register;
export const unregisterEmptyState = _registry.unregister;
export const onEmptyStateChange = _registry.subscribe;
export const __resetEmptyStateForTest = _registry.__resetForTest;

/**
 * List contributions matching the given context, sorted by priority.
 * Filtered by `when` against the live ShellContext.
 */
export function listEmptyStates(
  input: EmptyStateInput,
): EmptyStateContribution[] {
  const ctx = getShellContext() as unknown as Record<string, unknown>;
  return Array.from(_registry.list())
    .filter((c) => c.context === input.context)
    .filter((c) => evaluateWhen(c.when, ctx))
    .sort((a, b) => a.priority - b.priority);
}
