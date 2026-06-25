/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Thrown by the dispatcher when the trust lattice computes
 * {@link GateBehavior#DENY} for a dispatch.
 *
 * <p>Today's lattice cell values produce DENY from no (source × risk) pair —
 * the exception class exists for forward-compat with future plugin-emitted
 * UNTRUSTED ops that declare HIGH risk on operations the platform wishes to
 * disallow categorically.
 */
public final class TrustGateDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final OperationRef operationRef;
  private final SourceTier sourceTier;

  public TrustGateDeniedException(OperationRef operationRef, SourceTier sourceTier) {
    super(
        "Trust gate denied operation "
            + Objects.requireNonNull(operationRef, "operationRef").value()
            + " from sourceTier=" + Objects.requireNonNull(sourceTier, "sourceTier"));
    this.operationRef = operationRef;
    this.sourceTier = sourceTier;
  }

  public OperationRef operationRef() {
    return operationRef;
  }

  public SourceTier sourceTier() {
    return sourceTier;
  }
}
