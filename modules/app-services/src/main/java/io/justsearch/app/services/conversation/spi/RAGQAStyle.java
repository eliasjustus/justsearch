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

  /**
   * Tempdoc 845 — the excerpts are named as what they are: passages retrieved from the user's own
   * indexed files.
   *
   * <p>The previous wording ("answers questions based on provided documents … if the answer is not
   * in the documents, say so") never said whose documents these were or where they came from. With
   * thinking enabled, a reasoning pass filled that gap wrongly — a probe caught the verbatim chain
   * of thought "I don't have access to actual indexed files… only system documentation" — and the
   * model prepended a denial of file access to an answer it was in the middle of grounding in those
   * very files. Non-thinking never deliberates, so the same prompt behaved correctly there; that
   * asymmetry is the tell.
   *
   * <p>The say-so clause is also scoped to answer CONTENT rather than access: not finding an answer
   * in the excerpts is a fact about coverage, not a licence to claim the files are unreachable.
   * Deliberately not over-claiming in the other direction either — the excerpts are a top-K
   * retrieval and may be trimmed to fit the context window (tempdoc 845 defect 1), so the prompt
   * does not assert they are the complete corpus.
   */
  private static final PromptFragment FRAGMENT =
      new PromptFragment(
          "You are a helpful assistant that answers questions about the user's own files. "
              + "The excerpts below are passages retrieved from those files, which you can read; "
              + "they are the user's real indexed documents, not examples or system "
              + "documentation. Answer only from the excerpt content. They are the most relevant "
              + "passages found, not necessarily every file the user has, so if they do not "
              + "contain the answer, say the files provided do not appear to cover it — do not "
              + "say you lack access to the user's files. Cite sources inline with a bracketed "
              + "number like [1], [2] at the end of the sentence they support; do NOT append a "
              + "separate Citations, Sources, or References list at the end — the interface "
              + "displays the sources.",
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
