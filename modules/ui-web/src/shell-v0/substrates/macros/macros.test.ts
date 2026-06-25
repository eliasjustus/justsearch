// @vitest-environment happy-dom

/**
 * Tempdoc 543 §25.δ3 — Macros substrate tests.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  defineMacro,
  removeMacro,
  listMacros,
  getMacro,
  runMacro,
  previewMacroReplay,
  subscribeMacros,
  restoreMacrosFromStorage,
  __resetMacrosForTest,
} from './index.js';
import { __resetActionsForTest, getAction, invokeAndApply } from '../actions/index.js';

beforeEach(() => {
  __resetMacrosForTest();
  __resetActionsForTest();
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem('justsearch.macros.v1');
  }
});

describe('Macros substrate (§25.δ3)', () => {
  it('defineMacro stores the macro and auto-registers an Action', () => {
    const m = defineMacro({
      id: 'my-macro',
      label: 'Open inspector then navigate',
      effects: [
        { kind: 'open-pane', paneId: 'inspector' },
        { kind: 'navigate', to: '#search' },
      ],
    });
    expect(m.id).toBe('my-macro');
    expect(getMacro('my-macro')).toBe(m);
    expect(listMacros()).toHaveLength(1);
    // Auto-registered as Action
    expect(getAction('core.action.macro.my-macro')).toBeDefined();
  });

  it('defineMacro throws on duplicate id', () => {
    defineMacro({ id: 'dup', label: 'A', effects: [{ kind: 'noop' }] });
    expect(() =>
      defineMacro({ id: 'dup', label: 'B', effects: [{ kind: 'noop' }] }),
    ).toThrow(/already defined/);
  });

  it('removeMacro removes from registry + unregisters Action', () => {
    defineMacro({ id: 'remove-me', label: 'R', effects: [{ kind: 'noop' }] });
    expect(getAction('core.action.macro.remove-me')).toBeDefined();
    expect(removeMacro('remove-me')).toBe(true);
    expect(getMacro('remove-me')).toBeUndefined();
    expect(getAction('core.action.macro.remove-me')).toBeUndefined();
  });

  it('runMacro dispatches each effect in order', async () => {
    const events: string[] = [];
    document.addEventListener('jf-advisory-ephemeral', (e: Event) => {
      events.push((e as CustomEvent).detail.message);
    });
    defineMacro({
      id: 'toast-chain',
      label: 'Toast chain',
      effects: [
        { kind: 'toast', message: 'one' },
        { kind: 'toast', message: 'two' },
        { kind: 'toast', message: 'three' },
      ],
    });
    const count = await runMacro('toast-chain');
    expect(count).toBe(3);
    expect(events).toEqual(['one', 'two', 'three']);
  });

  it('runMacro unknown id returns 0', async () => {
    expect(await runMacro('nope')).toBe(0);
  });

  // 543-fwd #12 — dry-run plan: previewMacroReplay lists effects + backend ops.
  it('previewMacroReplay returns the would-be entries and the backend op ids', () => {
    defineMacro({
      id: 'mixed',
      label: 'Mixed',
      effects: [
        { kind: 'navigate', to: '#a' },
        { kind: 'invoke-operation', operationId: 'core.file-operations' },
        { kind: 'toast', message: 'done' },
      ],
    });
    const plan = previewMacroReplay('mixed');
    expect(plan.entries.map((e) => e.effect.kind)).toEqual([
      'navigate',
      'invoke-operation',
      'toast',
    ]);
    expect(plan.backendOps).toEqual(['core.file-operations']);
    // Unknown macro → empty plan.
    expect(previewMacroReplay('nope')).toEqual({ entries: [], backendOps: [] });
  });

  // 543-fwd #12 — the backend-replay guard: runMacro must NOT silently re-POST.
  it('runMacro skips backend effects unless allowBackendReplay is set', async () => {
    const toasts: string[] = [];
    const invokes: string[] = [];
    document.addEventListener('jf-advisory-ephemeral', (e) => toasts.push((e as CustomEvent).detail.message));
    document.addEventListener('jf-invoke-operation', (e) => invokes.push((e as CustomEvent).detail.operationId));
    defineMacro({
      id: 'guarded',
      label: 'Guarded',
      effects: [
        { kind: 'toast', message: 'fe-ran' },
        { kind: 'invoke-operation', operationId: 'core.file-operations' },
      ],
    });
    // Default: backend effect skipped, only FE dispatched.
    const guarded = await runMacro('guarded');
    expect(guarded).toBe(1);
    expect(toasts).toEqual(['fe-ran']);
    expect(invokes).toEqual([]);
    // Opt-in: backend effect dispatched.
    const allowed = await runMacro('guarded', { allowBackendReplay: true });
    expect(allowed).toBe(2);
    expect(invokes).toEqual(['core.file-operations']);
  });

  // 543-fwd #12 — the palette Action opens the dry-run panel for a backend macro
  // (does not run it), but runs a pure-FE macro directly.
  it('macro Action routes backend macros to the dry-run panel, runs FE macros directly', async () => {
    const opened: string[] = [];
    const toasts: string[] = [];
    document.addEventListener('jf-open-macro-dry-run', (e) => opened.push((e as CustomEvent).detail.macroId));
    document.addEventListener('jf-advisory-ephemeral', (e) => toasts.push((e as CustomEvent).detail.message));
    document.addEventListener('jf-invoke-operation', () => {
      throw new Error('backend op must NOT fire from the palette path');
    });
    defineMacro({
      id: 'backend-macro',
      label: 'B',
      effects: [{ kind: 'invoke-operation', operationId: 'core.file-operations' }],
    });
    defineMacro({
      id: 'fe-macro',
      label: 'F',
      effects: [{ kind: 'toast', message: 'fe-only' }],
    });
    await invokeAndApply('core.action.macro.backend-macro');
    expect(opened).toEqual(['backend-macro']); // opened dry-run, did not run
    await invokeAndApply('core.action.macro.fe-macro');
    expect(toasts).toContain('fe-only'); // ran directly
  });

  it('subscribeMacros notifies on define + remove', () => {
    const fn = vi.fn();
    subscribeMacros(fn);
    defineMacro({ id: 'x', label: 'X', effects: [{ kind: 'noop' }] });
    removeMacro('x');
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('persistence: define → re-read via restore picks it up', () => {
    defineMacro({
      id: 'persistent',
      label: 'P',
      effects: [{ kind: 'noop' }],
    });
    // Wipe in-memory state but leave localStorage.
    const raw = localStorage.getItem('justsearch.macros.v1');
    expect(raw).toBeTruthy();
    __resetMacrosForTest();
    // __resetMacrosForTest clears localStorage, so re-write the
    // persistence and try restore on a fresh state.
    localStorage.setItem('justsearch.macros.v1', raw!);
    __resetActionsForTest();
    restoreMacrosFromStorage();
    expect(getMacro('persistent')).toBeDefined();
    expect(getAction('core.action.macro.persistent')).toBeDefined();
  });

  it('macro Action invokes runMacro via the kernel dispatch', async () => {
    const toasts: string[] = [];
    document.addEventListener('jf-advisory-ephemeral', (e: Event) => {
      toasts.push((e as CustomEvent).detail.message);
    });
    defineMacro({
      id: 'invoke-via-action',
      label: 'I',
      effects: [{ kind: 'toast', message: 'macro fired' }],
    });
    const { invokeAndApply } = await import('../actions/index.js');
    await invokeAndApply('core.action.macro.invoke-via-action');
    expect(toasts).toContain('macro fired');
  });
});
