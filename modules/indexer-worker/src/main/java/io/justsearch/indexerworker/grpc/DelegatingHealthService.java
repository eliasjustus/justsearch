/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.HealthServiceGrpc;
import java.util.Objects;

/**
 * Delegating wrapper for HealthService that enables runtime service swapping.
 *
 * <p>Registered once with the gRPC server. The single RPC call is forwarded to a {@code volatile}
 * delegate that can be swapped without restarting the gRPC server. The delegate is typed as the
 * generated ImplBase to support cross-classloader hot-reload (Phase 2, tempdoc 305).
 */
public final class DelegatingHealthService extends HealthServiceGrpc.HealthServiceImplBase {

  private volatile HealthServiceGrpc.HealthServiceImplBase delegate;

  public DelegatingHealthService(HealthServiceGrpc.HealthServiceImplBase delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public void setDelegate(HealthServiceGrpc.HealthServiceImplBase delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  @Override
  public void check(HealthCheckRequest req, StreamObserver<HealthCheckResponse> obs) {
    delegate.check(req, obs);
  }
}
