/**
 * wire-Category truth-table — tempdoc 530 §4.3 (Pass-7 Phase F).
 *
 * Conforms to scripts/governance/lib/truth-table-runner.mjs (substrate
 * requires a `verdict*` export). Wraps the protobuf-evolution truth-table
 * function from protobuf-truth-table.mjs.
 */

import { computeVerdict } from './protobuf-truth-table.mjs';

/**
 * @param {{classification, breaks, currentVersion, baselineVersion}} input
 * @returns {{ruleId, status: 'pass'|'fail'|'info', reason}}
 */
export function verdictForProtobufEvolution(input) {
  const v = computeVerdict(input);
  // Map pass-noop → info per the substrate's tri-state vocabulary.
  const status = v.status === 'pass-noop' ? 'info' : v.status;
  return { ruleId: `contract-governance/${v.ruleId}`, status, reason: v.reason };
}
