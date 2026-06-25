/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;

/**
 * SPI for declaring operation-scoped leases against the dev-runner ownership model. Tempdoc 542
 * Layer 3.
 *
 * <p>Call sites that initiate long-running operations register a lease before the first
 * irreversible step. The lease is persisted to {@code tmp/dev-runner/op-leases.json} (single
 * writer = Head) and read by the dev-runner's {@code acquireAdmission} gate to decide takeover
 * policy.
 *
 * <p>Outside the shared full-stack dev mode (e.g., production Tauri, isolated backend-only
 * eval), the {@code JUSTSEARCH_DEV_RUNNER_STATE_ROOT} environment variable is unset; in that
 * case the service is a no-op and handles are trivial — call sites do not need to branch on
 * environment.
 *
 * <p>Stability: stable SPI.
 */
public interface OperationLeaseService {

  /**
   * No-op implementation for tests, isolated launches, and callers that don't want lease
   * semantics. {@code register} returns a handle whose methods all do nothing.
   */
  static OperationLeaseService noOp() {
    return NoOpOperationLeaseService.INSTANCE;
  }

  /**
   * Register a new operation lease and return a handle that controls its lifetime.
   *
   * @param opClass stable string identifying the op type (e.g. {@code "indexing.migration"}).
   *                Must be non-null and non-blank.
   * @param criticality admission-policy class; see {@link OpCriticality}.
   * @param expectedDurationSec optimistic upper bound on op duration. Informs expiry safety;
   *                            should not be the worst-case timeout.
   * @param metadata op-class-specific payload (may be null or empty); persisted in the lease file
   *                 verbatim. Useful for audit (e.g. {@code {"sourceGen": "g-...", "targetGen": "g-..."}}).
   * @return handle to {@code renew} / {@code release} the lease; never null.
   */
  OperationLeaseHandle register(
      String opClass,
      OpCriticality criticality,
      long expectedDurationSec,
      Map<String, Object> metadata);
}
