// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from './tauriRuntime';

/**
 * Whether the user currently has this window's attention — tempdoc 655's long-term design pass.
 *
 * Environment-correct, not Tauri-only: inside Tauri, `document.hidden`/the Page Visibility API is
 * confirmed unreliable (Tauri v2 upstream issues #6864/#10592 — a webview window frequently
 * reports "visible" even when minimized or covered by another window), so this uses Tauri's own
 * native window-focus signal instead. Outside Tauri (plain browser dev mode), `document.hasFocus()`
 * is reliable and is used directly — the bug is Tauri-specific, not a reason to avoid the standard
 * API where it actually works.
 *
 * Fails safe: on any error, returns `true` (assume focused), so a failure here can only ever
 * suppress an unnecessary desktop notification, never fail to show one the user is actually
 * missing — never throws into the caller, mirroring `sendDesktopNotification`'s own convention.
 */
export async function isWindowFocused(): Promise<boolean> {
  if (!isTauriRuntime()) {
    return typeof document === 'undefined' ? true : document.hasFocus();
  }
  try {
    const { getCurrentWindow } = await import('@tauri-apps/api/window');
    return await getCurrentWindow().isFocused();
  } catch {
    // Native window API unavailable — assume focused (fail toward NOT over-notifying).
    return true;
  }
}
