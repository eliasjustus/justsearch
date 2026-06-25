/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Epoch-based burst detector for the Worker-side file watcher (tempdoc 626 §Axis-A).
 *
 * <p>Ported from the Head-side {@code io.justsearch.app.services.worker.BurstDetector} when the
 * overflow/burst recovery nets were relocated onto the Worker watcher so the redundant Head watcher
 * could be retired without dropping them. A burst is a heuristic fallback for when the OS does NOT
 * explicitly signal a dropped-events OVERFLOW (e.g. Windows misses DELETEs during high churn such as
 * unzip / git checkout): a sustained event spike schedules a reconcile so the index re-converges.
 *
 * <p>Tracks per-root event counts using simple epoch-based counter reset: O(1) time and space. Each
 * root's {@code DirectoryWatcher} runs on a dedicated thread, so a root's counters are only touched
 * by one thread at a time — no per-root synchronization is needed. The {@link ConcurrentHashMap}
 * provides safe concurrent access across roots.
 */
final class WorkerBurstDetector {
  private final ConcurrentHashMap<Path, RootBurstState> states = new ConcurrentHashMap<>();

  /**
   * Record a watcher event for the given root.
   *
   * @return {@code true} if this event crossed the burst threshold for the first time this second
   *     (caller should schedule a reconcile)
   */
  boolean recordEvent(Path root, int threshold) {
    RootBurstState state = states.computeIfAbsent(root, k -> new RootBurstState());
    long currentSecond = System.currentTimeMillis() / 1000;

    // Reset counter at second boundary
    if (currentSecond != state.lastSecond) {
      state.lastSecond = currentSecond;
      state.eventCount = 0;
      state.syncScheduled = false;
    }

    state.eventCount++;
    if (state.eventCount > threshold && !state.syncScheduled) {
      state.syncScheduled = true;
      return true;
    }
    return false;
  }

  /** Remove tracking state for a root that is no longer watched. */
  void removeRoot(Path root) {
    states.remove(root);
  }

  private static final class RootBurstState {
    long lastSecond;
    long eventCount;
    boolean syncScheduled;
  }
}
