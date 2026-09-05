/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The parent-content revision identity carried by every chunk document
 * ({@code chunk_parent_content_sha256}, tempdoc 931 §C.1).
 *
 * <p>{@code chunk_content} is indexed but not stored, so a read-modify-write on a chunk has to
 * re-slice it out of the parent's stored {@code content}. Parent write and chunk regeneration are
 * two separate coordinator calls, so an NRT refresh between them can expose the NEW parent content
 * next to the OLD chunk documents — and an equal-or-longer rewrite then yields silently wrong chunk
 * text. Writer and reader hash the same parent string through this one method so the comparison
 * cannot drift.
 */
public final class ChunkParentRevision {

  private ChunkParentRevision() {}

  /**
   * @param parentContent the parent document's extracted content, exactly as it is stored
   * @return the lowercase-hex SHA-256 of {@code parentContent}'s UTF-8 bytes
   */
  public static String sha256Hex(String parentContent) {
    if (parentContent == null) {
      throw new IllegalArgumentException("parentContent must not be null");
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(parentContent.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** The first 8 hex characters of a revision hash, for log and error messages. */
  public static String shortForm(String sha256Hex) {
    if (sha256Hex == null || sha256Hex.isBlank()) return "<absent>";
    return sha256Hex.length() <= 8 ? sha256Hex : sha256Hex.substring(0, 8);
  }
}
