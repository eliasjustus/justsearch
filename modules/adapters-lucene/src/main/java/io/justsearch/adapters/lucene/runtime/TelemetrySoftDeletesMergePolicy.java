/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.FilterCodecReader;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.OneMergeWrappingMergePolicy;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOSupplier;

/**
 * Extension of Lucene's SoftDeletesRetentionMergePolicy that records kept/purged soft deletes via
 * the provided counters. Implementation mirrors upstream logic but emits retention telemetry.
 */
final class TelemetrySoftDeletesMergePolicy extends OneMergeWrappingMergePolicy {
  private final String field;
  private final Supplier<Query> retentionQuerySupplier;

  TelemetrySoftDeletesMergePolicy(
      String field,
      Supplier<Query> retentionQuerySupplier,
      MergePolicy in,
      LongConsumer keptCounter,
      LongConsumer purgedCounter) {
    final String softDeleteField = Objects.requireNonNull(field, "field");
    final Supplier<Query> supplier =
        Objects.requireNonNull(retentionQuerySupplier, "retentionQuerySupplier");
    final LongConsumer keptConsumer = keptCounter == null ? value -> {} : keptCounter;
    final LongConsumer purgedConsumer = purgedCounter == null ? value -> {} : purgedCounter;
    super(
        in,
        toWrap ->
            new MergePolicy.OneMerge(toWrap.segments) {
              @Override
              public CodecReader wrapForMerge(CodecReader reader) throws IOException {
                CodecReader wrapped = toWrap.wrapForMerge(reader);
                Bits liveDocs = reader.getLiveDocs();
                // Defensive check: CodecReader.getLiveDocs() can return null per API contract,
                // though in practice during merges, committed segments always have a Bits object
                // (even if all docs are live). This check prevents NPE in edge cases.
                if (liveDocs == null) {
                  // no deletes to consider
                  return wrapped;
                }
                RetentionResult result =
                    applyRetentionQuery(softDeleteField, supplier.get(), wrapped);
                if (result != null) {
                  recordMetrics(
                      keptConsumer,
                      purgedConsumer,
                      result);
                }
                return result == null ? wrapped : result.reader();
              }
            });
    this.field = softDeleteField;
    this.retentionQuerySupplier = supplier;
  }

  @Override
  public boolean keepFullyDeletedSegment(IOSupplier<CodecReader> readerIOSupplier)
      throws IOException {
    CodecReader reader = readerIOSupplier.get();
    Scorer scorer =
        getScorer(retentionQuerySupplier.get(), wrapLiveDocs(reader, null, reader.maxDoc()));
    if (scorer != null) {
      // if any doc matches retention query we keep the segment
      return scorer.iterator().nextDoc() != DocIdSetIterator.NO_MORE_DOCS;
    }
    return super.keepFullyDeletedSegment(readerIOSupplier);
  }

  @Override
  public int numDeletesToMerge(
      SegmentCommitInfo info, int delCount, IOSupplier<CodecReader> readerSupplier)
      throws IOException {
    final int numDeletesToMerge = super.numDeletesToMerge(info, delCount, readerSupplier);
    if (numDeletesToMerge != 0 && info.getSoftDelCount() > 0) {
      final CodecReader reader = readerSupplier.get();
      if (reader.getLiveDocs() != null) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new FieldExistsQuery(field), BooleanClause.Occur.FILTER);
        builder.add(retentionQuerySupplier.get(), BooleanClause.Occur.FILTER);
        Scorer scorer =
            getScorer(builder.build(), wrapLiveDocs(reader, null, reader.maxDoc()));
        if (scorer != null) {
          Bits liveDocs = reader.getLiveDocs();
          int numDeletedDocs = reader.numDeletedDocs();
          while (scorer.iterator().nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
            if (!liveDocs.get(scorer.docID())) {
              numDeletedDocs--;
            }
          }
          return numDeletedDocs;
        }
      }
    }
    return numDeletesToMerge;
  }

  private static RetentionResult applyRetentionQuery(
      String softDeleteField, Query retentionQuery, CodecReader reader) throws IOException {
    if (retentionQuery == null) retentionQuery = new MatchNoDocsQuery();
    Bits liveDocs = reader.getLiveDocs();
    if (liveDocs == null) {
      return null;
    }

    CodecReader wrappedReader =
        wrapLiveDocs(
            reader,
            new Bits() {
              @Override
              public boolean get(int index) {
                return liveDocs.get(index) == false;
              }

              @Override
              public int length() {
                return liveDocs.length();
              }
            },
            reader.maxDoc() - reader.numDocs());

    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    builder.add(new FieldExistsQuery(softDeleteField), BooleanClause.Occur.FILTER);
    builder.add(retentionQuery, BooleanClause.Occur.FILTER);
    Scorer scorer = getScorer(builder.build(), wrappedReader);
    int totalSoftDeleted = reader.numDeletedDocs();
    if (scorer != null) {
      FixedBitSet cloneLiveDocs = FixedBitSet.copyOf(liveDocs);
      int numExtraLiveDocs = 0;
      var it = scorer.iterator();
      while (it.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        if (cloneLiveDocs.getAndSet(it.docID()) == false) {
          numExtraLiveDocs++;
        }
      }
      int purged = Math.max(0, totalSoftDeleted - numExtraLiveDocs);
      return new RetentionResult(
          wrapLiveDocs(reader, cloneLiveDocs, reader.numDocs() + numExtraLiveDocs),
          numExtraLiveDocs,
          purged);
    } else if (totalSoftDeleted > 0) {
      return new RetentionResult(reader, 0, totalSoftDeleted);
    }
    return null;
  }

  private static Scorer getScorer(Query query, CodecReader reader) throws IOException {
    IndexSearcher searcher = new IndexSearcher(reader);
    searcher.setQueryCache(null);
    Weight weight =
        searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1.0f);
    return weight.scorer(reader.getContext());
  }

  private static void recordMetrics(
      LongConsumer keptCounter, LongConsumer purgedCounter, RetentionResult result) {
    long kept = result.kept();
    long purged = result.purged();
    if (kept > 0) keptCounter.accept(kept);
    if (purged > 0) purgedCounter.accept(purged);
  }

  private static CodecReader wrapLiveDocs(CodecReader reader, Bits liveDocs, int numDocs) {
    return new FilterCodecReader(reader) {
      @Override
      public IndexReader.CacheHelper getCoreCacheHelper() {
        return reader.getCoreCacheHelper();
      }

      @Override
      public IndexReader.CacheHelper getReaderCacheHelper() {
        return null;
      }

      @Override
      public Bits getLiveDocs() {
        return liveDocs;
      }

      @Override
      public int numDocs() {
        return numDocs;
      }
    };
  }

  private record RetentionResult(CodecReader reader, int kept, int purged) {}
}
