/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Severity;
import java.util.stream.Stream;

/**
 * Merged Retry + RateLimit validator (per tempdoc 429 §C.D).
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code retry.allowAutoRetry && !retry.idempotencyKey.isPresent()} → handled
 *       at type-construction time by {@code RetryPolicy} compact constructor;
 *       this validator only verifies the runtime entries are well-formed.
 *   <li>{@code rateLimit < 1 second} → WARN (sub-second rate limits rarely meaningful
 *       for user-driven operations).
 * </ul>
 */
public final class RetryRateLimitValidator implements RegistryShapeValidator {

  private static final long MIN_RATE_LIMIT_MS = 1000L;

  @Override
  public String name() {
    return "RetryRateLimitValidator";
  }

  @Override
  public Severity severity() {
    return Severity.WARNING;
  }

  @Override
  public Stream<ValidationFinding> validate(ValidationContext context) {
    return context.catalog().definitions().stream()
        .flatMap(
            op -> {
              Stream.Builder<ValidationFinding> builder = Stream.builder();
              op.policy()
                  .rateLimit()
                  .ifPresent(
                      r -> {
                        if (r.toMillis() < MIN_RATE_LIMIT_MS) {
                          builder.add(
                              new ValidationFinding(
                                  op.id().value(),
                                  name(),
                                  Severity.WARNING,
                                  "Sub-second rate limit (" + r.toMillis() + "ms) is rarely "
                                      + "meaningful for user-driven operations; verify intent"));
                        }
                      });
              return builder.build();
            });
  }
}
