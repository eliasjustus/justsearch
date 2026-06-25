// @vitest-environment happy-dom

/**
 * 569 §19 Phase 6 — PresentationEditorSurface behavior test.
 *
 * The editor is a real new surface (audit-without-test: a static "it mounts" is a hypothesis — this
 * exercises the load-bearing flow). It pins: (i) the palette only offers authorable, registered
 * hints; (ii) inserting a palette element yields a declaration that CERTIFIES (the closed-vocab
 * guarantee surfaced at compose time); (iii) the inline linter reflects the structured verdict; and
 * (iv) the live preview mounts a real `<jf-declared-surface>` for the working body region.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './PresentationEditorSurface.js';
import type { PresentationEditorSurface } from './PresentationEditorSurface.js';
import { certifyPresentation } from '../themes/conformanceGate.js';
// Side-effect: register the eager renderer set so the palette intersection is non-empty.
import '../renderers/registry.js';

async function mount(): Promise<PresentationEditorSurface> {
  const el = document.createElement(
    'jf-presentation-editor-surface',
  ) as PresentationEditorSurface;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('PresentationEditorSurface (569 §19 Phase 6)', () => {
  beforeEach(() => {
    localStorage.clear(); // §R T2.1 — isolate the persisted declText draft between cases
    document.body.innerHTML = '';
  });

  it('offers a palette of authorable, registered hints', async () => {
    const el = await mount();
    try {
      expect(el.paletteHints.length).toBeGreaterThan(0);
      // toggle-switch is eagerly registered AND authorable, so it must be offered.
      expect(el.paletteHints).toContain('toggle-switch');
      const palette = el.shadowRoot?.querySelector('[data-testid="editor-palette"]');
      expect(palette).toBeTruthy();
    } finally {
      el.remove();
    }
  });

  it('the starter declaration certifies, and inserting a palette element keeps it certifying', async () => {
    const el = await mount();
    try {
      // Starter draft certifies out of the box.
      expect(certifyPresentation(JSON.parse(el.declText)).verdict.ok).toBe(true);

      // Insert a toggle-switch via the same path the palette button calls.
      await (
        el as unknown as { insertHint(h: string): Promise<void> }
      ).insertHint('toggle-switch');
      await el.updateComplete;

      const draft = JSON.parse(el.declText);
      // The inserted node landed in the first body region's schema + uischema.
      const region = draft.body['core.settings.interface'];
      const props = Object.keys(region.schema.properties);
      expect(props).toContain('toggle_switch');
      expect(region.schema.properties.toggle_switch['x-ui-renderer']).toBe('toggle-switch');
      expect(region.uischema.elements.some((e: { scope?: string }) => e.scope === '#/properties/toggle_switch')).toBe(
        true,
      );
      // Still certifies — the palette can only produce legal declarations.
      expect(certifyPresentation(draft).verdict.ok).toBe(true);
    } finally {
      el.remove();
    }
  });

  it('the inline linter reports the structured verdict for an invalid draft', async () => {
    const el = await mount();
    try {
      el.declText = '{ not valid json';
      await el.updateComplete;
      const linter = el.shadowRoot?.querySelector('.linter')?.textContent ?? '';
      expect(linter).toMatch(/Invalid JSON/);

      // A parseable-but-unrepresentable draft surfaces the gate's structured reason.
      el.declText = JSON.stringify({
        schemaVersion: 1,
        id: 'user.bad',
        displayName: 'Bad',
        theme: { tokens: { 'not-a-token': '#fff' } },
      });
      await el.updateComplete;
      const linter2 = el.shadowRoot?.querySelector('.linter')?.textContent ?? '';
      expect(linter2).toMatch(/known token/);
    } finally {
      el.remove();
    }
  });

  it('renders a live <jf-declared-surface> preview for the working body region', async () => {
    const el = await mount();
    try {
      const preview = el.shadowRoot?.querySelector('jf-declared-surface');
      expect(preview).toBeTruthy();
    } finally {
      el.remove();
    }
  });
});

describe('PresentationEditorSurface — settle transients on hide (tempdoc 609)', () => {
  beforeEach(() => {
    localStorage.clear(); // §R T2.1 — isolate the persisted declText draft between cases
    document.body.innerHTML = '';
  });

  it('resets the in-flight busy/message on disconnect but KEEPS the declText draft', async () => {
    const el = await mount();
    const v = el as unknown as { busy: boolean; message: string | null; declText: string };
    v.busy = true; // "Asking the on-device model…" in flight
    v.message = 'Asking the on-device model…';
    v.declText = '{ "id": "my-unsaved-draft" }';

    el.remove(); // navigate away (instance retained; settleTransients fires via JfElement)

    expect(v.busy).toBe(false); // no stale spinner on return
    expect(v.message).toBeNull();
    expect(v.declText).toBe('{ "id": "my-unsaved-draft" }'); // author draft is recoverable, kept
  });
});
