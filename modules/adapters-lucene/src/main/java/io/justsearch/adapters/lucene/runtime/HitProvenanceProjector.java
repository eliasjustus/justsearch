/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BranchFusionSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.ChunkMergeSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.FusionSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.HitProvenanceSignals;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RetrieverSignal;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 549 Slice 3c (U2): builds typed per-hit provenance from the TYPED pre-fusion leg
 * results, then attaches it onto the post-fusion hits by docId.
 *
 * <p>The generic fusers ({@code HybridFusionUtils}) create fresh {@link SearchHit}s and only
 * copy-forward the stringly-typed {@code debugScores} map — they do NOT propagate the typed
 * {@code provenance} field. So provenance is never built inside the fuser and never relies on
 * the fuser to carry it: each orchestration stage (where the leg semantics are known) indexes
 * its own typed leg results and re-maps the resulting provenance onto the fused hits here.
 *
 * <p>Per-doc leg rank is the 1-based position in that leg's ranked list; raw leg score is the
 * hit's {@code score()}. The fusion-stage score is the fused hit's {@code score()} — available
 * unconditionally, so the fusion leg is always-on (it no longer needs {@code debug=true}, which
 * the retired {@code debugScores}-parsing path required).
 */
public final class HitProvenanceProjector {

  private HitProvenanceProjector() {}

  /** Indexes a ranked leg result: docId &rarr; (1-based rank, raw leg score). */
  public static Map<String, RetrieverSignal> indexLeg(SearchResult leg) {
    if (leg == null || leg.hits() == null || leg.hits().isEmpty()) {
      return Map.of();
    }
    Map<String, RetrieverSignal> idx = new HashMap<>();
    int rank = 1;
    for (SearchHit hit : leg.hits()) {
      String docId = hit.docId();
      if (docId != null) {
        // First occurrence wins (best rank) if a docId repeats within a leg.
        idx.putIfAbsent(docId, new RetrieverSignal(rank, hit.score()));
      }
      rank++;
    }
    return idx;
  }

  /** Which retrieval leg a single-leg (no-fusion) result came from. */
  public enum LegKind {
    BM25,
    SPLADE,
    DENSE
  }

  /**
   * Attaches a single retrieval leg (no fusion) where the fused result IS the leg — the common
   * single-leg paths (BM25-only, dense-only, SPLADE-only). Ranks/scores come from the result's
   * own ordering.
   */
  public static SearchResult attachSingleLeg(SearchResult leg, LegKind kind) {
    return switch (kind) {
      case BM25 -> attachRetrieval(leg, leg, null, null, null);
      case SPLADE -> attachRetrieval(leg, null, leg, null, null);
      case DENSE -> attachRetrieval(leg, null, null, leg, null);
    };
  }

  /**
   * Convenience overload taking the raw leg {@link SearchResult}s (null = leg didn't run); it
   * indexes each leg and delegates. Lets orchestration call sites pass the typed leg results
   * directly without indexing boilerplate.
   */
  public static SearchResult attachRetrieval(
      SearchResult fused,
      SearchResult bm25Leg,
      SearchResult spladeLeg,
      SearchResult denseLeg,
      String fusionMethod) {
    return attachRetrieval(
        fused, indexLeg(bm25Leg), indexLeg(spladeLeg), indexLeg(denseLeg), fusionMethod);
  }

  /**
   * Attaches the retriever legs (bm25/splade/dense — any may be null if that leg didn't run)
   * and, when {@code fusionMethod} is non-null, a fusion leg (score = the fused hit's score).
   * Preserves any chunk-merge / branch-fusion legs already present on the hit.
   */
  public static SearchResult attachRetrieval(
      SearchResult fused,
      Map<String, RetrieverSignal> bm25Idx,
      Map<String, RetrieverSignal> spladeIdx,
      Map<String, RetrieverSignal> denseIdx,
      String fusionMethod) {
    if (fused == null || fused.hits() == null) {
      return fused;
    }
    List<SearchHit> annotated =
        fused.hits().stream()
            .map(
                hit -> {
                  String docId = hit.docId();
                  RetrieverSignal bm25 = lookup(bm25Idx, docId);
                  RetrieverSignal splade = lookup(spladeIdx, docId);
                  RetrieverSignal dense = lookup(denseIdx, docId);
                  FusionSignal fusion =
                      fusionMethod != null ? new FusionSignal(hit.score(), fusionMethod) : null;
                  HitProvenanceSignals base = baseOf(hit);
                  return hit.withProvenance(
                      new HitProvenanceSignals(
                          bm25, splade, dense, fusion, base.chunkMerge(), base.branchFusion()));
                })
            .toList();
    return new SearchResult(annotated, fused.totalHits(), fused.tookMs(), fused.nextCursor());
  }

