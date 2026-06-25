package io.justsearch.telemetry.grpc;

import io.grpc.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;

public class TraceServerInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> TRACEPARENT = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRACESTATE = Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

  public static volatile String lastObservedTraceId;

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    TextMapGetter<Metadata> getter = new TextMapGetter<>() {
      @Override public String get(Metadata carrier, String key) {
        if ("traceparent".equals(key)) return carrier.get(TRACEPARENT);
        if ("tracestate".equals(key)) return carrier.get(TRACESTATE);
        return null;
      }
      @Override public Iterable<String> keys(Metadata carrier) { return java.util.List.of("traceparent","tracestate"); }
    };
    Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.current(), headers, getter);
    try (var scope = extracted.makeCurrent()) {
      lastObservedTraceId = Span.fromContext(extracted).getSpanContext().getTraceId();
      return next.startCall(call, headers);
    }
  }
}
