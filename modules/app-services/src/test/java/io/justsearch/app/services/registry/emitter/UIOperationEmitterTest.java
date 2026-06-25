package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UIOperationEmitter} per tempdoc 429 §6 + §F.3 closure.
 *
 * <p>Verifies the FE shell JSON envelope shape carries id, presentation, intf
 * (inputs/result/errors/uiHints), policy (risk/confirm/audit/undoSupported), provenance,
 * and executors.
 */
final class UIOperationEmitterTest {

  @Test
  void emitProducesFeJsonEnvelope() {
    UIOperationEmitter emitter = new UIOperationEmitter();
    Operation op =
        new Operation(
            new OperationRef("core.example"),
            Presentation.of(
                new I18nKey("ops.example.label"), new I18nKey("ops.example.description")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.HIGH,
                new ConfirmStrategy.Typed(new I18nKey("ops.example.confirm")),
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(),
                true),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(new OperationRef("core.example")),
            Provenance.core("1.0"),
            Set.of(ExecutorTag.UI, ExecutorTag.AGENT));
    OperationCatalog catalog = OperationCatalog.of("core", List.of(op));

    List<Map<String, Object>> entries = emitter.emit(catalog, List.of());

    assertEquals(1, entries.size());
    Map<String, Object> entry = entries.get(0);
    assertEquals("core.example", entry.get("id"));
    assertEquals("operation", entry.get("type"));

    @SuppressWarnings("unchecked")
    Map<String, Object> presentation = (Map<String, Object>) entry.get("presentation");
    assertEquals("ops.example.label", presentation.get("labelKey"));
    assertEquals("ops.example.description", presentation.get("descriptionKey"));

    assertNotNull(entry.get("intf"));

    // Tempdoc 511-followup Track E: enum-name canonical casing
    // (uppercase) on the wire so the FE doesn't need to normalize.
    // The confirm discriminator is `kind` (matches FE) — was `type`.
    @SuppressWarnings("unchecked")
    Map<String, Object> policy = (Map<String, Object>) entry.get("policy");
    assertEquals("HIGH", policy.get("risk"));
    assertEquals(true, policy.get("undoSupported"));
    assertEquals("METADATA_ONLY", policy.get("audit"));
    @SuppressWarnings("unchecked")
    Map<String, Object> confirm = (Map<String, Object>) policy.get("confirm");
    assertEquals("TYPED", confirm.get("kind"));
    assertEquals("ops.example.confirm", confirm.get("confirmTextKey"));

    // Tempdoc 560 WS3 — an operation declaring no inverse still emits the field as JSON null
    // (Optional<OperationRef> → string | null), so the FE wire shape is stable.
    assertTrue(policy.containsKey("inverseOperationId"), "inverseOperationId field must be present");
    assertNull(policy.get("inverseOperationId"), "absent inverse must serialize as null");

    @SuppressWarnings("unchecked")
    Map<String, Object> provenance = (Map<String, Object>) entry.get("provenance");
    assertEquals("CORE", provenance.get("tier"));
    assertEquals("core", provenance.get("contributorId"));
    assertEquals("1.0", provenance.get("version"));

    @SuppressWarnings("unchecked")
    List<String> executors = (List<String>) entry.get("executors");
    assertTrue(executors.contains("UI"));
    assertTrue(executors.contains("AGENT"));

    // Slice 447 §X.3.1 partition + 447-followup-live-wiring §X.12.8 Item 2.2:
    // empty availability/lineage emit as empty objects so the schema baseline
    // and the production wire shape stay in sync.
    @SuppressWarnings("unchecked")
    Map<String, Object> availability = (Map<String, Object>) entry.get("availability");
    assertNotNull(availability, "empty availability must still appear on the wire");

    @SuppressWarnings("unchecked")
    Map<String, Object> lineage = (Map<String, Object>) entry.get("lineage");
    assertNotNull(lineage, "empty lineage must still appear on the wire");
    @SuppressWarnings("unchecked")
    List<String> affects = (List<String>) lineage.get("affects");
    @SuppressWarnings("unchecked")
    List<String> supersedes = (List<String>) lineage.get("supersedes");
    assertEquals(List.of(), affects);
    assertEquals(List.of(), supersedes);
  }

