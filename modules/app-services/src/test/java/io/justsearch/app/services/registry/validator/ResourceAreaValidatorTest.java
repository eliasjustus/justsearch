package io.justsearch.app.services.registry.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Privacy;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import io.justsearch.app.observability.CapabilitiesResourceCatalog;
import io.justsearch.app.observability.health.HealthResourceCatalog;
import io.justsearch.app.observability.operations.OperationHistoryResourceCatalog;
import io.justsearch.app.observability.runtime.RuntimeContextResourceCatalog;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per tempdoc 430 §A.8 + rev 3.20 §B.AF.6 rec 3: structural assertion that Resource
 * catalog entries have well-formed shapes. Catches misconfigured Resource entries
 * regardless of which catalog they come from — slice 1.3's Log/Table Resources will
 * benefit from this same validator.
 *
 * <p>Phase 2 of slice 444a extended the {@code Resource} record with {@code category},
 * {@code history}, and {@code recovery} fields. The synthetic test entries below pass
 * pass-through safe values ({@link Category#STATE} + empty Optionals) for those fields
 * because the existing validator rules don't depend on them. Phase 3 will add the
 * Category × SubscriptionMode matrix tests + HistoryPolicy required-when tests.
 */
@DisplayName("ResourceAreaValidator")
final class ResourceAreaValidatorTest {

  // ============================================================
  // Production catalog — HealthResourceCatalog must pass
  // ============================================================

  @Test
  @DisplayName("HealthResourceCatalog produces no findings (smoke)")
  void healthResourceCatalogIsClean() {
    ResourceAreaValidator validator = new ResourceAreaValidator();
    List<ResourceAreaValidator.Finding> findings =
        validator.validate(new HealthResourceCatalog());
    assertTrue(
        findings.isEmpty(),
        "HealthResourceCatalog should have no shape findings; got: " + findings);
  }

  @Test
  @DisplayName("RuntimeContextResourceCatalog produces no findings (slice 440 smoke)")
  void runtimeContextResourceCatalogIsClean() {
    ResourceAreaValidator validator = new ResourceAreaValidator();
    List<ResourceAreaValidator.Finding> findings =
        validator.validate(new RuntimeContextResourceCatalog());
    assertTrue(
        findings.isEmpty(),
        "RuntimeContextResourceCatalog should have no shape findings; got: " + findings);
  }

  @Test
  @DisplayName("CapabilitiesResourceCatalog produces no findings (slice 443 smoke)")
  void capabilitiesResourceCatalogIsClean() {
    ResourceAreaValidator validator = new ResourceAreaValidator();
    List<ResourceAreaValidator.Finding> findings =
        validator.validate(new CapabilitiesResourceCatalog());
    assertTrue(
        findings.isEmpty(),
        "CapabilitiesResourceCatalog should have no shape findings; got: " + findings);
  }

  @Test
  @DisplayName("OperationHistoryResourceCatalog produces no findings (slice 444b smoke)")
  void operationHistoryResourceCatalogIsClean() {
    ResourceAreaValidator validator = new ResourceAreaValidator();
    List<ResourceAreaValidator.Finding> findings =
        validator.validate(new OperationHistoryResourceCatalog());
    assertTrue(
        findings.isEmpty(),
        "OperationHistoryResourceCatalog should have no shape findings; got: " + findings);
  }

  // ============================================================
  // Synthetic catalogs — each shape constraint catches a specific defect
  // ============================================================

  @Test
  @DisplayName("blank schema URL is flagged")
  void blankSchemaUrlFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-entry"),
            samplePresentation(),
            "  ",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertTrue(findings.stream().anyMatch(f -> f.issue().contains("schema URL")));
  }

  @Test
  @DisplayName("blank endpoint is flagged")
  void blankEndpointFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-entry"),
            samplePresentation(),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertTrue(findings.stream().anyMatch(f -> f.issue().contains("endpoint must be non-blank")));
  }

  @Test
  @DisplayName("SSE_STREAM endpoint not starting with /api/ or /infra/ is flagged")
  void sseEndpointMissingApiPrefixFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-entry"),
            samplePresentation(),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/badprefix/foo/stream", // neither /api/ nor /infra/
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    // Per slice 443: relaxation accepts /api/ OR /infra/. The finding message mentions
    // both. A regression that disabled the prefix check entirely would NOT produce this
    // finding and the assertion would fail.
    assertTrue(
        findings.stream()
            .anyMatch(f -> f.issue().contains("/api/") && f.issue().contains("/infra/")),
        "Finding should mention both accepted prefixes; got: " + findings);
  }

  @Test
  @DisplayName("SSE_STREAM endpoint at /infra/<...>/stream is accepted (slice 443 relaxation)")
  void sseEndpointInfraPrefixIsAccepted() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-entry"),
            samplePresentation(),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/infra/capabilities/stream",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    // Locks the relaxation: a regression that reverted to /api/-only would flag this
    // endpoint and produce a finding, failing the assertion.
    assertTrue(
        findings.stream().noneMatch(f -> f.issue().contains("should start with")),
        "/infra/ prefix must be accepted; got prefix-related findings: " + findings);
  }

  @Test
  @DisplayName("SSE_STREAM endpoint not ending with /stream is flagged")
  void sseEndpointMissingStreamSuffixFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-entry"),
            samplePresentation(),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/api/health/events", // missing /stream suffix
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertTrue(findings.stream().anyMatch(f -> f.issue().contains("/stream")));
  }

  @Test
  @DisplayName("blank kind is flagged")
  void blankKindFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-entry"),
            samplePresentation(),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "  ",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertTrue(findings.stream().anyMatch(f -> f.issue().contains("kind")));
  }

  @Test
  @DisplayName("clean entry produces no findings (parity with HealthResourceCatalog)")
  void cleanEntryIsClean() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-clean"),
            samplePresentation(),
            "https://example/schema.json",
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-clean",
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertEquals(List.of(), findings);
  }

  @Test
  @DisplayName("multiple defects on one entry produce multiple findings")
  void multipleFindingsAccumulate() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-broken"),
            samplePresentation(),
            "  ", // bad schema
            Category.STATE,
            SubscriptionMode.SSE_STREAM,
            "  ", // bad endpoint
            "  ", // bad kind
            Optional.empty(),
            Optional.empty(),
            Provenance.core("1.0"),
            Privacy.noPaths(),
            Set.of(),
            Set.of(),
            "");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertFalse(
        findings.size() < 3,
        "expected ≥3 findings (schema + endpoint + kind); got: " + findings);
  }

  // ============================================================
  // Phase 3 — Category × SubscriptionMode matrix (slice 444a §B.A.5)
  // ============================================================

  // 11 permitted cells: assert no matrix-related finding.
  // 1 rejected cell (HISTORY × POLLING): assert finding present.
  // (LOG_TAIL retired in slice 448 phase 6 — see CONFLICT-LEDGER C-012 path-b.)

  @Test
  @DisplayName("matrix: STATE × ONE_SHOT permitted")
  void matrixStateOneShot() {
    assertMatrixPermitted(Category.STATE, SubscriptionMode.ONE_SHOT, Optional.empty());
  }

  @Test
  @DisplayName("matrix: STATE × SSE_STREAM permitted (replace-only is recipe discipline)")
  void matrixStateSseStream() {
    assertMatrixPermitted(Category.STATE, SubscriptionMode.SSE_STREAM, Optional.empty());
  }

  @Test
  @DisplayName("matrix: STATE × POLLING permitted")
  void matrixStatePolling() {
    assertMatrixPermitted(Category.STATE, SubscriptionMode.POLLING, Optional.empty());
  }

  @Test
  @DisplayName("matrix: EVENT_STREAM × ONE_SHOT permitted (snapshot fallback)")
  void matrixEventStreamOneShot() {
    assertMatrixPermitted(
        Category.EVENT_STREAM, SubscriptionMode.ONE_SHOT, Optional.of(sampleRingBufferPolicy()));
  }

  @Test
  @DisplayName("matrix: EVENT_STREAM × SSE_STREAM permitted")
  void matrixEventStreamSseStream() {
    assertMatrixPermitted(
        Category.EVENT_STREAM, SubscriptionMode.SSE_STREAM, Optional.of(sampleRingBufferPolicy()));
  }

  @Test
  @DisplayName("matrix: EVENT_STREAM × POLLING permitted (rare; prefer SSE)")
  void matrixEventStreamPolling() {
    assertMatrixPermitted(
        Category.EVENT_STREAM, SubscriptionMode.POLLING, Optional.of(sampleRingBufferPolicy()));
  }

  @Test
  @DisplayName("matrix: HISTORY × ONE_SHOT permitted (paginated query)")
  void matrixHistoryOneShot() {
    assertMatrixPermitted(
        Category.HISTORY, SubscriptionMode.ONE_SHOT, Optional.of(sampleDurableRetentionPolicy()));
  }

  @Test
  @DisplayName("matrix: HISTORY × SSE_STREAM permitted (resume from cursor)")
  void matrixHistorySseStream() {
    assertMatrixPermitted(
        Category.HISTORY, SubscriptionMode.SSE_STREAM, Optional.of(sampleDurableRetentionPolicy()));
  }

  @Test
  @DisplayName("matrix: HISTORY × POLLING REJECTED")
  void matrixHistoryPollingRejected() {
    Resource entry =
        sampleEntry(
            Category.HISTORY, SubscriptionMode.POLLING, Optional.of(sampleDurableRetentionPolicy()));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("not permitted for category")));
  }

  @Test
  @DisplayName("matrix: TABULAR × ONE_SHOT permitted")
  void matrixTabularOneShot() {
    assertMatrixPermitted(Category.TABULAR, SubscriptionMode.ONE_SHOT, Optional.empty());
  }

  @Test
  @DisplayName("matrix: TABULAR × SSE_STREAM permitted")
  void matrixTabularSseStream() {
    assertMatrixPermitted(Category.TABULAR, SubscriptionMode.SSE_STREAM, Optional.empty());
  }

  @Test
  @DisplayName("matrix: TABULAR × POLLING permitted")
  void matrixTabularPolling() {
    assertMatrixPermitted(Category.TABULAR, SubscriptionMode.POLLING, Optional.empty());
  }

  // Slice 3a.1.4 (TIMESERIES Resource Category substrate): the values[] array on the
  // TimeseriesSnapshot wire payload carries the window implicitly, so HistoryPolicy is
  // forbidden (matches STATE/TABULAR). SSE_STREAM is the primary path for live samples;
  // ONE_SHOT serves snapshot reads; POLLING is rejected (a polling client that re-fetches
  // the window every interval is structurally identical to subscribing to SSE_STREAM but
  // with worse wire economy).

  @Test
  @DisplayName("matrix: TIMESERIES × ONE_SHOT permitted (snapshot read)")
  void matrixTimeseriesOneShot() {
    assertMatrixPermitted(Category.TIMESERIES, SubscriptionMode.ONE_SHOT, Optional.empty());
  }

  @Test
  @DisplayName("matrix: TIMESERIES × SSE_STREAM permitted (live samples; replace-on-frame)")
  void matrixTimeseriesSseStream() {
    assertMatrixPermitted(Category.TIMESERIES, SubscriptionMode.SSE_STREAM, Optional.empty());
  }

  @Test
  @DisplayName("matrix: TIMESERIES × POLLING REJECTED")
  void matrixTimeseriesPollingRejected() {
    Resource entry = sampleEntry(Category.TIMESERIES, SubscriptionMode.POLLING, Optional.empty());
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("not permitted for category")));
  }

  // ============================================================
  // Phase 3 — HistoryPolicy required-when by Category (slice 444a §B.A.6 + §B.D)
  // ============================================================

  @Test
  @DisplayName("history: STATE without HistoryPolicy is clean")
  void historyStateEmptyClean() {
    assertNoHistoryFinding(Category.STATE, SubscriptionMode.ONE_SHOT, Optional.empty());
  }

  @Test
  @DisplayName("history: STATE WITH HistoryPolicy is FORBIDDEN")
  void historyStateWithPolicyForbidden() {
    Resource entry =
        sampleEntry(
            Category.STATE, SubscriptionMode.ONE_SHOT, Optional.of(sampleRingBufferPolicy()));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("forbids HistoryPolicy")));
  }

  @Test
  @DisplayName("history: EVENT_STREAM with HistoryPolicy is clean")
  void historyEventStreamWithPolicyClean() {
    assertNoHistoryFinding(
        Category.EVENT_STREAM, SubscriptionMode.SSE_STREAM, Optional.of(sampleRingBufferPolicy()));
  }

  @Test
  @DisplayName("history: EVENT_STREAM WITHOUT HistoryPolicy is REQUIRED")
  void historyEventStreamEmptyRequired() {
    Resource entry =
        sampleEntry(Category.EVENT_STREAM, SubscriptionMode.SSE_STREAM, Optional.empty());
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("requires a HistoryPolicy")));
  }

  @Test
  @DisplayName("history: HISTORY with HistoryPolicy is clean")
  void historyHistoryWithPolicyClean() {
    assertNoHistoryFinding(
        Category.HISTORY,
        SubscriptionMode.SSE_STREAM,
        Optional.of(sampleDurableRetentionPolicy()));
  }

  @Test
  @DisplayName("history: HISTORY WITHOUT HistoryPolicy is REQUIRED")
  void historyHistoryEmptyRequired() {
    Resource entry =
        sampleEntry(Category.HISTORY, SubscriptionMode.SSE_STREAM, Optional.empty());
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("requires a HistoryPolicy")));
  }

  @Test
  @DisplayName("history: TABULAR without HistoryPolicy is clean")
  void historyTabularEmptyClean() {
    assertNoHistoryFinding(Category.TABULAR, SubscriptionMode.ONE_SHOT, Optional.empty());
  }

  @Test
  @DisplayName("history: TABULAR WITH HistoryPolicy is FORBIDDEN")
  void historyTabularWithPolicyForbidden() {
    Resource entry =
        sampleEntry(
            Category.TABULAR, SubscriptionMode.ONE_SHOT, Optional.of(sampleRingBufferPolicy()));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("forbids HistoryPolicy")));
  }

  // Slice 3a.1.4: TIMESERIES forbids HistoryPolicy. The values[] array on the
  // TimeseriesSnapshot wire payload carries the window implicitly; declaring a separate
  // HistoryPolicy would double-declare retention.

  @Test
  @DisplayName("history: TIMESERIES without HistoryPolicy is clean")
  void historyTimeseriesEmptyClean() {
    assertNoHistoryFinding(Category.TIMESERIES, SubscriptionMode.SSE_STREAM, Optional.empty());
  }

  @Test
  @DisplayName("history: TIMESERIES WITH HistoryPolicy is FORBIDDEN")
  void historyTimeseriesWithPolicyForbidden() {
    Resource entry =
        sampleEntry(
            Category.TIMESERIES,
            SubscriptionMode.SSE_STREAM,
            Optional.of(sampleRingBufferPolicy()));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("forbids HistoryPolicy")));
  }

  // ============================================================
  // Phase 3 — resumeWindow ≤ retention (slice 444a §B.A.6, partial enforcement)
  // ============================================================

  @Test
  @DisplayName("resume: DURABLE retention=90d resumeWindow=5min is clean")
  void resumeDurableWithinRetention() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.DURABLE,
            Optional.empty(),
            Optional.of(Duration.ofDays(90)),
            OnOverflow.EVICT_OLDEST,
            Duration.ofMinutes(5));
    Resource entry =
        sampleEntry(Category.HISTORY, SubscriptionMode.SSE_STREAM, Optional.of(policy));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .noneMatch(f -> f.issue().contains("exceeds DURABLE retention")));
  }

  @Test
  @DisplayName("resume: DURABLE retention=5min resumeWindow=10min REJECTED")
  void resumeDurableExceedsRetention() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.DURABLE,
            Optional.empty(),
            Optional.of(Duration.ofMinutes(5)),
            OnOverflow.EVICT_OLDEST,
            Duration.ofMinutes(10));
    Resource entry =
        sampleEntry(Category.HISTORY, SubscriptionMode.SSE_STREAM, Optional.of(policy));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .anyMatch(f -> f.issue().contains("exceeds DURABLE retention")));
  }

  @Test
  @DisplayName("resume: DURABLE capacity-only (no retention) — rule does not apply")
  void resumeDurableCapacityOnlyNoCheck() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.DURABLE,
            Optional.of(1_000_000),
            Optional.empty(),
            OnOverflow.EVICT_OLDEST,
            Duration.ofHours(1));
    Resource entry =
        sampleEntry(Category.HISTORY, SubscriptionMode.SSE_STREAM, Optional.of(policy));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .noneMatch(f -> f.issue().contains("exceeds DURABLE retention")));
  }

  @Test
  @DisplayName("resume: RING_BUFFER + any resumeWindow — rule does not apply")
  void resumeRingBufferNoCheck() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.RING_BUFFER,
            Optional.of(200),
            Optional.empty(),
            OnOverflow.EVICT_OLDEST,
            Duration.ofHours(24));
    Resource entry =
        sampleEntry(Category.EVENT_STREAM, SubscriptionMode.SSE_STREAM, Optional.of(policy));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .noneMatch(f -> f.issue().contains("exceeds DURABLE retention")));
  }

  @Test
  @DisplayName("resume: EXTERNAL + any resumeWindow — rule does not apply")
  void resumeExternalNoCheck() {
    HistoryPolicy policy =
        new HistoryPolicy(
            HistoryPolicy.Mode.EXTERNAL,
            Optional.empty(),
            Optional.empty(),
            OnOverflow.EVICT_OLDEST,
            Duration.ofHours(24));
    Resource entry =
        sampleEntry(Category.HISTORY, SubscriptionMode.SSE_STREAM, Optional.of(policy));
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .noneMatch(f -> f.issue().contains("exceeds DURABLE retention")));
  }

  // ============================================================
  // Slice 445 substrate-extension: cross-reference enforcement
  // ============================================================

  @Test
  @DisplayName("itemOperations referencing unknown OperationRef is flagged when crossref enabled")
  void itemOperationsUnknownOperationFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-tabular"),
            samplePresentation(),
            "https://example/schema.json",
            io.justsearch.agent.api.registry.Category.TABULAR,
            io.justsearch.agent.api.registry.SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            io.justsearch.agent.api.registry.Provenance.core("1.0"),
            io.justsearch.agent.api.registry.Privacy.noPaths(),
            Set.of(new OperationRef("core.unknown-op")),
            Set.of(),
            "id");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry), List.of(emptyOperationCatalog()));
    assertTrue(
        findings.stream()
            .anyMatch(
                f ->
                    f.issue().contains("itemOperations references unknown")
                        && f.issue().contains("core.unknown-op")),
        "Expected unknown-op finding; got: " + findings);
  }

  @Test
  @DisplayName(
      "itemOperations cross-reference NOT enforced when no operation catalog supplied (legacy callers)")
  void itemOperationsNotCheckedWithoutCatalogs() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-tabular"),
            samplePresentation(),
            "https://example/schema.json",
            io.justsearch.agent.api.registry.Category.TABULAR,
            io.justsearch.agent.api.registry.SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            io.justsearch.agent.api.registry.Provenance.core("1.0"),
            io.justsearch.agent.api.registry.Privacy.noPaths(),
            Set.of(new OperationRef("core.unknown-op")),
            Set.of(),
            "id");
    // Single-arg validate() is the legacy entry point — no cross-reference check.
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry));
    assertTrue(
        findings.stream().noneMatch(f -> f.issue().contains("itemOperations references unknown")),
        "Legacy single-arg validate() must not enforce cross-reference; got: " + findings);
  }

  @Test
  @DisplayName("Privacy resolver referencing unknown OperationRef is flagged when crossref enabled")
  void privacyResolverUnknownFlagged() {
    Resource entry =
        new Resource(
            new ResourceRef("core.test-tabular"),
            samplePresentation(),
            "https://example/schema.json",
            io.justsearch.agent.api.registry.Category.TABULAR,
            io.justsearch.agent.api.registry.SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            io.justsearch.agent.api.registry.Provenance.core("1.0"),
            io.justsearch.agent.api.registry.Privacy.hashedWithResolver(
                new OperationRef("core.unknown-resolver")),
            Set.of(),
            Set.of(),
            "id");
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry), List.of(emptyOperationCatalog()));
    assertTrue(
        findings.stream()
            .anyMatch(
                f -> f.issue().contains("privacy.resolver references unknown Operation id")),
        "Expected resolver-unknown finding; got: " + findings);
  }

  @Test
  @DisplayName("itemOperations resolving in supplied catalog produces no finding")
  void itemOperationsResolveInCatalogPasses() {
    OperationRef knownId = new OperationRef("core.cancel-job");
    Resource entry =
        new Resource(
            new ResourceRef("core.test-tabular"),
            samplePresentation(),
            "https://example/schema.json",
            io.justsearch.agent.api.registry.Category.TABULAR,
            io.justsearch.agent.api.registry.SubscriptionMode.SSE_STREAM,
            "/api/test/stream",
            "test-kind",
            Optional.empty(),
            Optional.empty(),
            io.justsearch.agent.api.registry.Provenance.core("1.0"),
            io.justsearch.agent.api.registry.Privacy.noPaths(),
            Set.of(knownId),
            Set.of(),
            "id");
    io.justsearch.agent.api.registry.OperationCatalog opCat =
        new io.justsearch.agent.api.registry.OperationCatalog() {
          @Override
          public String namespace() {
            return "core";
          }

          @Override
          public List<io.justsearch.agent.api.registry.Operation> definitions() {
            return List.of(
                new io.justsearch.agent.api.registry.Operation(
                    knownId,
                    samplePresentation(),
                    io.justsearch.agent.api.registry.Interface.of(
                        "{\"type\":\"object\"}", "{\"type\":\"object\"}"),
                    new io.justsearch.agent.api.registry.OperationPolicy(
                        io.justsearch.agent.api.registry.RiskTier.LOW,
                        io.justsearch.agent.api.registry.ConfirmStrategy.None.INSTANCE,
                        io.justsearch.agent.api.registry.AuditPolicy.NONE,
                        io.justsearch.agent.api.registry.RetryPolicy.noRetry(),
                        Optional.empty(),
                        Set.of(),
                        false),
                    io.justsearch.agent.api.registry.OperationAvailability.empty(),
                    io.justsearch.agent.api.registry.OperationLineage.empty(),
                    io.justsearch.agent.api.registry.Binding.of(knownId),
                    new io.justsearch.agent.api.registry.Provenance(
                        io.justsearch.agent.api.registry.TrustTier.CORE, "test", "1.0"),
                    Set.of(io.justsearch.agent.api.registry.ExecutorTag.UI)));
          }
        };
    List<ResourceAreaValidator.Finding> findings =
        new ResourceAreaValidator().validate(catalogOf(entry), List.of(opCat));
    assertTrue(
        findings.stream().noneMatch(f -> f.issue().contains("itemOperations references unknown")),
        "Resolving itemOp must not produce finding; got: " + findings);
  }

  private static io.justsearch.agent.api.registry.OperationCatalog emptyOperationCatalog() {
    return new io.justsearch.agent.api.registry.OperationCatalog() {
      @Override
      public String namespace() {
        return "test-empty";
      }

      @Override
      public List<io.justsearch.agent.api.registry.Operation> definitions() {
        return List.of();
      }
    };
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static Presentation samplePresentation() {
    return Presentation.of(
        new I18nKey("registry-resource.test.label"),
        new I18nKey("registry-resource.test.description"));
  }

  private static ResourceCatalog catalogOf(Resource entry) {
    return new ResourceCatalog() {
      @Override
      public String namespace() {
        return "test";
      }

      @Override
      public List<Resource> definitions() {
        return List.of(entry);
      }
    };
  }

  /**
   * Builds a Resource entry with safe defaults for everything except the three Phase 3
   * inputs (Category, SubscriptionMode, HistoryPolicy). The endpoint conforms to the
   * SSE_STREAM format ({@code /api/.../stream}) so the existing endpoint-format check
   * doesn't flag SSE entries; non-SSE entries also pass that check trivially.
   */
  private static Resource sampleEntry(
      Category category, SubscriptionMode mode, Optional<HistoryPolicy> history) {
    return new Resource(
        new ResourceRef("core.test-matrix"),
        samplePresentation(),
        "https://example/schema.json",
        category,
        mode,
        "/api/test/stream",
        "test-kind",
        history,
        Optional.empty(),
        Provenance.core("1.0"),
        Privacy.noPaths(),
        Set.of(),
        Set.of(),
        "id");
  }

  private static HistoryPolicy sampleRingBufferPolicy() {
    return new HistoryPolicy(
        HistoryPolicy.Mode.RING_BUFFER,
        Optional.of(200),
        Optional.empty(),
        OnOverflow.EVICT_OLDEST,
        Duration.ofMinutes(5));
  }

  private static HistoryPolicy sampleDurableRetentionPolicy() {
    return new HistoryPolicy(
        HistoryPolicy.Mode.DURABLE,
        Optional.empty(),
        Optional.of(Duration.ofDays(90)),
        OnOverflow.EVICT_OLDEST,
        Duration.ofMinutes(15));
  }

  private static HistoryPolicy sampleExternalPolicy() {
    return new HistoryPolicy(
        HistoryPolicy.Mode.EXTERNAL,
        Optional.of(500),
        Optional.empty(),
        OnOverflow.EVICT_OLDEST,
        Duration.ofMinutes(1));
  }

  private static void assertMatrixPermitted(
      Category category, SubscriptionMode mode, Optional<HistoryPolicy> history) {
    Resource entry = sampleEntry(category, mode, history);
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .noneMatch(f -> f.issue().contains("not permitted for category")),
        "expected matrix to permit " + category + " × " + mode);
  }

  private static void assertNoHistoryFinding(
      Category category, SubscriptionMode mode, Optional<HistoryPolicy> history) {
    Resource entry = sampleEntry(category, mode, history);
    assertTrue(
        new ResourceAreaValidator().validate(catalogOf(entry)).stream()
            .noneMatch(
                f ->
                    f.issue().contains("requires a HistoryPolicy")
                        || f.issue().contains("forbids HistoryPolicy")),
        "expected no history-related finding for "
            + category
            + " × "
            + mode
            + " with history="
            + history);
  }
}
