/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;

/** Point-in-time process-local operation admission state. */
public record OperationLeaseSnapshot(
    boolean admissionFrozen,
    String preparationId,
    String reason,
    List<OperationLease> activeLeases,
    List<String> cancellationRequestedOpIds) {

  public OperationLeaseSnapshot {
    activeLeases = activeLeases == null ? List.of() : List.copyOf(activeLeases);
    cancellationRequestedOpIds =
        cancellationRequestedOpIds == null ? List.of() : List.copyOf(cancellationRequestedOpIds);
  }

  /** Source-compatible constructor for callers that do not consume cancellation audit state. */
  public OperationLeaseSnapshot(
      boolean admissionFrozen,
      String preparationId,
      String reason,
      List<OperationLease> activeLeases) {
    this(admissionFrozen, preparationId, reason, activeLeases, List.of());
  }
}
