/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.PromptContributor;
import io.justsearch.agent.api.conversation.PromptFragment;
import java.util.Optional;

/**
 * {@link PromptContributor} that tells the model what <em>shape</em> the answer should take.
 *
 * <p>Per tempdoc 822 §1.2-1.3 (slice S6): {@code RAGQAStyle} is the only contributor on the ask
 * path and says nothing about output form, so the model emits unstructured prose — no headings,
 * no backticks, no fenced blocks. This contributor supplies the missing shape guidance as a
 * separate fragment rather than an edit to {@code RAGQAStyle}'s string, so that (a) the §1.5 A/B
 * is an arm switch rather than a fork of the prompt text, (b) the same guidance is reusable by
 * other tiers later without a second copy, and (c) {@code RAGQAStyleTest}'s substring assertions
 * stay meaningful.
 *
 * <p>Every clause is a <em>shape</em> rule; none asks for more content, and three of the five
 * bullets are explicit anti-inflation constraints — the failure mode a 9B model has when told
 * "use headings" is to emit a four-heading skeleton over two facts. Deliberately absent: any
 * "be thorough / detailed" wording (length inflation against a 1024-token budget), any citation
 * instruction ({@code RAGQAStyle} is the single citation authority — a second one is exactly the
 * duplicate authority §3 removes), and any tone/persona wording (the identity preamble owns it).
 *
 * <p>Stateless singleton; priority 20 — after the identity/style preamble (10), before catalog
 * descriptors (50-69) and dynamic context (80-99), per the tiering documented at
 * {@code URLEmissionGrammar}.
 *
 * <p>Registered only on {@code RAGAskShape}. Extending it to the summarize or agent tiers is a
 * one-line registration plus a re-run of the §1.5 A/B per tier, and is deliberately not done
 * (822 §6 open question 3, resolved "not yet").
 */
public final class AnswerShapeGrammar implements PromptContributor {

  /** Stable id used by {@code ConversationShape.promptContributorIds}. */
  public static final String ID = "core.answer-shape-grammar";

  /**
   * A/B arm switch (tempdoc 822 §1.5), read from the request body. Absent ⇒ arm {@code B}, the
   * shipped behaviour: the fragment is contributed. An explicit {@code false} selects arm
   * {@code A}, the control, in which this contributor adds nothing at all.
   *
   * <p>§1.5 specifies the arms as "with / without the registration, switched by rebuilding". A
   * per-request flag is the same two arms and is strictly better for the protocol §1.5 itself
   * mandates: the arms must be <em>interleaved</em> (A,B,A,B per prompt) to remove drift, which a
   * rebuild — or a JVM-lifetime system property — cannot do, because it forces blocked arms with
   * a stack restart, a re-warmed model and a possible re-index in between. Here both arms run in
   * one process against one corpus, and the registration line, the shape definition and the
   * fragment text are byte-identical between them, so an arm difference cannot be an artifact of
   * an edited source tree.
   *
   * <p>Not a system property: {@code AppServicesWorkerGuardrailsTest} bars {@code
   * System.getProperty} across {@code io.justsearch.app.services..} (allowlist-only, and the
   * allowlist is meant to shrink). No shipped caller sends this key; it is inert for every real
   * request.
   */
  public static final String ARM_SWITCH_KEY = "answerShapeGrammar";

  private static final int PRIORITY = 20;

  private static final String FRAGMENT_TEXT =
      "Write the answer in Markdown, and let the answer's real shape choose the markup.\n\n"
          + "- Plain paragraphs are the default. A question with one answer gets one paragraph:"
          + " no heading, no list, no closing summary.\n"
          + "- Use a `##` heading only when the answer genuinely has two or more distinct parts,"
          + " and then give every part one.\n"
          + "- Use a numbered or bulleted list only when the content is already a list"
          + " (steps, alternatives, enumerated findings). Do not split one thought into bullets.\n"
          + "- Put file names, paths, commands, identifiers, values and other literal strings in"
          + " backticks. Use a fenced code block for anything spanning more than one line.\n"
          + "- Do not invent sections, headings or list items to make a short answer look thorough,"
          + " and do not restate the answer at the end.";

  private static final PromptFragment FRAGMENT = new PromptFragment(FRAGMENT_TEXT, PRIORITY);

  public static final AnswerShapeGrammar INSTANCE = new AnswerShapeGrammar();

  private AnswerShapeGrammar() {}

  /**
   * Whether the shape-guidance fragment is contributed for this request. Defaults to {@code true};
   * only an explicit {@code false} (boolean or the string {@code "false"}) on
   * {@link #ARM_SWITCH_KEY} selects the control arm.
   */
  public static boolean enabled(ConversationContext ctx) {
    Object raw = ctx == null ? null : ctx.requestBody().get(ARM_SWITCH_KEY);
    if (raw instanceof Boolean flag) {
      return flag;
    }
    if (raw instanceof String s) {
      return !"false".equalsIgnoreCase(s.trim());
    }
    return true;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Optional<PromptFragment> contribute(ConversationContext ctx) {
    return enabled(ctx) ? Optional.of(FRAGMENT) : Optional.empty();
  }
}
