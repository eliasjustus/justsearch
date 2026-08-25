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
import { ModalityController, __resetModalityForTest } from '../primitives/modality.js';

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
    __resetModalityForTest();
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

  /**
   * Tempdoc 864 review F3 — SHIFT is not a modifier for this guard, and that is the runtime's answer,
   * not an accident: the dispatcher's test is `!parsed.mod && !parsed.ctrl && !parsed.meta &&
   * !parsed.alt`. A `shift+?` binding is therefore still a printable a reader can type, and must be
   * suppressed in an editable exactly like a bare one. `scripts/ci/check-printable-keybinding-policy.mjs`
   * classifies Shift the same way — this case is what keeps the gate and the runtime from drifting
   * apart into two answers.
   */
  it('864: Shift does not make a printable a chord — shift+? is suppressed while typing', () => {
    registerKeybinding({
      key: 'shift+?',
      commandId: 'test.help',
      source: 'default',
      provenance: CORE_PROVENANCE,
    });
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: '?', shiftKey: true, bubbles: true, composed: true }),
    );
    expect(invoked).not.toContain('test.help');
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: '?', shiftKey: true, bubbles: true, composed: true }),
    );
    expect(invoked, 'the binding must still fire outside an editable').toContain('test.help');
  });

  it('a modified chord (Ctrl+L) still fires from inside an input', () => {
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'l', ctrlKey: true, bubbles: true, composed: true }),
    );
    expect(invoked).toContain('test.focus-bar-chord');
  });

  /**
   * Tempdoc 864 Layer 2(b) — the dispatcher is the third of the three global-key sites that take the
   * SHARED `shouldIgnoreKeyEvent`. Adding these checks HERE ONLY (the first draft's plan) would have
   * left the two raw listeners uncovered and made a fifth fork of the guard set; the two adopters
   * are pinned in `Sv3Main.navigation.test.ts` and `Shell.globalKeys.test.ts`.
   */
  it('864: an IME-composing press and an auto-repeat press dispatch nothing', () => {
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: '/', isComposing: true, bubbles: true, composed: true }),
    );
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'l', ctrlKey: true, repeat: true, bubbles: true, composed: true }),
    );
    expect(invoked).toEqual([]);
  });

  /**
   * Tempdoc 864 Layer 2(d) — while a modal owns the keyboard, a modifier-less printable belongs to
   * the modal's content. Chords are deliberately NOT blocked: `mod+k` is how the palette is toggled,
   * and blocking it would trap the reader inside the thing they opened.
   */
  it('864: a modal suppresses the modifier-less binding but not the chord', () => {
    const modality = new ModalityController({
      addController: () => {},
      removeController: () => {},
      requestUpdate: () => {},
      updateComplete: Promise.resolve(true),
    });
    modality.enter();
    try {
      document.body.dispatchEvent(
        new KeyboardEvent('keydown', { key: '/', bubbles: true, composed: true }),
      );
      expect(invoked).not.toContain('test.focus-bar');
      document.body.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'l', ctrlKey: true, bubbles: true, composed: true }),
      );
      expect(invoked).toContain('test.focus-bar-chord');
    } finally {
      modality.exit({ skipFocusRestore: true });
    }
    document.body.dispatchEvent(
      new KeyboardEvent('keydown', { key: '/', bubbles: true, composed: true }),
    );
    expect(invoked, 'the suppression outlived the modal').toContain('test.focus-bar');
  });
});
