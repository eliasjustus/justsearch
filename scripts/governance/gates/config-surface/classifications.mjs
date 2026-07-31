/**
 * config-surface gate classifications — tempdoc 799 K.4.
 * Ratchet over the runtime configuration surface; only-shrinks.
 */

export const CONFIG_SURFACE_CLASSIFICATIONS = new Set([
  'declared-growth',
  'merge-import',
  'emergency-override',
  'monotonic-shrink',
]);

export function aggregateConfigSurfaceClassifications(declarations) {
  const classifications = declarations.map((d) => d.classification);
  const growthCovered = classifications.some((c) =>
    ['declared-growth', 'merge-import', 'emergency-override'].includes(c),
  );
  return { growthCovered, classifications };
}
