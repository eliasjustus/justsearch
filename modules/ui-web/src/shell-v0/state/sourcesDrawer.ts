// SPDX-License-Identifier: Apache-2.0
// Tempdoc 565 §3.A — the open-state of the one window's sources pane (the answer's grounding
// passages). A tiny module store mirroring retrospectiveDrawer; the pane reflects `open` and the
// one window toggles it. 574 §23.B — a PURE open-state store; single-drawer arbitration now lives in the
// SourcesPane component (it composes the `right-drawer`-layer TransientController), not here.

let _open = false;
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

export function isSourcesOpen(): boolean {
  return _open;
}

export function setSourcesOpen(open: boolean): void {
  if (_open === open) return;
  _open = open;
  notify();
}

export function toggleSources(): void {
  setSourcesOpen(!_open);
}

export function subscribeSources(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetSourcesDrawer(): void {
  _open = false;
  _subs.clear();
}
