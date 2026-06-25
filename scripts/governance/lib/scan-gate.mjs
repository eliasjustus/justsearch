/**
 * scan-gate — a generic 530-kernel enforcer factory for the presentation
 * POSITIVE-COVERAGE / whole-tree SCAN gates (tempdoc 574 §25 Phase 4, Edge 4):
 * transient-arbitration, modal-arbitration, modality-contract, ambient-purity.
 *
 * Unlike the per-file ratchets these carry NO baseline — a violation is always a
 * FAIL (the contract is enforced by construction; there is no shrinking tail).
 * Detection stays in the standalone `scripts/ci/check-*.mjs` (one authority — the
 * CLI and this enforcer call the same `detect({ root })`); this factory only
 * adapts a `{ violations: [{ file, message, rule? }] }` result into the kernel's
 * `{ findings, verdict, ruleDescriptions }` contract. The kernel gains the
 * run-record (a missing gate-run becomes detectable) + SARIF.
 *
 * `detect` is rooted: in `fixtureMode` it scans `fixtureRoot` (so the self-test
 * fixtures provide their own register/tree/authority), otherwise `repoRoot`.
 */
export function makeScanGate({
  detect,
  toolName,
  rulePrefix,
  ruleDescriptions,
  defaultRule = 'violation',
}) {
  return async function enforce(options) {
    const { repoRoot, fixtureMode = false, fixtureRoot } = options;
    const root = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
    const { violations } = detect({ root });
    const findings = violations.map((v) => ({
      ruleId: `${rulePrefix}/${v.rule ?? defaultRule}`,
      level: 'error',
      message: v.message,
      uri: v.file,
    }));
    return {
      toolName,
      toolVersion: '0.1.0',
      findings,
      verdict: violations.length ? 'fail' : 'pass',
      ruleDescriptions,
    };
  };
}
