/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Builds Lucene filter queries from {@link RuntimeSearchFilters}.
 *
 * <p>This class is responsible for constructing filter queries that apply structured filters
 * (mime, language, file kind, path prefix, modified date range) and chunk exclusion logic.
 *
 * <p>All methods are pure query-building operations with no state dependencies.
 */
public final class QueryFilterBuilder {

  /** Default boost weight for soft-boost filters (363). Calibrated by weight sweep (w=20). */
  public static final float DEFAULT_BOOST_WEIGHT = 20.0f;

  private QueryFilterBuilder() {
    // Utility class - no instantiation
  }

  /**
   * Normalizes a path prefix for platform-consistent path filtering.
   *
   * <p>Converts forward slashes to platform separators, lowercases on Windows,
   * and ensures trailing separator.
   *
   * @param path the path prefix to normalize (may be null)
   * @return normalized path, or null if input is null
   */
  public static String normalizePathPrefix(String path) {
    if (path == null) return null;
    String normalized = path.replace('/', java.io.File.separatorChar);
    boolean isWindows = io.justsearch.configuration.PlatformPaths.isWindows();
    if (isWindows) {
      normalized = normalized.toLowerCase(java.util.Locale.ROOT);
    }
    if (!normalized.endsWith(java.io.File.separator)) {
      normalized = normalized + java.io.File.separator;
    }
    return normalized;
  }

  /**
   * Adds an OR filter for a list of term values to the query builder.
   *
   * <p>Builds a disjunction (OR) of TermQueries for each non-blank value in the list
   * and adds it as a FILTER clause to the builder.
   *
   * @param qb the query builder to add the filter to
   * @param values the list of values to match (may be null or empty)
   * @param fieldName the Lucene field name to match against
   * @return true if a filter was added, false if values was null/empty or all values were blank
   */
  private static boolean addTermOrFilter(
      BooleanQuery.Builder qb, List<String> values, String fieldName) {
    if (values == null || values.isEmpty()) {
      return false;
    }
    BooleanQuery.Builder or = new BooleanQuery.Builder();
    for (String v : values) {
      if (v == null || v.isBlank()) continue;
      or.add(new TermQuery(new Term(fieldName, v)), BooleanClause.Occur.SHOULD);
    }
    BooleanQuery q = or.build();
    if (q.clauses().isEmpty()) {
      return false;
    }
    qb.add(q, BooleanClause.Occur.FILTER);
    return true;
  }

  /**
   * Tempdoc 585 §D Phase 4 (D4b) — apply the collection scope. An EXPLICIT scope (non-empty
   * {@code collections}) includes ONLY those collections (the "Agent history" scope passes
   * {@code ["agent-history"]}); the DEFAULT (empty) excludes the reserved {@code agent-history}
   * collection with a {@code MUST_NOT}, so indexed run transcripts never pollute normal document
   * search. The {@code MUST_NOT} only matches docs that carry the agent-history tag — untagged docs
   * (no collection field) pass through. Always contributes a clause, so returns true.
   */
  private static boolean addCollectionScope(BooleanQuery.Builder qb, List<String> collections) {
    if (collections != null && !collections.isEmpty()) {
      // An explicit scope is a POSITIVE include filter.
      return addTermOrFilter(qb, collections, SchemaFields.COLLECTION);
    }
    // The default exclusion is a MUST_NOT — NOT a positive filter: like the chunk exclusion it needs
    // a MatchAllDocs anchor when no positive filter is present (a pure-negative query matches none),
    // so it returns false (does not set hasFilters).
    qb.add(
        new TermQuery(new Term(SchemaFields.COLLECTION, SchemaFields.AGENT_HISTORY_COLLECTION)),
        BooleanClause.Occur.MUST_NOT);
    return false;
  }

  /**
   * Adds an IndexOrDocValuesQuery range filter for a long field.
   * Uses LongPoint (BKD-tree) when driving iteration, DocValues for verification.
   */
  private static boolean addLongRangeFilter(
      BooleanQuery.Builder qb, Long fromMs, Long toMs, String field) {
    long from = fromMs == null ? 0L : fromMs;
    long to = toMs == null ? 0L : toMs;
    if (from <= 0 && to <= 0) return false;
    long min = from > 0 ? from : Long.MIN_VALUE;
    long max = to > 0 ? to : Long.MAX_VALUE;
    if (min > max) { long tmp = min; min = max; max = tmp; }
    qb.add(new IndexOrDocValuesQuery(
        LongPoint.newRangeQuery(field, min, max),
        NumericDocValuesField.newSlowRangeQuery(field, min, max)),
        BooleanClause.Occur.FILTER);
    return true;
  }

