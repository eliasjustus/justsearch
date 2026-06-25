package io.justsearch.indexerworker.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 589 Fix A — {@code readStateBestEffort()}'s cache must reflect writes and be a true cache.
 *
 * <p>The race the fix targets — a non-volatile {@code lastReadVersion} paired non-atomically with the
 * cached {@code State} and a non-atomic {@code stateVersion++} — is removed by-construction (one
 * immutable holder behind a single {@code volatile}); it is not deterministically unit-testable. This
 * pins the single-threaded contract the holder must preserve: a cache HIT returns the same instance
 * when nothing changed, and a write INVALIDATES the cache so the next read re-loads the new state.
 * {@link IndexGenerationManager} had no unit test before this.
 */
final class IndexGenerationManagerCacheTest {

  @Test
  void readStateBestEffort_cachesUntilWrite_thenReflectsTheWrite(@TempDir Path tempDir)
      throws Exception {
    IndexGenerationManager mgr = new IndexGenerationManager(tempDir.resolve("index"));
    var layout = mgr.initializeOrLoad();
    assertNotNull(layout, "layout created");

    IndexGenerationManager.State read1 = mgr.readStateBestEffort();
    IndexGenerationManager.State read2 = mgr.readStateBestEffort();
    assertNotNull(read1, "state present after initializeOrLoad");
    assertSame(read1, read2, "second read is a cache hit (same instance, no re-parse)");

    // A real write (set the pause flag) must invalidate the cache. (setMigrationPaused persists
    // via writeState without creating a generation directory, so it's a clean cache-invalidation
    // probe.)
    mgr.setMigrationPaused(true, "test");
    IndexGenerationManager.State read3 = mgr.readStateBestEffort();
    assertNotSame(read1, read3, "cache invalidated on write — a fresh State is re-loaded from disk");
    assertEquals(Boolean.TRUE, read3.migration_paused(), "re-read reflects the write");
    assertEquals("test", read3.pause_reason(), "re-read carries the persisted reason");

    // The new state is itself cached until the next write.
    assertSame(read3, mgr.readStateBestEffort(), "post-write read is cached again");
  }
}
