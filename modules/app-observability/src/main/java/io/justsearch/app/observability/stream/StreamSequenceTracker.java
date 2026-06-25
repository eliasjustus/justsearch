/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Atomic monotonic sequence generator for SSE envelope frames.
 *
 * <p>Per slice 436: each stream has its own monotonic counter; {@link #next()} returns the
 * next seq starting at 1 (frames seq <= 0 are invalid per
 * {@link io.justsearch.app.api.stream.SseEnvelope}).
 *
 * <p>One instance per active stream connection. Sequences do NOT persist across server
 * restarts — clients with old resume tokens see a {@code reset + snapshot} on reconnect.
 */
public final class StreamSequenceTracker {

  private final AtomicLong counter = new AtomicLong(0);

  /** Returns the next monotonic sequence number (starts at 1). */
  public long next() {
    return counter.incrementAndGet();
  }

  /** Returns the most recently issued sequence number, or 0 if none issued yet. */
  public long current() {
    return counter.get();
  }
}
