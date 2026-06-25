package io.justsearch.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tempdoc 628 Stage D-part2: the worker→Head fatal-reason marker handshake. */
class WorkerFatalReasonMarkerTest {

  @Test
  void writeThenReadAndClearReturnsReasonAndDeletesMarker() throws Exception {
    Path dataDir = Files.createTempDirectory("fatal-marker");

    // No marker yet → no reason.
    assertNull(WorkerFatalReasonMarker.readAndClear(dataDir));

    // Worker side stamps the corruption reason on its way out.
    WorkerFatalReasonMarker.write(dataDir, WorkerFatalReasonMarker.INDEX_CORRUPT);
    assertTrue(Files.exists(WorkerFatalReasonMarker.pathFor(dataDir)));

    // Head side reads it once...
    assertEquals(WorkerFatalReasonMarker.INDEX_CORRUPT, WorkerFatalReasonMarker.readAndClear(dataDir));
    // ...and it is cleared, so a later clean restart does not re-trigger the affordance.
    assertFalse(Files.exists(WorkerFatalReasonMarker.pathFor(dataDir)));
    assertNull(WorkerFatalReasonMarker.readAndClear(dataDir));
  }

  @Test
  void writeIsBestEffortAndNeverThrowsOnBadDir() {
    // A null dataDir must not throw (best-effort: a missing marker just means a generic worker-down).
    WorkerFatalReasonMarker.write(null, WorkerFatalReasonMarker.INDEX_CORRUPT);
    assertNull(WorkerFatalReasonMarker.readAndClear(null));
  }
}
