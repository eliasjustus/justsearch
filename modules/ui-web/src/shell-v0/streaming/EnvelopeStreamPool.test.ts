// @vitest-environment happy-dom

/**
 * Tempdoc 508-followup §γ3 — EnvelopeStreamPool tests.
 *
 * Verifies the multiplex contract: N subscribers to the same URL
 * share one underlying EventSource; the last unsubscribe disposes
 * the stream; different URLs get distinct streams.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  subscribePooled,
  __resetEnvelopeStreamPoolForTest,
  __poolSizeForTest,
} from './EnvelopeStreamPool.js';

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  static reset() { FakeEventSource.instances = []; }
  readonly url: string;
  closed = false;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  // EnvelopeStream calls addEventListener('frame', ...) — emulate the
  // minimal surface we need (no real frames in these tests).
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  addEventListener(): void {}
  removeEventListener(): void {}
  close(): void {
    this.closed = true;
  }
}

beforeEach(() => {
  __resetEnvelopeStreamPoolForTest();
  FakeEventSource.reset();
});

function configFor(url: string) {
  return () => ({
    url,
    initialState: null,
    reducer: (s: unknown, _f: unknown) => s,
    eventSourceFactory: (u: string) =>
      new FakeEventSource(u) as unknown as EventSource,
  });
}

describe('EnvelopeStreamPool (tempdoc 508-followup §γ3)', () => {
  it('first subscriber creates one EventSource', () => {
    const unsub = subscribePooled('/a', vi.fn(), configFor('/a'));
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(__poolSizeForTest()).toBe(1);
    unsub();
  });

  it('second subscriber attaches to the existing EventSource', () => {
    const unsub1 = subscribePooled('/a', vi.fn(), configFor('/a'));
    const unsub2 = subscribePooled('/a', vi.fn(), configFor('/a'));
    expect(FakeEventSource.instances).toHaveLength(1);
    expect(__poolSizeForTest()).toBe(1);
    unsub1();
    unsub2();
  });

  it('distinct URLs get distinct EventSources', () => {
    subscribePooled('/a', vi.fn(), configFor('/a'));
    subscribePooled('/b', vi.fn(), configFor('/b'));
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(__poolSizeForTest()).toBe(2);
  });

  it('unsubscribing one of two leaves the EventSource open', () => {
    const unsub1 = subscribePooled('/a', vi.fn(), configFor('/a'));
    subscribePooled('/a', vi.fn(), configFor('/a'));
    unsub1();
    expect(FakeEventSource.instances[0]!.closed).toBe(false);
    expect(__poolSizeForTest()).toBe(1);
  });

  it('unsubscribing the last subscriber closes the EventSource', () => {
    const unsub1 = subscribePooled('/a', vi.fn(), configFor('/a'));
    const unsub2 = subscribePooled('/a', vi.fn(), configFor('/a'));
    unsub1();
    unsub2();
    expect(FakeEventSource.instances[0]!.closed).toBe(true);
    expect(__poolSizeForTest()).toBe(0);
  });

  it('subscribing again after full disposal creates a new EventSource', () => {
    const unsub1 = subscribePooled('/a', vi.fn(), configFor('/a'));
    unsub1();
    subscribePooled('/a', vi.fn(), configFor('/a'));
    expect(FakeEventSource.instances).toHaveLength(2);
  });

  it('idempotent unsubscribe does not double-decrement', () => {
    const unsub1 = subscribePooled('/a', vi.fn(), configFor('/a'));
    const unsub2 = subscribePooled('/a', vi.fn(), configFor('/a'));
    unsub1();
    unsub1(); // calling again should be a no-op for the pool
    expect(__poolSizeForTest()).toBe(1);
    unsub2();
    expect(__poolSizeForTest()).toBe(0);
  });
});
