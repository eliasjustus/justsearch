/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import java.util.List;

/**
 * A composite readiness state derived from multiple readiness components.
 *
 * <p>Stability: stable (API contract)
 */
public record ReadinessCompositeView(String state, List<String> reasonCodes) {

  public ReadinessCompositeView {
    reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
  }
}
