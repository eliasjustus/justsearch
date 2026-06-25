// Truth table for the consumer-presence gate (tempdoc 560 §5/§6 — the NonEmpty<ConsumerHook>
// keystone). Pure verdict functions, per the discipline-gate-kernel contract
// (scripts/governance/lib/truth-table-runner.mjs): a declaration with >=1 consumer passes; a
// zero-consumer declaration fails unless grandfathered, in which case it is surfaced as `info`.

/**
 * Verdict for one registry-snapshot entry.
 *
 * @param {{ consumerCount?: number }} entry
 * @param {boolean} isExempt whether the entry's id is grandfathered in exemptions.json
 * @returns {'pass' | 'fail' | 'info'}
 */
export function verdictForEntry(entry, isExempt) {
  const count = entry && typeof entry.consumerCount === 'number' ? entry.consumerCount : 0;
  if (count > 0) return 'pass';
  return isExempt ? 'info' : 'fail';
}
