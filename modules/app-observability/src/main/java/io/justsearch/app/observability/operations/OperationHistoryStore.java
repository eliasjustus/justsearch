/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring buffer of recent {@link OperationHistoryEntry} appends.
 *
 * <p>Per slice 444b (Operation history HISTORY Resource): in-memory bounded log per the
 * 01c recipe's "in-memory bounded log + DURABLE-mode HistoryPolicy" producer-attachment
 * option (best-effort durability for events small enough that an in-memory store is
 * sufficient). Mirrors {@link io.justsearch.app.observability.health.OccurrenceLog}'s
 * pattern.
 *
 * <p>Eviction is FIFO: appending past capacity drops the oldest entry. {@link #recent()}
 * returns a chronological (insertion-order) snapshot; the most recent entry is at the
 * end of the returned list.
 *
 * <p>The slice's coordination flag — that durable persistence with replay is a future
 * concern — applies. Subscribers reconnecting see only the current ring-buffer window;
 * a follow-up slice can swap to DURABLE-backed storage when the workload pressure
 * justifies the schema/migrations cost.
 */
public final class OperationHistoryStore {

  /** Default ring-buffer capacity per slice 444b §"Substrate". */
  public static final int DEFAULT_CAPACITY = 200;

  private final int capacity;
  private final Deque<OperationHistoryEntry> entries = new ArrayDeque<>();
  // Tempdoc 550 F5: fired on every append so the one action-event log is fed STRUCTURALLY (wired to
  // the ledger), not by a separate broadcast call an emit site could forget. Notified outside the
  // lock.
  private final List<java.util.function.Consumer<OperationHistoryEntry>> appendListeners =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  public OperationHistoryStore() {
    this(DEFAULT_CAPACITY);
  }

  public OperationHistoryStore(int capacity) {
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
  public void addAppendListener(java.util.function.Consumer<OperationHistoryEntry> listener) {
    appendListeners.add(Objects.requireNonNull(listener, "listener"));
  }

  /** Appends {@code entry}; if at capacity, evicts the oldest entry first. */
  public void append(OperationHistoryEntry entry) {
    Objects.requireNonNull(entry, "entry");
    synchronized (this) {
      if (entries.size() >= capacity) {
        entries.pollFirst();
      }
      entries.addLast(entry);
    }
    for (var listener : appendListeners) {
      // Outside the lock (listeners do SSE publish), and fail-soft: a throwing fan-in listener must
      // NEVER break the append or propagate into the caller (defense-in-depth; independent-review
      // F5 NIT). The store's own state is already committed above.
      try {
        listener.accept(entry);
      } catch (RuntimeException ignored) {
        // swallow — the one-log fan-in is best-effort relative to the authoritative store append.
      }
    }
  }

  /** Returns all retained entries in chronological order. */
  public synchronized List<OperationHistoryEntry> recent() {
    return List.copyOf(entries);
  }

  /**
   * Returns the most recent {@code n} entries in chronological order. If {@code n >= size},
   * returns the full retained set.
   */
  public synchronized List<OperationHistoryEntry> recent(int n) {
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
    List<OperationHistoryEntry> all = new ArrayList<>(entries);
    return List.copyOf(all.subList(size - n, size));
  }
}
