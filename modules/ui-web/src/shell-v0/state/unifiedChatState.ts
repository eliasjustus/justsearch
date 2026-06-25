// SPDX-License-Identifier: Apache-2.0
// Tempdoc 561 P-B3 (one window): 'agent' is the action-plane affordance — the §2.1 phase
// transition where the one window crosses from oracle (answer plane: chat/ask/extract) to actor
// (the agent loop). Selecting it hosts the agent experience within this single window rather than
// routing to a separate surface (resolving 497's 4→2 gap by composition, not a second window).
// Tempdoc 577 Goal 3 (§3.2/§3.3): 'retrieve' is the BASE intent tier — pure search (the
// ephemeral hit-list), reusing the FE `searchState` store; NOT an LLM conversation shape
// (§3.3 correction). The one window's input escalates retrieve → documents (Ask, grounded)
// → agent (Delegate, run). retrieve is the entry tier the retired `core.search-surface` folds into.
export type Affordance = 'none' | 'retrieve' | 'documents' | 'extract' | 'agent';

// Slice 514 introduced an optional `kind` discriminant here, intending it to
// drive per-ConversationShape routing. Slice 515 FIX-5 removes it: no
// consumer materialised, and the current askAi kinds don't differentiate on
// shape selection (affordance + docIds already encode the routing). The
// AskAiIntent union remains as a call-site type contract in askAi.ts. If a
// kind that genuinely needs a different ConversationShape is added, the
// discriminant can be re-introduced with a named consumer.

/**
 * Tempdoc 526 §14.5 T7 — `docIds` field removed. The substrate
 * (selectionState.result-set kind) owns multi-doc context now. `query` and
 * `affordance` survive as UI mode + prefilled input, neither of which is a
 * "selection."
 *
 * URL restoration of `?docIds=...` still happens at the bootstrap layer
 * (see `buildSelectionAdapter` in `router/bootstrap.ts`), which publishes
 * the restored ids into selectionState as a `result-set` SelectionItem.
 */
export interface UnifiedChatState {
  query: string;
  affordance: Affordance;
}

let state: UnifiedChatState = { query: '', affordance: 'none' };
const listeners = new Set<(s: UnifiedChatState) => void>();

export function getUnifiedChatState(): UnifiedChatState {
  return state;
}

export function resetUnifiedChatState(): void {
  state = { query: '', affordance: 'none' };
  notify();
}

export function serializeUnifiedChat(): {
  query?: string;
  affordance?: string;
} {
  const out: { query?: string; affordance?: string } = {};
  if (state.query) out.query = state.query;
  if (state.affordance !== 'none') out.affordance = state.affordance;
  return out;
}

export function restoreUnifiedChat(snapshot: {
  query?: string | string[];
  affordance?: string | string[];
}): void {
  const q =
    typeof snapshot.query === 'string'
      ? snapshot.query
      : Array.isArray(snapshot.query)
        ? snapshot.query[0] ?? ''
        : '';
  const aff = typeof snapshot.affordance === 'string' ? snapshot.affordance : 'none';
  state = {
    query: q,
    affordance:
      aff === 'documents' || aff === 'extract' || aff === 'agent' ? aff : 'none',
  };
  notify();
}

export function subscribeUnifiedChat(
  listener: (s: UnifiedChatState) => void,
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
      // swallow listener errors
    }
  }
}
