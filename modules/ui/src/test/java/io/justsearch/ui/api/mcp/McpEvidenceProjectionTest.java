/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.SearchTrace.Degradation;
import io.justsearch.app.api.knowledge.SearchTrace.HitStage;
import io.justsearch.app.api.knowledge.SearchTrace.Qpp;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.api.knowledge.SearchTrace.StageStatus;
import io.justsearch.app.api.knowledge.SearchTrace.TraceStage;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 658 — conformance test for {@link McpEvidenceProjection} (the execution-surface register
 * guard for the MCP evidence projection). Asserts the projection is a total, correctly-typed view of
 * the canonical {@link SearchTrace} and {@code ContextCitation}/{@code ContextResult} records: every
 * evidence field surfaces at the agent altitude, enum values serialize by their stable
 * {@code wireId}/{@code wireValue} (not the Java enum name), and the numeric detail tier appears only
 * when populated. Pure-function projection → no backend needed (mirrors SearchTraceSpanProjectionTest).
 */
@DisplayName("McpEvidenceProjection: agent-surface evidence is a total projection of the canonical records")
final class McpEvidenceProjectionTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object o) {
    return (List<Object>) o;
  }

  /**
   * Reflective totality guard — the Java analogue of the FE's {@code assertFieldRoles} pattern
   * (evidenceProjection.ts). Asserts every record component of {@code type} is a key in
   * {@code projectedSlice} unless it is declared intentionally {@code elided}. This makes the
   * silent-field-drop class unrepresentable: adding a field to a canonical evidence record both breaks
   * the maximal-fixture constructor arity below (forcing a fixture update) AND trips this guard until
   * the projection actually surfaces the field (tempdoc 658 post-review hardening).
   */
  private static void assertCovers(
      Class<? extends Record> type, Map<String, Object> projectedSlice, Set<String> elided) {
    for (RecordComponent rc : type.getRecordComponents()) {
      if (elided.contains(rc.getName())) {
        continue;
      }
      assertTrue(
          projectedSlice.containsKey(rc.getName()),
          type.getSimpleName()
              + " field '"
              + rc.getName()
              + "' is not projected into the MCP evidence — either project it or add it to the"
              + " declared elided set with a reason.");
    }
  }

  @Test
  @DisplayName("search: projects query-level trace (mode/decision/qpp/degradation + stages by wireId)")
  void searchProjectsQueryLevelTrace() {
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            new Qpp(1.5f, 2.0f, 3.0f),
            new Degradation(true, "FINGERPRINT_MISMATCH", true, "NO_EMBEDDING_SERVICE", false, "absent"),
            List.of(
                new TraceStage(StageId.SPARSE_RETRIEVAL, StageStatus.EXECUTED, null, 5L, null, 42L),
                new TraceStage(
                    StageId.DENSE_RETRIEVAL, StageStatus.SKIPPED, "vector_blocked", null, null, null)));
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(), null, null, null, null, null, null, null, trace);

    Map<String, Object> evidence = McpEvidenceProjection.searchEvidence(resp, false);
    Map<String, Object> t = asMap(evidence.get("searchTrace"));

    assertEquals("HYBRID", t.get("effectiveMode"));
    assertEquals("multi_leg", t.get("decisionKind"));
    Map<String, Object> qpp = asMap(t.get("qpp"));
    assertEquals(1.5f, qpp.get("maxIdf"));
    assertEquals(2.0f, qpp.get("avgIctf"));
    assertEquals(3.0f, qpp.get("queryScope"));

    Map<String, Object> deg = asMap(t.get("degradation"));
    assertEquals(true, deg.get("vectorBlocked"));
    assertEquals("FINGERPRINT_MISMATCH", deg.get("vectorBlockedReason"));
    assertEquals(true, deg.get("hybridFallback"));
    assertEquals("NO_EMBEDDING_SERVICE", deg.get("hybridFallbackReason"));
    assertEquals(false, deg.get("spladeExecuted"));
    assertEquals("absent", deg.get("spladeSkipReason"));

    List<Object> stages = asList(t.get("stages"));
    assertEquals(2, stages.size());
    Map<String, Object> s0 = asMap(stages.get(0));
    // The stable wireId, NOT the Java enum name — this is the projection's load-bearing correctness.
    assertEquals("sparse-retrieval", s0.get("id"));
    assertEquals("executed", s0.get("status"));
    assertEquals(5L, s0.get("ms"));
    assertEquals(42L, s0.get("cardinality"));
    Map<String, Object> s1 = asMap(stages.get(1));
    assertEquals("dense-retrieval", s1.get("id"));
    assertEquals("skipped", s1.get("status"));
    assertEquals("vector_blocked", s1.get("reason"));
    assertNull(s1.get("ms"));
  }

  @Test
  @DisplayName(
      "search: per-hit trace + fusion legScores under detail=true; numeric detail only when present")
  void searchProjectsPerHitTraceAndLegScores() {
    HitStage sparse = new HitStage(StageId.SPARSE_RETRIEVAL, 1, 3.3f, null);
    HitStage fused = new HitStage(StageId.FUSION, 1, 0.9f, Map.of("cc_weight_sparse", 0.6f));
    HitStage splade = new HitStage(StageId.SPLADE_RETRIEVAL, 2, 1.7f, null);
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1",
            0.9d,
            Map.of("title", "Troubleshooting", "path", "help/troubleshooting.md"),
            List.of(),
            List.of(),
            List.of(),
            List.of(sparse, fused, splade));
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(hit), null, null, null, null, null, null, null, null);

    Map<String, Object> evidence = McpEvidenceProjection.searchEvidence(resp, true);
    List<Object> results = asList(evidence.get("results"));
    assertEquals(1, results.size());
    Map<String, Object> h = asMap(results.get(0));
    assertEquals("doc-1", h.get("id"));
    assertEquals("Troubleshooting", h.get("title"));
    assertEquals("help/troubleshooting.md", h.get("path"));
    assertEquals(0.9d, h.get("score"));

    List<Object> hitStages = asList(h.get("trace"));
    assertEquals(3, hitStages.size());
    Map<String, Object> hsSparse = asMap(hitStages.get(0));
    assertEquals("sparse-retrieval", hsSparse.get("id"));
    assertEquals(1, hsSparse.get("rank"));
    assertEquals(3.3f, hsSparse.get("score"));
    // Numeric detail tier absent on the sparse stage (no detail map), present on the fusion stage.
    assertFalse(hsSparse.containsKey("detail"));
    assertTrue(asMap(hitStages.get(1)).containsKey("detail"));

    Map<String, Object> legs = asMap(h.get("legScores"));
    assertEquals(3.3f, legs.get("sparse"));
    assertEquals(0f, legs.get("dense"));
    // splade must be projected + carry the SPLADE leg score (previously never asserted anywhere).
    assertEquals(1.7f, legs.get("splade"));
    assertEquals(0.9f, legs.get("fused"));
  }

  @Test
  @DisplayName("answer: projects every ContextCitation field + the quality/degradation signals")
  void answerProjectsCitationsAndQuality() {
    ContextCitation cite =
        new ContextCitation("doc-42", 2, 5, 100, 260, 0.87f, "an excerpt", 12, 18, "Overview", 2);
    ContextResult result =
        new ContextResult(
            "assembled context",
            3,
            7,
            0,
            List.of(cite),
            "HYBRID",
            "HYBRID_AVAILABLE",
            false,
            List.of(),
            new QualitySignals(0.87f, 0.1f, 0.42f, 7, 3));

    Map<String, Object> evidence = McpEvidenceProjection.answerEvidence(result);

    List<Object> citations = asList(evidence.get("citations"));
    assertEquals(1, citations.size());
    Map<String, Object> c = asMap(citations.get(0));
    assertEquals("doc-42", c.get("parentDocId"));
    assertEquals(2, c.get("chunkIndex"));
    assertEquals(5, c.get("chunkTotal"));
    assertEquals(100, c.get("startChar"));
    assertEquals(260, c.get("endChar"));
    assertEquals(0.87f, c.get("score"));
    assertEquals("an excerpt", c.get("excerpt"));
    assertEquals(12, c.get("startLine"));
    assertEquals(18, c.get("endLine"));
    assertEquals("Overview", c.get("headingText"));
    assertEquals(2, c.get("headingLevel"));

    // Every quality field is projected — the full ContextResult counts + all five QualitySignals
    // fields (guards against a silent-drop regression like the one this test was strengthened for).
    Map<String, Object> quality = asMap(evidence.get("quality"));
    assertEquals(7, quality.get("chunksFound"));
    assertEquals(3, quality.get("chunksUsed"));
    assertEquals("HYBRID", quality.get("retrievalMode"));
    assertEquals("HYBRID_AVAILABLE", quality.get("retrievalModeReason"));
    assertEquals(false, quality.get("contextTruncated"));
    assertEquals(0.42f, quality.get("retrievalCoverage"));
    assertEquals(0.87f, quality.get("bestChunkScore"));
    assertEquals(0.1f, quality.get("scoreGap"));
    assertEquals(7, quality.get("chunksConsidered"));
    assertEquals(3, quality.get("chunksIncluded"));
  }

  @Test
  @DisplayName("answer: full-doc fallback (empty citations) projects an empty citation list, not null")
  void answerFallbackEmptyCitations() {
    ContextResult fallback =
        new ContextResult(
            "full doc", 0, 0, 1, List.of(), "FULLTEXT_FALLBACK", "NO_CHUNKS_FOUND", false, List.of());
    Map<String, Object> evidence = McpEvidenceProjection.answerEvidence(fallback);
    assertTrue(asList(evidence.get("citations")).isEmpty());
    assertEquals("FULLTEXT_FALLBACK", asMap(evidence.get("quality")).get("retrievalMode"));
  }

  /**
   * Tempdoc 770 — the maximal fixture shared by the two halves of the totality guard: every field
   * non-null / non-empty, so the projection's null-omission never hides a component from the
   * reflective check. {@code path} is deliberately distinct from {@code id} (the projection elides
   * a path equal to the id), so the field is present to be covered.
   */
  private static KnowledgeSearchResponse maximalSearchResponse() {
    TraceStage stage =
        new TraceStage(StageId.FUSION, StageStatus.EXECUTED, "reason", 5L, "fusion-detail", 12L);
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            new Qpp(1.5f, 2.0f, 3.0f),
            new Degradation(true, "R1", true, "R2", true, "R3"),
            List.of(stage));
    HitStage hitStage = new HitStage(StageId.FUSION, 1, 0.9f, Map.of("cc", 0.9f));
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1", 0.9d, Map.of("title", "T", "path", "P"),
            List.of(), List.of(), List.of(), List.of(hitStage));
    return new KnowledgeSearchResponse(
        1L, 1L, 5L, List.of(hit), null, null, null, null, null, null, null, trace);
  }

  @Test
  @DisplayName(
      "totality (b): the DEFAULT tier omits exactly {trace, legScores} relative to the detail tier"
          + " — nothing else silently stops shipping (tempdoc 770)")
  void defaultTierOmitsExactlyTheProvenanceBlock() {
    KnowledgeSearchResponse resp = maximalSearchResponse();

    Map<String, Object> withDetail = McpEvidenceProjection.searchEvidence(resp, true);
    Map<String, Object> byDefault = McpEvidenceProjection.searchEvidence(resp, false);

    // Response-level shape is identical between tiers — only the per-hit block is tiered.
    assertEquals(withDetail.keySet(), byDefault.keySet());

    Map<String, Object> detailHit = asMap(asList(withDetail.get("results")).get(0));
    Map<String, Object> defaultHit = asMap(asList(byDefault.get("results")).get(0));

    Set<String> omitted = new java.util.LinkedHashSet<>(detailHit.keySet());
    omitted.removeAll(defaultHit.keySet());
    assertEquals(
        Set.of("trace", "legScores"),
        omitted,
        "the default tier must omit the ranking-provenance block and nothing else");
    assertTrue(
        defaultHit.keySet().containsAll(
            detailHit.keySet().stream().filter(k -> !omitted.contains(k)).toList()),
        "the default tier must add no field the detail tier lacks");

    // Every retained field is byte-identical between tiers — the gate elides, it does not reshape.
    for (String key : defaultHit.keySet()) {
      assertEquals(detailHit.get(key), defaultHit.get(key), "field '" + key + "' differs by tier");
    }
  }

  @Test
  @DisplayName(
      "search: excerpts survive BOTH tiers (the only document text the agent receives) and `path`"
          + " is emitted only when it differs from `id` (tempdoc 770)")
  void excerptsAreUngatedAndPathIsElidedWhenEqualToId() {
    KnowledgeSearchResponse.ExcerptRegion region =
        new KnowledgeSearchResponse.ExcerptRegion("the excerpt body", 0, 16, 1, List.of());
    // Hit A: path differs from id → path is informative, so it is emitted.
    KnowledgeSearchResponse.Hit distinctPath =
        new KnowledgeSearchResponse.Hit(
            "doc-1", 0.9d, Map.of("path", "C:/corpus/a.md"),
            List.of(), List.of(), List.of(region),
            List.of(new HitStage(StageId.FUSION, 1, 0.9f, null)));
    // Hit B: path IS the id (the measured case — 14,617/14,617 hits) → duplicate, so elided.
    KnowledgeSearchResponse.Hit pathEqualsId =
        new KnowledgeSearchResponse.Hit(
            "C:/corpus/b.md", 0.8d, Map.of("path", "C:/corpus/b.md"),
            List.of(), List.of(), List.of(region),
            List.of(new HitStage(StageId.FUSION, 2, 0.8f, null)));
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            2L, 2L, 5L, List.of(distinctPath, pathEqualsId),
            null, null, null, null, null, null, null, null);

    for (boolean includeDetail : new boolean[] {false, true}) {
      List<Object> results =
          asList(McpEvidenceProjection.searchEvidence(resp, includeDetail).get("results"));

      Map<String, Object> a = asMap(results.get(0));
      assertEquals("C:/corpus/a.md", a.get("path"), "distinct path is informative — keep it");
      assertEquals(
          "the excerpt body",
          asMap(asList(a.get("excerpts")).get(0)).get("text"),
          "excerpts must never be gated (detail=" + includeDetail + ")");

      Map<String, Object> b = asMap(results.get(1));
      assertEquals("C:/corpus/b.md", b.get("id"));
      assertFalse(b.containsKey("path"), "path equal to id is a verbatim duplicate — elide it");
      assertFalse(asList(b.get("excerpts")).isEmpty(), "excerpts must never be gated");
    }
  }

  @Test
  @DisplayName(
      "totality (a): with detail=true, every field of every canonical evidence record is projected"
          + " (reflective guard)")
  void projectionCoversEveryEvidenceField() {
    // Tempdoc 770: totality is asserted over the DETAIL tier — the union of what ships — with the
    // default tier's exact subset relationship pinned separately by
    // defaultTierOmitsExactlyTheProvenanceBlock(). A totality guard must describe what actually
    // ships, not a test-only path (770 §G).
    Map<String, Object> searchEvidence =
        McpEvidenceProjection.searchEvidence(maximalSearchResponse(), true);

    Map<String, Object> traceMap = asMap(searchEvidence.get("searchTrace"));
    // `version` is the structural-compat hint the FE explain panel also elides (searchTraceExplain.ts).
    assertCovers(SearchTrace.class, traceMap, Set.of("version"));
    assertCovers(Qpp.class, asMap(traceMap.get("qpp")), Set.of());
    assertCovers(Degradation.class, asMap(traceMap.get("degradation")), Set.of());
    assertCovers(TraceStage.class, asMap(asList(traceMap.get("stages")).get(0)), Set.of());
    Map<String, Object> hitMap = asMap(asList(searchEvidence.get("results")).get(0));
    assertCovers(HitStage.class, asMap(asList(hitMap.get("trace")).get(0)), Set.of());
    // LegScores is a canonical record projected into the per-hit `legScores` map — the exact 4-field
    // hand-mapping shape that caused Defect 2, so it is guarded reflectively too (all four legs incl.
    // splade must be a key).
    assertCovers(SearchTrace.LegScores.class, asMap(hitMap.get("legScores")), Set.of());

    ContextCitation cite =
        new ContextCitation("doc-42", 2, 5, 100, 260, 0.87f, "excerpt", 12, 18, "Overview", 2);
    ContextResult result =
        new ContextResult(
            "ctx", 3, 7, 0, List.of(cite), "HYBRID", "HYBRID_AVAILABLE", false, List.of(),
            new QualitySignals(0.87f, 0.1f, 0.42f, 7, 3));
    Map<String, Object> answerEvidence = McpEvidenceProjection.answerEvidence(result);
    assertCovers(ContextCitation.class, asMap(asList(answerEvidence.get("citations")).get(0)), Set.of());
    // The `quality` map is a SUPERSET (QualitySignals fields + ContextResult counts) — assert it covers
    // every QualitySignals component.
    assertCovers(QualitySignals.class, asMap(answerEvidence.get("quality")), Set.of());

    // Intentionally NOT reflectively guarded: KnowledgeSearchResponse.Hit and ContextResult are
    // selective carriers (they surface identity + the nested evidence records above, not every field —
    // e.g. ContextResult.sections is not ranking-evidence). Their evidence-bearing content is the
    // nested records this test already covers. (Tempdoc 725 W1: Hit.matchSpans/excerptRegions ARE now
    // projected — as matchedTerms/matchedFields/excerpts, see McpSearchTraceLegibilityTest — but Hit
    // stays a selective, non-reflectively-guarded carrier overall.)
  }
}
