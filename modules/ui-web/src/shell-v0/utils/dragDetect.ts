// SPDX-License-Identifier: Apache-2.0
/**
 * dragDetect — framework-agnostic drag-drop file detection (slice 459).
 *
 * Listens at the window level for drag/drop events. Detects whether
 * the drag carries folders vs files via `webkitGetAsEntry()` (browser)
 * or Tauri's drop-event payload (desktop).
 *
 * Returns an unsubscribe function. Pair with `<jf-drag-overlay>` (Lit)
 * to display the visual overlay.
 */

import { isTauriRuntime } from '../../utils/tauriRuntime.js';

export type DragKind = 'folder' | 'file' | 'unknown';

export interface DragState {
  isDragging: boolean;
  dragKind: DragKind | null;
}

export interface DragDetectOptions {
  /** Called when state transitions (drag enter / leave / drop / type-detect). */
  onStateChange?: (state: DragState) => void;
  /** Called with one or more folder paths on drop. */
  onFolderDrop?: (paths: string[]) => void;
  /** Called with one or more file paths on drop. */
  onFileDrop?: (paths: string[]) => void;
}

function detectKind(e: DragEvent): DragKind {
  const items = e.dataTransfer?.items;
  if (!items || items.length === 0) return 'unknown';
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item?.kind === 'file') {
      const entry = (item as { webkitGetAsEntry?: () => { isDirectory?: boolean } | null })
        .webkitGetAsEntry?.();
      if (entry?.isDirectory) return 'folder';
      return 'file';
    }
  }
  return 'unknown';
}

async function readTauriDropPaths(): Promise<string[]> {
  // Tauri 1.x and 2.x both expose drop paths via different APIs.
  // We rely on the global event the user-side listener already
  // consumed (set via @tauri-apps/api/event listen). Production
  // wiring uses the ui-side useDragDrop's getTauriDropPaths(e)
  // helper; in the Lit context we don't have access to that closure.
  // Fallback: emit empty and let the caller handle browser-only mode.
  return [];
}

/**
 * Start listening to global drag/drop events. Returns an unsubscribe
 * function that removes all window listeners.
 */
export function startDragDetect(opts: DragDetectOptions): () => void {
  let dragCounter = 0;
  let lastKind: DragKind | null = null;

  const update = (s: Partial<DragState>) => {
    opts.onStateChange?.({
      isDragging: s.isDragging ?? false,
      dragKind: s.dragKind ?? null,
    });
  };

  const onDragEnter = (e: DragEvent) => {
    e.preventDefault();
    if (!e.dataTransfer?.types?.includes('Files')) return;
    dragCounter++;
    if (dragCounter === 1) {
      lastKind = detectKind(e);
      update({ isDragging: true, dragKind: lastKind });
    }
  };

  const onDragOver = (e: DragEvent) => {
    e.preventDefault();
    // Re-detect kind in case the source supplies new info on hover.
    const kind = detectKind(e);
    if (kind !== lastKind && kind !== 'unknown') {
      lastKind = kind;
      update({ isDragging: true, dragKind: kind });
    }
  };

  const onDragLeave = (e: DragEvent) => {
    e.preventDefault();
    dragCounter = Math.max(0, dragCounter - 1);
    if (dragCounter === 0) {
      lastKind = null;
      update({ isDragging: false, dragKind: null });
    }
  };

  const onDrop = async (e: DragEvent) => {
    e.preventDefault();
    dragCounter = 0;
    update({ isDragging: false, dragKind: null });
    const folderPaths: string[] = [];
    const filePaths: string[] = [];

    if (e.dataTransfer?.items) {
      for (let i = 0; i < e.dataTransfer.items.length; i++) {
        const item = e.dataTransfer.items[i];
        if (!item) continue;
        if (item.kind !== 'file') continue;
        const entry = (item as { webkitGetAsEntry?: () => { isDirectory?: boolean; fullPath?: string; name?: string } | null })
          .webkitGetAsEntry?.();
        const file = item.getAsFile?.();
        const path = (file as { path?: string } | null | undefined)?.path;
        if (entry?.isDirectory && path) folderPaths.push(path);
        else if (path) filePaths.push(path);
      }
    }

    if (isTauriRuntime()) {
      try {
        const tauriPaths = await readTauriDropPaths();
        // V1 Lit-side fallback: tauri payload is read by the
        // app-level event hook. Browser path provides raw paths via
        // file.path (Tauri exposes this). If empty, skip.
        for (const p of tauriPaths) {
          if (!filePaths.includes(p) && !folderPaths.includes(p)) {
            filePaths.push(p);
          }
        }
      } catch {
        // ignore
      }
    }

    if (folderPaths.length > 0 && opts.onFolderDrop) opts.onFolderDrop(folderPaths);
    if (filePaths.length > 0 && opts.onFileDrop) opts.onFileDrop(filePaths);
  };

  window.addEventListener('dragenter', onDragEnter);
  window.addEventListener('dragover', onDragOver);
  window.addEventListener('dragleave', onDragLeave);
  window.addEventListener('drop', onDrop);

  return () => {
    window.removeEventListener('dragenter', onDragEnter);
    window.removeEventListener('dragover', onDragOver);
    window.removeEventListener('dragleave', onDragLeave);
    window.removeEventListener('drop', onDrop);
  };
}
