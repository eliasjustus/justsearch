/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.WildcardQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal autocomplete/suggest collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates the suggestion query construction and result extraction logic that was previously
 * inlined in the runtime facade.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
public final class SuggestOps {
  private static final Logger log = LoggerFactory.getLogger(SuggestOps.class);

  private final SearcherBridge bridge;

  SuggestOps(SearcherBridge bridge) {
    this.bridge = bridge;
  }

  /**
   * Returns autocomplete suggestions for the given prefix.
   *
   * <p>Builds a disjunctive query across content and title fields with prefix and infix matching.
   * Title matches are boosted above content matches. Results are deduplicated by filename/title.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   *
   * @param prefix the query prefix to match
   * @param limit maximum number of suggestions to return
   * @return list of suggestion strings (document filenames/titles)
   */
  public List<String> suggest(String prefix, int limit) {
    if (prefix == null || prefix.isBlank()) {
      return List.of();
    }
    final int effectiveLimit = limit <= 0 ? 5 : limit;

    String normalizedPrefix = prefix.trim().toLowerCase(Locale.ROOT);

    // Skip very short prefixes
    if (normalizedPrefix.length() < 2) {
      return List.of();
    }

    try {
      return bridge.withSearcher(searcher -> {
        // Content-field queries: prefix with BM25 scoring, infix with constant-score
        Term term = new Term(SchemaFields.CONTENT, normalizedPrefix);
        PrefixQuery prefixQuery =
            new PrefixQuery(term, MultiTermQuery.SCORING_BOOLEAN_REWRITE);
        WildcardQuery wildcardQuery =
            new WildcardQuery(
                new Term(SchemaFields.CONTENT, "*" + normalizedPrefix + "*"));

        // Title-field queries: higher boost so title matches rank above body-only matches
        Term titleTerm = new Term(SchemaFields.TITLE, normalizedPrefix);
        PrefixQuery titlePrefixQuery =
            new PrefixQuery(titleTerm, MultiTermQuery.SCORING_BOOLEAN_REWRITE);
        WildcardQuery titleWildcardQuery =
            new WildcardQuery(
                new Term(SchemaFields.TITLE, "*" + normalizedPrefix + "*"));

        // Combine: title prefix > title infix = content prefix > content infix
        BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
        queryBuilder.add(
            new BoostQuery(titlePrefixQuery, 4.0f), BooleanClause.Occur.SHOULD);
        queryBuilder.add(
            new BoostQuery(titleWildcardQuery, 2.0f), BooleanClause.Occur.SHOULD);
        queryBuilder.add(new BoostQuery(prefixQuery, 2.0f), BooleanClause.Occur.SHOULD);
        queryBuilder.add(wildcardQuery, BooleanClause.Occur.SHOULD);
        Query query = queryBuilder.build();

        // Search for matching documents
        var topDocs = searcher.search(query, effectiveLimit * 3); // Fetch extra for deduplication

        Set<String> seenSuggestions = new LinkedHashSet<>();
        org.apache.lucene.index.StoredFields storedFields = searcher.storedFields();
        Set<String> storedAllowlist = Set.of(SchemaFields.PATH, SchemaFields.TITLE);

        for (var scoreDoc : topDocs.scoreDocs) {
          Map<String, String> fields = SearchResultFormatter.extractFromStoredFields(
              storedFields, scoreDoc.doc, false, storedAllowlist);

          // Get the path field and extract filename
          String path = fields.get(SchemaFields.PATH);
          if (path != null && !path.isBlank()) {
            String filename = extractFilename(path);
            if (filename != null && !filename.isBlank()) {
              seenSuggestions.add(filename);
            }
          }

          // Also check for title field if available
          String title = fields.get(SchemaFields.TITLE);
          if (title != null && !title.isBlank()) {
            seenSuggestions.add(title);
          }

          if (seenSuggestions.size() >= effectiveLimit) {
            break;
          }
        }

        return new ArrayList<>(seenSuggestions)
            .subList(0, Math.min(seenSuggestions.size(), effectiveLimit));
      });
    } catch (IOException e) {
      log.warn("Suggest query failed", e);
      return List.of();
    }
  }

  /** Extracts the filename from a file path (handles both Unix and Windows separators). */
  static String extractFilename(String path) {
    if (path == null) return null;
    int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    if (lastSep >= 0 && lastSep < path.length() - 1) {
      return path.substring(lastSep + 1);
    }
    return path;
  }
}
