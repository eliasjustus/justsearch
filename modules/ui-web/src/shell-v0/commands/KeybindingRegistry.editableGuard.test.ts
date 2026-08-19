// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Search Thread S2 — the dispatcher's editable-target guard: a MODIFIER-LESS
 * binding (e.g. '/') must never steal keystrokes from an editable control,
 * while modified chords (Ctrl+L) still fire everywhere. The listener is
 * capture-phase on window, so without the guard a plain-key binding would
 * hijack every input in the app.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  registerKeybinding,
  attachKeybindingDispatcher,
  detachKeybindingDispatcher,
  __resetForTest,
} from './KeybindingRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

describe('KeybindingRegistry editable-target guard (Search Thread S2)', () => {
  let invoked: string[] = [];
  let teardown: (() => void) | null = null;
  let input: HTMLInputElement;

  beforeEach(() => {
    invoked = [];
    registerKeybinding({
      key: '/',
      commandId: 'test.focus-bar',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    registerKeybinding({
      key: 'mod+l',
      commandId: 'test.focus-bar-chord',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    teardown = attachKeybindingDispatcher((id) => invoked.push(id));
    input = document.createElement('input');
    document.body.appendChild(input);
  });

  afterEach(() => {
    teardown?.();
    detachKeybindingDispatcher();
    __resetForTest();
    input.remove();
  });

  it("plain '/' fires from a non-editable target", () => {
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: '/', bubbles: true, composed: true }),
    );
    expect(invoked).toContain('test.focus-bar');
  });

  it("plain '/' is suppressed when typing in an input", () => {
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: '/', bubbles: true, composed: true }),
    );
    expect(invoked).not.toContain('test.focus-bar');
  });

  it.each([
    ['textarea', () => document.createElement('textarea')],
    ['select', () => document.createElement('select')],
    ['contentEditable', () => {
      const el = document.createElement('div');
      Object.defineProperty(el, 'isContentEditable', { configurable: true, value: true });
      return el;
    }],
  ])("plain '/' is suppressed when the target is a %s", (_name, make) => {
    // Tempdoc 857 PR-A / review F3 — this guard's predicate is now the SHARED `isTypingTarget`
    // rather than an inline copy, so the union it covers is a dependency of this file and not just
    // a local literal. `select` in particular was covered here and NOWHERE else: dropping it from
    // the shared predicate used to leave this suite entirely green, which is exactly the kind of
    // silent coupling a shared predicate has to be pinned against.
    const target = make();
    document.body.appendChild(target);
    target.dispatchEvent(new KeyboardEvent('keydown', { key: '/', bubbles: true, composed: true }));
    expect(invoked).not.toContain('test.focus-bar');
    target.remove();
  });

  it('a modified chord (Ctrl+L) still fires from inside an input', () => {
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'l', ctrlKey: true, bubbles: true, composed: true }),
    );
    expect(invoked).toContain('test.focus-bar-chord');
  });
});
