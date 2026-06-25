/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.advisory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Per-class bounded ring buffer of recent {@link AdvisoryRecord}s. Used to populate
 * the SSE snapshot frame on subscribe. Replaces the old per-class-typed
 * {@code OperationCompletedAdvisoryLog} with a class-agnostic buffer.
 */
public final class AdvisoryLog {

  public static final int DEFAULT_CAPACITY = 200;

  private final int capacity;
  private final Deque<AdvisoryRecord> events = new ArrayDeque<>();

  public AdvisoryLog() {
    this(DEFAULT_CAPACITY);
  }

  public AdvisoryLog(int capacity) {
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

  public synchronized void append(AdvisoryRecord record) {
    Objects.requireNonNull(record, "record");
    if (events.size() >= capacity) {
      events.pollFirst();
    }
    events.addLast(record);
  }

  public synchronized List<AdvisoryRecord> recent() {
    return List.copyOf(events);
  }

  public synchronized List<AdvisoryRecord> recent(int n) {
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
    List<AdvisoryRecord> all = new ArrayList<>(events);
    return List.copyOf(all.subList(size - n, size));
  }
}
