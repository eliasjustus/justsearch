/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring buffer of recent {@link AuthorizationOutcomeEntry} appends — tempdoc 550
 * Outcome face. The gate-decision sibling of {@link OperationHistoryStore}; the action ledger
 * projects over both (federated-ledger decision D1). FIFO eviction past capacity; {@link
 * #recent()} returns a chronological snapshot (most recent last). Mirrors
 * {@link OperationHistoryStore}.
 */
public final class AuthorizationOutcomeStore {

  /** Default ring-buffer capacity (mirrors OperationHistoryStore). */
  public static final int DEFAULT_CAPACITY = 200;

  private final int capacity;
  private final Deque<AuthorizationOutcomeEntry> entries = new ArrayDeque<>();
  // Tempdoc 550 F5: append-listeners fan every gate firing into the one action-event log.
  private final List<java.util.function.Consumer<AuthorizationOutcomeEntry>>
      appendListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  public AuthorizationOutcomeStore() {
    this(DEFAULT_CAPACITY);
  }

  public AuthorizationOutcomeStore(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
    }
    this.capacity = capacity;
  }

  public synchronized int size() {
    return entries.size();
  }

  /** Register a listener fired on every append (e.g. fan-in to the one action-event log; F5). */
  public void addAppendListener(java.util.function.Consumer<AuthorizationOutcomeEntry> listener) {
    appendListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /** Appends {@code entry}; if at capacity, evicts the oldest entry first. */
  public void append(AuthorizationOutcomeEntry entry) {
    Objects.requireNonNull(entry, "entry");
    synchronized (this) {
      if (entries.size() >= capacity) {
        entries.pollFirst();
      }
      entries.addLast(entry);
    }
    for (var listener : appendListeners) {
      // Outside the lock + fail-soft: a throwing fan-in listener must not break the gate-outcome
      // append (independent-review F5 NIT; defense-in-depth).
      try {
        listener.accept(entry);
      } catch (RuntimeException ignored) {
        // swallow — best-effort one-log fan-in relative to the authoritative append.
      }
    }
  }

  /** Returns all retained entries in chronological order. */
  public synchronized List<AuthorizationOutcomeEntry> recent() {
    return List.copyOf(entries);
  }
}
