// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 496 §3.A — store module for the Ask (RAG Q&A) chat surface.
 *
 * Participates in the store/snapshot/NavigationHandler system so the Ask
 * surface can receive pre-filled context from other surfaces (e.g.,
 * BrowseSurface "Ask about this" context menu, SearchSurface result action,
 * prompt starters).
 *
 * Three-method contract: serialize / restore / subscribe — same as
 * searchState.ts and searchFiltersState.ts.
 */

export interface AskChatState {
  query: string;
  docIds: string[];
}

let state: AskChatState = { query: '', docIds: [] };
const listeners = new Set<(s: AskChatState) => void>();

export function getAskChatState(): AskChatState {
  return state;
}

export function setAskQuery(query: string): void {
  state = { ...state, query };
  notify();
}

export function setAskDocIds(docIds: string[]): void {
  state = { ...state, docIds };
  notify();
}

export function resetAskChatState(): void {
  state = { query: '', docIds: [] };
  notify();
}

export function serializeAskChat(): { query?: string; docIds?: string[] } {
  const out: { query?: string; docIds?: string[] } = {};
  if (state.query) out.query = state.query;
  if (state.docIds.length > 0) out.docIds = state.docIds;
  return out;
}

export function restoreAskChat(snapshot: {
  query?: string | string[];
  docIds?: string | string[];
}): void {
  const q =
    typeof snapshot.query === 'string'
      ? snapshot.query
      : Array.isArray(snapshot.query)
        ? snapshot.query[0] ?? ''
        : '';
  const ids =
    typeof snapshot.docIds === 'string'
      ? [snapshot.docIds]
      : Array.isArray(snapshot.docIds)
        ? snapshot.docIds
        : [];
  state = { query: q, docIds: ids };
  notify();
}

export function subscribeAskChat(
  listener: (s: AskChatState) => void,
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
