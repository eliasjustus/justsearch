// Truth table for the runtime-witness gate (tempdoc 560 §5). Pure verdict over a single
// (operation, witness) measurement: the declared-agent-eligibility and the actual delivery must
// agree. `declared ⟺ delivered`; a mismatch either way fails —
//   - declared && !delivered  → over-claimed agent consumer (the static gate sees a consumer the
//     prompt-construction channel never carries);
//   - !declared && delivered  → phantom offering (the channel carries a declaration the catalog
//     does not account for).

/**
 * @param {{ declaredEligible?: boolean, delivered?: boolean }} measurement
 * @returns {'pass' | 'fail'}
 */
export function verdictForWitness(measurement) {
  const declared = !!(measurement && measurement.declaredEligible);
  const delivered = !!(measurement && measurement.delivered);
  return declared === delivered ? 'pass' : 'fail';
}
