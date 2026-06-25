// @vitest-environment happy-dom

/**
 * Slice 477 H2.5 — V1.5 substrate verification under SES lockdown.
 *
 * Why this exists, and why it's a SEPARATE FILE from the rest of
 * the substrate tests:
 *
 * The originally-planned H2.5 ("vitest setup file that calls
 * lockdown before all tests") proved infeasible. Lockdown freezes
 * `Date`, which is incompatible with happy-dom's Date implementation
 * AND with vitest's fake-timer machinery. Running the full suite
 * under lockdown produces 34 of 66 file failures — none of which
 * are substrate bugs; all are vitest internals that depend on
 * mutable Date.
 *
 * Critical-analysis pivot (per CLAUDE.md "structural fix over quick
 * fix"): rather than fight vitest's lifecycle, we ship a FOCUSED
 * test file that:
 *   1. Imports SES first.
 *   2. Calls `lockdown()` with the production taming options.
 *   3. Verifies the V1.5 substrate (Compartment + Loader + tier
 *      attenuation + isolation) works correctly in the locked-down
 *      realm.
 *
 * This proves what V1.5.2 default-on lockdown promises: the
 * substrate continues to function under lockdown. It does NOT
 * prove every line of FE code works under lockdown — but the
 * substrate is the security-critical surface.
 *
 * Future regressions: any change to PluginLoader / PluginCompartment
 * / PluginCapabilityBundle / PluginRegistry that breaks lockdown
 * compatibility will fail in this file. CI runs it.
 *
 * Wire: `npm run test:unit:lockdown` runs only this file. The
 * default `test:unit:run` excludes it (it's named `*.lockdown.test.ts`
 * via vitest config).
 */

// CRITICAL: SES + lockdown must run BEFORE any other import that
// touches Date (happy-dom's setup, lit, etc.). The static import is
// hoisted to the top by the JS module evaluation order.
import 'ses';
lockdown({
  errorTaming: 'unsafe',
  consoleTaming: 'unsafe',
  domainTaming: 'unsafe',
  overrideTaming: 'severe',
});

import { describe, expect, it } from 'vitest';
import { loadPluginFromUrl, type SourceFetcher } from './PluginLoader.js';
import { PluginRegistry } from './PluginRegistry.js';
import { buildCapabilityBundle } from './PluginCapabilityBundle.js';
import { buildDefaultEndowments } from './PluginCompartment.js';
import type { TrustChannel } from './TrustChannel.js';

function fetcherOf(source: string): SourceFetcher {
  return async () => source;
}

/**
 * 478 §4.D: trust verdicts come from a TrustChannel, not from
 * caller-asserted tier. Tests that need the TRUSTED tier path
 * pass a mock channel whose verify() returns TRUSTED. The
 * default StubTrustChannel returns UNTRUSTED.
 */
const TRUSTED_MOCK: TrustChannel = {
  verify: async () => ({
    tier: 'TRUSTED_PLUGIN' as const,
    explanation: 'mock trust channel: substrate-lockdown test',
    identity: 'test',
  }),
};

describe('substrate under lockdown — Compartment isolation', () => {
  it('Compartment construction works after lockdown', () => {
    const c = new Compartment({ host: 42 });
    const result = c.evaluate('host + 1');
    expect(result).toBe(43);
  });

  it('Compartment.evaluate cannot reach host browser globals', () => {
    const c = new Compartment({});
    // Browser globals not endowed → not visible.
    expect(c.evaluate('typeof window')).toBe('undefined');
    expect(c.evaluate('typeof fetch')).toBe('undefined');
    expect(c.evaluate('typeof document')).toBe('undefined');
    // SES makes `Compartment` available in compartments by design
    // (nested compartments). That's not a leak; it's the SES contract.
    expect(c.evaluate('typeof Compartment')).toBe('function');
  });

  it('lockdown freezes intrinsics: Array.prototype.push not mutable', () => {
    expect(() => {
      (Array.prototype as { push: unknown }).push = () => 999;
    }).toThrow();
  });
});

