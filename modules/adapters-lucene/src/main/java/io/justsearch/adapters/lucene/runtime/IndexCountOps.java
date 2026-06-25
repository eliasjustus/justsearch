/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ChunkEmbeddingCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.EmbeddingCounts;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SpladeFeatureCounts;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal aggregate-query collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates document count and corpus profiling operations: total doc count,
 * field-value counts, embedding status counts, SPLADE status counts, and the corpus profile.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code
 * close()}. Access from the runtime must go through a volatile snapshot to ensure visibility
 * across threads.
 */
public final class IndexCountOps {
  private static final Logger log = LoggerFactory.getLogger(IndexCountOps.class);

  private final SearcherBridge bridge;
  private volatile CorpusProfile cachedProfile;
  private volatile long cachedProfileVersion = -1L;

  IndexCountOps(SearcherBridge bridge) {
    this.bridge = bridge;
  }

  /**
   * Returns the total number of documents in the index.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public long docCount() {
    try {
      return bridge.withSearcher(
          searcher -> (long) searcher.getIndexReader().numDocs());
    } catch (IOException e) {
      return 0;
    }
  }

  /**
   * Counts documents matching a specific field value.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countByField(String field, String value) {
    if (field == null || value == null) {
      return 0;
    }
    try {
      return bridge.withSearcher(searcher -> {
        Query query = new TermQuery(new Term(field, value));
        return searcher.count(query);
      });
    } catch (IOException e) {
      log.debug("Failed to count {}={}: {}", field, value, e.getMessage());
      return 0;
    }
  }

  /**
   * Counts documents matching all supplied exact field values.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countByFields(Map<String, String> filters) {
    if (filters == null || filters.isEmpty()) {
      return 0;
    }
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (Map.Entry<String, String> entry : filters.entrySet()) {
      String field = entry.getKey();
      String value = entry.getValue();
      if (field == null || value == null) {
        return 0;
      }
      builder.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.FILTER);
    }
    try {
      return bridge.withSearcher(searcher -> searcher.count(builder.build()));
    } catch (IOException e) {
      log.debug("Failed to count filters {}: {}", filters, e.getMessage());
      return 0;
    }
  }

  /**
   * Counts the documents matching an arbitrary Lucene {@link Query} (exact, unbounded — Lucene's
   * {@code IndexSearcher.count} has no {@code totalHitsThreshold} early-termination, unlike the
   * scored retrieval path). Tempdoc 597: the search response's {@code matchCount} (the true
   * matched-document total) is computed via this, over the same chunk-excluded query the facets
   * scan — so the headline can read "Top N of M matches" and {@code matchCount >=} every facet
   * value by construction, instead of the bounded fused-candidate-union {@code totalHits}.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public int countQuery(Query query) {
    if (query == null) {
      return 0;
    }
    try {
      return bridge.withSearcher(searcher -> searcher.count(query));
    } catch (IOException e) {
      log.debug("Failed to count query: {}", e.getMessage());
      return 0;
    }
  }

  /**
   * Queries chunk embedding status counts for the status endpoint.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public ChunkEmbeddingCounts queryChunkEmbeddingCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query chunksQuery = new TermQuery(new Term(SchemaFields.IS_CHUNK, "true"));
        int total = searcher.count(chunksQuery);

        Query completedQuery = new BooleanQuery.Builder()
            .add(chunksQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_COMPLETED)),
                BooleanClause.Occur.FILTER)
            .build();
        int completed = searcher.count(completedQuery);

        Query pendingQuery = new BooleanQuery.Builder()
            .add(chunksQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_PENDING)),
                BooleanClause.Occur.FILTER)
            .build();
        int pending = searcher.count(pendingQuery);

        Query failedQuery = new BooleanQuery.Builder()
            .add(chunksQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.CHUNK_EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_FAILED)),
                BooleanClause.Occur.FILTER)
            .build();
        int failed = searcher.count(failedQuery);

        return new ChunkEmbeddingCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query chunk embedding counts: {}", e.getMessage());
      return new ChunkEmbeddingCounts(0, 0, 0, 0);
    }
  }

  /**
   * Queries doc-level embedding status counts for whole (non-chunk) documents.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public EmbeddingCounts queryEmbeddingCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query wholeDocsQuery =
            new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                    BooleanClause.Occur.MUST_NOT)
                .build();
        int total = searcher.count(wholeDocsQuery);

        Query completedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_COMPLETED)),
                BooleanClause.Occur.FILTER)
            .build();
        int completed = searcher.count(completedQuery);

        Query pendingQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_PENDING)),
                BooleanClause.Occur.FILTER)
            .build();
        int pending = searcher.count(pendingQuery);

        Query failedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.EMBEDDING_STATUS,
                    SchemaFields.EMBEDDING_STATUS_FAILED)),
                BooleanClause.Occur.FILTER)
            .build();
        int failed = searcher.count(failedQuery);

        return new EmbeddingCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query embedding counts: {}", e.getMessage());
      return new EmbeddingCounts(0, 0, 0, 0);
    }
  }

  /**
   * Queries SPLADE feature extraction status counts for whole (non-chunk) documents.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public SpladeFeatureCounts querySpladeFeatureCounts() {
    try {
      return bridge.withSearcher(searcher -> {
        Query wholeDocsQuery =
            new BooleanQuery.Builder()
                .add(new MatchAllDocsQuery(), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                    BooleanClause.Occur.MUST_NOT)
                .build();
        int total = searcher.count(wholeDocsQuery);

        Query completedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.SPLADE_STATUS,
                    SchemaFields.SPLADE_STATUS_COMPLETED)),
                BooleanClause.Occur.FILTER)
            .build();
        int completed = searcher.count(completedQuery);

        Query pendingQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.SPLADE_STATUS,
                    SchemaFields.SPLADE_STATUS_PENDING)),
                BooleanClause.Occur.FILTER)
            .build();
        int pending = searcher.count(pendingQuery);

        Query failedQuery = new BooleanQuery.Builder()
            .add(wholeDocsQuery, BooleanClause.Occur.FILTER)
            .add(new TermQuery(new Term(
                    SchemaFields.SPLADE_STATUS,
                    SchemaFields.SPLADE_STATUS_FAILED)),
                BooleanClause.Occur.FILTER)
            .build();
        int failed = searcher.count(failedQuery);

        return new SpladeFeatureCounts(total, completed, pending, failed);
      });
    } catch (IOException e) {
      log.debug("Failed to query SPLADE feature counts: {}", e.getMessage());
      return new SpladeFeatureCounts(0, 0, 0, 0);
    }
  }

  /**
   * Returns the cached corpus profile, recomputing when the index version changes.
   *
   * <p>Uses {@link DirectoryReader#getVersion()} for O(1) staleness detection — the profile is
   * automatically invalidated after any commit without requiring explicit cache clearing.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public CorpusProfile getOrComputeCorpusProfile() {
    long currentVersion = getReaderVersion();
    CorpusProfile p = cachedProfile;
    if (p != null && cachedProfileVersion == currentVersion) {
      return p;
    }
    p = computeCorpusProfile();
    cachedProfile = p;
    cachedProfileVersion = currentVersion;
    return p;
  }

  private long getReaderVersion() {
    try {
      return bridge.withSearcher(
          s -> {
            if (s.getIndexReader() instanceof DirectoryReader dr) {
              return dr.getVersion();
            }
            return -1L;
          });
    } catch (IOException e) {
      return -1L;
    }
  }

  /**
   * Computes a corpus-level profile from {@code parent_token_count} DocValues. Iterates all
   * non-chunk parent documents in the index, building a bucket histogram of token counts. Used to
   * gate chunk-aware merge for short-document corpora where chunks ≈ documents and branch fusion
   * injects noise (tempdoc 309 §26).
   *
   * <p>Cost: O(N parent docs), typically 5-20 ms for 100K documents. Should be cached.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public CorpusProfile computeCorpusProfile() {
    try {
      return bridge.withSearcher(searcher -> {
        IndexReader reader = searcher.getIndexReader();

        // Count chunk documents
        long chunkCount = searcher.count(
            new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")));

        // Build a query for non-chunk documents only
        Query nonChunkQuery = new BooleanQuery.Builder()
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST)
            .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.MUST_NOT)
            .build();
        Query rewritten = searcher.rewrite(nonChunkQuery);
        Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);

        long parentCount = 0;
        long totalTokens = 0;
        long docsWithTokens = 0;
        int[] buckets = new int[CorpusProfile.BUCKET_BOUNDARIES.length + 1];

        for (LeafReaderContext leaf : reader.leaves()) {
          var scorer = weight.scorer(leaf);
          if (scorer == null) continue;

          NumericDocValues ptcDv =
              leaf.reader().getNumericDocValues(SchemaFields.PARENT_TOKEN_COUNT);

          var twoPhase = scorer.twoPhaseIterator();
          DocIdSetIterator it =
              (twoPhase == null) ? scorer.iterator() : twoPhase.approximation();
          int doc;
          while ((doc = it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            if (twoPhase != null && !twoPhase.matches()) continue;
            parentCount++;
            if (ptcDv != null && ptcDv.advanceExact(doc)) {
              long tc = ptcDv.longValue();
              totalTokens += tc;
              docsWithTokens++;
              buckets[CorpusProfile.bucketFor(tc)]++;
            }
          }
        }

        return new CorpusProfile(parentCount, chunkCount, totalTokens, docsWithTokens, buckets);
      });
    } catch (IOException e) {
      log.debug("Failed to compute corpus profile: {}", e.getMessage());
      return CorpusProfile.EMPTY;
    }
  }
}
