/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.util.function.Supplier;

/**
 * A run with control points — agent / workflow (tempdoc 834 §0, column one). It has approval,
 * budget and context gates, it can be steered, and with no observer it MAY park.
 *
 * <p>This is the only subtype with {@code setPark}. See {@link OneShotRunChannel} for the other
 * half of the guard.
 */
public non-sealed interface SteppedRunChannel extends RunChannel {

  /** Records why the run is stopped; {@code null} clears the park. */
  void setPark(ParkState park);

  /**
   * Supplies the act-on-the-run primer at every subscribe. A SUPPLIER rather than a stored value
   * because the snapshot must be current AT ATTACH TIME — a value stamped when the run started
   * would prime a reattacher with a state the run left thousands of frames ago.
   */
  void setSnapshotSupplier(Supplier<RunStateSnapshot> supplier);
}
