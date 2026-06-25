/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;

public final class RequestMetadataInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> REQUEST_ID_HEADER =
      Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String requestId = headers.get(REQUEST_ID_HEADER);
    if (requestId == null || requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    Context ctx = Context.current().withValue(GrpcContextKeys.REQUEST_ID, requestId);
    return Contexts.interceptCall(ctx, call, headers, next);
  }

  public static String currentRequestId() {
    String id = GrpcContextKeys.REQUEST_ID.get();
    return id == null ? UUID.randomUUID().toString() : id;
  }
}
