/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import java.util.Optional;

/**
 * {@link PromptContributor} for the RAG-ask shape.
 *
 * <p>Per tempdoc 491 §C3: lifts the hardcoded ask-system prompt that used to live inside
 * {@code RagStreamingHandler.handleAskStream} as inline message construction. Now a
 * substrate-composed contributor.
 *
 * <p>Stateless singleton; priority 10 (identity/style preambles lead the prompt).
 */
public final class RAGQAStyle implements PromptContributor {

  /** Stable id used by {@code ConversationShape.promptContributorIds}. */
  public static final String ID = "core.rag-qa-style";

  public static final RAGQAStyle INSTANCE = new RAGQAStyle();

  private static final PromptFragment FRAGMENT =
      new PromptFragment(
          "You are a helpful assistant that answers questions based on provided documents. "
              + "Only answer based on the document content. If the answer is not in the "
              + "documents, say so. Cite sources inline with a bracketed number like [1], [2] "
              + "at the end of the sentence they support; do NOT append a separate Citations, "
              + "Sources, or References list at the end — the interface displays the sources.",
          10);

  private RAGQAStyle() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Optional<PromptFragment> contribute(ConversationContext ctx) {
    return Optional.of(FRAGMENT);
  }
}
