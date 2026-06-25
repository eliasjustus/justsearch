/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.grpc;

import io.grpc.Status;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Lightweight circuit breaker for transient gRPC failures.
 *
 * <p>State transitions:
 * <ul>
 *   <li>CLOSED -> OPEN after threshold consecutive transient failures</li>
 *   <li>OPEN -> HALF_OPEN after cooldown elapses</li>
 *   <li>HALF_OPEN -> CLOSED on success</li>
 *   <li>HALF_OPEN -> OPEN on transient failure</li>
 * </ul>
 */
public final class GrpcCircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  public interface Observer {
    Observer NOOP = new Observer() {};

    default void onStateTransition(State from, State to) {}

    default void onRequestRejected(State state) {}
  }

  public static final int DEFAULT_FAILURE_THRESHOLD = 3;
  public static final long DEFAULT_COOLDOWN_MS = 10_000L;

  private final int failureThreshold;
  private final long cooldownMs;
  private final LongSupplier clock;
  private final Observer observer;
  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger failureCount = new AtomicInteger(0);
  private final AtomicLong openedAtMs = new AtomicLong(0L);
  private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean(false);

  public GrpcCircuitBreaker() {
    this(DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN_MS, System::currentTimeMillis, Observer.NOOP);
  }

  GrpcCircuitBreaker(int failureThreshold, long cooldownMs, LongSupplier clock) {
    this(failureThreshold, cooldownMs, clock, Observer.NOOP);
  }

  public GrpcCircuitBreaker(
      int failureThreshold, long cooldownMs, LongSupplier clock, Observer observer) {
    if (failureThreshold <= 0) {
      throw new IllegalArgumentException("failureThreshold must be positive");
    }
    if (cooldownMs < 0L) {
      throw new IllegalArgumentException("cooldownMs must be >= 0");
    }
    this.failureThreshold = failureThreshold;
    this.cooldownMs = cooldownMs;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.observer = observer == null ? Observer.NOOP : observer;
  }

  public boolean allowRequest() {
    State current = state.get();
    return switch (current) {
      case CLOSED -> true;
      case OPEN -> tryOpenProbeAfterCooldown();
      case HALF_OPEN -> {
        boolean allowed = halfOpenProbeInFlight.compareAndSet(false, true);
        if (!allowed) {
          observer.onRequestRejected(State.HALF_OPEN);
        }
        yield allowed;
      }
    };
  }

  public void recordSuccess() {
    State previous = state.getAndSet(State.CLOSED);
    failureCount.set(0);
    openedAtMs.set(0L);
    halfOpenProbeInFlight.set(false);
    if (previous != State.CLOSED) {
      observer.onStateTransition(previous, State.CLOSED);
    }
  }

  public void recordFailure() {
    int failures = failureCount.incrementAndGet();
    long now = clock.getAsLong();
    while (true) {
      State current = state.get();
      if (current == State.HALF_OPEN) {
        if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
          openedAtMs.set(now);
          halfOpenProbeInFlight.set(false);
          observer.onStateTransition(State.HALF_OPEN, State.OPEN);
        }
        return;
      }
      if (current == State.CLOSED && failures >= failureThreshold) {
        if (state.compareAndSet(State.CLOSED, State.OPEN)) {
          openedAtMs.set(now);
          observer.onStateTransition(State.CLOSED, State.OPEN);
        }
        return;
      }
      return;
    }
  }

  public State state() {
    return state.get();
  }

  public State getState() {
    return state();
  }

  public int failureCount() {
    return failureCount.get();
  }

  public int getFailureCount() {
    return failureCount();
  }

  public boolean isOpen() {
    return state.get() == State.OPEN;
  }

  public void reset() {
    recordSuccess();
  }

  private boolean tryOpenProbeAfterCooldown() {
    long elapsed = clock.getAsLong() - openedAtMs.get();
    if (elapsed <= cooldownMs) {
      observer.onRequestRejected(State.OPEN);
      return false;
    }
    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
      halfOpenProbeInFlight.set(false);
      observer.onStateTransition(State.OPEN, State.HALF_OPEN);
    }
    boolean allowed = state.get() == State.HALF_OPEN && halfOpenProbeInFlight.compareAndSet(false, true);
    if (!allowed) {
      observer.onRequestRejected(state.get());
    }
    return allowed;
  }
}
