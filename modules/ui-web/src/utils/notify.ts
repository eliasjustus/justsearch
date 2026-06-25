// SPDX-License-Identifier: Apache-2.0
import { isTauriRuntime } from './tauriRuntime';

/**
 * Send a desktop notification via the Tauri notification plugin.
 * No-ops silently in browser mode or if permissions are denied.
 */
export async function sendDesktopNotification(title: string, body?: string): Promise<void> {
  if (!isTauriRuntime()) return;

  try {
    const {
      isPermissionGranted,
      requestPermission,
      sendNotification,
    } = await import('@tauri-apps/plugin-notification');

    let granted = await isPermissionGranted();
    if (!granted) {
      const permission = await requestPermission();
      granted = permission === 'granted';
    }
    if (!granted) return;

    sendNotification({ title, body });
  } catch {
    // Plugin not available — swallow silently.
  }
}
