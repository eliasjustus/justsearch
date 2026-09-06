/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState;
import io.justsearch.indexerworker.fixtures.FormatCapabilityExpectedState.ExpectedState;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.IngestionEvent;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.RecentIngestionEventsResponse;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Production-path acceptance for the deterministic format-capability matrix. */
@DisplayName("Format capability matrix production path")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class FormatCapabilityMatrixE2ETest {

  private static final int SEARCH_LIMIT = 20;
  private static final int SLICE_CHARS = 64;
  private static final ObjectMapper JSON = new ObjectMapper();

  @RegisterExtension
  static final TestEnvironmentProvisioner ENV = new TestEnvironmentProvisioner();

  private static final Map<FormatId, Path> FIXTURE_PATHS = new EnumMap<>(FormatId.class);

  private static WorkerProcessManager worker;
  private static MmfTestHarness mmf;
  private static GrpcTestClient grpcClient;
  private static ScheduledExecutorService heartbeatExecutor;
  private static final AtomicReference<Throwable> HEARTBEAT_FAILURE = new AtomicReference<>();

  @BeforeAll
  static void startWorker() throws Exception {
    HEARTBEAT_FAILURE.set(null);
    worker =
        WorkerProcessManager.fromDistribution(ENV.getWorkerDistDir(), ENV.getTempDir())
            .withJvmArgs(ENV.getWorkerJvmArgs());
    worker.spawnWorker();

    mmf = new MmfTestHarness(worker.getSignalFilePath());
    mmf.open();
    mmf.keepAlive();
    heartbeatExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "format-capability-worker-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
    heartbeatExecutor.scheduleAtFixedRate(
        () -> {
          try {
            mmf.keepAlive();
          } catch (Throwable failure) {
            HEARTBEAT_FAILURE.compareAndSet(null, failure);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);

    grpcClient = new GrpcTestClient(mmf.awaitPort(30_000, 100));
    assertTrue(grpcClient.isHealthy(), "Worker should be healthy before indexing");
  }

  @AfterAll
  static void stopWorker() throws Exception {
    try {
      if (heartbeatExecutor != null) {
        heartbeatExecutor.shutdownNow();
        assertTrue(
            heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS),
            "Worker heartbeat executor should terminate before MMF cleanup");
      }
    } finally {
      heartbeatExecutor = null;
      try {
        if (grpcClient != null) {
          grpcClient.close();
        }
      } finally {
        grpcClient = null;
        try {
          if (mmf != null) {
            mmf.close();
          }
        } finally {
          mmf = null;
          if (worker != null) {
            worker.close();
            worker = null;
          }
        }
      }
    }
  }

  @Test
  @DisplayName("all rows survive admission, extraction, indexing, search, fetch, and ledger reconciliation")
  void allRowsSurviveTheProductionPath() throws Exception {
    Path fixtureDirectory = ENV.getTempDir().resolve("format-capability");
    List<String> absolutePaths = new ArrayList<>();
    Set<String> searchMarkers = new HashSet<>();
    for (FormatId id : FormatId.values()) {
      Path path = FormatCapabilityFixtureFactory.write(fixtureDirectory, id).toAbsolutePath().normalize();
      FIXTURE_PATHS.put(id, path);
      absolutePaths.add(path.toString());
      assertTrue(
          searchMarkers.add(searchMarker(FormatCapabilityExpectedState.forFormat(id))),
          id + " must have a unique JUSTSEARCH_ marker for exact result selection");
    }

    assertEquals(FormatId.values().length, FIXTURE_PATHS.size(), "Every matrix row needs a fixture");
    assertEquals(
        FormatId.values().length,
        grpcClient.submitBatch(absolutePaths, 30_000).getAcceptedCount(),
        "Worker should admit every generated fixture");
    assertTrue(
        grpcClient.awaitIndexing(FormatId.values().length, 120_000, 200),
        "All format fixtures should converge in the index");
    assertHeartbeatHealthy();

    RecentIngestionEventsResponse ledger = grpcClient.recentIngestionEvents(100, 10_000);
    assertEquals(
        FormatId.values().length,
        ledger.getEventsList().stream().filter(FormatCapabilityMatrixE2ETest::isMatrixEvent).count(),
        "Each matrix row should produce one privacy-safe ledger event");

    for (FormatId id : FormatId.values()) {
      mmf.keepAlive();
      assertProductionPath(id, ledger);
    }
    assertHeartbeatHealthy();
  }

  private static void assertProductionPath(FormatId id, RecentIngestionEventsResponse ledger)
      throws Exception {
    ExpectedState expected = FormatCapabilityExpectedState.forFormat(id);
    Path source = FIXTURE_PATHS.get(id);
    String normalizedPath = PathNormalizer.normalizePath(source.toString());

    SearchResponse search =
        grpcClient.search(searchMarker(expected), SEARCH_LIMIT, PipelineConfigs.TEXT, 30_000);
    SearchResult hit =
        search.getResultsList().stream()
            .filter(
                result -> normalizedPath.equals(result.getFieldsOrDefault(SchemaFields.PATH, "")))
            .findFirst()
            .orElseGet(
                () -> {
                  fail(id + " was not returned for its unique marker; hits=" + search.getResultsCount());
                  return null;
                });
    assertNotNull(hit);

    CompleteSlice fetched = fetchCompleteSlice(hit.getId());
    assertEquals(expected.exactAnnotatedText(), fetched.content(), id + " stored content drifted");
    assertEquals(
        normalizedPath, fetched.metadata().get(SchemaFields.PATH), id + " stored path drifted");
    assertEquals(
        expected.mimeType(), fetched.metadata().get(SchemaFields.MIME), id + " MIME drifted");
    assertVisualEvidence(
        id, expected, fetched.metadata().get(SchemaFields.VISUAL_EXTRACTION_EVIDENCE));

    String pathHash = DocumentIdentityStore.pathHash(normalizedPath);
    List<IngestionEvent> matchingEvents =
        ledger.getEventsList().stream().filter(event -> pathHash.equals(event.getPathHash())).toList();
    assertEquals(1, matchingEvents.size(), id + " should have exactly one ledger event");
    IngestionEvent event = matchingEvents.getFirst();
    assertEquals("SUCCESS_FULL", event.getOutcomeClass(), id + " outcome drifted");
    assertEquals("SUCCESS_FULL", event.getArtifactStatus(), id + " artifact status drifted");
    assertEquals(expected.policyId(), event.getPolicyId(), id + " extraction policy drifted");
    assertEquals(expected.parserAdapterId(), event.getParserId(), id + " parser adapter drifted");
  }

  private static CompleteSlice fetchCompleteSlice(String docId) {
    StringBuilder content = new StringBuilder();
    Map<String, String> metadata = Map.of();
    Integer totalChars = null;
    int offset = 0;

    for (int page = 0; page < 100; page++) {
      FetchDocumentSliceResponse response =
          grpcClient.fetchDocumentSlice(docId, offset, SLICE_CHARS, 10_000);
      assertTrue(response.getFound(), "Stored document should exist: " + docId);
      assertEquals(docId, response.getDocId(), "Slice should retain the requested document id");

      if (totalChars == null) {
        totalChars = response.getTotalChars();
        metadata = Map.copyOf(response.getMetadataMap());
      } else {
        assertEquals(totalChars.intValue(), response.getTotalChars(), "Slice total changed while paging");
        assertEquals(metadata, response.getMetadataMap(), "Slice metadata changed while paging");
      }

      content.append(response.getContent());
      assertEquals(
          offset + response.getContent().length(),
          response.getNextOffsetChars(),
          "Slice next offset must equal consumed UTF-16 characters");
      assertTrue(
          response.getNextOffsetChars() > offset || !response.getTruncated(),
          "A truncated slice must make forward progress");
      offset = response.getNextOffsetChars();

      if (!response.getTruncated()) {
        assertEquals(totalChars.intValue(), offset, "Final slice must end at the declared total");
        assertEquals(totalChars.intValue(), content.length(), "Fetched content must cover the declared total");
        return new CompleteSlice(content.toString(), metadata);
      }
    }
    throw new AssertionError("Stored content exceeded the 100-page safety bound: " + docId);
  }

  private static void assertVisualEvidence(
      FormatId id, ExpectedState expected, String visualEvidence) throws Exception {
    assertNotNull(visualEvidence, id + " visual extraction evidence is missing");
    assertFalse(visualEvidence.isBlank(), id + " visual extraction evidence is blank");
    JsonNode evidence = JSON.readTree(visualEvidence);
    assertEquals("structured", evidence.path("route").asText(), id + " extraction route drifted");
    JsonNode counts = evidence.path("structuredElementCounts");
    assertTrue(counts.isObject(), id + " structured element counts must be an object");
    assertEquals(
        Set.of("tables", "headings", "lists"),
        Set.copyOf(counts.propertyNames()),
        id + " structured element count fields drifted");
    assertStructuredCount(id, counts, "tables", expected.structuredCounts().tables());
    assertStructuredCount(id, counts, "headings", expected.structuredCounts().headings());
    assertStructuredCount(id, counts, "lists", expected.structuredCounts().lists());
  }

  private static void assertStructuredCount(
      FormatId id, JsonNode counts, String field, int expected) {
    JsonNode actual = counts.get(field);
    assertTrue(actual.isIntegralNumber(), id + " " + field + " count must be an integer");
    assertEquals(expected, actual.intValue(), id + " " + field + " count drifted");
  }

  private static boolean isMatrixEvent(IngestionEvent event) {
    return FIXTURE_PATHS.values().stream()
        .map(Path::toString)
        .map(PathNormalizer::normalizePath)
        .map(DocumentIdentityStore::pathHash)
        .anyMatch(event.getPathHash()::equals);
  }

  private static void assertHeartbeatHealthy() {
    Throwable failure = HEARTBEAT_FAILURE.get();
    if (failure != null) {
      throw new AssertionError("Worker heartbeat failed", failure);
    }
  }

  private static String searchMarker(ExpectedState expected) {
    return expected.requiredMarkers().stream()
        .filter(marker -> marker.startsWith("JUSTSEARCH_"))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(expected.recipeId() + " has no owned JUSTSEARCH_ marker"));
  }

  private record CompleteSlice(String content, Map<String, String> metadata) {}
}
