/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.parseFloatOrNull;
import static io.justsearch.adapters.lucene.runtime.LuceneRuntimeUtils.parseLongOrNull;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;

/**
 * Utility methods for encoding and decoding search_after cursors.
 *
 * <p>Search_after cursors enable efficient pagination through large result sets by storing the
 * position information needed to resume from a specific point.
 *
 * <p>Extracted from LuceneLifecycleManager to improve code organization and testability.
 */
public final class SearchAfterCursorHelper {

  /** Prefix for all search_after cursor tokens (version 1). */
  public static final String CURSOR_PREFIX = "safter-v1:";

  private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

  private SearchAfterCursorHelper() {} // utility class

  /**
   * Decoded search_after cursor containing all position information.
   *
   * @param sort the sort order used for the original search
   * @param docId the document ID at this cursor position
   * @param score the relevance score (for RELEVANCE sort)
   * @param modifiedAt the modification timestamp (for MODIFIED_* sorts)
   * @param sizeBytes the file size in bytes (for SIZE_* sorts)
   */
  public record DecodedCursor(
      RuntimeSearchSort sort, String docId, Float score, Long modifiedAt, Long sizeBytes) {}

  /**
   * Decodes a search_after cursor token into its component parts.
   *
   * <p>Format: {@code safter-v1:<sortKey>:<docIdB64>:<score|_>:<modifiedAt|_>:<sizeBytes|_>}
   *
   * @param token the cursor token to decode
   * @return decoded cursor, or null if token is null/blank
   * @throws IllegalArgumentException if token format is invalid
   */
  public static DecodedCursor decode(String token) {
    if (token == null || token.isBlank()) return null;
    String t = token.trim();
    if (!t.startsWith(CURSOR_PREFIX)) {
      throw new IllegalArgumentException("invalid_cursor_prefix");
    }
    String payload = t.substring(CURSOR_PREFIX.length());
    // Format: <sortKey>:<docIdB64>:<score|_>:<modifiedAt|_>:<sizeBytes|_>
    String[] parts = payload.split(":", -1);
    if (parts.length != 5) {
      throw new IllegalArgumentException("invalid_cursor_format");
    }
    RuntimeSearchSort sort = RuntimeSearchSort.fromKey(parts[0]);
    if (sort == null) {
      throw new IllegalArgumentException("invalid_cursor_sort");
    }
    String docId;
    try {
      docId = new String(B64_DEC.decode(parts[1]), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid_cursor_doc_id");
    }
    Float score = parseFloatOrNull(parts[2]);
    Long modifiedAt = parseLongOrNull(parts[3]);
    Long sizeBytes = parseLongOrNull(parts[4]);
    return new DecodedCursor(sort, docId, score, modifiedAt, sizeBytes);
  }

  /**
   * Encodes cursor position information into a token string.
   *
   * @param sort the sort order used for the search
   * @param last the last ScoreDoc from the search results
   * @param docId the document ID at this position
   * @return encoded cursor token, or null if any required parameter is missing
   */
  public static String encode(RuntimeSearchSort sort, ScoreDoc last, String docId) {
    if (sort == null || docId == null || docId.isBlank() || last == null) {
      return null;
    }
    String docB64 = B64_ENC.encodeToString(docId.getBytes(StandardCharsets.UTF_8));
    String score = "_";
    String modifiedAt = "_";
    String sizeBytes = "_";

    if (sort == RuntimeSearchSort.RELEVANCE) {
      score = Float.toString(last.score);
    } else if (sort == RuntimeSearchSort.MODIFIED_DESC || sort == RuntimeSearchSort.MODIFIED_ASC) {
      if (last instanceof FieldDoc fd && fd.fields != null && fd.fields.length > 0) {
        Object v = fd.fields[0];
        if (v instanceof Long l) modifiedAt = Long.toString(l);
        else if (v instanceof Number n) modifiedAt = Long.toString(n.longValue());
      }
    } else if (sort == RuntimeSearchSort.SIZE_DESC || sort == RuntimeSearchSort.SIZE_ASC) {
      if (last instanceof FieldDoc fd && fd.fields != null && fd.fields.length > 0) {
        Object v = fd.fields[0];
        if (v instanceof Long l) sizeBytes = Long.toString(l);
        else if (v instanceof Number n) sizeBytes = Long.toString(n.longValue());
      }
    }

    return CURSOR_PREFIX + sort.key() + ":" + docB64 + ":" + score + ":" + modifiedAt + ":" + sizeBytes;
  }
}
