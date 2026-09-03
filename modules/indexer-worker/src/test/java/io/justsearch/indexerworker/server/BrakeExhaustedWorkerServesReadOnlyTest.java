/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 915 §C.8 — what a Worker does after it has spent its automatic-rebuild budget.
 *
 * <p>The first cut of the brake returned early from {@code KnowledgeServer.start()}. That skipped
 * gRPC bind, the port write, the indexing loop and the whole {@code appServices} construction, so
 * the Worker exited with no port and no fatal-reason marker: the reason code this change added was
 * unreachable, and so was the read-only serving it promised. A constant existing in
 * {@code LifecycleReasonCode} says nothing about whether anything can emit it — this test drives
 * the real server into the exhausted state and reads the answer off the wire.
 *
 * <p>Deliberately an emit-chain test, not a constant test: it asserts the Worker still binds and
 * serves (a, c) AND that the status payload carries the pair the Head maps to
 * {@code index.rebuild_brake_exhausted} (b), AND that the operator's recovery path is reachable
 * from that state and clears the brake (d).
 */
@Timeout(120)
final class BrakeExhaustedWorkerServesReadOnlyTest {

  private KnowledgeServer server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort; the assertions have already run
      }
    }
  }

  @Test
  void anExhaustedBrakeServesSearchReadOnlyAndReportsWhyIngestionStopped(@TempDir Path tempDir)
      throws Exception {
    // 1. A real generation layout: Blue holds one document and is fine; a migration is already in
    //    flight and its Green carries a foreign fingerprint. That is the scenario the brake models —
    //    a rebuild that keeps failing the same way — and it is the path that reaches start()'s
    //    SCHEMA_MISMATCH handler, because the resumed migration opens Green with open() rather than
    //    openDeferred(). Seeded through WorkerBootFixture: the inline copy this test used to carry
    //    seeded Blue with FieldCatalogDef.forTesting(768) instead of the catalog the Worker loads,
    //    which is the fork the fixture exists to prevent.
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    WorkerBootFixture.seedInFlightGreen(layout, "f".repeat(64), 1);
    IndexGenerationManager genManager = layout.genManager();

    // 2. The brake, already spent on the shape THIS runtime would produce. Computing the target
    //    the same way the server does is the point: a hand-written key would make the test pass
    //    while the production lookup missed.
    String target = WorkerBootFixture.currentFingerprint();
    for (int i = 0; i <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; i++) {
      genManager.recordAutoRebuildAttempt(target);
    }
    assertTrue(
        genManager.autoRebuildAttemptsFor(target) > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
        "precondition: the budget for this target is spent");

    // 3. Boot a real Worker over that data directory, under the production policy.
    WorkerBootFixture.publishConfig(
        layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    server = new KnowledgeServer(WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    // (a) the Worker took the exhausted-brake path AND finished starting. The first assertion is
    //     what makes the rest mean anything: a bound port and a served search are equally true of an
    //     ordinary boot, so without it this test passes whether or not the branch ever ran.
    assertTrue(
        server.rebuildBrakeExhaustedForTest(),
        "precondition: the boot actually took the exhausted-brake path");
    assertTrue(server.isRunning(), "the Worker must not treat an exhausted brake as a fatal start");
    assertTrue(server.getPort() > 0, "gRPC must be bound: a Worker with no port is a Worker gone");

    channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();

    // (b) the status payload says WHY ingestion stopped, in the vocabulary the Head maps to
    //     index.rebuild_brake_exhausted.
    StatusResponse status =
        IngestServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .indexStatus(StatusRequest.newBuilder().build());
    assertEquals(
        "BLOCKED_REBUILD_BRAKE",
        status.getCompatibility().getSchemaCompatState(),
        "the compat state is the wire carrier for the new reason code");
    assertEquals(
        "rebuild_brake_exhausted",
        status.getCompatibility().getReindexRequiredReason(),
        "reindexRequiredReason is what StatusLifecycleHandler turns into"
            + " index.rebuild_brake_exhausted");
    assertTrue(
        status.getCompatibility().getReindexRequired(),
        "an exhausted brake is a reindex-required state");

    // (c) Blue still serves. This is the promise the read-only fall-through makes; a Worker that
    //     binds but cannot answer a query has kept the letter of it and none of the substance.
    SearchResponse search =
        SearchServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .search(SearchRequest.newBuilder().setQuery("*").setLimit(10).build());
    assertNotNull(search, "search must answer while the brake is exhausted");

    // (d) the recovery path out of the state, driven over the wire rather than by calling the
    //     generation manager directly. core.rebuild-index (RebuildIndexHandler) resolves to
    //     IndexingService.startMigration(USER_REQUESTED_REBUILD) → MigrationOps → this exact RPC,
    //     so this is the Worker half of the chain the readiness notice's remedy promises. The Head
    //     half (handler → op-lease → RemoteKnowledgeClient) is app-services' and is covered there;
    //     what could not be asserted from a fixture call is that the RPC is even reachable in the
    //     braked state, which is where appServices is built from a read-only runtime.
    MigrationStartResponse rebuild =
        IngestServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .startMigration(
                MigrationStartRequest.newBuilder()
                    .setReason(MigrationSource.USER_REQUESTED_REBUILD.wire())
                    .setRestartWorker(false)
                    .build());
    assertTrue(rebuild.getAccepted(), "the operator rebuild is reachable from here: "
        + rebuild.getError());
    assertNotNull(rebuild.getBuildingGenerationId(), "and it allocates a Green beside Blue");
    assertTrue(!rebuild.getBuildingGenerationId().isBlank());
    // A FRESH manager for everything after the boot. IndexGenerationManager caches state.json
    // per instance and invalidates only on its OWN writes, so the seeding instance above would
    // answer from a pre-boot snapshot and this arm would assert against state the Worker has since
    // replaced.
    IndexGenerationManager postBoot = new IndexGenerationManager(layout.indexBase());
    IndexGenerationManager.State promoted = postBoot.promoteBuildingGenerationToActive();
    assertNull(promoted.auto_rebuild_key(), "a completed rebuild clears the brake");
    assertEquals(
        0,
        postBoot.autoRebuildAttemptsFor(target),
        "the budget is restored, so a later genuine mismatch is not refused for this one");
  }

}
