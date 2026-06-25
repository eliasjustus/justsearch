package io.justsearch.telemetry.grpc;

import io.grpc.*;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;

public class TraceClientInterceptor implements ClientInterceptor {
  private static final Metadata.Key<String> TRACEPARENT = Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRACESTATE = Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, (carrier, key, value) -> {
          if ("traceparent".equals(key)) carrier.put(TRACEPARENT, value);
          else if ("tracestate".equals(key)) carrier.put(TRACESTATE, value);
        });
        super.start(responseListener, headers);
      }
    };
  }
}
