// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 495 F2 — Shared keyboard handler for chat submit.
 *
 * Extracted from the duplicated Enter/Shift+Enter pattern in AgentView,
 * AskView, NavigateView, and SummarizeView.
 */

export function handleSubmitKey(
  e: KeyboardEvent,
  onSubmit: () => void,
  opts?: { requireCtrl?: boolean },
): void {
  if (e.key !== 'Enter') return;
  if (e.shiftKey) return;
  if (opts?.requireCtrl && !e.ctrlKey) return;
  e.preventDefault();
  onSubmit();
}

/**
 * Tempdoc 559 Authority V — map Enter/Space to a control's activation. The shared
 * form of the role="button"+tabindex+keydown triad an element needs when it is a
 * focusable affordance but cannot be a native `<button>`/`<jf-control>` (e.g. a
 * clickable list row whose layout forbids a button wrapper). Pair with
 * `role="button" tabindex="0"`.
 */
export function activateOnKey(e: KeyboardEvent, onActivate: () => void): void {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  e.preventDefault();
  onActivate();
}
