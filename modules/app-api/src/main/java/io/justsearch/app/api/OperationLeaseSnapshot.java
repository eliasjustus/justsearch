/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;

/** Point-in-time process-local operation admission state. */
public record OperationLeaseSnapshot(
    boolean admissionFrozen,
    String preparationId,
    String reason,
    List<OperationLease> activeLeases) {

  public OperationLeaseSnapshot {
    activeLeases = activeLeases == null ? List.of() : List.copyOf(activeLeases);
  }
}