  /**
   * Applies runtime filters to a content query.
   *
   * <p>Combines the content query with structured filters (mime, language, file kind, etc.)
   * and applies default chunk exclusion unless explicitly requested.
   *
   * @param contentQuery the base content query (must not be null)
   * @param filters optional structured filters (may be null)
   * @return combined query with filters applied, or null if contentQuery is null
   */
  public static Query applyRuntimeFilters(Query contentQuery, RuntimeSearchFilters filters) {
    if (contentQuery == null) {
      return null;
    }

    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(contentQuery, BooleanClause.Occur.MUST);

    // Always exclude chunks by default unless explicitly requested.
    // This prevents opaque chunk doc IDs (chunk:<uuid>) from leaking into normal search results.
    boolean includeChunks = filters != null && filters.includeChunks();
    if (!includeChunks) {
      qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
    }

    // If no filters, we still needed to apply chunk exclusion above, so return early now.
    if (filters == null) {
      return qb.build();
    }

    // Term-based filters: mime, file_kind, mime_base, language
    addTermOrFilter(qb, filters.mime(), SchemaFields.MIME);
    addTermOrFilter(qb, filters.fileKind(), SchemaFields.FILE_KIND);
    addTermOrFilter(qb, filters.mimeBase(), SchemaFields.MIME_BASE);
    addTermOrFilter(qb, filters.language(), SchemaFields.LANGUAGE);

    // Tempdoc 585 §D Phase 4 (D4b) — collection scope: an explicit scope includes only those
    // collections; the default excludes the reserved agent-history collection so transcripts don't
    // pollute normal document search.
    addCollectionScope(qb, filters.collection());

    // Entity filters (NER-extracted values)
    addTermOrFilter(qb, filters.entityPersons(), SchemaFields.ENTITY_PERSONS_RAW);
    addTermOrFilter(qb, filters.entityOrganizations(), SchemaFields.ENTITY_ORGANIZATIONS_RAW);
    addTermOrFilter(qb, filters.entityLocations(), SchemaFields.ENTITY_LOCATIONS_RAW);

    // Metadata filters (frontmatter-extracted, lowercased at index+query time)
    addTermOrFilter(qb, filters.metaSource(), SchemaFields.META_SOURCE);
    addTermOrFilter(qb, filters.metaAuthor(), SchemaFields.META_AUTHOR);
    addTermOrFilter(qb, filters.metaCategory(), SchemaFields.META_CATEGORY);

    // path prefix filter
    if (filters.pathPrefix() != null && !filters.pathPrefix().isBlank()) {
      String normalized = normalizePathPrefix(filters.pathPrefix());
      qb.add(new PrefixQuery(new Term(SchemaFields.PATH, normalized)), BooleanClause.Occur.FILTER);
    }

    // doc_ids exact match filter (scoped search to specific documents)
    addTermOrFilter(qb, filters.docIds(), SchemaFields.PATH);

    // modified_at range filter (IndexOrDocValuesQuery for optimal performance)
    addLongRangeFilter(qb, filters.modifiedFromMs(), filters.modifiedToMs(), SchemaFields.MODIFIED_AT);

    // meta_published_at range filter
    addLongRangeFilter(qb, filters.metaPublishedFromMs(), filters.metaPublishedToMs(),
        SchemaFields.META_PUBLISHED_AT);

    return qb.build();
  }

  /**
   * Builds a filter-only query (no content query) from structured filters.
   *
   * <p>Used by VECTOR and HYBRID search modes to apply user filters and chunk exclusion.
   *
   * @param filters optional structured filters (may be null)
   * @return filter query, or null if no filtering is needed
   */
  public static Query buildFilterQueryOnly(RuntimeSearchFilters filters) {
    // If no filters provided, still need to exclude chunks by default
    boolean includeChunks = filters != null && filters.includeChunks();

    // If filters is null and we need to exclude chunks, build minimal filter
    if (filters == null) {
      if (!includeChunks) {
        // Exclude chunks by default
        return new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT)
            .build();
      }
      return null;  // No filtering needed
    }

