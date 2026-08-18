/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.util.Optional;
import java.util.function.Supplier;

/** The stepped-run implementation: park + primer on top of the shared observation substrate. */
final class SteppedRunChannelImpl extends AbstractRunChannel implements SteppedRunChannel {

  private volatile ParkState park;
  private volatile Supplier<RunStateSnapshot> snapshotSupplier;

  SteppedRunChannelImpl(
      RunId id, RunDescriptor descriptor, RunChannelPolicy policy, java.time.Clock clock) {
    super(id, descriptor, policy, clock);
  }

  @Override
  public void setPark(ParkState park) {
    this.park = park;
  }

  @Override
  public void setSnapshotSupplier(Supplier<RunStateSnapshot> supplier) {
    this.snapshotSupplier = supplier;
  }

  @Override
  public Optional<ParkState> park() {
    return Optional.ofNullable(park);
  }

  @Override
  public Optional<RunStateSnapshot> snapshot() {
    Supplier<RunStateSnapshot> supplier = snapshotSupplier;
    if (supplier == null) {
      return Optional.empty();
    }
    // The supplier reaches into a live session on another thread. A primer that THREW here would
    // take down the attach — and the attach is precisely the path a user takes to recover a run
    // they can no longer see. Degrade to "no primer" instead.
    try {
      return Optional.ofNullable(supplier.get());
    } catch (RuntimeException unavailable) {
      return Optional.empty();
    }
  }
}
