// SPDX-License-Identifier: Apache-2.0
export function isTauriRuntime(): boolean {
  if (typeof window === 'undefined') return false;
  const proto = window.location?.protocol;
  const host = (window.location?.hostname ?? '').toLowerCase();
  // Tauri runtimes vary by platform/version:
  // - tauri://localhost (custom protocol)
  // - http(s)://tauri.localhost (WebView2 / isolation host)
  // Some builds may not expose `window.__TAURI__`, but `__TAURI_INTERNALS__` is a strong signal.
  const hasTauriGlobal = !!(window as any).__TAURI__;
  const hasTauriInternals = !!(window as any).__TAURI_INTERNALS__;
  const isTauriProtocol = proto === 'tauri:';
  const isTauriHost = host === 'tauri.localhost';
  return isTauriProtocol || isTauriHost || hasTauriGlobal || hasTauriInternals;
}


