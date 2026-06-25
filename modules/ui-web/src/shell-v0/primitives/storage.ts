// SPDX-License-Identifier: Apache-2.0
/**
 * Storage primitive — Tempdoc 543 §25.α4.
 *
 * Two substrates (Effects journal, Profiles) previously duplicated the
 * "probe localStorage availability with a write-then-delete check"
 * pattern verbatim. Centralized here so a future browser-API change
 * (e.g., Private Browsing throwing on quota) needs one fix not N.
 *
 * Returns the Storage handle if usable, null when localStorage is
 * either undefined (SSR / headless) or throws (Safari Private mode).
 */

let _testProbeKey = '__jf_storage_probe__';

export function safeLocalStorage(): Storage | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    localStorage.setItem(_testProbeKey, '1');
    localStorage.removeItem(_testProbeKey);
    return localStorage;
  } catch {
    return null;
  }
}

/** Test-only: override the probe key (useful in isolated test envs). */
export function __setStorageProbeKeyForTest(key: string): void {
  _testProbeKey = key;
}
