// SPDX-License-Identifier: Apache-2.0
// Tempdoc 561 C-2 (the graded continuum) — the §2.1 phase transition expressed as ONE ordered
// scalar. The interaction is a continuum across autonomy: the answer plane (oracle) is posture 0;
// crossing into the agent (actor) raises posture with the autonomy dial. The DISPATCH stays binary
// (affordance === 'agent' routes to the agent loop); only the CHROME grades on this scalar — the
// composer language, the Activity-rail approval posture, and the visible supervision dial. It is
// derived per render from (affordance × dial), never stored and never written to the record.
import type { Affordance } from './unifiedChatState.js';
import type { AutonomyLevel } from '../substrates/autonomy/index.js';

/** 0 = oracle (answer plane); 1/2/3 = agent under WATCH/ASSIST/AUTO (rising autonomy). */
export type AgencyPosture = 0 | 1 | 2 | 3;

/**
 * The agency posture for a render: the affordance is the coarse axis (oracle vs actor) and the
 * autonomy dial the fine axis (how much you supervise the actor). One monotonic scalar.
 */
export function agencyPosture(affordance: Affordance, level: AutonomyLevel): AgencyPosture {
  if (affordance !== 'agent') return 0;
  switch (level) {
    case 'watch':
      return 1;
    case 'assist':
      return 2;
    case 'auto':
      return 3;
  }
}

/** The chrome copy a posture grades — composer language + the rail's approval-posture header. */
export interface PostureChrome {
  /** Composer placeholder (empty at posture 0 — the answer plane keeps its own affordance copy). */
  readonly placeholder: string;
  /** Send-button label. */
  readonly sendLabel: string;
  /** The Activity-rail header naming the approval posture (empty at posture 0). */
  readonly approvalPosture: string;
}

/**
 * Posture → graded chrome copy. The posture-3 line truthfully reflects the C-4 floor (irreversible
 * writes still confirm even in Auto), so the chrome never over-promises full automation.
 */
export function postureChrome(p: AgencyPosture): PostureChrome {
  switch (p) {
    case 1:
      return {
        placeholder: 'Ask the agent — you approve each step…',
        sendLabel: 'Send for review',
        approvalPosture: 'Reviewing every step',
      };
    case 2:
      return {
        placeholder: 'Ask the agent to act — writes need your OK…',
        sendLabel: 'Send',
        approvalPosture: 'Auto-running reads · confirming writes',
      };
    case 3:
      return {
        placeholder: 'Ask the agent — it runs automatically…',
        sendLabel: 'Send & auto-run',
        approvalPosture: 'Auto-running · confirming irreversible writes',
      };
    case 0:
    default:
      return { placeholder: '', sendLabel: 'Send', approvalPosture: '' };
  }
}
