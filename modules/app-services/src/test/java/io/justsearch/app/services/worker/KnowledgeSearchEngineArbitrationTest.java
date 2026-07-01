/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.HitStage;
import io.justsearch.ipc.SearchResult;
import io.justsearch.reranker.RerankerConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 643 (E1/E2, perf-skip): pure-math tests for
 * {@link KnowledgeSearchEngine#computeJudgeArbitrationAlpha} and
 * {@link KnowledgeSearchEngine#isFusionDecisiveForSkip}.
 *
 * <p>Mirrors the testability pattern and naming convention of D-004's {@code HybridSearchOpsTest}
 * arbitration-math tests ({@code legOf}, {@code legDescending}, {@code arb(...)}) — both helpers
 * are package-private static and pure, so they are exercised directly with hand-built {@code
 * SearchResult}/{@code HitStage} protos instead of through the full search pipeline.
 */
@DisplayName("KnowledgeSearchEngine judge-arbitration gate")
class KnowledgeSearchEngineArbitrationTest {

  private static final double BASE_ALPHA = 0.5;
  private static final double FUSION_PROTECT_ALPHA = 0.85;

  /** A window of {@code n} candidates, each with a {@code sparse-retrieval}/{@code
   * dense-retrieval} rank equal to its position (both legs agree on order and membership). */
  private static List<SearchResult> agreeingWindow(int n) {
    List<SearchResult> window = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      window.add(
          SearchResult.newBuilder()
              .setId("doc" + i)
              .addTrace(HitStage.newBuilder().setId("sparse-retrieval").setRank(i + 1))
              .addTrace(HitStage.newBuilder().setId("dense-retrieval").setRank(i + 1))
              .build());
    }
    return window;
  }

  /** A window where the sparse and dense top-K sets are entirely disjoint (legs disagree). */
  private static List<SearchResult> disagreeingWindow(int n) {
    List<SearchResult> window = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      window.add(
          SearchResult.newBuilder()
              .setId("sparse-doc" + i)
              .addTrace(HitStage.newBuilder().setId("sparse-retrieval").setRank(i + 1))
              .build());
      window.add(
          SearchResult.newBuilder()
              .setId("dense-doc" + i)
              .addTrace(HitStage.newBuilder().setId("dense-retrieval").setRank(i + 1))
              .build());
    }
    return window;
  }

  /** A window with no leg trace at all (no comparable signal). */
  private static List<SearchResult> noLegSignalWindow(int n) {
    List<SearchResult> window = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      window.add(SearchResult.newBuilder().setId("doc" + i).build());
    }
    return window;
  }

  /** An agreeing window where a {@code chunk-merge} stage is also present (untrustworthy). */
  private static List<SearchResult> chunkBranchWindow(int n) {
    List<SearchResult> window = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      window.add(
          SearchResult.newBuilder()
              .setId("doc" + i)
              .addTrace(HitStage.newBuilder().setId("sparse-retrieval").setRank(i + 1))
              .addTrace(HitStage.newBuilder().setId("dense-retrieval").setRank(i + 1))
              .addTrace(HitStage.newBuilder().setId("chunk-merge"))
              .build());
    }
    return window;
  }

  // ==================== computeJudgeArbitrationAlpha ====================

  @Test
  @DisplayName("thin CE margin + agreeing legs → protect via fusion (the only new behavior)")
  void thinMargin_agreeingLegs_protectsViaFusion() {
    float[] ceScores = {1.0f, 0.99f, 0.5f}; // top1-top2 gap ~0 after normalization → thin margin
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            agreeingWindow(3), ceScores, BASE_ALPHA, FUSION_PROTECT_ALPHA);
    assertEquals(FUSION_PROTECT_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("decisive CE margin → baseAlpha unchanged (today's behavior), even if legs agree")
  void decisiveMargin_returnsBaseAlpha() {
    float[] ceScores = {5.0f, 0.1f, 0.0f}; // large top1-top2 gap → CE confident
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            agreeingWindow(3), ceScores, BASE_ALPHA, FUSION_PROTECT_ALPHA);
    assertEquals(BASE_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("thin CE margin but disagreeing legs → baseAlpha unchanged (arbitrating real signal)")
  void thinMargin_disagreeingLegs_returnsBaseAlpha() {
    float[] ceScores = {1.0f, 0.99f, 0.5f};
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            disagreeingWindow(3), ceScores, BASE_ALPHA, FUSION_PROTECT_ALPHA);
    assertEquals(BASE_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("chunk-merge present → leg signal untrustworthy → baseAlpha unchanged")
  void chunkBranchActive_returnsBaseAlpha() {
    float[] ceScores = {1.0f, 0.99f, 0.5f}; // thin margin, would otherwise protect via fusion
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            chunkBranchWindow(3), ceScores, BASE_ALPHA, FUSION_PROTECT_ALPHA);
    assertEquals(BASE_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("no leg signal at all → treated as agree, but a decisive CE margin still wins")
  void noLegSignal_decisiveMargin_returnsBaseAlpha() {
    float[] ceScores = {5.0f, 0.1f, 0.0f};
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            noLegSignalWindow(3), ceScores, BASE_ALPHA, FUSION_PROTECT_ALPHA);
    assertEquals(BASE_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("no leg signal at all + thin CE margin → protects via fusion (mirrors D-004's empty-leg convention)")
  void noLegSignal_thinMargin_protectsViaFusion() {
    float[] ceScores = {1.0f, 0.99f, 0.5f};
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            noLegSignalWindow(3), ceScores, BASE_ALPHA, FUSION_PROTECT_ALPHA);
    assertEquals(FUSION_PROTECT_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("fewer than 2 candidates → no margin signal → baseAlpha unchanged")
  void fewerThanTwoCandidates_returnsBaseAlpha() {
    assertEquals(
        BASE_ALPHA,
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            agreeingWindow(1), new float[] {1.0f}, BASE_ALPHA, FUSION_PROTECT_ALPHA),
        1e-9);
    assertEquals(
        BASE_ALPHA,
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            List.of(), new float[0], BASE_ALPHA, FUSION_PROTECT_ALPHA),
        1e-9);
  }

  @Test
  @DisplayName("never lowers alpha below baseAlpha (Math.max floor, mirrors D-004)")
  void neverLowersAlphaBelowBase() {
    float[] ceScores = {1.0f, 0.99f, 0.5f};
    double alpha =
        KnowledgeSearchEngine.computeJudgeArbitrationAlpha(
            agreeingWindow(3), ceScores, /* baseAlpha= */ 0.9, FUSION_PROTECT_ALPHA);
    assertEquals(0.9, alpha, 1e-9, "baseAlpha (0.9) already exceeds fusionProtectAlpha (0.85)");
  }

  // ==================== isFusionDecisiveForSkip ====================

  @Test
  @DisplayName("perf-skip: agreeing legs alone (no CE score needed) → decisive, safe to skip")
  void agreeingLegs_isDecisiveForSkip() {
    assertTrue(KnowledgeSearchEngine.isFusionDecisiveForSkip(agreeingWindow(3)));
  }

  @Test
  @DisplayName("perf-skip: disagreeing legs → not decisive, do not skip")
  void disagreeingLegs_isNotDecisiveForSkip() {
    assertFalse(KnowledgeSearchEngine.isFusionDecisiveForSkip(disagreeingWindow(3)));
  }

  @Test
  @DisplayName(
      "perf-skip: no leg signal → NOT decisive (stricter than the blend gate's own convention)")
  void noLegSignal_isNotDecisiveForSkip() {
    // Unlike computeJudgeArbitrationAlpha (where "no signal" maps to "agree, don't intervene",
    // and "don't intervene" for the blend still means "call the CE"), perf-skip's "don't
    // intervene" means "call the CE" too -- so an inconclusive signal must map to false here,
    // not true. This is the deliberate asymmetry documented on isFusionDecisiveForSkip.
    assertFalse(KnowledgeSearchEngine.isFusionDecisiveForSkip(noLegSignalWindow(3)));
  }

  @Test
  @DisplayName("perf-skip: chunk-merge present → untrustworthy signal → do not skip")
  void chunkBranchActive_isNotDecisiveForSkip() {
    assertFalse(KnowledgeSearchEngine.isFusionDecisiveForSkip(chunkBranchWindow(3)));
  }

  // ==================== resolveBlendAlpha / shouldSkipCrossEncoder (wiring gates) ====================
  //
  // Tempdoc 643 critical-analysis-pass: these two methods are the exact gating logic that used to
  // live inline in the RPC-adjacent wiring (no test previously exercised it — only the pure math
  // functions above were tested). A wrong `&&`/`||` or a wrong flag here would silently change
  // ranking/latency behavior without ever touching computeJudgeArbitrationAlpha or
  // isFusionDecisiveForSkip themselves, so the GATE needs its own coverage, independent of the CE
  // RPC (there is no existing fake/seam for that RPC — these tests need none, by construction).

  private static RerankerConfig config(
      boolean judgeArbitrationEnabled, boolean judgeArbitrationSkipEnabled) {
    return new RerankerConfig(
        true,
        Path.of("/model"),
        20,
        200,
        5,
        512,
        true,
        0,
        16_000,
        true,
        BASE_ALPHA,
        judgeArbitrationEnabled,
        FUSION_PROTECT_ALPHA,
        judgeArbitrationSkipEnabled);
  }

  @Test
  @DisplayName("resolveBlendAlpha: arbitration disabled → always the static judgeBlendAlpha")
  void resolveBlendAlpha_arbitrationDisabled_returnsStaticAlpha() {
    // Thin margin + agreeing legs would trigger fusion-protect if arbitration were on -- disabled
    // must ignore the signal entirely and return the static config value unchanged.
    float[] ceScores = {1.0f, 0.99f, 0.5f};
    double alpha = KnowledgeSearchEngine.resolveBlendAlpha(
        config(false, false), agreeingWindow(3), ceScores);
    assertEquals(BASE_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("resolveBlendAlpha: arbitration enabled → delegates to computeJudgeArbitrationAlpha")
  void resolveBlendAlpha_arbitrationEnabled_delegatesToSignal() {
    float[] ceScores = {1.0f, 0.99f, 0.5f}; // thin margin + agreeing legs → fusion-protect fires
    double alpha = KnowledgeSearchEngine.resolveBlendAlpha(
        config(true, false), agreeingWindow(3), ceScores);
    assertEquals(FUSION_PROTECT_ALPHA, alpha, 1e-9);
  }

  @Test
  @DisplayName("shouldSkipCrossEncoder: both flags off → never skip, even with a decisive window")
  void shouldSkipCrossEncoder_bothOff_returnsFalse() {
    assertFalse(
        KnowledgeSearchEngine.shouldSkipCrossEncoder(config(false, false), agreeingWindow(3)));
  }

  @Test
  @DisplayName(
      "shouldSkipCrossEncoder: arbitration on, skip off → never skip (both flags required)")
  void shouldSkipCrossEncoder_arbitrationOnSkipOff_returnsFalse() {
    assertFalse(
        KnowledgeSearchEngine.shouldSkipCrossEncoder(config(true, false), agreeingWindow(3)));
  }

  @Test
  @DisplayName(
      "shouldSkipCrossEncoder: arbitration off, skip on → never skip (the wrong-gate case: skip"
          + " alone must not be sufficient)")
  void shouldSkipCrossEncoder_arbitrationOffSkipOn_returnsFalse() {
    assertFalse(
        KnowledgeSearchEngine.shouldSkipCrossEncoder(config(false, true), agreeingWindow(3)));
  }

  @Test
  @DisplayName("shouldSkipCrossEncoder: both on + decisive window → skip")
  void shouldSkipCrossEncoder_bothOnDecisiveWindow_returnsTrue() {
    assertTrue(
        KnowledgeSearchEngine.shouldSkipCrossEncoder(config(true, true), agreeingWindow(3)));
  }

  @Test
  @DisplayName("shouldSkipCrossEncoder: both on + non-decisive (disagreeing) window → do not skip")
  void shouldSkipCrossEncoder_bothOnNonDecisiveWindow_returnsFalse() {
    assertFalse(
        KnowledgeSearchEngine.shouldSkipCrossEncoder(config(true, true), disagreeingWindow(3)));
  }
}
