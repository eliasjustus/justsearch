/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.SearchServiceGrpc;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 885 item 3. The interceptor is the only producer of the gauge the indexing duty cycle
 * reads, so the two properties that matter are: every terminal path decrements exactly once, and
 * the method filter counts user-waiting work only.
 */
final class ForegroundLoadInterceptorTest {

  /**
   * Drives one intercepted call. {@code rec.call} captures the call object the <i>handler</i>
   * receives — i.e. the interceptor's own wrapper — because closing the raw call underneath the
   * wrapper would bypass the very decrement this test is about.
   */
  private static <ReqT, RespT> ServerCall.Listener<ReqT> run(
      ForegroundLoadInterceptor interceptor, MethodDescriptor<ReqT, RespT> method, Recorder rec) {
    ServerCallHandler<ReqT, RespT> handler =
        (call, headers) -> {
          rec.call = call;
          return new ServerCall.Listener<>() {};
        };
    return interceptor.interceptCall(new FakeServerCall<>(method), new Metadata(), handler);
  }

  private static final class Recorder {
    private ServerCall<?, ?> call;
  }

  @Test
  @DisplayName("a foreground call increments on start and decrements once on OK close")
  void okCloseBalancesTheGauge() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadInterceptor interceptor = new ForegroundLoadInterceptor(load);
    Recorder rec = new Recorder();

    ServerCall.Listener<?> listener =
        run(interceptor, SearchServiceGrpc.getSearchMethod(), rec);
    assertEquals(1, load.inFlight(), "in flight while the search runs");

    rec.call.close(Status.OK, new Metadata());
    assertEquals(0, load.inFlight());

    // onComplete follows close on the happy path; the gauge must not go negative or double-count.
    listener.onComplete();
    assertEquals(0, load.inFlight());
    assertEquals(1L, load.startedTotal());
  }

  @Test
  @DisplayName("an error close decrements too — a failed search must not pin the gauge")
  void errorCloseBalancesTheGauge() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadInterceptor interceptor = new ForegroundLoadInterceptor(load);
    Recorder rec = new Recorder();

    run(interceptor, SearchServiceGrpc.getRetrieveContextMethod(), rec);
    assertEquals(1, load.inFlight());

    rec.call.close(Status.INTERNAL.withDescription("boom"), new Metadata());
    assertEquals(0, load.inFlight());
  }

  @Test
  @DisplayName("a cancelled call decrements — the throttle must not survive a client that walked away")
  void cancelBalancesTheGauge() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadInterceptor interceptor = new ForegroundLoadInterceptor(load);
    Recorder rec = new Recorder();

    ServerCall.Listener<?> listener =
        run(interceptor, SearchServiceGrpc.getFetchDocumentsMethod(), rec);
    assertEquals(1, load.inFlight());

    listener.onCancel();
    assertEquals(0, load.inFlight());

    // A close racing the cancel must not double-decrement.
    rec.call.close(Status.CANCELLED, new Metadata());
    assertEquals(0, load.inFlight());
  }

  @Test
  @DisplayName("concurrent foreground calls stack and unwind independently")
  void concurrentCallsStack() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadInterceptor interceptor = new ForegroundLoadInterceptor(load);
    Recorder a = new Recorder();
    Recorder b = new Recorder();

    run(interceptor, SearchServiceGrpc.getSearchMethod(), a);
    run(interceptor, SearchServiceGrpc.getSuggestMethod(), b);
    assertEquals(2, load.inFlight());

    a.call.close(Status.OK, new Metadata());
    assertEquals(1, load.inFlight());
    b.call.close(Status.OK, new Metadata());
    assertEquals(0, load.inFlight());
  }

  @Test
  @DisplayName("wrong-gate: an IndexStatus poll is NOT foreground and does not throttle indexing")
  void indexStatusIsNotForeground() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadInterceptor interceptor = new ForegroundLoadInterceptor(load);
    IndexingPacing pacing = new IndexingPacing(load, 20, 500L);
    Recorder rec = new Recorder();

    run(interceptor, IngestServiceGrpc.getIndexStatusMethod(), rec);

    assertEquals(0, load.inFlight(), "the Head's status poll is an observer, not a user");
    assertEquals(0L, load.startedTotal());
    assertFalse(
        pacing.foregroundBusy(),
        "throttling indexing on a status poll is exactly the defect item 3 removes");

    // ...whereas a real search does make the loop pace.
    run(interceptor, SearchServiceGrpc.getSearchMethod(), rec);
    assertTrue(pacing.foregroundBusy());
  }

  @Test
  @DisplayName("ListAllDocumentIds is not foreground — its only caller is the background GPL pager")
  void listAllDocumentIdsIsNotForeground() {
    ForegroundLoad load = new ForegroundLoad();
    ForegroundLoadInterceptor interceptor = new ForegroundLoadInterceptor(load);

    run(interceptor, SearchServiceGrpc.getListAllDocumentIdsMethod(), new Recorder());

    assertEquals(0, load.inFlight());
  }

  @Test
  @DisplayName("the foreground set is exactly SearchService minus ListAllDocumentIds")
  void foregroundSetIsTheSearchServiceMinusThePager() {
    Set<String> searchServiceMethods =
        SearchServiceGrpc.getServiceDescriptor().getMethods().stream()
            .map(m -> m.getFullMethodName())
            .collect(Collectors.toSet());
    Set<String> expected =
        searchServiceMethods.stream()
            .filter(
                name ->
                    !name.equals(SearchServiceGrpc.getListAllDocumentIdsMethod().getFullMethodName()))
            .collect(Collectors.toSet());

    assertEquals(expected, ForegroundLoadInterceptor.foregroundMethods());
    assertEquals(9, ForegroundLoadInterceptor.foregroundMethods().size());
    assertFalse(
        ForegroundLoadInterceptor.isForeground(
            IngestServiceGrpc.getSubmitBatchMethod().getFullMethodName()),
        "no ingest RPC may count as foreground");
  }

  /** Minimal ServerCall that carries a real generated method descriptor. */
  private static final class FakeServerCall<ReqT, RespT> extends ServerCall<ReqT, RespT> {
    private final MethodDescriptor<ReqT, RespT> method;

    FakeServerCall(MethodDescriptor<ReqT, RespT> method) {
      this.method = method;
    }

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(RespT message) {}

    @Override
    public void close(Status status, Metadata trailers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
      return method;
    }
  }
}
