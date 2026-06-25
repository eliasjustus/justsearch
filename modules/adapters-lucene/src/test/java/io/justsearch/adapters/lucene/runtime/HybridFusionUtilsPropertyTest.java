package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Property-style tests for {@link HybridFusionUtils} — the law-bearing fusion seam
 * (governance/logic-seams.v1.json). Each test encodes an INVARIANT (the "law") and checks it over
 * many seeded inputs, rather than a hand-picked example. This is the Pillar-B half of tempdoc 555
 * (the law as an executable spec); the {@code test-efficacy} gate measures, via PIT, that these
 * actually kill faults (the Pillar-C half).
 *
 * <p><b>No PBT framework dependency.</b> Implemented with plain JUnit 5 + a seeded {@link Random}
 * generator on purpose: the idiomatic Java PBT library (jqwik) shipped an AI-agent-targeted
 * prompt-injection payload in 1.10.0 (patched in 1.10.1, same maintainer), so it is deliberately
 * NOT added to this security-sensitive, dependency-verified build (tempdoc 555 §7/§10). Seeded
 * generation gives the same "law over many inputs" guarantee with zero supply-chain exposure.
 *
 * <p>Laws here are ALGORITHM-SPECIFIC (tempdoc 555 §10/U3 correction): RRF is rank-monotone (score
 * enters only via rank), CC is score-monotone (min-max normalized).
 */
class HybridFusionUtilsPropertyTest {

  private static final int RRF_K = 60; // default when ResolvedConfig is null
  private static final int ITERS = 200;
  // Fused hit.score() is a float (production casts the double score to float), so score assertions
  // use a float-appropriate tolerance. Mutated formulas diverge far beyond this, so mutants still die.
  private static final double EPS = 1e-6;

  private static SearchResult result(List<SearchHit> hits) {
    return new SearchResult(hits, hits.size(), 0);
  }

  private static SearchHit hit(String id, float score) {
    return new SearchHit(id, score, Map.of());
  }

  // ---- RRF laws -----------------------------------------------------------

