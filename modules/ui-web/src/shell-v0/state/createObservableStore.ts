// SPDX-License-Identifier: Apache-2.0
/**
 * createObservableStore — the one tiny observable-value primitive (569 §18.B / Phase 0).
 *
 * The `value + Set<listener> + notify + reset` idiom recurs across the shell's module-level stores
 * (uiModeState, and the observable halves of several substrates). This extracts it once: a typed,
 * change-deduped, subscribe-with-optional-immediate store. It owns ONLY the observable concern — a
 * store that ALSO persists keeps its own persistence (UserStateDocument / settings / localStorage
 * differ per store), so this is deliberately not a persistence framework; forcing the persist-bearing
 * stores through one shape would be a forced abstraction.
 */

export interface ObservableStore<T> {
  /** Current value. */
  get(): T;
  /** Set the value; returns true iff it changed (per `equals`) and listeners fired. */
  set(next: T): boolean;
  /** Subscribe to changes; `immediate` fires the listener once with the current value. Returns unsubscribe. */
  subscribe(listener: (value: T) => void, opts?: { immediate?: boolean }): () => void;
  /** Reset to the initial value AND drop all listeners (test-only / teardown semantics). */
  reset(): void;
}

export function createObservableStore<T>(
  initial: T,
  opts?: { equals?: (a: T, b: T) => boolean },
): ObservableStore<T> {
  const equals = opts?.equals ?? Object.is;
  let value = initial;
  const listeners = new Set<(value: T) => void>();
  return {
    get: () => value,
    set(next: T): boolean {
      if (equals(value, next)) return false;
      value = next;
      for (const l of listeners) {
        try {
          l(value);
        } catch {
          /* a listener throwing must not break the notify loop */
        }
      }
      return true;
    },
    subscribe(listener: (value: T) => void, options?: { immediate?: boolean }): () => void {
      listeners.add(listener);
      if (options?.immediate) {
        try {
          listener(value);
        } catch {
          /* swallow */
        }
      }
      return () => {
        listeners.delete(listener);
      };
    },
    reset(): void {
      value = initial;
      listeners.clear();
    },
  };
}
