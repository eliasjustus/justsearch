package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 626 §Axis-A — the burst-detect heuristic that schedules a reconcile when a per-root event
 * spike crosses the threshold (a missed-event net for OS-dropped events that arrive without an
 * OVERFLOW). Deterministic: tests the pure counter logic, no watcher/executor timing.
 */
final class WorkerBurstDetectorTest {

  private final Path root = Path.of("/watched/root");

  @Test
  void crossesThresholdExactlyOncePerSecond() {
    WorkerBurstDetector detector = new WorkerBurstDetector();
    int threshold = 5;
    // First `threshold` events do not trip (count must EXCEED threshold).
    for (int i = 0; i < threshold; i++) {
      assertFalse(detector.recordEvent(root, threshold), "event " + i + " must not trip");
    }
    // The (threshold+1)-th event trips exactly once.
    assertTrue(detector.recordEvent(root, threshold), "crossing the threshold must trip");
    // Further events in the same second do not re-trip (sync already scheduled).
    assertFalse(detector.recordEvent(root, threshold), "must not re-trip within the same second");
    assertFalse(detector.recordEvent(root, threshold), "must not re-trip within the same second");
  }

  @Test
  void belowThresholdNeverTrips() {
    WorkerBurstDetector detector = new WorkerBurstDetector();
    for (int i = 0; i < 100; i++) {
      assertFalse(detector.recordEvent(root, 1_000), "below-threshold event must not trip");
    }
  }

  @Test
  void removeRootResetsState() {
    WorkerBurstDetector detector = new WorkerBurstDetector();
    for (int i = 0; i < 3; i++) {
      detector.recordEvent(root, 2);
    }
    detector.removeRoot(root);
    // After removal the counter restarts: first two events under the fresh state do not trip.
    assertFalse(detector.recordEvent(root, 2));
    assertFalse(detector.recordEvent(root, 2));
  }
}