  @Test
  @DisplayName("RRF law: a leg-1-only doc at rank r scores exactly 1/(k+r); ranks are strictly ordered")
  void rrf_leg1Only_exactScoreAndRankMonotone() {
    Random rnd = new Random(20260603L);
    for (int it = 0; it < ITERS; it++) {
      int n = 1 + rnd.nextInt(12);
      List<SearchHit> leg1 = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        // descending raw scores so input order == rank order; RRF must ignore the raw value.
        leg1.add(hit("d" + i, (float) (100.0 - i + rnd.nextDouble())));
      }
      SearchResult fused =
          HybridFusionUtils.fuseWithRRF(result(leg1), result(List.of()), 1000, false, 100, 1.0, null);

      assertEquals(n, fused.hits().size());
      double prev = Double.MAX_VALUE;
      for (int rank = 1; rank <= n; rank++) {
        SearchHit h = fused.hits().get(rank - 1);
        // rank order preserved (kills rank-increment + k+rank math mutants)
        assertEquals("d" + (rank - 1), h.docId(), "rank order must follow leg-1 input order");
        // exact RRF score (kills math + boundary mutants on the contribution formula)
        assertEquals(1.0 / (RRF_K + rank), h.score(), EPS, "RRF contribution must be 1/(k+rank)");
        // strictly decreasing (rank-monotonicity)
        assertTrue(h.score() < prev, "fused score must strictly decrease with worse rank");
        prev = h.score();
      }
    }
  }

  @Test
  @DisplayName("RRF law: leg-2 weight is clamped to [0,1] and a leg-2-only doc scores w/(k+r)")
  void rrf_leg2Weight_clampedAndExact() {
    double[] rawWeights = {-1.0, -0.001, 0.0, 0.25, 0.5, 1.0, 1.0001, 5.0};
    for (double raw : rawWeights) {
      double w = Math.max(0.0, Math.min(1.0, raw)); // the law
      List<SearchHit> leg2 = List.of(hit("x0", 9f), hit("x1", 8f), hit("x2", 7f));
      SearchResult fused =
          HybridFusionUtils.fuseWithRRF(result(List.of()), result(leg2), 1000, false, 100, raw, null);
      // capped count is full here (cap=100 > 3); every leg-2-only doc present at w/(k+rank)
      assertEquals(3, fused.hits().size());
      for (int rank = 1; rank <= 3; rank++) {
        double expected = w / (RRF_K + rank);
        SearchHit h = fused.hits().get(rank - 1);
        assertEquals(expected, h.score(), EPS, "leg-2 contribution must be clamp(w)/(k+rank)");
      }
    }
  }

  @Test
  @DisplayName("RRF law: a doc in both legs sums the two contributions (1/(k+r1) + w/(k+r2))")
  void rrf_bothLegs_sumsContributions() {
    Random rnd = new Random(7L);
    for (int it = 0; it < ITERS; it++) {
      double w = rnd.nextDouble();
      // shared doc s0 at rank 1 in leg1 and rank 2 in leg2
      List<SearchHit> leg1 = List.of(hit("s0", 5f), hit("a", 4f));
      List<SearchHit> leg2 = List.of(hit("b", 3f), hit("s0", 2f));
      SearchResult fused =
          HybridFusionUtils.fuseWithRRF(result(leg1), result(leg2), 1000, false, 100, w, null);
      double s0 =
          fused.hits().stream().filter(h -> h.docId().equals("s0")).findFirst().orElseThrow().score();
      double expected = 1.0 / (RRF_K + 1) + w / (RRF_K + 2);
      assertEquals(expected, s0, EPS, "shared doc must sum both legs' contributions");
    }
  }

  @Test
  @DisplayName("RRF law: the result2-only cap bounds how many leg-2-only docs are admitted")
  void rrf_result2OnlyCap_isRespected() {
    for (int cap = 0; cap <= 5; cap++) {
      List<SearchHit> leg2 = new ArrayList<>();
      for (int i = 0; i < 5; i++) leg2.add(hit("v" + i, 5f - i));
      SearchResult fused =
          HybridFusionUtils.fuseWithRRF(result(List.of()), result(leg2), 1000, false, cap, 1.0, null);
      assertEquals(Math.min(5, cap), fused.hits().size(), "only `cap` leg-2-only docs admitted");
    }
  }

  @Test
  @DisplayName("RRF law: fused hits carry merged fields from both legs (kills the mergeFields elision)")
  void rrf_mergesFieldsFromBothLegs() {
    SearchHit a1 = new SearchHit("doc", 5f, Map.of("title", "T"));
    SearchHit a2 = new SearchHit("doc", 4f, Map.of("author", "A"));
    SearchResult fused =
        HybridFusionUtils.fuseWithRRF(result(List.of(a1)), result(List.of(a2)), 10, false, 100, 1.0, null);
    Map<String, String> fields = fused.hits().get(0).fields();
    assertEquals("T", fields.get("title"), "leg-1 field must survive the merge");
    assertEquals("A", fields.get("author"), "leg-2 field must be merged in");
  }

  @Test
  @DisplayName("RRF law: debug=true attaches per-leg rrf debug scores (kills the mergeDebugScores/debug elision)")
  void rrf_debugAttachesPerLegScores() {
    SearchResult fused =
        HybridFusionUtils.fuseWithRRF(
            result(List.of(hit("d", 5f))), result(List.of(hit("d", 4f))), 10, true, 100, 1.0, null);
    Map<String, Float> debug = fused.hits().get(0).debugScores();
    assertTrue(debug.containsKey("sparse_rrf"), "debug must expose the leg-1 rrf component");
    assertTrue(debug.containsKey("vector_rrf"), "debug must expose the leg-2 rrf component");
    assertEquals(1.0f / (RRF_K + 1), debug.get("sparse_rrf"), 1e-6f);
  }

  @Test
  @DisplayName("RRF law: equal fused scores break ties deterministically by leg-1 score (total order)")
  void rrf_tieBreaksByLeg1ScoreThenDocId() {
    // p and q both leg-1-only at the same rank slot is impossible; instead give both the same fused
    // score by placing each alone in a different leg at the same rank, then the comparator must
    // fall through to the leg-1-score tiebreak. p has a leg-1 score, q does not.
    List<SearchHit> leg1 = List.of(hit("p", 9f));
    List<SearchHit> leg2 = List.of(hit("q", 1f));
    SearchResult fused =
        HybridFusionUtils.fuseWithRRF(result(leg1), result(leg2), 10, false, 100, 1.0, null);
    // both score 1/(k+1); p (leg-1 score 9) must precede q (leg-1 score 0) — comparator tiebreak.
    assertEquals("p", fused.hits().get(0).docId(), "leg-1-scored doc must win an equal-RRF tie");
    assertEquals("q", fused.hits().get(1).docId());
  }

  // ---- CC laws (score-monotone, min-max normalized) -----------------------

  @Test
  @DisplayName("CC law: alpha=1 weights the dense leg only (ccScore = alpha*normDense + (1-alpha)*normSparse)")
  void cc_alphaOne_isDenseMonotone() {
    List<SearchHit> sparse = List.of(hit("a", 10f), hit("b", 5f), hit("c", 1f));
    List<SearchHit> dense = List.of(hit("c", 9f), hit("b", 8f), hit("a", 7f));
    SearchResult fused = HybridFusionUtils.fuseWithCC(result(sparse), result(dense), 10, 1.0, false, false);
    // alpha=1 → sparse ignored → order follows dense score desc: c, b, a
    List<String> order = fused.hits().stream().map(SearchHit::docId).toList();
    assertEquals(List.of("c", "b", "a"), order, "alpha=1 must rank by dense score only");
  }

  @Test
  @DisplayName("CC law: alpha=0 weights the sparse leg only")
  void cc_alphaZero_isSparseMonotone() {
    List<SearchHit> sparse = List.of(hit("a", 10f), hit("b", 5f), hit("c", 1f));
    List<SearchHit> dense = List.of(hit("c", 9f), hit("b", 8f), hit("a", 7f));
    SearchResult fused = HybridFusionUtils.fuseWithCC(result(sparse), result(dense), 10, 0.0, false, false);
    // alpha=0 → dense ignored → order follows sparse score desc: a, b, c
    List<String> order = fused.hits().stream().map(SearchHit::docId).toList();
    assertEquals(List.of("a", "b", "c"), order, "alpha=0 must rank by sparse score only");
  }

  // ---- Parent-length multiplier laws (public, directly testable) ----------

  @Test
  @DisplayName("SPLADE multiplier law: 1.0 at/below 1024 tokens, 0.0 at/above 4096, linear between")
  void spladeMultiplier_piecewiseLinear() {
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(0L), EPS);
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(512L), EPS);
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(1024L), EPS);
    assertEquals(0.0, HybridFusionUtils.spladeParentLengthMultiplier(4096L), EPS);
    assertEquals(0.0, HybridFusionUtils.spladeParentLengthMultiplier(9000L), EPS);
    // midpoint 2560 = 1024 + 3072/2 → 0.5 (kills the interpolation math + non-zero return mutants)
    assertEquals(0.5, HybridFusionUtils.spladeParentLengthMultiplier(2560L), EPS);
    // strictly decreasing across the interpolation band
    double prev = Double.MAX_VALUE;
    for (long n = 1024; n <= 4096; n += 256) {
      double m = HybridFusionUtils.spladeParentLengthMultiplier(n);
      assertTrue(m <= prev, "multiplier must be non-increasing in token count");
      prev = m;
    }
  }

  @Test
  @DisplayName("Chunk-branch multiplier law: minMultiplier at/below 1024, 1.0 at/above 4096, linear between")
  void chunkBranchMultiplier_piecewiseLinear() {
    double min = 0.3;
    assertEquals(min, HybridFusionUtils.chunkBranchParentLengthMultiplier(512L, min), EPS);
    assertEquals(min, HybridFusionUtils.chunkBranchParentLengthMultiplier(1024L, min), EPS);
    assertEquals(1.0, HybridFusionUtils.chunkBranchParentLengthMultiplier(4096L, min), EPS);
    assertEquals(1.0, HybridFusionUtils.chunkBranchParentLengthMultiplier(8000L, min), EPS);
    // midpoint → min + (1-min)/2
    assertEquals(min + (1.0 - min) / 2.0, HybridFusionUtils.chunkBranchParentLengthMultiplier(2560L, min), EPS);
  }

  @Test
  @DisplayName("Multiplier law: a blank/absent/garbage parent-token-count field falls back to 1.0")
  void multiplier_fieldFallbacks() {
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier((Map<String, String>) null), EPS);
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(Map.of()), EPS);
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(Map.of("parent_token_count", "  ")), EPS);
    assertEquals(1.0, HybridFusionUtils.spladeParentLengthMultiplier(Map.of("parent_token_count", "abc")), EPS);
    assertEquals(0.5, HybridFusionUtils.spladeParentLengthMultiplier(Map.of("parent_token_count", "2560")), EPS);
  }
}
