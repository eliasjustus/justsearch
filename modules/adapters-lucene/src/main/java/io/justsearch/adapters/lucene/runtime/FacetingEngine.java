/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FacetsResult;
import net.jcip.annotations.ThreadSafe;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TwoPhaseIterator;
import org.apache.lucene.search.Weight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-contained faceting engine for computing facet counts from Lucene queries.
 *
 * <p>This class encapsulates the facet computation logic, using Supplier/Consumer pattern
 * for searcher lifecycle management to avoid direct SearcherManager coupling.
 */
@ThreadSafe
public final class FacetingEngine {
  private static final Logger log = LoggerFactory.getLogger(FacetingEngine.class);

  /** Default safety cap for documents scanned during facet computation. */
  public static final int DEFAULT_MAX_DOCS_SCANNED = 50_000;

  private final SearcherBridge bridge;
  private final Function<String, FieldMapper.FieldDef> fieldDefLookup;

  /**
   * Creates a new faceting engine.
   *
   * @param bridge shared searcher acquire/release helper
   * @param fieldDefLookup looks up field definitions by name
   */
  public FacetingEngine(
      SearcherBridge bridge,
      Function<String, FieldMapper.FieldDef> fieldDefLookup) {
    this.bridge = bridge;
    this.fieldDefLookup = fieldDefLookup;
  }

  /**
   * Computes facet counts for a query using DocValues.
   *
   * <p>Only keyword fields (SortedDocValues) are counted. This is intentionally off by default
   * and should only be executed when the client explicitly requests facets.
   *
   * @param query lucene query (should already include any filters)
   * @param fieldToSize facet fields to compute (field -> topN)
   * @param maxDocsScanned safety cap (<=0 uses default of 50,000)
   * @return facet counts with truncation flag
   */
  public FacetsResult computeFacets(Query query, Map<String, Integer> fieldToSize, int maxDocsScanned) {
    if (query == null || fieldToSize == null || fieldToSize.isEmpty()) {
      return new FacetsResult(Map.of(), false, 0L);
    }

    // Safety default: prevent pathological broad queries from scanning the entire index.
    int cap = maxDocsScanned <= 0 ? DEFAULT_MAX_DOCS_SCANNED : maxDocsScanned;

    IndexSearcher searcher = null;
    try {
      searcher = bridge.acquire();

      // IMPORTANT:
      // MultiTermQuery implementations (e.g. PrefixQuery from pathPrefix filters) do not implement createWeight
      // directly and must be rewritten first. IndexSearcher.search(...) does this internally, but since facet
      // computation uses createWeight directly, we must rewrite here.
      Query rewritten = searcher.rewrite(query);
      Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);

      Map<String, Map<String, Long>> counts = new HashMap<>();
      for (String f : fieldToSize.keySet()) {
        if (f != null && !f.isBlank()) {
          counts.put(f, new HashMap<>());
        }
      }

      boolean truncated = false;
      long scanned = 0;

      for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
        if (truncated) break;

        Scorer scorer = weight.scorer(leaf);
        if (scorer == null) continue;

        // Cache per-leaf DocValues for requested fields (only keyword/docValues fields are supported).
        Map<String, SortedDocValues> dvs = new HashMap<>();
        Map<String, SortedSetDocValues> setDvs = new HashMap<>();
        for (String field : counts.keySet()) {
          FieldMapper.FieldDef def = fieldDefLookup.apply(field);
          if (def == null || !def.docValues || !"keyword".equals(def.type)) {
            continue;
          }
          try {
            if (def.multiValued) {
              SortedSetDocValues sdv = DocValues.getSortedSet(leaf.reader(), field);
              setDvs.put(field, sdv);
            } else {
              SortedDocValues dv = DocValues.getSorted(leaf.reader(), field);
              dvs.put(field, dv);
            }
          } catch (IllegalStateException ignored) {
            // Field has no DocValues in this segment.
          }
        }

        // IMPORTANT:
        // Some queries expose a two-phase iterator (approximation + matches()).
        // If we iterate only scorer.iterator(), we may count false positives from the approximation.
        // IndexSearcher.search(...) handles this internally; for facets we must do it ourselves.
        TwoPhaseIterator twoPhase = scorer.twoPhaseIterator();
        DocIdSetIterator it = (twoPhase == null) ? scorer.iterator() : twoPhase.approximation();
        int doc;
        while ((doc = it.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
          if (twoPhase != null && !twoPhase.matches()) {
            continue;
          }
          scanned++;
          if (cap > 0 && scanned > cap) {
            truncated = true;
            break;
          }

          for (var entry : dvs.entrySet()) {
            String field = entry.getKey();
            SortedDocValues dv = entry.getValue();
            if (dv == null) continue;
            if (dv.advanceExact(doc)) {
              String value = dv.lookupOrd(dv.ordValue()).utf8ToString();
              if (value != null) {
                counts.get(field).merge(value, 1L, Long::sum);
              }
            }
          }

          for (var entry : setDvs.entrySet()) {
            String field = entry.getKey();
            SortedSetDocValues sdv = entry.getValue();
            if (sdv == null) continue;
            if (sdv.advanceExact(doc)) {
              for (int i = 0; i < sdv.docValueCount(); i++) {
                long ord = sdv.nextOrd();
                String value = sdv.lookupOrd(ord).utf8ToString();
                if (value != null) {
                  counts.get(field).merge(value, 1L, Long::sum);
                }
              }
            }
          }
        }
      }

      // Reduce to top-N per field.
      Map<String, Map<String, Long>> top = new HashMap<>();
      for (var entry : counts.entrySet()) {
        String field = entry.getKey();
        int size = fieldToSize.getOrDefault(field, 10);
        if (size <= 0) size = 10;

        List<Map.Entry<String, Long>> list = new ArrayList<>(entry.getValue().entrySet());
        list.sort((a, b) -> {
          int c = Long.compare(b.getValue(), a.getValue());
          if (c != 0) return c;
          return a.getKey().compareTo(b.getKey());
        });

        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        int n = Math.min(size, list.size());
        for (int i = 0; i < n; i++) {
          out.put(list.get(i).getKey(), list.get(i).getValue());
        }
        top.put(field, Collections.unmodifiableMap(out));
      }

      // Tempdoc 597: `scanned` is the matched-document total — the headline's "M", with every facet
      // value tallied from it (so facet value <= matchedDocs by construction).
      return new FacetsResult(Collections.unmodifiableMap(top), truncated, scanned);

    } catch (IOException e) {
      log.warn("Failed to compute facets", e);
      return new FacetsResult(Map.of(), false, 0L);
    } finally {
      if (searcher != null) {
        bridge.release(searcher);
      }
    }
  }
}
