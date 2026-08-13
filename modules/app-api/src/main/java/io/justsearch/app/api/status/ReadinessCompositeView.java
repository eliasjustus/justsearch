/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.List;

/**
 * A composite readiness state derived from multiple readiness components.
 *
 * <p>{@code stale} / {@code maxStalenessMs} aggregate the member components' freshness facts
 * (tempdoc 821 §P P1): {@code stale} is true when ANY member was derived without a fresh
 * observation of its source, and {@code maxStalenessMs} is the oldest member's age. They are a
 * projection of the member {@link ReadinessComponentView}s, never a second observation — a
 * composite whose members are all head-local never goes stale.
 *
 * <p>Stability: stable (API contract)
 */
public record ReadinessCompositeView(
    String state, List<String> reasonCodes, boolean stale, long maxStalenessMs) {

  public ReadinessCompositeView {
    reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
  }
}
