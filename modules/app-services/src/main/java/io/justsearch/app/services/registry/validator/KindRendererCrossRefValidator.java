/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Slice 3a.1.9 §B.B.B D2 — cross-reference validator: every Resource catalog
 * {@code kind} string either resolves through a Category-default renderer or
 * a registered FE specialty hint.
 *
 * <p>Background: slice 3a.1.9 §B.A.G elevated {@code Resource.kind} from a
 * free identifier into a renderer-dispatch hint axis. The FE consumer
 * {@code <jf-resource-view>} calls
 * {@code dispatchResourceRenderer({category, hint: resource.kind})}; specialty
 * registrations like
 * {@code (EVENT_STREAM, hint="operation-history") → <jf-table>} dispatch
 * ahead of Category defaults. The original slice didn't validate the
 * inverse direction: a {@code kind} value the FE has no registration for
 * silently falls back to the Category default (which may not exist).
 *
 * <p>Defect class this catches: a Resource declares a Category with no
 * Category-default renderer (HISTORY at slice 448 phase 6 close — slice 448
 * retired LOG_TAIL per CONFLICT-LEDGER C-012 path-b) AND no specialty
 * registration for its {@code kind} → the FE renders a "renderer not shipped"
 * placeholder with no compile-time signal.
 *
 * <p>Coverage scope: this validator only runs against the
 * <strong>shipping FE registration set</strong> known to the test
 * harness. The known-set is hard-coded in {@code ValidatorRunnerTest}
 * because the FE registrations live in TS modules not statically
 * accessible from the JVM. A future runtime-introspection (or
 * codegen-from-FE-registry) extension could replace the hard-coded set;
 * meanwhile, the test harness updates whenever a new specialty lands.
 *
 * <p>Why a separate class: keeps {@link ResourceAreaValidator} focused on
 * shape constraints (every entry is well-formed). This class is about
 * cross-substrate consistency (catalog ↔ FE registry), a different
 * concern at a different validation layer.
 */
public final class KindRendererCrossRefValidator {

  /**
   * Categories that ship a default renderer at slice 3a.1.9 close. Resources
   * declaring these Categories render via the default when their {@code kind}
   * has no specialty registration — a deliberate fallback, not a defect.
   *
   * <p>Source of truth: {@code modules/ui-web/src/shell-v0/renderers/resourceRegistryDefaults.ts}.
   * If a slice adds a Category default (e.g., slice 444c HISTORY shipping
   * {@code <jf-history-list>}), update this set in lockstep.
   */
  private static final Set<Category> CATEGORIES_WITH_DEFAULT_RENDERER =
      Set.of(
          Category.STATE,
          Category.EVENT_STREAM,
          Category.TABULAR,
          Category.TIMESERIES);

  /**
   * Validates {@code catalog} against the supplied {@code knownKinds} set.
   *
   * <p>Findings are emitted when:
   *
   * <ul>
   *   <li>The Resource declares a Category with no default renderer
   *       (HISTORY at slice 448 phase 6 close), AND
   *   <li>The Resource's {@code kind} is not in {@code knownKinds}.
   * </ul>
   *
   * <p>Resources declaring a Category WITH a default renderer never produce
   * findings even if their {@code kind} is unknown — the Category default
   * fires (validated separately by the resourceViewContract conformance
   * test in modules/ui-web).
   *
   * @param catalog the catalog to check
   * @param knownKinds the set of {@code kind} strings the FE has specialty
   *     registrations for (e.g., {@code "operation-history"} for the Ledger
   *     specialty registered in {@code resourceRegistryDefaults.ts})
   * @return list of findings; empty when every Resource resolves through
   *     either a Category default or a known specialty
   */
  public List<ResourceAreaValidator.Finding> validate(
      ResourceCatalog catalog, Set<String> knownKinds) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(knownKinds, "knownKinds");
    List<ResourceAreaValidator.Finding> findings = new ArrayList<>();
    for (Resource entry : catalog.definitions()) {
      Category category = entry.category();
      if (category == null) continue; // ResourceAreaValidator catches this
      if (CATEGORIES_WITH_DEFAULT_RENDERER.contains(category)) continue;
      String kind = entry.kind();
      if (kind == null || kind.isBlank()) continue; // ResourceAreaValidator catches this
      if (!knownKinds.contains(kind)) {
        String id = entry.id() == null ? "<null-id>" : entry.id().value();
        findings.add(
            new ResourceAreaValidator.Finding(
                id,
                "kind '"
                    + kind
                    + "' has no FE specialty renderer registered AND "
                    + category
                    + " has no default renderer; the Resource will render a "
                    + "'renderer not shipped' placeholder. "
                    + "Either add a specialty in resourceRegistryDefaults.ts "
                    + "or wait until slice "
                    + suggestSliceFor(category)
                    + " ships the Category default."));
      }
    }
    return List.copyOf(findings);
  }

  private static String suggestSliceFor(Category category) {
    return switch (category) {
      case HISTORY -> "444c (HISTORY substrate)";
      default -> "<unknown>";
    };
  }
}
