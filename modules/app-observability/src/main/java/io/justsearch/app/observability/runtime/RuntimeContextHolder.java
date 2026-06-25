/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-value in-memory holder for the current {@link RuntimeContext}.
 *
 * <p>Per slice 440 (Runtime mode STATE Resource): the head's authoritative replica of the
 * current runtime context. STATE Resources have no retained history — only the current value.
 * A snapshot fetch reads from this holder; SSE consumers receive snapshot on connect plus
 * change broadcasts on subsequent {@link #set(RuntimeContext)} calls.
 *
 * <p>{@link #set} is unconditional — duplicate-suppression (broadcasting only on actual
 * change) is the caller's responsibility, mirroring the {@link
 * io.justsearch.app.observability.health.ConditionStore} discipline of treating store
 * mutation and broadcast as separate steps.
 */
public final class RuntimeContextHolder {

  private final AtomicReference<RuntimeContext> current = new AtomicReference<>();

  /** Initializes the holder with an initial context. Equivalent to {@link #set} on first call. */
  public RuntimeContextHolder(RuntimeContext initial) {
    Objects.requireNonNull(initial, "initial");
    current.set(initial);
  }

  /** Returns the current context. Never null after construction. */
  public RuntimeContext current() {
    return current.get();
  }

  /**
   * Replaces the current context unconditionally. Returns the previous value so callers can
   * suppress no-op broadcasts (per the registry's "broadcast only on real change" discipline).
   */
  public RuntimeContext set(RuntimeContext next) {
    Objects.requireNonNull(next, "next");
    return current.getAndSet(next);
  }
}
