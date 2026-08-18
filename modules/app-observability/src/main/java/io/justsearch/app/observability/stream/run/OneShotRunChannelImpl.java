/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.util.Optional;

/**
 * The one-shot implementation. Both {@link #park()} and {@link #snapshot()} are empty BY
 * CONSTRUCTION, not by policy: there is no field either could read from, because the type exposes
 * no way to set one (§3.4), and a one-shot pipeline has no fact a user can act on (§6.4) — a phase
 * label would be presentation, needing engine hooks, and would be substrate without a consumer.
 */
final class OneShotRunChannelImpl extends AbstractRunChannel implements OneShotRunChannel {

  OneShotRunChannelImpl(
      RunId id, RunDescriptor descriptor, RunChannelPolicy policy, java.time.Clock clock) {
    super(id, descriptor, policy, clock);
  }

  @Override
  public Optional<ParkState> park() {
    return Optional.empty();
  }

  @Override
  public Optional<RunStateSnapshot> snapshot() {
    return Optional.empty();
  }
}
