package io.justsearch.app.services.registry.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tempdoc 550 E3 — availability derived from RequiredCapability. */
final class CapabilityAvailabilityTest {

  @Test
  void workerOnlineDerivesNotWorkerCapability() {
    AvailabilityExpression expr =
        CapabilityAvailability.derive(Set.of(RequiredCapability.WorkerOnline.INSTANCE)).orElseThrow();
    var not = assertInstanceOf(AvailabilityExpression.Not.class, expr);
    var cm = assertInstanceOf(AvailabilityExpression.ConditionMatches.class, not.child());
    assertEquals("worker.capability", cm.conditionId());
  }

  @Test
  void inferenceOnlineDerivesNotInferenceCapability() {
    AvailabilityExpression expr =
        CapabilityAvailability.derive(Set.of(RequiredCapability.InferenceOnline.INSTANCE))
            .orElseThrow();
    var cm =
        assertInstanceOf(
            AvailabilityExpression.ConditionMatches.class,
            assertInstanceOf(AvailabilityExpression.Not.class, expr).child());
    assertEquals("inference.capability", cm.conditionId());
  }

  @Test
  void multipleCapabilitiesDeriveAllOf() {
    AvailabilityExpression expr =
        CapabilityAvailability.derive(
                Set.of(
                    RequiredCapability.WorkerOnline.INSTANCE,
                    RequiredCapability.InferenceOnline.INSTANCE))
            .orElseThrow();
    var allOf = assertInstanceOf(AvailabilityExpression.AllOf.class, expr);
    assertEquals(2, allOf.children().size(), "one Not(...) per distinct capability condition");
  }

  @Test
  void gpuAndEmptyDeriveNothing() {
    assertTrue(
        CapabilityAvailability.derive(Set.of(new RequiredCapability.GpuAvailable("nvidia")))
            .isEmpty(),
        "GPU has no published condition → no availability hint");
    assertTrue(CapabilityAvailability.derive(Set.of()).isEmpty());
  }

  @Test
  void catalogTransformFillsCapabilityOpsButPreservesExplicitAvailability() {
    Operation workerOp = op("core.needs-worker", Set.of(RequiredCapability.WorkerOnline.INSTANCE), null);
    Operation noCapOp = op("core.no-cap", Set.of(), null);
    // An op with a hand-authored availability (explicit wins — must be preserved verbatim).
    AvailabilityExpression explicit =
        new AvailabilityExpression.Not(new AvailabilityExpression.ConditionMatches("index.unavailable"));
    Operation explicitOp = op("core.explicit", Set.of(RequiredCapability.WorkerOnline.INSTANCE), explicit);

    OperationCatalog out =
        CapabilityAvailability.withCapabilityDerivedAvailability(
            OperationCatalog.of("core", List.of(workerOp, noCapOp, explicitOp)));

    // workerOp gained a derived ¬worker.capability.
    AvailabilityExpression derived =
        find(out, "core.needs-worker").availability().expression().orElseThrow();
    assertEquals(
        "worker.capability",
        ((AvailabilityExpression.ConditionMatches)
                ((AvailabilityExpression.Not) derived).child())
            .conditionId());
    // noCapOp stays empty (no capability → nothing derived).
    assertTrue(find(out, "core.no-cap").availability().expression().isEmpty());
    // explicitOp keeps its hand-authored index.unavailable gate (explicit wins).
    assertEquals(
        "index.unavailable",
        ((AvailabilityExpression.ConditionMatches)
                ((AvailabilityExpression.Not)
                        find(out, "core.explicit").availability().expression().orElseThrow())
                    .child())
            .conditionId());
  }

  private static Operation find(OperationCatalog catalog, String id) {
    return catalog.findByIdValue(id).orElseThrow();
  }

  private static Operation op(
      String id, Set<RequiredCapability> caps, AvailabilityExpression explicit) {
    OperationAvailability availability =
        explicit == null
            ? OperationAvailability.empty()
            : new OperationAvailability(Optional.of(explicit), Optional.empty());
    return new Operation(
        new OperationRef(id),
        Presentation.of(new I18nKey("test." + id), new I18nKey(id + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            caps,
            false),
        availability,
        OperationLineage.empty(),
        Binding.of(new OperationRef(id)),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT),
        Audience.USER);
  }
}
