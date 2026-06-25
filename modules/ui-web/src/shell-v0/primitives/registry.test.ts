/**
 * Registry primitive — Tempdoc 543 §28.W7 tests.
 */

import { describe, expect, it, vi } from 'vitest';
import { createRegistry } from './registry.js';

interface TestEntry {
  readonly id: string;
  readonly label: string;
}

describe('createRegistry (§28.W7)', () => {
  it('register stores by id; list returns all entries', () => {
    const r = createRegistry<TestEntry>();
    r.register({ id: 'a', label: 'A' });
    r.register({ id: 'b', label: 'B' });
    expect(r.list()).toHaveLength(2);
  });

  it('register replaces by id (idempotent on repeat id)', () => {
    const r = createRegistry<TestEntry>();
    r.register({ id: 'a', label: 'first' });
    r.register({ id: 'a', label: 'second' });
    expect(r.list()).toHaveLength(1);
    expect(r.get('a')?.label).toBe('second');
  });

  it('unregister removes by id; returns true on hit, false on miss', () => {
    const r = createRegistry<TestEntry>();
    r.register({ id: 'a', label: 'A' });
    expect(r.unregister('a')).toBe(true);
    expect(r.unregister('a')).toBe(false);
    expect(r.list()).toHaveLength(0);
  });

  it('get returns undefined for unknown id', () => {
    const r = createRegistry<TestEntry>();
    expect(r.get('nope')).toBeUndefined();
  });

  it('subscribe fires on register + unregister; returns unsubscribe fn', () => {
    const r = createRegistry<TestEntry>();
    const listener = vi.fn();
    const unsub = r.subscribe(listener);
    r.register({ id: 'a', label: 'A' });
    r.unregister('a');
    expect(listener).toHaveBeenCalledTimes(2);
    unsub();
    r.register({ id: 'b', label: 'B' });
    expect(listener).toHaveBeenCalledTimes(2); // no further calls after unsub
  });

  it('subscribe swallows listener errors so one bad subscriber does not break the loop', () => {
    const r = createRegistry<TestEntry>();
    const good = vi.fn();
    r.subscribe(() => {
      throw new Error('bad subscriber');
    });
    r.subscribe(good);
    r.register({ id: 'a', label: 'A' });
    expect(good).toHaveBeenCalledTimes(1);
  });

  it('onRegister hook fires after map mutation, before listeners', () => {
    const events: string[] = [];
    const r = createRegistry<TestEntry>({
      onRegister: (item) => events.push(`hook:${item.id}`),
    });
    r.subscribe(() => events.push('listener'));
    r.register({ id: 'a', label: 'A' });
    expect(events).toEqual(['hook:a', 'listener']);
  });

  it('onUnregister hook fires with item before listeners', () => {
    const events: string[] = [];
    const r = createRegistry<TestEntry>({
      onUnregister: (id, item) => events.push(`hook:${id}:${item.label}`),
    });
    r.register({ id: 'a', label: 'A' });
    r.subscribe(() => events.push('listener'));
    r.unregister('a');
    expect(events).toEqual(['hook:a:A', 'listener']);
  });

  it('__resetForTest clears entries + listeners', () => {
    const r = createRegistry<TestEntry>();
    const listener = vi.fn();
    r.subscribe(listener);
    r.register({ id: 'a', label: 'A' });
    r.__resetForTest();
    expect(r.list()).toHaveLength(0);
    r.register({ id: 'b', label: 'B' });
    expect(listener).toHaveBeenCalledTimes(1); // pre-reset only
  });

  it('_map escape hatch + __notify lets batch mutations fire one notification', () => {
    const r = createRegistry<TestEntry>();
    const listener = vi.fn();
    r.subscribe(listener);
    r._map.set('a', { id: 'a', label: 'A' });
    r._map.set('b', { id: 'b', label: 'B' });
    r._map.set('c', { id: 'c', label: 'C' });
    expect(listener).not.toHaveBeenCalled();
    r.__notify();
    expect(listener).toHaveBeenCalledTimes(1);
    expect(r.list()).toHaveLength(3);
  });
});
