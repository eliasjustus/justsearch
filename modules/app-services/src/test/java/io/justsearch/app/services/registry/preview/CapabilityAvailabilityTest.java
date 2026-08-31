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
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
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
  void emptyCapabilitySetDerivesNothing() {
    // Tempdoc 737 §8a/§12d: RequiredCapability.GpuAvailable was dead vocabulary (required by no
    // operation, resolver arm hardcoded true) and has been removed along with IndexedRoot — this
    // test now covers the one remaining "derives nothing" case: an empty capability set.
    assertTrue(CapabilityAvailability.derive(Set.of()).isEmpty());
  }

  @Test
  void deCircularizedLifecycleOpsDeriveEmptyAvailability() {
    // Tempdoc 737 §8a/§12b: switch-inference-mode, activate-runtime-variant, and
    // deactivate-runtime-variant used to require InferenceOnline — a postcondition their own
    // success establishes — which meant they were hidden from the agent surface (via
    // capability-derived availability) exactly when needed to recover. They now declare no
    // capabilities at all, so derivation yields no availability expression and the ops are never
    // hidden. This supersedes the fossil coverage this file previously carried for the circular
    // InferenceOnline requirement on these ops.
    CoreOperationCatalog catalog = new CoreOperationCatalog();
    for (var id :
        List.of(
            CoreOperationCatalog.SWITCH_INFERENCE_MODE,
            CoreOperationCatalog.ACTIVATE_RUNTIME_VARIANT,
            CoreOperationCatalog.DEACTIVATE_RUNTIME_VARIANT)) {
      Operation op = catalog.findById(id).orElseThrow();
      assertTrue(
          op.policy().requiredCapabilities().isEmpty(),
          id.value() + " must declare no RequiredCapability (tempdoc 737 §12b)");
      assertTrue(
          CapabilityAvailability.derive(op.policy().requiredCapabilities()).isEmpty(),
          id.value() + " must derive no availability expression");
    }
    // trigger-offline-processing keeps WorkerOnline (it is not established by this op) — its
    // derivation still yields a condition, distinguishing "de-circularized" from "unconditional".
    Operation triggerOffline =
        catalog.findById(CoreOperationCatalog.TRIGGER_OFFLINE_PROCESSING).orElseThrow();
    assertEquals(
        Set.of(RequiredCapability.WorkerOnline.INSTANCE),
        triggerOffline.policy().requiredCapabilities());
    assertTrue(
        CapabilityAvailability.derive(triggerOffline.policy().requiredCapabilities()).isPresent());
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
