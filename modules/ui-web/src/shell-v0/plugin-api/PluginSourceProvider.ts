// SPDX-License-Identifier: Apache-2.0
/**
 * PluginSourceProvider — Tempdoc 507 §3.5 / §6 Phase 4.
 *
 * Abstracts plugin source loading across platforms:
 * - Tauri: reads from ~/.justsearch/plugins/ via IPC
 * - Browser: fetches from URL (existing path)
 *
 * The consumer (PluginLoader) doesn't know whether source came from
 * disk or network.
 */

import { isTauriRuntime } from '../../utils/tauriRuntime.js';

/** Discovered plugin on disk. */
export interface DiscoveredPlugin {
  id: string;
  path: string;
  manifestJson: string;
  sourceText: string;
  /**
   * Tempdoc 508 §13 critical-analysis A4: true when manifest.json
   * or plugin.js exceeded the Rust-side size cap (64 KB / 1 MB).
   * manifestJson / sourceText are empty strings in that case;
   * callers MUST skip installation.
   */
  tooLarge?: boolean;
}

/**
 * Scan the plugin directory for installed plugins.
 * Returns discovered plugins with their manifest and source text.
 *
 * In Tauri: scans ~/.justsearch/plugins/ via Rust IPC command.
 * In browser: returns empty (file-based distribution not available).
 */
export async function discoverPlugins(): Promise<DiscoveredPlugin[]> {
  if (!isTauriRuntime()) return [];

  try {
    const { invoke } = await import('@tauri-apps/api/core');
    const result = await invoke<DiscoveredPlugin[]>('scan_plugins');
    return result ?? [];
  } catch {
    // The `scan_plugins` Rust command exists (modules/shell/src-tauri/
    // src/lib.rs); this catch is the general failure fallback (command
    // errored, plugin dir missing, etc.) — return empty rather than throw.
    return [];
  }
}

/**
 * Read a single plugin's source from the plugin directory.
 * Used for hot-reload when a plugin file changes.
 */
export async function readPluginSource(pluginPath: string): Promise<string | null> {
  if (!isTauriRuntime()) return null;

  try {
    const { invoke } = await import('@tauri-apps/api/core');
    return await invoke<string>('read_plugin_source', { path: pluginPath });
  } catch {
    return null;
  }
}

/**
 * Get the platform-appropriate plugin directory path. Creates the
 * directory on disk if it doesn't exist (file-based distribution
 * needs the dir to exist before users can drop plugins into it).
 * Returns null in browser (no filesystem access).
 */
export async function getPluginDirectory(): Promise<string | null> {
  if (!isTauriRuntime()) return null;

  try {
    const { invoke } = await import('@tauri-apps/api/core');
    // §12.1 — prefer the dedicated command that creates the dir on
    // first call. Fall back to composing from justsearch_paths for
    // older shells (no breakage if get_plugin_dir is unavailable).
    try {
      return await invoke<string>('get_plugin_dir');
    } catch {
      const paths = await invoke<{ home: string }>('justsearch_paths');
      return `${paths.home}/plugins`;
    }
  } catch {
    return null;
  }
}

/**
 * Get the platform-appropriate theme directory path.
 * Returns null in browser (no filesystem access).
 */
export async function getThemeDirectory(): Promise<string | null> {
  if (!isTauriRuntime()) return null;

  try {
    const { invoke } = await import('@tauri-apps/api/core');
    const paths = await invoke<{ home: string }>('justsearch_paths');
    return `${paths.home}/themes`;
  } catch {
    return null;
  }
}
