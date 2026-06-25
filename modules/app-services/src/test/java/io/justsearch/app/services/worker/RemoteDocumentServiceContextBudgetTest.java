package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.mmf.MmfWorkerSignalLayoutV1;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RemoteDocumentService fallback context budgeting")
final class RemoteDocumentServiceContextBudgetTest {

  private static final int MAX_CONTEXT_CHARS = 200_000;

  private Server server;
  private MainSignalBus signalBus;
  private RemoteKnowledgeClient client;
  private String prevDataDir;
  private Path tempDataDir;

  @BeforeEach
  void setUp() throws Exception {
    prevDataDir = System.getProperty("justsearch.data.dir");
    tempDataDir = Files.createTempDirectory("justsearch-scale002-test-");
    System.setProperty("justsearch.data.dir", tempDataDir.toString());

    // Start a minimal SearchService over a real loopback port so RemoteKnowledgeClient can connect.
    FailingRetrieveContextService service =
        new FailingRetrieveContextService(
            Map.of(
                "doc-1", "A".repeat(100_000),
                "doc-2", "B".repeat(150_000)));
    server = NettyServerBuilder.forPort(0).addService(service).build().start();

    // Create and open the signal bus MMF, then write the chosen gRPC port so reconnect() is stable.
    Path signalPath = tempDataDir.resolve("signals").resolve("worker-signal.mmf");
    signalBus = new MainSignalBus(signalPath);
    signalBus.open();
    writePortForTests(signalBus, server.getPort());

    // Generous deadline to avoid flaky failures on contended CI runners.
    // The test flow is: retrieveContext (UNAVAILABLE → retry → fail) → fallback → fetchDocuments.
    // Both RPCs use CONTENT_FETCH (2x multiplier), so effective deadline = 10s per RPC.
    // At deadlineMs=500 (1s effective), CI runners under load hit DEADLINE_EXCEEDED on
    // fetchDocuments, causing the fallback's inner catch to return docsUsed=0.
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
  }

  @Test
  @DisplayName("caps fallback context to 200k chars including header+separator overhead")
  void capsFallbackContextIncludingOverhead() throws Exception {
    RemoteDocumentService service = new RemoteDocumentService(() -> client);

    Set<String> docIds = new LinkedHashSet<>();
    docIds.add("doc-1");
    docIds.add("doc-2");

    var result = service
        .retrieveContextWithMeta("what is this?", docIds, 5)
        .toCompletableFuture()
        // Generous timeout; test validates correctness (200K cap), not latency.
        .get(6, TimeUnit.SECONDS);

    assertFalse(result.usedChunks(), "Should indicate fallback to full docs when retrieveContext RPC fails");
    assertEquals(0, result.chunksUsed(), "Fallback should report chunksUsed=0");
    assertEquals(2, result.docsUsed(), "Should include both docs (second truncated to fit cap)");

    String context = result.context();
    assertEquals(MAX_CONTEXT_CHARS, context.length(), "Context must be strictly capped to maxChars");

    assertTrue(context.startsWith("[From: doc-1]\n"), "Should include header for doc-1");
    assertTrue(context.contains(ContextBudgeter.SECTION_SEPARATOR), "Should include section separator");
    assertTrue(context.contains("[From: doc-2]\n"), "Should include header for doc-2");
    assertTrue(context.endsWith("B"), "Final truncated content should end with doc-2 content");

    // Verify the truncation math is strict and counts overhead.
    String header1 = "[From: doc-1]\n";
    String header2 = "[From: doc-2]\n";
    String sep = ContextBudgeter.SECTION_SEPARATOR;
    String a = "A".repeat(100_000);
    String b = "B".repeat(150_000);
    int remainingForB = MAX_CONTEXT_CHARS - (header1.length() + a.length() + sep.length() + header2.length());
    String expected = header1 + a + sep + header2 + b.substring(0, remainingForB);
    assertEquals(expected, context, "Context should be truncated exactly to budget including overhead");
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

    // Sanity: ensure the normal public read path sees it (protects against endian mistakes).
    assertEquals(port, bus.readPort());
  }

  private static final class FailingRetrieveContextService extends SearchServiceGrpc.SearchServiceImplBase {
    private final Map<String, String> docs;

    private FailingRetrieveContextService(Map<String, String> docs) {
      this.docs = docs;
    }

    @Override
    public void retrieveContext(RetrieveContextRequest request,
                                StreamObserver<RetrieveContextResponse> responseObserver) {
      responseObserver.onError(
          Status.UNAVAILABLE.withDescription("forced failure for fallback test").asRuntimeException());
    }

    @Override
    public void fetchDocuments(FetchDocumentsRequest request,
                               StreamObserver<FetchDocumentsResponse> responseObserver) {
      FetchDocumentsResponse.Builder out = FetchDocumentsResponse.newBuilder();
      for (String docId : request.getDocIdsList()) {
        String content = docs.getOrDefault(docId, "");
        out.addDocuments(
            DocumentContent.newBuilder()
                .setDocId(docId)
                .setContent(content)
                .setFound(true)
                .build());
      }
      responseObserver.onNext(out.build());
      responseObserver.onCompleted();
    }
  }
}
