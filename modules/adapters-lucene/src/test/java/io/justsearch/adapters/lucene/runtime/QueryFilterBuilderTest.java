package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import java.util.List;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QueryFilterBuilder#buildChunkFilterQuery} which builds Lucene filter queries
 * applicable to chunk documents (only mime, fileKind, mimeBase, language — skipping pathPrefix,
 * modifiedAt range, entity fields, and IS_CHUNK exclusion).
 */
final class QueryFilterBuilderTest {

  @Test
  void buildChunkFilterQuery_nullFiltersReturnsNull() {
    assertNull(QueryFilterBuilder.buildChunkFilterQuery(null));
  }

  @Test
  void buildChunkFilterQuery_emptyFiltersReturnsNull() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of()).language(List.of()).fileKind(List.of()).mimeBase(List.of())
            .build();
    assertNull(QueryFilterBuilder.buildChunkFilterQuery(filters));
  }

  @Test
  void buildChunkFilterQuery_mimeFilterProducesQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of("application/pdf"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for mime");
  }

  @Test
  void buildChunkFilterQuery_languageFilterProducesQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .language(List.of("en"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for language");
  }

  @Test
  void buildChunkFilterQuery_fileKindFilterProducesQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .fileKind(List.of("document"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for fileKind");
  }

  @Test
  void buildChunkFilterQuery_multipleFiltersProducesCombinedQuery() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of("text/plain"))
            .language(List.of("de"))
            .fileKind(List.of("document"))
            .mimeBase(List.of("text"))
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce combined filter");
    // Should be a BooleanQuery with 4 FILTER clauses
    BooleanQuery bq = (BooleanQuery) q;
    assertEquals(4, bq.clauses().size(), "4 filter clauses: mime, language, fileKind, mimeBase");
  }

  @Test
  void buildChunkFilterQuery_ignoresPathPrefixAndDateRange() {
    // pathPrefix and modifiedAt range are NOT stored on chunks — should be ignored
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .pathPrefix("/some/path")
            .modifiedFromMs(1000L)
            .modifiedToMs(2000L)
            .build();
    assertNull(
        QueryFilterBuilder.buildChunkFilterQuery(filters),
        "pathPrefix and date range should not produce chunk filter");
  }

  @Test
  void buildChunkFilterQuery_ignoresEntityFilters() {
    // Entity filters are NOT stored on chunks — should be ignored
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .entityPersons(List.of("Alice"))
            .entityOrganizations(List.of("Acme Corp"))
            .entityLocations(List.of("Berlin"))
            .build();
    assertNull(
        QueryFilterBuilder.buildChunkFilterQuery(filters),
        "Entity filters should not produce chunk filter");
  }

  @Test
  void buildChunkFilterQuery_ignoresMetadataFilters() {
    // Metadata filters are NOT stored on chunks — should be ignored
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .metaSource(List.of("the verge"))
            .metaAuthor(List.of("stan choe"))
            .metaCategory(List.of("tech"))
            .build();
    assertNull(
        QueryFilterBuilder.buildChunkFilterQuery(filters),
        "Metadata filters should not produce chunk filter");
  }

  @Test
  void buildFilterQueryOnly_includesMetadataTermFilters() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .metaSource(List.of("the verge"))
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "Should produce a filter for meta_source");
  }

  @Test
  void buildFilterQueryOnly_includesMetadataDateRange() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .metaPublishedFromMs(1000L)
            .metaPublishedToMs(2000L)
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "Should produce a filter for meta_published_at range");
  }

  @Test
  void buildChunkFilterQuery_mimeFilterWithPathPrefixOnlyAppliesMime() {
    // Mixed: mime is applicable to chunks, pathPrefix is not
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(List.of("application/pdf"))
            .pathPrefix("/ignored/path")
            .build();
    Query q = QueryFilterBuilder.buildChunkFilterQuery(filters);
    assertNotNull(q, "Should produce a filter for the mime part");
    BooleanQuery bq = (BooleanQuery) q;
    assertEquals(1, bq.clauses().size(), "Only mime clause, pathPrefix ignored");
  }

  // ---- doc_ids filter tests (366 Phase 6) ----

  @Test
  void applyRuntimeFilters_docIdsAddsTermFilter() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(List.of("/path/to/doc1.txt", "/path/to/doc2.txt"))
            .build();
    Query q = QueryFilterBuilder.applyRuntimeFilters(
        new org.apache.lucene.search.MatchAllDocsQuery(), filters);
    assertNotNull(q);
    String queryStr = q.toString();
    assertTrue(queryStr.contains("/path/to/doc1.txt") || queryStr.contains("path"),
        "doc_ids should produce PATH filter clauses: " + queryStr);
  }

  @Test
  void buildFilterQueryOnly_docIdsProducesFilter() {
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .docIds(List.of("/single/doc.pdf"))
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "doc_ids alone should produce a filter");
  }

  // ---- Soft-boost filter tests (363) ----

  private static RuntimeSearchFilters boostWithSource(String... sources) {
    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .metaSource(List.of(sources))
        .build();
  }

  @Test
  void applyBoostFilters_nullFiltersAddsNoClauses() {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    QueryFilterBuilder.applyBoostFilters(qb, null, 0.5f);
    BooleanQuery bq = qb.build();
    assertEquals(1, bq.clauses().size(), "Only the MUST clause, no boost added");
  }

  @Test
  void applyBoostFilters_metaSourceAddsShouldClause() {
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    QueryFilterBuilder.applyBoostFilters(qb, boostWithSource("the verge"), 0.5f);
    BooleanQuery bq = qb.build();
    assertEquals(2, bq.clauses().size(), "MUST + one SHOULD boost");
    BooleanClause boostClause = bq.clauses().get(1);
    assertEquals(BooleanClause.Occur.SHOULD, boostClause.occur(), "Boost is SHOULD, not FILTER");
    assertTrue(boostClause.query() instanceof org.apache.lucene.search.BoostQuery,
        "Wrapped in BoostQuery");
  }

  @Test
  void applyBoostFilters_multipleFieldsProduceMultipleShouldClauses() {
    RuntimeSearchFilters boostFilters = LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .entityPersons(List.of("elon musk"))
        .metaSource(List.of("the verge"))
        .metaCategory(List.of("technology"))
        .build();
    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(new org.apache.lucene.search.MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    QueryFilterBuilder.applyBoostFilters(qb, boostFilters, 0.5f);
    BooleanQuery bq = qb.build();
    assertEquals(4, bq.clauses().size(), "MUST + 3 SHOULD boost clauses");
    long shouldCount = bq.clauses().stream()
        .filter(c -> c.occur() == BooleanClause.Occur.SHOULD)
        .count();
    assertEquals(3, shouldCount, "3 SHOULD clauses for 3 boost fields");
  }

  // ===== Tempdoc 585 §D Phase 4 (D4b) — the collection scope =====

  @Test
  void buildFilterQueryOnly_defaultExcludesAgentHistory() {
    // No explicit collection scope ⇒ the reserved agent-history collection is excluded by default
    // (a MUST_NOT), so indexed run transcripts never pollute normal document search.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q, "the default exclusion always produces a filter");
    String s = q.toString();
    // Lucene renders a MUST_NOT clause with a leading '-'.
    assertTrue(s.contains("-collection:agent-history"), "agent-history is MUST_NOT excluded: " + s);
    // And the pure-negative query is anchored so it does not match nothing.
    assertTrue(s.contains("*:*") || s.contains("MatchAllDocs"), "anchored with MatchAllDocs: " + s);
  }

  @Test
  void buildFilterQueryOnly_explicitAgentHistoryScopeIncludesIt() {
    // Scoping to the agent-history collection (the "Agent history" search scope) INCLUDES it (a
    // FILTER clause) and does NOT also exclude it.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .includeChunks(true)
            .collection(List.of("agent-history"))
            .build();
    Query q = QueryFilterBuilder.buildFilterQueryOnly(filters);
    assertNotNull(q);
    String s = q.toString();
    assertTrue(s.contains("collection:agent-history"), "agent-history is included: " + s);
    assertTrue(!s.contains("-collection:agent-history"), "not also excluded: " + s);
  }

  @Test
  void applyRuntimeFilters_defaultExcludesAgentHistory() {
    // The keyword-search path (content query + filters) also excludes agent-history by default.
    RuntimeSearchFilters filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
    Query content = new org.apache.lucene.search.MatchAllDocsQuery();
    Query q = QueryFilterBuilder.applyRuntimeFilters(content, filters);
    assertTrue(q.toString().contains("-collection:agent-history"), "excluded: " + q);
  }
}
