/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    Path dataDir = tempDir.resolve("data");
    Path indexBase = dataDir.resolve("index");
    Files.createDirectories(dataDir);

    // 1. A real generation layout: Blue holds one document and is fine; a migration is already in
    //    flight and its Green carries a foreign fingerprint. That is the scenario the brake models —
    //    a rebuild that keeps failing the same way — and it is the path that reaches start()'s
    //    SCHEMA_MISMATCH handler, because the resumed migration opens Green with open() rather than
    //    openDeferred(). (See the reachability note in tempdoc 915 §C.8: a first-boot mismatch on an
    //    index that HAS segments takes openDeferred(), where ComponentsFactory swallows a guard
    //    failure because the initial open is read-only.)
    IndexGenerationManager genManager = new IndexGenerationManager(indexBase);
    var layout = genManager.initializeOrLoad();
    Path bluePath = genManager.resolveGenerationPathStrict(layout.state().active_generation());
    seedIndex(bluePath, null);

    IndexGenerationManager.State inFlight = genManager.startMigration("schema_mismatch");
    Path greenPath = genManager.resolveGenerationPathStrict(inFlight.building_generation());
    seedIndex(greenPath, "f".repeat(64));

    // 2. The brake, already spent on the shape THIS runtime would produce. Computing the target
    //    the same way the server does is the point: a hand-written key would make the test pass
    //    while the production lookup missed.
    String target = new SsotCommitMetadataSource().build().get(IndexFingerprint.COMMIT_META_KEY)
        .toString();
    for (int i = 0; i <= IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS; i++) {
      genManager.recordAutoRebuildAttempt(target);
    }
    assertTrue(
        genManager.autoRebuildAttemptsFor(target) > IndexGenerationManager.MAX_AUTO_REBUILD_ATTEMPTS,
        "precondition: the budget for this target is spent");

    // 3. Boot a real Worker over that data directory, under the production policy.
    publishConfig(dataDir, indexBase);
    server = new KnowledgeServer(workerConfig(dataDir));
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

    // (d) the recovery path out of the state. A user-initiated rebuild allocates Green beside
    //     Blue, and the promotion that completes it clears the brake — otherwise "run Rebuild
    //     index" would be advice that leaves the user exactly where they were.
    IndexGenerationManager.State migrating =
        genManager.startMigration("user_requested_rebuild");
    assertNotNull(migrating.building_generation(), "the operator rebuild is reachable from here");
    IndexGenerationManager.State promoted = genManager.promoteBuildingGenerationToActive();
    assertNull(promoted.auto_rebuild_key(), "a completed rebuild clears the brake");
    assertEquals(
        0,
        genManager.autoRebuildAttemptsFor(target),
        "the budget is restored, so a later genuine mismatch is not refused for this one");
  }

  /**
   * Commits one document. A non-null {@code fingerprintOverride} stamps a shape no runtime would
   * produce, which is what makes the open fail the parity check.
   */
  private static void seedIndex(Path path, String fingerprintOverride) throws Exception {
    java.util.Map<String, Object> meta =
        new java.util.HashMap<>(new SsotCommitMetadataSource().build());
    if (fingerprintOverride != null) {
      meta.put(IndexFingerprint.COMMIT_META_KEY, fingerprintOverride);
    }
    Map<String, Object> frozen = Map.copyOf(meta);
    try (RunningRuntime r =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(768),
                () -> frozen,
                new JsonSchemaCommitMetadataValidator())
            .atPath(path)
            .open()) {
      r.indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, "seeded-doc",
                      SchemaFields.DOC_UID, "seeded-doc#0",
                      SchemaFields.CONTENT, "a document Blue must keep serving")));
      r.commitOps().commitAndTrack(CommitReason.DRAIN);
    }
  }

  private static void publishConfig(Path dataDir, Path indexBase) {
    ResolvedConfig rc =
        new ResolvedConfigBuilder()
            .contributeBaseSources()
            .putDefault("justsearch.data.dir", dataDir.toAbsolutePath().toString())
            .putDefault("justsearch.index.base_path", indexBase.toAbsolutePath().toString())
            // The key really is un-prefixed here (ResolvedConfigBuilder:1545). Getting it wrong is
            // silent: the policy falls back to the DEV default REBUILD_BACKUP_FIRST, the mismatch is
            // "recovered" destructively instead of propagating, and the brake branch never runs -
            // which is precisely what the rebuildBrakeExhaustedForTest() precondition caught.
            .putDefault("index.schema_mismatch.policy", "BLUE_GREEN_MIGRATE")
            .build();
    ConfigStore.setGlobal(new ConfigStore(rc));
  }

  private static WorkerConfig workerConfig(Path dataDir) {
    ResolvedConfig rc = ConfigStore.global().get();
    return new WorkerConfig(
        "127.0.0.1",
        0,
        30_000L,
        128,
        64 * 1024 * 1024,
        dataDir,
        rc.search().collection(),
        60_000L,
        "0.0.0-test",
        new SsotCommitMetadataSource().build(),
        "test-manifest",
        500L,
        "block");
  }
}
