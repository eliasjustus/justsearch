// @vitest-environment happy-dom

/**
 * 569 §15 — the Help reference renderers project shortcuts + info lists through the engine (the 3rd
 * real surface). Mirrors the FolderCardRenderer render-test shape.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import './ShortcutsTableRenderer.js';
import './ListItemsRenderer.js';
import type { ShortcutsTableRenderer } from './ShortcutsTableRenderer.js';
import type { ListItemsRenderer } from './ListItemsRenderer.js';
import { getXUiRendererTag } from './XUiRendererControl.js';

beforeEach(() => {
  document.body.innerHTML = '';
});

function mount<T extends HTMLElement>(tag: string, data: unknown): T {
  const el = document.createElement(tag) as T & {
    data: unknown;
    visible: boolean;
    enabled: boolean;
    uischema: unknown;
    onChange: () => void;
  };
  document.body.appendChild(el);
  el.data = data;
  el.visible = true;
  el.enabled = true;
  el.uischema = { type: 'Control' };
  el.onChange = () => {};
  return el as unknown as T;
}

describe('<jf-shortcuts-table>', () => {
  it('registers the hint and renders one row per shortcut (desc + kbd)', async () => {
    expect(getXUiRendererTag('shortcuts-table')).toBe('jf-shortcuts-table');
    const el = mount<ShortcutsTableRenderer>('jf-shortcuts-table', [
      { keys: '/', desc: 'Focus search bar' },
      { keys: '??', desc: 'AI chat mode' },
    ]);
    await el.updateComplete;
    const rows = el.shadowRoot?.querySelectorAll('.shortcut-row');
    expect(rows?.length).toBe(2);
    expect(el.shadowRoot?.querySelector('kbd')?.textContent).toBe('/');
    expect(el.shadowRoot?.querySelector('.shortcut-row span')?.textContent).toContain('Focus search bar');
    el.remove();
  });
});

describe('<jf-list-items>', () => {
  it('registers the hint and renders one <li> per string', async () => {
    expect(getXUiRendererTag('list-items')).toBe('jf-list-items');
    const el = mount<ListItemsRenderer>('jf-list-items', ['alpha', 'beta', 'gamma']);
    await el.updateComplete;
    const items = el.shadowRoot?.querySelectorAll('li');
    expect(items?.length).toBe(3);
    expect(items?.[1]?.textContent).toBe('beta');
    el.remove();
  });
});
