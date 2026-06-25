/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import java.util.Optional;

/**
 * {@link PromptContributor} that contributes the summarization style preamble.
 *
 * <p>Per tempdoc 491 §5.1: lifted from the prompt logic the agent loop never carried and
 * that the legacy {@code OnlineAiServiceImpl.streamSummary} buried inside its
 * {@code InferenceLifecycleManager} delegation. Each new {@code SummarizeShape} variant
 * (single-doc, batch, hierarchical) references this contributor by id so the style is
 * consistent across all summarization shapes.
 *
 * <p>Stateless singleton with priority 10 (identity / style preambles by convention land at
 * the top of the assembled system prompt — see {@link PromptFragment} javadoc).
 */
public final class SummarizationStyle implements PromptContributor {

  /** Stable id used by {@code ConversationShape.promptContributorIds}. */
  public static final String ID = "core.summarization-style";

  /** Singleton instance — contributor is stateless. */
  public static final SummarizationStyle INSTANCE = new SummarizationStyle();

  private static final PromptFragment FRAGMENT =
      new PromptFragment(
          "You are a helpful assistant that summarizes documents concisely. "
              + "Focus on key information: dates, amounts, parties, and main purpose. "
              + "ONLY summarize what is explicitly stated in the provided text. "
              + "Do not add information from outside knowledge.",
          10);

  private SummarizationStyle() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Optional<PromptFragment> contribute(ConversationContext ctx) {
    return Optional.of(FRAGMENT);
  }
}
