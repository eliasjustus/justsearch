package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.core.dto.Cursor;
import io.justsearch.core.dto.Query;
import io.justsearch.core.dto.Result;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchServiceGrpc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 683 — in-process gRPC round-trip for the {@code SearchPort.search(Query)} path:
 * Core {@code Query} → IPC {@code SearchRequest} marshalling, and IPC {@code SearchResponse} →
 * Core {@code Result} mapping ({@code toCoreResult}), exercised against a hand-rolled
 * {@link SearchServiceGrpc.SearchServiceImplBase} stub via the {@code connectForTesting} seam
 * (no real port, no signal-bus discovery). Harness pattern per {@link RemoteIndexingJobsBridgeTest}.
 */
@DisplayName("RemoteKnowledgeClient search round-trip (in-process gRPC)")
final class RemoteKnowledgeClientSearchRoundTripTest {

  private Server server;
  private ManagedChannel channel;
  private StubSearchService stub;
  private RemoteKnowledgeClient client;
  private String prevDataDir;

  @BeforeEach
  void setUp() throws Exception {
    prevDataDir = System.getProperty("justsearch.data.dir");
    Path tempDataDir = Files.createTempDirectory("justsearch-683-roundtrip-");
    System.setProperty("justsearch.data.dir", tempDataDir.toString());

    String name = InProcessServerBuilder.generateName();
    stub = new StubSearchService();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(stub).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();

    // Signal bus is never opened/read: the connectForTesting seam bypasses reconnect() discovery.
    MainSignalBus signalBus = new MainSignalBus(tempDataDir.resolve("signals/worker-signal.mmf"));
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
  @DisplayName("marshals Query text/limit and maps SearchResponse hits/nextCursor/totalHits exactly")
  void searchRoundTripWithoutCursor() {
    stub.cannedResponse =
        SearchResponse.newBuilder()
            .addResults(SearchResult.newBuilder().setId("doc-a").setScore(0.75f))
            .addResults(SearchResult.newBuilder().setId("doc-b").setScore(0.25f))
            .setTotalHits(42L)
            .setTookMs(7L)
            .setNextCursor("cursor-next")
            .build();

    Result result = client.search(textQuery("hello world", 5, null));

    // (a) Marshalling: the wire SearchRequest carries the Query's fields.
    SearchRequest sent = stub.lastRequest.get();
    assertNotNull(sent, "stub should have received a SearchRequest");
    assertEquals("hello world", sent.getQuery());
    assertEquals(5, sent.getLimit());
    assertEquals("", sent.getCursor(), "no cursor on the Query means no cursor on the wire");
    assertEquals(PipelineConfigs.HYBRID, sent.getPipeline(), "cursor-less search uses HYBRID");

    // (b) Mapping: the core Result mirrors the canned response field-by-field.
    assertEquals(2, result.hits().size());
    assertEquals("doc-a", result.hits().get(0).doc_id());
    assertEquals(0.75d, result.hits().get(0).score());
    assertEquals(Map.of(), result.hits().get(0).highlights());
    assertEquals("doc-b", result.hits().get(1).doc_id());
    assertEquals(0.25d, result.hits().get(1).score());
    assertNotNull(result.cursor(), "non-blank next_cursor must map to a core Cursor");
    assertEquals("cursor-next", result.cursor().token());
    assertTrue(result.cursor().isLegacy());
    assertEquals(Map.of(), result.facets());
    assertEquals(42L, result.metadata().get("total_hits"));
    assertEquals(7L, result.metadata().get("took_ms"));
  }

  @Test
  @DisplayName("marshals the Query cursor (TEXT pipeline) and maps a blank next_cursor to null")
  void searchRoundTripWithCursor() {
    stub.cannedResponse =
        SearchResponse.newBuilder()
            .addResults(SearchResult.newBuilder().setId("doc-c").setScore(1.5f))
            .setTotalHits(1L)
            .setTookMs(3L)
            .build();

    Result result = client.search(textQuery("paged query", 10, Cursor.legacy("cur-42")));

    SearchRequest sent = stub.lastRequest.get();
    assertNotNull(sent, "stub should have received a SearchRequest");
    assertEquals("paged query", sent.getQuery());
    assertEquals(10, sent.getLimit());
    assertEquals("cur-42", sent.getCursor());
    assertEquals(PipelineConfigs.TEXT, sent.getPipeline(), "a cursor forces the TEXT pipeline");

    assertEquals(1, result.hits().size());
    assertEquals("doc-c", result.hits().get(0).doc_id());
    assertEquals(1.5d, result.hits().get(0).score());
    assertNull(result.cursor(), "blank next_cursor must map to a null core Cursor");
    assertEquals(1L, result.metadata().get("total_hits"));
    assertEquals(3L, result.metadata().get("took_ms"));
  }

  private static Query textQuery(String text, int limit, Cursor cursor) {
    return new Query(
        limit,
        0,
        null,
        null,
        null,
        List.of(new Query.Clause("text", null, text, null)),
        cursor);
  }

  /** Captures the incoming request and answers with a canned response. */
  private static final class StubSearchService extends SearchServiceGrpc.SearchServiceImplBase {
    final AtomicReference<SearchRequest> lastRequest = new AtomicReference<>();
    volatile SearchResponse cannedResponse = SearchResponse.getDefaultInstance();

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
      lastRequest.set(request);
      responseObserver.onNext(cannedResponse);
      responseObserver.onCompleted();
    }
  }
}
