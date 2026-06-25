// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 496 §3.A — store module for the Agent chat surface.
 *
 * Receives pre-filled context (initialMessage/sessionId) from other
 * surfaces via the store/snapshot/NavigationHandler system.
 */

export interface AgentChatState {
  initialMessage: string;
  sessionId: string;
}

let state: AgentChatState = { initialMessage: '', sessionId: '' };
const listeners = new Set<(s: AgentChatState) => void>();

export function getAgentChatState(): AgentChatState {
  return state;
}

export function resetAgentChatState(): void {
  state = { initialMessage: '', sessionId: '' };
  notify();
}

export function serializeAgentChat(): Record<string, string> {
  const out: Record<string, string> = {};
  if (state.initialMessage) out.initialMessage = state.initialMessage;
  if (state.sessionId) out.sessionId = state.sessionId;
  return out;
}

export function restoreAgentChat(snapshot: {
  initialMessage?: string | string[];
  sessionId?: string | string[];
}): void {
  const msg =
    typeof snapshot.initialMessage === 'string'
      ? snapshot.initialMessage
      : '';
  const sid =
    typeof snapshot.sessionId === 'string' ? snapshot.sessionId : '';
  state = { initialMessage: msg, sessionId: sid };
  notify();
}

export function subscribeAgentChat(
  listener: (s: AgentChatState) => void,
): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function notify(): void {
  for (const l of listeners) {
    try {
      l(state);
    } catch {
      // swallow
    }
  }
}
