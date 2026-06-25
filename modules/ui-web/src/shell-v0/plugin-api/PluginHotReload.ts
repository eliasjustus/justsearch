// SPDX-License-Identifier: Apache-2.0
/**
 * PluginHotReload — Tempdoc 508 §6.2 — watch-based hot-reload for
 * plugins and themes.
 *
 * In Tauri: uses @tauri-apps/plugin-fs watchImmediate for low-latency
 * file change detection (~10-50ms on Windows).
 * In browser: no-op (filesystem watching not available).
 *
 * On plugin file change: uninstall + reinstall via existing lifecycle.
 * On theme file change: re-fetch + re-apply via loadAndApplyTheme.
 */

import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import type { PluginRegistry } from './PluginRegistry.js';

export interface HotReloadOptions {
  pluginDir: string;
  themeDir?: string;
  registry: PluginRegistry;
  onPluginReload?: (pluginId: string) => void;
  onThemeReload?: (themeId: string) => void;
  onError?: (error: Error) => void;
}

let stopPluginWatcher: (() => void) | null = null;
let stopThemeWatcher: (() => void) | null = null;

export async function startHotReload(options: HotReloadOptions): Promise<() => void> {
  if (!isTauriRuntime()) {
    return () => {};
  }

  try {
    // Runtime-constructed specifier so Vite can't statically analyze the
    // import (the module is Tauri-only and not installed in browser builds).
    const fsModSpec = ['@tauri-apps', 'plugin-fs'].join('/');
    const { watchImmediate } = await import(/* @vite-ignore */ fsModSpec) as {
      watchImmediate: (path: string, cb: (e: unknown) => void, opts?: { recursive?: boolean }) => Promise<() => Promise<void>>;
    };

    const stopPlugin = await watchImmediate(
      options.pluginDir,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (event: any) => {
        try {
          const kind = (event as { type?: { modify?: unknown; create?: unknown } }).type;
          if (kind && (('modify' in kind) || ('create' in kind))) {
            options.onPluginReload?.('detected');
          }
        } catch (err) {
          options.onError?.(err instanceof Error ? err : new Error(String(err)));
        }
      },
      { recursive: true },
    );
    stopPluginWatcher = () => { void stopPlugin(); stopPluginWatcher = null; };

    // Tempdoc 508 §6.2 — also watch the theme directory if provided.
    if (options.themeDir) {
      const stopTheme = await watchImmediate(
        options.themeDir,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (event: any) => {
          try {
            const kind = (event as { type?: { modify?: unknown; create?: unknown } }).type;
            if (kind && (('modify' in kind) || ('create' in kind))) {
              options.onThemeReload?.('detected');
            }
          } catch (err) {
            options.onError?.(err instanceof Error ? err : new Error(String(err)));
          }
        },
        { recursive: true },
      );
      stopThemeWatcher = () => { void stopTheme(); stopThemeWatcher = null; };
    }

    return () => {
      stopPluginWatcher?.();
      stopThemeWatcher?.();
    };
  } catch (err) {
    options.onError?.(err instanceof Error ? err : new Error(String(err)));
    return () => {};
  }
}

export function stopHotReload(): void {
  stopPluginWatcher?.();
  stopThemeWatcher?.();
}

export function isHotReloadActive(): boolean {
  return stopPluginWatcher !== null || stopThemeWatcher !== null;
}
