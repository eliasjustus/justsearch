/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compatibility adapter over ipc-common's canonical gRPC circuit breaker.
 *
 * <p>App-services keeps this wrapper to preserve existing public API and telemetry/log semantics
 * while delegating state transitions and probe/rejection behavior to the shared primitive.
 */
public final class GrpcCircuitBreaker {
  private static final Logger log = LoggerFactory.getLogger(GrpcCircuitBreaker.class);

  static final int FAILURE_THRESHOLD = io.justsearch.ipc.grpc.GrpcCircuitBreaker.DEFAULT_FAILURE_THRESHOLD;
  static final long COOLDOWN_MS = io.justsearch.ipc.grpc.GrpcCircuitBreaker.DEFAULT_COOLDOWN_MS;

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN;

    static State fromCore(io.justsearch.ipc.grpc.GrpcCircuitBreaker.State coreState) {
      return switch (coreState) {
        case CLOSED -> CLOSED;
        case OPEN -> OPEN;
        case HALF_OPEN -> HALF_OPEN;
      };
    }
  }

  private final IpcTelemetry telemetry;
  private final io.justsearch.ipc.grpc.GrpcCircuitBreaker delegate;

  public GrpcCircuitBreaker() {
    this(IpcTelemetry.noop());
  }

  public GrpcCircuitBreaker(IpcTelemetry telemetry) {
    this(telemetry, System::currentTimeMillis);
  }

  GrpcCircuitBreaker(IpcTelemetry telemetry, LongSupplier clock) {
    this.telemetry = telemetry != null ? telemetry : IpcTelemetry.noop();
    this.delegate =
        new io.justsearch.ipc.grpc.GrpcCircuitBreaker(
            FAILURE_THRESHOLD,
            COOLDOWN_MS,
            clock,
            new io.justsearch.ipc.grpc.GrpcCircuitBreaker.Observer() {
              @Override
              public void onStateTransition(
                  io.justsearch.ipc.grpc.GrpcCircuitBreaker.State from,
                  io.justsearch.ipc.grpc.GrpcCircuitBreaker.State to) {
                State mappedFrom = State.fromCore(from);
                State mappedTo = State.fromCore(to);
                if (mappedFrom == State.OPEN && mappedTo == State.HALF_OPEN) {
                  log.info(
                      "Circuit breaker transitioning OPEN -> HALF_OPEN after {}ms cooldown",
                      COOLDOWN_MS);
                } else if (mappedTo == State.OPEN) {
                  if (mappedFrom == State.CLOSED) {
                    log.warn(
                        "Circuit breaker transitioning CLOSED -> OPEN after {} consecutive failures",
                        delegate.getFailureCount());
                  } else {
                    log.warn(
                        "Circuit breaker transitioning {} -> OPEN after probe request failed",
                        mappedFrom);
                  }
                } else if (mappedTo == State.CLOSED && mappedFrom != State.CLOSED) {
                  log.info(
                      "Circuit breaker transitioning {} -> CLOSED after successful request",
                      mappedFrom);
                }
                GrpcCircuitBreaker.this.telemetry.recordCircuitBreakerStateChange(
                    CircuitBreakerState.fromWire(mappedFrom.name()),
                    CircuitBreakerState.fromWire(mappedTo.name()));
              }

              @Override
              public void onRequestRejected(io.justsearch.ipc.grpc.GrpcCircuitBreaker.State state) {
                GrpcCircuitBreaker.this.telemetry.recordCircuitBreakerRejection();
              }
            });
  }

  public boolean allowRequest() {
    return delegate.allowRequest();
  }

  public void recordSuccess() {
    delegate.recordSuccess();
  }

  public void recordFailure() {
    delegate.recordFailure();
  }

  public State getState() {
    return State.fromCore(delegate.getState());
  }

  public boolean isOpen() {
    return delegate.isOpen();
  }

  public int getFailureCount() {
    return delegate.getFailureCount();
  }

  public void reset() {
    delegate.reset();
    log.info("Circuit breaker manually reset to CLOSED");
  }
}
