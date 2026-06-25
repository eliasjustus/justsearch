/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
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
import io.justsearch.agent.api.registry.WorkflowRef;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 */
public final class WorkflowOperationProjection {

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

  /** Project every workflow in {@code catalog} onto an agent-facing Operation. */
  public static List<Operation> project(WorkflowCatalog catalog) {
    return catalog.definitions().stream().map(WorkflowOperationProjection::toOperation).toList();
  }

  /** Project one workflow onto its agent-tool Operation. */
  public static Operation toOperation(Workflow workflow) {
    OperationRef ref = opRefFor(workflow.id());
    return new Operation(
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
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        workflow.provenance(),
        Set.of(ExecutorTag.AGENT),
        Audience.AGENT,
        // The workflow's declared consumers carry over (NonEmpty by Workflow's constructor), so the
        // projected op satisfies the consumer-presence gate without a synthetic hook.
        workflow.consumers());
  }
}
