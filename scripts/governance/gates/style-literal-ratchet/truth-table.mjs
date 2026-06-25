/**
 * style-literal-ratchet truth-table — tempdoc 574 §25 Phase 4. Mirrors the
 * ts-any per-file-ratchet verdict logic (the verdict is inlined in the shared
 * `makeRatchetGate` factory for terseness; this is the kernel-required pure
 * verdict function).
 */
export function verdictForFile({ path, current, pinned, classification }) {
  if (current <= pinned) {
    return current < pinned
      ? {
          ruleId: 'style-literal-ratchet/rebalance-available',
          status: 'info',
          reason: `${path}: ${current} < ${pinned}`,
        }
      : {
          ruleId: 'style-literal-ratchet/within-baseline',
          status: 'pass',
          reason: `${path}: at baseline`,
        };
  }
  if (classification === 'silent-growth') {
    return {
      ruleId: 'style-literal-ratchet/silent-growth',
      status: 'fail',
      reason: `${path}: ${pinned} → ${current}`,
    };
  }
  return {
    ruleId: `style-literal-ratchet/${classification}`,
    status: 'pass',
    reason: `${path}: classification covers`,
  };
}
