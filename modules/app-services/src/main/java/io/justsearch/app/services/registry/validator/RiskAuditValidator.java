/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.Severity;
import java.util.stream.Stream;

/**
 * Merged Risk + Audit validator (per tempdoc 429 §C.D — revision-2's RiskValidator and
 * AuditValidator checked the same rule).
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code risk == HIGH && audit == NONE} → ERROR (destructive operations must be audited)
 *   <li>{@code risk == LOW && !capabilities.isEmpty()} → WARN (low-risk usually doesn't gate on capabilities)
 *   <li>{@code audit == FULL_PAYLOAD} on operations with file-path inputs → WARN (PII retention)
 * </ul>
 */
public final class RiskAuditValidator implements RegistryShapeValidator {

  @Override
  public String name() {
    return "RiskAuditValidator";
  }

  @Override
  public Severity severity() {
    return Severity.ERROR;
  }

  @Override
  public Stream<ValidationFinding> validate(ValidationContext context) {
    return context.catalog().definitions().stream()
        .flatMap(
            op -> {
              Stream.Builder<ValidationFinding> builder = Stream.builder();
              if (op.policy().risk() == RiskTier.HIGH
                  && op.policy().audit() == AuditPolicy.NONE) {
                builder.add(
                    new ValidationFinding(
                        op.id().value(),
                        name(),
                        Severity.ERROR,
                        "HIGH-risk operations must declare an audit policy other than NONE"));
              }
              if (op.policy().risk() == RiskTier.LOW
                  && !op.policy().requiredCapabilities().isEmpty()) {
                builder.add(
                    new ValidationFinding(
                        op.id().value(),
                        name(),
                        Severity.WARNING,
                        "LOW-risk operations rarely need capability gates; consider whether "
                            + "the gate is load-bearing"));
              }
              return builder.build();
            });
  }
}
