/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.mmf.MmfWorkerSignalLayoutV1;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §3-C2 — the Head-side half of RAG collection scoping, over a real gRPC wire (harness
 * mirrors {@link RemoteDocumentServicePreSearchPipelineTest}). Two hops are pinned, because both
 * dropped the scope before this change: {@code SearchRpcOps#retrieveContext}'s param→proto mapping,
 * and {@code RemoteDocumentService}'s open-retrieval pre-search, whose discovered doc universe the
 * downstream {@code RetrieveContextRequest} is scoped to — an unscoped pre-search would resolve an
 * agent-history ASK to zero parents, since the DEFAULT scope excludes exactly that collection.
 */
@DisplayName("RemoteDocumentService — collection scope reaches the wire (821 §3-C2)")
final class RemoteDocumentServiceCollectionScopeTest {

  private Server server;
  private MainSignalBus signalBus;
  private RemoteKnowledgeClient client;
  private String prevDataDir;
  private Path tempDataDir;
  private ConfigStore prevConfigStore;
  private final AtomicReference<SearchRequest> capturedSearchRequest = new AtomicReference<>();
  private final AtomicReference<RetrieveContextRequest> capturedRetrieveContextRequest =
      new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    prevConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();

    prevDataDir = System.getProperty("justsearch.data.dir");
    tempDataDir = Files.createTempDirectory("justsearch-821-collection-scope-test-");
    System.setProperty("justsearch.data.dir", tempDataDir.toString());

    CapturingSearchService service =
        new CapturingSearchService(capturedSearchRequest, capturedRetrieveContextRequest);
    server = NettyServerBuilder.forPort(0).addService(service).build().start();

    Path signalPath = tempDataDir.resolve("signals").resolve("worker-signal.mmf");
    signalBus = new MainSignalBus(signalPath);
    signalBus.open();
    writePortForTests(signalBus, server.getPort());

    client = new RemoteKnowledgeClient(signalBus, /*deadlineMs=*/ 5000, /*maxRetries=*/ 1);
    client.connect(server.getPort());
  }

  @AfterEach
  void tearDown() throws Exception {
    if (client != null) {
      client.close();
      client = null;
    }
    if (signalBus != null) {
      signalBus.close();
      signalBus = null;
    }
    if (server != null) {
      server.shutdownNow().awaitTermination();
      server = null;
    }
    if (prevDataDir == null) {
      System.clearProperty("justsearch.data.dir");
    } else {
      System.setProperty("justsearch.data.dir", prevDataDir);
    }
    TestResolvedConfigHelper.restoreGlobal(prevConfigStore);
  }

  private void retrieve(List<String> collection, Set<String> docIds) throws Exception {
    RemoteDocumentService service = new RemoteDocumentService(() -> client);
    RetrieveContextParams params =
        RetrieveContextParams.of("what did the agent do?", 5, 4096, docIds, List.of(), collection);
    service.retrieveContext(params).toCompletableFuture().get(6, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName("an explicit scope is mapped onto RetrieveContextRequest.collection")
  void explicitScopeReachesTheRetrieveContextRequest() throws Exception {
    retrieve(List.of("agent-history"), Set.of("d:/agent/session-1.md"));

    RetrieveContextRequest sent = capturedRetrieveContextRequest.get();
    assertTrue(sent != null, "retrieveContext() must have been called");
    assertEquals(
        List.of("agent-history"),
        sent.getCollectionList(),
        "the collection scope must reach the Worker, not be dropped in the param->proto mapping");
  }

  @Test
  @DisplayName("the open-retrieval pre-search carries the same scope")
  void preSearchCarriesTheSameScope() throws Exception {
    // Empty docIds is the open-retrieval path: RemoteDocumentService pre-searches for the doc
    // universe first, so the pre-search must be scoped the same way the retrieval is.
    retrieve(List.of("agent-history"), Set.of());

    SearchRequest preSearch = capturedSearchRequest.get();
    assertTrue(preSearch != null, "open retrieval must have issued a pre-search");
    assertEquals(
        List.of("agent-history"),
        preSearch.getFilters().getCollectionList(),
        "an unscoped pre-search would find zero agent-history parents (the default scope excludes"
            + " that collection) and silently starve the retrieval");

    // The rebuilt params carrying the discovered doc ids must not drop the scope either — that is
    // the hand-rolled record copy that the 811 D-2 class of bug lives in.
    assertEquals(
        List.of("agent-history"),
        capturedRetrieveContextRequest.get().getCollectionList(),
        "the scope must survive the discovered-docIds rebuild");
  }

  @Test
  @DisplayName("no scope leaves the wire fields empty (pre-821 behavior, byte-identical)")
  void absentScopeLeavesTheWireUntouched() throws Exception {
    retrieve(List.of(), Set.of());

    assertEquals(
        List.of(),
        capturedRetrieveContextRequest.get().getCollectionList(),
        "an absent scope must send an empty repeated field, which the Worker reads as the DEFAULT"
            + " scope");
    assertTrue(
        capturedSearchRequest.get().getFilters().getCollectionList().isEmpty(),
        "and the pre-search must be unscoped exactly as before");
  }

  private static void writePortForTests(MainSignalBus bus, int port) throws Exception {
    Field f = MainSignalBus.class.getDeclaredField("segment");
    f.setAccessible(true);
    MemorySegment segment = (MemorySegment) f.get(bus);
    segment.set(
        ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
        MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT,
        port);
    segment.force();
    assertEquals(port, bus.readPort());
  }

  /** Fake Worker {@code SearchService}: captures both request shapes the Head sends. */
  private static final class CapturingSearchService
      extends SearchServiceGrpc.SearchServiceImplBase {
    private final AtomicReference<SearchRequest> searchCapture;
    private final AtomicReference<RetrieveContextRequest> retrieveContextCapture;

    private CapturingSearchService(
        AtomicReference<SearchRequest> searchCapture,
        AtomicReference<RetrieveContextRequest> retrieveContextCapture) {
      this.searchCapture = searchCapture;
      this.retrieveContextCapture = retrieveContextCapture;
    }

    @Override
    public void search(SearchRequest request, StreamObserver<SearchResponse> responseObserver) {
      searchCapture.set(request);
      SearchResponse.Builder resp = SearchResponse.newBuilder();
      resp.addResults(
          SearchResult.newBuilder()
              .setId("d:/agent/session-1.md")
              .putFields("path", "d:/agent/session-1.md")
              .build());
      responseObserver.onNext(resp.build());
      responseObserver.onCompleted();
    }

    @Override
    public void retrieveContext(
        RetrieveContextRequest request,
        StreamObserver<RetrieveContextResponse> responseObserver) {
      retrieveContextCapture.set(request);
      responseObserver.onNext(RetrieveContextResponse.newBuilder().build());
      responseObserver.onCompleted();
    }
  }
}
