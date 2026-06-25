/**
 * prose-tier-register gate classification vocabulary — tempdoc 530 §Meta-loop.
 *
 * Used when a PR modifies `.claude/rules/tier-register.md` (changing a rule's
 * tier, retiring a rule, etc.) or when a referenced gate id doesn't exist.
 */

export const PROSE_TIER_CLASSIFICATIONS = new Set([
  'tier-change',
  'new-rule-registered',
  'rule-retired',
  'emergency-override',
]);

/**
 * @param {Array<{classification: string}>} declarations
 */
export function aggregateProseTierClassifications(declarations) {
  const classifications = declarations.map(d => d.classification);
  return {
    changeCovered: classifications.some(c =>
      ['tier-change', 'new-rule-registered', 'rule-retired', 'emergency-override'].includes(c),
    ),
    classifications,
  };
}
