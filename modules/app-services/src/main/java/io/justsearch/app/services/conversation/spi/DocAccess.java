/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation.spi;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ContextInjector} that resolves a {@code docId} (and/or inline {@code content})
 * from the request body, loads the document text via {@link DocumentService}, and returns
 * a single user message containing the resolved text plus the summarize instruction.
 *
 * <p>Per tempdoc 491 §5.1 + §9 Phase C: lifts the doc-loading logic that lived in
 * {@code SummaryController#resolveContent}. The legacy controller's
 * {@code ContentResolution} record + truncation-by-character-count are inlined here for
 * Phase C; token-budget truncation is handled separately by the engine via the LLM
 * service's context window limits (or, when needed, by a sibling {@code TokenBudget}
 * injector that hard-truncates before the LLM call).
 *
 * <p>Request body schema (read via {@link ConversationContext#requestBody}): {@code docId}
 * (string, optional) — canonical document id to fetch; {@code content} (string, optional)
 * — inline content to summarize (used when no docId is supplied or fetch returns empty).
 *
 * <p>Stateful per-instance (holds the {@link DocumentService} reference + timeout), not
 * stateless — the engine constructs one instance per process.
 */
public final class DocAccess implements ContextInjector {

  private static final Logger LOG = LoggerFactory.getLogger(DocAccess.class);

  /** Stable id used by {@code ConversationShape.contextInjectorIds}. */
  public static final String ID = "core.doc-access";

  /** Default fetch timeout. Matches the legacy {@code SummaryController.timeout}. */
  static final Duration DEFAULT_FETCH_TIMEOUT = Duration.ofSeconds(10);

  /** Soft character cap on injected content — mirrors legacy {@code MAX_CONTENT_CHARS}. */
  static final int MAX_CONTENT_CHARS = 200_000;

  private final DocumentService documents;
  private final Duration fetchTimeout;

  public DocAccess(DocumentService documents) {
    this(documents, DEFAULT_FETCH_TIMEOUT);
  }

  public DocAccess(DocumentService documents, Duration fetchTimeout) {
    this.documents = Objects.requireNonNull(documents, "documents");
    this.fetchTimeout = Objects.requireNonNull(fetchTimeout, "fetchTimeout");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public InjectorResult inject(ConversationContext ctx) {
    Map<String, Object> body = ctx.requestBody();
    // Per tempdoc 526 §13 §13.4 R8 — positional body.startChar/endChar
    // fallback is retired. Selection-range summarize is the typed body.selection
    // path exclusively (handled by SelectionContextInjector); DocAccess is now
    // strictly the full-doc loader for shapes that don't ride selection
    // substrate. When a typed selection is present, defer to it (selection
    // injector emits the full request).
    if (body.get("selection") != null) {
      return InjectorResult.empty();
    }
    String docId = asString(body.get("docId"));
    String providedContent = asString(body.get("content"));

    String fullContent = resolveContent(docId, providedContent);
    if (fullContent == null || fullContent.isBlank()) {
      return InjectorResult.empty();
    }

    String truncated = fullContent.length() > MAX_CONTENT_CHARS
        ? fullContent.substring(0, MAX_CONTENT_CHARS)
        : fullContent;

    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", "Summarize the following document:\n\n" + truncated);

    return InjectorResult.messagesOnly(List.of(message));
  }

  /**
   * Resolve the document content. Prefers the documents service when a {@code docId} is
   * supplied; falls back to {@code providedContent} when the fetch returns nothing or the
   * service is unavailable.
   */
  private String resolveContent(String docId, String providedContent) {
    String fallback = providedContent == null ? "" : providedContent;
    if (docId == null || docId.isBlank()) {
      return fallback;
    }
    try {
      DocumentRecord record =
          documents.fetch(docId).toCompletableFuture().get(fetchTimeout.toMillis(), TimeUnit.MILLISECONDS);
      if (record != null && record.content() != null && !record.content().isBlank()) {
        return record.content();
      }
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof UnsupportedOperationException) {
        LOG.info("DocAccess: document service unavailable; falling back to provided content");
      } else {
        LOG.warn("DocAccess: document fetch failed for {}", docId, cause);
      }
    } catch (java.util.concurrent.TimeoutException e) {
      LOG.warn("DocAccess: document fetch timed out for {} after {}", docId, fetchTimeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("DocAccess: document fetch interrupted for {}", docId);
    } catch (UnsupportedOperationException e) {
      LOG.info("DocAccess: document service unavailable; falling back to provided content");
    } catch (RuntimeException e) {
      LOG.warn("DocAccess: document fetch failed for {}", docId, e);
    }
    return fallback;
  }

  private static String asString(Object o) {
    if (o == null) return null;
    return o.toString();
  }
}
