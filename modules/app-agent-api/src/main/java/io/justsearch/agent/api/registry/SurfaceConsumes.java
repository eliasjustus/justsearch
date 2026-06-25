/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;
import java.util.Set;

/**
 * Typed cross-reference graph from a {@link Surface} to the primitives it
 * consumes.
 *
 * <p>Per slice 449 §4: a Surface composes existing primitives (Resource,
 * Operation, Prompt, DiagnosticChannel). The fields here are the cross-
 * reference axis V1.5's slice {@code 3a-1-8c-cross-reference-enforcement.md}
 * extends to (per §6.5).
 *
 * <p>Per slice 447-impl-A: each cross-reference uses the primitive's own
 * typed id — {@link ResourceRef} for Resource refs, {@link OperationRef} for
 * Operation refs, {@link PromptRef} for Prompt refs,
 * {@link DiagnosticChannelRef} for DiagnosticChannel refs. This closes the
 * §A.1 sub-defect where Resource and Prompt ids were misused as
 * {@link OperationRef}.
 */
public record SurfaceConsumes(
    Set<ResourceRef> resources,
    Set<OperationRef> operations,
    Set<PromptRef> prompts,
    Set<DiagnosticChannelRef> diagnosticChannels,
    Set<ConversationShapeRef> conversationShapes) {

  public SurfaceConsumes {
    resources = resources == null ? Set.of() : Set.copyOf(resources);
    operations = operations == null ? Set.of() : Set.copyOf(operations);
    prompts = prompts == null ? Set.of() : Set.copyOf(prompts);
    diagnosticChannels =
        diagnosticChannels == null ? Set.of() : Set.copyOf(diagnosticChannels);
    // Slice 491 §9.D Phase E — Surfaces that host a ConversationShape (chat surfaces)
    // declare it here. The FE <jf-chat-shape-mount> reads this field to resolve which
    // shape's view factory to mount. Audience-completeness gate (C5) cross-references
    // this set against ConversationShape.projections() to detect orphans.
    conversationShapes =
        conversationShapes == null ? Set.of() : Set.copyOf(conversationShapes);
  }

  /** Legacy-style constructor preserved for back-compat with pre-Phase-E surface entries. */
  public SurfaceConsumes(
      Set<ResourceRef> resources,
      Set<OperationRef> operations,
      Set<PromptRef> prompts,
      Set<DiagnosticChannelRef> diagnosticChannels) {
    this(resources, operations, prompts, diagnosticChannels, Set.of());
  }

  /** Convenience factory for a Surface consuming nothing (rare; mostly tests). */
  public static SurfaceConsumes empty() {
    return new SurfaceConsumes(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
  }

  /** Convenience: total reference count. Useful for "consumes anything?" checks. */
  public int totalCount() {
    return resources.size()
        + operations.size()
        + prompts.size()
        + diagnosticChannels.size()
        + conversationShapes.size();
  }

  /**
   * Returns true if any of the consumed primitives carry a non-CORE
   * consumer-permission signal (i.e., a DiagnosticChannel with
   * TRUSTED_PLUGIN or OPERATOR_OVERRIDE). Resource.Privacy is orthogonal
   * and does NOT contribute (per slice 449 §0 D2).
   *
   * <p>The validator uses this to compute {@code audienceFloorFromConsumedChannels}
   * via cross-reference into the actual DiagnosticChannel catalog.
   * {@link SurfaceConsumes} itself only knows the ids; the validator does
   * the lookup against the catalog.
   */
  public boolean hasDiagnosticChannelReferences() {
    return !diagnosticChannels.isEmpty();
  }
}
