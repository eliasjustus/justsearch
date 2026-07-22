/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.services.HighlightingOps;
import io.justsearch.ipc.ExcerptRegion;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Test;

/**
 * The tempdoc 775 §E step-1 selection law: candidate windows are ranked by distinguishing-entity
 * coverage FIRST, then query-term IDF density — so the window carrying the query's rare/entity token
 * beats the densest query-term cluster (the 45%-legal miss is exactly that divergence). This is a
 * pure unit test (no index): a StandardAnalyzer + an OR term-query drive the same {@code
 * collectMatchOffsets} the production locus uses.
 */
class EvidenceSpanSelectorTest {

  private static final Analyzer ANALYZER = new StandardAnalyzer();
  private static final long NUM_DOCS = 1000L;

  // Two query-match clusters >400 chars apart. Cluster A (early): dense "report" only. Cluster B
  // (late): one "report" next to a globally-unique name — the analogue of the buried bridge entity.
  private static final String DENSE_A =
      "report report report findings report report report data report analysis report.";
  private static final String FILLER = " padding padding padding padding padding padding ".repeat(12);
  private static final String ENTITY_B = "the report was authored by Zorptannicus in chambers.";
  private static final String CONTENT = DENSE_A + FILLER + ENTITY_B;

  private static org.apache.lucene.search.Query reportQuery() {
    return HighlightingOps.buildTermQuery(ANALYZER, "report");
  }

  private static boolean containsCi(String haystack, String needle) {
    return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
  }

  @Test
  void flagOffIdfPathPrefersDensestCluster_missesBuriedEntity() {
    // The unchanged IDF-only delivery path ranks the densest query-term cluster first.
    List<ExcerptRegion> regions =
        HighlightingOps.computeExcerptRegions(ANALYZER, reportQuery(), CONTENT, 1, Map.of("report", 1.0));
    assertFalse(regions.isEmpty(), "expected an excerpt region");
    assertFalse(
        containsCi(regions.get(0).getText(), "Zorptannicus"),
        "IDF-only path should NOT surface the buried entity as the top region");
  }

  @Test
  void dfRaritySignalPrefersEntityBearingWindow() {
    // "report" common (df=800); "zorptannicus" globally unique (df=1 <= 2% threshold).
    EvidenceSpanSelector.DfProvider df =
        terms -> {
          java.util.HashMap<String, Integer> m = new java.util.HashMap<>();
          for (String t : terms) m.put(t, "zorptannicus".equals(t) ? 1 : 800);
          return m;
        };
    EvidenceSpanSelector selector =
        new EvidenceSpanSelector(EvidenceSpanSelector.EntitySignal.DF_RARITY, df, NUM_DOCS);
    List<EvidenceSpanSelector.SelectedSpan> spans =
        selector.select(ANALYZER, reportQuery(), CONTENT, 1, Map.of("report", 1.0), "", "doc-1");
    assertFalse(spans.isEmpty(), "expected a selected span");
    EvidenceSpan top = spans.get(0).span();
    assertTrue(
        containsCi(top.text(), "Zorptannicus"),
        "df-rarity selector should prefer the window carrying the df=1 entity: " + top.text());
    assertTrue(top.isAnswerBearing(), "the selected span must be marked answer-bearing");
    assertTrue(
        top.entityCoverage().contains("zorptannicus"),
        "entityCoverage should record the distinguishing token: " + top.entityCoverage());
  }

  @Test
  void nerMembershipSignalPrefersEntityBearingWindow() {
    // No df signal used; the doc's NER entity text names the bridge person.
    EvidenceSpanSelector selector =
        new EvidenceSpanSelector(EvidenceSpanSelector.EntitySignal.NER_MEMBERSHIP, null, NUM_DOCS);
    List<EvidenceSpanSelector.SelectedSpan> spans =
        selector.select(
            ANALYZER, reportQuery(), CONTENT, 1, Map.of("report", 1.0), "Zorptannicus", "doc-1");
    assertFalse(spans.isEmpty(), "expected a selected span");
    EvidenceSpan top = spans.get(0).span();
    assertTrue(
        containsCi(top.text(), "Zorptannicus"),
        "ner-membership selector should prefer the window carrying the doc NER entity: " + top.text());
    assertTrue(top.entityCoverage().contains("zorptannicus"));
  }

  @Test
  void matchSpansAreWindowRelativeAndProjectionRoundTrips() {
    EvidenceSpanSelector.DfProvider df =
        terms -> {
          java.util.HashMap<String, Integer> m = new java.util.HashMap<>();
          for (String t : terms) m.put(t, "zorptannicus".equals(t) ? 1 : 800);
          return m;
        };
    EvidenceSpanSelector selector =
        new EvidenceSpanSelector(EvidenceSpanSelector.EntitySignal.DF_RARITY, df, NUM_DOCS);
    List<EvidenceSpanSelector.SelectedSpan> spans =
        selector.select(ANALYZER, reportQuery(), CONTENT, 3, Map.of("report", 1.0), "", "doc-1");
    assertFalse(spans.isEmpty());
    for (EvidenceSpanSelector.SelectedSpan sel : spans) {
      ExcerptRegion region = EvidenceSpanSelector.toExcerptRegion(sel, 0);
      assertEquals(sel.span().text(), region.getText());
      assertEquals(sel.span().charStart(), region.getStartChar());
      assertEquals(sel.span().charEnd(), region.getEndChar());
      assertEquals(sel.span().lineStart(), region.getApproxLine());
      for (var span : region.getMatchSpansList()) {
        assertTrue(span.getStartChar() >= 0);
        assertTrue(span.getEndChar() <= region.getText().length(), "match span must be window-relative");
      }
    }
  }
}
