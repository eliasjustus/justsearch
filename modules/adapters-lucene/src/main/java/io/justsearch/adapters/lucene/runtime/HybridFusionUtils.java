/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility methods for hybrid search fusion algorithms.
 *
 * <p>Extracted from {@link LuceneLifecycleManager} to improve organization and testability. Contains
 * pure functions for combining search results from different retrieval methods (e.g., BM25 + kNN).
 */
public final class HybridFusionUtils {

  private static final long SPLADE_FULL_WEIGHT_MAX_TOKENS =
      Long.getLong("justsearch.splade.full_weight_max_tokens", 1024L);
  private static final long SPLADE_ZERO_WEIGHT_MIN_TOKENS =
      Long.getLong("justsearch.splade.zero_weight_min_tokens", 4096L);

  private HybridFusionUtils() {
    // Utility class - no instantiation
  }

  /**
   * Tempdoc 636 Design v3 — recall-complete rerank pool. Guarantees that every doc in {@code
   * protectedHits} is present in the returned list (capped at {@code limit}), so a candidate a
   * retrieval leg ranked highly cannot be gatekept out of the downstream cross-encoder's rerank
   * window by the fused-score truncation. The CE reranks only the top of whatever the Worker
   * returns; on a grep-defeating paraphrase query the lexical leg's filler outscores the
   * dense-found answer in fusion, dropping it below that window so the CE never sees it (tempdoc 636
   * §Direction investigation: {@code hybrid} 0.24 ≪ {@code vector} 0.82). This splices any such
   * dropped leg-top-N candidate back in.
   *
   * <p>Order: the fused prefix is preserved verbatim; rescued hits are appended <em>below</em> it
   * (re-scored just under the prefix floor so the merged list stays monotonically descending for
   * display and downstream consumers), displacing the lowest-fused non-protected hits to stay
   * within {@code limit}. The cross-encoder re-scores the pool anyway, and per-leg provenance is
   * re-attached by docId at the call site, so the rescued hits' synthetic scores are
   * inconsequential to ranking quality.
   *
   * <p>Pure and deterministic. Returns {@code fused} unchanged when every protected doc is already
   * present (the common case, and the byte-identical equivalence to the flag-off path).
   *
   * @param fused the fused hit list (already ordered + truncated by fused score)
   * @param protectedHits leg-top-N candidates that must be present (e.g. top-N of each leg)
   * @param limit the maximum size of the returned list
   */
  public static List<SearchHit> spliceRecallComplete(
      List<SearchHit> fused, Collection<SearchHit> protectedHits, int limit) {
    if (fused == null || protectedHits == null || protectedHits.isEmpty() || limit <= 0) {
      return fused;
    }
    Set<String> fusedIds = new HashSet<>();
    for (SearchHit h : fused) {
      if (h.docId() != null) {
        fusedIds.add(h.docId());
      }
    }
    // Protected hits missing from the fused list, de-duped by docId, in leg/iteration order.
    List<SearchHit> missing = new ArrayList<>();
    Set<String> missingIds = new HashSet<>();
    for (SearchHit h : protectedHits) {
      String id = h.docId();
      if (id == null || fusedIds.contains(id) || !missingIds.add(id)) {
        continue;
      }
      missing.add(h);
      if (missing.size() >= limit) {
        break;
      }
    }
    if (missing.isEmpty()) {
      return fused; // every protected doc already present → no-op (flag-off equivalence)
    }
    int keepFused = Math.max(0, limit - missing.size());
    List<SearchHit> result = new ArrayList<>(Math.min(limit, fused.size() + missing.size()));
    for (int i = 0; i < fused.size() && result.size() < keepFused; i++) {
      result.add(fused.get(i));
    }
    float floor = result.isEmpty() ? 0.0f : result.get(result.size() - 1).score();
    float step = Math.max(1e-6f, Math.abs(floor) * 1e-3f + 1e-6f);
    int rank = 1;
    for (SearchHit h : missing) {
      if (result.size() >= limit) {
        break;
      }
      float synthetic = floor - step * rank++;
      result.add(new SearchHit(h.docId(), synthetic, h.fields(), h.debugScores(), null));
    }
    return result;
  }

  /** Top-{@code n} hits of a result (by its existing order), for the recall-complete protected set. */
  public static List<SearchHit> topN(SearchResult r, int n) {
    if (r == null || r.hits() == null || n <= 0) {
      return List.of();
    }
    List<SearchHit> hits = r.hits();
    return hits.size() <= n ? hits : new ArrayList<>(hits.subList(0, n));
  }

