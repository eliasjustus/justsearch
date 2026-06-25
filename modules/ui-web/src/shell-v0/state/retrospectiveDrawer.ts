// SPDX-License-Identifier: Apache-2.0
// Tempdoc 561 (surface tier) — the open-state of the one window's retrospective drawer
// (Sessions / Timeline / History). A tiny module store the panel reflects and the one window toggles,
// mirroring how the agent-activity / advisory drawers toggle their `open` attribute.
// Tempdoc 565 §7.3 / 574 §23.B — a PURE open-state store; single-drawer arbitration now lives in the
// RetrospectivePanel component (it composes the `right-drawer`-layer TransientController), not here.

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

export function isRetrospectiveOpen(): boolean {
  return _open;
}

export function setRetrospectiveOpen(open: boolean): void {
  if (_open === open) return;
  _open = open;
  notify();
}

export function toggleRetrospective(): void {
  setRetrospectiveOpen(!_open);
}

export function subscribeRetrospective(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetRetrospectiveDrawer(): void {
  _open = false;
  _subs.clear();
}
