/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.RequiredCapability;
import java.util.Objects;
import java.util.Set;

/**
 * Context passed to each {@link RegistryShapeValidator}'s {@code validate(...)} call.
 *
 * <p>Per tempdoc 429 §E.7: carries the substrate inputs every validator needs — the
 * catalog under test, the handler registry (for binding checks), the loaded i18n keys
 * (for I18nKeyValidator), and the declared capability set (for capability checks). The
 * test wiring constructs the full context once and passes it to each validator's
 * parameterized run.
 */
public record ValidationContext(
    OperationCatalog catalog,
    HandlerRegistry handlers,
    Set<String> validI18nKeys,
    Set<RequiredCapability> declaredCapabilities) {

  public ValidationContext {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(handlers, "handlers");
    validI18nKeys = validI18nKeys == null ? Set.of() : Set.copyOf(validI18nKeys);
    declaredCapabilities =
        declaredCapabilities == null ? Set.of() : Set.copyOf(declaredCapabilities);
  }
}
