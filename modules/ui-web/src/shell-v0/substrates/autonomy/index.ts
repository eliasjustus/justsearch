// SPDX-License-Identifier: Apache-2.0
/**
 * Autonomy substrate — Tempdoc 543 §32 U1 (Autonomy Dial).
 *
 * A persisted per-user setting controlling how much the agent acts on its
 * own. It is NOT an accept-count learner — §32.9 corrected that sketch as
 * unsafe (OWASP "treat the LLM as an untrusted client"). It is a FE surface
 * OVER the backend (SourceTier × RiskTier) trust lattice. The lattice (the
 * §32 S2 bridge) ALWAYS gates write/destructive agent ops backend-side
 * regardless of this dial; the dial only controls the FE's proactive routing
 * of agent invocations:
 *
 *   watch  → EVERY agent effect is PROPOSED to the PendingEffect queue for
 *            explicit approval (maximum oversight — even agent navigations).
 *   assist → agent BACKEND operations (invoke-operation effects) are proposed
 *            for one-click approval; pure-FE agent effects (navigate, toast,
 *            …) dispatch. Accepting a proposal re-dispatches it as a USER
 *            action (BUTTON transport, via applyEffect's default originator),
 *            so the backend auto-runs LOW/MEDIUM and STILL typed-confirms
 *            HIGH/destructive — the user's approval is the confirmation, no
 *            token round-trip needed. (default)
 *   auto   → every agent effect dispatches; the FE adds no proactive queueing.
 *            The backend lattice is the sole gate (auto-runs LOW agent ops,
 *            428s MEDIUM+).
 *
 * The FE never classifies risk (the backend stays the authority, §32.9); the
 * dial only distinguishes "calls the backend" (invoke-operation) from
 * "pure FE". Safety invariant: lowering oversight here NEVER lets a
 * destructive agent op auto-fire — watch/assist queue it (approval →
 * user-attributed re-dispatch, which the backend still typed-confirms for
 * HIGH), and auto lets the backend 428 it (TRUSTED×HIGH and UNTRUSTED×HIGH
 * both = TYPED_CONFIRM).
 *
 * SCOPE (source-verified — AgentLoopService.java:1142-1145,1261). The dial gates
 * agent invocations that flow through `invokeAndApply` with originator='agent'.
 * The agent loop routes a tool-call by name: `vop_`-prefixed (FE-VIRTUAL) calls
 * → `handleVirtualToolCall` → `tool_call_virtual` SSE → the FE
 * `VirtualToolDispatcher` → `invokeCommandWithResult({originator:'agent'})` →
 * `invokeAndApply(...,'agent')`, i.e. THIS gate. EVERYTHING ELSE — the core
 * agent ops (`core_search_index`, `core_ingest_files`, `core_file_operations`,
 * `core_browse_folders`) — runs SERVER-SIDE via `executeOperationWithPolicy`
 * (OperationDispatcher → BackendIntentRouter → trust lattice) and NEVER reaches
 * this gate. So the dial gates the FE-virtual (`vop_`) agent path only; the
 * backend trust lattice (transport=AGENT_LOOP → UNTRUSTED) is what gates the
 * agent's core ops, and is the universal backstop.
 *
 * Practical consequence (be honest): there are currently NO `vop_` operations
 * published to the agent (only plugin/shell commands decorated via
 * `VirtualOperationCatalog` become `vop_` tools, and none exist by default).
 * So today this dial gates no real agent traffic — it is forward-looking
 * infrastructure for the FE-virtual agent path. `watch/assist/auto` are
 * genuinely distinct in code + tests; they only fire once a `vop_` agent tool
 * exists (or a handler explicitly `proposeEffect`s an agent invocation).
 */

import { safeLocalStorage } from '../../primitives/storage.js';
import { notifyAll } from '../../primitives/notify.js';

export type AutonomyLevel = 'watch' | 'assist' | 'auto';

const LEVELS: readonly AutonomyLevel[] = ['watch', 'assist', 'auto'];
const KEY = 'justsearch.autonomy.level.v1';
const DEFAULT_LEVEL: AutonomyLevel = 'assist';

function readPersisted(): AutonomyLevel {
  const raw = safeLocalStorage()?.getItem(KEY);
  return raw && (LEVELS as readonly string[]).includes(raw)
    ? (raw as AutonomyLevel)
    : DEFAULT_LEVEL;
}

let _level: AutonomyLevel = readPersisted();
const _listeners = new Set<() => void>();

