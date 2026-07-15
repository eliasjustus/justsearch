// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * Tempdoc 727 F-8 — regression test for the DATA PROTECTION row disagreeing with the CHAT
 * ENCRYPTION panel after a successful unlock.
 *
 * Root cause: `SecuritySurface.unlockEncryption()` (and the symmetric `lockEncryption()`) only
 * updated the LOCAL optimistic `encState` field from the unlock/lock POST's own response. The
 * DATA PROTECTION row (shared `renderAtRestCard`, also mounted on Health) derives its
 * "Conversations: … unlocked/locked" text from `this.status.conversationProtection.state` — the
 * aiStateStore's POLLED `/api/status` snapshot (`statusPoll.ts`, 10s `INTERVAL_MS`) — a DIFFERENT
 * truth source that only caught up on the next scheduled poll. Two rows on one screen disagreeing
 * about the same fact for up to a full poll interval.
 *
 * Fix: both transitions now call the aiStateStore's `refreshStatusNow()` on success, forcing an
 * immediate `/api/status` re-fetch so the polled snapshot catches up to the same truth the local
 * `encState` already reflects — a state-source fix, not a render-side timer/refresh hack.
 *
 * This test does NOT mount the element (no `connectedCallback`, matching the detached-harness
 * pattern already used by `SecuritySurface.test.ts`) — it drives the private transition methods
 * directly against a mocked `host_.data.fetch` and asserts the state-source refresh fires. On
 * pre-fix code, `refreshStatusNow` is never called and every assertion below FAILS.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
// vi.mock calls are hoisted above this import by Vitest's transform (same pattern as
// UnifiedChatView.test.ts), so SecuritySurface picks up the mocked aiStateStore module.
import './SecuritySurface.js';

const refreshStatusNow = vi.fn().mockResolvedValue(undefined);

vi.mock('../state/aiStateStore.js', () => ({
  subscribeAiState: vi.fn(() => () => {}),
  refreshStatusNow: (...args: unknown[]) => refreshStatusNow(...args),
}));

interface EncryptionHarness {
  host_: { data: { fetch: (path: string, init?: RequestInit) => Promise<Response> } };
  encState: string;
  unlockEncryption(passphrase: string): Promise<void>;
  lockEncryption(): Promise<void>;
}

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, json: async () => body } as Response;
}

function harness(): EncryptionHarness {
  // Detached custom element — no document.body.appendChild, so connectedCallback (and its real
  // subscribeAiState wiring) never runs; only the private transition methods are exercised. Mirrors
  // the existing SecuritySurface.test.ts `chatProtectionText` harness pattern.
  return document.createElement('jf-security-surface') as unknown as EncryptionHarness;
}

describe('SecuritySurface encryption transitions refresh the shared status snapshot — tempdoc 727 F-8', () => {
  afterEach(() => {
    refreshStatusNow.mockClear();
  });

  it('unlockEncryption() forces an immediate status refresh on success', async () => {
    const el = harness();
    el.host_ = { data: { fetch: vi.fn().mockResolvedValue(jsonResponse({ state: 'unlocked' })) } };
    await el.unlockEncryption('correct horse battery staple');
    expect(el.encState).toBe('unlocked');
    expect(refreshStatusNow).toHaveBeenCalledTimes(1);
  });

  it('does NOT force a refresh when the unlock request fails', async () => {
    const el = harness();
    el.host_ = {
      data: { fetch: vi.fn().mockResolvedValue(jsonResponse({ error: 'bad passphrase' }, false)) },
    };
    await el.unlockEncryption('wrong passphrase here');
    expect(refreshStatusNow).not.toHaveBeenCalled();
  });

  it('lockEncryption() forces the same immediate refresh on success (the symmetric transition)', async () => {
    const el = harness();
    el.host_ = { data: { fetch: vi.fn().mockResolvedValue(jsonResponse({ state: 'locked' })) } };
    await el.lockEncryption();
    expect(el.encState).toBe('locked');
    expect(refreshStatusNow).toHaveBeenCalledTimes(1);
  });

  it('does NOT force a refresh when the lock request fails', async () => {
    const el = harness();
    el.host_ = { data: { fetch: vi.fn().mockResolvedValue(jsonResponse({}, false)) } };
    await el.lockEncryption();
    expect(refreshStatusNow).not.toHaveBeenCalled();
  });
});
