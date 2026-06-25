/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring buffer of recent {@link NavigationHistoryEntry} appends — the Navigation
 * sibling to {@link io.justsearch.app.observability.operations.OperationHistoryStore}.
 *
 * <p>Tempdoc 550 Slice F1 (Outcome face). Mirrors the operation-history store exactly:
 * in-memory bounded log, FIFO eviction past capacity, chronological (insertion-order)
 * {@link #recent()} snapshot with the most recent entry at the end. Durable persistence
 * with replay is a future concern (the unified action-ledger read-view); subscribers see
 * only the current ring-buffer window.
 */
public final class NavigationHistoryStore {

  /** Default ring-buffer capacity; matches OperationHistoryStore's window. */
  public static final int DEFAULT_CAPACITY = 200;

  private final int capacity;
  private final Deque<NavigationHistoryEntry> entries = new ArrayDeque<>();
  // Tempdoc 550 F5: append-listeners fan every entry into the one action-event log structurally.
  private final List<java.util.function.Consumer<NavigationHistoryEntry>> appendListeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  public NavigationHistoryStore() {
    this(DEFAULT_CAPACITY);
  }

  public NavigationHistoryStore(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
    }
    this.capacity = capacity;
  }

  public int capacity() {
    return capacity;
  }

  public synchronized int size() {
    return entries.size();
  }

  /** Register a listener fired on every append (e.g. fan-in to the one action-event log; F5). */
  public void addAppendListener(java.util.function.Consumer<NavigationHistoryEntry> listener) {
    appendListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /** Appends {@code entry}; if at capacity, evicts the oldest entry first. */
  public void append(NavigationHistoryEntry entry) {
    Objects.requireNonNull(entry, "entry");
    synchronized (this) {
      if (entries.size() >= capacity) {
        entries.pollFirst();
      }
      entries.addLast(entry);
    }
    for (var listener : appendListeners) {
      // Outside the lock + fail-soft: a throwing fan-in listener must not break navigation dispatch
      // (independent-review F5 NIT — the navigation path lacked this defense-in-depth).
      try {
        listener.accept(entry);
      } catch (RuntimeException ignored) {
        // swallow — best-effort one-log fan-in relative to the authoritative append.
      }
    }
  }

  /** Returns all retained entries in chronological order. */
  public synchronized List<NavigationHistoryEntry> recent() {
    return List.copyOf(entries);
  }

  /**
   * Returns the most recent {@code n} entries in chronological order. If {@code n >=
   * size}, returns the full retained set.
   */
  public synchronized List<NavigationHistoryEntry> recent(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must be >= 0, got " + n);
    }
    if (n == 0 || entries.isEmpty()) {
      return List.of();
    }
    int size = entries.size();
    if (n >= size) {
      return List.copyOf(entries);
    }
    List<NavigationHistoryEntry> all = new ArrayList<>(entries);
    return List.copyOf(all.subList(size - n, size));
  }
}
