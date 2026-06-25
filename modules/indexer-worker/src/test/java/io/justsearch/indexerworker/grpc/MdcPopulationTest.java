package io.justsearch.indexerworker.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.justsearch.ipc.logging.MdcContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Verifies that the interceptor → MdcContext chain correctly populates MDC keys
 * for structured log correlation across the Head → Worker gRPC boundary.
 */
final class MdcPopulationTest {

  private static final String TRACEPARENT =
      "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01";
  private static final String EXPECTED_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
  private static final String EXPECTED_REQUEST_ID = "req-abc-123";

  @Test
  void interceptorsPopulateMdcKeys() {
    TracingServerInterceptor tracingInterceptor = new TracingServerInterceptor();
    RequestMetadataInterceptor requestInterceptor = new RequestMetadataInterceptor();

    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER), TRACEPARENT);
    headers.put(
        Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER),
        EXPECTED_REQUEST_ID);

    AtomicReference<String> observedTraceId = new AtomicReference<>();
    AtomicReference<String> observedRequestId = new AtomicReference<>();

    // Innermost handler: read MDC after MdcContext is opened
    ServerCallHandler<Object, Object> innerHandler =
        (call, metadata) -> {
          SpanContext spanCtx =
              Span.fromContext(TracingServerInterceptor.currentOtelContext()).getSpanContext();
          String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;
          try (MdcContext mdc =
              MdcContext.request(traceId, RequestMetadataInterceptor.currentRequestId())) {
            observedTraceId.set(MDC.get("trace_id"));
            observedRequestId.set(MDC.get("request_id"));
          }
          return new ServerCall.Listener<>() {};
        };

    // Chain: tracing → request-metadata → inner handler (same order as KnowledgeServer)
    ServerCallHandler<Object, Object> withRequestId =
        (call, metadata) ->
            requestInterceptor.interceptCall(call, metadata, innerHandler);

    tracingInterceptor.interceptCall(new NoopServerCall<>(), headers, withRequestId);

    assertEquals(EXPECTED_TRACE_ID, observedTraceId.get());
    assertEquals(EXPECTED_REQUEST_ID, observedRequestId.get());
  }

  @Test
  void mdcClearedAfterScopeCloses() {
    MDC.clear();
    try (MdcContext mdc = MdcContext.request("some-trace", "some-request")) {
      assertEquals("some-trace", MDC.get("trace_id"));
    }
    assertNull(MDC.get("trace_id"));
    assertNull(MDC.get("request_id"));
  }

  @Test
  void invalidSpanContextProducesNullTraceId() {
    // No interceptor context → Span.current() has invalid SpanContext
    SpanContext spanCtx =
        Span.fromContext(TracingServerInterceptor.currentOtelContext()).getSpanContext();
    String traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;

    assertNull(traceId);
  }

  private static final class NoopServerCall<ReqT, RespT> extends ServerCall<ReqT, RespT> {
    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(RespT message) {}

    @Override
    public void close(io.grpc.Status status, Metadata trailers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
      return null;
    }
  }
}
