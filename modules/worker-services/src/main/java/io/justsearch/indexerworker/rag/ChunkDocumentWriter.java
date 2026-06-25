/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.services.LanguageUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import io.justsearch.indexing.chunking.ChunkIds;
import io.justsearch.indexing.chunking.ChunkSplitter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical writer for chunk documents (RAG).
 *
 * <p>This centralizes chunk indexing so all write paths (index loop, VDU live update,
 * VDU replay) produce consistent chunks (offsets, sizing, metadata).
 */
public final class ChunkDocumentWriter {

  private static final Logger log = LoggerFactory.getLogger(ChunkDocumentWriter.class);

  public static final int CHUNK_THRESHOLD_CHARS = 2000;
  public static final int CHUNK_TARGET_TOKENS = ChunkSplitter.DEFAULT_CHUNK_TOKENS;
  public static final int CHUNK_OVERLAP_TOKENS = ChunkSplitter.DEFAULT_OVERLAP_TOKENS;
  private static final int CONTENT_PREVIEW_MAX_CHARS = 4096;

  private ChunkDocumentWriter() {}

  public record ParentChunkMetadata(
      String mime, String mimeBase, String fileKind, String language, Long parentTokenCount) {}

  /**
   * Regenerates chunk docs for a parent doc by loading metadata from the existing parent document.
   *
   * <p>This is used by VDU update/replay paths where we don't have a {@code Path} or
   * {@code ExtractionResult} but want chunks to inherit metadata such as mime/file_kind.
   */
  public static int regenerateChunksFromExistingParent(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator,
      String parentDocId, String content) {
    if (documentFieldOps == null) {
      return 0;
    }
    String mime = documentFieldOps.getDocumentField(parentDocId, SchemaFields.MIME);
    String mimeBase = documentFieldOps.getDocumentField(parentDocId, SchemaFields.MIME_BASE);
    String fileKind = documentFieldOps.getDocumentField(parentDocId, SchemaFields.FILE_KIND);
    String parentTokenCountRaw =
        documentFieldOps.getDocumentField(parentDocId, SchemaFields.PARENT_TOKEN_COUNT);

    boolean isMarkdown = "markdown".equalsIgnoreCase(fileKind);
    String preview = LanguageUtils.contentPreview(content, CONTENT_PREVIEW_MAX_CHARS, isMarkdown);
    String language = LanguageUtils.resolveLanguage(preview);
    Long parentTokenCount =
        parentTokenCountRaw == null || parentTokenCountRaw.isBlank()
            ? null
            : Long.parseLong(parentTokenCountRaw);

    return regenerateChunks(
        documentFieldOps,
        indexingCoordinator,
        parentDocId,
        content,
        new ParentChunkMetadata(mime, mimeBase, fileKind, language, parentTokenCount));
  }

