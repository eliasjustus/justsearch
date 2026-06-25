/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * SPI: pre-pends side-data (retrieved documents, doc-loading payloads) into the LLM's working
 * message list before the model call.
 *
 * <p>Per tempdoc 491 §5.1 + §C2.0 (substrate enhancement E2): performs IO (RAG retrieval,
 * doc-loading) and returns an {@link InjectorResult} carrying (a) messages to inject, (b)
 * SSE events to emit during injection (e.g., {@code rag.meta}, {@code progress}), and (c) an
 * optional terminal error that aborts the conversation before the LLM call (used for
 * missing-required-field cases like absent {@code question} / {@code docIds}).
 *
 * <p>Example injectors: {@code RAGContext(query, topK)} runs retrieval over a docId set,
 * returns the top-K chunks; {@code DocAccess(docIds)} loads doc text by id, returns it as a
 * single user message.
 */
public interface ContextInjector {

  /**
   * Stable identifier within the substrate's injector registry. Used in
   * {@code ConversationShape.contextInjectorIds} to compose this injector into a shape's
   * manifest.
   */
  String id();

  /**
   * Perform injection. Returns the messages to prepend to the model's working message list,
   * any SSE events to emit during injection, and an optional terminal error.
   *
   * <p>Messages use the OpenAI shape (a {@code Map} with at minimum {@code "role"} and
   * {@code "content"} keys). Multiple {@link ContextInjector}s on a single shape compose by
   * declaration order in the shape manifest; injected messages from each are concatenated in
   * that order before the user message.
   *
   * @param ctx the per-request context
   * @return injection result — messages, events, optional terminal error
   */
  InjectorResult inject(ConversationContext ctx);

  /**
   * Slice 491 §9.D Phase E (G4) — which shape trust tiers may compose this injector.
   * Default = all tiers. Restrictive implementations override (e.g., an injector that
   * loads privileged data may return {@code EnumSet.of(TrustTier.CORE)} so plugin
   * shapes can't read it).
   */
  default java.util.Set<io.justsearch.agent.api.registry.TrustTier> allowedShapeTiers() {
    return java.util.EnumSet.allOf(io.justsearch.agent.api.registry.TrustTier.class);
  }
}
