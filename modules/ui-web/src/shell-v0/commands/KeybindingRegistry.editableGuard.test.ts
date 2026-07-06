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

  it('a modified chord (Ctrl+L) still fires from inside an input', () => {
    input.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'l', ctrlKey: true, bubbles: true, composed: true }),
    );
    expect(invoked).toContain('test.focus-bar-chord');
  });
});
