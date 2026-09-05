/** Pure identity/severity verdicts for the historical npm-audit gate id. */

const RANK = { unknown: 0, low: 1, moderate: 2, high: 3, critical: 4 };

export function verdictForAdvisory({ target, ghsaId, acceptedSeverity, currentSeverity, classification }) {
  if (acceptedSeverity && RANK[acceptedSeverity] >= RANK[currentSeverity]) {
    return { ruleId: 'npm-audit/within-baseline', status: 'pass', reason: `${target}/${ghsaId} is accepted at ${acceptedSeverity}` };
  }
  if (classification === 'silent-regression') {
    return { ruleId: 'npm-audit/silent-regression', status: 'fail', reason: `${target}/${ghsaId}@${currentSeverity} is not accepted` };
  }
  return { ruleId: `npm-audit/${classification}-without-repin`, status: 'fail', reason: `${target}/${ghsaId}@${currentSeverity} is declared but not repinned` };
}

export function verdictForBaselineShift({ target, ghsaId, classification }) {
  if (classification === 'silent-regression') {
    return { ruleId: 'npm-audit/silent-baseline-shift', status: 'fail', reason: `${target}/${ghsaId} was accepted without a changeset` };
  }
  return { ruleId: 'npm-audit/declared-baseline-shift', status: 'info', reason: `${target}/${ghsaId} is covered by '${classification}'` };
}
