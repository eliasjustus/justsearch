/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Severity;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates Operation id namespacing.
 *
 * <p>Most of the rule is enforced at type construction by
 * {@link io.justsearch.agent.api.registry.OperationRef} — this validator catches the
 * residual case where IDs accidentally collide between catalogs (different catalogs
 * declaring the same id).
 */
public final class NamespacingValidator implements RegistryShapeValidator {

  private static final Pattern NAMESPACE_PATTERN =
      Pattern.compile("^(core|vendor\\.[a-z][a-z0-9-]*)\\.[a-z][a-z0-9-]*$");

  @Override
  public String name() {
    return "NamespacingValidator";
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
              String idValue = op.id().value();
              if (!NAMESPACE_PATTERN.matcher(idValue).matches()) {
                builder.add(
                    new ValidationFinding(
                        idValue,
                        name(),
                        Severity.ERROR,
                        "Operation id must match ^(core|vendor.<x>).<id>$ (lowercase, kebab-case)"));
              }
              if (!idValue.startsWith(context.catalog().namespace() + ".")) {
                builder.add(
                    new ValidationFinding(
                        idValue,
                        name(),
                        Severity.WARNING,
                        "Operation id namespace prefix doesn't match catalog namespace '"
                            + context.catalog().namespace() + "'"));
              }
              return builder.build();
            });
  }
}