  /**
   * Fuses two search results using Reciprocal Rank Fusion (RRF).
   *
   * <p>RRF is a simple yet effective method for combining ranked lists. The score for a document is
   * the sum of 1/(K + rank) across all lists where it appears, where K is a constant (typically
   * 60).
   */
  public static SearchResult fuseWithRRF(
      SearchResult result1,
      SearchResult result2,
      int limit,
      boolean debug,
      int vectorOnlyCap,
      double vectorWeightRaw,
      ResolvedConfig resolvedConfig) {
    return fuseWithRRFNamed(
        result1,
        result2,
        limit,
        debug,
        vectorOnlyCap,
        vectorWeightRaw,
        resolvedConfig,
        "sparse",
        "vector",
        "",
        true);
  }

  /**
   * Fuses two search results using RRF with a score key prefix.
   *
   * <p>When the prefix is non-empty, the emitted keys are prefixed (for example "chunk_sparse",
   * "chunk_vector", "chunk_rrf"). Any pre-existing debug scores on input hits are carried forward
   * into the fused result, preserving per-component attribution through multi-stage fusion.
   */
  public static SearchResult fuseWithRRF(
      SearchResult result1,
      SearchResult result2,
      int limit,
      boolean debug,
      int vectorOnlyCap,
      double vectorWeightRaw,
      ResolvedConfig resolvedConfig,
      String scoreKeyPrefix) {
    return fuseWithRRFNamed(
        result1,
        result2,
        limit,
        debug,
        vectorOnlyCap,
        vectorWeightRaw,
        resolvedConfig,
        key(scoreKeyPrefix, "sparse"),
        key(scoreKeyPrefix, "vector"),
        scoreKeyPrefix,
        true);
  }

  /**
   * Fuses two search results using RRF while letting the caller explicitly name both legs.
   *
   * <p>This is used for second-stage fusions where the inputs are no longer "sparse" and
   * "vector", for example whole-document results versus collapsed chunk-branch results.
   */
  public static SearchResult fuseWithRRFNamed(
      SearchResult result1,
      SearchResult result2,
      int limit,
      boolean debug,
      int result2OnlyCap,
      double result2WeightRaw,
      ResolvedConfig resolvedConfig,
      String result1Key,
      String result2Key,
      String fusedKeyPrefix,
      boolean result1ScoreBoostEnabled) {

    ResolvedConfig.HybridSearch hs =
        resolvedConfig != null ? resolvedConfig.hybridSearch() : null;
    final int k = hs != null ? hs.rrfK() : 60;

    double result2Weight = Math.max(0.0, Math.min(1.0, result2WeightRaw));
    int result2OnlyCapEffective = result2OnlyCap <= 0 ? 0 : result2OnlyCap;
    final double result1ScoreBoostWeight =
        result1ScoreBoostEnabled ? Math.max(0.0, hs != null ? hs.bm25ScoreBoostWeight() : 0.0) : 0.0;

    Map<String, Double> scores = new HashMap<>();
    Map<String, Map<String, String>> fieldsByDoc = new HashMap<>();
    Map<String, Map<String, Float>> existingDebugScores = new HashMap<>();

    Map<String, Float> result1Scores = new HashMap<>();
    Map<String, Float> result1Ranks = new HashMap<>();
    Map<String, Float> result2Scores = new HashMap<>();
    Map<String, Float> result2Ranks = new HashMap<>();

    Map<String, Double> result1RrfScores = debug ? new HashMap<>() : null;
    Map<String, Double> result2RrfScores = debug ? new HashMap<>() : null;

    int rank = 1;
    for (SearchHit hit : result1.hits()) {
      String docId = hit.docId();
      if (docId == null) continue;

      double rrfScore = 1.0 / (k + rank);
      scores.merge(docId, rrfScore, Double::sum);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);

      result1Scores.put(docId, hit.score());
      result1Ranks.put(docId, (float) rank);
      if (debug) {
        result1RrfScores.put(docId, rrfScore);
      }
      rank++;
    }

    rank = 1;
    int result2OnlyAdded = 0;
    for (SearchHit hit : result2.hits()) {
      String docId = hit.docId();
      // Symmetric with the result1 leg above: a null docId is a malformed hit that does not
      // consume a rank slot, so valid hits keep their natural rank (tempdoc 554).
      if (docId == null) continue;

      boolean inResult1 = result1Scores.containsKey(docId);
      if (!inResult1) {
        if (result2OnlyAdded >= result2OnlyCapEffective) {
          rank++;
          continue;
        }
        result2OnlyAdded++;
      }

      double rrfScore = result2Weight / (k + rank);
      scores.merge(docId, rrfScore, Double::sum);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);

