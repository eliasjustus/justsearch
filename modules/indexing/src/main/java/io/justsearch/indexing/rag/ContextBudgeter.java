/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.rag;

import io.justsearch.core.util.Strings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a multi-section context string with a strict character budget.
 *
 * <p>Important: the budget counts <b>all</b> output characters, including section separators and
 * headers (e.g. {@code [From: file.pdf]}), so callers get deterministic caps.
 */
public final class ContextBudgeter {

  /** Separator between sections. */
  public static final String SECTION_SEPARATOR = "\n\n---\n\n";

  /** Default label used when a section label is missing. */
  public static final String DEFAULT_SOURCE_LABEL = "unknown";

  /** Outcome of trying to append a section. */
  public enum AppendResult {
    /** Section content was blank, so nothing changed and callers may continue. */
    SKIPPED_EMPTY,
    /** A full section was appended and callers may continue. */
    APPENDED,
    /** The section was appended but its content had to be truncated to fit; callers should stop. */
    APPENDED_TRUNCATED,
    /** No more content can be appended without exceeding the budget; callers should stop. */
    STOPPED_BUDGET
  }

  /**
   * A section that was successfully appended to the context.
   * Phase 4: Enables structured section tracking for citation filtering.
   */
  public record Section(String sourceLabel, String content, boolean truncated, int sectionIndex) {}

  private final int maxChars;
  private final StringBuilder sb = new StringBuilder();
  private final List<Section> sections = new ArrayList<>();
  private boolean first = true;

  public ContextBudgeter(int maxChars) {
    if (maxChars <= 0) {
      throw new IllegalArgumentException("maxChars must be > 0");
    }
    this.maxChars = maxChars;
  }

  public int maxChars() {
    return maxChars;
  }

  public int length() {
    return sb.length();
  }

  public boolean isEmpty() {
    return sb.isEmpty();
  }

  public String build() {
    return sb.toString();
  }

  /**
   * Returns an unmodifiable list of sections that were successfully appended.
   * Phase 4: Enables structured section tracking for citation filtering.
   */
  public List<Section> sections() {
    return Collections.unmodifiableList(sections);
  }

  /**
   * Appends one section as:
   *
   * <pre>
   * [From: label]
   * content
   * </pre>
   *
   * with {@link #SECTION_SEPARATOR} between sections.
   *
   * <p>The output will never exceed {@link #maxChars()} characters.
   */
  public AppendResult appendSection(String sourceLabel, String content) {
    if (content == null || content.isBlank()) {
      return AppendResult.SKIPPED_EMPTY;
    }

    String label = (sourceLabel == null || sourceLabel.isBlank()) ? DEFAULT_SOURCE_LABEL : sourceLabel;
    String header = "[From: " + label + "]\n";
    String sep = first ? "" : SECTION_SEPARATOR;

    int remaining = maxChars - sb.length();
    if (remaining <= 0) {
      return AppendResult.STOPPED_BUDGET;
    }

    int overhead = sep.length() + header.length();
    if (overhead >= remaining) {
      return AppendResult.STOPPED_BUDGET;
    }

    int availableForContent = remaining - overhead;
    if (availableForContent <= 0) {
      return AppendResult.STOPPED_BUDGET;
    }

    if (content.length() > availableForContent) {
      // Append partial content to fit. Surrogate-safe: never cut mid-pair (tempdoc 554 §B.1).
      String truncatedContent = Strings.codePointSafePrefix(content, availableForContent);
      sb.append(sep).append(header).append(truncatedContent);
      sections.add(new Section(label, truncatedContent, true, sections.size()));
      first = false;
      return AppendResult.APPENDED_TRUNCATED;
    }

    sb.append(sep).append(header).append(content);
    sections.add(new Section(label, content, false, sections.size()));
    first = false;
    return AppendResult.APPENDED;
  }
}
