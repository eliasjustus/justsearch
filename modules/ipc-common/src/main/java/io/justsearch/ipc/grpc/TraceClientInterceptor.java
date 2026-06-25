/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;

public final class TraceClientInterceptor implements ClientInterceptor {
  private static final Metadata.Key<String> TRACEPARENT =
      Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRACESTATE =
      Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

  private static final TextMapSetter<Metadata> SETTER =
      new TextMapSetter<>() {
        @Override
        public void set(Metadata carrier, String key, String value) {
          if (carrier == null || value == null) {
            return;
          }
          if ("traceparent".equals(key)) {
            carrier.put(TRACEPARENT, value);
          } else if ("tracestate".equals(key)) {
            carrier.put(TRACESTATE, value);
          }
        }
      };

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(
        next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        W3CTraceContextPropagator.getInstance().inject(Context.current(), headers, SETTER);
        super.start(responseListener, headers);
      }
    };
  }
}
