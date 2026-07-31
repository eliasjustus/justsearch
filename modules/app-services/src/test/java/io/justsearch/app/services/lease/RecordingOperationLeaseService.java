package io.justsearch.app.services.lease;

import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLease;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OperationLeaseSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Records op-lease register/release for tests that assert an off-request writer holds an upgrade
 * blocker for the whole lifetime of its background work (tempdoc 617 §10.8).
 *
 * <p>Two things need pinning, and they fail in different ways, so both are captured:
 *
 * <ul>
 *   <li>{@link #registerThread()} — registration must happen on the CALLER's thread. Registering
 *       inside the worker leaves a window where upgrade prepare observes no blocker while the write
 *       is about to begin. Asserted by thread identity rather than by event order, because a worker
 *       that fails fast can finish before the starting call returns, which made an
 *       order-based assertion pass on Windows and fail on Linux.
 *   <li>{@link #releaseThread()} — a release performed on the caller's thread means the lease ended
 *       when the work was handed off, not when it finished.
 * </ul>
 */
public final class RecordingOperationLeaseService implements OperationLeaseService {

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final AtomicReference<String> registerThread = new AtomicReference<>();
  private final AtomicReference<String> releaseThread = new AtomicReference<>();

  /** Ordered {@code register:<opClass>} / {@code release:<outcome>} events. */
  public List<String> events() {
    return List.copyOf(events);
  }

  /** Name of the thread that registered the lease, or null if nothing registered one. */
  public String registerThread() {
    return registerThread.get();
  }

  /** Name of the thread that released the lease, or null if it has not been released. */
  public String releaseThread() {
    return releaseThread.get();
  }

  @Override
  public OperationLeaseHandle register(
      String opClass, OpCriticality criticality, long expectedDurationSec, Map<String, Object> md) {
    events.add("register:" + opClass);
    registerThread.set(Thread.currentThread().getName());
    return new OperationLeaseHandle() {
      private final AtomicBoolean released = new AtomicBoolean(false);

      @Override
      public String opId() {
        return "recording-" + opClass;
      }

      @Override
      public String opClass() {
        return opClass;
      }

      @Override
      public void renew() {}

      @Override
      public void release(OpLeaseOutcome outcome) {
        if (released.compareAndSet(false, true)) {
          events.add("release:" + outcome);
          releaseThread.set(Thread.currentThread().getName());
        }
      }

      @Override
      public void close() {
        release(OpLeaseOutcome.SUCCESS);
      }
    };
  }

  @Override
  public OperationLeaseSnapshot freezeAdmission(String reason) {
    return snapshot();
  }

  @Override
  public OperationLeaseSnapshot snapshot() {
    return new OperationLeaseSnapshot(false, "", "", List.<OperationLease>of());
  }

  @Override
  public void releaseAdmission(String preparationId) {}
}
