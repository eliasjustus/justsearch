/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.util.Map;

/**
 * Head-side identity projection for a search hit.
 *
 * <p>The public hit id remains the path-oriented document id used by search and citation surfaces.
 * Feedback storage instead needs the stable parent {@code doc_uid}. The Worker enriches delivered
 * chunk hits with the parent UID; the suffix check also accepts a raw chunk UID
 * ({@code parentUid#chunkIndex}) without silently accepting an inconsistent child identity. It
 * never guesses when the identity fields are absent or inconsistent.
 */
public final class KnowledgeSearchHitIdentity {

  private static final String DOC_UID = "doc_uid";
  private static final String PARENT_DOC_ID = "parent_doc_id";
  private static final String CHUNK_INDEX = "chunk_index";
  private static final String IS_CHUNK = "is_chunk";

  private KnowledgeSearchHitIdentity() {}

  /** Returns the path-oriented id referenced by the UI and agent citation surfaces. */
  public static String sourceDocId(KnowledgeSearchResponse.Hit hit) {
    if (hit == null) {
      return null;
    }
    String parent = nonBlank(hit.fields().get(PARENT_DOC_ID));
    return parent != null ? parent : nonBlank(hit.id());
  }

  /**
   * Returns the stable parent document UID, or {@code null} when the hit cannot prove one.
   *
   * <p>Whole-document hits use their stored {@code doc_uid}. Chunk hits must also carry a valid
   * non-negative {@code chunk_index}, and their UID must end in the matching suffix.
   */
  public static String stableParentDocUid(KnowledgeSearchResponse.Hit hit) {
    if (hit == null) {
      return null;
    }
    Map<String, String> fields = hit.fields();
    String uid = nonBlank(fields.get(DOC_UID));
    if (uid == null) {
      return null;
    }
    if (nonBlank(fields.get(PARENT_DOC_ID)) == null) {
      // Parent UIDs are UUIDs and cannot contain the deterministic child separator. Orphaned
      // chunk markers likewise mean this hit cannot prove that the UID belongs to a whole
      // document. Never persist a child UID after malformed/collapsed metadata loss.
      if (uid.indexOf('#') >= 0
          || nonBlank(fields.get(CHUNK_INDEX)) != null
          || "true".equalsIgnoreCase(nonBlank(fields.get(IS_CHUNK)))) {
        return null;
      }
      return uid;
    }
    String rawIndex = nonBlank(fields.get(CHUNK_INDEX));
    if (rawIndex == null) {
      return null;
    }
    int chunkIndex;
    try {
      chunkIndex = Integer.parseInt(rawIndex);
    } catch (NumberFormatException ignored) {
      return null;
    }
    if (chunkIndex < 0) {
      return null;
    }
    String suffix = "#" + chunkIndex;
    if (uid.endsWith(suffix) && uid.length() > suffix.length()) {
      String parentUid = uid.substring(0, uid.length() - suffix.length());
      return parentUid.indexOf('#') < 0 ? parentUid : null;
    }
    return uid.indexOf('#') < 0 ? uid : null;
  }

  private static String nonBlank(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
