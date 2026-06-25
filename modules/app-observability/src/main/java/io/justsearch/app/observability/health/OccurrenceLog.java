/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded ring buffer of recent {@link LifecycleEvent} occurrences.
 *
 * <p>Per tempdoc 430 §"In scope — substrate" (item 7): the head retains the last N
 * lifecycle/threshold occurrences for the SSE snapshot payload. Default capacity 200;
 * configurable via {@code JUSTSEARCH_HEALTH_OCCURRENCE_BUFFER}.
 *
 * <p>Eviction is FIFO: appending past capacity drops the oldest entry. {@link #recent()}
 * and {@link #recent(int)} return chronological (insertion-order) snapshots; the most
 * recent entry is at the end of the returned list.
 *
 * <p>Persistent occurrence storage with replay is V2 work (per §"Out of scope") —
 * subscribers reconnecting do not see missed occurrences, only the current ring-buffer
 * window.
 */
public final class OccurrenceLog {

  /** Default ring-buffer capacity per tempdoc 430 §"In scope". */
  public static final int DEFAULT_CAPACITY = 200;

  private final int capacity;
  private final Deque<HealthEvent> events = new ArrayDeque<>();

  public OccurrenceLog() {
    this(DEFAULT_CAPACITY);
  }

  public OccurrenceLog(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be > 0, got " + capacity);
    }
    this.capacity = capacity;
  }

  public int capacity() {
    return capacity;
  }

  public synchronized int size() {
    return events.size();
  }

  /** Appends {@code event}; if at capacity, evicts the oldest entry first. */
  public synchronized void append(HealthEvent event) {
    Objects.requireNonNull(event, "event");
    if (events.size() >= capacity) {
      events.pollFirst();
    }
    events.addLast(event);
  }

  /** Returns all retained occurrences in chronological order. */
  public synchronized List<HealthEvent> recent() {
    return List.copyOf(events);
  }

  /**
   * Returns the most recent {@code n} occurrences in chronological order. If
   * {@code n >= size}, returns the full retained set.
   */
  public synchronized List<HealthEvent> recent(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("n must be >= 0, got " + n);
    }
    if (n == 0 || events.isEmpty()) {
      return List.of();
    }
    int size = events.size();
    if (n >= size) {
      return List.copyOf(events);
    }
    List<HealthEvent> all = new ArrayList<>(events);
    return List.copyOf(all.subList(size - n, size));
  }
}
