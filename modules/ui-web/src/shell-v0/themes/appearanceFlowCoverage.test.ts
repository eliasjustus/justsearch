// @vitest-environment happy-dom

/**
 * Tempdoc 806 B.2 (round-12): the declared Settings region must be able to PERSIST every control
 * it renders.
 *
 * Round 12 reported a "Vim keybindings" toggle going visually ON while `ui.vimMode` stayed false,
 * and correctly cleared the write path using the High-contrast control. The two differ in exactly
 * one way: `SETTINGS_INTERFACE_SCHEMA` declares both, `APPEARANCE_FLOW` had an edge only for
 * High-contrast, and the statechart's `save-settings` effect is the ONLY persistence — so the Vim
 * edit reached `SettingsSurface.patch()`'s local `this.ui` copy, rendered ON from it, and stopped.
 * `defaultAction` was the same defect, unreported.
 *
 * This pins the invariant rather than the two instances: a schema property with no writing edge is
 * a control that lies about having saved. A new declared field will fail here until it has one.
 */
import { describe, it, expect } from 'vitest';
import { APPEARANCE_FLOW, SETTINGS_DECLARED, SETTINGS_INTERFACE_REGION } from './builtinPresentations.js';
import { createMachine } from '../substrates/interaction/index.js';
import type { Effect } from '../substrates/effect.js';

/** Every `ui.*` key any `save-settings` effect anywhere in the flow can write. */
function persistableKeys(): Set<string> {
  const keys = new Set<string>();
  for (const state of APPEARANCE_FLOW.states) {
    for (const t of state.transitions ?? []) {
      for (const e of t.effects ?? []) {
        if (e.kind !== 'save-settings') continue;
        const ui = (e.settings as Record<string, unknown>)?.['ui'];
        if (ui && typeof ui === 'object') {
          for (const k of Object.keys(ui as Record<string, unknown>)) keys.add(k);
        }
      }
    }
  }
  return keys;
}

describe('806: APPEARANCE_FLOW covers every control the declared Settings region renders', () => {
  it('every SETTINGS_INTERFACE schema property has a save-settings edge that writes it', () => {
    const body = SETTINGS_DECLARED.body?.[SETTINGS_INTERFACE_REGION];
    expect(body, 'the declared Settings region must exist').toBeTruthy();
    const declared = Object.keys(
      (body!.schema as unknown as { properties: Record<string, unknown> }).properties,
    );
    expect(declared.length, 'sanity: the region declares controls').toBeGreaterThan(0);

    const writable = persistableKeys();
    const unwritable = declared.filter((k) => !writable.has(k));
    expect(
      unwritable,
      'a declared control with no save-settings edge renders its new value and never persists it ' +
        '(round-12 Vim toggle). Add an edge to APPEARANCE_FLOW and route it in SettingsSurface.patch().',
    ).toEqual([]);
  });

  it('VIM_ON / VIM_OFF actually emit the persisting effect (the round-12 instance)', () => {
    for (const [event, expected] of [
      ['VIM_ON', true],
      ['VIM_OFF', false],
    ] as const) {
      const seen: Effect[] = [];
      const m = createMachine(APPEARANCE_FLOW, (e) => {
        seen.push(e);
      });
      m.send(event);
      const save = seen.find((e) => e.kind === 'save-settings');
      expect(save, `${event} must dispatch save-settings`).toBeTruthy();
      const ui = (save as { settings: Record<string, unknown> }).settings['ui'] as Record<
        string,
        unknown
      >;
      expect(ui['vimMode']).toBe(expected);
    }
  });
});
