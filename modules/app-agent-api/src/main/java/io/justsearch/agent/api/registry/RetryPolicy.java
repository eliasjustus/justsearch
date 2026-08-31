/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Optional;

/**
 * Retry contract for an Operation invocation.
 *
 * <p>{@code allowAutoRetry && !idempotencyKey.isPresent()} is rejected by the compact
 * constructor: auto-retry without idempotency is unsafe, so the axis is unrepresentable in that
 * shape rather than merely linted against.
 *
 * <p>The axis declares PERMISSION to replay, not a schedule. Tempdoc 879 wired
 * {@code AgentToolDispatcher} to it: the tool-retry loop replays only when the operation declares
 * {@code allowAutoRetry}, up to {@code maxRetries}, while the back-off delays stay the caller's
 * timing policy ({@code AgentRetryPolicy}). Before that the dispatcher hard-coded
 * {@code risk == LOW}, so this record was declarative only and the two could disagree.
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
