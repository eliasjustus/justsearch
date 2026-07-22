/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.services.HighlightingOps;
import io.justsearch.ipc.ExcerptRegion;
import io.justsearch.ipc.MatchSpan;
import java.util.List;
import java.util.Map;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Test;

/**
 * Flag-off byte-equivalence golden (tempdoc 775 step 1; the 774 Stage-1 "defaults byte-equivalent"
 * pattern). The delivery excerpt with the EvidenceSpan flag OFF is the unchanged {@code
 * HighlightingOps.computeExcerptRegions} — this test pins its exact output (text, char offsets,
 * approx line, window-relative match spans) so the behavior-preserving extraction (buildClusters /
 * projectWindow / windowMatchSpans) cannot silently drift the default delivery.
 */
class ExcerptRegionDefaultsByteEquivalenceTest {

  private static final Analyzer ANALYZER = new StandardAnalyzer();

  // Deterministic two-cluster document. "needle" appears densely early and once late (>400 apart).
  private static final String CONTENT =
      "Intro sentence one. The needle needle needle appears here densely. Middle sentence."
          + " lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor.".repeat(8)
          + " Finally a lone needle sits at the tail of the document body.";

  /** Serializes offsets + window-relative match spans (byte-exact, filler-independent). */
  private static String offsetGolden(List<ExcerptRegion> regions) {
    StringBuilder sb = new StringBuilder();
    for (ExcerptRegion r : regions) {
      sb.append("[")
          .append(r.getStartChar())
          .append(",")
          .append(r.getEndChar())
          .append(",L")
          .append(r.getApproxLine())
          .append("]");
      for (MatchSpan m : r.getMatchSpansList()) {
        sb.append("<").append(m.getStartChar()).append(":").append(m.getEndChar()).append(">");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  @Test
  void defaultDeliveryExcerptIsByteIdentical() {
    org.apache.lucene.search.Query query = HighlightingOps.buildTermQuery(ANALYZER, "needle");
    List<ExcerptRegion> regions =
        HighlightingOps.computeExcerptRegions(ANALYZER, query, CONTENT, 3, Map.of("needle", 4.0));

    // Pinned offsets + match spans (captured from the behavior-preserving extraction).
    String expected = "[0,239,L1]<24:30><31:37><38:44>\n[524,768,L1]<199:205>\n";
    assertEquals(expected, offsetGolden(regions), "flag-off delivery excerpt drifted from the byte-golden");

    // Byte-exact text: every region's text is precisely the content window at its offsets — so the
    // pinned offsets above fully determine the delivered bytes without copying the filler.
    for (ExcerptRegion r : regions) {
      assertEquals(
          CONTENT.substring(r.getStartChar(), r.getEndChar()),
          r.getText(),
          "region text must equal the content window at its char offsets");
    }
  }
}
