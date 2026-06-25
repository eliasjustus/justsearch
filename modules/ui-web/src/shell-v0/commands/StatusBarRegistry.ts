// SPDX-License-Identifier: Apache-2.0
/**
 * StatusBarRegistry — Tempdoc 508 §4.2 — contribution registry for
 * status bar items.
 *
 * StatusDeck reads from this registry to render items. Core items
 * and plugin-contributed items go through the same mechanism.
 *
 * Tempdoc 543 §3.A — `provenance` is the typed uniform attribution.
 * Tempdoc 543 §21.A2 (D5) — provenance is REQUIRED on registration;
 * the legacy `source` discriminator is retained for tag-based
 * historical filtering but no longer drives attribution.
 *
 * Tempdoc 543 §28.W7 — uses the shared `createRegistry` primitive
 * for boilerplate consolidation.
 */

import type { Provenance } from '../primitives/provenance.js';
import { createRegistry } from '../primitives/registry.js';

export interface StatusBarItem {
  readonly id: string;
  readonly position: 'left' | 'right';
  readonly priority: number;
  readonly render: () => HTMLElement | string;
  readonly source: 'core' | 'plugin';
  /** Tempdoc 543 §3.A / §21.A2 — required typed provenance. */
  readonly provenance: Provenance;
  /**
   * Tempdoc 559 Authority V (Operability), declaration-deepening — the DECLARED
   * accessible name of this metric (e.g. "Documents indexed"). The status item's
   * accessible name PROJECTS from this field through the one display authority
   * (`present({kind:'metric', id})`) rather than being a hand-stamped inline
   * string in the renderer. Required for `core.*` items (the `controls-a11y`
   * gate enforces it); a plugin item that omits it falls back to its humanized id.
   * A plain string (not the branded `DisplayLabel`) so this registry has no
   * import cycle with `present.ts` — the projector brands it.
   */
  readonly accessibleLabel?: string;
  /**
   * Tempdoc 559 Part II Authority VI (Adaptivity), declaration-deepening — the
   * per-item overflow POLICY, beyond bare priority order. `'pinned'` items are
   * never hidden when space runs out (the adaptive bar trims only the `'normal'`
   * tail); use for the always-visible health signals (connection / memory).
   * Default `'normal'`. Overflow is therefore a projection of
   * (space × priority × this policy), not space × priority alone.
   */
  readonly overflow?: 'normal' | 'pinned';
}

const _registry = createRegistry<StatusBarItem>();

export const registerStatusBarItem = _registry.register;
export const unregisterStatusBarItem = _registry.unregister;
export const onStatusBarChange = _registry.subscribe;
export const __resetForTest = _registry.__resetForTest;

export function listStatusBarItems(position?: 'left' | 'right'): StatusBarItem[] {
  const all = Array.from(_registry.list());
  const filtered = position ? all.filter((i) => i.position === position) : all;
  return filtered.sort((a, b) => a.priority - b.priority);
}

/** O(1) lookup by id — the seam `present({kind:'metric'})` projects a name from (559 Authority V). */
export function getStatusBarItem(id: string): StatusBarItem | undefined {
  return _registry.get(id);
}
