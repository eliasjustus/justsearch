/**
 * 569 Move 1/3/6 — the body/layout apply path + active-presentation store.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import {
  applyPresentationBodies,
  activeBodyFor,
  activeInteractionFor,
  getActivePresentation,
  clearPresentation,
  subscribePresentation,
  __resetPresentationForTest,
} from './presentationRuntime.js';
import {
  SETTINGS_DECLARED,
  SETTINGS_INTERFACE_REGION,
  APPEARANCE_FLOW,
  CONFIRM_CEREMONY,
} from '../themes/builtinPresentations.js';
import { createMachine } from '../substrates/interaction/index.js';
import type { Effect } from '../substrates/effect.js';

beforeEach(() => __resetPresentationForTest());

describe('applyPresentationBodies', () => {
  it('applies a valid built-in declaration and exposes its body by region id', () => {
    const res = applyPresentationBodies(SETTINGS_DECLARED);
    expect(res.ok).toBe(true);
    expect(res.errors).toEqual([]);
    const body = activeBodyFor(SETTINGS_INTERFACE_REGION);
    expect(body).toBeDefined();
    expect(body?.heading).toBe('Interface & Appearance');
    expect(getActivePresentation().id).toBe('builtin.settings-declared');
  });

  it('does NOT apply a hard-invalid declaration (unknown key) and leaves the store unchanged', () => {
    const res = applyPresentationBodies({
      schemaVersion: 1,
      id: 'bad.decl',
      displayName: 'Bad',
      cssText: 'html{display:none}', // unrepresentable field → validation error
    });
    expect(res.ok).toBe(false);
    expect(res.errors.length).toBeGreaterThan(0);
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeUndefined();
    expect(getActivePresentation().id).toBeNull();
  });

  it('clearPresentation reverts every region to the built-in (degrade-never-fail)', () => {
    applyPresentationBodies(SETTINGS_DECLARED);
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeDefined();
    clearPresentation();
    expect(activeBodyFor(SETTINGS_INTERFACE_REGION)).toBeUndefined();
    expect(getActivePresentation().id).toBeNull();
  });

  it('569 §14 — publishes the interaction tier; activeInteractionFor returns the declared statechart', () => {
    expect(activeInteractionFor('core.appearance-flow')).toBeUndefined(); // none active yet
    applyPresentationBodies(SETTINGS_DECLARED);
    const chart = activeInteractionFor('core.appearance-flow');
    expect(chart).toBeDefined();
    expect(chart?.id).toBe('core.appearance-flow');
    expect(activeInteractionFor('core.confirm-ceremony')).toBeDefined();
    clearPresentation();
    expect(activeInteractionFor('core.appearance-flow')).toBeUndefined();
  });
});

describe('569 §14 — APPEARANCE_FLOW behaviour', () => {
  it('a theme event dispatches set-appearance (apply) + save-settings (persist) as named effects', () => {
    const fired: Effect[] = [];
    const m = createMachine(APPEARANCE_FLOW, (e) => fired.push(e)); // test dispatcher (no DOM/journal)
    expect(m.state).toBe('active');
    m.send('THEME_DARK');
    expect(fired).toEqual([
      { kind: 'set-appearance', theme: 'dark' },
      { kind: 'save-settings', settings: { ui: { theme: 'dark' } } },
    ]);
    expect(m.state).toBe('active'); // always-accepting; never blocks the next change
    fired.length = 0;
    m.send('MODE_ADVANCED');
    expect(fired).toEqual([
      { kind: 'set-ui-mode', mode: 'advanced' },
      { kind: 'save-settings', settings: { ui: { mode: 'advanced' } } },
    ]);
  });
});

describe('569 §15 — CONFIRM_CEREMONY (the BRANCHING, guarded delete ceremony)', () => {
  it('REQUEST → confirming; the typed==true guard gates CONFIRM → done firing the closed toast', () => {
    const fired: Effect[] = [];
    const m = createMachine(CONFIRM_CEREMONY, (e) => fired.push(e)); // test dispatcher
    expect(m.state).toBe('idle');

    m.send('REQUEST');
    expect(m.state).toBe('confirming'); // multi-state: a real branch, not a self-loop

    // Guard blocks the destructive step until "DELETE" is typed (typed == true).
    m.send('CONFIRM', { typed: false });
    expect(m.state).toBe('confirming'); // guard failed → no transition
    expect(fired).toEqual([]); // no effect fired

    m.send('CONFIRM', { typed: true });
    expect(m.state).toBe('done');
    // The declared effect is the SAFE closed toast — no phantom invoke-operation; the bespoke
    // delete body runs in the surface on entering `done` (§7).
    expect(fired).toEqual([{ kind: 'toast', message: 'Confirmed', severity: 'success' }]);
  });

  it('CANCEL from confirming returns to idle (no effect, no destructive step)', () => {
    const fired: Effect[] = [];
    const m = createMachine(CONFIRM_CEREMONY, (e) => fired.push(e));
    m.send('REQUEST');
    m.send('CANCEL');
    expect(m.state).toBe('idle');
    expect(fired).toEqual([]);
  });
});

describe('subscribePresentation', () => {
  it('fires immediately with the current value and again on every apply/clear', () => {
    const seen: (string | null)[] = [];
    const unsub = subscribePresentation((p) => seen.push(p.id));
    applyPresentationBodies(SETTINGS_DECLARED);
    clearPresentation();
    unsub();
    applyPresentationBodies(SETTINGS_DECLARED); // after unsub — not observed
    expect(seen).toEqual([null, 'builtin.settings-declared', null]);
  });
});