  /** Convenience overload taking the raw chunk leg {@link SearchResult}s (null = leg absent). */
  public static SearchResult attachChunkMerge(
      SearchResult fusedChunk,
      SearchResult sparseLeg,
      SearchResult denseLeg,
      SearchResult spladeLeg) {
    return attachChunkMerge(fusedChunk, indexLeg(sparseLeg), indexLeg(denseLeg), indexLeg(spladeLeg));
  }

  /**
   * Attaches the 3-leg chunk-fusion provenance to fused chunk hits. The CC-fused chunk score is
   * the fused hit's {@code score()}. Preserves any retriever/branch legs already present.
   */
  public static SearchResult attachChunkMerge(
      SearchResult fusedChunk,
      Map<String, RetrieverSignal> sparseIdx,
      Map<String, RetrieverSignal> denseIdx,
      Map<String, RetrieverSignal> spladeIdx) {
    if (fusedChunk == null || fusedChunk.hits() == null) {
      return fusedChunk;
    }
    List<SearchHit> annotated =
        fusedChunk.hits().stream()
            .map(
                hit -> {
                  String docId = hit.docId();
                  RetrieverSignal sp = lookup(sparseIdx, docId);
                  RetrieverSignal dn = lookup(denseIdx, docId);
                  RetrieverSignal spl = lookup(spladeIdx, docId);
                  ChunkMergeSignal cm =
                      new ChunkMergeSignal(
                          rankOf(sp),
                          rankOf(dn),
                          rankOf(spl),
                          scoreOf(sp),
                          scoreOf(dn),
                          scoreOf(spl),
                          hit.score());
                  return hit.withProvenance(baseOf(hit).withChunkMerge(cm));
                })
            .toList();
    return new SearchResult(
        annotated, fusedChunk.totalHits(), fusedChunk.tookMs(), fusedChunk.nextCursor());
  }

  /**
   * After whole-vs-chunk branch fusion, combines the whole-doc branch's retriever/fusion legs
   * (recovered from {@code wholeDocResult}), the chunk-merge leg (recovered from
   * {@code chunkParentResult}), and a branch-fusion leg (whole/chunk branch scores + the fused
   * branch score). Re-maps onto the {@code merged} hits by docId.
   */
  public static SearchResult attachBranchFusion(
      SearchResult merged,
      SearchResult wholeDocResult,
      SearchResult chunkParentResult,
      String branchMethod) {
    if (merged == null || merged.hits() == null) {
      return merged;
    }
    Map<String, SearchHit> wholeIdx = byDocId(wholeDocResult);
    Map<String, SearchHit> chunkIdx = byDocId(chunkParentResult);
    List<SearchHit> annotated =
        merged.hits().stream()
            .map(
                hit -> {
                  String docId = hit.docId();
                  SearchHit whole = wholeIdx.get(docId);
                  SearchHit chunk = chunkIdx.get(docId);
                  // Whole-doc branch carries the retriever + (top-level) fusion legs.
                  HitProvenanceSignals base =
                      whole != null ? baseOf(whole) : HitProvenanceSignals.EMPTY;
                  ChunkMergeSignal cm =
                      chunk != null && chunk.provenance() != null
                          ? chunk.provenance().chunkMerge()
                          : null;
                  float wholeScore = whole != null ? whole.score() : 0f;
                  float chunkScore = chunk != null ? chunk.score() : 0f;
                  BranchFusionSignal bf =
                      new BranchFusionSignal(wholeScore, chunkScore, hit.score(), branchMethod);
                  HitProvenanceSignals sig = base;
                  if (cm != null) {
                    sig = sig.withChunkMerge(cm);
                  }
                  return hit.withProvenance(sig.withBranchFusion(bf));
                })
            .toList();
    return new SearchResult(annotated, merged.totalHits(), merged.tookMs(), merged.nextCursor());
  }

  private static HitProvenanceSignals baseOf(SearchHit hit) {
    return hit.provenance() != null ? hit.provenance() : HitProvenanceSignals.EMPTY;
  }

  private static RetrieverSignal lookup(Map<String, RetrieverSignal> idx, String docId) {
    return idx == null || docId == null ? null : idx.get(docId);
  }

  private static int rankOf(RetrieverSignal s) {
    return s == null ? 0 : s.rank();
  }

  private static float scoreOf(RetrieverSignal s) {
    return s == null ? 0f : s.rawScore();
  }

  private static Map<String, SearchHit> byDocId(SearchResult result) {
    if (result == null || result.hits() == null) {
      return Map.of();
    }
    Map<String, SearchHit> idx = new LinkedHashMap<>();
    for (SearchHit hit : result.hits()) {
      if (hit.docId() != null) {
        idx.putIfAbsent(hit.docId(), hit);
      }
    }
    return idx;
  }
}
