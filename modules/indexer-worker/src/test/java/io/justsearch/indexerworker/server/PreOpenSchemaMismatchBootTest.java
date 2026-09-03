/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 915 §C.12 — the schema-mismatch decision must not depend on HOW the index is opened.
 *
 * <p>It used to. The active generation opens {@code openDeferred()} whenever it has segments, and a
 * deferred open reaches {@code ComponentsFactory} as read-only, where a guard failure is logged
 * rather than raised. So on the boot path most installs take — an existing index, with documents,
 * whose shape changed — the automatic blue/green migration never started. The status surface still
 * said {@code reindex_required}, which is why nothing looked broken; the Worker simply never acted.
 *
 * <p>Detection now happens BEFORE the open-mode choice, reading the last commit's user data off the
 * directory. These tests are all boot-level for that reason: every one of them passes at unit level
 * against the broken code, because the defect was never in a unit.
 */
@Timeout(180)
final class PreOpenSchemaMismatchBootTest {

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort
      }
      server = null;
    }
  }

  private IndexGenerationManager.State stateAfterBoot(WorkerBootFixture.Layout layout)
      throws IOException {
    return new IndexGenerationManager(layout.indexBase()).initializeOrLoad().state();
  }

  /** (a) The headline case: segments present, shape changed, production policy. */
  @Test
  void aChangedShapeOnAnIndexWithSegmentsStartsTheMigrationAtBoot(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    IndexGenerationManager.State after = stateAfterBoot(layout);
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(),
        after.migration_state(),
        "an index whose shape changed must start migrating at boot — this is the whole point of"
            + " making BLUE_GREEN_MIGRATE the production default");
    assertNotNull(after.building_generation(), "a Green generation was allocated");
    assertTrue(server.getPort() > 0, "and Blue keeps serving while it rebuilds");
  }

  /** (a) The same index under the refusing policy. */
  @Test
  void aChangedShapeOnAnIndexWithSegmentsIsRefusedUnderFailClosed(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), "f".repeat(64), 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");

    KnowledgeServer refusing = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    IOException ex = assertThrows(IOException.class, refusing::start);
    assertTrue(
        KnowledgeServer.isSchemaMismatch(ex),
        "FAIL_CLOSED must refuse for the schema-mismatch reason, not some incidental failure");
    assertEquals(
        IndexGenerationManager.MigrationState.IDLE.name(),
        stateAfterBoot(layout).migration_state(),
        "refusing must not have started a migration");
  }

  /** (b) The negative: a matching index still opens deferred, silently. */
  @Test
  void aMatchingIndexWithSegmentsBootsWithoutMigratingOrWarning(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
      server.start();

      assertEquals(
          IndexGenerationManager.MigrationState.IDLE.name(),
          stateAfterBoot(layout).migration_state(),
          "a matching index must not be migrated — a detector that fires on everything is not a"
              + " detector");
      assertTrue(server.getPort() > 0);
      assertFalse(
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .anyMatch(m -> m.contains("PARITY_DIFF") || m.contains("PRE-OPEN")),
          "and it must do so silently");
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
  }

  /** (c) The real upgrade path, and the one O7 broke: a legacy index with no fingerprint at all. */
  @Test
  void aLegacyIndexWithSegmentsMigratesAtBoot(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), WorkerBootFixture.NO_FINGERPRINT, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    IndexGenerationManager.State after = stateAfterBoot(layout);
    assertEquals(
        IndexGenerationManager.MigrationState.MIGRATING.name(),
        after.migration_state(),
        "every index built before index_fingerprint existed looks like this — if the upgrade"
            + " rebuild does not start here it starts nowhere");
    assertNotNull(after.building_generation());
  }

  /** (e) The fresh install: an index that exists but holds nothing is not a migration candidate. */
  @Test
  void aFreshEmptyIndexDoesNotMigrateAtBoot(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), WorkerBootFixture.NO_FINGERPRINT, 0);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertEquals(
        IndexGenerationManager.MigrationState.IDLE.name(),
        stateAfterBoot(layout).migration_state(),
        "a first launch must not spend a rebuild on an index with nothing in it (the shared"
            + " ParityDiagnostics predicate, same one the status surface uses)");
    assertNull(stateAfterBoot(layout).building_generation());
  }

  /**
   * (d) The other half of O7: a schema mismatch escaping the deferred writer upgrade must not be
   * filed under the generic background-init WARN. That catch is why the failure was invisible even
   * on the boots where it did surface.
   */
  @Test
  void aSchemaMismatchFromTheDeferredUpgradeIsNotReportedAsNonFatal() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KnowledgeServer.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      Exception wrapped =
          new IllegalStateException("deferred upgrade failed", IndexMetadataParityGuard.schemaMismatch());
      KnowledgeServer.logBackgroundInitFailure(wrapped);
      KnowledgeServer.logBackgroundInitFailure(new IllegalStateException("ONNX session failed"));

      var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
      assertTrue(
          messages.stream().anyMatch(m -> m.contains("Ingestion is STOPPED")),
          "a stopped ingestion pipeline is not a degraded capability; got: " + messages);
      assertFalse(
          messages.stream()
              .anyMatch(
                  m ->
                      m.contains("non-fatal")
                          && messages.indexOf(m) == 0),
          "the mismatch must not take the non-fatal branch; got: " + messages);
      assertTrue(
          messages.stream().anyMatch(m -> m.contains("non-fatal")),
          "an unrelated background failure still takes the non-fatal branch");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** The classifier the branch above turns on, including the wrapping the upgrade path applies. */
  @Test
  void schemaMismatchIsRecognisedThroughTheCauseChain() {
    assertTrue(KnowledgeServer.isSchemaMismatch(IndexMetadataParityGuard.schemaMismatch()));
    assertTrue(
        KnowledgeServer.isSchemaMismatch(
            new IllegalStateException("wrapped", IndexMetadataParityGuard.schemaMismatch())));
    assertFalse(KnowledgeServer.isSchemaMismatch(new IllegalStateException("unrelated")));
    assertFalse(
        KnowledgeServer.isSchemaMismatch(
            new IndexRuntimeIOException(
                IndexRuntimeIOException.Reason.CORRUPT_INDEX, "corrupt", null)),
        "corruption has its own recovery path and must not be mistaken for a shape change");
  }
}
