// SPDX-License-Identifier: Apache-2.0
/**
 * inspectorState — Lit-side pub-sub for the Inspector pane (slice 462).
 *
 * Owns: currently-selected docId, active tab, AI Q&A streaming state.
 * Surfaces (search results, browse rows) call `setSelected()`; the
 * Inspector subscribes and fetches the preview / context on demand.
 *
 * Tempdoc 508 §11.2 / §13.2 — the single-item Selected* state here
 * is now a derived view over selectionState (the first-class
 * multi-select-aware substrate). `setSelected` is preserved as the
 * public API for existing surface callers; under the hood it
 * publishes through selectionState so the ShellContext / when-
 * evaluator / context-menu pipeline see the selection too.
 */

import {
  setSingleSelection,
  clearSelection,
  DEFAULT_CAPABILITIES_BY_KIND,
  type SelectionItem,
} from './selectionState.js';
import type {
  AgentSource,
  AgentSentenceCite,
} from '../../api/generated/shape-handlers/shared.js';

export type InspectorTab = 'preview' | 'context' | 'answer' | 'input';

export interface SelectedItem {
  id: string;
  title: string;
  path: string;
  kind?: string;
  size?: number;
  modifiedAt?: number;
}

export interface AiState {
  loading: boolean;
  text: string;
  error: string | null;
  /** Tempdoc 577 Phase 1 (Move F) — the answer's grounding sources from the agent `done` event
   *  (clickable local passages). Optional so the plugin-facing InspectorStateView stays unchanged. */
  sources?: AgentSource[];
  /** Tempdoc 577 Phase 1 — the per-sentence cites resolved against `sources` for the inline [n] weave. */
  citations?: AgentSentenceCite[];
}

export interface InspectorState {
  isOpen: boolean;
  selected: SelectedItem | null;
  activeTab: InspectorTab;
  ai: AiState;
}

let state: InspectorState = {
  isOpen: false,
  selected: null,
  activeTab: 'preview',
  ai: { loading: false, text: '', error: null },
};

const listeners = new Set<(s: InspectorState) => void>();

export function getInspectorState(): InspectorState {
  return state;
}

export function setInspectorState(patch: Partial<InspectorState>): void {
  state = { ...state, ...patch };
  for (const l of listeners) l(state);
}

export function subscribeInspector(listener: (s: InspectorState) => void): () => void {
  listeners.add(listener);
  listener(state);
  return () => listeners.delete(listener);
}

export function setSelected(item: SelectedItem | null): void {
  setInspectorState({
    selected: item,
    isOpen: item !== null ? true : state.isOpen,
    ai: { loading: false, text: '', error: null },
  });
  // §13.2 bridge: publish to selectionState so the ShellContext +
  // when-evaluator + context-action filtering see the selection.
  // §13 critical-analysis A2: read kind from SelectedItem.kind
  // (optional field) so non-search callers (BrowseSurface, plugin
  // showInspector) carry the correct discriminator. Default is
  // 'search-hit' for back-compat with the legacy single-kind shape.
  if (item === null) {
    clearSelection();
    return;
  }
  setSingleSelection(buildSelectionItem(item), null);
}

/**
 * Build a discriminated SelectionItem from the legacy SelectedItem
 * shape. The optional `kind` field determines the union variant.
 * §13.2: 'search-hit' | 'browse-node' | 'plugin-item' are the only
 * shipped kinds; unknown values fall back to 'search-hit' to keep
 * the bridge total. Capabilities default to the per-kind defaults.
 */
function buildSelectionItem(item: SelectedItem): SelectionItem {
  const kind = (item.kind ?? 'search-hit') as SelectionItem['kind'];
  switch (kind) {
    case 'browse-node':
      return {
        kind: 'browse-node',
        path: item.path,
        nodeKind: 'file',
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['browse-node'],
      };
    case 'plugin-item':
      return {
        kind: 'plugin-item',
        pluginId: 'unknown',
        itemId: item.id,
        label: item.title,
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['plugin-item'],
      };
    case 'search-hit':
    default:
      return {
        kind: 'search-hit',
        hitId: item.id,
        title: item.title,
        path: item.path,
        capabilities: DEFAULT_CAPABILITIES_BY_KIND['search-hit'],
      };
  }
}

export function setActiveTab(tab: InspectorTab): void {
  setInspectorState({ activeTab: tab });
}

export function setOpen(open: boolean): void {
  setInspectorState({ isOpen: open });
}

/**
 * Tempdoc 508-followup §β4 — reset the inspector as part of a profile
 * switch. Clears any selected item (which also clears the underlying
 * selectionState), closes the pane, and resets the active tab to
 * 'preview' so a fresh open lands on the canonical default.
 */
export function resetInspectorState(): void {
  setSelected(null);
  setInspectorState({
    isOpen: false,
    activeTab: 'preview',
  });
}
