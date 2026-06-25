// SPDX-License-Identifier: Apache-2.0
/**
 * Persistence Contract Module
 *
 * Centralizes all localStorage access to enforce a clear ownership model
 * and enable future migrations. All storage keys are defined here.
 *
 * ## Key Categories
 *
 * 1. **Server-synced cache**: Data also stored server-side. Local copy is a
 *    cache that may be stale. Source of truth is the server.
 *
 * 2. **Local-only preferences**: UI preferences that never leave the browser.
 *    Source of truth is localStorage.
 *
 * ## Migration Strategy
 *
 * When adding new keys or changing schema:
 * 1. Add a new version suffix (e.g., `justsearch-settings-v2`)
 * 2. Add migration logic in `migrateKey()`
 * 3. Keep reading old keys for one release cycle
 */

// ==================== Key Registry ====================

/**
 * All allowed localStorage keys (string literal union).
 * Add new keys here to make them part of the contract.
 */
type StorageKey =
  | 'justsearch-settings'       // Server-synced app settings cache
  | 'justsearch-high-contrast'  // Local-only: accessibility preference
  | 'justsearch-debug'          // Local-only: debug mode flag
  | 'justsearch-log-level';     // Local-only: log level override


// ==================== Type-Safe Helpers ====================

/**
 * Check if localStorage is available.
 */
function isStorageAvailable(): boolean {
  try {
    const test = '__storage_test__';
    localStorage.setItem(test, test);
    localStorage.removeItem(test);
    return true;
  } catch {
    return false;
  }
}

/**
 * Read a string value from localStorage.
 * Returns null if key doesn't exist or storage is unavailable.
 */
export function readString(key: StorageKey): string | null {
  if (!isStorageAvailable()) return null;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * Write a string value to localStorage.
 * Silently fails if storage is unavailable.
 */
function writeString(key: StorageKey, value: string): void {
  if (!isStorageAvailable()) return;
  try {
    localStorage.setItem(key, value);
  } catch {
    // Ignore quota errors, etc.
  }
}


/**
 * Read and parse a JSON value from localStorage.
 * Returns null if key doesn't exist, storage is unavailable, or parse fails.
 *
 * @param key - Storage key
 * @param validator - Optional validator function (e.g., Zod safeParse)
 */
export function readJson<T>(
  key: StorageKey,
  validator?: (data: unknown) => T | null
): T | null {
  const raw = readString(key);
  if (raw === null) return null;

  try {
    const parsed: unknown = JSON.parse(raw);
    if (validator) {
      return validator(parsed);
    }
    return parsed as T;
  } catch {
    return null;
  }
}

/**
 * Serialize and write a JSON value to localStorage.
 * Silently fails if storage is unavailable or serialization fails.
 */
export function writeJson<T>(key: StorageKey, value: T): void {
  try {
    writeString(key, JSON.stringify(value));
  } catch {
    // Ignore serialization errors
  }
}

// ==================== Convenience Accessors ====================

/**
 * Read a boolean value from localStorage.
 * Returns defaultValue if key doesn't exist or has unexpected value.
 */
export function readBoolean(key: StorageKey, defaultValue: boolean = false): boolean {
  const raw = readString(key);
  if (raw === null) return defaultValue;
  if (raw === 'true') return true;
  if (raw === 'false') return false;
  return defaultValue;
}

/**
 * Write a boolean value to localStorage.
 */
export function writeBoolean(key: StorageKey, value: boolean): void {
  writeString(key, value ? 'true' : 'false');
}


