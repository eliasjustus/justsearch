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
 * path and says nothing about output form. (§1.1's stronger claim — that the model therefore emits
 * "no headings, no backticks" — was falsified by the cycle-0 A/B: the unguided baseline emits
 * backticks in 18 of 24 runs and lists in 12. What it reliably does NOT do is head a multi-part
 * answer.) This contributor supplies the missing shape guidance as a
 * separate fragment rather than an edit to {@code RAGQAStyle}'s string, so that (a) the §1.5 A/B
 * is an arm switch rather than a fork of the prompt text, (b) the same guidance is reusable by
 * other tiers later without a second copy, and (c) {@code RAGQAStyleTest}'s substring assertions
 * stay meaningful.
 *
 * <p>Every clause is a <em>shape</em> rule; none asks for more content, and the anti-inflation
 * constraints are all gathered in the single-fact clause — the failure mode a 9B model has when
 * told "use headings" is to emit a four-heading skeleton over two facts. Deliberately absent: any
 * "be thorough / detailed" wording (length inflation against a 1024-token budget), any citation
 * instruction ({@code RAGQAStyle} is the single citation authority — a second one is exactly the
 * duplicate authority §3 removes), and any tone/persona wording (the identity preamble owns it).
 *
 * <p><strong>Provisional until the §1.5 gate passes.</strong> The cycle-0 run failed two of the
 * four acceptance criteria, so the fragment is OFF by default and reaches the model only when a
 * request opts in ({@link #ARM_SWITCH_KEY}). The shipped ask window sends no flag and therefore
 * still gets the cycle-0 shipped prompt.
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
   * A/B arm switch (tempdoc 822 §1.5), read from the request body. Absent ⇒ arm {@code A}, the
   * control: this contributor adds nothing at all. An explicit {@code true} selects arm {@code B}
   * and contributes the fragment.
   *
   * <p>Opt-IN, not opt-out, because the cycle-0 A/B failed criteria 1 and 3 — §1.5 makes the
   * fragment provisional until all four hold, so the default path must be the measured-shipped
   * behaviour and the candidate wording must be asked for explicitly. Flipping this default is
   * the act of shipping the grammar, and it is gated on a passing A/B, not on a code review.
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

  /**
   * CYCLE 1 of the §1.5 wording loop (max 3). Cycle 0 was the design's §1.2 text; it failed
   * criteria 1 and 3 over 48 runs, and the measured cause was structural, not stylistic: the
   * fragment LED with "Plain paragraphs are the default", and the 9B model applied that first
   * clause to every answer — arm B's headings did not rise (2/12 multi-part, identical to the
   * control) while its length fell in 18 of 24 twins and its list lines fell in 11 twins and rose
   * in none. Cycle 0 also assumed a baseline that emits no markup; it does not (the control
   * emitted backticks in 18 of 24 runs), so "put … in backticks" was a no-op against a baseline
   * already doing it.
   *
   * <p>Cycle 1 therefore reorders rather than rewrites: the MULTI-PART case leads, the
   * plain-paragraph default is scoped to the SINGLE-FACT case (which now carries all three
   * anti-inflation protections, so criterion 2's 0/12 result is not put at risk), and the backtick
   * clause is strengthened from permission to obligation to move criterion 1's second half.
   * Citation wording is untouched — {@code RAGQAStyle} remains the single authority.
   */
  private static final String FRAGMENT_TEXT =
      "Write the answer in Markdown, and let the answer's real shape choose the markup.\n\n"
          + "- When the answer has two or more distinct parts, introduce each part with a short"
          + " `##` heading, and give every part one.\n"
          + "- Use a numbered or bulleted list when the content is already a list (steps,"
          + " alternatives, enumerated findings).\n"
          + "- A single-fact answer stays plain paragraphs — no heading, no list, no closing"
          + " summary. Do not split one thought into bullets, do not invent sections, headings or"
          + " list items to make a short answer look thorough, and do not restate the answer at"
          + " the end.\n"
          + "- Always put file names, paths, commands, identifiers, values and other literal"
          + " strings in backticks — every one of them, every time.\n"
          + "- Use a fenced code block for anything spanning more than one line.";

  private static final PromptFragment FRAGMENT = new PromptFragment(FRAGMENT_TEXT, PRIORITY);

  public static final AnswerShapeGrammar INSTANCE = new AnswerShapeGrammar();

  private AnswerShapeGrammar() {}

  /**
   * Whether the shape-guidance fragment is contributed for this request. Defaults to
   * {@code false}; only an explicit {@code true} (boolean or the string {@code "true"}) on
   * {@link #ARM_SWITCH_KEY} opts a request into the candidate wording.
   */
  public static boolean enabled(ConversationContext ctx) {
    Object raw = ctx == null ? null : ctx.requestBody().get(ARM_SWITCH_KEY);
    if (raw instanceof Boolean flag) {
      return flag;
    }
    if (raw instanceof String s) {
      return "true".equalsIgnoreCase(s.trim());
    }
    return false;
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