export function getAutonomyLevel(): AutonomyLevel {
  return _level;
}

export function setAutonomyLevel(level: AutonomyLevel): void {
  if (!LEVELS.includes(level) || level === _level) return;
  _level = level;
  safeLocalStorage()?.setItem(KEY, level);
  notifyAll(_listeners);
}

export function listAutonomyLevels(): readonly AutonomyLevel[] {
  return LEVELS;
}

export function subscribeAutonomy(listener: () => void): () => void {
  _listeners.add(listener);
  return () => {
    _listeners.delete(listener);
  };
}

/**
 * Disposition for an agent invocation of `effectKind` under a given level:
 * `propose` (route to the PendingEffect queue for approval) or `dispatch`
 * (apply now; the backend lattice remains the write/destructive gate).
 *
 * The three levels are genuinely distinct, keyed on whether the agent effect
 * calls the backend (`invoke-operation`) or is a pure-FE effect:
 *   - watch  → propose EVERYTHING.
 *   - assist → propose backend operations; dispatch pure-FE effects.
 *   - auto   → dispatch EVERYTHING (the backend lattice is the sole gate).
 *
 * Risk is never classified here — assist proposes ALL backend operations (the
 * backend then decides AUTO vs CONFIRM); the FE only distinguishes
 * backend-touching from pure-FE. See the module header for the safety
 * invariant.
 */
export function agentInvocationDisposition(
  effectKind: string,
  level: AutonomyLevel = _level,
): 'propose' | 'dispatch' {
  if (level === 'watch') return 'propose';
  if (level === 'auto') return 'dispatch';
  // assist: propose backend operations for approval; dispatch pure-FE effects.
  return effectKind === 'invoke-operation' ? 'propose' : 'dispatch';
}

// Tempdoc 561 P-D (autonomy-policy collapse): the former `agentToolAutoApprove(risk, level)` — the
// FE's parallel auto-approval authority that re-derived the decision from `risk` — is REMOVED. The
// backend issuance policy (`IntentGateEvaluator.agentGate`, which folds in the dial sent on the run
// request + the `/autonomy` endpoint) is now the SOLE decider; `AgentSessionController` auto-approves
// iff the wire `gateBehavior` is `auto`. The dial here is a preference communicated to the backend,
// not a second decision point.

/** Tempdoc 561 P-D1: the backend trust-lattice verdict carried on the wire (lowercased). */
export type BackendGate = 'auto' | 'inline_confirm' | 'typed_confirm' | 'deny';

/**
 * 543-fwd #2 (because-line) — a deterministic, plain-text explanation of WHY
 * an agent tool-call is gated the way it is.
 *
 * Tempdoc 561 P-D1: when the backend supplies its authoritative `gateBehavior` on the wire, the
 * explanation reflects THAT decision (the single authority) rather than re-deriving from `risk` +
 * dial — collapsing the second-authority the 543 U3 note called out ("the real tool-call wire shape
 * carries `risk` but no reason/confidence, so this is derived FE-side"). Only when the verdict is
 * absent (legacy/test wiring) does it fall back to the dial-derived sentence below.
 */
export function becauseLine(
  risk: 'LOW' | 'MEDIUM' | 'HIGH',
  level: AutonomyLevel = _level,
  backendGate?: BackendGate,
): string {
  // Backend authority wins when present — the explanation names the real gate decision.
  if (backendGate) {
    switch (backendGate) {
      case 'auto':
        return 'The system will run this automatically (low-risk, trusted).';
      case 'inline_confirm':
        return 'Needs a quick confirmation before it runs.';
      case 'typed_confirm':
        return 'Higher-risk action — needs your typed confirmation.';
      case 'deny':
        return 'Blocked by policy — this action cannot run.';
    }
  }
  if (risk === 'HIGH') {
    return 'HIGH-risk action — always needs your confirmation.';
  }
  if (level === 'watch') {
    return 'Watch mode — every action needs your confirmation.';
  }
  if (level === 'auto') {
    return `Auto mode — ${risk}-risk actions run automatically.`;
  }
  // assist (default): LOW auto-approves, MEDIUM is confirmed.
  return risk === 'LOW'
    ? 'Assist mode — read-only (LOW) actions run automatically.'
    : 'Assist mode — write (MEDIUM) actions need your confirmation.';
}

/** Test-only reset. */
export function __resetAutonomyForTest(): void {
  _level = DEFAULT_LEVEL;
  _listeners.clear();
  safeLocalStorage()?.removeItem(KEY);
}
