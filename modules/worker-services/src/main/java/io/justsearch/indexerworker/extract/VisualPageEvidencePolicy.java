/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** Shared visual-page vocabulary used by Worker extraction evidence and routing. */
final class VisualPageEvidencePolicy {
  static final int READABLE_PAGE_MIN_CHARS = 50;
  static final String LAYOUT_TABLE_LIKE = "table_like";
  static final String LAYOUT_MIXED_VISUAL = "mixed_visual";
  static final String LAYOUT_LOW = "low";
  static final String LAYOUT_NONE = "none";

  private VisualPageEvidencePolicy() {}

  static boolean hasReadableText(String text) {
    return text != null && text.strip().length() >= READABLE_PAGE_MIN_CHARS;
  }

  static String layoutComplexity(
      int tableCount, int listCount, int headingCount, int imagePageCount, boolean mixedPdf) {
    if (tableCount > 0) {
      return LAYOUT_TABLE_LIKE;
    }
    if (mixedPdf || imagePageCount > 0) {
      return LAYOUT_MIXED_VISUAL;
    }
    if (listCount > 0 || headingCount > 0) {
      return LAYOUT_LOW;
    }
    return LAYOUT_NONE;
  }
}
