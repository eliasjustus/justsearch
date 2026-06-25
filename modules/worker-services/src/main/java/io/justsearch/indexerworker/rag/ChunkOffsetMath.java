/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure offset/heading math for RAG chunk metadata — the law-bearing core extracted from
 * {@link ChunkDocumentWriter} (tempdoc 555 Pillar A, functional-core/imperative-shell §4). These were
 * pure static methods trapped inside an IO-bearing writer; isolating them makes the law
 * (line-number = 1 + newlines before the offset; preceding-heading = last markdown heading before the
 * offset) independently testable and mutation-targeted. Registered in governance/logic-seams.v1.json.
 *
 * <p>No IO, no state — a registered behavioral-law seam.
 */
public final class ChunkOffsetMath {

  private ChunkOffsetMath() {}

  /** Markdown heading pattern: 1-6 # characters at start of line, followed by space and text. */
  private static final Pattern MARKDOWN_HEADING_PATTERN =
      Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$", Pattern.MULTILINE);

  /** Result of heading extraction. */
  public record HeadingInfo(int level, String text) {
    public static final HeadingInfo NONE = new HeadingInfo(0, "");
  }

  /**
   * Calculates the 1-based line number for a given character offset.
   *
   * @param content the full document content
   * @param charOffset 0-based character offset
   * @return 1-based line number
   */
  public static int calculateLineNumber(String content, int charOffset) {
    if (content == null || content.isEmpty() || charOffset <= 0) {
      return 1;
    }
    int line = 1;
    int limit = Math.min(charOffset, content.length());
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /**
   * Finds the nearest markdown heading preceding the given character offset.
   *
   * @param content the full document content
   * @param charOffset 0-based character offset
   * @return HeadingInfo with level (1-6) and text, or HeadingInfo.NONE if no heading found
   */
  public static HeadingInfo findPrecedingHeading(String content, int charOffset) {
    if (content == null || content.isEmpty() || charOffset <= 0) {
      return HeadingInfo.NONE;
    }

    int searchLimit = Math.min(charOffset, content.length());
    String searchRegion = content.substring(0, searchLimit);

    Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(searchRegion);
    HeadingInfo lastFound = HeadingInfo.NONE;

    while (matcher.find()) {
      int level = matcher.group(1).length();
      String text = matcher.group(2).trim();
      if (!text.isEmpty()) {
        lastFound = new HeadingInfo(level, text);
      }
    }

    return lastFound;
  }
}
