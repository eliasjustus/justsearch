/**
 * ratchet-gate — a generic 530-kernel enforcer factory for the presentation
 * per-file ratchets (tempdoc 574 §25 Phase 4, Edge 4).
 *
 * The DETECTION logic stays in the standalone `scripts/ci/check-*.mjs` (one
 * authority — the CLI and this kernel enforcer call the SAME `detect()`); this
 * factory only adapts a `{ failures: [{ file, message }] }` result into the
 * kernel's `{ findings, verdict, ruleDescriptions }` contract and wires the
 * shared changeset-justification + rebalance machinery (the run-record /
 * SARIF / declared-growth gains the standalone scripts lacked).
 *
 * A ratchet "regression" (a file exceeding its baseline) is a FAIL unless a
 * declared changeset (`declared-growth` / `merge-import` / `emergency-override`)
 * covers it — mirroring the `ts-any` gate (530 §2.5), the per-file-ratchet
 * template.
 */
import { resolve, join } from 'node:path';

import { loadChangesets } from './changeset-loader.mjs';

const COVERING = ['declared-growth', 'merge-import', 'emergency-override'];

export function makeRatchetGate({
  detect,
  rebalanceBaseline,
  srcSubdir,
  toolName,
  rulePrefix,
  ruleDescriptions,
  classifications,
}) {
  return async function enforce(options) {
    const {
      repoRoot,
      gate,
      baselineRef,
      rebalance = false,
      fixtureMode = false,
      fixtureRoot,
    } = options;
    const root = fixtureMode && fixtureRoot ? fixtureRoot : repoRoot;
    const srcRoot = join(root, srcSubdir).replaceAll('\\', '/');
    const baselinePath = resolve(root, gate.baseline?.path ?? '');

    if (rebalance && !fixtureMode) {
      rebalanceBaseline({ srcRoot, baselinePath });
    }

    const { failures } = detect({ srcRoot, baselinePath });

    const decls = gate.changesetsDir
      ? loadChangesets({
          repoRoot: root,
          changesetsDir: gate.changesetsDir,
          baselineRef,
          allowedClassifications: classifications,
          classificationField: 'classification',
          requireJustificationFor: new Set(COVERING),
          fixtureMode,
        })
      : [];
    const growthCovered = decls.some((d) => COVERING.includes(d.classification));
    const coveringCls =
      decls.find((d) => COVERING.includes(d.classification))?.classification ??
      'declared-growth';

    const findings = [];
    let verdict = 'pass';
    for (const f of failures) {
      if (growthCovered) {
        findings.push({
          ruleId: `${rulePrefix}/${coveringCls}`,
          level: 'note',
          message: `${f.message} — '${coveringCls}' covers`,
          uri: f.file,
        });
      } else {
        verdict = 'fail';
        findings.push({
          ruleId: `${rulePrefix}/silent-growth`,
          level: 'error',
          message: f.message,
          uri: f.file,
        });
      }
    }

    return {
      toolName,
      toolVersion: '0.1.0',
      findings,
      verdict,
      ruleDescriptions,
    };
  };
}
