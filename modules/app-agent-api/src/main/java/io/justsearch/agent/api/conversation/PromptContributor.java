/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import io.justsearch.agent.api.registry.TrustTier;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * SPI: contributes a fragment to the assembled system prompt.
 *
 * <p>Per tempdoc 491 §5.1: one of the four substrate SPIs. Stateless. Priority-ordered. A shape's
 * manifest references {@link PromptContributor}s by id; the engine collects all referenced
 * contributors' fragments and assembles the system prompt deterministically.
 *
 * <p>The agent loop's existing {@code Supplier<String>} pattern for condition-context fragments
 * is one prior-art example. {@link PromptContributor}'s contract is designed forward — it
 * supports all four lifecycle-axis cells, not just the agent loop's needs.
 *
 * <p>Example contributors (not exhaustive): {@code IdentityPreamble}, {@code RAGQAStyle},
 * {@code URLEmissionGrammar}, {@code SummarizationStyle}, {@code DocAccessPreamble},
 * {@code ToolDescriptors} (driven by a {@code CatalogProjection}).
 *
 * <p><strong>Phase B status</strong>: the SPI interface is defined; the only implementation
 * registered in Phase B is the agent shape's prompt logic, which lives inside the agent loop's
 * existing {@code buildSystemPrompt()} method (encapsulated behind the {@code ToolIteratingShape}
 * adapter, not lifted out). Fresh implementations land in Phase C as RAG ask, summarize, and
 * URL emission shapes are added.
 */
public interface PromptContributor {

  /**
   * Stable identifier for this contributor within the substrate's contributor registry.
   * Used in {@code ConversationShape.promptContributorIds} to compose this contributor into
   * a shape's manifest.
   */
  String id();

  /**
   * Return a fragment to include in the assembled system prompt, or {@link Optional#empty}
   * if this contributor has nothing to add for the current invocation. Empty returns are
   * legitimate (a dynamic contributor may have no content under some context conditions);
   * the engine simply skips them.
   *
   * @param ctx the per-request context (read-only)
   * @return the contribution, or empty
   */
  Optional<PromptFragment> contribute(ConversationContext ctx);

  /**
   * Slice 491 §9.D Phase E (G4) — which shape trust tiers may compose this contributor.
   * Default = all tiers (CORE + TRUSTED_PLUGIN + UNTRUSTED_PLUGIN). Restrictive
   * implementations override to limit composition; e.g., a contributor that handles
   * privileged-state context-injection may return {@code EnumSet.of(TrustTier.CORE)} so
   * only CORE-tier shapes can compose it. The catalog's tier-compatibility validator
   * (in {@link io.justsearch.app.services.conversation.CoreConversationShapeCatalog})
   * enforces this at registration.
   */
  default Set<TrustTier> allowedShapeTiers() {
    return EnumSet.allOf(TrustTier.class);
  }
}
