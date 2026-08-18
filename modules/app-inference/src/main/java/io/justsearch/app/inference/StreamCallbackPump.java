/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ordered, off-lock dispatch of one stream's consumer callbacks.
 *
 * <p>The streaming transport holds the process-wide online-request lock for the whole llama-server
 * exchange, and the exchange is the body read. Invoking consumer callbacks inline from that read
 * loop put SSE writes and citation scoring <em>inside</em> the lock, so an arbitrarily slow — or
 * permanently blocked — consumer held the one lock every chat, VDU and stream request needs.
 * Callbacks are queued here instead and run on a separate thread, strictly in submission order,
 * while the read loop keeps draining the response.
 *
 * <p>Failure semantics are preserved rather than swallowed: a callback that throws stops further
 * dispatch and records the throwable, which the read loop picks up on its next line and rethrows —
 * so a {@code CancellationException} from a consumer still aborts the stream and routes to {@code
 * onError}, exactly as it did when callbacks ran inline.
 */
final class StreamCallbackPump implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(StreamCallbackPump.class);

  private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
  private final Semaphore available = new Semaphore(0);
  private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
  private final CountDownLatch drained = new CountDownLatch(1);
  private volatile boolean stopping;

  StreamCallbackPump(ExecutorService executor) {
    executor.execute(this::drainLoop);
  }

  /** Queue a callback. Dropped once a callback has failed — that stream is being torn down. */
  void dispatch(Runnable callback) {
    if (failure.get() != null || stopping) {
      return;
    }
    queue.add(callback);
    available.release();
  }

  /** The throwable a callback raised, or {@code null} if none has. */
  RuntimeException failure() {
    return failure.get();
  }

  /**
   * Blocks until every callback dispatched so far has run. Called after the body read completes and
   * <em>after</em> the online-request lock is released, so the terminal callback never fires before
   * the content it follows.
   */
  void awaitDrain() throws InterruptedException {
    close();
    drained.await();
  }

  /** Stops the drain loop once the already-queued callbacks have run. Idempotent. */
  @Override
  public void close() {
    stopping = true;
    available.release();
  }

  private void drainLoop() {
    try {
      for (; ; ) {
        available.acquire();
        Runnable next = queue.poll();
        if (next == null) {
          if (stopping) {
            return;
          }
          continue;
        }
        if (failure.get() != null) {
          continue;
        }
        try {
          next.run();
        } catch (RuntimeException e) {
          // Recorded, not swallowed: the read loop rethrows this on its next line so the stream
          // ends through the same onError path an inline callback failure used to take.
          failure.compareAndSet(null, e);
          LOG.debug("Stream callback threw ({}); aborting the stream", e.toString());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      drained.countDown();
    }
  }
}
