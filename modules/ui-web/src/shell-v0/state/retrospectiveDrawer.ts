// SPDX-License-Identifier: Apache-2.0
// Tempdoc 561 (surface tier) — the open-state of the one window's retrospective drawer
// (Sessions / Timeline / History). A tiny module store the panel reflects and the one window toggles,
// mirroring how the agent-activity / advisory drawers toggle their `open` attribute.
// Tempdoc 565 §7.3 / 574 §23.B — a PURE open-state store; single-drawer arbitration now lives in the
// RetrospectivePanel component (it composes the `right-drawer`-layer TransientController), not here.

/**
 * Tempdoc 814 (finding 7) — the drawer's tab identities. Declared HERE rather than in the panel
 * because opening the drawer AT a tab is now a cross-component request (a background-run segment in
 * the thread points at its inbox item), and the requester must not import the panel to name a tab.
 */
export type RetrospectiveTab = 'sessions' | 'timeline' | 'history' | 'inbox';

let _open = false;
/** The tab an `openRetrospectiveAt` caller asked for, until the panel consumes it. */
let _requestedTab: RetrospectiveTab | null = null;
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

/**
 * Tempdoc 814 (finding 7, one-authority-one-pointer) — open the drawer AT a named tab.
 *
 * Notifies unconditionally (not via `setRetrospectiveOpen`, which is a no-op when the state already
 * matches) so a pointer clicked while the drawer is already open still switches the tab.
 */
export function openRetrospectiveAt(tab: RetrospectiveTab): void {
  _requestedTab = tab;
  _open = true;
  notify();
}

/** The pending requested tab, cleared by the read (the panel applies it exactly once). */
export function takeRequestedTab(): RetrospectiveTab | null {
  const t = _requestedTab;
  _requestedTab = null;
  return t;
}

export function subscribeRetrospective(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetRetrospectiveDrawer(): void {
  _open = false;
  _requestedTab = null;
  _subs.clear();
}
