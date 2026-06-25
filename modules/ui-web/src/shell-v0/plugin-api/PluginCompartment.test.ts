// @vitest-environment happy-dom

// Slice 477 H2.6 — SES is now lazy-loaded by the loader; tests
// that construct Compartment synchronously must import SES
// themselves.
import 'ses';

/**
 * Tests for slice 466 SES Compartment factory.
 *
 * V1.5 alpha scope: verify the Compartment construction + endowment
 * + isolation properties. We do NOT call lockdown() globally
 * (deferred to V1.5.1) so this is a "compartment-only" verification.
 *
 * Verifications:
 *  - Compartment global available after `import 'ses'`
 *  - createPluginCompartment returns a Compartment
 *  - endowments are visible inside compartment-evaluated code
 *  - host globals (window, document, fetch) are NOT visible inside
 *    the compartment unless explicitly endowed
 *  - two compartments don't share globals (plugin-A can't see plugin-B)
 */

import { describe, expect, it } from 'vitest';
import {
  createPluginCompartment,
  isSesAvailable,
} from './PluginCompartment.js';

describe('PluginCompartment (slice 466)', () => {
  it('SES is available after import', () => {
    expect(isSesAvailable()).toBe(true);
  });

  it('createPluginCompartment returns a Compartment instance', () => {
    const c = createPluginCompartment({ host: {} });
    expect(c).toBeInstanceOf(Compartment);
  });

  it('endowed values are visible inside compartment code', () => {
    const host = { ping: () => 'pong' };
    const c = createPluginCompartment({ host });
    // Compartment.evaluate runs source in the compartment realm.
    const result = c.evaluate('host.ping()');
    expect(result).toBe('pong');
  });

  it('host globals (window, document) are NOT visible inside compartment by default', () => {
    const c = createPluginCompartment({ host: {} });
    // Reading a non-endowed global throws ReferenceError inside the
    // compartment realm.
    expect(() => c.evaluate('typeof window')).not.toThrow();
    // typeof returns 'undefined' for a missing identifier rather than
    // throwing, but accessing the value directly throws.
    const typeofWindow = c.evaluate('typeof window');
    expect(typeofWindow).toBe('undefined');

    const typeofDocument = c.evaluate('typeof document');
    expect(typeofDocument).toBe('undefined');

    const typeofFetch = c.evaluate('typeof fetch');
    expect(typeofFetch).toBe('undefined');
  });

  it('two compartments do not share globals', () => {
    const a = createPluginCompartment({ host: { name: 'A' } });
    const b = createPluginCompartment({ host: { name: 'B' } });
    expect(a.evaluate('host.name')).toBe('A');
    expect(b.evaluate('host.name')).toBe('B');
    // Mutating a's globalThis must not affect b.
    a.evaluate('globalThis.leaked = "from-A"');
    expect(b.evaluate('typeof leaked')).toBe('undefined');
  });

  it('intrinsic types (Array, Object, Promise) are available inside compartment', () => {
    const c = createPluginCompartment({ host: {} });
    expect(c.evaluate('typeof Array')).toBe('function');
    expect(c.evaluate('typeof Object')).toBe('function');
    expect(c.evaluate('typeof Promise')).toBe('function');
    expect(c.evaluate('Array.isArray([1,2,3])')).toBe(true);
  });

  it('endowed console facade is callable', () => {
    let captured: string | null = null;
    const fakeConsole = {
      log: (msg: string) => {
        captured = msg;
      },
    };
    const c = createPluginCompartment({ host: {}, console: fakeConsole });
    c.evaluate('console.log("hello from plugin")');
    expect(captured).toBe('hello from plugin');
  });
});
