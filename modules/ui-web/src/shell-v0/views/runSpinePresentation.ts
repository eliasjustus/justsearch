// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 621 Phase 5 — the run-spine's PURE presentation helpers, extracted from UnifiedChatView.ts so
 * the host keeps only the render + interaction wiring. Both are pure functions of their inputs (no element
 * state), matching the sibling `runStepPresentation` / `budgetProjection` / `unifiedThreadProjection`
 * pattern — independently unit-testable, and the host calls them from `renderRunSpine`.
 */
import type { UnifiedTurnItem } from './unifiedThreadProjection.js';

/**
 * §13/§19.4 — the spine minimap's per-item vertical positions (0..1). Only the TURNS (user/assistant)
 * anchor at their real measured scroll fraction (from the `NavigationController`'s `fractions` map); the
 * intra-run steps (tool/progress/error) are interpolated EVENLY between the turn landmarks as "texture",
 * so a dense run's step burst spreads across the user→answer span instead of knotting into one region.
 */
export function computeSpinePositions(
  items: readonly UnifiedTurnItem[],
  fractions: ReadonlyMap<string, number>,
): number[] {
  const n = items.length;
  const raw = items.map((it) => {
    if (it.kind !== 'user' && it.kind !== 'assistant') return null;
    const f = fractions.get(it.id);
    return f === undefined ? null : f;
  });
  const out = new Array<number>(n).fill(0);
  let i = 0;
  while (i < n) {
    if (raw[i] !== null) {
      out[i] = raw[i] as number;
      i++;
      continue;
    }
    let j = i;
    while (j < n && raw[j] === null) j++;
    const lo = i === 0 ? 0 : (raw[i - 1] as number);
    const hi = j === n ? 1 : (raw[j] as number);
    const span = hi - lo;
    const count = j - i;
    for (let k = 0; k < count; k++) out[i + k] = lo + (span * (k + 1)) / (count + 1);
    i = j;
  }
  return out;
}

/** §13 Pillar A — the accessible name for a spine node (it is an operable jump control). */
export function spineNodeLabel(it: UnifiedTurnItem): string {
  switch (it.kind) {
    case 'user':
      return 'Jump to your message';
    case 'assistant':
      return 'Jump to the answer';
    case 'tool-activity':
      return 'Jump to a tool step';
    case 'error':
      return 'Jump to an error';
    default:
      return 'Jump to a step';
  }
}
