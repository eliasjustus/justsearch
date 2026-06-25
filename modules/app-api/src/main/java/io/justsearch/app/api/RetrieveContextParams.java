/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.List;
import java.util.Set;

/**
 * Parameters for RAG context retrieval, supporting entity/temporal/content filters
 * and context format selection.
 *
 * <p>This is the rich alternative to the positional-parameter {@link
 * DocumentService#retrieveContextWithMeta} methods. New callers (REST API, MCP tools)
 * should use this; existing callers can migrate incrementally.
 */
public record RetrieveContextParams(
    String question,
    Set<String> docIds,
    int topK,
    int maxContextTokens,
    List<String> entityPersons,
    List<String> entityOrganizations,
    List<String> entityLocations,
    TimeRange modifiedAt,
    boolean freshnessEnabled,
    String pathPrefix,
    List<String> fileKind,
    boolean autoEntityExtract,
    ContextFormat contextFormat,
    List<String> metaSource,
    List<String> metaAuthor,
    List<String> metaCategory,
    TimeRange metaPublishedAt,
    boolean returnFullDocuments,
    List<String> excludedSourceIds) {

  /** Time range filter (inclusive bounds, epoch millis). */
  public record TimeRange(long fromMs, long toMs) {
    public static final TimeRange UNSET = new TimeRange(0, 0);

    public boolean isSet() {
      return fromMs > 0 || toMs > 0;
    }
  }

  /** Format for assembled RAG context string. */
  public enum ContextFormat {
    LABELED,
    XML,
    PLAIN
  }

  /** Compact constructor with defaults. */
  public RetrieveContextParams {
    question = question == null ? "" : question;
    docIds = docIds == null ? Set.of() : Set.copyOf(docIds);
    topK = topK <= 0 ? 5 : Math.min(topK, 20);
    entityPersons = entityPersons == null ? List.of() : List.copyOf(entityPersons);
    entityOrganizations = entityOrganizations == null ? List.of() : List.copyOf(entityOrganizations);
    entityLocations = entityLocations == null ? List.of() : List.copyOf(entityLocations);
    modifiedAt = modifiedAt == null ? TimeRange.UNSET : modifiedAt;
    pathPrefix = pathPrefix == null ? "" : pathPrefix;
    fileKind = fileKind == null ? List.of() : List.copyOf(fileKind);
    contextFormat = contextFormat == null ? ContextFormat.LABELED : contextFormat;
    metaSource = metaSource == null ? List.of() : List.copyOf(metaSource);
    metaAuthor = metaAuthor == null ? List.of() : List.copyOf(metaAuthor);
    metaCategory = metaCategory == null ? List.of() : List.copyOf(metaCategory);
    metaPublishedAt = metaPublishedAt == null ? TimeRange.UNSET : metaPublishedAt;
    excludedSourceIds = excludedSourceIds == null ? List.of() : List.copyOf(excludedSourceIds);
  }

  /** Minimal builder for the common case: question + token budget. */
  public static RetrieveContextParams of(String question, int topK, int maxContextTokens) {
    return new RetrieveContextParams(
        question, Set.of(), topK, maxContextTokens,
        List.of(), List.of(), List.of(),
        TimeRange.UNSET, false, "", List.of(),
        false, ContextFormat.LABELED,
        List.of(), List.of(), List.of(), TimeRange.UNSET, false, List.of());
  }

  /**
   * Tempdoc 610 §J.3 — builder for the chat RAG path: question + token budget + (optional) scoped
   * docIds + the hidden-source exclusion set.
   */
  public static RetrieveContextParams of(
      String question, int topK, int maxContextTokens,
      Set<String> docIds, List<String> excludedSourceIds) {
    return new RetrieveContextParams(
        question, docIds, topK, maxContextTokens,
        List.of(), List.of(), List.of(),
        TimeRange.UNSET, false, "", List.of(),
        false, ContextFormat.LABELED,
        List.of(), List.of(), List.of(), TimeRange.UNSET, false, excludedSourceIds);
  }

  /** Builder from the existing positional parameters (backward compatibility). */
  public static RetrieveContextParams fromLegacy(
      String question, Set<String> docIds, int topK, int maxContextTokens) {
    return new RetrieveContextParams(
        question, docIds, topK, maxContextTokens,
        List.of(), List.of(), List.of(),
        TimeRange.UNSET, false, "", List.of(),
        false, ContextFormat.LABELED,
        List.of(), List.of(), List.of(), TimeRange.UNSET, false, List.of());
  }
}
