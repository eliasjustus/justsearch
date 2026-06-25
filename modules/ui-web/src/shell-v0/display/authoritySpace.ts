// SPDX-License-Identifier: Apache-2.0
/**
 * authoritySpace — Tempdoc 577 §2.13 #17 / §2.14 Root III.
 *
 * THE projection of the agent's verb-space: each tool's risk tier × the current posture → what the
 * agent will do with it ("runs automatically" vs "asks first" vs "always confirms"). This is the
 * agent-window analogue of Goal-1 Move C (which projected the *result's* verb-space through the same
 * operation catalog); here we project the *agent's* authority so a user can calibrate trust BEFORE
 * delegating, instead of only discovering a gate by tripping it (§2.11 #8 — the ceremony surface
 * becomes reachable by inspection).
 *
 * Honest scope: the AUTHORITATIVE per-call gate is the backend `IntentGateEvaluator.agentGate`
 * (risk × autonomy × reversibility, carried on the wire as `gateBehavior`). This projection is the
 * FE's calibration approximation from the same inputs the dial-derived {@link becauseLine} uses — it
 * tells the user the *policy* ("under Auto, write actions run automatically"), not a per-call verdict.
 */
import type { AutonomyLevel } from '../substrates/autonomy/index.js';
import type { AgentToolInfo } from '../controllers/AgentSessionController.js';

/** What the agent will do with a tool under the current posture. */
export type ToolDisposition = 'auto-runs' | 'asks-first' | 'always-confirms' | 'blocked';

/**
 * Project (tool risk × undo-support × posture) → disposition. Mirrors the backend lattice the
 * dial-derived {@link becauseLine} encodes: HIGH always confirms; Watch confirms everything; Auto
 * auto-runs LOW + reversible MEDIUM (irreversible MEDIUM still confirms); Assist auto-runs LOW only.
 */
export function toolDisposition(
  risk: AgentToolInfo['risk'],
  supportsUndo: boolean | undefined,
  level: AutonomyLevel,
): ToolDisposition {
  const r = risk ?? 'low';
  if (r === 'high') return 'always-confirms';
  if (level === 'watch') return 'asks-first';
  if (level === 'auto') {
    if (r === 'low') return 'auto-runs';
    // MEDIUM under Auto: reversible runs, irreversible still confirms (the C-4 floor).
    return supportsUndo === false ? 'asks-first' : 'auto-runs';
  }
  // assist (default): LOW read-only runs; MEDIUM write confirms.
  return r === 'low' ? 'auto-runs' : 'asks-first';
}

/** Human label for a disposition (the panel's group heading + per-row chip). */
export function dispositionLabel(d: ToolDisposition): string {
  switch (d) {
    case 'auto-runs':
      return 'Runs automatically';
    case 'asks-first':
      return 'Asks you first';
    case 'always-confirms':
      return 'Always confirms';
    case 'blocked':
      return 'Blocked';
  }
}

/** Order for grouping (asks-first / always-confirms surface ABOVE auto-runs — the trust-relevant rows first). */
export const DISPOSITION_ORDER: readonly ToolDisposition[] = [
  'always-confirms',
  'asks-first',
  'auto-runs',
  'blocked',
];
