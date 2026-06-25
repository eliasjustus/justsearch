// Truth table for the host-owns-truth gate (tempdoc 560 §4.5 / kernel decision 05). Pure verdict:
// the reserved `core` namespace is the host's truth; the `vendor.*` namespace is for contributions.
// A core-namespace id with non-CORE provenance forks core truth; a vendor-namespace id with CORE
// provenance is a mislabeled contribution. Both fail.

/**
 * @param {{ id?: string, provenance?: string }} entry
 * @returns {'pass' | 'fail'}
 */
export function verdictForEntry(entry) {
  const id = (entry && entry.id) || '';
  const provenance = (entry && entry.provenance) || '';
  if (id.startsWith('core.') && provenance !== 'CORE') return 'fail';
  if (id.startsWith('vendor.') && provenance === 'CORE') return 'fail';
  return 'pass';
}
