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

/**
 * Tempdoc 854 PR-A — the shadow-piercing half of the "is the reader typing?" guard: descend from the
 * document's active element through nested shadow roots to the element that ACTUALLY has focus
 * (`jf-sv3-main` → `jf-sv3-composer` → `textarea`). A bare `document.activeElement` check stops at the
 * outermost custom element and reports a non-editable host, which is why every modifier-less window
 * shortcut in this app needs the descent rather than the one-liner.
 *
 * Duck-typed on purpose — it reads `.shadowRoot?.activeElement` and nothing else, so a test may
 * monkeypatch `document.activeElement` with a plain object literal (the idiom
 * `views/UnifiedChatView.test.ts:2482-2499` already relies on). Narrowing it to `instanceof Element`,
 * `nodeType` or `closest()` would break that, and with it the only test of the descent.
 */
export function deepActiveElement(doc: Document = document): Element | null {
  let active: Element | null = doc.activeElement;
  while (active?.shadowRoot?.activeElement) active = active.shadowRoot.activeElement;
  return active;
}

/**
 * Tempdoc 854 PR-A — is this element one the reader types into? The UNION of the two copies that
 * existed before: `KeybindingRegistry.ts`'s modifier-less guard (which covered `SELECT`) and
 * `UnifiedChatView`'s inline J/K guard (which did not). The omission was a live bug — with the
 * workflow `<select>` focused (`views/UnifiedChatView.ts:3987`) a `j` press stole the element's
 * native type-ahead — so the union is a fix, not a widening for symmetry.
 *
 * Split from {@link deepActiveElement} so a caller that resolves its subject differently can reuse
 * the predicate without inheriting the descent: `KeybindingRegistry` asks where the event ORIGINATED
 * (`composedPath()[0]`), which is a different question from where focus IS, and is deliberately not
 * re-pointed at the descent.
 */
export function isTypingTarget(el: Element | null): boolean {
  if (el === null || el === undefined) return false;
  const tag = el.tagName;
  return (
    tag === 'INPUT' ||
    tag === 'TEXTAREA' ||
    tag === 'SELECT' ||
    (el as HTMLElement).isContentEditable === true
  );
}
