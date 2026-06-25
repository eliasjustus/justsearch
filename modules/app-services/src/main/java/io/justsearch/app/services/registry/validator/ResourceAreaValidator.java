/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.PathPolicy;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Build-time validator over a {@link ResourceCatalog} — asserts each Resource entry has
 * a well-formed shape suitable for the registry's wire format and FE consumer dispatch.
 *
 * <p>Per tempdoc 430 §A.8 + rev 3.20 §B.AF.6 rec 3: the slice's acceptance criteria
 * called for {@code ResourceAreaValidator} to catch misconfigured Resource entries.
 * Slice 1.3 will introduce more Resource catalogs (Log Resources, Table Resources);
 * a generic validator is more valuable than a HealthResourceCatalog-specific test.
 *
 * <p>Per slice 444a Phase 3 (§B.A.5 + §B.A.6 + §B.D): extended with the
 * Category × SubscriptionMode matrix, HistoryPolicy required-when rules, and the
 * resumeWindow ≤ retention partial enforcement. Semantic discipline (replace-only
 * on STATE × SSE_STREAM, etc.) is recipe-documented in
 * {@code 30-agent-workflows/01a-e-add-<category>-resource.md}, NOT validator-enforced.
 *
 * <p>This validator stands parallel to the 6 {@link RegistryShapeValidator} implementations
 * (which validate {@link io.justsearch.agent.api.registry.OperationCatalog}). It does not
 * implement {@code RegistryShapeValidator} because that interface's
 * {@link ValidationContext} parameter is operation-specific; rather than generalize the
 * 429 framework for one new validator, this class exposes a direct
 * {@link #validate(ResourceCatalog)} entry point. Slice 1.3 may revisit unification if
 * additional Resource validators emerge.
 */
public final class ResourceAreaValidator {

  /** A single shape violation found during validation. */
  public record Finding(String resourceId, String issue) {

    public Finding {
      Objects.requireNonNull(resourceId, "resourceId");
      Objects.requireNonNull(issue, "issue");
    }
  }

  /**
   * Validates every entry in {@code catalog} against the documented shape constraints.
   *
   * @param catalog the catalog to validate; must be non-null
   * @return the list of findings; empty when all entries are well-formed
   */
  public List<Finding> validate(ResourceCatalog catalog) {
    return validate(catalog, List.of());
  }

  /**
   * Slice 445 substrate-extension: validates {@code catalog} with optional
   * cross-reference enforcement against {@code operationCatalogs}.
   *
   * <p>When {@code operationCatalogs} is non-empty, each
   * {@link Resource#itemOperations()}, {@link Resource#collectionOperations()}, and
   * {@link Privacy#resolver()} OperationRef is checked to resolve in one of the
   * provided catalogs. If empty, those cross-references are not enforced —
   * callers that don't have access to the OperationCatalog at validate-time
   * (legacy tests) get the original behavior.
   *
   * <p>This is a partial implementation of the cross-reference-enforcement
   * concern that slice 3a-1-8c will generalize across all registry primitives.
   */
  public List<Finding> validate(
      ResourceCatalog catalog, List<? extends OperationCatalog> operationCatalogs) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(operationCatalogs, "operationCatalogs");
    Set<String> knownOperationIds = collectOperationIds(operationCatalogs);
    List<Finding> findings = new ArrayList<>();
    for (Resource entry : catalog.definitions()) {
      validateEntry(entry, findings, knownOperationIds, !operationCatalogs.isEmpty());
    }
    return List.copyOf(findings);
  }

  private static Set<String> collectOperationIds(List<? extends OperationCatalog> catalogs) {
    Set<String> ids = new HashSet<>();
    for (OperationCatalog catalog : catalogs) {
      for (Operation op : catalog.definitions()) {
        if (op.id() != null) {
          ids.add(op.id().value());
        }
      }
    }
    return ids;
  }

  private static void validateEntry(
      Resource entry,
      List<Finding> findings,
      Set<String> knownOperationIds,
      boolean crossReferenceEnabled) {
    Objects.requireNonNull(entry, "entry");
    String id = entry.id() == null ? "<null-id>" : entry.id().value();

    // Schema URL: every Resource entry must declare a non-blank schema URL pointing at
    // the wire payload's JSON Schema (per §6 + §A.5 wire format expectations).
    if (entry.schema() == null || entry.schema().isBlank()) {
      findings.add(new Finding(id, "schema URL must be non-blank"));
    }

    // SubscriptionMode: required.
    SubscriptionMode mode = entry.subscriptionMode();
    if (mode == null) {
      findings.add(new Finding(id, "subscriptionMode must not be null"));
    }

    // Endpoint: required + format constraints per subscriptionMode.
    if (entry.endpoint() == null || entry.endpoint().isBlank()) {
      findings.add(new Finding(id, "endpoint must be non-blank"));
    } else if (mode == SubscriptionMode.SSE_STREAM) {
      String endpoint = entry.endpoint();
      // Slice 443: relaxed from `/api/`-only to `/api/` OR `/infra/` — the existing
      // capability handshake stream is at `/infra/capabilities/stream`. Both prefixes are
      // production conventions: `/api/` for application-level endpoints, `/infra/` for
      // infrastructure-level (capabilities, health-checks).
      if (!endpoint.startsWith("/api/") && !endpoint.startsWith("/infra/")) {
        findings.add(
            new Finding(
                id,
                "SSE_STREAM endpoint should start with /api/ or /infra/ (got: " + endpoint + ")"));
      }
      if (!endpoint.endsWith("/stream")) {
        findings.add(
            new Finding(id, "SSE_STREAM endpoint should end with /stream (got: " + endpoint + ")"));
      }
    }

    // Kind: required, non-blank, identifies the FE renderer.
    if (entry.kind() == null || entry.kind().isBlank()) {
      findings.add(new Finding(id, "kind must be non-blank"));
    }

    // Presentation: required; label + description i18n keys must be non-null.
    if (entry.presentation() == null) {
      findings.add(new Finding(id, "presentation must not be null"));
    } else {
      if (entry.presentation().labelKey() == null) {
        findings.add(new Finding(id, "presentation.labelKey must not be null"));
      }
      if (entry.presentation().descriptionKey() == null) {
        findings.add(new Finding(id, "presentation.descriptionKey must not be null"));
      }
    }

    // Provenance: required.
    if (entry.provenance() == null) {
      findings.add(new Finding(id, "provenance must not be null"));
    }

    // ----- Slice 444a Phase 3 rules -----

    // Category: required (defense-in-depth; Resource compact constructor also null-checks).
    Category category = entry.category();
    if (category == null) {
      findings.add(new Finding(id, "category must not be null"));
    }

    // Category × SubscriptionMode matrix (per slice 444a §B.A.5).
    if (category != null && mode != null && !isPermittedMatrixCell(category, mode)) {
      findings.add(
          new Finding(
              id,
              "subscriptionMode "
                  + mode
                  + " is not permitted for category "
                  + category
                  + " (per slice 444a §B.A.5)"));
    }

    // HistoryPolicy required-when by Category (per slice 444a §B.A.6 + §B.D).
    // §B.D amendment: STATE/TABULAR forbid HistoryPolicy entirely; the §B.A.6 "unless
    // EXTERNAL" carve-out is deferred until a concrete workload calls for it.
    Optional<HistoryPolicy> history = entry.history();
    if (category != null && history != null) {
      switch (category) {
        case EVENT_STREAM, HISTORY -> {
          if (history.isEmpty()) {
            findings.add(new Finding(id, category + " category requires a HistoryPolicy"));
          }
        }
        // TIMESERIES forbids HistoryPolicy because the values[] array on the
        // TimeseriesSnapshot wire payload carries the window implicitly (per slice 3a.1.4
        // §B.4 + §B.5). Adding a separate HistoryPolicy would double-declare retention.
        case STATE, TABULAR, TIMESERIES -> {
          if (history.isPresent()) {
            findings.add(new Finding(id, category + " category forbids HistoryPolicy"));
          }
        }
      }
    }

    // resumeWindow ≤ effective retention (per slice 444a §B.A.6, partial enforcement).
    // Only the DURABLE-with-explicit-retention case is computable. RING_BUFFER's effective
    // retention depends on event rate (not declarable); EXTERNAL's retention is opaque to
    // the Resource; DURABLE-with-capacity-only also depends on rate.
    if (history != null && history.isPresent()) {
      HistoryPolicy hp = history.get();
      if (hp.mode() == HistoryPolicy.Mode.DURABLE && hp.retention().isPresent()) {
        Duration retention = hp.retention().get();
        if (hp.resumeWindow().compareTo(retention) > 0) {
          findings.add(
              new Finding(
                  id,
                  "history.resumeWindow ("
                      + hp.resumeWindow()
                      + ") exceeds DURABLE retention ("
                      + retention
                      + ")"));
        }
      }
    }

    // ----- Slice 445 substrate-extension rules -----

    // Privacy axis: non-null is enforced by Resource compact constructor; this is
    // defense-in-depth that surfaces a clear Finding rather than a NullPointerException
    // if a Resource were ever constructed with reflection bypass.
    Privacy privacy = entry.privacy();
    if (privacy == null) {
      findings.add(new Finding(id, "privacy axis must not be null"));
    } else if (privacy.pathPolicy() == PathPolicy.HASHED_REQUIRES_RESOLVER
        && privacy.resolver().isEmpty()) {
      // Privacy compact constructor catches this too, but include the validator-side
      // finding so build-time output is uniform.
      findings.add(
          new Finding(
              id, "privacy.pathPolicy HASHED_REQUIRES_RESOLVER requires a resolver Operation id"));
    }

    // itemOperations + collectionOperations cross-reference: if the caller supplied
    // operationCatalogs, every referenced OperationRef must resolve. Otherwise skip
    // (legacy callers without OperationCatalog access).
    if (crossReferenceEnabled) {
      checkOperationsResolve(
          entry.itemOperations(), id, "itemOperations", knownOperationIds, findings);
      checkOperationsResolve(
          entry.collectionOperations(),
          id,
          "collectionOperations",
          knownOperationIds,
          findings);
      if (privacy != null && privacy.resolver().isPresent()) {
        OperationRef resolver = privacy.resolver().get();
        if (!knownOperationIds.contains(resolver.value())) {
          findings.add(
              new Finding(
                  id,
                  "privacy.resolver references unknown Operation id: " + resolver.value()));
        }
      }
    }
  }

  private static void checkOperationsResolve(
      Set<OperationRef> operationIds,
      String resourceId,
      String fieldName,
      Set<String> knownOperationIds,
      List<Finding> findings) {
    if (operationIds == null) return;
    for (OperationRef opId : operationIds) {
      if (opId == null) continue;
      if (!knownOperationIds.contains(opId.value())) {
        findings.add(
            new Finding(
                resourceId,
                fieldName + " references unknown Operation id: " + opId.value()));
      }
    }
  }

  /**
   * Encodes the Category × SubscriptionMode permission matrix from slice 444a §B.A.5.
   *
   * <p>Exhaustive switch over {@link Category} — no default branch. Adding a new Category
   * value (governed by shape governance per {@code 10-kernel/04-shape-governance.md})
   * will require updating this method, with the compiler enforcing visibility into the
   * matrix coverage.
   *
   * <p>Permitted cells:
   *
   * <ul>
   *   <li>STATE × {ONE_SHOT, SSE_STREAM, POLLING}: all permitted (replace-only on SSE is
   *       recipe discipline per {@code 01a-add-state-resource.md}, not validator-enforced).
   *   <li>EVENT_STREAM × {ONE_SHOT, SSE_STREAM, POLLING}: all permitted (POLLING is rare
   *       but legal).
   *   <li>HISTORY × {ONE_SHOT, SSE_STREAM}: permitted; POLLING rejected (paginated history
   *       reads are ONE_SHOT; SSE_STREAM resumes from cursor; POLLING has no defensible
   *       semantic).
   *   <li>TABULAR × {ONE_SHOT, SSE_STREAM, POLLING}: all permitted.
   * </ul>
   *
   * <p>Slice 448 phase 6 (2026-05-07) retired LOG_TAIL per CONFLICT-LEDGER C-012 path-b.
   * Operator-trace surfaces are modeled as the sibling
   * {@code DiagnosticChannel} primitive instead.
   */
  private static boolean isPermittedMatrixCell(Category category, SubscriptionMode mode) {
    return switch (category) {
      case STATE, EVENT_STREAM, TABULAR -> true;
      case HISTORY, TIMESERIES -> mode != SubscriptionMode.POLLING;
    };
  }
}
