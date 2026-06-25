/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Set;

/**
 * The set of primitive contributions a {@link Plugin} bundles — the axis projections it adds
 * (tempdoc 560 §4.4): EXECUTABLE operations, OBSERVABLE resources, LANGUAGE_MEDIATED prompts,
 * PRESENTATION surfaces, OBSERVABLE-stream {@code diagnosticChannels}, and {@code conversationShapes}.
 * A {@code Plugin} <em>references</em> these by id; the primitives themselves remain ordinary catalog
 * declarations (each carrying its own provenance/trust/consumers). This is the "core-as-contribution"
 * shape (521): there is no separate plugin primitive type — a plugin is just a manifest over the
 * existing axes.
 *
 * <p>Tempdoc 560 §10.4 (declaration completeness): the bundle now spans all six declarable kinds, not
 * four — a plugin can contribute DiagnosticChannels and ConversationShapes (which a {@link Surface}
 * may also <em>consume</em>), closing the "a plugin can consume but not contribute these" asymmetry.
 *
 * <p>Truth boundary (§4.5): a plugin may contribute operations and feed connectors/surfaces; it
 * never forks the index or an existing core primitive. Enforced structurally elsewhere; this record
 * is the declared bundle.
 */
public record PluginContributions(
    Set<OperationRef> operations,
    Set<ResourceRef> resources,
    Set<PromptRef> prompts,
    Set<SurfaceRef> surfaces,
    Set<DiagnosticChannelRef> diagnosticChannels,
    Set<ConversationShapeRef> conversationShapes) {

  public PluginContributions {
    operations = operations == null ? Set.of() : Set.copyOf(operations);
    resources = resources == null ? Set.of() : Set.copyOf(resources);
    prompts = prompts == null ? Set.of() : Set.copyOf(prompts);
    surfaces = surfaces == null ? Set.of() : Set.copyOf(surfaces);
    diagnosticChannels = diagnosticChannels == null ? Set.of() : Set.copyOf(diagnosticChannels);
    conversationShapes = conversationShapes == null ? Set.of() : Set.copyOf(conversationShapes);
  }

  public static PluginContributions empty() {
    return new PluginContributions(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
  }

  /** A plugin that contributes only EXECUTABLE operations (the MCP-host shape). */
  public static PluginContributions ofOperations(Set<OperationRef> operations) {
    return new PluginContributions(operations, Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
  }

  /** A plugin that contributes only OBSERVABLE-stream DiagnosticChannels (the example-channel shape). */
  public static PluginContributions ofDiagnosticChannels(Set<DiagnosticChannelRef> diagnosticChannels) {
    return new PluginContributions(
        Set.of(), Set.of(), Set.of(), Set.of(), diagnosticChannels, Set.of());
  }

  /** A plugin that contributes only ConversationShapes. */
  public static PluginContributions ofConversationShapes(Set<ConversationShapeRef> conversationShapes) {
    return new PluginContributions(
        Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), conversationShapes);
  }

  /** Total number of contributed primitives across all axes. */
  public int size() {
    return operations.size()
        + resources.size()
        + prompts.size()
        + surfaces.size()
        + diagnosticChannels.size()
        + conversationShapes.size();
  }

  public boolean isEmpty() {
    return size() == 0;
  }
}
