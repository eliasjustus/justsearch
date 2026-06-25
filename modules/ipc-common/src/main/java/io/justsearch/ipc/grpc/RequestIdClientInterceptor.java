/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.UUID;
import org.slf4j.MDC;

public final class RequestIdClientInterceptor implements ClientInterceptor {
  private static final Metadata.Key<String> REQUEST_ID_HEADER =
      Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new ForwardingClientCall.SimpleForwardingClientCall<>(
        next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        headers.put(REQUEST_ID_HEADER, resolveRequestId());
        super.start(responseListener, headers);
      }
    };
  }

  private static String resolveRequestId() {
    String fromMdc = MDC.get("request_id");
    if (fromMdc != null && !fromMdc.isBlank()) {
      return fromMdc;
    }
    return UUID.randomUUID().toString();
  }
}
