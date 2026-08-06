// SPDX-License-Identifier: Apache-2.0
/**
 * Backend-restart bridge — tempdoc 805 G.1.
 *
 * The Rust shell emits `justsearch://backend-restart` when it observes a NEW per-boot instanceId
 * (a new Head incarnation, on a new ephemeral port with a new session token). Everything the
 * webview cached about the old incarnation is dead at that moment; the first thing to drop is the
 * session token, because a stale one 401s every mutating call with no recovery (R11-F2).
 *
 * In browser (vite dev): no-op — Tauri APIs absent. Follows the lazy-import pattern established by
 * `shell-v0/router/tauriBridge.ts` and `TauriDeepLinkSource.ts`.
 */

import { isTauriRuntime } from '../utils/tauriRuntime.js';
import { invalidateSessionToken } from './http.js';

const TAURI_EVENT_NAME = 'justsearch://backend-restart';

/**
 * Subscribe to the shell's restart event. `onRestart` runs AFTER the binding is invalidated, so a
 * consumer that re-binds (the boot path reloads the window) never observes the dead token.
 *
 * Returns an unsubscribe handle (no-op outside Tauri or when subscription fails).
 */
export async function installBackendRestartBridge(
  onRestart?: () => void,
): Promise<() => void> {
  if (!isTauriRuntime()) {
    return () => {
      /* no-op */
    };
  }
  try {
    const { listen } = await import('@tauri-apps/api/event');
    return await listen(TAURI_EVENT_NAME, () => {
      invalidateSessionToken();
      onRestart?.();
    });
  } catch (err) {
    console.warn('[backendRestart] failed to subscribe to Tauri event:', err);
    return () => {
      /* no-op */
    };
  }
}