  @Test
  @SuppressWarnings("unchecked")
  void emitSerializesDeclaredInverseOperation() {
    // Tempdoc 560 WS3 — an operation declaring a backend inverse emits the sibling op id, so the
    // FE Effect Journal can materialize an invoke-operation inverse Effect (undo re-issues it).
    UIOperationEmitter emitter = new UIOperationEmitter();
    Operation op =
        new Operation(
            new OperationRef("core.add-thing"),
            Presentation.of(new I18nKey("k"), new I18nKey("k")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                    RiskTier.LOW,
                    ConfirmStrategy.None.INSTANCE,
                    AuditPolicy.METADATA_ONLY,
                    RetryPolicy.noRetry(),
                    Optional.empty(),
                    Set.of(),
                    false)
                .withInverseOperationRef(new OperationRef("core.remove-thing")),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(new OperationRef("core.add-thing")),
            Provenance.core("1.0"),
            Set.of(ExecutorTag.UI));
    OperationCatalog catalog = OperationCatalog.of("core", List.of(op));

    Map<String, Object> entry = emitter.emit(catalog, List.of()).get(0);
    Map<String, Object> policy = (Map<String, Object>) entry.get("policy");
    assertEquals("core.remove-thing", policy.get("inverseOperationId"));
  }

  @Test
  void emitIncludesPopulatedLineageAffects() {
    // Mirrors core.rebuild-index after Item 2.1 — the emitter must surface
    // lineage.affects so the FE history surface (and any future agent
    // retrospection consumer) can read the Operation→Resource AFFECTS edge.
    UIOperationEmitter emitter = new UIOperationEmitter();
    Operation op =
        new Operation(
            new OperationRef("core.rebuild"),
            Presentation.of(new I18nKey("k"), new I18nKey("k")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.HIGH,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            new OperationLineage(
                Set.of(
                    new io.justsearch.agent.api.registry.ResourceRef("core.r-a"),
                    new io.justsearch.agent.api.registry.ResourceRef("core.r-b")),
                Set.of(new OperationRef("core.older-rebuild"))),
            Binding.of(new OperationRef("core.rebuild")),
            Provenance.core("1.0"),
            Set.of(ExecutorTag.UI));
    OperationCatalog catalog = OperationCatalog.of("core", List.of(op));

    Map<String, Object> entry = emitter.emit(catalog, List.of()).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> lineage = (Map<String, Object>) entry.get("lineage");
    @SuppressWarnings("unchecked")
    List<String> affects = (List<String>) lineage.get("affects");
    @SuppressWarnings("unchecked")
    List<String> supersedes = (List<String>) lineage.get("supersedes");
    // Sorted for deterministic baseline tests.
    assertEquals(List.of("core.r-a", "core.r-b"), affects);
    assertEquals(List.of("core.older-rebuild"), supersedes);
  }

  // Imports for the new test (kept minimal — full presentation factory unused).
  // (Imports already at file top: Binding, ConfirmStrategy, Presentation, etc.)

  @Test
  void emitFiltersByUiExecutor() {
    UIOperationEmitter emitter = new UIOperationEmitter();
    Operation uiOp = makeOp("core.ui", Set.of(ExecutorTag.UI));
    Operation agentOnly = makeOp("core.agent-only", Set.of(ExecutorTag.AGENT));
    OperationCatalog catalog = OperationCatalog.of("core", List.of(uiOp, agentOnly));

    List<Map<String, Object>> entries = emitter.emit(catalog, List.of());

    assertEquals(1, entries.size());
    assertEquals("core.ui", entries.get(0).get("id"));
  }

  private static Operation makeOp(String id, Set<ExecutorTag> executors) {
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("test." + id), new I18nKey("test." + id + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        executors);
  }
}
