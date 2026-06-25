package io.justsearch.indexing.invariants;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class InvariantSuiteIT {

  @TempDir Path tempDir;

  @Test
  void guardPassesWhenParityMatches() throws Exception {
    seedIndex(tempDir, stableMetadata());
    IndexOpenGuard guard =
        new IndexMetadataParityGuard(() -> tempDir, () -> stableMetadata());
    assertDoesNotThrow(guard::checkOnOpen);
  }

  @Test
  void guardThrowsAndLogsWhenMetadataDrifts() throws Exception {
    seedIndex(tempDir, stableMetadata());
    Logger logger = (Logger) LoggerFactory.getLogger(IndexMetadataParityGuard.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      IndexOpenGuard guard =
          new IndexMetadataParityGuard(() -> tempDir, () -> driftedMetadata());
      // analyzer_fp / schema_ver are rebuild-requiring parity keys: a drift now surfaces as
      // SCHEMA_MISMATCH so the recovery wrapper rebuilds the index instead of crashing read-only
      // (tempdoc 581 §13). Query-time-only keys (similarity_fp/boosts_fp) still mark read-only.
      IndexRuntimeIOException ex =
          assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
      assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, ex.reason());
      assertTrue(ex.getMessage().contains("parity mismatch"));
      assertTrue(
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .anyMatch(msg -> msg.startsWith("PARITY_DIFF key=")),
          "Expected PARITY_DIFF markers to be logged");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private static Map<String, Object> stableMetadata() {
    return Map.of(
        "analyzer_fp", "baseline-analyzers",
        "schema_ver", "v1",
        "dag_hash", "deadbee",
        "similarity_fp", "bm25@0.9/0.4",
        "boosts_fp", "none");
  }

  private static Map<String, Object> driftedMetadata() {
    return Map.of(
        "analyzer_fp", "drifted-analyzers",
        "schema_ver", "v2",
        "dag_hash", "cafebad",
        "similarity_fp", "bm25@1/0.4",
        "boosts_fp", "none");
  }

  private static void seedIndex(Path indexPath, Map<String, Object> metadata) throws IOException {
    try (Directory directory = FSDirectory.open(indexPath);
        IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      Document doc = new Document();
      doc.add(new StringField("doc_id", "seed-doc", Field.Store.YES));
      writer.addDocument(doc);
      List<Map.Entry<String, String>> commitData = new ArrayList<>();
      for (var entry : metadata.entrySet()) {
        commitData.add(Map.entry(entry.getKey(), entry.getValue().toString()));
      }
      commitData.add(Map.entry("commit_id", UUID.randomUUID().toString()));
      writer.setLiveCommitData(commitData);
      writer.commit();
    }
  }
}
