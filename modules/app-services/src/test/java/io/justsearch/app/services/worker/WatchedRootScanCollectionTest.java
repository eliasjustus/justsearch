/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.ScanMode;
import io.justsearch.ipc.ScanRootProgress;
import io.justsearch.ipc.ScanRootRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C2 — a watched root's OWN initial scan carries the root's collection.
 *
 * <p>This file used to be the baseline pin for the opposite behaviour: the label reached the
 * watcher arm ({@code WorkerWatchFn.watch(rootPath, collection)}) but the scan arm had no channel
 * for it, so {@link RootLifecycleOps.ScanRootFn#scan} took only (rootPath, excludeGlobs,
 * progressConsumer) and the documents the initial scan admitted carried no {@code collection} at
 * all. The scan arm now takes the label too, and the assertions below are the positive inversion:
 * both arms must receive the SAME collection for the same root.
 */
@DisplayName("watched-root scan — collection carriage")
final class WatchedRootScanCollectionTest {

  @TempDir Path tempDir;

  /** Captures what each arm received for one addWatchedRoot call. */
  private record Arms(String watched, String scanned, String scannedRoot) {}

  private Arms addRootAndCapture(String label, Path root) throws Exception {
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state =
        new WatchedRootsState(
            watchedRoots, new WatchedRootsStore(tempDir.resolve(root.getFileName() + ".json"), null));

    AtomicReference<String> watchedCollection = new AtomicReference<>();
    AtomicReference<String> scannedCollection = new AtomicReference<>();
    AtomicReference<String> scannedRoot = new AtomicReference<>();
    java.util.concurrent.ExecutorService walkExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    RootLifecycleOps ops =
        new RootLifecycleOps(
            watchedRoots,
            state,
            () -> ExcludeMatcher.empty(true),
            (rootPath, collection, globs, progress) -> {
              scannedRoot.set(rootPath);
              scannedCollection.set(collection);
              return null;
            },
            new RootLifecycleOps.WorkerWatchFn() {
              @Override
              public void watch(String rootPath, String collection) {
                watchedCollection.set(collection);
              }

              @Override
              public void unwatch(String rootPath) {}
            },
            p -> null,
            s -> null,
            mock(SyncOps.class),
            walkExecutor);

    ops.addWatchedRoot(label, root);
    walkExecutor.shutdown();
    assertTrue(
        walkExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
        "the queued walk must finish before the assertions read what it dispatched");
    return new Arms(watchedCollection.get(), scannedCollection.get(), scannedRoot.get());
  }

  @Test
  @DisplayName("both the watcher arm AND the scan arm receive the root's collection")
  void labeledRootScanCarriesItsCollection() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("labeled"));

    Arms arms = addRootAndCapture("my-notes", root);

    assertTrue(arms.scannedRoot() != null, "the walk did dispatch a scan for the labeled root");
    assertEquals(
        "my-notes", arms.watched(), "the watcher arm carries the label (unchanged behaviour)");
    assertEquals(
        "my-notes",
        arms.scanned(),
        "the scan arm must carry the SAME label — the initial scan's documents belong to the"
            + " root's collection, not the default bucket");
  }

  @Test
  @DisplayName("a blank label normalizes to the default bucket on BOTH arms, not to null")
  void blankLabelNormalizesOnBothArms() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("unlabeled"));

    Arms arms = addRootAndCapture("  ", root);

    // Precision: proves the scan arm forwards addWatchedRoot's NORMALIZED label rather than
    // happening to agree by both being null/blank.
    assertEquals("default", arms.watched(), "watcher arm sees the normalized label");
    assertEquals("default", arms.scanned(), "scan arm sees the same normalized label");
  }

  /**
   * The tests above run against a test-supplied ScanRootFn, so they pin the Java threading but not
   * the PRODUCTION lambda that maps it onto the RPC. This drives the real
   * {@link RemoteKnowledgeClient#addWatchedRoot} over an in-process gRPC server and asserts the
   * {@link ScanRootRequest} the Worker would actually receive — so reverting that lambda's
   * collection argument to {@code null} fails here. Harness pattern per
   * {@link RemoteKnowledgeClientSearchRoundTripTest}.
   */
  @Nested
  @DisplayName("production lambda → ScanRootRequest (in-process gRPC)")
  class ProductionWireForwarding {

    private Server server;
    private ManagedChannel channel;
    private CapturingIngestService ingest;
    private RemoteKnowledgeClient client;
    private String prevDataDir;

    @BeforeEach
    void setUp() throws Exception {
      prevDataDir = System.getProperty("justsearch.data.dir");
      Path dataDir = Files.createDirectories(tempDir.resolve("data"));
      System.setProperty("justsearch.data.dir", dataDir.toString());

      String name = InProcessServerBuilder.generateName();
      ingest = new CapturingIngestService();
      server =
          InProcessServerBuilder.forName(name).directExecutor().addService(ingest).build().start();
      channel = InProcessChannelBuilder.forName(name).directExecutor().build();

      // Signal bus is never opened/read: connectForTesting bypasses reconnect() port discovery.
      MainSignalBus signalBus = new MainSignalBus(dataDir.resolve("signals/worker-signal.mmf"));
      client = new RemoteKnowledgeClient(signalBus, /*deadlineMs=*/ 5000, /*maxRetries=*/ 0);
      client.connectForTesting(channel);
    }

    @AfterEach
    void tearDown() throws Exception {
      if (client != null) {
        client.close();
        client = null;
      }
      if (channel != null) {
        channel.shutdownNow();
        channel.awaitTermination(2, TimeUnit.SECONDS);
      }
      if (server != null) {
        server.shutdownNow();
        server.awaitTermination(2, TimeUnit.SECONDS);
      }
      if (prevDataDir == null) {
        System.clearProperty("justsearch.data.dir");
      } else {
        System.setProperty("justsearch.data.dir", prevDataDir);
      }
    }

    @Test
    @DisplayName("addWatchedRoot's scan reaches the wire with the root's collection set")
    void scanRequestCarriesTheRootsCollection() throws Exception {
      Path root = Files.createDirectories(tempDir.resolve("wired"));

      client.addWatchedRoot("my-notes", root);

      assertTrue(
          ingest.received.await(10, TimeUnit.SECONDS),
          "the background walk must dispatch a ScanRoot RPC");
      ScanRootRequest request = ingest.request.get();
      assertEquals(
          "my-notes",
          request.getCollection(),
          "the production ScanRootFn must forward the collection into ScanRootRequest — a literal"
              + " null here is the defect this pins");
      assertEquals(
          root.toAbsolutePath().normalize().toString(),
          request.getRootPath(),
          "same request, so the collection cannot belong to some other root's scan");
      assertEquals(
          ScanMode.SCAN_MODE_INITIAL, request.getMode(), "a watched root's own scan is the initial one");
    }
  }

  /** In-process IngestService that records the ScanRootRequest and completes the stream. */
  private static final class CapturingIngestService
      extends IngestServiceGrpc.IngestServiceImplBase {
    private final AtomicReference<ScanRootRequest> request = new AtomicReference<>();
    private final CountDownLatch received = new CountDownLatch(1);

    @Override
    public void scanRoot(ScanRootRequest req, StreamObserver<ScanRootProgress> responseObserver) {
      request.set(req);
      responseObserver.onNext(ScanRootProgress.newBuilder().setComplete(true).build());
      responseObserver.onCompleted();
      received.countDown();
    }
  }
}
