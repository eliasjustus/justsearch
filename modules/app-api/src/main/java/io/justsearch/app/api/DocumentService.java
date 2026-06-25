/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Stable surface for retrieving persisted documents by id.
 *
 * <p>The desktop UI relies on this service to hydrate summaries with the full record instead of the
 * compact snippet returned by search responses.
 */
public interface DocumentService {

  /** Separator between context sections (mirrors indexing module constant). */
  String SECTION_SEPARATOR = "\n\n---\n\n";

  /**
   * Tempdoc 565 §15.A — the ONE answer↔source citation-grounding cutoff. Below this cosine
   * similarity an answer sentence is not counted as grounded by a source chunk. Both the RAG matcher
   * ({@code StreamingCitationMatcher}) and the agent matcher ({@code AgentCitationResolver}) read THIS
   * value: the §15.G de-risk proved both call the same {@link #matchCitations} scorer on the same
   * [0,1] scale, so the former 0.45 (agent) / 0.5 (RAG) split was drift, not calibration. The numeric
   * value stays an evidence-backed calibration — but it lives in exactly one place now.
   */
  double DEFAULT_CITATION_SIMILARITY_THRESHOLD = 0.5;

  /**
   * Fetch the full document content for the supplied identifier.
   *
   * @param docId canonical document identifier
   * @return async stage containing the resolved document payload
   */
  CompletionStage<DocumentRecord> fetch(String docId);

