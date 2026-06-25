import { afterEach, describe, expect, it, vi } from 'vitest';

import type { HostApiDeps } from './HostApiImpl.js';
import {
  KERNEL_SPECIFIERS,
  kernelEndowment,
  resolveKernelModules,
} from './KernelResolver.js';
import type { PluginDataAccess } from './plugin-types.js';

function deps(): HostApiDeps {
  return { apiBase: '', registerSurfacePort: () => {} };
}

function dataOf(modules: Record<string, { namespace: Record<string, unknown> }>): PluginDataAccess {
  return modules['@kernel/data']!.namespace.data as PluginDataAccess;
}

describe('KernelResolver — §4.2 @kernel/* resolver-time module substitution', () => {
  afterEach(() => vi.restoreAllMocks());

  it('resolves every @kernel/* specifier to a module namespace', () => {
    const modules = resolveKernelModules('p', 'TRUSTED_PLUGIN', deps());
    expect(Object.keys(modules).sort()).toEqual([...KERNEL_SPECIFIERS].sort());
    for (const spec of KERNEL_SPECIFIERS) {
      // default + named export both present.
      expect(modules[spec].namespace.default).toBeDefined();
    }
  });

  it('substitutes the GET-only attenuated data capability for UNTRUSTED at resolve time', async () => {
    const untrusted = dataOf(resolveKernelModules('p', 'UNTRUSTED_PLUGIN', deps()));
    // The attenuation lives in WHICH module resolved — not in an if() the caller hits.
    await expect(untrusted.fetch('/api/secret', { method: 'POST' })).rejects.toThrow(/GET/);
    await expect(untrusted.fetch('/api/secret')).rejects.toThrow(/may not access/);
  });

  it('resolves the full data capability for TRUSTED (same import, different module)', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response('{}', { status: 200 }));
    const trusted = dataOf(resolveKernelModules('p', 'TRUSTED_PLUGIN', deps()));
    // A TRUSTED plugin's @kernel/data performs the request (no allowlist / method restriction).
    await trusted.fetch('/api/secret', { method: 'POST' });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('the resolved module differs by tier — that is the substitution', () => {
    const trusted = dataOf(resolveKernelModules('p', 'TRUSTED_PLUGIN', deps()));
    const untrusted = dataOf(resolveKernelModules('p', 'UNTRUSTED_PLUGIN', deps()));
    expect(trusted).not.toBe(untrusted);
    expect(trusted.fetch).not.toBe(untrusted.fetch);
  });

  describe('kernelEndowment — the @kernel/* access path as a resolver', () => {
    it('resolves @kernel/data to the tier-attenuated capability', async () => {
      const kernel = kernelEndowment('p', 'UNTRUSTED_PLUGIN', deps());
      const data = kernel('@kernel/data') as PluginDataAccess;
      // Same substitution as the module map: UNTRUSTED data is GET-only / allowlisted.
      await expect(data.fetch('/api/secret', { method: 'POST' })).rejects.toThrow(/GET/);
    });

    it('throws for an unknown @kernel path', () => {
      const kernel = kernelEndowment('p', 'TRUSTED_PLUGIN', deps());
      expect(() => kernel('@kernel/nope')).toThrow(/no capability at import path/);
    });
  });
});
