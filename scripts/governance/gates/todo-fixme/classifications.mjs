/**
 * todo-fixme gate classifications — tempdoc 530 §2.6.
 * Per-file count of TODO / FIXME / XXX comments; only-shrinks.
 */

export const TODO_FIXME_CLASSIFICATIONS = new Set([
  'declared-growth',
  'merge-import',
  'emergency-override',
  'monotonic-shrink',
]);

export function aggregateTodoFixmeClassifications(declarations) {
  const classifications = declarations.map(d => d.classification);
  const growthCovered = classifications.some(c =>
    ['declared-growth', 'merge-import', 'emergency-override'].includes(c),
  );
  return { growthCovered, classifications };
}
