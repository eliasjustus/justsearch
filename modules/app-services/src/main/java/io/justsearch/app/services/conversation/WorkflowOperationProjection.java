/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.Workflow;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowNode;
import io.justsearch.agent.api.registry.WorkflowRef;
import io.justsearch.app.services.registry.preview.CapabilityAvailability;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 560 WS5 (the one window) — projects each declared {@link Workflow} onto an EXECUTABLE
 * {@link Operation} so the model sees workflows in the SAME tool list as core operations and MCP
 * tools. The projected op is AGENT-only ({@code executors = {AGENT}}, {@code audience = AGENT}) — it
 * never reaches the UI registry path — and the agent loop routes its invocation through the streaming
 * {@link io.justsearch.agent.api.registry.WorkflowToolRunner} rather than a synchronous handler.
 *
 * <p>The op ref is the workflow ref re-prefixed {@code core.<name>} → {@code core.workflow-<name>}, a
 * bijection ({@link #opRefFor} / {@link #workflowRefFor}) that (a) keeps the projected op id distinct
 * from any operation id and (b) lets the one-window inventory classify the tool's {@code kind} from
 * the prefix alone. Today only {@code core.*} workflows are projected (the only authored namespace).
 *
 * <p>Tempdoc 876 B.4 — <b>a projected operation inherits the availability of what it composes</b>.
 * The projection is resolved against the composed registry: a workflow whose {@code ToolStep} refs
 * all resolve is offered with the CONJUNCTION of those operations' availability expressions; a
 * workflow composing an operation that is not in the registry at all is <b>not projected</b>, because
 * an absence is not expressible as an availability expression and "offered but unrunnable" is the
 * defect this closes ({@code core.demo-compose} composes the OPTIONAL {@code vendor.mcphost.*}
 * reference server, and was offered to every model on every install). The workflow itself is
 * untouched — it stays a first-class {@link WorkflowCatalog} entry a human can run from the picker;
 * only the AGENT-tool projection narrows.
 */
public final class WorkflowOperationProjection {

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowOperationProjection.class);

  /** The op-ref prefix that marks a projected workflow (consumed by the inventory's kind classifier). */
  public static final String OP_PREFIX = "core.workflow-";

  private static final String CORE_NS = "core.";

  private WorkflowOperationProjection() {}

  /** The projected operation ref for a workflow: {@code core.<name>} → {@code core.workflow-<name>}. */
  public static OperationRef opRefFor(WorkflowRef workflow) {
    String value = workflow.value();
    String name = value.startsWith(CORE_NS) ? value.substring(CORE_NS.length()) : value;
    return new OperationRef(OP_PREFIX + name);
  }

  /**
   * The workflow ref a projected operation ref maps back to, or empty when {@code op} is not a
   * projected workflow ({@code core.workflow-<name>} → {@code core.<name>}).
   */
  public static Optional<WorkflowRef> workflowRefFor(OperationRef op) {
    String value = op.value();
    if (!value.startsWith(OP_PREFIX)) {
      return Optional.empty();
    }
    return Optional.of(new WorkflowRef(CORE_NS + value.substring(OP_PREFIX.length())));
  }

  /**
   * Project every workflow in {@code catalog} onto an agent-facing Operation, resolved against
   * {@code knownOperations} — the operations that actually exist in the composed registry at
   * projection time. A workflow composing an unresolvable operation is SKIPPED (tempdoc 876 B.4).
   *
   * <p>The known-operations argument is required by construction: there is deliberately no overload
   * that projects without a resolver, because that overload IS the defect (every workflow declared
   * unconditionally available regardless of whether the thing it runs exists).
   */
  public static List<Operation> project(WorkflowCatalog catalog, Collection<Operation> knownOperations) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(knownOperations, "knownOperations");
    Map<OperationRef, Operation> index = new LinkedHashMap<>();
    for (Operation op : knownOperations) {
      index.put(op.id(), op);
    }
    List<Operation> projected = new ArrayList<>();
    for (Workflow workflow : catalog.definitions()) {
      toOperation(workflow, ref -> Optional.ofNullable(index.get(ref))).ifPresent(projected::add);
    }
    return List.copyOf(projected);
  }

  /**
   * Project one workflow onto its agent-tool Operation, or {@link Optional#empty()} when the workflow
   * composes an operation {@code resolver} cannot resolve.
   *
   * <p>Only {@link WorkflowNode.ToolStep} composes an Operation: an {@code LlmStep} delegates to a
   * ConversationShape and a {@code GateStep} to a ConfirmStrategy, so neither imposes an operation
   * dependency. The projected op's availability is the conjunction of the resolved operations'
   * availability expressions — one distinct expression is carried through as-is, several become an
   * {@link AvailabilityExpression.AllOf}, and none (nothing composed declares state-dependent gating)
   * leaves {@link OperationAvailability#empty()}, the pre-876 behaviour for the simple case.
   *
   * <p>A composed op that declares no expression but DOES declare {@link
   * io.justsearch.agent.api.registry.RequiredCapability}s contributes its capability-derived
   * expression, resolved here through the same {@link CapabilityAvailability#derive} the composed
   * catalog's own derivation pass uses. Deriving at this seam rather than relying on that later pass
   * is deliberate: {@code withCapabilityDerivedAvailability} fills only ops with NO expression, so a
   * projection that already carries one composed op's explicit expression would never receive
   * another's capability gate — and the workflow would be offered while the operation it runs is
   * capability-blocked, which is the very defect §B.4 exists to close, one level down.
   */
  public static Optional<Operation> toOperation(
      Workflow workflow, Function<OperationRef, Optional<Operation>> resolver) {
    Objects.requireNonNull(workflow, "workflow");
    Objects.requireNonNull(resolver, "resolver");
    Set<AvailabilityExpression> composed = new LinkedHashSet<>();
    for (WorkflowNode node : workflow.nodes()) {
      if (!(node instanceof WorkflowNode.ToolStep step)) {
        continue;
      }
      Optional<Operation> target = resolver.apply(step.operation());
      if (target.isEmpty()) {
        LOG.info(
            "Workflow {} is NOT projected as an agent tool: node '{}' composes operation {},"
                + " which is not present in the composed registry (tempdoc 876 B.4)",
            workflow.id().value(),
            step.nodeId(),
            step.operation().value());
        return Optional.empty();
      }
      Operation composedOp = target.get();
      composedOp
          .availability()
          .expression()
          .or(() -> CapabilityAvailability.derive(composedOp.policy().requiredCapabilities()))
          .ifPresent(composed::add);
    }
    OperationRef ref = opRefFor(workflow.id());
    return Optional.of(
        new Operation(
            ref,
            workflow.presentation(),
            // Workflows take no model-supplied arguments today (each is a fixed node sequence); the
            // streaming runner sets the workflow body itself. An empty object schema keeps the tool
            // callable with `{}`.
            Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(),
                false),
            // Tempdoc 876 B.4: inherited from what the workflow composes, not hardcoded empty.
            composedAvailability(composed),
            OperationLineage.empty(),
            Binding.of(ref),
            workflow.provenance(),
            Set.of(ExecutorTag.AGENT),
            Audience.AGENT,
            // The workflow's declared consumers carry over (NonEmpty by Workflow's constructor), so
            // the projected op satisfies the consumer-presence gate without a synthetic hook.
            workflow.consumers()));
  }

  /**
   * The conjunction of the composed operations' availability expressions. Argument defaults are NOT
   * inherited: a projected workflow takes no model-supplied arguments (the runner sets the workflow
   * body itself), so a referenced op's defaults have nothing to apply to.
   */
  private static OperationAvailability composedAvailability(Set<AvailabilityExpression> composed) {
    if (composed.isEmpty()) {
      return OperationAvailability.empty();
    }
    AvailabilityExpression expression =
        composed.size() == 1
            ? composed.iterator().next()
            : new AvailabilityExpression.AllOf(List.copyOf(composed));
    return new OperationAvailability(Optional.of(expression), Optional.empty());
  }
}
