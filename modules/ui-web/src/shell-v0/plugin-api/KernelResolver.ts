// SPDX-License-Identifier: Apache-2.0
/**
 * §4.2 — the 507 KCS capability boundary as `@kernel/*` import paths with resolver-time module
 * substitution (tempdoc 560 §4.2).
 *
 * A plugin reaches host capabilities by importing them — `import { data } from '@kernel/data'` —
 * rather than receiving a `host.data` object whose methods branch on trust. The MODULE RESOLVER
 * substitutes the trust-attenuated implementation at resolve time: which module backs `@kernel/data`
 * is decided HERE, by tier, before the plugin's code runs. There is no `if (isUntrusted)` in any
 * capability method body — the capability modules already attenuate by composition (548 §4.2 /
 * 521 §2.3), and this resolver picks the per-tier variant by constructing the host API at the right
 * tier and exposing each capability under its `@kernel/*` specifier.
 *
 * The result is a SES Compartment module map: `new Compartment(endowments, resolveKernelModules(...))`
 * makes `compartment.import('@kernel/data')` resolve to the tier-correct capability. Same import in
 * the plugin source; different resolved module for an UNTRUSTED vs a TRUSTED plugin.
 */

import { createHostApi, type HostApiDeps } from './HostApiImpl.js';
import type { PluginHostApi, PluginTrustTier } from './plugin-types.js';

/** The capability import specifiers a plugin may resolve through the kernel boundary. */
export const KERNEL_SPECIFIERS = [
  '@kernel/data',
  '@kernel/ui',
  '@kernel/ai',
  '@kernel/selection',
  '@kernel/registration',
  '@kernel/platform',
] as const;

export type KernelSpecifier = (typeof KERNEL_SPECIFIERS)[number];

/**
 * A SES Compartment module descriptor. The {@code namespace} object's own keys become the resolved
 * module's named exports (and {@code default} its default export).
 */
export interface KernelModuleDescriptor {
  namespace: Record<string, unknown>;
}

/**
 * Resolve the `@kernel/*` module map for a plugin of {@code tier} — the resolver-time trust
 * substitution. Each specifier resolves to the tier-attenuated capability drawn from a host API
 * constructed at {@code tier}; an UNTRUSTED plugin's `@kernel/data` is the GET-only allowlisted
 * variant, a TRUSTED plugin's is the full one — same import, different resolved module, decided here
 * and not in any method body.
 */
export function resolveKernelModules(
  pluginId: string,
  tier: PluginTrustTier,
  deps: HostApiDeps,
): Record<KernelSpecifier, KernelModuleDescriptor> {
  const host: PluginHostApi = createHostApi(pluginId, tier, deps);
  const mod = (cap: unknown, named: string): KernelModuleDescriptor => ({
    namespace: { default: cap, [named]: cap },
  });
  return {
    '@kernel/data': mod(host.data, 'data'),
    '@kernel/ui': mod(host.ui, 'ui'),
    '@kernel/ai': mod(host.ai, 'ai'),
    '@kernel/selection': mod(host.selection, 'selection'),
    '@kernel/registration': mod(host.registration, 'registration'),
    '@kernel/platform': mod(host.platform, 'platform'),
  };
}

/**
 * The `@kernel/*` access path as an endowed resolver — the achievable form of "resolver-time module
 * substitution is the access path" under SES 2.0 (which ships no `ModuleSource`/compartment-mapper, so
 * a script-mode plugin cannot use ES `import` syntax to reach `@kernel/*`; the module map is still
 * passed to the Compartment for a future module-mode plugin). A plugin calls
 * {@code kernel('@kernel/data')} and receives the TIER-ATTENUATED capability — the substitution is which
 * module the resolver returns, decided once here by tier, never an {@code if(isUntrusted)} in a body.
 */
export function kernelEndowment(
  pluginId: string,
  tier: PluginTrustTier,
  deps: HostApiDeps,
): (specifier: string) => unknown {
  const modules = resolveKernelModules(pluginId, tier, deps) as Record<
    string,
    KernelModuleDescriptor
  >;
  return (specifier: string) => {
    const resolved = modules[specifier];
    if (!resolved) {
      throw new Error(
        `@kernel: no capability at import path '${specifier}' ` +
          `(available: ${KERNEL_SPECIFIERS.join(', ')}).`,
      );
    }
    return resolved.namespace.default;
  };
}