    // Build filter using same logic as applyRuntimeFilters but without content query
    BooleanQuery.Builder qb = new BooleanQuery.Builder();

    // Always exclude chunks unless explicitly requested
    if (!includeChunks) {
      qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
    }

    // Term-based filters: mime, file_kind, mime_base, language
    boolean hasFilters = false;
    hasFilters |= addTermOrFilter(qb, filters.mime(), SchemaFields.MIME);
    hasFilters |= addTermOrFilter(qb, filters.fileKind(), SchemaFields.FILE_KIND);
    hasFilters |= addTermOrFilter(qb, filters.mimeBase(), SchemaFields.MIME_BASE);
    hasFilters |= addTermOrFilter(qb, filters.language(), SchemaFields.LANGUAGE);

    // Tempdoc 585 §D Phase 4 (D4b) — collection scope (default-excludes the reserved agent-history).
    hasFilters |= addCollectionScope(qb, filters.collection());

    // Entity filters (NER-extracted values)
    hasFilters |= addTermOrFilter(qb, filters.entityPersons(), SchemaFields.ENTITY_PERSONS_RAW);
    hasFilters |=
        addTermOrFilter(qb, filters.entityOrganizations(), SchemaFields.ENTITY_ORGANIZATIONS_RAW);
    hasFilters |= addTermOrFilter(qb, filters.entityLocations(), SchemaFields.ENTITY_LOCATIONS_RAW);

    // Metadata filters (frontmatter-extracted, lowercased at index+query time)
    hasFilters |= addTermOrFilter(qb, filters.metaSource(), SchemaFields.META_SOURCE);
    hasFilters |= addTermOrFilter(qb, filters.metaAuthor(), SchemaFields.META_AUTHOR);
    hasFilters |= addTermOrFilter(qb, filters.metaCategory(), SchemaFields.META_CATEGORY);

    // path prefix filter
    if (filters.pathPrefix() != null && !filters.pathPrefix().isBlank()) {
      String normalized = normalizePathPrefix(filters.pathPrefix());
      qb.add(new PrefixQuery(new Term(SchemaFields.PATH, normalized)), BooleanClause.Occur.FILTER);
      hasFilters = true;
    }

    // doc_ids exact match filter (scoped search to specific documents)
    hasFilters |= addTermOrFilter(qb, filters.docIds(), SchemaFields.PATH);

    // modified_at range filter (IndexOrDocValuesQuery for optimal performance)
    hasFilters |= addLongRangeFilter(qb, filters.modifiedFromMs(), filters.modifiedToMs(),
        SchemaFields.MODIFIED_AT);

    // meta_published_at range filter
    hasFilters |= addLongRangeFilter(qb, filters.metaPublishedFromMs(),
        filters.metaPublishedToMs(), SchemaFields.META_PUBLISHED_AT);

    // No positive include-filters: qb may hold only MUST_NOT exclusions (the chunk exclusion and/or
    // the tempdoc-585-D4b default agent-history exclusion). A pure-negative BooleanQuery matches
    // nothing, so anchor it with MatchAllDocs. If qb is genuinely empty (no exclusions either —
    // includeChunks AND no collection exclusion), no filtering is needed.
    if (!hasFilters) {
      if (qb.build().clauses().isEmpty()) {
        return null;
      }
      qb.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    }

