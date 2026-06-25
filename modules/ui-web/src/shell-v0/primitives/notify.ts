// SPDX-License-Identifier: Apache-2.0
/**
 * Listener-notification primitive — Tempdoc 543 §25.α5.
 *
 * 19+ substrates / state stores previously duplicated this exact
 * pattern:
 *
 *   for (const l of listeners) {
 *     try { l(); } catch { /* swallow */ /* }
 *   }
 *
 * `swallow` matters because one bad subscriber must not break the
 * notify loop for other subscribers. Centralized so adding telemetry
 * (e.g., "report swallowed errors to a dev console") is one fix.
 */

export function notifyAll(listeners: Iterable<() => void>): void {
  for (const l of listeners) {
    try {
      l();
    } catch {
      /* swallow — one bad subscriber must not break the loop */
    }
  }
}

/**
 * Variant for typed-payload notifications (Effect Journal, Pending
 * substrate, etc.). Same swallow semantics.
 */
export function notifyAllWith<T>(
  listeners: Iterable<(payload: T) => void>,
  payload: T,
): void {
  for (const l of listeners) {
    try {
      l(payload);
    } catch {
      /* swallow */
    }
  }
}
