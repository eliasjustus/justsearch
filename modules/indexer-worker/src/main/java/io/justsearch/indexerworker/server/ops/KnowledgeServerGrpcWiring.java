/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.grpc.DelegatingHealthService;
import io.justsearch.indexerworker.grpc.DelegatingIngestService;
import io.justsearch.indexerworker.grpc.DelegatingSearchService;
import io.justsearch.indexerworker.server.WorkerAppServices;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

public final class KnowledgeServerGrpcWiring {
  private KnowledgeServerGrpcWiring() {}

  public record GrpcWiringResult(
      Server server,
      DelegatingSearchService searchService,
      DelegatingIngestService ingestService,
      DelegatingHealthService healthService) {}

  public static GrpcWiringResult createGrpcServer(
      WorkerConfig config, List<ServerInterceptor> interceptors, WorkerAppServices appServices)
      throws IOException {
    InetSocketAddress address = new InetSocketAddress(config.host(), 0);
    NettyServerBuilder builder =
        NettyServerBuilder.forAddress(address).maxInboundMessageSize(32 * 1024 * 1024);

    for (ServerInterceptor interceptor : interceptors) {
      builder.intercept(interceptor);
    }

    // Wrap concrete services in delegates and register with gRPC server
    DelegatingHealthService healthWrapper =
        new DelegatingHealthService(appServices.grpcHealthService());
    builder.addService(healthWrapper);

    DelegatingIngestService ingestWrapper =
        new DelegatingIngestService(appServices.grpcIngestService());
    builder.addService(ingestWrapper);

    DelegatingSearchService searchWrapper =
        new DelegatingSearchService(appServices.grpcSearchService());
    builder.addService(searchWrapper);

    return new GrpcWiringResult(builder.build(), searchWrapper, ingestWrapper, healthWrapper);
  }
}
