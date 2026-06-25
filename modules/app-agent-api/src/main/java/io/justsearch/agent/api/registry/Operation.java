/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Invocable operation primitive.
 *
 * <p>Per tempdoc 429 §6 (Operation = MCP Tool): a named, typed, callable thing exposed
 * to one or more invocation surfaces (UI, AGENT, CLI). Replaces the legacy
 * {@code ToolDefinition} SPI and the never-built {@code AdminAction} concept; one
 * declaration drives multiple emitters per §E.8 (UI JSON, OpenAI function-calling JSON).
 *
 * <p>Per slice 447 §X.3.1 + §X.11.5 follow-up Phase 3: the formerly-monolithic
 * "OperationPolicy" axes have been partitioned along consumer-model lines:
 *
 * <ul>
 *   <li>{@link OperationPolicy} — invocation-time axes (risk, confirm, audit, retry,
 *       rateLimit, capabilities, undo). Read by the executor.
 *   <li>{@link OperationAvailability} — discovery-time axes (when is this Operation
 *       suggested? what default args ride along?). Read by the discovery layer.
 *   <li>{@link OperationLineage} — post-execution axes (which Resources are affected,
 *       which prior Operation does this supersede). Read by history surfaces + lineage
 *       analysis.
 * </ul>
 *
 * <p>The partition aligns with {@code 10-kernel/04-shape-governance.md} §"Entry Kind
 * Before Primitive" applied at field-grouping level. {@link OperationAvailability}
 * and {@link OperationLineage} default to {@link OperationAvailability#empty()} /
 * {@link OperationLineage#empty()} during the initial partition rollout per §X.11.2;
 * non-empty values land per declaration when consumer slices ship.
 *
 * <p>Per §B.G: no {@code @RecordBuilder}; the codebase convention for &lt;12-field
 * records is canonical constructors.
 */
public record Operation(
    OperationRef id,
    Presentation presentation,
    Interface intf,
    OperationPolicy policy,
    OperationAvailability availability,
    OperationLineage lineage,
    Binding binding,
    Provenance provenance,
    Set<ExecutorTag> executors,
    Audience audience,
    List<ConsumerHook> consumers) implements RegistryEntry, ConsumerDeclaring {

  public Operation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(intf, "intf");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(availability, "availability");
    Objects.requireNonNull(lineage, "lineage");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(audience, "audience");
    executors = executors == null ? Set.of() : Set.copyOf(executors);
    consumers = consumers == null ? List.of() : List.copyOf(consumers);
  }

  /**
   * Backward-compat constructor for callsites that don't yet declare audience or
   * consumer hooks.
   *
   * <p>Per slice 481 §7 step 2 (audience kernel-lift, 2026-05-08): audience defaults
   * to {@link Audience#USER}. Per §7 step 3 (ConsumerHook substrate, 2026-05-08):
   * consumers defaults to empty list — the structural enforcement
   * ({@code NonEmpty<ConsumerHook>}) is deferred to a Pass-3 design slice that
   * resolves the runtime-witness mechanism + SliceCatalog referential integrity.
   *
   * <p>This constructor is intentionally non-deprecated: the conversion is additive.
   */
  public Operation(
      OperationRef id,
      Presentation presentation,
      Interface intf,
      OperationPolicy policy,
      OperationAvailability availability,
      OperationLineage lineage,
      Binding binding,
      Provenance provenance,
      Set<ExecutorTag> executors) {
    this(id, presentation, intf, policy, availability, lineage,
        binding, provenance, executors, Audience.USER, List.of());
  }

  /** Backward-compat constructor for callsites declaring audience but not consumers. */
  public Operation(
      OperationRef id,
      Presentation presentation,
      Interface intf,
      OperationPolicy policy,
      OperationAvailability availability,
      OperationLineage lineage,
      Binding binding,
      Provenance provenance,
      Set<ExecutorTag> executors,
      Audience audience) {
    this(id, presentation, intf, policy, availability, lineage,
        binding, provenance, executors, audience, List.of());
  }

  /**
   * Return a copy of this operation with a different {@link OperationAvailability}, all other
   * fields preserved. Additive helper (tempdoc 550 E3): lets the substrate derive an op's
   * availability from its declared {@link RequiredCapability} set at catalog-assembly time
   * without mutating the original record.
   */
  public Operation withAvailability(OperationAvailability newAvailability) {
    return new Operation(
        id, presentation, intf, policy, newAvailability, lineage,
        binding, provenance, executors, audience, consumers);
  }
}
