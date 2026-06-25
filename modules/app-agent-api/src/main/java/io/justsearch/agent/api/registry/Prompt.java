/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Structured prompt template primitive.
 *
 * <p>Per tempdoc 429 §6 (Prompt = MCP Prompt): formalizes what
 * {@code /infra/capabilities.prompt_templates} already does — structured prompt templates
 * with explicit required-variable lists. V1 ships the type and an empty catalog; concrete
 * Prompt entries land in a future slice.
 *
 * <p>{@code templateRef} is a path/URL to the template content
 * (e.g., {@code "SSOT/prompts/en/intent.v1.json"}). {@code requiredVariables} enumerates
 * the variable names callers must provide; the existing Mustache template loader resolves
 * them at invocation time.
 */
public record Prompt(
    PromptRef id,
    Presentation presentation,
    String templateRef,
    List<String> requiredVariables,
    Provenance provenance,
    Audience audience,
    List<ConsumerHook> consumers) implements RegistryEntry, ConsumerDeclaring {

  public Prompt {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(templateRef, "templateRef");
    requiredVariables = requiredVariables == null ? List.of() : List.copyOf(requiredVariables);
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(audience, "audience");
    consumers = consumers == null ? List.of() : List.copyOf(consumers);
    // §5 keystone — NonEmpty<ConsumerHook> unrepresentable (rung 2): Prompt is inline-only, so a
    // zero-consumer Prompt cannot be constructed. The consumer-defaulting backward-compat ctors were
    // removed — they could only have produced an unrepresentable instance (tempdoc 560 §5).
    if (consumers.isEmpty()) {
      throw new IllegalArgumentException(
          "Prompt "
              + id.value()
              + " must declare >=1 consumer — a zero-consumer declaration is unrepresentable"
              + " (tempdoc 560 §5 NonEmpty<ConsumerHook> keystone).");
    }
  }
}
