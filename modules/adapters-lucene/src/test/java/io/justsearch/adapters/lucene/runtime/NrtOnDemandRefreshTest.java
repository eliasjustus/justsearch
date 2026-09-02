/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 885 item 19 — the reopen-on-demand seam, end to end through a real index.
 *
 * <p>Every test here suspends the background reopen thread first. That is the whole point: with the
 * {@code ControlledRealTimeReopenThread} stopped, the ONLY thing that can make a freshly written
 * document visible is the refresh {@link SearcherBridge} performs before acquiring. A test that
 * left the thread running would pass in both modes and prove nothing.
 */
final class NrtOnDemandRefreshTest {

  private static final int DIM = 768;

  private static String config(Path dataDir, String nrtBlock) {
    return "app:\n"
        + "  data_dir: "
        + dataDir.toString().replace("\\", "\\\\")
        + "\n"
        + "index:\n"
        + "  collections:\n"
        + "    - name: runtime\n"
        + "      roots: ['ignored']\n"
        + "  vector:\n"
        + "    dimension: "
        + DIM
        + "\n"
        + nrtBlock;
  }

  private static final String ON_DEMAND =
      "  nrt:\n    mode: on_demand\n    background_reopen_ms: 2000\n"
          + "    on_demand_max_stale_ms: 1000\n";

  private static final String CONTINUOUS = "  nrt:\n    mode: continuous\n";

  /** Opens a runtime under the given NRT config with the background reopen thread suspended. */
  private static RunningRuntime openWithBackgroundReopenStopped(String nrtBlock) throws IOException {
    Path dataDir = Files.createTempDirectory("justsearch-nrt-ondemand-");
    Path cfg = Files.createTempFile("justsearch-nrt-ondemand-", ".yaml");
    Files.writeString(cfg, config(dataDir, nrtBlock));
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(DIM),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .ephemeral()
            .open();
    runtime.commitOps().suspendNrtRefresh();
    return runtime;
  }

  private static void index(RunningRuntime runtime, int count) {
    for (int i = 0; i < count; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "doc-" + i,
                      SchemaFields.DOC_UID, "doc-" + i + "#0",
                      SchemaFields.PATH, "test/doc-" + i + ".txt",
                      SchemaFields.CONTENT, "content " + i)));
    }
  }

  @Test
  @DisplayName("on_demand: a search after N added docs sees them with the background thread stopped")
  void onDemandSearchSeesNewDocumentsWithoutTheBackgroundThread() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND);
      assertEquals(0L, runtime.indexCountOps().docCount(), "empty index before any write");
      long reopensBefore = runtime.runtimeGaugesSnapshot().reopenCount();

      index(runtime, 3);

      // No commit and no background thread: only the on-demand refresh can make these visible.
      assertEquals(3L, runtime.indexCountOps().docCount());
      assertTrue(
          runtime.runtimeGaugesSnapshot().reopenCount() > reopensBefore,
          "the foreground acquire must have reopened the searcher");
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("on_demand: an idle index does not reopen, however many searches arrive")
  void onDemandDoesNotReopenWhenNothingWasWritten() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(ON_DEMAND);
      index(runtime, 2);
      assertEquals(2L, runtime.indexCountOps().docCount());

      long reopensAfterFirstSearch = runtime.runtimeGaugesSnapshot().reopenCount();
      for (int i = 0; i < 10; i++) {
        assertEquals(2L, runtime.indexCountOps().docCount());
      }
      assertEquals(
          reopensAfterFirstSearch,
          runtime.runtimeGaugesSnapshot().reopenCount(),
          "a fresh searcher must not be reopened again — age alone is not a reason to reopen");
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  /**
   * The discriminator for the mode gate. Same suspended-thread setup, same writes; in continuous
   * mode the foreground path must NOT refresh, so the documents stay invisible. If the seam ever
   * stopped consulting {@code nrtMode} this is the test that reds.
   */
  @Test
  @DisplayName("continuous: the foreground path does not refresh, so new docs stay invisible")
  void continuousModeDoesNotRefreshOnTheForegroundPath() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(CONTINUOUS);
      index(runtime, 3);
      assertEquals(
          0L,
          runtime.indexCountOps().docCount(),
          "continuous mode relies on the background thread, which this test stopped");
      assertEquals(0L, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("segments_since_reopen counts the writer's new segments and resets on reopen")
  void segmentsSinceReopenTracksTheReopenBacklog() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(CONTINUOUS);
      assertEquals(0L, runtime.runtimeGaugesSnapshot().segmentsSinceReopen());

      index(runtime, 2);
      // commit() flushes the RAM buffer into a new segment; nothing has reopened over it yet.
      runtime.commitOps().commitAndTrack();
      assertTrue(
          runtime.runtimeGaugesSnapshot().segmentsSinceReopen() > 0,
          "a flushed segment the current searcher cannot see is the next reopen's backlog");
      assertEquals(1L, runtime.runtimeGaugesSnapshot().commitCount());

      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(
          0L,
          runtime.runtimeGaugesSnapshot().segmentsSinceReopen(),
          "the reopen consumed the backlog");
      assertEquals(1L, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  @Test
  @DisplayName("a refresh with nothing to reopen does not increment the reopen count")
  void refreshWithNothingNewDoesNotCountAsAReopen() throws Exception {
    String prev = System.getProperty("justsearch.config");
    RunningRuntime runtime = null;
    try {
      runtime = openWithBackgroundReopenStopped(CONTINUOUS);
      index(runtime, 1);
      runtime.commitOps().maybeRefreshBlocking();
      long after = runtime.runtimeGaugesSnapshot().reopenCount();
      assertEquals(1L, after);

      // This is the mechanism the on_demand background thread relies on to idle: Lucene's
      // openIfChanged returns null on an unchanged index, so afterRefresh(false) fires and the
      // counter does not move.
      runtime.commitOps().maybeRefreshBlocking();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(after, runtime.runtimeGaugesSnapshot().reopenCount());
    } finally {
      closeQuietly(runtime);
      restore(prev);
    }
  }

  private static void closeQuietly(RunningRuntime runtime) {
    if (runtime == null) return;
    try {
      runtime.close();
    } catch (RuntimeException e) {
      /* best-effort */
    }
  }

  private static void restore(String prev) {
    if (prev == null) System.clearProperty("justsearch.config");
    else System.setProperty("justsearch.config", prev);
  }
}
