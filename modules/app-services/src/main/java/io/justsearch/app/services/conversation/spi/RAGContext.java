/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.core.util.DocumentTypeDetector;
import io.justsearch.core.util.TokenEstimation;
import io.justsearch.core.util.TokenEstimation.TruncationResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG retrieval {@link ContextInjector} for the RAG-ask shape.
 *
 * <p>Per tempdoc 491 §C3: lifts the retrieval + fallback logic from
 * {@code RagStreamingHandler.fetchRagContext} / {@code fetchBatchFallback} /
 * {@code formatDocuments}. Reads {@code {question, docIds[], topK?}} from the request body,
 * runs {@link DocumentService#retrieveContextWithMeta}, falls back to
 * {@link DocumentService#fetchBatch} when chunked retrieval is unavailable, applies token
 * truncation as a safety net, emits a {@code rag.meta} event (namespaced per §C3 plan), and
 * persists citations + chunks-used + docIds into {@link ConversationContext#attributes} for
 * {@code CitationMatcher} + {@code RAGDoneEnricher} to consume. After truncation, all RAG
 * citations are retained as-is (kept = every citation when chunk retrieval was used, none
 * otherwise) — citations are NOT filtered down to the sections the truncation actually kept.
 * Section-aware citation filtering is not implemented; it would require a section-boundary-aware
 * truncation redesign in {@link TokenEstimation}.
 *
 * <p>Missing {@code question} or {@code docIds} → {@link InjectorResult#terminalError} with
 * code {@code NO_QUESTION} / {@code NO_FILES}; engine aborts before LLM call.
 */
public final class RAGContext implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(RAGContext.class);

  public static final String ID = "core.rag-context";

  /** Default top-K for chunk retrieval (matches legacy default). */
  public static final int DEFAULT_TOP_K = 5;

  /** Default fetch timeout for retrieval and batch-fetch fallback. */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

  /** Attribute keys for cross-SPI handoff. */
  public static final String ATTR_CITATIONS = "rag.citations";
  public static final String ATTR_CHUNKS_USED = "rag.chunksUsed";
  public static final String ATTR_CHUNKS_FOUND = "rag.chunksFound";
  public static final String ATTR_USED_RAG = "rag.usedRag";
  public static final String ATTR_DOC_IDS = "rag.docIds";
  public static final String ATTR_FILE_COUNT = "rag.fileCount";

  /**
   * Tempdoc 610 §J.3 — the conversation's hidden-source ids (unit-separator-joined parentDocId +
   * chunkIndex), seeded onto the context by the engine from the ConversationStore. RAGContext threads
   * them to retrieval so the Worker drops those chunks pre-search. A {@code List<String>}; absent = none.
   */
  public static final String ATTR_EXCLUDED_SOURCES = "rag.excludedSources";

  /**
   * Tempdoc 561 P-A/P-B — the producer-owned calibration signal for the answer's evidence. Holds the
   * CRAG-style {@link DocumentService.QualitySignals} (best chunk score, score gap, retrieval
   * coverage, chunks considered) the retrieval producer computed, so {@code RAGDoneEnricher} can
   * project it onto the done payload and the engine can persist it WITH the assistant turn. The
   * consumer renders the producer's calibration rather than re-deriving confidence FE-side.
   */
  public static final String ATTR_QUALITY = "rag.quality";

  private final DocumentService documents;
  private final Duration timeout;

  /**
   * Fallback top-K when the request body does not specify one. Sourced from
   * {@code justsearch.rag.top_k} at the composition root (tempdoc 799 N.2). Before that the
   * setting resolved correctly and was read by nothing — {@link #DEFAULT_TOP_K} always won.
   *
   * <p>Precedence is body -> this -> {@link #DEFAULT_TOP_K}; an explicit per-request topK still
   * wins, so wiring config cannot override a caller that asked for a specific value.
   */
  private final int defaultTopK;

  public RAGContext(DocumentService documents) {
    this(documents, DEFAULT_TIMEOUT, DEFAULT_TOP_K);
  }

  public RAGContext(DocumentService documents, Duration timeout) {
    this(documents, timeout, DEFAULT_TOP_K);
  }

  /**
   * Configured-top-K constructor (tempdoc 799 N.2). Keeps {@link #DEFAULT_TIMEOUT} internal so the
   * composition root does not need visibility of it.
   */
  public RAGContext(DocumentService documents, int defaultTopK) {
    this(documents, DEFAULT_TIMEOUT, defaultTopK);
  }

  public RAGContext(DocumentService documents, Duration timeout, int defaultTopK) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.defaultTopK = defaultTopK > 0 ? defaultTopK : DEFAULT_TOP_K;
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Map<String, Object> body = ctx.requestBody();
    String question = asString(body.get("question"));
    // Tempdoc 603 C2 — prefer the decontextualized standalone question (QueryRewriteInjector runs before
    // us and stashes it). A follow-up only retrieves a coherent passage set once coreference/ellipsis is
    // resolved from the conversation history; absent a rewrite (first turn / AI down / timeout) we use the
    // raw question unchanged.
    Object standalone = ctx.attributes().get(QueryRewriteInjector.ATTR_STANDALONE_QUESTION);
    if (standalone instanceof String s && !s.isBlank()) {
      question = s;
    }
    List<String> docIds = extractDocIds(body);
    int topK = extractTopK(body);

    if (question == null || question.isBlank()) {
      return InjectorResult.terminalError(errorEvent("No question provided", "NO_QUESTION"));
    }
    // Stash docIds + fileCount for the done enricher (set even on retrieval failure).
    ctx.attributes().put(ATTR_DOC_IDS, docIds);
    ctx.attributes().put(ATTR_FILE_COUNT, docIds.size());

    Set<String> docIdSet = new HashSet<>(docIds);

    // Tempdoc 610 §J.3 — the user's hidden retrieved sources (seeded by the engine from the store);
    // threaded to retrieval so the Worker drops these chunks before ranking.
    List<String> excludedSourceIds = excludedSourcesFrom(ctx);

    // Try chunked RAG retrieval. When docIds is empty, use open-retrieval
    // (BM25 pre-search discovers relevant documents from the full index).
    RetrievalAttempt attempt;
    if (docIds.isEmpty()) {
      attempt = tryOpenRetrieval(question, topK, excludedSourceIds);
    } else {
      attempt = tryRetrieveContext(question, docIdSet, topK, excludedSourceIds);
    }
    ContextResult retrieval = attempt.result();
    String context = retrieval == null ? null : retrieval.context();
    int chunksUsed = retrieval == null ? 0 : retrieval.chunksUsed();
    int chunksFound = retrieval == null ? 0 : retrieval.chunksFound();
    List<ContextCitation> citations = retrieval == null ? List.of() : retrieval.citations();
    String retrievalMode = retrieval == null ? "" : retrieval.retrievalMode();
    String retrievalModeReason = retrieval == null ? "" : retrieval.retrievalModeReason();
    boolean contextTruncated = retrieval != null && retrieval.contextTruncated();

    List<SseEvent> events = new ArrayList<>();
    if (retrieval != null) {
      Map<String, Object> ragMeta = new LinkedHashMap<>();
      ragMeta.put("retrieval_mode", retrievalMode);
      ragMeta.put("retrieval_mode_reason", retrievalModeReason);
      ragMeta.put("context_truncated", contextTruncated);
      ragMeta.put("chunks_used", chunksUsed);
      ragMeta.put("chunks_found", chunksFound);
      DocumentService.QualitySignals qs = retrieval.quality();
      ragMeta.put("best_chunk_score", qs.bestChunkScore());
      ragMeta.put("score_gap", qs.scoreGap());
      ragMeta.put("retrieval_coverage", qs.retrievalCoverage());
      ragMeta.put("chunks_considered", qs.chunksConsidered());
      events.add(new SseEvent("rag.meta", ragMeta));
      // Tempdoc 561 P-A/P-B: stash the producer's calibration so RAGDoneEnricher projects it onto the
      // done payload and the engine persists it WITH the assistant turn (evidence first-class on the
      // record). EPHEMERAL retrieval semantics are unchanged — this only exposes the signal downstream.
      ctx.attributes().put(ATTR_QUALITY, qs);
    }

    // Fallback to whole-document fetch when chunks are unavailable or empty.
    if (context == null || context.isBlank() || chunksUsed == 0) {
      if ("FALLBACK_FAILED".equals(retrievalMode)) {
        Map<String, Object> err = errorPayload("RAG context retrieval failed", "FETCH_FAILED");
        err.put("docIds", docIds);
        return InjectorResult.terminalError(new SseEvent("error", err));
      }
      if (docIds.isEmpty()) {
        // Tempdoc 806 B.2 (round-12): an unscoped ask whose retrieval never COMPLETED used to answer
        // "No matching documents found in the index" — a confident negative claim about the corpus
        // derived from a call that failed to run. Round 12 saw exactly this on a cold reranker while
        // the same question answered in ~9.5s on retry. An attempt that did not finish reports itself
        // as unfinished; only a retrieval that ran and found nothing may claim nothing is there.
        if (attempt.timedOut()) {
          return InjectorResult.terminalError(
              errorEvent(
                  "Still working on it - the search engine did not answer in time. This is not a"
                      + " result about your documents; ask again in a moment.",
                  "RETRIEVAL_TIMEOUT"));
        }
        if (attempt.failed()) {
          return InjectorResult.terminalError(
              errorEvent(
                  "Retrieval could not run, so nothing was searched. This is not a result about"
                      + " your documents.",
                  "RETRIEVAL_FAILED"));
        }
        Map<String, Object> err =
            errorPayload("No matching documents found in the index", "NO_CONTENT");
        return InjectorResult.terminalError(new SseEvent("error", err));
      }
      // Tempdoc 610 §J.3 — mirror the worker fallback: never re-inject (via whole-doc fetch) a parent
      // doc whose chunks the user hid. If every selected doc is hidden, this empties to NO_CONTENT.
      List<String> fallbackDocIds = dropExcludedParentDocs(docIds, excludedSourceIds);
      String fallback = fallbackDocIds.isEmpty() ? null : fetchBatchFallback(fallbackDocIds);
      if (fallback == null || fallback.isBlank()) {
        Map<String, Object> err = errorPayload("No content in selected files", "NO_CONTENT");
        err.put("docIds", docIds);
        return InjectorResult.terminalError(new SseEvent("error", err));
      }
      context = fallback;
      // chunksUsed already 0; chunksFound stays as-is; citations remain empty.
    }

    // Token-budget truncation safety net.
    int budgetTokens = TokenEstimation.computeSafeInputBudgetTokens(8192, 1024);
    TruncationResult truncation = TokenEstimation.truncateIfNeeded(context, budgetTokens);

    boolean usedRag = chunksUsed > 0;
    // Citations correspond to chunks; if we used full-doc fallback, no citations.
    List<ContextCitation> kept = usedRag ? citations : List.of();
    int keptCount = kept.size();

    // Stash for downstream consumers (StreamingCitationMatcher, RAGDoneEnricher).
    ctx.attributes().put(ATTR_CITATIONS, kept);
    ctx.attributes().put(ATTR_CHUNKS_USED, usedRag ? keptCount : 0);
    ctx.attributes().put(ATTR_CHUNKS_FOUND, chunksFound);
    ctx.attributes().put(ATTR_USED_RAG, usedRag);

    // Slice 493: emit citations at retrieval time so the FE has them before any LLM tokens.
    if (!kept.isEmpty()) {
      List<Map<String, Object>> citationMaps = new ArrayList<>(kept.size());
      for (ContextCitation c : kept) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parentDocId", c.parentDocId());
        m.put("chunkIndex", c.chunkIndex());
        m.put("chunkTotal", c.chunkTotal());
        m.put("startChar", c.startChar());
        m.put("endChar", c.endChar());
        m.put("score", c.score());
        m.put("excerpt", c.excerpt());
        m.put("startLine", c.startLine());
        m.put("endLine", c.endLine());
        m.put("headingText", c.headingText());
        m.put("headingLevel", c.headingLevel());
        citationMaps.add(Map.copyOf(m));
      }
      events.add(new SseEvent("rag.citations", Map.of("citations", citationMaps)));
    }

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put(
        "content", "Documents:\n" + truncation.content() + "\n\nQuestion: " + question);
    return InjectorResult.of(List.of(message), events);
  }

  /**
   * Tempdoc 806 B.2 — the outcome of ONE retrieval attempt, keeping "ran and returned nothing" apart
   * from "never completed". Collapsing the two into a bare {@code null} is what let an unfinished call
   * be reported to the user as a fact about their corpus.
   *
   * @param result the retrieval result, or {@code null} when the attempt did not complete
   * @param timedOut the attempt exceeded its budget (this instance's timeout, or the Worker RPC deadline)
   * @param failed the attempt did not complete for any reason, timeout included
   */
  private record RetrievalAttempt(ContextResult result, boolean timedOut, boolean failed) {
    static RetrievalAttempt ok(ContextResult result) {
      return new RetrievalAttempt(result, false, false);
    }

    static RetrievalAttempt from(Exception e) {
      return new RetrievalAttempt(null, isDeadline(e), true);
    }
  }

  /** True when a throwable (or any cause) is a local budget expiry or a gRPC DEADLINE_EXCEEDED. */
  private static boolean isDeadline(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof java.util.concurrent.TimeoutException) {
        return true;
      }
      String message = c.getMessage();
      if (message != null && message.contains("DEADLINE_EXCEEDED")) {
        return true;
      }
    }
    return false;
  }

  private RetrievalAttempt tryRetrieveContext(
      String question, Set<String> docIdSet, int topK, List<String> excludedSourceIds) {
    try {
      // Tempdoc 610 §J.3 — go through the rich params path so the hidden-source exclusion threads to
      // the Worker. maxContextTokens=0 preserves the scoped path's char-budget behavior.
      RetrieveContextParams params =
          RetrieveContextParams.of(question, topK, 0, docIdSet, excludedSourceIds);
      return RetrievalAttempt.ok(
          documents
              .retrieveContext(params)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      LOG.warn("RAGContext: scoped retrieveContext failed; will fall back to batch fetch", e);
      return RetrievalAttempt.from(e);
    }
  }

  private RetrievalAttempt tryOpenRetrieval(
      String question, int topK, List<String> excludedSourceIds) {
    try {
      int budgetTokens = TokenEstimation.computeSafeInputBudgetTokens(8192, 1024);
      RetrieveContextParams params =
          RetrieveContextParams.of(question, topK, budgetTokens, Set.of(), excludedSourceIds);
      return RetrievalAttempt.ok(
          documents
              .retrieveContext(params)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (Exception e) {
      LOG.warn("RAGContext: open-retrieval failed (no docIds, pre-search path)", e);
      return RetrievalAttempt.from(e);
    }
  }

  /** Tempdoc 610 §J.3 — drop docIds whose parentDocId the user hid (parsed from the unit-sep ids). */
  private static List<String> dropExcludedParentDocs(
      List<String> docIds, List<String> excludedSourceIds) {
    if (excludedSourceIds.isEmpty() || docIds.isEmpty()) {
      return docIds;
    }
    Set<String> excludedParents = new HashSet<>();
    for (String id : excludedSourceIds) {
      int sep = id.lastIndexOf((char) 0x1F);
      excludedParents.add(sep > 0 ? id.substring(0, sep) : id);
    }
    List<String> kept = new ArrayList<>(docIds.size());
    for (String d : docIds) {
      if (!excludedParents.contains(d)) {
        kept.add(d);
      }
    }
    return kept;
  }

  /** Tempdoc 610 §J.3 — the hidden-source ids the engine seeded onto the context (never null). */
  private static List<String> excludedSourcesFrom(ConversationContext ctx) {
    Object raw = ctx.attributes().get(ATTR_EXCLUDED_SOURCES);
    if (raw instanceof List<?> l) {
      List<String> out = new ArrayList<>(l.size());
      for (Object o : l) {
        if (o instanceof String s && !s.isBlank()) {
          out.add(s);
        }
      }
      return out;
    }
    return List.of();
  }

  private String fetchBatchFallback(List<String> docIds) {
    try {
      Map<String, DocumentRecord> docs =
          documents
              .fetchBatch(docIds)
              .toCompletableFuture()
              .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return formatDocuments(docs, docIds);
    } catch (Exception e) {
      LOG.warn("RAGContext: fetchBatch fallback failed for {} docIds", docIds.size(), e);
      return null;
    }
  }

  private static String formatDocuments(Map<String, DocumentRecord> docs, List<String> docIds) {
    StringBuilder sb = new StringBuilder();
    for (String docId : docIds) {
      DocumentRecord record = docs.get(docId);
      if (record == null || record.content() == null || record.content().isBlank()) {
        continue;
      }
      sb.append("--- File: ")
          .append(DocumentTypeDetector.extractFilename(docId))
          .append(" ---\n");
      sb.append(record.content()).append("\n\n");
    }
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractDocIds(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("docIds");
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(Objects::nonNull)
        .map(Object::toString)
        .filter(s -> !s.isBlank())
        .collect(Collectors.toUnmodifiableList());
  }

  private int extractTopK(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("topK");
    if (raw instanceof Number n) {
      int v = n.intValue();
      return v > 0 ? v : defaultTopK;
    }
    return defaultTopK;
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  private static SseEvent errorEvent(String message, String code) {
    return new SseEvent("error", errorPayload(message, code));
  }

  private static Map<String, Object> errorPayload(String message, String code) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("error", message);
    p.put("errorCode", code);
    p.put("i18nKey", "errors." + code);
    return p;
  }
}
