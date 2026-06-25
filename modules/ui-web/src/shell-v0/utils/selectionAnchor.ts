// SPDX-License-Identifier: Apache-2.0
/**
 * selectionAnchor — Tempdoc 526 §14.5 follow-up — producer-side rect
 * register for the F9 floating action menu.
 *
 * The menu lives in Shell's shadow root; user selections live in
 * InspectorPane's shadow root (two boundaries deep). Cross-shadow-root
 * {@code window.getSelection()} is implementation-defined for nested open
 * roots and returns {@code null} in practice on Chromium 138.
 *
 * The substrate-correct answer: the selection's *producer* knows where the
 * selection visually lives; it publishes the rect alongside the typed
 * SelectionItem. The menu reads from this one-shot register instead of
 * querying DOM. New producers (citation panel, future surfaces) publish
 * here too.
 *
 * One-shot: {@link takeMenuAnchor} drains the register so a stale rect
 * doesn't anchor the menu to nothing after the selection clears.
 */

export interface MenuAnchorRect {
  readonly top: number;
  readonly left: number;
  readonly bottom: number;
  readonly right: number;
}

let pending: MenuAnchorRect | null = null;
const listeners = new Set<(rect: MenuAnchorRect | null) => void>();

export function setMenuAnchor(rect: MenuAnchorRect | null): void {
  pending = rect;
  for (const l of listeners) {
    try {
      l(rect);
    } catch {
      /* swallow */
    }
  }
}

export function peekMenuAnchor(): MenuAnchorRect | null {
  return pending;
}

export function takeMenuAnchor(): MenuAnchorRect | null {
  const r = pending;
  pending = null;
  return r;
}

export function subscribeMenuAnchor(
  listener: (rect: MenuAnchorRect | null) => void,
): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
