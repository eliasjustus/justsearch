/** adr-coverage truth-table — tempdoc 530 §2.7 + tempdoc 884 (premise probes). */

/** Where an author is sent when a probe fails. Quoted verbatim in every failure message. */
export const AMENDMENT_PROCEDURE =
  'docs/decisions/README.md § How to re-examine an ADR';

export function verdictForAdrGlob({ adr, glob, matches }) {
  if (matches.length > 0) {
    return { ruleId: 'adr-coverage/all-paths-resolve', status: 'pass', reason: `${adr}: glob '${glob}' resolves to ${matches.length} file(s)` };
  }
  return { ruleId: 'adr-coverage/stale-coverage', status: 'fail', reason: `${adr}: glob '${glob}' matches no file` };
}

/**
 * A probe either still holds or the ADR's premise has drifted away from the code.
 * The failure message quotes the premise and names the amendment procedure, so the
 * remedy reads as "re-examine and amend the ADR", never "edit the number until green".
 */
export function verdictForProbe({ adr, probeId, premise, ok, detail }) {
  if (ok) {
    return {
      ruleId: 'adr-coverage/probe-ok',
      status: 'pass',
      reason: `${adr}: probe '${probeId}' holds — ${detail}`,
    };
  }
  return {
    ruleId: 'adr-coverage/probe-failed',
    status: 'fail',
    reason:
      `${adr}: premise "${premise}" no longer holds — ${detail}. `
      + `The fix is to re-examine and amend the ADR (${AMENDMENT_PROCEDURE}), `
      + `not to edit the probe until it passes. Probe id: '${probeId}' `
      + `in governance/adr-probes.v1.json.`,
  };
}

/**
 * Every live (`accepted*` / `stable*`) ADR either names probes or states why it cannot
 * have one (`probes: none - <reason>`). Warning, not a block (884 §3).
 */
export function verdictForProbeCoverage({ adr, status, probeCount, statedReason }) {
  if (probeCount > 0) {
    return { ruleId: 'adr-coverage/has-probe', status: 'pass', reason: `${adr}: ${probeCount} premise probe(s)` };
  }
  if (statedReason) {
    return { ruleId: 'adr-coverage/has-probe', status: 'pass', reason: `${adr}: no probe, reason stated — ${statedReason}` };
  }
  return {
    ruleId: 'adr-coverage/no-probe',
    status: 'info',
    reason:
      `${adr}: status '${status}' but no premise probe and no stated reason. `
      + `Add 'probes: [<id>]' naming an entry in governance/adr-probes.v1.json, `
      + `or 'probes: none - <reason>' when the premise has no cheap mechanical form.`,
  };
}

/**
 * Where an author is sent when a risk row's instrument stops resolving. The remedy is
 * deliberately one-directional: build the thing, or amend the row. Deleting the reference
 * is the move this rule exists to make impossible to do quietly.
 */
export const RISK_INSTRUMENT_INTENT =
  'A row whose instrument stops resolving is a lane that closed without building what it promised.';

/**
 * One risk row's `**Instrument:**` reference either resolves against the tree or it does not.
 * The failure quotes the reference verbatim so the message is actionable without opening the
 * register, and names the two legitimate fixes so "delete the reference" never reads as one.
 */
export function verdictForRiskInstrument({ riskId, instrument, ok, detail }) {
  if (ok) {
    return {
      ruleId: 'adr-coverage/risk-instrument-ok',
      status: 'pass',
      reason: `${riskId}: instrument '${instrument}' resolves — ${detail}`,
    };
  }
  return {
    ruleId: 'adr-coverage/risk-instrument-unresolved',
    status: 'fail',
    reason:
      `${riskId}: instrument '${instrument}' does not resolve — ${detail}. `
      + `The fix is to build the instrument or amend the risk row in `
      + `docs/reference/architectural-risks.md; it is never to delete the reference. `
      + RISK_INSTRUMENT_INTENT,
  };
}

/**
 * A row with no instrument, or a bare `none`, is the 269 shape: a note nobody reads.
 * A stated reason does not clear the warning — it only makes the warning informative —
 * because an unowned risk with nothing to check is exactly what should stay visible.
 */
export function verdictForRiskInstrumentCoverage({ riskId, instrument, statedReason }) {
  if (instrument && statedReason) {
    return {
      ruleId: 'adr-coverage/risk-no-instrument',
      status: 'info',
      reason:
        `${riskId}: no instrument, reason stated — ${statedReason}. `
        + `Replace 'none - <reason>' with a gate:/check:/test:/metric:/tempdoc: reference `
        + `as soon as one exists.`,
    };
  }
  if (instrument) {
    return {
      ruleId: 'adr-coverage/risk-no-instrument',
      status: 'info',
      reason:
        `${riskId}: a bare 'none' states nothing. Write 'none - <reason>', or name a `
        + `gate:/check:/test:/metric:/tempdoc: reference.`,
    };
  }
  return {
    ruleId: 'adr-coverage/risk-no-instrument',
    status: 'info',
    reason:
      `${riskId}: no '**Instrument:**' field. Every risk row names one machine-checkable `
      + `reference (see docs/reference/architectural-risks.md § Instrument grammar), or `
      + `'none - <reason>'. ${RISK_INSTRUMENT_INTENT}`,
  };
}

/**
 * An absent register is silence (nothing was restored yet); a present-but-broken one is a
 * failure, because a register that will not parse silently disables every instrument in it.
 */
export function verdictForRiskRegister({ registerPath, problem }) {
  if (!problem) {
    return { ruleId: 'adr-coverage/risk-register-ok', status: 'pass', reason: `${registerPath} parsed` };
  }
  return {
    ruleId: 'adr-coverage/risk-register-malformed',
    status: 'fail',
    reason:
      `${registerPath} is structurally broken: ${problem}. A register that does not parse `
      + `checks nothing, which is indistinguishable from having no register at all.`,
  };
}

/**
 * A decision nobody has re-read in six months is the 269 failure mode (one systematic
 * review, ever). Warning at the gate; the session-start hint is PR 2.
 */
export function verdictForReviewStale({ adr, lastReviewed, ageDays, thresholdDays }) {
  if (lastReviewed === null || lastReviewed === undefined) {
    return {
      ruleId: 'adr-coverage/review-stale',
      status: 'info',
      reason: `${adr}: no 'last_reviewed' date. Add 'last_reviewed: YYYY-MM-DD' (${AMENDMENT_PROCEDURE}).`,
    };
  }
  if (ageDays > thresholdDays) {
    return {
      ruleId: 'adr-coverage/review-stale',
      status: 'info',
      reason:
        `${adr}: last reviewed ${lastReviewed} (${ageDays} days ago, threshold ${thresholdDays}). `
        + `Re-examine it (${AMENDMENT_PROCEDURE}) and update 'last_reviewed'.`,
    };
  }
  return { ruleId: 'adr-coverage/review-fresh', status: 'pass', reason: `${adr}: reviewed ${lastReviewed} (${ageDays} days ago)` };
}
