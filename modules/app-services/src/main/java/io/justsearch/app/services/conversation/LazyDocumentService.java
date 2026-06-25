/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.RetrieveContextParams;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Late-resolving {@link DocumentService} proxy that delegates to a supplier on every call.
 *
 * <p>Solves the Worker late-binding problem: when the Head constructs SPI instances (RAGContext,
 * DocAccess, StreamingCitationMatcher) before the Worker is connected, a frozen
 * {@link DocumentService} reference stays unavailable even after the Worker reconnects.
 * This proxy resolves the current service on each call, so SPIs automatically start working
 * when the Worker late-binds.
 *
 * <p>Tempdoc 519 F2: when {@code delegate.get()} returns {@code null} (Worker not yet connected),
 * each method returns a failed {@link CompletionStage} with {@link UnavailableException} instead
 * of NPE. The Null Object {@code DocumentService.unavailable()} was previously the fallback;
 * F2 deleted it, so this proxy now owns the unavailable behavior for the Document SPI flow.
 */
public final class LazyDocumentService implements DocumentService {

  private final Supplier<DocumentService> delegate;

  public LazyDocumentService(Supplier<DocumentService> delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate supplier");
  }

  private <T> CompletionStage<T> resolve(Function<DocumentService, CompletionStage<T>> op) {
    DocumentService d = delegate.get();
    if (d == null) {
      return CompletableFuture.failedFuture(
          new UnavailableException("DocumentService unavailable (Worker not connected)"));
    }
    return op.apply(d);
  }

  @Override
  public CompletionStage<DocumentRecord> fetch(String docId) {
    return resolve(d -> d.fetch(docId));
  }

  @Override
  public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds) {
    return resolve(d -> d.fetchBatch(docIds));
  }

  @Override
  public CompletionStage<DocumentSlice> fetchSlice(String docId, int offsetChars, int maxChars) {
    return resolve(d -> d.fetchSlice(docId, offsetChars, maxChars));
  }

  @Override
  public CompletionStage<ContextResult> retrieveContextWithMeta(
      String question, Set<String> docIds, int topK) {
    return resolve(d -> d.retrieveContextWithMeta(question, docIds, topK));
  }

  @Override
  public CompletionStage<ContextResult> retrieveContextWithMeta(
      String question, Set<String> docIds, int topK, int maxContextTokens) {
    return resolve(d -> d.retrieveContextWithMeta(question, docIds, topK, maxContextTokens));
  }

  @Override
  public CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params) {
    return resolve(d -> d.retrieveContext(params));
  }

  @Override
  public CompletionStage<CitationMatchResult> matchCitations(
      String answerText, List<ContextCitation> citations, double threshold) {
    return resolve(d -> d.matchCitations(answerText, citations, threshold));
  }
}
