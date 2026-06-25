/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

/**
 * Trivial {@link IterationController} that always terminates after one LLM call.
 *
 * <p>Per tempdoc 491 §5.1: used by all {@link IterationMode#ONE_SHOT} shapes (summarize, RAG
 * ask, structured extraction, URL-emission chat). Returns {@link IterationDecision#STOP_SUCCESS}
 * on every invocation — the engine still calls {@link #next} once after the LLM call to
 * produce the {@code done} SSE event, but never iterates again.
 *
 * <p>Stateless. Singleton instance available via {@link #INSTANCE}; the id is the conventional
 * {@code "core.single-hop"}.
 */
public final class SingleHopController implements IterationController {

  /** Stable singleton id. */
  public static final String ID = "core.single-hop";

  /** Singleton instance — controller is stateless. */
  public static final SingleHopController INSTANCE = new SingleHopController();

  private SingleHopController() {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public IterationDecision next(ConversationContext ctx) {
    return IterationDecision.STOP_SUCCESS;
  }
}
