// SPDX-License-Identifier: Apache-2.0
// Tempdoc 610 §K — the open-state of the context-inspector drawer ("what the last turn saw"). A tiny
// pure open-state store mirroring `sourcesDrawer` (574 §23.B); single-drawer arbitration lives in the
// ContextInspectorPane component (it composes the `right-drawer`-layer TransientController), not here.

import type { InspectorView } from '../components/ContextInspectorPane.js';

let _open = false;
let _view: InspectorView | null = null;
const _subs = new Set<() => void>();

function notify(): void {
  for (const s of _subs) {
    try {
      s();
    } catch {
      /* swallow */
    }
  }
}

export function isContextInspectorOpen(): boolean {
  return _open;
}

export function setContextInspectorOpen(open: boolean): void {
  if (_open === open) return;
  _open = open;
  notify();
}

export function toggleContextInspector(): void {
  setContextInspectorOpen(!_open);
}

/** The whole-prompt projection the (shell-mounted) inspector pane renders. UnifiedChatView — which holds
 * the thread/floor/breakdown — pushes it here; the pane reads it (mirrors SourcesPane ← agentSessionStore). */
export function getContextInspectorView(): InspectorView | null {
  return _view;
}

export function setContextInspectorView(view: InspectorView | null): void {
  _view = view;
  notify();
}

export function subscribeContextInspector(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetContextInspectorDrawer(): void {
  _open = false;
  _view = null;
  _subs.clear();
}
