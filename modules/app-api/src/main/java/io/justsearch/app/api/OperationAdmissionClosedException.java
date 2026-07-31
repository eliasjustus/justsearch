/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/** Typed registration failure while an upgrade preparation owns the admission barrier. */
public final class OperationAdmissionClosedException extends IllegalStateException {
  private final String preparationId;
  private final String reason;

  public OperationAdmissionClosedException(String preparationId, String reason) {
    super("Operation admission is frozen for preparation " + preparationId + ": " + reason);
    this.preparationId = preparationId;
    this.reason = reason;
  }

  public String preparationId() {
    return preparationId;
  }

  public String reason() {
    return reason;
  }
}
