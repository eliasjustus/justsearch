/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.chunking;

import java.util.UUID;

/**
 * Centralized chunk ID generation utilities.
 *
 * <p>This class provides opaque chunk ID generation to avoid coupling between
 * file paths and chunk document IDs. Chunk IDs are intentionally opaque (UUIDs)
 * so that:
 * <ul>
 *   <li>File paths containing special characters (like '#') don't collide with chunk markers</li>
 *   <li>Delete/lookup operations use proper schema fields (parent_doc_id, is_chunk) not string parsing</li>
 *   <li>The chunk ID format can evolve without breaking external consumers</li>
 * </ul>
 *
 * <p>All code that generates chunk doc_id values should use this class rather than
 * string concatenation patterns like {@code parentDocId + "#chunk_" + index}.
 */
// PERMANENT COMPAT - DO NOT REMOVE (chunk ID format is an external contract)
public final class ChunkIds {

  /**
   * Prefix for chunk document IDs.
   *
   * <p>This prefix makes chunk IDs clearly identifiable as synthetic/generated
   * rather than file paths, preventing accidental misinterpretation.
   */
  public static final String CHUNK_PREFIX = "chunk:";

  private ChunkIds() {
    // Static utility class
  }

  /**
   * Generates a new opaque chunk document ID.
   *
   * <p>The returned ID is a UUID-based string that:
   * <ul>
   *   <li>Cannot collide with normalized file paths</li>
   *   <li>Is unique across all chunks in the index</li>
   *   <li>Does not encode the parent document ID or chunk index</li>
   * </ul>
   *
   * <p>Note: Chunk position information should be stored in dedicated fields
   * ({@code chunk_index}, {@code chunk_total}) rather than embedded in the ID.
   *
   * @return a new unique chunk document ID
   */
  public static String newChunkDocId() {
    return CHUNK_PREFIX + UUID.randomUUID().toString();
  }


}
