/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.interaction;

/**
 * The plane-neutral kind of a single {@link InteractionEvent}.
 *
 * <p>Tempdoc 561 P-A/P-B (Tier-2 canonical interaction record). One vocabulary spans BOTH the answer
 * plane (chat / RAG / extract) and the agent plane (tool activity), so the unified thread renders
 * every turn from one record. Message kinds carry their text in {@link InteractionEvent#content()};
 * the non-message kinds carry their detail in {@link InteractionEvent#attributes()}.
 */
public enum InteractionEventKind {
  /** A user's turn (typed prompt) on either plane. */
  USER_MESSAGE,
  /** An assistant text turn (answer-plane response or agent-loop narration). */
  ASSISTANT_MESSAGE,
  /** A tool call's lifecycle on the agent plane (proposed / pending / executed / rejected). */
  TOOL_ACTIVITY,
  /** A progress note from the agent loop. */
  PROGRESS,
  /** An error surfaced on either plane. */
  ERROR,
  /** A multi-agent handoff on the agent plane. */
  HANDOFF
}
