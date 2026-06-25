// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import './RecentsMenu.js';
import {
  recordNavigation,
  __resetJournalForTest,
  type DispatchFn,
} from '../state/NavigationJournal.js';

interface MenuEl extends HTMLElement {
  dispatch: DispatchFn | null;
  open: boolean;
  updateComplete: Promise<unknown>;
}

async function mount(dispatch: DispatchFn): Promise<MenuEl> {
  const el = document.createElement('jf-recents-menu') as MenuEl;
  el.dispatch = dispatch;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('RecentsMenu (tempdoc 609 §R T1.2)', () => {
  beforeEach(() => {
    __resetJournalForTest();
    document.body.innerHTML = '';
  });

  it('renders recent surfaces (most-recent-first, deduped, current excluded) and restores on click', async () => {
    // Visit three surfaces; the last one is "current" (cursor) and should be excluded from the list.
    recordNavigation('core.search-surface', 'justsearch://surface/core.search-surface', 'Search', 'RAIL');
    recordNavigation('core.library-surface', 'justsearch://surface/core.library-surface', 'Library', 'RAIL');
    recordNavigation('core.health-surface', 'justsearch://surface/core.health-surface', 'Health', 'RAIL');

    const dispatch = vi.fn<DispatchFn>(() => Promise.resolve(undefined));
    const el = await mount(dispatch);
    el.open = true;
    await el.updateComplete;

    const items = [...el.shadowRoot!.querySelectorAll<HTMLButtonElement>('.item')];
    const labels = items.map((b) => b.textContent?.trim());
    // Current (Health) excluded; earlier two listed most-recent-first.
    expect(labels).toEqual(['Library', 'Search']);

    items[0]!.click(); // pick "Library"
    expect(dispatch).toHaveBeenCalledTimes(1);
    const [intent] = dispatch.mock.calls[0]!;
    expect((intent as { address: { target: string } }).address.target).toBe('core.library-surface');
    expect(el.open).toBe(false); // menu closes after a pick
  });

  it('shows an empty state when there is no trail beyond the current view', async () => {
    recordNavigation('core.search-surface', 'justsearch://surface/core.search-surface', 'Search', 'RAIL');
    const el = await mount(vi.fn<DispatchFn>(() => Promise.resolve(undefined)));
    el.open = true;
    await el.updateComplete;
    expect(el.shadowRoot!.querySelector('.empty')).not.toBeNull();
    expect(el.shadowRoot!.querySelectorAll('.item').length).toBe(0);
  });
});
