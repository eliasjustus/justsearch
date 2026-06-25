/**
 * baseline-tamper-detector — the shared home of the "no silent downgrade" invariant
 * (tempdoc 576 §5, generalizing tempdoc 530 §Layer 1).
 *
 * Every ratchet gate independently re-implemented the same decision: when a baseline value is
 * RELAXED (a per-file pin raised, a per-metric baseline/hard_cap raised, a floor lowered), it is
 * either COVERED by a classified changeset → emit a `declared-*` note, or SILENT → emit a `*-shift`
 * / `*-bump` error AND fail the gate. Centralizing that decision means the invariant lives in ONE
 * place (and the exception-count meta-ratchet + any future gate reuse it), while each gate keeps
 * ownership of HOW it detects a relaxation and the exact wording of its finding.
 *
 * Each gate detects its own relaxation events and passes them here; the detector owns only the
 * covered → note vs silent → error+fail dispatch, so gate ruleIds and messages are preserved
 * byte-for-byte.
 *
 * @param {Array<{
 *   raised: boolean,            // is this a relaxation (baseline weakened)? non-raised events are ignored
 *   covered: boolean,           // does a classified changeset cover it?
 *   silentRuleId: string,       // ruleId for the uncovered (failing) case, e.g. 'class-size/silent-pin-bump'
 *   silentMessage: string,      // exact message for the uncovered case
 *   declaredRuleId: string,     // ruleId for the covered (note) case, e.g. 'class-size/declared-pin-bump'
 *   declaredMessage: string,    // exact message for the covered case
 *   uri?: string,
 * }>} events
 * @returns {{ findings: Array<{ruleId: string, level: 'error'|'note', message: string, uri?: string}>, fail: boolean }}
 */
export function detectBaselineTamper(events) {
  const findings = [];
  let fail = false;
  for (const e of events) {
    if (!e || !e.raised) continue;
    if (e.covered) {
      findings.push({ ruleId: e.declaredRuleId, level: 'note', message: e.declaredMessage, uri: e.uri });
    } else {
      fail = true;
      findings.push({ ruleId: e.silentRuleId, level: 'error', message: e.silentMessage, uri: e.uri });
    }
  }
  return { findings, fail };
}
