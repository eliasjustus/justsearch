// @vitest-environment happy-dom

/**
 * Slice 477 H2.3 — TrustChannel tests (StubTrustChannel +
 * RemoteTrustChannel).
 *
 * Verifies the architectural keystone (478 §4.D) that trust
 * verdicts are produced by TrustChannel implementations, never
 * decided inline by registry / loader / Settings UI.
 */

import { describe, expect, it, vi } from 'vitest';
import {
  StubTrustChannel,
  RemoteTrustChannel,
  FirstPartyTrustChannel,
  type TrustChannel,
  type TrustEvidence,
} from './TrustChannel.js';

const sampleEvidence: TrustEvidence = {
  source: '({ id: "test", version: "1.0.0", contractVersion: "1.1", tagNamespace: "test", capabilities: {}, register: () => {} })',
  signature: undefined,
  url: 'plugin://test',
};

describe('StubTrustChannel', () => {
  it('always returns UNTRUSTED_PLUGIN regardless of input', async () => {
    const verdict = await StubTrustChannel.verify(sampleEvidence);
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
    expect(verdict.identity).toBeNull();
    expect(verdict.explanation).toContain('V1.5.1 alpha');
  });

  it('returns UNTRUSTED even when a signature is present', async () => {
    const verdict = await StubTrustChannel.verify({
      ...sampleEvidence,
      signature: 'fake-cosign-bundle',
    });
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
  });
});

describe('RemoteTrustChannel — backend stub responses', () => {
  it('returns TRUSTED_PLUGIN when backend says verified=true', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ verified: true, identity: 'github.com/acme', reason: 'verified' }),
        { status: 200 },
      ),
    );
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('TRUSTED_PLUGIN');
    expect(verdict.identity).toBe('github.com/acme');
    expect(verdict.explanation).toBe('verified');
  });

  it('returns UNTRUSTED when backend says verified=false', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({ verified: false, reason: 'V1.5.1 alpha stub' }),
        { status: 200 },
      ),
    );
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
    expect(verdict.explanation).toContain('V1.5.1 alpha stub');
  });

  it('returns UNTRUSTED on backend network failure (does not throw)', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('connection refused'));
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
    expect(verdict.explanation).toContain('backend unreachable');
  });

  it('returns UNTRUSTED on non-2xx response', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('Internal Server Error', { status: 500 }),
    );
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
    expect(verdict.explanation).toContain('500');
  });

  it('returns UNTRUSTED when response is malformed JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response('not JSON', { status: 200 }),
    );
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
    expect(verdict.explanation).toContain('not JSON');
  });

  it('returns UNTRUSTED when response missing required `verified` field', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: 'ok' }), { status: 200 }),
    );
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('UNTRUSTED_PLUGIN');
    expect(verdict.explanation).toContain('verified');
  });

  it('sends artifactSha256 + signature + url in the body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ verified: false, reason: 'stub' }), { status: 200 }),
    );
    const ch = new RemoteTrustChannel('http://test', fetchMock);
    await ch.verify({
      source: 'hello',
      signature: 'sig123',
      url: 'plugin://x',
    });
    expect(fetchMock).toHaveBeenCalledWith(
      'http://test/api/plugins/verify',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
    const callBody = JSON.parse(fetchMock.mock.calls[0]![1]!.body as string);
    expect(callBody.artifactSha256).toMatch(/^[0-9a-f]{64}$/);
    expect(callBody.signature).toBe('sig123');
    expect(callBody.url).toBe('plugin://x');
  });

  it('invokes fetch with the global realm as receiver, never the channel instance (560 §28 Illegal-invocation regression)', async () => {
    // The bug it guards: a native `fetch` stored as an instance field and then called as
    // `this.fetchImpl(...)` is invoked with `this` = the RemoteTrustChannel instance, which the
    // browser rejects with "TypeError: Illegal invocation". verify()'s catch swallows the throw →
    // the verify POST never leaves the page and EVERY plugin is forced UNTRUSTED (the operator
    // allowlist can never take effect). The pre-existing tests all injected `vi.fn()` doubles,
    // which tolerate any receiver, so none exercised this. happy-dom's fetch likewise does not
    // enforce the Window-receiver rule, so the receiver-binding is asserted structurally — the
    // only environment-portable way to pin the regression.
    let capturedThis: unknown = Symbol('unset');
    const recordingFetch = function (this: unknown): Promise<Response> {
      capturedThis = this;
      return Promise.resolve(
        new Response(JSON.stringify({ verified: true, reason: 'ok' }), { status: 200 }),
      );
    } as unknown as typeof fetch;
    const ch = new RemoteTrustChannel('http://test', recordingFetch);
    const verdict = await ch.verify(sampleEvidence);
    expect(verdict.tier).toBe('TRUSTED_PLUGIN');
    // Bound to the global realm; with the bug (unbound field) this would have been `ch`.
    expect(capturedThis).toBe(globalThis);
    expect(capturedThis).not.toBe(ch);
  });
});

describe('FirstPartyTrustChannel (560 §23)', () => {
  const marked: TrustEvidence = {
    source: '/* @justsearch-first-party: token-editor */ ({ id: "token-editor" })',
    signature: undefined,
    url: 'http://localhost:3001/plugin.js',
  };
  const unmarked: TrustEvidence = {
    source: '({ id: "third-party" })',
    signature: undefined,
    url: 'http://evil.example/plugin.js',
  };

  it('grants TRUSTED_PLUGIN in dev for a first-party-marked source', async () => {
    const ch = new FirstPartyTrustChannel(StubTrustChannel, true);
    const v = await ch.verify(marked);
    expect(v.tier).toBe('TRUSTED_PLUGIN');
    expect(v.identity).toBe('token-editor');
    expect(v.explanation).toContain('first-party');
  });

  it('delegates to the fallback (UNTRUSTED) for an unmarked source in dev', async () => {
    const ch = new FirstPartyTrustChannel(StubTrustChannel, true);
    const v = await ch.verify(unmarked);
    expect(v.tier).toBe('UNTRUSTED_PLUGIN');
  });

  it('does NOT honor a marker that appears beyond the leading window (anchor; 560 §24)', async () => {
    const ch = new FirstPartyTrustChannel(StubTrustChannel, true);
    const deepMarker: TrustEvidence = {
      // marker buried far past the 512-char leading window — must not grant trust
      source: `/* ${'x'.repeat(700)} */\n/* @justsearch-first-party: sneaky */ ({ id: "sneaky" })`,
      signature: undefined,
      url: 'http://evil.example/plugin.js',
    };
    const v = await ch.verify(deepMarker);
    expect(v.tier).toBe('UNTRUSTED_PLUGIN');
  });

  it('NEVER grants trust in a production build — pure pass-through even for a marked source', async () => {
    const ch = new FirstPartyTrustChannel(StubTrustChannel, false);
    const v = await ch.verify(marked);
    expect(v.tier).toBe('UNTRUSTED_PLUGIN');
  });

  it('does not swallow a TRUSTED backend verdict for an unmarked source', async () => {
    const trustedFallback: TrustChannel = {
      async verify() {
        return { tier: 'TRUSTED_PLUGIN', explanation: 'backend ok', identity: 'acme' };
      },
    };
    const ch = new FirstPartyTrustChannel(trustedFallback, true);
    const v = await ch.verify(unmarked);
    expect(v.tier).toBe('TRUSTED_PLUGIN');
    expect(v.identity).toBe('acme');
  });
});
