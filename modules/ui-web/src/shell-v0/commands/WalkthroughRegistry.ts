// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §16.4 — Walkthroughs registry.
 *
 * A walkthrough is a small, ordered sequence of guided steps a user
 * progresses through (open palette → run a search → switch theme → …).
 * Modeled as a contribution axis analogous to {@link
 * '../commands/EmptyStateRegistry.ts'} so plugins can contribute their
 * own walkthroughs through the same lifecycle (V1.5 install /
 * uninstall) used for empty-state cards, status-bar items, etc.
 *
 * Step completion is driven by per-step `completionEvents`. The V1
 * vocabulary supports only `onCommand:<commandId>` — when the command
 * with that id is invoked through the CommandRegistry, the step is
 * marked complete and the walkthrough advances. Future event-kinds
 * (e.g., `onShellContextChange`) fold in additively.
 *
 * Persistence: progress (active step index, completed step ids,
 * dismissed flag) lives in `UserStateDocument.walkthroughState`, a
 * cross-profile slice (one user, one progress record per
 * walkthrough id). The registry itself is in-memory; persistence is
 * the consumer's concern.
 *
 * Tempdoc 543 §28.W7 — uses the shared `createRegistry` primitive.
 * register wraps the factory with a pre-register validation that
 * walkthroughs declare at least one step.
 */

import { getShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import type { Provenance } from '../primitives/provenance.js';
import { createRegistry } from '../primitives/registry.js';

export interface WalkthroughStep {
  readonly id: string;
  /** Step title shown in the walkthrough card. */
  readonly title: string;
  /** Markdown / plain-text body shown beneath the title. */
  readonly body: string;
  /**
   * Optional completion event vocabulary — VS Code's published kinds,
   * plus an explicit "no event" (user-driven Next click).
   *
   * Tempdoc 521 §16.4 deeper (full vocabulary):
   *   - `onCommand:<commandId>` — fires when CommandRegistry invokes
   *     that id (any caller, fire-and-forget or awaitable variant).
   *   - `onSettingChanged:<key>` — fires when the named
   *     UserStateDocument setting transitions. V1 published keys:
   *     activeThemeId, activeProfileId, activeLayoutId, viewerAudience.
   *   - `extensionInstalled:<pluginId>` — fires after PluginRegistry
   *     successfully installs the named plugin.
   */
  readonly completionEvent?:
    | `onCommand:${string}`
    | `onSettingChanged:${string}`
    | `extensionInstalled:${string}`;
}

export interface WalkthroughContribution {
  readonly id: string;
  /** Human-readable label shown in the walkthrough card header. */
  readonly title: string;
  /** Short description for picker UIs. */
  readonly description?: string;
  /** Ordering hint for picker UIs (ascending). */
  readonly priority: number;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /**
   * §11.1 when-clause gating. Absent = always available. Evaluated
   * against the live ShellContext.
   */
  readonly when?: string;
  readonly steps: ReadonlyArray<WalkthroughStep>;
}

const _registry = createRegistry<WalkthroughContribution>();

export function registerWalkthrough(c: WalkthroughContribution): void {
  if (c.steps.length === 0) {
    throw new Error(`Walkthrough '${c.id}' has no steps`);
  }
  _registry.register(c);
}

export const unregisterWalkthrough = _registry.unregister;
export const getWalkthrough = _registry.get;
export const onWalkthroughCatalogChange = _registry.subscribe;
export const __resetWalkthroughsForTest = _registry.__resetForTest;

/**
 * List walkthroughs visible against the live ShellContext, sorted by
 * priority. Plugins that contributed walkthroughs gated by `when`
 * (e.g., a "first-run" walkthrough gated by `profileFirstRun == true`)
 * are filtered here.
 */
export function listWalkthroughs(): WalkthroughContribution[] {
  const ctx = getShellContext() as unknown as Record<string, unknown>;
  return Array.from(_registry.list())
    .filter((c) => evaluateWhen(c.when, ctx))
    .sort((a, b) => a.priority - b.priority);
}