    return qb.build();
  }

  /**
   * Builds a filter query for chunk search from structured filters.
   *
   * <p>Only applies filters that are stored on chunk documents: mime, fileKind, mimeBase, language.
   * Skips: IS_CHUNK exclusion (chunks are the target), pathPrefix (PATH on chunks stores
   * parentDocId, not the file path), modifiedAt range (not stored on chunks), and entity filters
   * (not stored on chunks).
   *
   * @param filters optional structured filters (may be null)
   * @return filter query for chunk search, or null if no applicable filters exist
   */
  public static Query buildChunkFilterQuery(RuntimeSearchFilters filters) {
    if (filters == null) {
      return null;
    }

    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    boolean hasClause = false;
    hasClause |= addTermOrFilter(qb, filters.mime(), SchemaFields.MIME);
    hasClause |= addTermOrFilter(qb, filters.fileKind(), SchemaFields.FILE_KIND);
    hasClause |= addTermOrFilter(qb, filters.mimeBase(), SchemaFields.MIME_BASE);
    hasClause |= addTermOrFilter(qb, filters.language(), SchemaFields.LANGUAGE);

    return hasClause ? qb.build() : null;
  }

  // ---- Soft-boost filters (363: query understanding) ----

  /**
   * Adds an OR-of-terms as a SHOULD boost clause (not a hard filter). Matching documents get a
   * fixed score bump equal to {@code weight}. Non-matching documents are NOT excluded. Uses {@link
   * ConstantScoreQuery} to avoid IDF variation across keyword fields.
   */
  private static boolean addTermOrBoost(
      BooleanQuery.Builder qb, List<String> values, String fieldName, float weight) {
    if (values == null || values.isEmpty()) {
      return false;
    }
    BooleanQuery.Builder or = new BooleanQuery.Builder();
    for (String v : values) {
      if (v == null || v.isBlank()) continue;
      or.add(new TermQuery(new Term(fieldName, v)), BooleanClause.Occur.SHOULD);
    }
    BooleanQuery q = or.build();
    if (q.clauses().isEmpty()) {
      return false;
    }
    qb.add(new BoostQuery(new ConstantScoreQuery(q), weight), BooleanClause.Occur.SHOULD);
    return true;
  }

  /**
   * Applies soft-boost filters to an existing query builder.
   *
   * <p>Unlike {@link #applyRuntimeFilters}, boost filters use {@link BooleanClause.Occur#SHOULD}
   * instead of {@link BooleanClause.Occur#FILTER}. Matching documents score higher but non-matching
   * documents are NOT excluded from results. This preserves recall while improving precision.
   *
   * @param qb the query builder to add boost clauses to (must already have a MUST content query)
   * @param boostFilters the structured boost filter values (may be null)
   * @param weight the boost weight (added to the content score for matching documents)
   */
  public static void applyBoostFilters(
      BooleanQuery.Builder qb, RuntimeSearchFilters boostFilters, float weight) {
    if (boostFilters == null) {
      return;
    }
    addTermOrBoost(qb, boostFilters.entityPersons(), SchemaFields.ENTITY_PERSONS_RAW, weight);
    addTermOrBoost(
        qb, boostFilters.entityOrganizations(), SchemaFields.ENTITY_ORGANIZATIONS_RAW, weight);
    addTermOrBoost(qb, boostFilters.entityLocations(), SchemaFields.ENTITY_LOCATIONS_RAW, weight);
    addTermOrBoost(qb, boostFilters.metaSource(), SchemaFields.META_SOURCE, weight);
    addTermOrBoost(qb, boostFilters.metaAuthor(), SchemaFields.META_AUTHOR, weight);
    addTermOrBoost(qb, boostFilters.metaCategory(), SchemaFields.META_CATEGORY, weight);

    // Date range boost (363): documents within the extracted date range get a score bump.
    addLongRangeBoost(
        qb,
        boostFilters.metaPublishedFromMs(),
        boostFilters.metaPublishedToMs(),
        SchemaFields.META_PUBLISHED_AT,
        weight);
  }

  /**
   * Adds a date range as a SHOULD boost clause. Documents within the range get a fixed score bump.
   * Uses the same IndexOrDocValuesQuery pattern as {@link #addLongRangeFilter} but wrapped in
   * BoostQuery + ConstantScoreQuery to avoid score variation.
   */
  private static boolean addLongRangeBoost(
      BooleanQuery.Builder qb, Long fromMs, Long toMs, String field, float weight) {
    long from = fromMs == null ? 0L : fromMs;
    long to = toMs == null ? 0L : toMs;
    if (from <= 0 && to <= 0) return false;
    long min = from > 0 ? from : Long.MIN_VALUE;
    long max = to > 0 ? to : Long.MAX_VALUE;
    if (min > max) {
      long tmp = min;
      min = max;
      max = tmp;
    }
    Query rangeQuery =
        new IndexOrDocValuesQuery(
            LongPoint.newRangeQuery(field, min, max),
            NumericDocValuesField.newSlowRangeQuery(field, min, max));
    qb.add(new BoostQuery(new ConstantScoreQuery(rangeQuery), weight), BooleanClause.Occur.SHOULD);
    return true;
  }
}
