/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import java.util.regex.Pattern;

/**
 * Tempdoc 865 §7.5 — the ONE place that knows how a search hit's TEXT is written into a tool
 * message, shared by the writer ({@code SearchTool.formatResults}) and the two readers in {@link
 * AgentContextCompressor} (the Layer-3 strip, and the inclusion receipt).
 *
 * <p>It exists because the coupling was previously a literal in one file and a regex in another, and
 * that drift already produced a false claim about evidence: the compressor's receipt asked "does
 * this message still have an {@code Excerpt:} line?", while a vector/dense-only hit has no excerpt
 * regions at all and is written as {@code Preview:} instead. Such a message could never match, so
 * every source it carried was reported {@code dropped} — "Retrieved · never sent to the model" over
 * text sitting verbatim in the prompt. One symbol, imported by both sides, is what makes that class
 * of divergence a compile-time concern rather than a silent one.
 *
 * <p><b>The two patterns are deliberately different, and conflating them would change the prompt.</b>
 * {@link #STRIPPABLE_LINE} is what Layer-3 compression REMOVES — {@code Excerpt:} lines only, exactly
 * as before. {@link #CARRIER_LINE} is what a reader asks about when the question is "does this
 * message still carry a hit's text", and that must include {@code Preview:}, which the strip does not
 * touch. Widening the strip to match the reader would silently delete preview text from the prompt.
 */
public final class ToolResultCarrier {

  private ToolResultCarrier() {}

  /** The label on a line carrying an excerpt region's text. */
  private static final String EXCERPT_LABEL = "Excerpt";

  /** The label on a line carrying a dense-only hit's content preview. */
  private static final String PREVIEW_LABEL = "Preview";

  /** The indent every carrier line is written with, under its {@code [n] title} header. */
  private static final String INDENT = "    ";

  /**
   * Write one excerpt-region line. The writer's only spelling of it — see {@link #CARRIER_LINE} for
   * the reader's.
   */
  public static String excerptLine(String text) {
    return String.format("%s%s: \"%s\"%n", INDENT, EXCERPT_LABEL, text);
  }

  /** Write one content-preview line (the vector/dense-only fallback: no excerpt regions exist). */
  public static String previewLine(String text) {
    return String.format("%s%s: \"%s\"%n", INDENT, PREVIEW_LABEL, text);
  }

  /**
   * Layer-3's strip target: excerpt lines, which are the longest per-result field and are only
   * useful for the iteration that produced them. NOT preview lines — see the class note.
   */
  static final Pattern STRIPPABLE_LINE =
      Pattern.compile("^\\s+" + EXCERPT_LABEL + ":.*$", Pattern.MULTILINE);

  /** Any line that puts a hit's text in front of the model — both spellings. */
  static final Pattern CARRIER_LINE =
      Pattern.compile(
          "^\\s+(?:" + EXCERPT_LABEL + "|" + PREVIEW_LABEL + "):.*$", Pattern.MULTILINE);

  /** The cheap pre-check {@code stripSearchExcerpts} uses before running the regex. */
  static boolean mayHaveStrippableLine(String content) {
    return content != null && content.contains(EXCERPT_LABEL + ":");
  }

  /**
   * Does this message still put a hit's text in front of the model? The inclusion receipt's one
   * question, asked of the artifact rather than of a bookkeeping trail.
   */
  static boolean carriesText(String content) {
    return content != null && CARRIER_LINE.matcher(content).find();
  }
}