describe('substrate under lockdown — Plugin loader', () => {
  it('loadPluginFromUrl works under lockdown (manifest object source)', async () => {
    const registry = new PluginRegistry();
    const source = `
      ({
        id: 'lockdown.test',
        version: '1.0.0',
        displayName: 'Lockdown Test',
        contractVersion: '1.1',
        tagNamespace: 'lockdown.test',
        capabilities: {},
        register: () => {},
      })
    `;
    const m = await loadPluginFromUrl(registry, 'plugin://lockdown', {
      sourceFetcher: fetcherOf(source),
      tier: 'TRUSTED_PLUGIN',
      expectedPluginId: 'lockdown.test',
    });
    expect(m.id).toBe('lockdown.test');
    expect(registry.has('lockdown.test')).toBe(true);
  });

  it('loadPluginFromUrl works under lockdown (factory shape)', async () => {
    const registry = new PluginRegistry();
    const source = `
      (() => ({
        id: 'lockdown.factory',
        version: '1.0.0',
        displayName: 'Factory',
        contractVersion: '1.1',
        tagNamespace: 'lockdown.factory',
        capabilities: {},
        register: () => {},
      }))
    `;
    const m = await loadPluginFromUrl(registry, 'plugin://factory', {
      sourceFetcher: fetcherOf(source),
      tier: 'TRUSTED_PLUGIN',
      expectedPluginId: 'lockdown.factory',
    });
    expect(m.id).toBe('lockdown.factory');
  });
});

describe('substrate under lockdown — Tier attenuation', () => {
  it('UNTRUSTED tier scopes localStorage under lockdown', async () => {
    const registry = new PluginRegistry();
    const source = `
      (({ localStorage }) => {
        localStorage.setItem('lk-key', 'lk-value');
        return {
          id: 'lk.untrusted',
          version: '1.0.0',
          displayName: 'LK',
          contractVersion: '1.1',
          tagNamespace: 'lk.untrusted',
          capabilities: {},
          register: () => {},
        };
      })
    `;
    await loadPluginFromUrl(registry, 'plugin://lk', {
      sourceFetcher: fetcherOf(source),
      tier: 'UNTRUSTED_PLUGIN',
      expectedPluginId: 'lk.untrusted',
    });
    expect(localStorage.getItem('lk-key')).toBeNull();
    expect(localStorage.getItem('plugin:lk.untrusted:lk-key')).toBe('lk-value');
    // Cleanup
    localStorage.removeItem('plugin:lk.untrusted:lk-key');
  });

  it('TRUSTED tier preserves raw localStorage access under lockdown', async () => {
    const registry = new PluginRegistry();
    const source = `
      (({ localStorage }) => {
        localStorage.setItem('lk-trusted', 'raw');
        return {
          id: 'lk.trusted',
          version: '1.0.0',
          displayName: 'LK Trusted',
          contractVersion: '1.1',
          tagNamespace: 'lk.trusted',
          capabilities: {},
          register: () => {},
        };
      })
    `;
    // 478 §4.D: TRUSTED tier comes from TrustChannel verdict, not
    // caller-asserted `tier`. Pass a mock channel that produces
    // TRUSTED so the loader uses the unattenuated bundle.
    await loadPluginFromUrl(registry, 'plugin://lk-trusted', {
      sourceFetcher: fetcherOf(source),
      trustChannel: TRUSTED_MOCK,
      expectedPluginId: 'lk.trusted',
    });
    expect(localStorage.getItem('lk-trusted')).toBe('raw');
    localStorage.removeItem('lk-trusted');
  });
});

describe('substrate under lockdown — Endowment helpers', () => {
  it('buildDefaultEndowments works under lockdown', () => {
    const e = buildDefaultEndowments();
    expect(e['host']).toBeNull();
    expect(typeof e['customElements']).toBe('object');
    expect(typeof e['HTMLElement']).toBe('function');
    expect(typeof e['localStorage']).toBe('object');
  });

  it('buildCapabilityBundle differentiates tiers under lockdown', () => {
    const trustedBundle = buildCapabilityBundle('TRUSTED_PLUGIN', 'x');
    const untrustedBundle = buildCapabilityBundle('UNTRUSTED_PLUGIN', 'x');
    // Trusted bundle uses raw browser globals (same instance reference).
    expect(trustedBundle['localStorage']).toBe(globalThis.localStorage);
    // Untrusted bundle wraps in a scoped facade (NOT the same reference).
    expect(untrustedBundle['localStorage']).not.toBe(globalThis.localStorage);
  });

  it('UNTRUSTED tier omits the document endowment entirely — F1, no window re-grant', () => {
    const trustedBundle = buildCapabilityBundle('TRUSTED_PLUGIN', 'x');
    const untrustedBundle = buildCapabilityBundle('UNTRUSTED_PLUGIN', 'x');
    // Trusted keeps the raw document (the window is reachable via defaultView).
    expect(trustedBundle['document']).toBe(globalThis.document);
    // Untrusted has NO `document` endowment at all (Fix C): there is no document to climb to the
    // window through — neither the direct `defaultView` path nor the transitive
    // `body.ownerDocument.defaultView` one a facade could not have closed.
    expect('document' in untrustedBundle).toBe(false);
    expect(untrustedBundle['document']).toBeUndefined();
  });
});
