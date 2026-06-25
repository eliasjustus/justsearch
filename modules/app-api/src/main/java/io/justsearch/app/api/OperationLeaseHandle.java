/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Handle returned by {@link OperationLeaseService#register}. Tempdoc 542 Layer 3.
 *
 * <p>Implements {@link AutoCloseable} so call sites can write try-with-resources:
 *
 * <pre>{@code
 * try (var handle = operationLeaseService.register(
 *         "indexing.migration", OpCriticality.MUST_COMPLETE, 1800, metadata)) {
 *   // ... irreversible work; lease is released on exit, success or exception
 * }
 * }</pre>
 *
 * <p>{@link #close()} releases with {@link OpLeaseOutcome#SUCCESS} unless {@link
 * #release(OpLeaseOutcome)} has already been called explicitly.
 */
public interface OperationLeaseHandle extends AutoCloseable {

  /** Opaque opId of the registered lease. */
  String opId();

  /** Stable string identifying the op type (mirrors the original {@code opClass}). */
  String opClass();

  /**
   * Refresh the lease's {@code heartbeatAt} and ensure {@code expiresAt} is at least the renewal
   * window from now. Never shortens an existing further-out expiry. No-op if already released.
   */
  void renew();

  /**
   * Release the lease with an explicit outcome. Idempotent — subsequent calls are no-ops. After
   * release, {@link #close()} does nothing.
   */
  void release(OpLeaseOutcome outcome);

  /** Releases with {@link OpLeaseOutcome#SUCCESS} if {@link #release} was not called. */
  @Override
  void close();
}
