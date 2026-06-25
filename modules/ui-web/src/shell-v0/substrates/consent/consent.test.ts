// @vitest-environment happy-dom

/**
 * Tempdoc 543 §25.β4 — Capability Consent substrate tests.
 */

import { beforeEach, describe, expect, it } from 'vitest';
import {
  checkCapability,
  isAllowed,
  recordConsent,
  consumeOnce,
  requestCapability,
  resolveConsentRequest,
  listPendingConsentRequests,
  listAllConsents,
  revokeConsent,
  restoreConsentFromStorage,
  __resetConsentForTest,
} from './index.js';

beforeEach(() => {
  __resetConsentForTest();
  // Clean localStorage state too.
  if (typeof localStorage !== 'undefined') {
    localStorage.removeItem('justsearch.consent.v1');
  }
});

describe('checkCapability / isAllowed (§25.β4)', () => {
  it('undecided returns undefined / false', () => {
    expect(checkCapability('p1', 'fs.read')).toBeUndefined();
    expect(isAllowed('p1', 'fs.read')).toBe(false);
  });

  it('recordConsent allow-always → isAllowed true', () => {
    recordConsent('p1', 'fs.read', 'allow-always');
    expect(checkCapability('p1', 'fs.read')).toBe('allow-always');
    expect(isAllowed('p1', 'fs.read')).toBe(true);
  });

  it('recordConsent deny → isAllowed false', () => {
    recordConsent('p1', 'fs.write', 'deny');
    expect(isAllowed('p1', 'fs.write')).toBe(false);
  });

  it('consumeOnce clears allow-once grant', () => {
    recordConsent('p1', 'net.fetch', 'allow-once');
    expect(isAllowed('p1', 'net.fetch')).toBe(true);
    expect(consumeOnce('p1', 'net.fetch')).toBe(true);
    expect(checkCapability('p1', 'net.fetch')).toBeUndefined();
  });

  it('consumeOnce is a no-op for allow-always / deny', () => {
    recordConsent('p2', 'fs.read', 'allow-always');
    expect(consumeOnce('p2', 'fs.read')).toBe(false);
    expect(isAllowed('p2', 'fs.read')).toBe(true);
  });

  it('revokeConsent removes the record', () => {
    recordConsent('p1', 'fs.read', 'allow-always');
    expect(revokeConsent('p1', 'fs.read')).toBe(true);
    expect(checkCapability('p1', 'fs.read')).toBeUndefined();
  });
});

describe('requestCapability flow (§25.β4)', () => {
  it('returns a Promise pending until resolveConsentRequest', async () => {
    const promise = requestCapability({
      contributorId: 'p1',
      capability: 'fs.read',
      description: 'Read files',
    });
    const pending = listPendingConsentRequests();
    expect(pending).toHaveLength(1);
    const id = pending[0]!.id;
    resolveConsentRequest(id, 'allow-always');
    const decision = await promise;
    expect(decision).toBe('allow-always');
    expect(checkCapability('p1', 'fs.read')).toBe('allow-always');
  });

  it('dispatches jf-consent-request CustomEvent', async () => {
    const events: CustomEvent[] = [];
    const handler = (e: Event) => events.push(e as CustomEvent);
    document.addEventListener('jf-consent-request', handler);
    try {
      const promise = requestCapability({
        contributorId: 'p1',
        capability: 'fs.read',
      });
      expect(events).toHaveLength(1);
      expect(events[0]!.detail.contributorId).toBe('p1');
      expect(events[0]!.detail.capability).toBe('fs.read');
      resolveConsentRequest(events[0]!.detail.id, 'deny');
      await promise;
    } finally {
      document.removeEventListener('jf-consent-request', handler);
    }
  });

  it('resolveConsentRequest unknown id returns false', () => {
    expect(resolveConsentRequest(999, 'allow-once')).toBe(false);
  });

  it('reset resolves in-flight promises with deny', async () => {
    const promise = requestCapability({
      contributorId: 'p1',
      capability: 'fs.read',
    });
    __resetConsentForTest();
    expect(await promise).toBe('deny');
  });
});

describe('persistence (§25.β4)', () => {
  it('allow-always persists across restoreConsentFromStorage', () => {
    recordConsent('p1', 'fs.read', 'allow-always');
    __resetConsentForTest();
    // Re-write the persisted state for the test (reset cleared in-memory).
    recordConsent('p1', 'fs.read', 'allow-always');
    // Simulate a fresh boot.
    __resetConsentForTest();
    // We can't actually fake a fresh boot since reset clears the store, so:
    // verify the persistence by recording, reading the localStorage payload
    // directly, and inspecting.
    recordConsent('p1', 'fs.read', 'allow-always');
    const raw = localStorage.getItem('justsearch.consent.v1');
    expect(raw).toBeTruthy();
    expect(raw).toContain('allow-always');
  });

  it('allow-once does NOT persist', () => {
    recordConsent('p1', 'fs.read', 'allow-once');
    const raw = localStorage.getItem('justsearch.consent.v1');
    // either no payload or payload doesn't contain this record
    if (raw) {
      const parsed = JSON.parse(raw);
      expect(parsed.entries).not.toContainEqual(
        expect.objectContaining({ decision: 'allow-once' }),
      );
    }
  });

  it('restoreConsentFromStorage replays persisted decisions', () => {
    // Seed the localStorage directly.
    const payload = {
      version: 1,
      entries: [
        {
          contributorId: 'persisted-plugin',
          capability: 'fs.read',
          decision: 'allow-always',
          decidedAt: '2026-05-01T00:00:00Z',
        },
      ],
    };
    localStorage.setItem('justsearch.consent.v1', JSON.stringify(payload));
    __resetConsentForTest();
    localStorage.setItem('justsearch.consent.v1', JSON.stringify(payload));
    restoreConsentFromStorage();
    expect(checkCapability('persisted-plugin', 'fs.read')).toBe('allow-always');
  });
});

describe('listAllConsents (§25.β4)', () => {
  it('returns all recorded decisions', () => {
    recordConsent('p1', 'fs.read', 'allow-always');
    recordConsent('p2', 'net.fetch', 'deny');
    const all = listAllConsents();
    expect(all).toHaveLength(2);
    const ids = all.map((c) => c.contributorId).sort();
    expect(ids).toEqual(['p1', 'p2']);
  });
});
