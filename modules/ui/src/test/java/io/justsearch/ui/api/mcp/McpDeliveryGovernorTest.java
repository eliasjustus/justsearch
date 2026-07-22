/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.MatchSpan;
import io.justsearch.app.api.knowledge.SearchTrace.HitStage;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 775 §E/§C — the delivery governor: deterministic degradation of the assembled
 * {@code justsearch_search} MCP payload at the 770 §E.3 client truncation cap. Extends the 770
 * golden/totality guards (see {@link McpEvidenceProjectionTest},
 * {@link McpTierEquivalenceGoldenTest}) to the governor path: the assertions here pin the
 * degradation order (numeric provenance first, then whole tail results, never mid-payload / mid-span),
 * the budget-boundary behaviour, the 0-disables escape hatch, byte-stable determinism, and the
 * explicit machine-readable notice.
 */
@DisplayName("McpDeliveryGovernor: deterministic degradation at the delivery cap (tempdoc 775 §E/§C)")
final class McpDeliveryGovernorTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  // ---- payload builders (mimic McpEvidenceProjection#searchEvidence output shape) ----

  private static Map<String, Object> hitMap(int rank, int excerptLen, boolean provenance) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("path", "docs/doc-" + rank + ".md");
    h.put("score", 1.0d - rank * 0.001d);
    List<Map<String, Object>> excerpts = new ArrayList<>();
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("text", "x".repeat(excerptLen));
    e.put("startChar", 0);
    e.put("endChar", excerptLen);
    excerpts.add(e);
    h.put("excerpts", excerpts);
    if (provenance) {
      List<Map<String, Object>> trace = new ArrayList<>();
      Map<String, Object> ts = new LinkedHashMap<>();
      ts.put("id", "fusion");
      ts.put("rank", rank);
      ts.put("score", 0.9f);
      ts.put("detail", Map.of("cc_weight_sparse", 0.6f, "cc_weight_dense", 0.4f));
      trace.add(ts);
      h.put("trace", trace);
      Map<String, Object> legs = new LinkedHashMap<>();
      legs.put("sparse", 0.5f);
      legs.put("dense", 0.5f);
      legs.put("splade", 0.5f);
      legs.put("fused", 0.9f);
      h.put("legScores", legs);
    }
    return h;
  }

  private static Map<String, Object> payload(int n, int excerptLen, boolean provenance) {
    Map<String, Object> p = new LinkedHashMap<>();
    List<Object> results = new ArrayList<>();
    for (int i = 1; i <= n; i++) {
      results.add(hitMap(i, excerptLen, provenance));
    }
    p.put("results", results);
    p.put("truncated", false);
    return p;
  }

  private static int bytes(Map<String, Object> p) {
    return MAPPER.writeValueAsString(p).getBytes(StandardCharsets.UTF_8).length;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> results(Map<String, Object> p) {
    return (List<Object>) p.get("results");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> governor(Map<String, Object> p) {
    return (Map<String, Object>) p.get("governor");
  }

  @SuppressWarnings("unchecked")
  private static String excerptText(Object hit) {
    Map<String, Object> h = (Map<String, Object>) hit;
    List<Map<String, Object>> excerpts = (List<Map<String, Object>>) h.get("excerpts");
    return (String) excerpts.get(0).get("text");
  }

  // ---- boundary: just-under is untouched ----

  @Test
  @DisplayName("just-under budget: payload untouched, no notice, provenance retained")
  void justUnderBudgetUntouched() {
    Map<String, Object> p = payload(3, 200, true);
    int budget = bytes(p) + 1_000; // comfortably above the serialized size
    Map<String, Object> out = McpDeliveryGovernor.govern(p, budget, MAPPER);
    assertEquals(3, results(out).size(), "no results dropped under budget");
    assertFalse(out.containsKey("governor"), "no notice when nothing was degraded");
    assertTrue(((Map<?, ?>) results(out).get(0)).containsKey("trace"), "provenance retained");
  }

  // ---- boundary: just-over → stripping provenance alone suffices ----

  @Test
  @DisplayName("just-over budget: stripping numeric provenance alone brings it under — no tail drop")
  void provenanceStripSuffices() {
    Map<String, Object> withProv = payload(4, 300, true);
    int sizeWith = bytes(withProv);
    int sizeWithout = bytes(payload(4, 300, false));
    // Budget strictly between the two so provenance-strip is exactly sufficient.
    int budget = sizeWithout + (sizeWith - sizeWithout) / 2;
    assertTrue(sizeWithout <= budget && budget < sizeWith, "test fixture must straddle the strip");

    Map<String, Object> out = McpDeliveryGovernor.govern(withProv, budget, MAPPER);

    assertEquals(4, results(out).size(), "no result dropped — provenance strip was enough");
    for (Object h : results(out)) {
      assertFalse(((Map<?, ?>) h).containsKey("trace"), "trace stripped");
      assertFalse(((Map<?, ?>) h).containsKey("legScores"), "legScores stripped");
    }
    Map<String, Object> g = governor(out);
    assertEquals(true, g.get("provenanceStripped"));
    assertEquals(0, g.get("resultsDropped"));
    assertEquals(4, g.get("originalResultCount"));
    assertTrue(bytes(out) <= budget, "governed payload fits the budget");
  }

  // ---- far-over → drop whole tail results, never mid-span ----

  @Test
  @DisplayName("far-over budget: whole tail results dropped lowest-ranked-first; surviving spans intact")
  void farOverDropsTailNeverMidSpan() {
    int excerptLen = 1_000;
    Map<String, Object> p = payload(20, excerptLen, true);
    int sizeWithout = bytes(payload(20, excerptLen, false));
    int budget = sizeWithout / 2; // even after stripping, ~half the results must go

    Map<String, Object> out = McpDeliveryGovernor.govern(p, budget, MAPPER);

    int delivered = results(out).size();
    assertTrue(delivered >= 1 && delivered < 20, "some but not all results survive: " + delivered);
    assertTrue(bytes(out) <= budget, "governed payload fits the budget");

    Map<String, Object> g = governor(out);
    assertEquals(true, g.get("provenanceStripped"));
    assertEquals(20 - delivered, g.get("resultsDropped"));
    assertEquals(20, g.get("originalResultCount"));
    assertEquals(delivered, g.get("deliveredResultCount"));

    // Never truncate mid-span: every surviving excerpt is delivered whole.
    for (Object h : results(out)) {
      assertEquals(excerptLen, excerptText(h).length(), "surviving excerpt must not be truncated");
    }
    // Tail dropped, not head: the survivors are the top ranks (doc-1..doc-N).
    assertEquals("docs/doc-1.md", ((Map<?, ?>) results(out).get(0)).get("path"));
    assertEquals(
        "docs/doc-" + delivered + ".md",
        ((Map<?, ?>) results(out).get(delivered - 1)).get("path"),
        "the dropped results are the lowest-ranked tail");
  }

  // ---- floor: never below one result, span never split ----

  @Test
  @DisplayName("single oversized result: delivered whole (never split), provenance stripped, notice fires")
  void singleOversizedResultDeliveredWhole() {
    int excerptLen = 60_000;
    Map<String, Object> p = payload(1, excerptLen, true);
    Map<String, Object> out = McpDeliveryGovernor.govern(p, 45_000, MAPPER);

    assertEquals(1, results(out).size(), "never drop below one result");
    assertEquals(excerptLen, excerptText(results(out).get(0)).length(), "span never split");
    Map<String, Object> g = governor(out);
    assertEquals(true, g.get("provenanceStripped"));
    assertEquals(0, g.get("resultsDropped"));
  }

  // ---- escape hatch: 0 disables ----

  @Test
  @DisplayName("budget 0 disables the governor entirely — huge payload delivered untouched")
  void zeroDisablesGovernor() {
    Map<String, Object> p = payload(30, 2_000, true);
    Map<String, Object> out = McpDeliveryGovernor.govern(p, 0, MAPPER);
    assertEquals(30, results(out).size(), "disabled: nothing dropped");
    assertFalse(out.containsKey("governor"), "disabled: no notice");
    assertTrue(((Map<?, ?>) results(out).get(0)).containsKey("trace"), "disabled: provenance retained");
  }

  // ---- determinism: same input → byte-stable governed output ----

  @Test
  @DisplayName("determinism: identical payloads governed to byte-identical serialized output")
  void deterministicByteStable() {
    int budget = bytes(payload(15, 800, false)) / 2;
    Map<String, Object> a = McpDeliveryGovernor.govern(payload(15, 800, true), budget, MAPPER);
    Map<String, Object> b = McpDeliveryGovernor.govern(payload(15, 800, true), budget, MAPPER);
    assertEquals(MAPPER.writeValueAsString(a), MAPPER.writeValueAsString(b));
  }

  // ---- notice shape: machine-readable + budget + counts ----

  @Test
  @DisplayName("notice names budget, pre-degradation count, resultsDropped, provenanceStripped")
  void noticeShapeIsMachineReadable() {
    int excerptLen = 1_000;
    int budget = bytes(payload(12, excerptLen, false)) / 2;
    Map<String, Object> out = McpDeliveryGovernor.govern(payload(12, excerptLen, true), budget, MAPPER);
    Map<String, Object> g = governor(out);
    assertEquals(budget, g.get("budgetBytes"));
    assertEquals(12, g.get("originalResultCount"));
    assertTrue(((Integer) g.get("resultsDropped")) > 0);
    assertEquals(true, g.get("provenanceStripped"));
    assertTrue(g.get("notice") instanceof String s && s.contains(Integer.toString(budget)));
  }

  // ---- integration through McpToolSurface#callSearch at limit 30, detail:true ----

  @Test
  @DisplayName(
      "integration (770 §C): detail:true limit 30 oversized results → result-count reduction + notice,"
          + " never mid-payload truncation")
  void integrationCallSearchDetailLimit30() {
    List<Hit> hits = new ArrayList<>();
    int excerptLen = 1_800; // 30 * ~1.8 KB excerpts >> the 45 KB default budget under detail
    String body = "y".repeat(excerptLen);
    for (int i = 1; i <= 30; i++) {
      MatchSpan span = new MatchSpan("content", 0, 9, "diagnostic");
      ExcerptRegion region = new ExcerptRegion(body, 0, excerptLen, 1, List.of(span));
      hits.add(
          new Hit(
              "docs/doc-" + i + ".md",
              1.0d - i * 0.001d,
              Map.of("title", "Doc " + i, "path", "docs/doc-" + i + ".md"),
              List.of("content"),
              List.of(span),
              List.of(region),
              List.of(new HitStage(StageId.FUSION, i, 0.9f, Map.of("cc", 0.9f)))));
    }
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            30L, 30L, 12L, hits, null, null, null, null, null, null, null, null);

    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenReturn(canned);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> ctrl,
            () -> null,
            Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneId.of("UTC")));

    Map<String, Object> result =
        surface.callTool(
            "justsearch_search", Map.of("query", "diagnostic", "limit", 30, "detail", true), "s1");

    @SuppressWarnings("unchecked")
    Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
    int delivered = results(structured).size();
    assertTrue(delivered < 30, "governor reduced the result count from 30 to " + delivered);
    Map<String, Object> g = governor(structured);
    assertTrue(g != null, "governor notice present");
    assertTrue(((Integer) g.get("resultsDropped")) > 0, "at least one tail result dropped");
    assertEquals(30, g.get("originalResultCount"));
    // Never mid-payload / mid-span: every delivered excerpt is intact.
    for (Object h : results(structured)) {
      assertEquals(excerptLen, excerptText(h).length(), "delivered excerpt must not be truncated");
    }
  }
}
