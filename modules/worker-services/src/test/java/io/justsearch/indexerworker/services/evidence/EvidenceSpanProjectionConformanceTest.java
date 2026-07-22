/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.ExcerptRegion;
import io.justsearch.ipc.MatchSpan;
import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Reflective totality guard for the {@link EvidenceSpan} record (execution-surfaces register,
 * guardKind: reflective). Asserts every record component is either PROJECTED onto the delivery
 * {@code ExcerptRegion} envelope or DELIBERATELY DROPPED — so a field can never be added to (or
 * removed from) EvidenceSpan without this test forcing a conscious projection decision (tempdoc 775,
 * mirrors the SearchTrace/ContextCitation reflective conformance guards).
 */
class EvidenceSpanProjectionConformanceTest {

  /** Components whose value flows into the interactive delivery excerpt. */
  private static final Set<String> PROJECTED = Set.of("text", "charStart", "charEnd", "lineStart");

  /**
   * Components deliberately NOT carried by the interactive {@code ExcerptRegion} envelope. They
   * serve the RAG/citation + CE envelopes (steps 2/3) and selection provenance — see the register's
   * evidence-span-selector note.
   */
  private static final Set<String> DROPPED =
      Set.of("parentDocId", "lineEnd", "headingText", "selectingLegs", "entityCoverage");

  @Test
  void everyEvidenceSpanComponentIsProjectedOrDeliberatelyDropped() {
    Set<String> classified = new HashSet<>();
    classified.addAll(PROJECTED);
    classified.addAll(DROPPED);

    Set<String> actual = new TreeSet<>();
    for (RecordComponent rc : EvidenceSpan.class.getRecordComponents()) {
      actual.add(rc.getName());
    }

    // Totality: the union of {projected, dropped} exactly equals the record's components — no field
    // silently unclassified, and no stale classification for a removed field.
    assertEquals(
        new TreeSet<>(classified),
        actual,
        "EvidenceSpan gained/lost a field without updating its ExcerptRegion projection classification");
    assertTrue(
        java.util.Collections.disjoint(PROJECTED, DROPPED),
        "a component cannot be both projected and dropped");
  }

  @Test
  void projectedComponentsCarryTheirValuesOntoTheExcerptRegion() {
    EvidenceSpan span =
        new EvidenceSpan(
            "parent-42",
            100,
            340,
            7,
            9,
            "Heading",
            "the evidence text",
            List.of("report"),
            List.of("zorptannicus"));
    MatchSpan ms =
        MatchSpan.newBuilder().setField("content").setStartChar(4).setEndChar(12).setTerm("evidence").build();
    EvidenceSpanSelector.SelectedSpan sel =
        new EvidenceSpanSelector.SelectedSpan(span, List.of(ms));

    ExcerptRegion region = EvidenceSpanSelector.toExcerptRegion(sel, 0);
    assertEquals(span.text(), region.getText());
    assertEquals(span.charStart(), region.getStartChar());
    assertEquals(span.charEnd(), region.getEndChar());
    assertEquals(span.lineStart(), region.getApproxLine());
    // matchSpans are the delivery-envelope addition, carried verbatim.
    assertEquals(1, region.getMatchSpansCount());
    assertEquals(ms, region.getMatchSpans(0));

    // lineOffset (chunk hits) is applied at projection time.
    ExcerptRegion offset = EvidenceSpanSelector.toExcerptRegion(sel, 1000);
    assertEquals(span.lineStart() + 1000, offset.getApproxLine());
  }
}
