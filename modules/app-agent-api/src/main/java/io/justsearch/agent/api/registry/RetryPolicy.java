/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;

/**
 * Retry contract for an Operation invocation.
 *
 * <p>Per tempdoc 429 §A.7 RetryRateLimitValidator: {@code allowAutoRetry &&
 * !idempotencyKey.isPresent()} fails the build (auto-retry without idempotency is unsafe).
 *
 * <p>Bit-for-bit-preserves the existing agent loop's retry behavior per §A.2:
 * READ_ONLY (LOW risk) tools auto-retry on transient failures; WRITE/DESTRUCTIVE
 * (MEDIUM/HIGH) do not.
 */
public record RetryPolicy(
    boolean allowAutoRetry,
    int maxRetries,
    Optional<String> idempotencyKey) {

  public RetryPolicy {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must be non-negative: " + maxRetries);
    }
    if (allowAutoRetry && idempotencyKey.isEmpty()) {
      throw new IllegalArgumentException(
          "allowAutoRetry requires an idempotencyKey (auto-retry without idempotency is unsafe)");
    }
  }

  /** Default policy: no auto-retry. Use for write/destructive operations. */
  public static RetryPolicy noRetry() {
    return new RetryPolicy(false, 0, Optional.empty());
  }

  /** Auto-retry policy with idempotency. Use for read-only operations bound to idempotent endpoints. */
  public static RetryPolicy autoRetry(int maxRetries, String idempotencyKey) {
    return new RetryPolicy(true, maxRetries, Optional.of(idempotencyKey));
  }
}
