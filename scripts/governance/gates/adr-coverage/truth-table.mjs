/** adr-coverage truth-table — tempdoc 530 §2.7. Verdict logic inlined in enforcer.mjs. */
export function verdictForAdrGlob({ adr, glob, matches }) {
  if (matches.length > 0) {
    return { ruleId: 'adr-coverage/all-paths-resolve', status: 'pass', reason: `${adr}: glob '${glob}' resolves to ${matches.length} file(s)` };
  }
  return { ruleId: 'adr-coverage/stale-coverage', status: 'fail', reason: `${adr}: glob '${glob}' matches no file` };
}
