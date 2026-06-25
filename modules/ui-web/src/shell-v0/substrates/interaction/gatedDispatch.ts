// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Fix B / Move 5+8 — route a user-authored statechart's effects through the SAME 550 trust
 * seam as `invokeAndApply` (verdict-at-the-seam): the Autonomy Dial decides *propose* vs *dispatch*
 * per effect kind, and an **agent-originated** effect the dial wants reviewed goes to the
 * PendingEffect queue (propose → review) instead of firing. So a user/agent interaction statechart
 * **cannot escalate privilege** — its effects pass the identical gate as every other invocation;
 * it composes the team's actions, it does not author them. A **user-origin** effect (the user
 * driving a real surface flow) dispatches directly and is journaled, exactly as clicking a control.
 *
 * This is the explicit seam Move 8 specifies; previously the machine called raw `applyEffect`, which
 * bypasses the dial (the gate lives in `invokeAndApply`, not in `applyEffect`).
 */
import type { Effect, EffectDispatcher } from '../effect.js';
import type { EffectOriginator } from '../effects/index.js';
import { applyEffect } from '../actions/index.js';
import { proposeEffect } from '../pending-effects/index.js';
import { agentInvocationDisposition } from '../autonomy/index.js';
import { CORE_PROVENANCE, type Provenance } from '../../primitives/provenance.js';

/**
 * Build an {@link EffectDispatcher} that gates each transition effect through the 550 Autonomy Dial.
 * - `originator: 'agent'` + dial says *propose* → {@link proposeEffect} (queued for review; not fired).
 * - otherwise → {@link applyEffect} (dispatched + journaled).
 */
export function createGatedEffectDispatcher(
  invokedBy: Provenance = CORE_PROVENANCE,
  originator: EffectOriginator = 'user',
): EffectDispatcher {
  return (effect: Effect): unknown => {
    if (originator === 'agent' && agentInvocationDisposition(effect.kind) === 'propose') {
      return proposeEffect(effect, invokedBy, 'agent', {
        rationale: `Statechart: ${effect.kind}`,
      });
    }
    return applyEffect(effect, invokedBy, originator);
  };
}
