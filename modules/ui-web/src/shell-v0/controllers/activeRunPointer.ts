// SPDX-License-Identifier: Apache-2.0
/**
 * activeRunPointer — Tempdoc 577 §2.14 Root I (#1d) — the ONE cross-tab pointer to the live agent run.
 *
 * The run is a backend-owned, REATTACHABLE entity (the session-local `RunEventHub` + the
 * `POST /api/chat/agent/{sessionId}/attach` endpoint serve N observers). Per-tab `sessionStorage`
 * lets the SAME tab reattach across a reload, but it cannot cross tabs. This pointer is the FE half
 * that lets a SECOND tab DISCOVER a run started in another tab: the originating tab writes the live
 * run's sessionId to `localStorage` (shared across same-origin tabs) on start and clears it on
 * terminal; a freshly-loaded tab reads it and reattaches.
 *
 * Single authority: this module is the ONLY reader/writer of the pointer key — controllers call these
 * helpers, they never touch `localStorage` directly. A stale pointer (the owning tab closed before the
 * terminal cleared it) is HARMLESS: `attachToRun` resolves a no-longer-live run via the backend's
 * `attach_not_live` signal and falls back to the persisted thread record.
 */
const KEY = 'justsearch.activeAgentRun.v1';

export interface ActiveRunPointer {
  readonly sessionId: string;
  /** Only the steerable AGENT run is cross-tab reattachable here (workflow/background are not). */
  readonly runKind: 'agent';
  /**
   * The chat conversation this run belongs to (when known). A reattaching tab pinned to a DIFFERENT
   * conversation must not adopt this run — see `AgentSessionController.reattachActiveRunOnLoad`.
   */
  readonly conversationId?: string;
}

/** Record that an agent run is live (called on `session_started`). */
export function setActiveRun(sessionId: string, conversationId?: string | null): void {
  try {
    const pointer: ActiveRunPointer = conversationId
      ? { sessionId, runKind: 'agent', conversationId }
      : { sessionId, runKind: 'agent' };
    localStorage.setItem(KEY, JSON.stringify(pointer));
  } catch {
    // localStorage unavailable (private mode / SSR / quota) — cross-tab discovery degrades to the
    // per-tab reattach; never throw into the run lifecycle.
  }
}

/** Clear the pointer when the run reaches a terminal (done / error / not-live). */
export function clearActiveRun(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    // ignore — see setActiveRun.
  }
}

/** Read the live-run pointer a freshly-loaded tab should reattach to, or null. */
export function readActiveRun(): ActiveRunPointer | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<ActiveRunPointer>;
    if (typeof parsed.sessionId === 'string' && parsed.sessionId.length > 0 && parsed.runKind === 'agent') {
      return typeof parsed.conversationId === 'string' && parsed.conversationId.length > 0
        ? { sessionId: parsed.sessionId, runKind: 'agent', conversationId: parsed.conversationId }
        : { sessionId: parsed.sessionId, runKind: 'agent' };
    }
    return null;
  } catch {
    return null;
  }
}
