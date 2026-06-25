package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link IndexingDocumentOps} metadata helpers. */
class IndexingDocumentOpsTest {

  @Test
  void parsePublishedAt_corpusFormat() {
    Long ms = IndexingDocumentOps.parsePublishedAt("2023-11-27 08:45:59");
    assertNotNull(ms);
    // 2023-11-27T08:45:59Z in epoch millis
    assertEquals(1701074759000L, ms, "Should parse corpus date format as UTC");
  }

  @Test
  void parsePublishedAt_dateOnly() {
    Long ms = IndexingDocumentOps.parsePublishedAt("2023-11-27");
    assertNotNull(ms);
    // 2023-11-27T00:00:00Z in epoch millis
    assertEquals(1701043200000L, ms, "Should parse date-only as midnight UTC");
  }

  @Test
  void parsePublishedAt_isoFormat() {
    Long ms = IndexingDocumentOps.parsePublishedAt("2023-11-27T08:45:59");
    assertNotNull(ms);
    assertEquals(1701074759000L, ms, "Should parse ISO format as UTC");
  }

  @Test
  void parsePublishedAt_unparseable() {
    assertNull(IndexingDocumentOps.parsePublishedAt("not a date"));
  }

  @Test
  void parsePublishedAt_emptyString() {
    assertNull(IndexingDocumentOps.parsePublishedAt(""));
  }
}
