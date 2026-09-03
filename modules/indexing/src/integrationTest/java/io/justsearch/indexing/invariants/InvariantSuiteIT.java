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
      // index_fingerprint is the one rebuild-requiring parity key: a drift surfaces as
      // SCHEMA_MISMATCH so the recovery path rebuilds (blue/green under the production default)
      // instead of leaving a dead index (tempdoc 915 §C).
      IndexRuntimeIOException ex =
          assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
      assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, ex.reason());
      assertTrue(ex.getMessage().contains("index_fingerprint mismatch"));
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

  /**
   * A drift confined to {@code boosts_fp} is query-time scoring config: the documents on disk are
   * exactly what this runtime would write, so the shard goes read-only until the config is
   * realigned and nothing is rebuilt. This is the half of the two-key split that a fingerprint-only
   * test cannot see — without it, collapsing boosts_fp into index_fingerprint would still pass.
   */
  @Test
  void boostsDriftMarksReadOnlyAndNeverRequestsARebuild() throws Exception {
    seedIndex(tempDir, stableMetadata());
    IndexOpenGuard guard =
        new IndexMetadataParityGuard(() -> tempDir, InvariantSuiteIT::boostsOnlyDriftMetadata);
    // IndexRuntimeIOException is not an IllegalStateException, so this expected type is itself the
    // assertion that the drift was NOT routed into schema-mismatch recovery.
    IllegalStateException ex = assertThrows(IllegalStateException.class, guard::checkOnOpen);
    assertTrue(ex.getMessage().contains("read-only"), ex.getMessage());
  }

  /**
   * The case the first cut of tempdoc 915 got wrong. Every index built before {@code
   * index_fingerprint} existed has a blank stored side FOREVER, so skipping a blank stored value
   * left the guard permanently inert on exactly the installs it was meant to protect. An index
   * whose physical shape was never recorded cannot be shown to match this runtime, so it migrates
   * once — the deliberate one-time upgrade rebuild the wave-2 release is built around.
   */
  @Test
  void aLegacyIndexWithNoFingerprintIsMigratedRatherThanIgnored() throws Exception {
    seedIndex(tempDir, legacyMetadata());
    IndexOpenGuard guard =
        new IndexMetadataParityGuard(() -> tempDir, InvariantSuiteIT::stableMetadata);

    IndexRuntimeIOException ex =
        assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
    assertEquals(IndexRuntimeIOException.Reason.SCHEMA_MISMATCH, ex.reason());
  }

  /** The diff has to say WHY, or the log and the status surface cannot explain the rebuild. */
  @Test
  void aLegacyIndexDiffNamesItselfAsLegacyRatherThanAsAShapeChange() throws Exception {
    seedIndex(tempDir, legacyMetadata());
    Logger logger = (Logger) LoggerFactory.getLogger(IndexMetadataParityGuard.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      IndexOpenGuard guard =
          new IndexMetadataParityGuard(() -> tempDir, InvariantSuiteIT::stableMetadata);
      assertThrows(IndexRuntimeIOException.class, guard::checkOnOpen);
      assertTrue(
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .anyMatch(msg -> msg.contains("index-without-fingerprint")),
          "the PARITY_DIFF marker must carry the legacy hint, not the generic shape-change one");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * A legacy index is only migrated on the key that costs a rebuild. An unverifiable benign key is
   * not worth reporting, let alone acting on, so a legacy index missing only boosts_fp opens.
   */
  @Test
  void aBlankBenignKeyOnALegacyIndexIsNotAMismatch() throws Exception {
    seedIndex(tempDir, Map.of("index_fingerprint", "baseline-shape", "dag_hash", "deadbee"));
    IndexOpenGuard guard =
        new IndexMetadataParityGuard(() -> tempDir, InvariantSuiteIT::stableMetadata);
    assertDoesNotThrow(guard::checkOnOpen);
  }

  /**
   * Neither side blank: with the stored fingerprint present but the runtime unable to compute one,
   * the guard must say nothing rather than declare a mismatch. An absent answer is not evidence of
   * difference, and the cost of getting this wrong is a full rebuild.
   */
  @Test
  void anIndeterminateExpectedFingerprintIsNotAMismatch() throws Exception {
    seedIndex(tempDir, stableMetadata());
    IndexOpenGuard guard =
        new IndexMetadataParityGuard(() -> tempDir, InvariantSuiteIT::fingerprintUnavailableMetadata);
    assertDoesNotThrow(guard::checkOnOpen);
  }

  /**
   * A brand-new install: the index exists and has committed, but holds no documents yet. There is
   * no content that could have been written under the wrong shape, so migrating it would rebuild
   * emptiness — and would do so on the very first launch, before the user has indexed anything.
   *
   * <p>This is also the case where the open-time guard and the reported status state used to be
   * able to disagree: the status path already had a docCount guard, the guard did not. They now
   * share one predicate ({@code ParityDiagnostics.isIndexWithoutRecordedFingerprint}).
   */
  @Test
  void aFreshEmptyIndexWithNoFingerprintIsNotMigrated() throws Exception {
    seedEmptyIndex(tempDir, legacyMetadata());
    IndexOpenGuard guard =
        new IndexMetadataParityGuard(() -> tempDir, InvariantSuiteIT::stableMetadata);

    assertDoesNotThrow(guard::checkOnOpen);
  }

  /**
   * Tempdoc 915 §C.5 — the WARN that says the parity check is NOT running must be said, because a
   * check that declines silently is indistinguishable from a check that passed. It must also be said
   * ONCE: a line repeated on every generation open is a line nobody reads.
   */
  @Test
  void theUncomputableFingerprintWarningIsEmittedOncePerBoot() throws Exception {
    IndexMetadataParityGuard.resetUncomputableWarnedForTest();
    seedIndex(tempDir, stableMetadata());
    Logger logger = (Logger) LoggerFactory.getLogger(IndexMetadataParityGuard.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      IndexOpenGuard guard =
          new IndexMetadataParityGuard(
              () -> tempDir, InvariantSuiteIT::fingerprintUnavailableMetadata);
      guard.checkOnOpen();
      guard.checkOnOpen();
      guard.checkOnOpen();
      long warns =
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .filter(msg -> msg.contains("Index parity is NOT being checked"))
              .count();
      assertEquals(1L, warns, "three opens, one warning");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      IndexMetadataParityGuard.resetUncomputableWarnedForTest();
    }
  }

  private static Map<String, Object> stableMetadata() {
    return Map.of(
        "index_fingerprint", "baseline-shape",
        "dag_hash", "deadbee",
        "boosts_fp", "none");
  }

  private static Map<String, Object> driftedMetadata() {
    return Map.of(
        "index_fingerprint", "drifted-shape",
        "dag_hash", "cafebad",
        "boosts_fp", "none");
  }

  private static Map<String, Object> boostsOnlyDriftMetadata() {
    return Map.of(
        "index_fingerprint", "baseline-shape",
        "dag_hash", "deadbee",
        "boosts_fp", "title=2.0");
  }

  /** Expected metadata from a runtime that could not compute a fingerprint at all. */
  private static Map<String, Object> fingerprintUnavailableMetadata() {
    return Map.of("dag_hash", "deadbee", "boosts_fp", "none");
  }

  /** An index committed before index_fingerprint existed: the retired keys, and none of the new. */
  private static Map<String, Object> legacyMetadata() {
    return Map.of(
        "index_schema_fp", "old-catalog-file-hash",
        "analyzer_fp", "old-analyzers",
        "schema_ver", "1.0.0",
        "boosts_fp", "none");
  }

  /** Same commit metadata, no documents: a committed but empty index. */
  private static void seedEmptyIndex(Path indexPath, Map<String, Object> metadata)
      throws IOException {
    try (Directory directory = FSDirectory.open(indexPath);
        IndexWriter writer =
            new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
      List<Map.Entry<String, String>> commitData = new ArrayList<>();
      for (var entry : metadata.entrySet()) {
        commitData.add(Map.entry(entry.getKey(), entry.getValue().toString()));
      }
      commitData.add(Map.entry("commit_id", UUID.randomUUID().toString()));
      writer.setLiveCommitData(commitData);
      writer.commit();
    }
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
