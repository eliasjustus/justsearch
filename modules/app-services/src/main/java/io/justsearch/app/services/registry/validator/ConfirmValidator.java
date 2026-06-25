/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.Severity;
import java.util.stream.Stream;

/**
 * Validates {@link ConfirmStrategy} declarations.
 *
 * <p>Most confirm-related invariants are enforced at the type level (e.g., TYPED
 * carries a non-null I18nKey by construction per §"Type-system invariants"); this
 * validator covers the residual structural checks.
 *
 * <p>Rules:
 * <ul>
 *   <li>HIGH-risk operations should not use {@code ConfirmStrategy.None} (WARN; rare exception)
 *   <li>LOW-risk operations should not use {@code ConfirmStrategy.Typed} (WARN; over-gating)
 * </ul>
 */
public final class ConfirmValidator implements RegistryShapeValidator {

  @Override
  public String name() {
    return "ConfirmValidator";
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
              switch (op.policy().risk()) {
                case HIGH -> {
                  if (op.policy().confirm() instanceof ConfirmStrategy.None) {
                    builder.add(
                        new ValidationFinding(
                            op.id().value(),
                            name(),
                            Severity.WARNING,
                            "HIGH-risk operation has ConfirmStrategy.None; verify this is "
                                + "intentional (e.g., system-internal call with another gate)"));
                  }
                }
                case LOW -> {
                  if (op.policy().confirm() instanceof ConfirmStrategy.Typed) {
                    builder.add(
                        new ValidationFinding(
                            op.id().value(),
                            name(),
                            Severity.WARNING,
                            "LOW-risk operation has ConfirmStrategy.Typed; users typically "
                                + "don't expect typed confirmation for low-risk actions"));
                  }
                }
                default -> {
                  // MEDIUM: any confirm strategy is reasonable
                }
              }
              return builder.build();
            });
  }
}
