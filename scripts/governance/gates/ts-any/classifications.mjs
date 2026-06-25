export { TS_ANY_CLASSIFICATIONS } from './enforcer.mjs';
export function aggregateTsAnyClassifications(declarations) {
  const cs = declarations.map(d => d.classification);
  return { growthCovered: cs.some(c => ['declared-growth','merge-import','emergency-override'].includes(c)), classifications: cs };
}
