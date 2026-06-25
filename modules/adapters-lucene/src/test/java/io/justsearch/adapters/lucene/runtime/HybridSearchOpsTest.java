package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HybridSearchOpsTest {

  // ---- shouldSkipVectorSearch ----

  private static ResolvedConfig resolveForTest(String yaml) {
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry();
    if (yaml != null) {
      try {
        tools.jackson.databind.JsonNode root =
            new tools.jackson.databind.ObjectMapper(
                    new tools.jackson.dataformat.yaml.YAMLFactory())
                .readTree(yaml);
        builder.contributeYaml(root);
      } catch (Exception ignored) {
      }
    }
    return builder.build();
  }

  private HybridSearchOps opsWithConfig(String yaml) {
    ResolvedConfig rc = resolveForTest(yaml);
    IndexSchema schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(768));
    RuntimeSession session = new RuntimeSession(schema);
    session.resolvedConfig = rc;
    // TextQueryOps and ReadPathOps need a bridge/searcher. For unit tests that only
    // exercise shouldSkipVectorSearch and computeLowSignalGating, null ops are acceptable since
    // those methods don't delegate to the search legs.
    return new HybridSearchOps(session, null, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"a", "ab", "abc"})
  void shouldSkipVectorSearchForShortQueries(String query) {
    // Default min chars is 4
    HybridSearchOps ops = opsWithConfig(null);
    assertTrue(ops.shouldSkipVectorSearch(query));
  }

  @ParameterizedTest
  @ValueSource(strings = {"the", "The", "THE", "is", "a", "an"})
  void shouldSkipVectorSearchForStopWords(String query) {
    HybridSearchOps ops = opsWithConfig("index:\n  hybrid:\n    vector_skip_min_chars: 1\n");
    assertTrue(ops.shouldSkipVectorSearch(query));
  }

  @Test
  void shouldNotSkipVectorSearchForNonStopSingleWord() {
    HybridSearchOps ops = opsWithConfig(null);
    assertFalse(ops.shouldSkipVectorSearch("thesis"));
  }

  @Test
  void shouldNotSkipVectorSearchForMultiWordWithStopWords() {
    HybridSearchOps ops = opsWithConfig(null);
    assertFalse(ops.shouldSkipVectorSearch("the cat sat"));
  }

  @Test
  void shouldSkipVectorSearchUsesDefaultsWhenConfigNull() {
    HybridSearchOps ops = opsWithConfig(null);
    // "test" is 4 chars (= default min chars), not a stop word → don't skip
    assertFalse(ops.shouldSkipVectorSearch("test"));
    // "abc" is 3 chars (< default 4) → skip
    assertTrue(ops.shouldSkipVectorSearch("abc"));
  }

  @Test
  void shouldSkipVectorSearchRespectsCustomMinChars() {
    HybridSearchOps ops = opsWithConfig("index:\n  hybrid:\n    vector_skip_min_chars: 6\n");
    assertTrue(ops.shouldSkipVectorSearch("hello")); // 5 < 6
    assertFalse(ops.shouldSkipVectorSearch("hellos")); // 6 >= 6
  }

  // ---- computeLowSignalGating ----

  @Test
  void computeLowSignalGatingDetectsVectorLowSignal() {
    HybridSearchOps ops =
        opsWithConfig(
            """
            index:
              hybrid:
                vector_low_signal_top_score_threshold: 0.40
                vector_only_cap_low_signal: 5
                vector_rrf_weight_low_signal: 0.3
            """);

    SearchResult bm25 = resultWithTopScore(10.0f, 100);
    SearchResult vector = resultWithTopScore(0.2f, 50); // below 0.40

    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(bm25, vector, "test query", "test");
    assertEquals(5, gating.vectorOnlyCap());
    assertEquals(0.3, gating.vectorWeight(), 0.001);
  }

  @Test
  void computeLowSignalGatingDetectsBm25TopScoreLowSignal() {
    HybridSearchOps ops =
        opsWithConfig(
            """
            index:
              hybrid:
                bm25_low_signal_top_score_threshold: 5.0
                vector_low_signal_top_score_threshold: 0.01
                vector_only_cap_low_signal: 3
                vector_rrf_weight_low_signal: 0.2
            """);

    SearchResult bm25 = resultWithTopScore(2.0f, 100); // below 5.0
    SearchResult vector = resultWithTopScore(0.9f, 50); // above 0.01

    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(bm25, vector, "test", "test");
    assertEquals(3, gating.vectorOnlyCap());
    assertEquals(0.2, gating.vectorWeight(), 0.001);
  }

  @Test
  void computeLowSignalGatingDetectsBm25TotalHitsLowSignal() {
    HybridSearchOps ops =
        opsWithConfig(
            """
            index:
              hybrid:
                bm25_low_signal_total_hits_threshold: 5
                vector_low_signal_top_score_threshold: 0.01
                vector_only_cap_low_signal: 2
                vector_rrf_weight_low_signal: 0.1
            """);

    SearchResult bm25 = resultWithTopScore(10.0f, 3); // 3 <= 5
    SearchResult vector = resultWithTopScore(0.9f, 50);

    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(bm25, vector, "test", "test");
    assertEquals(2, gating.vectorOnlyCap());
    assertEquals(0.1, gating.vectorWeight(), 0.001);
  }

  @Test
  void computeLowSignalGatingHighSignalReturnsMaxCap() {
    HybridSearchOps ops =
        opsWithConfig(
            """
            index:
              hybrid:
                vector_low_signal_top_score_threshold: 0.40
                vector_rrf_weight: 0.8
            """);

    SearchResult bm25 = resultWithTopScore(10.0f, 100);
    SearchResult vector = resultWithTopScore(0.9f, 50); // above 0.40

    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(bm25, vector, "strong query", "test");
    assertEquals(Integer.MAX_VALUE, gating.vectorOnlyCap());
    assertEquals(0.8, gating.vectorWeight(), 0.001);
  }

  @Test
  void computeLowSignalGatingHandlesEmptyResults() {
    HybridSearchOps ops =
        opsWithConfig(
            """
            index:
              hybrid:
                vector_low_signal_top_score_threshold: 0.40
                vector_only_cap_low_signal: 1
                vector_rrf_weight_low_signal: 0.05
            """);

    SearchResult empty = new SearchResult(List.of(), 0, 0);
    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(empty, empty, "empty", "test");
    assertEquals(1, gating.vectorOnlyCap()); // low signal (0.0 < 0.40)
    assertEquals(0.05, gating.vectorWeight(), 0.001);
  }

  @Test
  void computeLowSignalGatingUsesDefaultsWhenConfigNull() {
    HybridSearchOps ops = opsWithConfig(null);

    SearchResult bm25 = resultWithTopScore(10.0f, 100);
    SearchResult vector = resultWithTopScore(0.2f, 50); // below default 0.40

    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(bm25, vector, "test", "test");
    // With default config and low signal: gating applies using ResolvedConfig builder defaults
    assertEquals(3, gating.vectorOnlyCap());
    assertEquals(0.25, gating.vectorWeight(), 0.001);
  }

  @Test
  void computeLowSignalGatingNullConfigHighSignalUsesDefaults() {
    HybridSearchOps ops = opsWithConfig(null);

    SearchResult bm25 = resultWithTopScore(10.0f, 100);
    SearchResult vector = resultWithTopScore(0.9f, 50); // above default 0.40

    HybridSearchOps.LowSignalGating gating =
        ops.computeLowSignalGating(bm25, vector, "test", "test");
    assertEquals(Integer.MAX_VALUE, gating.vectorOnlyCap());
    assertEquals(0.75, gating.vectorWeight(), 0.001);
  }

  // ---- E2E-8: vector-skip debug scores ----

  @Test
  void vectorSkipPopulatesSparseAndVectorDebugScores() {
    HybridSearchOps ops = opsWithConfig(null);
    // "ab" is 2 chars (< default 4) → triggers vector skip
    HybridSearchOps.TextSearchLeg textLeg =
        (q, l) ->
            new SearchResult(
                List.of(new SearchHit("doc-1", 5.5f, Map.of("title", "Test"))), 1, 0);
    HybridSearchOps.VectorSearchLeg vectorLeg = (v, l) -> new SearchResult(List.of(), 0, 0);

    SearchResult result =
        ops.executeHybrid(textLeg, vectorLeg, "ab", new float[] {0.1f}, 10, false, "test");
    assertEquals(1, result.hits().size());
    SearchHit hit = result.hits().getFirst();
    // Non-debug mode: "sparse", "vector" + rank keys (matching fuseWithRRF key names)
    assertEquals(5.5f, hit.debugScores().get("sparse"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("vector"), 0.001f);
    assertEquals(1.0f, hit.debugScores().get("sparse_rank"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("vector_rank"), 0.001f);
    assertEquals(4, hit.debugScores().size());
  }

  @Test
  void vectorSkipDebugModeIncludesRrfKeys() {
    HybridSearchOps ops = opsWithConfig(null);
    HybridSearchOps.TextSearchLeg textLeg =
        (q, l) -> new SearchResult(List.of(new SearchHit("doc-1", 3.0f, Map.of())), 1, 0);
    HybridSearchOps.VectorSearchLeg vectorLeg = (v, l) -> new SearchResult(List.of(), 0, 0);

    SearchResult result =
        ops.executeHybrid(textLeg, vectorLeg, "ab", new float[] {0.1f}, 10, true, "test");
    assertEquals(1, result.hits().size());
    SearchHit hit = result.hits().getFirst();
    // Debug mode: "sparse", "vector" + RRF debug keys.
    // Vector leg was skipped, so RRF contributions are 0; only "rrf" carries the raw BM25 score.
    assertEquals(3.0f, hit.debugScores().get("sparse"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("vector"), 0.001f);
    assertEquals(3.0f, hit.debugScores().get("rrf"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("sparse_rrf"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("vector_rrf"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("sparse_boost"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("rrf_base"), 0.001f);
    assertEquals(1.0f, hit.debugScores().get("sparse_rank"), 0.001f);
    assertEquals(0.0f, hit.debugScores().get("vector_rank"), 0.001f);
    assertEquals(9, hit.debugScores().size());
  }

  // ---- helpers ----

  private static SearchResult resultWithTopScore(float topScore, long totalHits) {
    if (topScore == 0.0f) {
      return new SearchResult(List.of(), totalHits, 0);
    }
    return new SearchResult(
        List.of(new SearchHit("doc-1", topScore, Map.of())), totalHits, 0);
  }

  // ---- Tempdoc 636 Design v2: per-query leg arbitration (CC alpha) ----

  /** Flat leg: n hits all with {@code topScore} (top2/top1 == 1.0 → incoherent). */
  private static SearchResult legOf(String idPrefix, float topScore, int n) {
    java.util.List<SearchHit> hits = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      hits.add(new SearchHit(idPrefix + i, topScore, Map.of()));
    }
    return new SearchResult(hits, n, 0);
  }

  /** Descending leg: hits[i] = topScore - i*step (a real top1−top2 gap / peaked distribution). */
  private static SearchResult legDescending(String idPrefix, float topScore, float step, int n) {
    java.util.List<SearchHit> hits = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) {
      hits.add(new SearchHit(idPrefix + i, topScore - i * step, Map.of()));
    }
    return new SearchResult(hits, n, 0);
  }

  // A dense leg that fires: top 0.7 (>= 0.55), gap 0.1 (>= 0.02).
  private static SearchResult confidentDense() {
    return legDescending("dns", 0.7f, 0.1f, 5);
  }

  // A BM25 leg that is incoherent (flat top → ratio 1.0).
  private static SearchResult flatBm25() {
    return legOf("lex", 9.0f, 5);
  }

  private static double arb(SearchResult bm25, SearchResult vector, double base) {
    return HybridSearchOps.computeArbitrationAlpha(bm25, vector, base, 0.85, 0.7);
  }

  @Test
  void topKDocIdOverlap_disjointLegs_isZero() {
    assertEquals(0.0, HybridSearchOps.topKDocIdOverlap(legOf("a", 1f, 5), legOf("b", 1f, 5), 10), 1e-9);
  }

  @Test
  void topKDocIdOverlap_identicalLegs_isOne() {
    assertEquals(1.0, HybridSearchOps.topKDocIdOverlap(legOf("a", 1f, 5), legOf("a", 1f, 5), 10), 1e-9);
  }

  @Test
  void bm25CoherenceRatio_flatIsOne_peakedIsLow_shortIsOne() {
    assertEquals(1.0, HybridSearchOps.bm25CoherenceRatio(legOf("x", 5f, 4)), 1e-9);
    assertEquals(0.2, HybridSearchOps.bm25CoherenceRatio(legDescending("x", 10f, 8f, 3)), 1e-9);
    assertEquals(1.0, HybridSearchOps.bm25CoherenceRatio(legOf("x", 5f, 1)), 1e-9); // < 2 hits
  }

  @Test
  void arbitration_fires_whenDenseConfidentGapped_diverge_andBm25Incoherent() {
    double alpha = arb(flatBm25(), confidentDense(), 0.5);
    assertEquals(0.85, alpha, 1e-9, "all firing conditions hold → raise alpha toward dense");
  }

  @Test
  void arbitration_doesNotFire_whenBm25Coherent() {
    // peaked BM25 (ratio 0.2 < 0.7) → BM25 has a clear winner → trust it, do not down-weight
    SearchResult peakedBm25 = legDescending("lex", 10.0f, 8.0f, 5);
    assertEquals(0.5, arb(peakedBm25, confidentDense(), 0.5), 1e-9);
  }

  @Test
  void arbitration_legsAgree_returnsBaseAlpha() {
    SearchResult vector = legDescending("shared", 0.7f, 0.1f, 5);
    SearchResult bm25 = legOf("shared", 9.0f, 5); // identical ids → overlap 1.0 (agree)
    assertEquals(0.5, arb(bm25, vector, 0.5), 1e-9);
  }

  @Test
  void arbitration_denseNotConfident_returnsBaseAlpha() {
    assertEquals(0.5, arb(flatBm25(), legDescending("dns", 0.3f, 0.1f, 5), 0.5), 1e-9);
  }

  @Test
  void arbitration_emptyVector_returnsBaseAlpha() {
    assertEquals(0.5, arb(flatBm25(), new SearchResult(List.of(), 0, 0), 0.5), 1e-9);
  }

  @Test
  void arbitration_neverLowersAlphaBelowBase() {
    assertEquals(0.9, arb(flatBm25(), confidentDense(), 0.9), 1e-9);
  }
}
