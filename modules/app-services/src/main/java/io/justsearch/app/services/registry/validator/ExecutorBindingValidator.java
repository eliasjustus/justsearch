/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Severity;
import java.util.stream.Stream;

/**
 * Merged ExecutorCoverage + Binding validator (per tempdoc 429 §C.D).
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code executors.isEmpty()} → ERROR (operation must declare at least one surface)
 *   <li>{@code binding.handlerId} resolves via {@link io.justsearch.agent.api.registry.HandlerRegistry}
 *       → ERROR if missing (operation must be invocable)
 * </ul>
 *
 * <p>Per §A.7 the merged validator absorbs CapabilityValidator's role too: missing
 * required capabilities surface as a typed denial at dispatch time, not a build-time
 * concern.
 */
public final class ExecutorBindingValidator implements RegistryShapeValidator {

  @Override
  public String name() {
    return "ExecutorBindingValidator";
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
              if (op.executors().isEmpty()) {
                builder.add(
                    new ValidationFinding(
                        op.id().value(),
                        name(),
                        Severity.ERROR,
                        "Operation must declare at least one ExecutorTag (UI, AGENT, or CLI)"));
              }
              OperationRef handlerId = new OperationRef(op.binding().handlerId());
              if (context.handlers().resolve(handlerId).isEmpty()) {
                builder.add(
                    new ValidationFinding(
                        op.id().value(),
                        name(),
                        Severity.ERROR,
                        "binding.handlerId '" + op.binding().handlerId()
                            + "' does not resolve to a registered OperationHandler"));
              }
              return builder.build();
            });
  }
}
