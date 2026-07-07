// @vitest-environment happy-dom
//
// Tempdoc 663 §O — regression coverage for the live-reproduced "stuck on Connecting… forever" bug:
// BrainSurface's OLD one-shot `refreshAll()` fetch had no retry, so a single transient failure of
// `/api/ai/install/status` permanently stranded `installStatus` at `null` with no self-correction.
// `aiInstallPoll` fixes this structurally (always-on, retry-forever, retain-last-good). These tests
// pin exactly that property: a failed first tick must NOT be the end of the story.
//
// Each poller tick issues THREE parallel fetches (install/runtime/packs) — tests below mock by URL
// and only assert on the install-status call count/values to keep the math legible.

import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  subscribeAiInstall,
  setAiInstallApiBase,
  __resetAiInstallPollForTest,
  type AiInstallSnapshot,
} from './aiInstallPoll.js';

function jsonResponse(body: unknown): Response {
  return { ok: true, json: async () => body } as Response;
}

/** Always-fail stand-in for the runtime/packs endpoints — irrelevant to these install-focused tests. */
async function alwaysFail(): Promise<Response> {
  throw new Error('not implemented in this test');
}

describe('aiInstallPoll — self-healing, always-on poller (tempdoc 663 §O regression)', () => {
  afterEach(() => {
    __resetAiInstallPollForTest();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('a failed first fetch does NOT permanently strand the signal — a later tick recovers', async () => {
    vi.useFakeTimers();
    let installCall = 0;
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/ai/install/status')) {
        installCall += 1;
        // First two ticks fail (the exact failure mode that stranded BrainSurface: the install-
        // status request fails on the first attempt(s)).
        if (installCall <= 2) throw new Error('network error');
        return jsonResponse({ state: 'idle', phase: 'idle', installedFully: false });
      }
      return alwaysFail();
    });
    vi.stubGlobal('fetch', fetchMock);

    setAiInstallApiBase('http://127.0.0.1:33221');
    const seen: AiInstallSnapshot[] = [];
    const unsub = subscribeAiInstall((snap) => seen.push(snap));

    // Eager first fetch (sync-on-subscribe value, before any network response lands).
    expect(seen[0]).toEqual({ install: null, runtime: null, packs: null });

    // Eager tick's request fails.
    await vi.advanceTimersByTimeAsync(0);
    expect(installCall).toBe(1);
    expect(seen.at(-1)!.install).toBeNull();

    // Second tick (1s later) also fails — this MUST NOT be a terminal state: no code path may stop
    // retrying just because prior attempts failed (that stop-retrying-after-failure gap is exactly
    // what stranded BrainSurface before this module existed).
    await vi.advanceTimersByTimeAsync(1000);
    expect(installCall).toBe(2);
    expect(seen.at(-1)!.install).toBeNull();

    // Third tick succeeds — the poller must pick it up on its own, with no manual refresh.
    await vi.advanceTimersByTimeAsync(1000);
    expect(installCall).toBe(3);
    expect(seen.at(-1)!.install).toEqual({ state: 'idle', phase: 'idle', installedFully: false });

    unsub();
  });

  it('retains the last-known-good value on a LATER transient failure (never regresses to null)', async () => {
    vi.useFakeTimers();
    let installCall = 0;
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/ai/install/status')) {
        installCall += 1;
        if (installCall === 1) return jsonResponse({ state: 'running', phase: 'downloading' });
        throw new Error('transient failure');
      }
      return alwaysFail();
    });
    vi.stubGlobal('fetch', fetchMock);

    setAiInstallApiBase('http://127.0.0.1:33221');
    const seen: Array<{ install: unknown }> = [];
    const unsub = subscribeAiInstall((snap) => seen.push({ install: snap.install }));

    await vi.advanceTimersByTimeAsync(0); // eager fetch succeeds
    expect(seen.at(-1)!.install).toEqual({ state: 'running', phase: 'downloading' });

    await vi.advanceTimersByTimeAsync(1000); // next tick fails
    // Must retain the last-known-good value, not regress to null — this is what lets a calm
    // "Installing…" render stay calm through a single bad poll instead of flashing to "Connecting…".
    expect(seen.at(-1)!.install).toEqual({ state: 'running', phase: 'downloading' });

    unsub();
  });

  it('the poller stops when the last subscriber unsubscribes, and resumes fresh for a new subscriber', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ state: 'idle', phase: 'idle' }));
    vi.stubGlobal('fetch', fetchMock);

    setAiInstallApiBase('http://127.0.0.1:33221');
    const unsub = subscribeAiInstall(() => {});
    await vi.advanceTimersByTimeAsync(0);
    const callsAfterOneTick = fetchMock.mock.calls.length; // 3 endpoints per tick
    expect(callsAfterOneTick).toBeGreaterThan(0);
    unsub();

    // No subscribers — no further ticks.
    await vi.advanceTimersByTimeAsync(9000);
    expect(fetchMock.mock.calls.length).toBe(callsAfterOneTick);

    // A fresh subscriber restarts the poller (eager fetch again).
    const unsub2 = subscribeAiInstall(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock.mock.calls.length).toBeGreaterThan(callsAfterOneTick);
    unsub2();
  });
});
