/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * Package-private no-op singleton returned by {@link OperationLeaseService#noOp()}.
 * Tempdoc 542 §B Layer 3.
 */
enum NoOpOperationLeaseService implements OperationLeaseService {
  INSTANCE;

  @Override
  public OperationLeaseHandle register(
      String opClass,
      OpCriticality criticality,
      long expectedDurationSec,
      Map<String, Object> metadata) {
    return NoOpHandle.INSTANCE;
  }

  private enum NoOpHandle implements OperationLeaseHandle {
    INSTANCE;

    @Override
    public String opId() {
      return "noop";
    }

    @Override
    public String opClass() {
      return "noop";
    }

    @Override
    public void renew() {}

    @Override
    public void release(OpLeaseOutcome outcome) {}

    @Override
    public void close() {}
  }
}
