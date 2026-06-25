// SPDX-License-Identifier: Apache-2.0
/**
 * Promoted alias store (tempdoc 499 F2).
 *
 * When a user clicks a "did you mean?" suggestion chip, the correction
 * is recorded as a promoted alias. Next time the same typo occurs, the
 * alias lookup in resolve() catches it instantly — no fuzzy matching
 * needed.
 *
 * Stored in localStorage as a JSON map: { typo: { target, reason } }.
 */

import type { AliasMap, AliasEntry } from './resolution.js';

const STORAGE_KEY = 'jf.promoted-aliases';

export function loadPromotedAliases(): AliasMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    return JSON.parse(raw) as AliasMap;
  } catch {
    return {};
  }
}

export function promoteAlias(typo: string, correctedId: string): void {
  const aliases = loadPromotedAliases();
  const entry: AliasEntry = { target: correctedId, reason: 'renamed' };
  const updated = { ...aliases, [typo]: entry };
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
  } catch {
    // localStorage full — silent degradation
  }
}
