/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read deadline for a streaming response body.
 *
 * <p>{@code HttpRequest.timeout} bounds only the arrival of the response headers, so a body that
 * goes silent mid-stream blocks the reading thread with no deadline of its own. This watchdog is
 * touched on every line read; when nothing has been read for {@code idleDeadline} it runs the
 * supplied abort action (closing the response body), which unblocks the reader so the stream can end
 * as a loud error instead of parking forever.
 */
final class StreamIdleWatchdog implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(StreamIdleWatchdog.class);
  private static final long MIN_TICK_MS = 25;

  private final Duration idleDeadline;
  private final Runnable abort;
  private final ScheduledFuture<?> ticker;
  private final AtomicBoolean fired = new AtomicBoolean(false);
  private volatile long lastReadNanos;

  StreamIdleWatchdog(ScheduledExecutorService scheduler, Duration idleDeadline, Runnable abort) {
    this.idleDeadline = idleDeadline;
    this.abort = abort;
    this.lastReadNanos = System.nanoTime();
    long tickMs = Math.max(MIN_TICK_MS, idleDeadline.toMillis() / 4);
    this.ticker = scheduler.scheduleWithFixedDelay(this::check, tickMs, tickMs, TimeUnit.MILLISECONDS);
  }

  /** Records that the reader made progress. */
  void touch() {
    lastReadNanos = System.nanoTime();
  }

  /** True once the deadline elapsed and the body was aborted. */
  boolean fired() {
    return fired.get();
  }

  @Override
  public void close() {
    ticker.cancel(false);
  }

  private void check() {
    if (System.nanoTime() - lastReadNanos < idleDeadline.toNanos()) {
      return;
    }
    if (!fired.compareAndSet(false, true)) {
      return;
    }
    LOG.warn("LLM stream idle for {} — aborting the response body read", idleDeadline);
    try {
      abort.run();
    } catch (RuntimeException e) {
      LOG.debug("Aborting a stalled LLM stream body threw: {}", e.toString());
    }
  }
}