  /**
   * Regenerates chunk docs for a parent doc with explicit metadata.
   *
   * <p>Deletion is best-effort; worst-case is stale chunks remain rather than failing the caller.
   */
  public static int regenerateChunks(
      DocumentFieldOps documentFieldOps, IndexingCoordinator indexingCoordinator,
      String parentDocId, String content, ParentChunkMetadata meta) {
    if (documentFieldOps == null || indexingCoordinator == null
        || parentDocId == null || parentDocId.isBlank()) {
      return 0;
    }

    // Always delete existing chunks first (prevents stale/orphan chunks).
    try {
      indexingCoordinator.deleteChunksForParentDocId(parentDocId);
    } catch (RuntimeException e) {
      log.debug("Failed to delete existing chunks for {}: {}", parentDocId, e.getMessage());
    }

    if (content == null || content.length() < CHUNK_THRESHOLD_CHARS) {
      return 0;
    }

    // F2: Use MIME-based mode selection for CSV/JSON chunking awareness
    ChunkSplitter.Mode mode = ChunkSplitter.Mode.fromMimeOrFileKind(
        meta != null ? meta.mimeBase : null,
        meta != null ? meta.fileKind : null);
    List<ChunkSplitter.Chunk> chunks =
        ChunkSplitter.splitWithMetadata(content, CHUNK_TARGET_TOKENS, CHUNK_OVERLAP_TOKENS, mode);
    if (chunks.size() <= 1) {
      return 0;
    }

    // ChunkSplitter offsets are now relative to the original content (including leading whitespace).
    int indexed = 0;
    for (ChunkSplitter.Chunk chunk : chunks) {
      String chunkContent = chunk.content();
      if (chunkContent == null || chunkContent.isBlank()) {
        continue;
      }

      Map<String, Object> fields = new HashMap<>();
      String chunkId = ChunkIds.newChunkDocId();
      fields.put(SchemaFields.DOC_ID, chunkId);
      fields.put(SchemaFields.DOC_UID, UUID.randomUUID().toString());
      fields.put(SchemaFields.IS_CHUNK, "true");
      fields.put(SchemaFields.PARENT_DOC_ID, parentDocId);
      fields.put(SchemaFields.CHUNK_INDEX, String.valueOf(chunk.index()));
      fields.put(SchemaFields.CHUNK_TOTAL, String.valueOf(chunks.size()));
      fields.put(SchemaFields.CHUNK_CONTENT, chunkContent);
      int absoluteStartChar = chunk.startChar();
      int absoluteEndChar = chunk.endChar();
      fields.put(SchemaFields.CHUNK_START_CHAR, String.valueOf(absoluteStartChar));
      fields.put(SchemaFields.CHUNK_END_CHAR, String.valueOf(absoluteEndChar));
      fields.put(SchemaFields.PATH, parentDocId);

      // F8 Tier 2: Line numbers (1-based)
      // endChar is exclusive [startChar, endChar), so use endChar-1 for the inclusive end line
      int startLine = ChunkOffsetMath.calculateLineNumber(content, absoluteStartChar);
      int endLine = ChunkOffsetMath.calculateLineNumber(content, Math.max(0, absoluteEndChar - 1));
      fields.put(SchemaFields.CHUNK_START_LINE, String.valueOf(startLine));
      fields.put(SchemaFields.CHUNK_END_LINE, String.valueOf(endLine));

      // Heading context: extract from Markdown and structured-extracted content.
      // The findPrecedingHeading() regex matches "## heading" markers which appear in both
      // native Markdown and StructuredDocument.toAnnotatedText() output (tempdoc 252 Tier 1).
      boolean hasHeadingMarkers =
          (meta != null
              && ("markdown".equalsIgnoreCase(meta.fileKind)
                  || "pdf".equalsIgnoreCase(meta.fileKind)
                  || "office".equalsIgnoreCase(meta.fileKind)));
      if (hasHeadingMarkers) {
        ChunkOffsetMath.HeadingInfo heading =
            ChunkOffsetMath.findPrecedingHeading(content, absoluteStartChar);
        fields.put(SchemaFields.CHUNK_HEADING_TEXT, heading.text());
        fields.put(SchemaFields.CHUNK_HEADING_LEVEL, String.valueOf(heading.level()));
      } else {
        fields.put(SchemaFields.CHUNK_HEADING_TEXT, "");
        fields.put(SchemaFields.CHUNK_HEADING_LEVEL, "0");
      }

      if (meta != null) {
        if (meta.mime != null && !meta.mime.isBlank()) {
          fields.put(SchemaFields.MIME, meta.mime);
        }
        if (meta.mimeBase != null && !meta.mimeBase.isBlank()) {
          fields.put(SchemaFields.MIME_BASE, meta.mimeBase);
        }
        if (meta.fileKind != null && !meta.fileKind.isBlank()) {
          fields.put(SchemaFields.FILE_KIND, meta.fileKind);
        }
        if (meta.language != null && !meta.language.isBlank()) {
          fields.put(SchemaFields.LANGUAGE, meta.language);
        }
        if (meta.parentTokenCount != null) {
          fields.put(SchemaFields.PARENT_TOKEN_COUNT, meta.parentTokenCount);
        }
      }

      fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());

      // Initialize chunk embedding status for Phase 6 backfill
      fields.put(SchemaFields.CHUNK_EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
      fields.put(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT, "0");

      // Initialize SPLADE status for Phase 3 backfill
      fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING);

      indexingCoordinator.indexSingle(new IndexDocument(fields));
      indexed++;
    }

    return indexed;
  }

  // ========== F8 Tier 2: Line Number & Heading Extraction ==========

}
