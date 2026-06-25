package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.IndexingService.IndexGcOutcome;
import io.justsearch.app.api.OperationLeaseService;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link IndexGcHandler} (slice 484 §3.6 / observations.md
 * `core.index-gc` closure). Mirrors {@link ReindexHandlerTest}'s lazy-supplier
 * + FakeIndexingService pattern.
 */
final class IndexGcHandlerTest {

  private static final OperationLeaseService LEASE = OperationLeaseService.noOp();

  /** Minimal IndexingService base — subclasses override what each test needs. */
  private static class FakeIndexingService implements IndexingService {
    @Override
    public List<Path> getWatchedPaths() {
      return List.of();
    }

    @Override
    public void addWatchedPath(Path path) {}

    @Override
    public int removeWatchedPath(Path path) {
      return 0;
    }

    @Override
    public void flush() {}
  }

  @Test
  void executeAcceptedSurfacesCounts() {
    AtomicInteger capturedKeep = new AtomicInteger(-1);
    AtomicReference<Boolean> capturedPmo = new AtomicReference<>(null);
    IndexGcHandler handler =
        new IndexGcHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
                    capturedKeep.set(keepLatest);
                    capturedPmo.set(pruneMarkedOnly);
                    return new IndexGcOutcome(true, 7, 5, "");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{}");
    assertTrue(result.success());
    assertEquals(0, capturedKeep.get(), "default keepLatest=0");
    assertEquals(Boolean.TRUE, capturedPmo.get(), "default pruneMarkedOnly=true");
    assertEquals(Boolean.TRUE, result.structuredData().get("accepted"));
    assertEquals(7, result.structuredData().get("markedCount"));
    assertEquals(5, result.structuredData().get("prunedCount"));
    assertTrue(result.message().contains("7"));
    assertTrue(result.message().contains("5"));
  }

  @Test
  void executePropagatesArgsVerbatim() {
    AtomicInteger capturedKeep = new AtomicInteger(-1);
    AtomicReference<Boolean> capturedPmo = new AtomicReference<>(null);
    IndexGcHandler handler =
        new IndexGcHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
                    capturedKeep.set(keepLatest);
                    capturedPmo.set(pruneMarkedOnly);
                    return new IndexGcOutcome(true, 0, 0, "");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{\"keepLatest\":3,\"pruneMarkedOnly\":false}");
    assertTrue(result.success());
    assertEquals(3, capturedKeep.get());
    assertEquals(Boolean.FALSE, capturedPmo.get());
  }

  @Test
  void executeRejectsNegativeKeepLatest() {
    IndexGcHandler handler =
        new IndexGcHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
                    throw new AssertionError("should not be called");
                  }
                },
            LEASE);
    OperationResult result = handler.execute("{\"keepLatest\":-1}");
    assertFalse(result.success());
    assertTrue(result.message().toLowerCase().contains("non-negative"));
  }

  @Test
  void executeWorkerRejectionSurfacesError() {
    IndexGcHandler handler =
        new IndexGcHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
                    return new IndexGcOutcome(false, 0, 0, "Concurrent GC in flight");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("Concurrent GC in flight"));
  }

  @Test
  void executeReturnsFailureWhenServiceNull() {
    IndexGcHandler handler = new IndexGcHandler(() -> null, LEASE);
    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("Indexing service unavailable"));
  }

  @Test
  void executeReturnsFailureWhenRunIndexGcUnsupported() {
    // IndexingService::unavailable returns an instance whose default runIndexGc
    // throws UnsupportedOperationException.
    IndexGcHandler handler = new IndexGcHandler(IndexingService::unavailable, LEASE);
    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("Index GC failed"));
  }

  @Test
  void executeReturnsFailureWhenRunIndexGcThrowsRuntime() {
    IndexGcHandler handler =
        new IndexGcHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
                    throw new RuntimeException("boom");
                  }
                },
            LEASE);

    OperationResult result = handler.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("boom"));
  }

  @Test
  void executeMalformedJsonReturnsFailure() {
    IndexGcHandler handler =
        new IndexGcHandler(
            () ->
                new FakeIndexingService() {
                  @Override
                  public IndexGcOutcome runIndexGc(int keepLatest, boolean pruneMarkedOnly) {
                    throw new AssertionError("should not be called");
                  }
                },
            LEASE);
    OperationResult result = handler.execute("not-json");
    assertFalse(result.success());
    assertTrue(result.message().toLowerCase().contains("invalid arguments json"));
  }
}
