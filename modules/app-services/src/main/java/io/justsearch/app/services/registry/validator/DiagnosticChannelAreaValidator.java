/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.SubCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Build-time validator over a {@link DiagnosticChannelCatalog} — slice 448 phase 1 stub.
 *
 * <p>Stands parallel to {@link ResourceAreaValidator}: a standalone validator with a
 * direct {@link #validate(DiagnosticChannelCatalog)} entry point rather than a
 * {@link RegistryShapeValidator} implementation (the latter's
 * {@link ValidationContext} parameter is OperationCatalog-specific). Slice 448 phase 2
 * may unify validators when {@code RegistryController} is parameterized.
 *
 * <p>V1 enforces only structural rules; semantic discipline (recursion mechanism,
 * subscription parameter range) is recipe-documented in the substrate amendment, not
 * validator-enforced. The validator catches catalog-author mistakes that would corrupt
 * wire shape; it does not enforce the appender or subscription correctness.
 *
 * <p>Phase-1 rules:
 *
 * <ul>
 *   <li>Channel ids unique within the catalog.
 *   <li>Endpoint non-blank (already enforced in the record's compact constructor; mirrored
 *       here so the validator surfaces a structured finding rather than letting an
 *       exception escape).
 *   <li>Selector's {@link io.justsearch.agent.api.registry.LoggerNamespaceSelector#prefixMappings()}
 *       must declare at least one mapping (a channel that resolves every emission to the
 *       default sub-category is structurally pointless).
 * </ul>
 *
 * <p>Phase-2+ rules (NOT in V1, intentional):
 *
 * <ul>
 *   <li>Cross-reference selector sub-categories against the closed
 *       {@link io.justsearch.agent.api.registry.SubCategory} enum — already enforced by
 *       Java's type system.
 *   <li>Cross-reference catalog ids against the FE registered channel set (parallel to
 *       {@code KindRendererCrossRefValidator}). Deferred until the FE catalog client lands
 *       in phase 5.
 * </ul>
 */
public final class DiagnosticChannelAreaValidator {

  /** A single shape violation found during validation. */
  public record Finding(String channelId, String issue) {

    public Finding {
      Objects.requireNonNull(channelId, "channelId");
      Objects.requireNonNull(issue, "issue");
    }
  }

  /**
   * Validates every entry in {@code catalog} against the V1 structural constraints.
   *
   * @param catalog the catalog to validate; must be non-null
   * @return the list of findings; empty when all entries are well-formed
   */
  public List<Finding> validate(DiagnosticChannelCatalog catalog) {
    Objects.requireNonNull(catalog, "catalog");
    final List<Finding> findings = new ArrayList<>();
    final Set<String> seenIds = new HashSet<>();
    for (final DiagnosticChannel channel : catalog.definitions()) {
      final String id = channel.id().value();
      if (!seenIds.add(id)) {
        findings.add(new Finding(id, "duplicate channel id within catalog"));
      }
      if (channel.endpoint().isBlank()) {
        findings.add(new Finding(id, "endpoint must be non-blank"));
      }
      if (channel.selector().prefixMappings().isEmpty()) {
        findings.add(
            new Finding(
                id,
                "LoggerNamespaceSelector.prefixMappings must declare at least one entry; "
                    + "default-only resolution is structurally pointless"));
      }
      // Sanity: declared sub-categories must be a subset of the closed enum (Java's type
      // system enforces this; the loop is here to make the structural intent explicit).
      for (final SubCategory sub : channel.selector().declaredSubCategories()) {
        Objects.requireNonNull(sub, "selector.declaredSubCategories must not contain null");
      }
    }
    return List.copyOf(findings);
  }
}
