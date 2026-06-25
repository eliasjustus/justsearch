// @vitest-environment happy-dom

/**
 * 569 Fix 5 — the data round-trip is now LOAD-BEARING (the default Settings render is the declared
 * one). This proves the full chain: a real declared control (the bespoke option-button renderer) →
 * onChange → DeclaredSurface.setAtPath → `surface-change` with the mutated data — which SettingsSurface
 * feeds into `patch()`. Exercises the REAL renderers, not a stub.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import '../renderers/registry.js'; // registers all controls + the x-ui-renderer hint renderers
import '../components/DeclaredSurface.js';
import type { DeclaredSurface, SurfaceChangeEventDetail } from '../components/DeclaredSurface.js';
import { SETTINGS_INTERFACE_BODY } from '../themes/builtinPresentations.js';

/** Recursively await updateComplete on every custom element in the subtree (settle nested renders). */
async function settle(root: Element, rounds = 4): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    const els: Element[] = [];
    const walk = (n: Element): void => {
      els.push(n);
      if (n.shadowRoot) for (const c of Array.from(n.shadowRoot.children)) walk(c);
      for (const c of Array.from(n.children)) walk(c);
    };
    walk(root);
    await Promise.all(
      els
        .map((e) => (e as Element & { updateComplete?: Promise<unknown> }).updateComplete)
        .filter(Boolean) as Promise<unknown>[],
    );
    await new Promise((r) => setTimeout(r, 0));
  }
}

/** Deep querySelectorAll across shadow boundaries. */
function deepQueryAll(root: Element, selector: string): Element[] {
  const out: Element[] = [];
  const walk = (n: Element): void => {
    if (n.matches(selector)) out.push(n);
    if (n.shadowRoot) for (const c of Array.from(n.shadowRoot.children)) walk(c);
    for (const c of Array.from(n.children)) walk(c);
  };
  walk(root);
  return out;
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('DeclaredSurface — settings round-trip (569 Fix 5)', () => {
  it('clicking a declared option-button emits surface-change with the mutated value', async () => {
    const el = document.createElement('jf-declared-surface') as DeclaredSurface;
    el.declaration = SETTINGS_INTERFACE_BODY;
    el.data = {
      mode: 'simple',
      theme: 'dark',
      highContrast: false,
      vimMode: false,
      defaultAction: 'open',
    };
    el.enabled = true;
    document.body.appendChild(el);
    await settle(el);

    let detail: SurfaceChangeEventDetail | null = null;
    el.addEventListener('surface-change', (e) => {
      detail = (e as CustomEvent<SurfaceChangeEventDetail>).detail;
    });

    const buttons = deepQueryAll(el, 'button.option-btn');
    expect(buttons.length).toBeGreaterThan(0); // the bespoke renderer actually rendered
    const advanced = buttons.find((b) => b.textContent?.includes('Advanced')) as HTMLButtonElement;
    expect(advanced).toBeTruthy();
    advanced.click();

    expect(detail).not.toBeNull();
    expect((detail as unknown as SurfaceChangeEventDetail).data).toMatchObject({ mode: 'advanced' });
  });
});
