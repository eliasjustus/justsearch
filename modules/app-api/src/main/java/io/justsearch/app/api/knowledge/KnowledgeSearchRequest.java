/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * Search request for the Knowledge Server-backed HTTP API (e.g., POST /api/knowledge/search).
 *
 * <p>Stability: stable (API contract)
 */
public record KnowledgeSearchRequest(
    String query,
    Integer limit,
    String mode,
    String sort,
    String cursor,
    List<String> projection,
    Filters filters,
    Filters boostFilters,
    Facets facets,
    String querySyntax,
    Boolean includeExcerpts,
    Boolean debug,
    PipelineConfig pipeline) {

  public KnowledgeSearchRequest {
    projection = projection == null ? List.of() : List.copyOf(projection);
    querySyntax = querySyntax == null || querySyntax.isBlank() ? null : querySyntax.trim();
  }

  @RecordBuilder
  public static record Filters(
      List<String> mime,
      List<String> language,
      List<String> fileKind,
      List<String> mimeBase,
      String pathPrefix,
      Boolean includeChunks,
      TimeRangeMs modifiedAt,
      List<String> entityPersons,
      List<String> entityOrganizations,
      List<String> entityLocations,
      List<String> metaSource,
      List<String> metaAuthor,
      List<String> metaCategory,
      TimeRangeMs metaPublishedAt,
      List<String> docIds,
      // Tempdoc 585 §D Phase 4 (D4b) — scope search to Lucene collection tag(s) (e.g. "agent-history").
      List<String> collection) {
    public Filters {
      mime = mime == null ? List.of() : List.copyOf(mime);
      language = language == null ? List.of() : List.copyOf(language);
      fileKind = fileKind == null ? List.of() : List.copyOf(fileKind);
      mimeBase = mimeBase == null ? List.of() : List.copyOf(mimeBase);
      entityPersons = entityPersons == null ? List.of() : List.copyOf(entityPersons);
      entityOrganizations = entityOrganizations == null ? List.of() : List.copyOf(entityOrganizations);
      entityLocations = entityLocations == null ? List.of() : List.copyOf(entityLocations);
      metaSource = metaSource == null ? List.of() : List.copyOf(metaSource);
      metaAuthor = metaAuthor == null ? List.of() : List.copyOf(metaAuthor);
      metaCategory = metaCategory == null ? List.of() : List.copyOf(metaCategory);
      docIds = docIds == null ? List.of() : List.copyOf(docIds);
      collection = collection == null ? List.of() : List.copyOf(collection);
    }
  }

  public static record TimeRangeMs(Long fromMs, Long toMs) {}

  public static record Facets(Boolean include, Integer maxDocsScanned, List<FieldSpec> fields) {
    public Facets {
      fields = fields == null ? List.of() : List.copyOf(fields);
    }
  }

  public static record FieldSpec(String field, Integer size) {}
}
