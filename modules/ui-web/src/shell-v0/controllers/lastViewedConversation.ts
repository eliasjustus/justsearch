// SPDX-License-Identifier: Apache-2.0
/**
 * lastViewedConversation — Tempdoc 609 Phase 3 (M3, identity-recoverable).
 *
 * The ONE per-tab pointer to the conversation this tab was last viewing. Returning to the chat
 * surface after navigating away should restore that thread automatically rather than collapsing to
 * the "Continue your last conversation?" card — which keys off the GLOBAL most-recent session
 * (`getRecentSessions()[0]`), not what THIS tab had open, and so can offer the wrong thread (609 §J.2).
 *
 * Scope: per-tab `sessionStorage` is deliberate — "what THIS tab was viewing" (it also persists across
 * a same-tab reload, like the agent run's per-tab reattach). Cross-tab restore is NOT wanted here: a
 * second tab should land cold, not adopt another tab's open thread.
 *
 * Single authority: this module is the only reader/writer of the key (mirrors `activeRunPointer`). A
 * stale pointer (the conversation was deleted) is harmless — `loadConversation` resolves a missing
 * session to an empty thread.
 */
const KEY = 'justsearch.lastViewedConversation.v1';

/** Record that this tab is now viewing `sessionId`. */
export function setLastViewedConversation(sessionId: string): void {
  try {
    if (sessionId) sessionStorage.setItem(KEY, sessionId);
  } catch {
    // sessionStorage unavailable (private mode / SSR / quota) — auto-restore degrades to the
    // resume card; never throw into the chat lifecycle.
  }
}

/** Forget the pointer (explicit New chat / Start fresh). */
export function clearLastViewedConversation(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // ignore — see setLastViewedConversation.
  }
}

/** The conversation this tab should auto-restore on mount, or null for a cold start. */
export function readLastViewedConversation(): string | null {
  try {
    const v = sessionStorage.getItem(KEY);
    return v && v.length > 0 ? v : null;
  } catch {
    return null;
  }
}
