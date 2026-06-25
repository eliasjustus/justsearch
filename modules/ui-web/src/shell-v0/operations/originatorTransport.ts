// SPDX-License-Identifier: Apache-2.0
import type { EffectOriginator } from '../substrates/effects/index.js';

/**
 * §32 S2 — map an Effect `originator` to the backend `TransportTag` the
 * (SourceTier × RiskTier) trust lattice gates on.
 *
 *   agent  → AGENT_LOOP      (UNTRUSTED — an agent tool-call from the agent
 *                             loop; write/destructive ops require TYPED_CONFIRM)
 *   system → SYSTEM_INTERNAL (TRUSTED — backend self-initiated)
 *   user   → BUTTON          (TRUSTED — direct user gesture)
 *   (absent → BUTTON, preserving the pre-bridge default)
 *
 * Extracted from the Shell `jf-invoke-operation` listener so the mapping is
 * unit-testable (the listener closure itself is not) — closes the S2 review's
 * audit-without-test follow-up. `AGENT_LOOP` (not `LLM_EMISSION`) is the
 * catalog's reserved transport for agent tool-calls; both resolve to
 * SourceTier.UNTRUSTED, so the gate outcome is identical, but AGENT_LOOP is
 * the semantically-correct provenance for an operation dispatch.
 */
export function originatorToTransport(originator?: EffectOriginator): string {
  switch (originator) {
    case 'agent':
      return 'AGENT_LOOP';
    case 'system':
      return 'SYSTEM_INTERNAL';
    default:
      return 'BUTTON';
  }
}
