// @vitest-environment happy-dom

/**
 * Slice 490 §4.D — AdvisoryRailBadge component tests.
 *
 * Covers unread-count display, custom-event dispatch on click, and the
 * Group B6 stale-feed indicator (tempdoc 662 post-implementation fix:
 * derived from the calm `aiStateStore` connection authority, not the
 * store's raw per-frame `isConnected`).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './AdvisoryRailBadge.js';
import type { AdvisoryRailBadge } from './AdvisoryRailBadge.js';
import type { AdvisoryListener, AdvisorySnapshot, AdvisoryStore } from './AdvisoryStore.js';
import {
  __feedForTest,
  __resetAiStateForTest,
  __tickClockForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';

// aiStateStore's listener fan-out is microtask-batched (see aiStateStore.test.ts) — a subscription
// callback fired by a signal mutation is not observable until the microtask queue drains.
const microtask = () => new Promise<void>((r) => queueMicrotask(() => r()));

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

/** Drives aiStateStore's phase to 'connected' (a real poll success, t0). */
function establishConnected(t0: number): void {
  vi.setSystemTime(t0);
  __feedForTest({
    status: { worker: { core: { indexedDocuments: 1 } } } as unknown as StatusSnapshot,
  });
  __tickClockForTest();
}

/** Advances past the 15s staleness threshold without a fresh poll — phase -> 'stale'. */
function advanceToStale(t0: number): void {
  vi.setSystemTime(t0 + 16_000);
  __tickClockForTest();
}

describe('AdvisoryRailBadge', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });

  afterEach(() => {
    __resetAiStateForTest();
    vi.useRealTimers();
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

  it('Group B6 — renders stale-feed dot when aiStateStore.phase is stale', async () => {
    vi.useFakeTimers();
    const t0 = new Date('2026-01-01T00:00:00Z').getTime();
    establishConnected(t0);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).toBeNull(); // connected at mount

    advanceToStale(t0);
    await microtask();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('button.disconnected')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('button')?.getAttribute('aria-label')).toContain(
      'offline',
    );
  });

  it('Group B6 — hides stale-feed dot once aiStateStore.phase is connected again', async () => {
    vi.useFakeTimers();
    const t0 = new Date('2026-01-01T00:00:00Z').getTime();
    establishConnected(t0);
    advanceToStale(t0);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).not.toBeNull();

    // A fresh poll success brings phase back to 'connected'.
    establishConnected(t0 + 16_000);
    await microtask();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).toBeNull();
    expect(el.shadowRoot?.querySelector('button.disconnected')).toBeNull();
  });

  it('tempdoc 662 regression — a momentary raw isConnected=false from AdvisoryStore does NOT trigger the stale visual while aiStateStore.phase stays connected', async () => {
    // This is the actual bug this fix closes: the multiplexer's late-subscribe reconnect flips
    // AdvisoryStore's raw isConnected false/true within milliseconds on a routine, healthy boot.
    // The badge must not react to that raw flag directly.
    vi.useFakeTimers();
    const t0 = new Date('2026-01-01T00:00:00Z').getTime();
    establishConnected(t0);
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;

    store.push({ isConnected: false }); // the raw per-frame flag momentarily drops
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).toBeNull();
    expect(el.shadowRoot?.querySelector('button.disconnected')).toBeNull();

    store.push({ isConnected: true }); // and recovers, as it does on a real reconnect
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).toBeNull();
  });

  it('renders no chrome when store is null', async () => {
    const el = make(null);
    await el.updateComplete;
    // Element still renders the button; no AdvisoryStore subscription is active, so
    // unreadCount stays 0. The stale-feed dot is independent of `store` — it follows
    // aiStateStore's phase (constructor default: 'connecting', not yet stale).
    expect(el.unreadCount).toBe(0);
    expect(el.shadowRoot?.querySelector('.disconnected-dot')).toBeNull();
  });
});
