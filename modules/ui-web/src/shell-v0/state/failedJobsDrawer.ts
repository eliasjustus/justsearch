// SPDX-License-Identifier: Apache-2.0
// Tempdoc 599 §16/B1 — open-state of the per-folder "failed files" right-drawer. A tiny module store
// the FailedJobsDrawer reflects and the Library folder row toggles, mirroring retrospectiveDrawer.ts.
// PURE open-state + the target folder's pathHash; single-drawer arbitration lives in the
// FailedJobsDrawer component (it composes the `right-drawer`-layer TransientController), not here.

let _open = false;
let _folderPathHash: string | null = null;
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

export function isFailedJobsOpen(): boolean {
  return _open;
}

/** The watched-root pathHash whose failed jobs the drawer shows (null when closed). */
export function failedJobsFolderPathHash(): string | null {
  return _folderPathHash;
}

export function openFailedJobs(folderPathHash: string): void {
  if (_open && _folderPathHash === folderPathHash) return;
  _open = true;
  _folderPathHash = folderPathHash;
  notify();
}

export function closeFailedJobs(): void {
  if (!_open) return;
  _open = false;
  _folderPathHash = null;
  notify();
}

export function subscribeFailedJobs(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}

/** Test-only reset. */
export function __resetFailedJobsDrawer(): void {
  _open = false;
  _folderPathHash = null;
  _subs.clear();
}
