// SPDX-License-Identifier: Apache-2.0
/**
 * host.platform capability module (548 §4.2 Increment A) — extracted from
 * HostApiImpl. Owns platform-capability detection + the trusted/untrusted
 * platform implementations and selects between them by tier (521 §2.3
 * composition; no runtime if(tier) in any method body). Pure extraction behind
 * the unchanged PluginHostApi facade.
 */

import { isTauriRuntime } from '../../../utils/tauriRuntime.js';
import { pickFolder as nativePickFolder } from '../../../utils/folderPicker.js';
import type { PluginHostApi, PluginTrustTier } from '../plugin-types.js';

type PluginPlatform = PluginHostApi['platform'];

function detectCapabilities(): ReadonlySet<string> {
  const caps = new Set<string>();
  if (isTauriRuntime()) {
    caps.add('file-picker');
    caps.add('folder-picker');
    caps.add('reveal-in-explorer');
    caps.add('native-notifications');
    caps.add('open-external-native');
  }
  caps.add('clipboard');
  caps.add('open-external');
  return caps;
}

let cachedCapabilities: ReadonlySet<string> | null = null;
function getCapabilities(): ReadonlySet<string> {
  if (!cachedCapabilities) cachedCapabilities = detectCapabilities();
  return cachedCapabilities;
}

async function openExternalUrl(url: string): Promise<void> {
  if (isTauriRuntime()) {
    try {
      const coreSpec = ['@tauri-apps', 'api', 'core'].join('/');
      const { invoke } = await import(/* @vite-ignore */ coreSpec) as { invoke: (cmd: string, args: unknown) => Promise<unknown> };
      await invoke('open_file', { path: url });
      return;
    } catch {
      // Fall through to window.open
    }
  }
  globalThis.open(url, '_blank');
}

/**
 * Tempdoc 565 §29 Tier-3 — open a LOCAL file path in the OS (the cited-file affordance). Reuses the
 * ONE `open_file` Tauri command (via {@link openExternalUrl}) so there is a single invoke site; outside
 * Tauri it falls back to `window.open`. The uniquely-local citation affordance: our sources are the
 * user's real files (`parentDocId` is a path), so a citation can open its file — no cloud product can.
 */
export async function openLocalFile(path: string): Promise<void> {
  if (!path) return;
  await openExternalUrl(path);
}

/**
 * Tempdoc 577 Phase 8 — reveal a LOCAL path in the OS file manager. The ONE
 * `reveal_in_explorer` invoke site; `createPlatformApi` and the search-result
 * context actions both ride it (no second invoke path). No-op outside Tauri.
 */
export async function revealLocalPath(path: string): Promise<void> {
  if (!path || !getCapabilities().has('reveal-in-explorer')) return;
  try {
    const coreSpec = ['@tauri-apps', 'api', 'core'].join('/');
    const { invoke } = await import(/* @vite-ignore */ coreSpec) as { invoke: (cmd: string, args: unknown) => Promise<unknown> };
    await invoke('reveal_in_explorer', { path });
  } catch {
    // No-op fallback
  }
}

export function createPlatformApi(tier: PluginTrustTier): PluginPlatform {
  const capabilities = getCapabilities();
  const isUntrusted = tier === 'UNTRUSTED_PLUGIN';
  return isUntrusted
    ? {
        capabilities: new Set([...capabilities].filter((c) => c === 'clipboard' || c === 'open-external')),
        pickFile: () => Promise.resolve(null),
        pickFolder: () => Promise.resolve(null),
        revealInExplorer: () => Promise.resolve(),
        openExternal: async (url: string) => openExternalUrl(url),
      }
    : {
        capabilities,
        pickFile: async (options?: { multiple?: boolean; filters?: Array<{ name: string; extensions: string[] }> }) => {
          if (!capabilities.has('file-picker')) return null;
          try {
            const dlgSpec = ['@tauri-apps', 'plugin-dialog'].join('/');
            const { open } = await import(/* @vite-ignore */ dlgSpec) as { open: (o: unknown) => Promise<unknown> };
            const result = await open({
              multiple: options?.multiple ?? false,
              filters: options?.filters,
            }) as string | string[] | null;
            if (result === null) return null;
            return Array.isArray(result) ? result : [result];
          } catch {
            return null;
          }
        },
        pickFolder: () => nativePickFolder(),
        // 577 Phase 8 — delegates to the one revealLocalPath invoke site.
        revealInExplorer: (path: string) => revealLocalPath(path),
        openExternal: async (url: string) => openExternalUrl(url),
      };
}
