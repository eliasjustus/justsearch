// @vitest-environment happy-dom

/**
 * Tempdoc 586 P-3 — regression guard for the Help "Keyboard shortcuts" table.
 *
 * The previous table advertised shortcuts that don't fire: `/` (focus search) and
 * `/` (command mode) were unimplemented V1 deferrals, and `??` is a search-box query
 * prefix, not a keycap. P-3 rewrote the module-private `SHORTCUTS` array in
 * HelpSurface to only the bindings that actually fire. `SHORTCUTS` is private and
 * renders either through the projection engine (`renderReferenceRegion`) or the
 * built-in fallback (`renderShortcuts`), so this asserts the rendered output across
 * the surface's full (nested) shadow tree rather than the const directly.
 */

import { afterEach, describe, expect, it } from 'vitest';
import './HelpSurface.js';

/** Concatenate text across the element's whole shadow subtree (nested shadow roots included). */
function collectShadowText(host: Element): string {
  let text = '';
  const queue: Element[] = [host];
  let i = 0;
  while (i < queue.length && i < 5000) {
    const node = queue[i++];
    if (!node) continue;
    const sr = node.shadowRoot;
    if (sr) {
      text += ' ' + (sr.textContent ?? '');
      queue.push(...Array.from(sr.children));
    }
    queue.push(...Array.from(node.children));
  }
  return text.replace(/\s+/g, ' ');
}

interface HelpElement extends HTMLElement {
  apiBase: string;
  updateComplete: Promise<unknown>;
}

async function mountHelp(): Promise<HelpElement> {
  const el = document.createElement('jf-help-surface') as HelpElement;
  el.apiBase = '';
  document.body.appendChild(el);
  // Let the surface + any nested declared-surface / renderers settle.
  await el.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  await el.updateComplete;
  return el;
}

describe('HelpSurface — keyboard shortcuts (tempdoc 586 P-3)', () => {
  afterEach(() => {
    document.querySelectorAll('jf-help-surface').forEach((el) => el.remove());
  });

  it('lists only shortcuts that actually fire; the defunct `/` and `??` rows are gone', async () => {
    const el = await mountHelp();
    const text = collectShadowText(el);

    // The corrected, real bindings are present.
    expect(text).toContain('Ctrl / ⌘ + K');
    expect(text).toContain('Open the command palette');

    // The misleading / unimplemented rows P-3 removed must NOT reappear.
    expect(text).not.toContain('Focus search bar');
    expect(text).not.toContain('Enter command mode');
    expect(text).not.toContain('Enter AI chat mode');
  });
});

describe('HelpSurface — settle transients on hide (tempdoc 609)', () => {
  it('resets export feedback on disconnect but KEEPS the expanded FAQ choice', async () => {
    const el = await mountHelp();
    const v = el as unknown as {
      exportPath: string | null;
      exportError: string | null;
      expanded: number | null;
    };
    v.exportPath = 'F:/diag/export.zip';
    v.exportError = 'Export failed: disk full';
    v.expanded = 3; // the user's open FAQ item (recoverable)

    el.remove(); // navigate away (instance retained; settleTransients fires via JfElement)

    expect(v.exportPath).toBeNull(); // stale export feedback settled
    expect(v.exportError).toBeNull();
    expect(v.expanded).toBe(3); // the open FAQ choice is recoverable, kept
  });
});
