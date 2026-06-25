// SPDX-License-Identifier: Apache-2.0
/**
 * host.registration capability module (548 §4.2 Increment A) — extracted from
 * HostApiImpl. UNTRUSTED can register surface ports but command/keybinding
 * registration is denied (no-op), selected by tier — no runtime if(tier).
 */

import type { PluginHostApi, PluginTrustTier, SurfacePortHandler } from '../plugin-types.js';

type PluginRegistration = PluginHostApi['registration'];

interface RegistrationDeps {
  registerSurfacePort: (pluginId: string, id: string, handler: SurfacePortHandler) => void;
  registerCommand?: (id: string, label: string, handler: () => void, labelKey?: string) => void;
  registerKeybinding?: (key: string, handler: () => void) => void;
}

export function createRegistrationApi(
  tier: PluginTrustTier,
  pluginId: string,
  deps: RegistrationDeps,
): PluginRegistration {
  const isUntrusted = tier === 'UNTRUSTED_PLUGIN';
  return isUntrusted
    ? {
        registerSurfacePort: (id: string, handler: SurfacePortHandler) => deps.registerSurfacePort(pluginId, id, handler),
        registerCommand: () => { /* UNTRUSTED: denied — audience-gated */ },
        registerKeybinding: () => { /* UNTRUSTED: denied */ },
      }
    : {
        registerSurfacePort: (id: string, handler: SurfacePortHandler) => deps.registerSurfacePort(pluginId, id, handler),
        registerCommand: (id: string, label: string, handler: () => void, labelKey?: string) => deps.registerCommand?.(id, label, handler, labelKey),
        registerKeybinding: (key: string, handler: () => void) => deps.registerKeybinding?.(key, handler),
      };
}
