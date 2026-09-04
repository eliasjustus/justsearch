// @vitest-environment happy-dom
/** uiModeState — the app-wide Simple/Detailed authority (tempdoc 557 Q8). */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  enqueueUiModePersistence,
  getUiMode,
  getUiModeRevision,
  isAdvancedMode,
  setUiMode,
  subscribeUiMode,
  __resetUiModeForTest,
} from './uiModeState.js';

describe('uiModeState', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    __resetUiModeForTest();
  });

  it('defaults to simple (hide advanced affordances until known)', () => {
    expect(getUiMode()).toBe('simple');
    expect(isAdvancedMode()).toBe(false);
  });

  it('resolves only "advanced" to advanced; everything else is simple', () => {
    setUiMode('advanced');
    expect(isAdvancedMode()).toBe(true);
    setUiMode('simple');
    expect(isAdvancedMode()).toBe(false);
    setUiMode(undefined);
    expect(getUiMode()).toBe('simple');
    setUiMode('garbage');
    expect(getUiMode()).toBe('simple');
  });

  it('notifies subscribers on change (and once synchronously on subscribe)', () => {
    const seen: string[] = [];
    const unsub = subscribeUiMode((m) => seen.push(m));
    expect(seen).toEqual(['simple']); // sync on subscribe
    setUiMode('advanced');
    expect(seen).toEqual(['simple', 'advanced']);
    setUiMode('advanced'); // no-op (unchanged) → no extra notify
    expect(seen).toEqual(['simple', 'advanced']);
    unsub();
    setUiMode('simple');
    expect(seen).toEqual(['simple', 'advanced']); // unsubscribed
  });

  it('increments its revision only when the shared choice changes', () => {
    expect(getUiModeRevision()).toBe(0);
    setUiMode('simple');
    expect(getUiModeRevision()).toBe(0);
    setUiMode('advanced');
    expect(getUiModeRevision()).toBe(1);
    setUiMode('advanced');
    expect(getUiModeRevision()).toBe(1);
    setUiMode('simple');
    expect(getUiModeRevision()).toBe(2);
  });

  it('serializes persistence submitted by different mode projections', async () => {
    const started: string[] = [];
    const releases: Array<() => void> = [];
    const submit = (owner: string): Promise<void> =>
      enqueueUiModePersistence(
        () => new Promise<void>((resolve) => {
          started.push(owner);
          releases.push(resolve);
        }),
      );

    const brain = submit('brain');
    const topbar = submit('topbar');
    const settings = submit('settings');
    await vi.waitFor(() => expect(started).toEqual(['brain']));

    releases.shift()?.();
    await brain;
    await vi.waitFor(() => expect(started).toEqual(['brain', 'topbar']));

    releases.shift()?.();
    await topbar;
    await vi.waitFor(() => expect(started).toEqual(['brain', 'topbar', 'settings']));

    releases.shift()?.();
    await settings;
  });

  it('aborts a timed-out writer and advances the queue', async () => {
    let firstAborted = false;
    const started: string[] = [];
    const intents: string[] = [];
    const first = enqueueUiModePersistence(
      (signal, intent) => new Promise<void>((_resolve, reject) => {
        started.push('first');
        intents.push(intent);
        signal.addEventListener('abort', () => {
          firstAborted = true;
          reject(signal.reason);
        }, { once: true });
      }),
      { timeoutMs: 5 },
    );
    const second = enqueueUiModePersistence(async (_signal, intent) => {
      started.push('second');
      intents.push(intent);
    });

    await expect(first).rejects.toMatchObject({ name: 'TimeoutError' });
    await expect(second).resolves.toBeUndefined();
    expect(firstAborted).toBe(true);
    expect(started).toEqual(['first', 'second']);
    expect(intents.map((intent) => Number(intent.slice(intent.lastIndexOf(':') + 1))))
      .toEqual([1, 2]);
  });

  it('keeps one monotonic ordering domain across a shell reload', async () => {
    const intents: string[] = [];
    await enqueueUiModePersistence(async (_signal, intent) => {
      intents.push(intent);
    });

    __resetUiModeForTest({ preservePersistence: true });
    await enqueueUiModePersistence(async (_signal, intent) => {
      intents.push(intent);
    });

    expect(intents).toHaveLength(2);
    const [beforeReload, afterReload] = intents.map((intent) => {
      const separator = intent.lastIndexOf(':');
      return {
        clientId: intent.slice(0, separator),
        sequence: Number(intent.slice(separator + 1)),
      };
    });
    expect(afterReload!.clientId).toBe(beforeReload!.clientId);
    expect(afterReload!.sequence).toBe(beforeReload!.sequence + 1);
  });

  it('times out a blocked cross-context sequence lock and advances the queue', async () => {
    let lockRequest = 0;
    const request = vi.fn((
      _name: string,
      options: { signal: AbortSignal },
      callback: () => string,
    ): Promise<string> => {
      lockRequest += 1;
      if (lockRequest > 1) return Promise.resolve(callback());
      return new Promise((_resolve, reject) => {
        options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true });
      });
    });
    vi.stubGlobal('navigator', { locks: { request } });

    const firstWriter = vi.fn(async () => undefined);
    const secondWriter = vi.fn(async () => undefined);
    const first = enqueueUiModePersistence(firstWriter, { timeoutMs: 5 });
    const second = enqueueUiModePersistence(secondWriter, { timeoutMs: 50 });

    await expect(first).rejects.toMatchObject({ name: 'TimeoutError' });
    await expect(second).resolves.toBeUndefined();
    expect(firstWriter).not.toHaveBeenCalled();
    expect(secondWriter).toHaveBeenCalledOnce();
  });
});
