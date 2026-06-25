/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;

public final class TracingServerInterceptor implements ServerInterceptor {
  private static final Context.Key<io.opentelemetry.context.Context> OTEL_CONTEXT_KEY =
      Context.key("js-indexer-otel-context");
  private static final Metadata.Key<String> TRACEPARENT =
      Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRACESTATE =
      Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

  private static final TextMapGetter<Metadata> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Metadata carrier) {
          return java.util.List.of("traceparent", "tracestate");
        }

        @Override
        public String get(Metadata carrier, String key) {
          if ("traceparent".equals(key)) {
            return carrier.get(TRACEPARENT);
          }
          if ("tracestate".equals(key)) {
            return carrier.get(TRACESTATE);
          }
          return null;
        }
      };

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    io.opentelemetry.context.Context extracted =
        W3CTraceContextPropagator.getInstance()
            .extract(io.opentelemetry.context.Context.root(), headers, GETTER);
    Context grpcCtx = Context.current().withValue(OTEL_CONTEXT_KEY, extracted);
    return Contexts.interceptCall(grpcCtx, call, headers, next);
  }

  public static io.opentelemetry.context.Context currentOtelContext() {
    io.opentelemetry.context.Context ctx = OTEL_CONTEXT_KEY.get();
    return ctx == null ? io.opentelemetry.context.Context.current() : ctx;
  }
}
