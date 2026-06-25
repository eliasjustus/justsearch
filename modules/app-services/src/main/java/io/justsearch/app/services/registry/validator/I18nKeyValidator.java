/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.Severity;
import java.util.stream.Stream;

/**
 * Validates that every i18n key on an Operation resolves against the loaded message catalog.
 *
 * <p>Per tempdoc 429 §C.D: ERROR severity (slice 1.1.d / tempdoc 431 shipped, so the
 * gate is fully active). The validator's {@link ValidationContext#validI18nKeys()} field
 * is loaded at test time from {@code messages/registry-operation.en.properties}; missing
 * keys fail the build.
 *
 * <p>Tempdoc 564 facet 4c: the keys are now <em>generated</em> from the operation id
 * ({@code Presentation.forId} / {@code ConfirmStrategy.typedForId} &rarr;
 * {@code ops.<id-suffix>.{label,description,confirm}}), so this is no longer a
 * drift-from-the-id check (that was the deleted {@code I18nKeyConventionValidator}) but the
 * remaining <em>coverage</em> gate: the id-derived keys must have authored text in the catalog.
 * The label/description/confirm <em>text</em> is the one irreducible per-op authoring.
 */
public final class I18nKeyValidator implements RegistryShapeValidator {

  @Override
  public String name() {
    return "I18nKeyValidator";
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
              String labelKey = op.presentation().labelKey().value();
              if (!context.validI18nKeys().contains(labelKey)) {
                builder.add(
                    new ValidationFinding(
                        op.id().value(),
                        name(),
                        Severity.ERROR,
                        "presentation.labelKey '" + labelKey + "' does not resolve in the "
                            + "registry-operation message catalog"));
              }
              String descriptionKey = op.presentation().descriptionKey().value();
              if (!context.validI18nKeys().contains(descriptionKey)) {
                builder.add(
                    new ValidationFinding(
                        op.id().value(),
                        name(),
                        Severity.ERROR,
                        "presentation.descriptionKey '" + descriptionKey + "' does not "
                            + "resolve in the registry-operation message catalog"));
              }
              if (op.policy().confirm() instanceof ConfirmStrategy.Typed typed) {
                String confirmKey = typed.confirmTextKey().value();
                if (!context.validI18nKeys().contains(confirmKey)) {
                  builder.add(
                      new ValidationFinding(
                          op.id().value(),
                          name(),
                          Severity.ERROR,
                          "policy.confirm.confirmTextKey '" + confirmKey + "' does not "
                              + "resolve in the registry-operation message catalog"));
                }
              }
              return builder.build();
            });
  }
}
