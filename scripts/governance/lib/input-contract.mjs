/**
 * Gate input-contract evaluation - tempdoc 742 D1.
 *
 * Required report artifacts are part of a gate's contract, enforced by the
 * RUNNER (not each enforcer). Historically every report-consuming enforcer
 * independently treated a missing report as warn-and-pass, which made the
 * `dead-code` (Knip) gate silently inert for its whole life: the required
 * `tmp/knip-report.json` was produced by nothing, so the gate always passed
 * vacuously. Vacuous green is a failure state - an enforcement mechanism whose
 * precondition is absent must fail loudly, not degrade to pass.
 *
 * A gate declares its inputs under `config.inputs`, each entry:
 *   { "path": "tmp/...", "producer": "<one-command remedy>", "class": "required" | "on-demand" }
 *
 * - `required` absent  -> the runner fails the gate WITHOUT dispatching the
 *   enforcer (verdict 'fail', ruleId kernel/input-missing).
 * - `on-demand` absent -> the runner SKIPS the gate WITHOUT dispatching the
 *   enforcer (verdict 'skipped', ruleId kernel/input-skipped). 'skipped' is not
 *   a fail; it does not gate the build.
 */

import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

export const INPUT_MISSING_RULE = 'kernel/input-missing';
export const INPUT_SKIPPED_RULE = 'kernel/input-skipped';

export const KERNEL_INPUT_RULE_DESCRIPTIONS = {
  [INPUT_MISSING_RULE]:
    'A required gate input artifact is absent; the gate cannot be evaluated. Produce it with the declared producer command (config.inputs[].producer).',
  [INPUT_SKIPPED_RULE]:
    'An on-demand gate input artifact is absent; the gate was skipped. Run the declared producer to evaluate this gate.',
};

const TOOL = { toolName: 'justsearch-gate-input-contract', toolVersion: '0.1.0' };

function declaredInputs(gate) {
  return Array.isArray(gate?.config?.inputs) ? gate.config.inputs : [];
}

/**
 * Evaluate a gate's declared input contract.
 *
 * @returns {null | { toolName, toolVersion, findings, verdict, ruleDescriptions }}
 *   `null` when all required inputs are present and no on-demand input is
 *   absent (the enforcer should be dispatched normally). Otherwise a synthetic
 *   enforcer-shaped result describing why the enforcer was NOT dispatched.
 */
export function evaluateGateInputs({ gate, repoRoot, fileExists = existsSync }) {
  const missingRequired = [];
  const missingOnDemand = [];
  for (const input of declaredInputs(gate)) {
    if (!input || !input.path) continue;
    if (fileExists(resolve(repoRoot, input.path))) continue;
    if (input.class === 'required') missingRequired.push(input);
    else if (input.class === 'on-demand') missingOnDemand.push(input);
  }

  if (missingRequired.length > 0) {
    return {
      ...TOOL,
      findings: missingRequired.map((i) => ({
        ruleId: INPUT_MISSING_RULE,
        level: 'error',
        message: `${i.path} missing - produce it with: ${i.producer}`,
        uri: i.path,
      })),
      verdict: 'fail',
      ruleDescriptions: KERNEL_INPUT_RULE_DESCRIPTIONS,
    };
  }

  if (missingOnDemand.length > 0) {
    return {
      ...TOOL,
      findings: missingOnDemand.map((i) => ({
        ruleId: INPUT_SKIPPED_RULE,
        level: 'note',
        message: `${i.path} absent; on-demand input - run ${i.producer} to evaluate this gate`,
        uri: i.path,
      })),
      verdict: 'skipped',
      ruleDescriptions: KERNEL_INPUT_RULE_DESCRIPTIONS,
    };
  }

  return null;
}

/**
 * The `required` inputs of a gate that are currently absent - the set
 * `--produce-inputs` runs producers for. On-demand inputs are never
 * auto-produced.
 */
export function requiredInputsToProduce({ gate, repoRoot, fileExists = existsSync }) {
  return declaredInputs(gate).filter(
    (i) => i && i.path && i.class === 'required' && !fileExists(resolve(repoRoot, i.path)),
  );
}