  /**
   * Fetch multiple documents by their identifiers in a single batch operation.
   *
   * @param docIds list of canonical document identifiers
   * @return async stage containing a map of docId to resolved document payload
   */
  default CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds) {
    // Default implementation: sequential fetch (subclasses should override for efficiency)
    return CompletableFuture.supplyAsync(() -> {
      Map<String, DocumentRecord> results = new LinkedHashMap<>();
      for (String docId : docIds) {
        try {
          DocumentRecord record = fetch(docId).toCompletableFuture().join();
          results.put(docId, record);
        } catch (Exception e) {
          // Include failed docs with empty content, preserving exception details
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          String errorType = cause.getClass().getSimpleName();
          String errorMsg = cause.getMessage() != null ? cause.getMessage() : "unknown";
          results.put(docId, new DocumentRecord(docId, "",
              Map.of("error", errorMsg, "errorType", errorType)));
        }
      }
      return results;
    });
  }

  /**
   * Retrieves relevant context for Q&A using RAG (Retrieval-Augmented Generation).
   *
   * <p>Uses BM25 search to find the most relevant chunks from the specified documents.
   * Falls back to full document content if chunks are not indexed.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve (default: 5)
   * @return formatted context string containing relevant chunks
   */

  /**
   * Retrieves relevant context for Q&A using RAG (Retrieval-Augmented Generation),
   * with metadata about whether chunks were actually used.
   *
   * <p>Uses BM25 search to find the most relevant chunks from the specified documents.
   * Falls back to full document content if chunks are not indexed.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve (default: 5)
   * @return result containing context string and chunk usage metadata
   */
  default CompletionStage<ContextResult> retrieveContextWithMeta(String question, Set<String> docIds, int topK) {
    return retrieveContextWithMeta(question, docIds, topK, 0);
  }

  /**
   * Retrieves relevant context for Q&A using RAG with token budget.
   *
   * <p>Phase 6 (Gap 6): When maxContextTokens > 0, the Worker uses token-aware budgeting
   * to avoid over-fetching context that would be truncated by the Head. This eliminates
   * the double-truncation problem where Worker returns 200K chars that Head truncates to 3K tokens.
   *
   * @param question the user's question
   * @param docIds set of document IDs to search within
   * @param topK number of chunks to retrieve (default: 5)
   * @param maxContextTokens token budget (0 = use character budget fallback)
   * @return result containing context string and chunk usage metadata
   */
  default CompletionStage<ContextResult> retrieveContextWithMeta(String question, Set<String> docIds, int topK, int maxContextTokens) {
    // Default: fall back to batch fetch and concatenate (no RAG - chunksUsed=0)
    return fetchBatch(List.copyOf(docIds))
        .thenApply(docs -> {
          StringBuilder sb = new StringBuilder();
          for (var entry : docs.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().content().isBlank()) {
              if (sb.length() > 0) {
                sb.append(SECTION_SEPARATOR);
              }
              sb.append("[From: ").append(extractFilename(entry.getKey())).append("]\n");
              sb.append(entry.getValue().content());
            }
          }
          // Default impl uses full docs, not chunks
          return new ContextResult(sb.toString(), 0, 0, docs.size(), List.of(),
              "FULLTEXT_FALLBACK", "DEFAULT_IMPL_NO_CHUNKS", false, List.of());
        });
  }

  /**
   * Immutable carrier for RAG context retrieval results.
   *
   * <p>Includes metadata to distinguish between:
   * <ul>
   *   <li>Actual chunk-based RAG (chunksUsed > 0)</li>
   *   <li>Fallback to full documents (chunksUsed == 0, docsUsed > 0)</li>
   *   <li>No content available (both zero)</li>
   * </ul>
   *
   * @param context the formatted context string
   * @param chunksUsed number of chunks included in the context (0 = fallback to full docs)
   * @param chunksFound total chunks found by search (may be > chunksUsed due to limits)
   * @param docsUsed number of full documents used (when chunksUsed == 0)
   * @param citations structured chunk span metadata for click-to-verify UI (empty for fallback)
   * @param retrievalMode retrieval mode used: BM25, HYBRID, FULLTEXT_FALLBACK
   * @param retrievalModeReason stable reason code explaining the mode choice
   * @param contextTruncated true if context was truncated due to token budget
   * @param sections structured sections linking content to citations (Phase 4)
   */
  record ContextResult(
      String context,
      int chunksUsed,
      int chunksFound,
      int docsUsed,
      List<ContextCitation> citations,
      String retrievalMode,
      String retrievalModeReason,
      boolean contextTruncated,
      List<ContextSection> sections,
      QualitySignals quality) {
    public ContextResult {
      context = context == null ? "" : context;
      chunksUsed = Math.max(0, chunksUsed);
      chunksFound = Math.max(0, chunksFound);
      docsUsed = Math.max(0, docsUsed);
      citations = citations == null ? List.of() : List.copyOf(citations);
      retrievalMode = retrievalMode == null ? "" : retrievalMode;
      retrievalModeReason = retrievalModeReason == null ? "" : retrievalModeReason;
      sections = sections == null ? List.of() : List.copyOf(sections);
      quality = quality == null ? QualitySignals.EMPTY : quality;
    }

    /** Backward-compatible constructor without quality signals. */
    public ContextResult(
        String context, int chunksUsed, int chunksFound, int docsUsed,
        List<ContextCitation> citations, String retrievalMode,
        String retrievalModeReason, boolean contextTruncated,
        List<ContextSection> sections) {
      this(context, chunksUsed, chunksFound, docsUsed, citations,
          retrievalMode, retrievalModeReason, contextTruncated, sections,
          QualitySignals.EMPTY);
    }

    /** Returns true if actual chunk-based RAG was used (not fallback). */
    public boolean usedChunks() {
      return chunksUsed > 0;
    }
  }

  /** Retrieval quality signals for CRAG-style confidence assessment. */
  record QualitySignals(
      float bestChunkScore,
      float scoreGap,
      float retrievalCoverage,
      int chunksConsidered,
      int chunksIncluded) {

    static final QualitySignals EMPTY = new QualitySignals(0f, 0f, 0f, 0, 0);
  }

  /**
   * Retrieves relevant context for Q&A using RAG with full filter support.
   *
   * <p>This is the rich alternative to the positional-parameter overloads.
   * Supports entity filters, temporal filters, content filters, auto entity
   * extraction, and context format selection.
   *
   * @param params retrieval parameters
   * @return result containing context string, chunk metadata, and quality signals
   */
  default CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params) {
    // Default: delegate to legacy method (ignoring new filter params)
    return retrieveContextWithMeta(
        params.question(), params.docIds(), params.topK(), params.maxContextTokens());
  }

  /**
   * Structured citation metadata for a chunk used in RAG context.
   *
   * <p>Offsets are 0-based character offsets into the parent document's extracted text.
   */
  record ContextCitation(
      String parentDocId,
      int chunkIndex,
      int chunkTotal,
      int startChar,
      int endChar,
      float score,
      String excerpt,
      // F8 Tier 2: In-document navigation fields
      int startLine,
      int endLine,
      String headingText,
      int headingLevel) {
    public ContextCitation {
      parentDocId = parentDocId == null ? "" : parentDocId;
      chunkIndex = Math.max(0, chunkIndex);
      chunkTotal = Math.max(1, chunkTotal);
      startChar = Math.max(0, startChar);
      endChar = Math.max(0, endChar);
      excerpt = excerpt == null ? "" : excerpt;
      startLine = Math.max(0, startLine);
      endLine = Math.max(0, endLine);
      headingText = headingText == null ? "" : headingText;
      headingLevel = Math.max(0, headingLevel);
    }
  }

  /**
   * A section in the assembled RAG context, linking to citation metadata.
   * Phase 4: Enables structured section tracking for citation filtering on truncation.
   */
  record ContextSection(
      String sourceLabel,
      String content,
      boolean truncated,
      int sectionIndex,
      int chunkIndex) {
    public ContextSection {
      sourceLabel = sourceLabel == null ? "" : sourceLabel;
      content = content == null ? "" : content;
      sectionIndex = Math.max(0, sectionIndex);
      chunkIndex = Math.max(0, chunkIndex);
    }
  }

  /**
   * Post-hoc citation matching: matches LLM answer sentences to source chunks
   * via embedding similarity on the Worker side.
   *
   * @param answerText the full LLM answer text
   * @param citations the context citations from RAG retrieval
   * @param threshold minimum cosine similarity (0.0-1.0)
   * @return result containing matched sentence-to-chunk mappings
   */
  default CompletionStage<CitationMatchResult> matchCitations(
      String answerText, List<ContextCitation> citations, double threshold) {
    return CompletableFuture.completedFuture(
        new CitationMatchResult(List.of(), 0, 0, 0));
  }

  /** Result of post-hoc citation matching. */
  record CitationMatchResult(
      List<CitationMatchEntry> matches,
      int sentencesTotal,
      int sentencesMatched,
      long tookMs) {
    public CitationMatchResult {
      matches = matches == null ? List.of() : List.copyOf(matches);
      sentencesTotal = Math.max(0, sentencesTotal);
      sentencesMatched = Math.max(0, sentencesMatched);
      tookMs = Math.max(0, tookMs);
    }
  }

  /** A single sentence-to-chunk citation match. */
  record CitationMatchEntry(
      int sentenceIndex,
      String sentenceText,
      int chunkIndex,
      double similarity,
      String parentDocId) {
    public CitationMatchEntry {
      sentenceText = sentenceText == null ? "" : sentenceText;
      parentDocId = parentDocId == null ? "" : parentDocId;
    }
  }

  private static String extractFilename(String path) {
    if (path == null) return "unknown";
    int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
  }

  /**
   * Null Object for environments where the Worker isn't connected. Returns a service whose
   * {@code fetch}/etc. methods complete with a failed future carrying
   * {@code UnsupportedOperationException}. Used as the seed for the {@code LazyDocumentService}
   * supplier chain before late-bind. Tempdoc 519 F2 (refined per §22): kept as the Null Object
   * pattern.
   */
  static DocumentService unavailable() {
    return docId ->
        CompletableFuture.failedFuture(
            new UnsupportedOperationException("Document service not configured"));
  }

  /** Signals that the underlying document store or index could not be reached. */
  class UnavailableException extends RuntimeException {
    public UnavailableException(String message) {
      super(message);
    }

    public UnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Immutable carrier for resolved document data. */
  record DocumentRecord(String docId, String content, Map<String, Object> metadata) {
    @SuppressWarnings("SelfAssignment")
    public DocumentRecord {
      docId = Objects.requireNonNull(docId, "docId");
      content = content == null ? "" : content;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  /**
   * Fetch a slice of the document content (paged by character offsets).
   *
   * <p>Implementations should prefer serving slices from the Worker/index (extracted text) rather than reading raw
   * files. The default implementation falls back to {@link #fetch(String)} and slices in-process.
   *
   * @param docId canonical document identifier
   * @param offsetChars 0-based offset into the extracted text
   * @param maxChars maximum number of characters to return
   */
  default CompletionStage<DocumentSlice> fetchSlice(String docId, int offsetChars, int maxChars) {
    int offset = Math.max(0, offsetChars);
    int max = maxChars <= 0 ? 20_000 : maxChars;
    return fetch(docId)
        .thenApply(
            record -> {
              if (record == null) {
                return new DocumentSlice(docId, "", Map.of(), false, false, 0, "not_found");
              }
              String content = record.content() == null ? "" : record.content();
              int totalLen = content.length();
              int start = Math.min(offset, totalLen);
              int end = Math.min(start + max, totalLen);
              String slice = start >= end ? "" : content.substring(start, end);
              boolean truncated = end < totalLen;
              return new DocumentSlice(
                  record.docId(),
                  slice,
                  record.metadata(),
                  true,
                  truncated,
                  end,
                  null);
            });
  }

  /** Immutable carrier for a paged slice of extracted text content. */
  record DocumentSlice(
      String docId,
      String content,
      Map<String, Object> metadata,
      boolean found,
      boolean truncated,
      int nextOffsetChars,
      String error) {
    public DocumentSlice {
      Objects.requireNonNull(docId, "docId");
      content = content == null ? "" : content;
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
      nextOffsetChars = Math.max(0, nextOffsetChars);
      error = error == null || error.isBlank() ? null : error;
    }
  }
}