      result2Scores.put(docId, hit.score());
      result2Ranks.put(docId, (float) rank);
      if (debug) {
        result2RrfScores.put(docId, rrfScore);
      }
      rank++;
    }

    List<SearchHit> fusedHits =
        scores.entrySet().stream()
            .sorted(
                (a, b) -> {
                  String docA = a.getKey();
                  String docB = b.getKey();
                  double scoreA =
                      a.getValue()
                          + result1ScoreBoostWeight * result1Scores.getOrDefault(docA, 0.0f);
                  double scoreB =
                      b.getValue()
                          + result1ScoreBoostWeight * result1Scores.getOrDefault(docB, 0.0f);
                  int cmp = Double.compare(scoreB, scoreA);
                  if (cmp != 0) return cmp;
                  cmp =
                      Float.compare(
                          result1Scores.getOrDefault(docB, 0.0f),
                          result1Scores.getOrDefault(docA, 0.0f));
                  if (cmp != 0) return cmp;
                  cmp =
                      Float.compare(
                          result2Scores.getOrDefault(docB, 0.0f),
                          result2Scores.getOrDefault(docA, 0.0f));
                  if (cmp != 0) return cmp;
                  return docA.compareTo(docB);
                })
            .limit(limit)
            .map(
                entry -> {
                  String docId = entry.getKey();
                  double baseScore = entry.getValue();
                  double boostedScore =
                      baseScore
                          + result1ScoreBoostWeight * result1Scores.getOrDefault(docId, 0.0f);
                  float fusedScore = (float) boostedScore;
                  Map<String, String> fields = fieldsByDoc.getOrDefault(docId, Map.of());

                  Map<String, Float> hitScores =
                      new HashMap<>(existingDebugScores.getOrDefault(docId, Map.of()));
                  hitScores.put(result1Key, result1Scores.getOrDefault(docId, 0.0f));
                  hitScores.put(result1Key + "_rank", result1Ranks.getOrDefault(docId, 0f));
                  hitScores.put(result2Key, result2Scores.getOrDefault(docId, 0.0f));
                  hitScores.put(result2Key + "_rank", result2Ranks.getOrDefault(docId, 0f));
                  if (debug) {
                    hitScores.put(
                        result1Key + "_rrf",
                        result1RrfScores.getOrDefault(docId, 0.0).floatValue());
                    hitScores.put(
                        result2Key + "_rrf",
                        result2RrfScores.getOrDefault(docId, 0.0).floatValue());
                    if (result1ScoreBoostEnabled) {
                      hitScores.put(
                          result1Key + "_boost",
                          (float) (result1ScoreBoostWeight * result1Scores.getOrDefault(docId, 0.0f)));
                    }
                    hitScores.put(key(fusedKeyPrefix, "rrf_base"), (float) baseScore);
                    hitScores.put(key(fusedKeyPrefix, "rrf"), fusedScore);
                  }
                  return new SearchHit(docId, fusedScore, fields, Map.copyOf(hitScores));
                })
            .toList();

    return new SearchResult(fusedHits, scores.size(), 0);
  }

  /**
   * Fuses two search results using Convex Combination (CC) of min-max normalized scores.
   */
  public static SearchResult fuseWithCC(
      SearchResult sparseResult,
      SearchResult denseResult,
      int limit,
      double alpha,
      boolean debug,
      boolean zeroExclude) {

    Map<String, Float> sparseScores = new HashMap<>();
    Map<String, Float> sparseRanks = new HashMap<>();
    Map<String, Map<String, String>> fieldsByDoc = new HashMap<>();
    int sparseRank = 1;
    for (SearchHit hit : sparseResult.hits()) {
      if (hit.docId() == null) continue;
      sparseScores.put(hit.docId(), hit.score());
      sparseRanks.put(hit.docId(), (float) sparseRank++);
      mergeFields(fieldsByDoc, hit);
    }

    Map<String, Float> denseScores = new HashMap<>();
    Map<String, Float> denseRanks = new HashMap<>();
    int denseRank = 1;
    for (SearchHit hit : denseResult.hits()) {
      if (hit.docId() == null) continue;
      denseScores.put(hit.docId(), hit.score());
      denseRanks.put(hit.docId(), (float) denseRank++);
      mergeFields(fieldsByDoc, hit);
    }

    double sparseMin = minScore(sparseScores);
    double sparseRange = scoreRange(sparseScores, sparseMin);
    double denseMin = minScore(denseScores);
    double denseRange = scoreRange(denseScores, denseMin);

    Map<String, Double> ccScores = new HashMap<>();
    var allDocIds = new java.util.HashSet<>(sparseScores.keySet());
    allDocIds.addAll(denseScores.keySet());

    for (String docId : allDocIds) {
      boolean inSparse = sparseScores.containsKey(docId);
      boolean inDense = denseScores.containsKey(docId);

      double normSparse = inSparse ? normalizeScore(sparseScores.get(docId), sparseMin, sparseRange) : 0.0;
      double normDense = inDense ? normalizeScore(denseScores.get(docId), denseMin, denseRange) : 0.0;

      double ccScore;
      if (zeroExclude && inDense && !inSparse) {
        ccScore = normDense;
      } else if (zeroExclude && inSparse && !inDense) {
        ccScore = normSparse;
      } else {
        ccScore = alpha * normDense + (1.0 - alpha) * normSparse;
      }
      ccScores.put(docId, ccScore);
    }

    List<SearchHit> fusedHits =
        ccScores.entrySet().stream()
            .sorted(
                (a, b) -> {
                  int cmp = Double.compare(b.getValue(), a.getValue());
                  return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
                })
            .limit(limit)
            .map(
                entry -> {
                  String docId = entry.getKey();
                  float fusedScore = entry.getValue().floatValue();
                  Map<String, String> fields = fieldsByDoc.getOrDefault(docId, Map.of());

                  Map<String, Float> hitScores = new HashMap<>();
                  hitScores.put("sparse", sparseScores.getOrDefault(docId, 0.0f));
                  hitScores.put("sparse_rank", sparseRanks.getOrDefault(docId, 0f));
                  hitScores.put("vector", denseScores.getOrDefault(docId, 0.0f));
                  hitScores.put("vector_rank", denseRanks.getOrDefault(docId, 0f));
                  if (debug) {
                    hitScores.put("cc", fusedScore);
                    hitScores.put("cc_alpha", (float) alpha);
                  }
                  return new SearchHit(docId, fusedScore, fields, Map.copyOf(hitScores));
                })
            .toList();

    return new SearchResult(fusedHits, ccScores.size(), 0);
  }

  /**
   * Fuses two named parent-level branches using convex combination (CC) with optional per-hit
   * modifier scaling.
   *
   * <p>This is used by Stage 3B to merge whole-document and collapsed chunk-parent branches after
   * both branches already produced parent-level candidates. The second branch can be modulated by
   * parent length so short documents trust the whole-doc branch more and long documents trust the
   * chunk branch more.
   */
  public static SearchResult fuseWithCCNamed(
      SearchResult result1,
      SearchResult result2,
      int limit,
      double[] weights,
      boolean debug,
      boolean zeroExclude,
      String result1Key,
      String result2Key,
      String fusedKeyPrefix,
      String result1Label,
      String result2Label,
      boolean applyResult2ParentLengthModulation,
      double result2MinWeightMultiplier) {

    Map<String, Float> result1Scores = new HashMap<>();
    Map<String, Float> result1Ranks = new HashMap<>();
    Map<String, Float> result2Scores = new HashMap<>();
    Map<String, Float> result2Ranks = new HashMap<>();
    Map<String, Map<String, String>> fieldsByDoc = new HashMap<>();
    Map<String, Map<String, Float>> existingDebugScores = new HashMap<>();

    int result1Rank = 1;
    for (SearchHit hit : result1.hits()) {
      if (hit.docId() == null) continue;
      result1Scores.put(hit.docId(), hit.score());
      result1Ranks.put(hit.docId(), (float) result1Rank++);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);
    }

    int result2Rank = 1;
    for (SearchHit hit : result2.hits()) {
      if (hit.docId() == null) continue;
      result2Scores.put(hit.docId(), hit.score());
      result2Ranks.put(hit.docId(), (float) result2Rank++);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);
    }

    double result1Min = minScore(result1Scores);
    double result1Range = scoreRange(result1Scores, result1Min);
    double result2Min = minScore(result2Scores);
    double result2Range = scoreRange(result2Scores, result2Min);

    double baseResult1Weight = weights[0];
    double baseResult2Weight = weights[1];

    Map<String, Double> ccScores = new HashMap<>();
    Map<String, double[]> effectiveWeightsByDoc = new HashMap<>();
    Map<String, double[]> modifiersByDoc = new HashMap<>();
    Map<String, double[]> normalizedScoresByDoc = new HashMap<>();

    var allDocIds = new java.util.HashSet<>(result1Scores.keySet());
    allDocIds.addAll(result2Scores.keySet());

    for (String docId : allDocIds) {
      boolean inResult1 = result1Scores.containsKey(docId);
      boolean inResult2 = result2Scores.containsKey(docId);

      double normResult1 =
          inResult1 ? normalizeScore(result1Scores.get(docId), result1Min, result1Range) : 0.0;
      double normResult2 =
          inResult2 ? normalizeScore(result2Scores.get(docId), result2Min, result2Range) : 0.0;

      Map<String, String> fields = fieldsByDoc.getOrDefault(docId, Map.of());
      double result1Modifier = 1.0;
      double result2Modifier =
          applyResult2ParentLengthModulation
              ? chunkBranchParentLengthMultiplier(fields, result2MinWeightMultiplier)
              : 1.0;

      double rawResult1Weight = baseResult1Weight * result1Modifier;
      double rawResult2Weight = baseResult2Weight * result2Modifier;
      double includedResult1Weight = inResult1 || !zeroExclude ? rawResult1Weight : 0.0;
      double includedResult2Weight = inResult2 || !zeroExclude ? rawResult2Weight : 0.0;

      double denominator = includedResult1Weight + includedResult2Weight;

      double effectiveResult1Weight =
          denominator > 0.0 ? includedResult1Weight / denominator : 0.0;
      double effectiveResult2Weight =
          denominator > 0.0 ? includedResult2Weight / denominator : 0.0;

      double ccScore =
          effectiveResult1Weight * normResult1 + effectiveResult2Weight * normResult2;

      ccScores.put(docId, ccScore);
      effectiveWeightsByDoc.put(docId, new double[] {effectiveResult1Weight, effectiveResult2Weight});
      modifiersByDoc.put(docId, new double[] {result1Modifier, result2Modifier});
      normalizedScoresByDoc.put(docId, new double[] {normResult1, normResult2});
    }

    List<SearchHit> fusedHits =
        ccScores.entrySet().stream()
            .sorted(
                (a, b) -> {
                  String docA = a.getKey();
                  String docB = b.getKey();
                  int cmp = Double.compare(b.getValue(), a.getValue());
                  if (cmp != 0) return cmp;
                  cmp =
                      Float.compare(
                          result1Scores.getOrDefault(docB, 0.0f),
                          result1Scores.getOrDefault(docA, 0.0f));
                  if (cmp != 0) return cmp;
                  cmp =
                      Float.compare(
                          result2Scores.getOrDefault(docB, 0.0f),
                          result2Scores.getOrDefault(docA, 0.0f));
                  if (cmp != 0) return cmp;
                  return docA.compareTo(docB);
                })
            .limit(limit)
            .map(
                entry -> {
                  String docId = entry.getKey();
                  float fusedScore = entry.getValue().floatValue();
                  Map<String, String> fields = fieldsByDoc.getOrDefault(docId, Map.of());
                  double[] effectiveWeights =
                      effectiveWeightsByDoc.getOrDefault(docId, new double[] {0.0, 0.0});
                  double[] modifiers = modifiersByDoc.getOrDefault(docId, new double[] {1.0, 1.0});
                  double[] normalizedScores =
                      normalizedScoresByDoc.getOrDefault(docId, new double[] {0.0, 0.0});

                  Map<String, Float> hitScores =
                      new HashMap<>(existingDebugScores.getOrDefault(docId, Map.of()));
                  hitScores.put(result1Key, result1Scores.getOrDefault(docId, 0.0f));
                  hitScores.put(result1Key + "_rank", result1Ranks.getOrDefault(docId, 0f));
                  hitScores.put(result1Key + "_norm", (float) normalizedScores[0]);
                  hitScores.put(result2Key, result2Scores.getOrDefault(docId, 0.0f));
                  hitScores.put(result2Key + "_rank", result2Ranks.getOrDefault(docId, 0f));
                  hitScores.put(result2Key + "_norm", (float) normalizedScores[1]);
                  if (debug) {
                    hitScores.put(key(fusedKeyPrefix, "cc"), fusedScore);
                    hitScores.put(
                        key(fusedKeyPrefix, "cc_weight_" + result1Label), (float) baseResult1Weight);
                    hitScores.put(
                        key(fusedKeyPrefix, "cc_weight_" + result2Label), (float) baseResult2Weight);
                    Float parentTokenCount = parentTokenCount(fields);
                    if (parentTokenCount != null) {
                      hitScores.put(key(fusedKeyPrefix, "parent_token_count"), parentTokenCount);
                    }
                    hitScores.put(
                        key(fusedKeyPrefix, "cc_effective_weight_" + result1Label),
                        (float) effectiveWeights[0]);
                    hitScores.put(
                        key(fusedKeyPrefix, "cc_effective_weight_" + result2Label),
                        (float) effectiveWeights[1]);
                    hitScores.put(
                        key(fusedKeyPrefix, "cc_modifier_" + result1Label), (float) modifiers[0]);
                    hitScores.put(
                        key(fusedKeyPrefix, "cc_modifier_" + result2Label), (float) modifiers[1]);
                  }
                  return new SearchHit(docId, fusedScore, fields, Map.copyOf(hitScores));
                })
            .toList();

    return new SearchResult(fusedHits, ccScores.size(), 0);
  }

  /**
   * Fuses three retrieval legs (BM25, dense, SPLADE) using a convex combination (CC) with
   * per-leg weights. Scores are min-max normalized per leg before combination.
   */
  public static SearchResult fuseWithCC3(
      SearchResult sparseResult,
      SearchResult denseResult,
      SearchResult spladeResult,
      int limit,
      double[] weights,
      boolean debug,
      boolean zeroExclude) {
    return fuseWithCC3(
        sparseResult,
        denseResult,
        spladeResult,
        limit,
        weights,
        debug,
        zeroExclude,
        "",
        false);
  }

  /**
   * Stage 3A capable 3-way CC fusion with optional score-key namespacing and parent-length-aware
   * SPLADE suppression.
   */
  public static SearchResult fuseWithCC3(
      SearchResult sparseResult,
      SearchResult denseResult,
      SearchResult spladeResult,
      int limit,
      double[] weights,
      boolean debug,
      boolean zeroExclude,
      String scoreKeyPrefix,
      boolean applyParentLengthModulation) {

    Map<String, Float> sparseScores = new HashMap<>();
    Map<String, Float> sparseRanks = new HashMap<>();
    Map<String, Float> denseScores = new HashMap<>();
    Map<String, Float> denseRanks = new HashMap<>();
    Map<String, Float> spladeScores = new HashMap<>();
    Map<String, Float> spladeRanks = new HashMap<>();
    Map<String, Map<String, String>> fieldsByDoc = new HashMap<>();
    Map<String, Map<String, Float>> existingDebugScores = new HashMap<>();

    int sparseRank = 1;
    for (SearchHit hit : sparseResult.hits()) {
      if (hit.docId() == null) continue;
      sparseScores.put(hit.docId(), hit.score());
      sparseRanks.put(hit.docId(), (float) sparseRank++);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);
    }

    int denseRank = 1;
    for (SearchHit hit : denseResult.hits()) {
      if (hit.docId() == null) continue;
      denseScores.put(hit.docId(), hit.score());
      denseRanks.put(hit.docId(), (float) denseRank++);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);
    }

    int spladeRank = 1;
    for (SearchHit hit : spladeResult.hits()) {
      if (hit.docId() == null) continue;
      spladeScores.put(hit.docId(), hit.score());
      spladeRanks.put(hit.docId(), (float) spladeRank++);
      mergeFields(fieldsByDoc, hit);
      mergeDebugScores(existingDebugScores, hit);
    }

    double sparseMin = minScore(sparseScores);
    double sparseRange = scoreRange(sparseScores, sparseMin);
    double denseMin = minScore(denseScores);
    double denseRange = scoreRange(denseScores, denseMin);
    double spladeMin = minScore(spladeScores);
    double spladeRange = scoreRange(spladeScores, spladeMin);

    double baseSparseWeight = weights[0];
    double baseDenseWeight = weights[1];
    double baseSpladeWeight = weights[2];
    boolean emitExtendedDebug = applyParentLengthModulation || (scoreKeyPrefix != null && !scoreKeyPrefix.isBlank());

    Map<String, Double> ccScores = new HashMap<>();
    Map<String, double[]> effectiveWeightsByDoc = new HashMap<>();
    Map<String, double[]> modifiersByDoc = new HashMap<>();

    var allDocIds = new java.util.HashSet<>(sparseScores.keySet());
    allDocIds.addAll(denseScores.keySet());
    allDocIds.addAll(spladeScores.keySet());

    for (String docId : allDocIds) {
      boolean inSparse = sparseScores.containsKey(docId);
      boolean inDense = denseScores.containsKey(docId);
      boolean inSplade = spladeScores.containsKey(docId);

      double normSparse =
          inSparse ? normalizeScore(sparseScores.get(docId), sparseMin, sparseRange) : 0.0;
      double normDense =
          inDense ? normalizeScore(denseScores.get(docId), denseMin, denseRange) : 0.0;
      double normSplade =
          inSplade ? normalizeScore(spladeScores.get(docId), spladeMin, spladeRange) : 0.0;

      Map<String, String> fields = fieldsByDoc.getOrDefault(docId, Map.of());
      double sparseModifier = 1.0;
      double denseModifier = 1.0;
      double spladeModifier =
          applyParentLengthModulation ? spladeParentLengthMultiplier(fields) : 1.0;

      double rawSparseWeight = baseSparseWeight * sparseModifier;
      double rawDenseWeight = baseDenseWeight * denseModifier;
      double rawSpladeWeight = baseSpladeWeight * spladeModifier;
      double includedSparseWeight = inSparse || !zeroExclude ? rawSparseWeight : 0.0;
      double includedDenseWeight = inDense || !zeroExclude ? rawDenseWeight : 0.0;
      double includedSpladeWeight = inSplade || !zeroExclude ? rawSpladeWeight : 0.0;

      double denominator = includedSparseWeight + includedDenseWeight + includedSpladeWeight;

      double effectiveSparseWeight =
          denominator > 0.0 ? includedSparseWeight / denominator : 0.0;
      double effectiveDenseWeight = denominator > 0.0 ? includedDenseWeight / denominator : 0.0;
      double effectiveSpladeWeight =
          denominator > 0.0 ? includedSpladeWeight / denominator : 0.0;

      double ccScore =
          effectiveSparseWeight * normSparse
              + effectiveDenseWeight * normDense
              + effectiveSpladeWeight * normSplade;

      ccScores.put(docId, ccScore);
      effectiveWeightsByDoc.put(
          docId,
          new double[] {effectiveSparseWeight, effectiveDenseWeight, effectiveSpladeWeight});
      modifiersByDoc.put(docId, new double[] {sparseModifier, denseModifier, spladeModifier});
    }

    List<SearchHit> fusedHits =
        ccScores.entrySet().stream()
            .sorted(
                (a, b) -> {
                  int cmp = Double.compare(b.getValue(), a.getValue());
                  return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
                })
            .limit(limit)
            .map(
                entry -> {
                  String docId = entry.getKey();
                  float fusedScore = entry.getValue().floatValue();
                  Map<String, String> fields = fieldsByDoc.getOrDefault(docId, Map.of());
                  double[] effectiveWeights =
                      effectiveWeightsByDoc.getOrDefault(docId, new double[] {0.0, 0.0, 0.0});
                  double[] modifiers =
                      modifiersByDoc.getOrDefault(docId, new double[] {1.0, 1.0, 1.0});

                  Map<String, Float> hitScores =
                      new HashMap<>(existingDebugScores.getOrDefault(docId, Map.of()));
                  hitScores.put(key(scoreKeyPrefix, "sparse"), sparseScores.getOrDefault(docId, 0.0f));
                  hitScores.put(
                      key(scoreKeyPrefix, "sparse_rank"), sparseRanks.getOrDefault(docId, 0f));
                  hitScores.put(key(scoreKeyPrefix, "vector"), denseScores.getOrDefault(docId, 0.0f));
                  hitScores.put(
                      key(scoreKeyPrefix, "vector_rank"), denseRanks.getOrDefault(docId, 0f));
                  hitScores.put(
                      key(scoreKeyPrefix, "splade"), spladeScores.getOrDefault(docId, 0.0f));
                  hitScores.put(
                      key(scoreKeyPrefix, "splade_rank"), spladeRanks.getOrDefault(docId, 0f));
                  if (debug) {
                    hitScores.put(key(scoreKeyPrefix, "cc"), fusedScore);
                    hitScores.put(key(scoreKeyPrefix, "cc_weight_sparse"), (float) baseSparseWeight);
                    hitScores.put(key(scoreKeyPrefix, "cc_weight_dense"), (float) baseDenseWeight);
                    hitScores.put(key(scoreKeyPrefix, "cc_weight_splade"), (float) baseSpladeWeight);
                    Float parentTokenCount = parentTokenCount(fields);
                    if (parentTokenCount != null) {
                      hitScores.put(key(scoreKeyPrefix, "parent_token_count"), parentTokenCount);
                    }
                    if (emitExtendedDebug) {
                      hitScores.put(
                          key(scoreKeyPrefix, "cc_effective_weight_sparse"),
                          (float) effectiveWeights[0]);
                      hitScores.put(
                          key(scoreKeyPrefix, "cc_effective_weight_dense"),
                          (float) effectiveWeights[1]);
                      hitScores.put(
                          key(scoreKeyPrefix, "cc_effective_weight_splade"),
                          (float) effectiveWeights[2]);
                      hitScores.put(
                          key(scoreKeyPrefix, "cc_modifier_sparse"), (float) modifiers[0]);
                      hitScores.put(
                          key(scoreKeyPrefix, "cc_modifier_dense"), (float) modifiers[1]);
                      hitScores.put(
                          key(scoreKeyPrefix, "cc_modifier_splade"), (float) modifiers[2]);
                    }
                  }
                  return new SearchHit(docId, fusedScore, fields, Map.copyOf(hitScores));
                })
            .toList();

    return new SearchResult(fusedHits, ccScores.size(), 0);
  }

  /** Returns the SPLADE multiplier for a stored parent-token-count field map. */
  public static double spladeParentLengthMultiplier(Map<String, String> fields) {
    if (fields == null) {
      return 1.0;
    }
    String raw = fields.get(SchemaFields.PARENT_TOKEN_COUNT);
    if (raw == null || raw.isBlank()) {
      return 1.0;
    }
    try {
      return spladeParentLengthMultiplier(Long.parseLong(raw));
    } catch (NumberFormatException ignored) {
      return 1.0;
    }
  }

  /** Returns the Stage 3A SPLADE multiplier for a parent token count. */
  public static double spladeParentLengthMultiplier(long parentTokenCount) {
    return linearInterpolationByParentLength(
        parentTokenCount, SPLADE_FULL_WEIGHT_MAX_TOKENS, SPLADE_ZERO_WEIGHT_MIN_TOKENS, 1.0, 0.0);
  }

  /** Returns the Stage 3B chunk-branch multiplier for a stored parent-token-count field map. */
  public static double chunkBranchParentLengthMultiplier(
      Map<String, String> fields, double minMultiplier) {
    if (fields == null) {
      return 1.0;
    }
    String raw = fields.get(SchemaFields.PARENT_TOKEN_COUNT);
    if (raw == null || raw.isBlank()) {
      return 1.0;
    }
    try {
      return chunkBranchParentLengthMultiplier(Long.parseLong(raw), minMultiplier);
    } catch (NumberFormatException ignored) {
      return 1.0;
    }
  }

  /** Returns the Stage 3B chunk-branch multiplier for a parent token count. */
  public static double chunkBranchParentLengthMultiplier(
      long parentTokenCount, double minMultiplier) {
    return linearInterpolationByParentLength(
        parentTokenCount,
        SPLADE_FULL_WEIGHT_MAX_TOKENS,
        SPLADE_ZERO_WEIGHT_MIN_TOKENS,
        minMultiplier,
        1.0);
  }

  private static String key(String prefix, String suffix) {
    return prefix == null || prefix.isBlank() ? suffix : prefix + suffix;
  }

  private static void mergeFields(Map<String, Map<String, String>> fieldsByDoc, SearchHit hit) {
    if (hit.docId() == null || hit.fields() == null || hit.fields().isEmpty()) {
      return;
    }
    Map<String, String> existing = fieldsByDoc.get(hit.docId());
    if (existing == null || existing.isEmpty()) {
      fieldsByDoc.put(hit.docId(), hit.fields());
      return;
    }
    if (existing.entrySet().containsAll(hit.fields().entrySet())) {
      return;
    }
    Map<String, String> merged = new HashMap<>(existing);
    for (var entry : hit.fields().entrySet()) {
      merged.putIfAbsent(entry.getKey(), entry.getValue());
    }
    fieldsByDoc.put(hit.docId(), Map.copyOf(merged));
  }

  private static void mergeDebugScores(
      Map<String, Map<String, Float>> debugScoresByDoc, SearchHit hit) {
    if (hit.docId() == null || hit.debugScores() == null || hit.debugScores().isEmpty()) {
      return;
    }
    Map<String, Float> merged = new HashMap<>(debugScoresByDoc.getOrDefault(hit.docId(), Map.of()));
    for (var entry : hit.debugScores().entrySet()) {
      merged.putIfAbsent(entry.getKey(), entry.getValue());
    }
    debugScoresByDoc.put(hit.docId(), Map.copyOf(merged));
  }

  private static double minScore(Map<String, Float> scores) {
    if (scores.isEmpty()) {
      return 0.0;
    }
    double min = Double.MAX_VALUE;
    for (float score : scores.values()) {
      if (score < min) {
        min = score;
      }
    }
    return min;
  }

  private static double maxScore(Map<String, Float> scores) {
    if (scores.isEmpty()) {
      return 0.0;
    }
    double max = -Double.MAX_VALUE;
    for (float score : scores.values()) {
      if (score > max) {
        max = score;
      }
    }
    return max;
  }

  private static double scoreRange(Map<String, Float> scores, double min) {
    if (scores.isEmpty()) {
      return 0.0;
    }
    return maxScore(scores) - min;
  }

  private static double normalizeScore(float score, double min, double range) {
    return range > 0.0 ? (score - min) / range : 1.0;
  }

  private static double linearInterpolationByParentLength(
      long parentTokenCount,
      long lowerBound,
      long upperBound,
      double lowerValue,
      double upperValue) {
    if (parentTokenCount <= lowerBound) {
      return lowerValue;
    }
    if (parentTokenCount >= upperBound) {
      return upperValue;
    }
    double span = upperBound - lowerBound;
    double offset = parentTokenCount - lowerBound;
    double progress = offset / span;
    return lowerValue + (upperValue - lowerValue) * progress;
  }

  private static Float parentTokenCount(Map<String, String> fields) {
    if (fields == null) {
      return null;
    }
    String raw = fields.get(SchemaFields.PARENT_TOKEN_COUNT);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Float.parseFloat(raw);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
