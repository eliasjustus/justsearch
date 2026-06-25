package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tempdoc 628 Stage B: the rebuild-pending marker is a durable sibling, written/read/cleared. */
class IndexRecoveryMarkerTest {

  @Test
  void writeReadClearRoundTrip() throws Exception {
    Path base = Files.createTempDirectory("marker-test");
    Path indexDir = base.resolve("g-20260621-000000");
    Files.createDirectories(indexDir);

    assertFalse(IndexRecoveryMarker.exists(indexDir), "no marker before write");

    IndexRecoveryMarker.write(indexDir, "corrupt_index");
    assertTrue(IndexRecoveryMarker.exists(indexDir));
    assertEquals("corrupt_index", IndexRecoveryMarker.readReason(indexDir));

    // The marker is a SIBLING of the index dir, never a file inside it (so it can't disturb Lucene's
    // file set).
    assertTrue(Files.exists(base.resolve("g-20260621-000000.rebuild-pending")));
    assertFalse(Files.exists(indexDir.resolve(".rebuild-pending")));

    IndexRecoveryMarker.clear(indexDir);
    assertFalse(IndexRecoveryMarker.exists(indexDir), "marker gone after clear");
    assertEquals(null, IndexRecoveryMarker.readReason(indexDir));
  }
}
