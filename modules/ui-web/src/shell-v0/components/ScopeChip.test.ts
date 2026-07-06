// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest';
import './ScopeChip.js';
import type { ScopeChip } from './ScopeChip.js';
import type { SearchScopeChip } from '../state/searchState.js';

async function mount(chip: SearchScopeChip): Promise<ScopeChip> {
  const el = document.createElement('jf-scope-chip') as ScopeChip;
  el.chip = chip;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('jf-scope-chip (Search-Thread S3)', () => {
  it('renders nothing when no chip is set', async () => {
    const el = document.createElement('jf-scope-chip') as ScopeChip;
    document.body.appendChild(el);
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('.scope-chip')).toBeNull();
  });

  it('renders a file-kind chip with the file glyph and a middle-ellipsized label', async () => {
    const longPath =
      '/very/deeply/nested/directory/structure/that/is/definitely/longer/than/the/display/max/file.md';
    const el = await mount({ kind: 'file', label: longPath, docIds: ['doc-1'] });
    const label = el.shadowRoot!.querySelector('.scope-chip-label')!.textContent!.trim();
    expect(label).not.toBe(longPath); // truncated
    expect(label).toContain('…');
    expect(label.endsWith('file.md')).toBe(true);
    // full path preserved for hover
    expect(el.shadowRoot!.querySelector('.scope-chip')!.getAttribute('title')).toBe(longPath);
  });

  it('renders a result-set chip label verbatim (no path truncation)', async () => {
    const el = await mount({ kind: 'result-set', label: '12 results', docIds: ['a', 'b'] });
    expect(el.shadowRoot!.querySelector('.scope-chip-label')!.textContent!.trim()).toBe(
      '12 results',
    );
  });

  it('the remove affordance is a native, keyboard-operable <button> with the required accessible name', async () => {
    const el = await mount({ kind: 'file', label: 'notes.md', docIds: ['doc-1'] });
    const btn = el.shadowRoot!.querySelector('button.scope-chip-remove') as HTMLButtonElement;
    expect(btn).toBeTruthy();
    expect(btn.tagName).toBe('BUTTON'); // native element — keyboard-operable by construction
    expect(btn.getAttribute('aria-label')).toBe('Remove scope notes.md');
  });

  it('clicking the remove affordance dispatches a bubbling, composed `scope-remove` event', async () => {
    const el = await mount({ kind: 'file', label: 'notes.md', docIds: ['doc-1'] });
    const handler = vi.fn();
    document.body.addEventListener('scope-remove', handler);
    const btn = el.shadowRoot!.querySelector('button.scope-chip-remove') as HTMLButtonElement;
    btn.click();
    expect(handler).toHaveBeenCalledTimes(1);
    const evt = handler.mock.calls[0]![0] as Event;
    expect(evt.bubbles).toBe(true);
    expect(evt.composed).toBe(true);
    document.body.removeEventListener('scope-remove', handler);
  });
});
