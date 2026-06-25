// @vitest-environment happy-dom

/**
 * Slice 490 §4.D — AdvisoryRailBadge component tests.
 *
 * Covers unread-count display, custom-event dispatch on click, and the
 * Group B6 disconnected-state indicator.
 */

import { beforeEach, describe, expect, it } from 'vitest';
import './AdvisoryRailBadge.js';
import type { AdvisoryRailBadge } from './AdvisoryRailBadge.js';
import type { AdvisoryListener, AdvisorySnapshot, AdvisoryStore } from './AdvisoryStore.js';

class StubAdvisoryStore {
  private listeners = new Set<AdvisoryListener>();
  private snap: AdvisorySnapshot = {
    advisories: [],
    unreadCount: 0,
    isConnected: false,
    lastFrameKind: 'initial',
  };

  subscribe(listener: AdvisoryListener): () => void {
    this.listeners.add(listener);
    listener(this.snap);
    return () => {
      this.listeners.delete(listener);
    };
  }

  push(snapshot: Partial<AdvisorySnapshot>): void {
    this.snap = { ...this.snap, ...snapshot };
    for (const l of this.listeners) l(this.snap);
  }
}

function make(store: AdvisoryStore | null): AdvisoryRailBadge {
  const el = document.createElement('jf-advisory-rail-badge') as AdvisoryRailBadge;
  el.store = store;
  document.body.appendChild(el);
  return el;
}

describe('AdvisoryRailBadge', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders no unread count when zero', async () => {
    const store = new StubAdvisoryStore() as unknown as AdvisoryStore;
    const el = make(store);
    await el.updateComplete;
    const count = el.shadowRoot?.querySelector('.count');
    expect(count).toBeNull();
  });

  it('renders unread count when > 0', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ unreadCount: 3 });
    await el.updateComplete;
    const count = el.shadowRoot?.querySelector('.count');
    expect(count?.textContent).toBe('3');
  });

  it('caps display at 99+', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ unreadCount: 250 });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.count')?.textContent).toBe('99+');
  });

  it('dispatches advisory-toggle-inbox custom event on click', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    const events: Event[] = [];
    document.addEventListener('advisory-toggle-inbox', (e) => events.push(e));
    const button = el.shadowRoot?.querySelector('button') as HTMLButtonElement;
    button.click();
    expect(events.length).toBe(1);
    expect(events[0]?.type).toBe('advisory-toggle-inbox');
  });

  it('Group B6 — renders disconnected dot when isConnected=false', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ isConnected: false });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('button.disconnected')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('button')?.getAttribute('aria-label')).toContain(
      'offline',
    );
  });

  it('Group B6 — hides disconnected dot when isConnected=true', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ isConnected: true });
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).toBeNull();
    expect(el.shadowRoot?.querySelector('button.disconnected')).toBeNull();
  });

  it('renders no chrome when store is null', async () => {
    const el = make(null);
    await el.updateComplete;
    // Element still renders the button, but no subscription is active. Count
    // remains 0; disconnected dot follows from the constructor default
    // (isConnected=false).
    expect(el.unreadCount).toBe(0);
  });
});
