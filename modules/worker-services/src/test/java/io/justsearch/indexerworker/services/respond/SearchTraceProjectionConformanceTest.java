package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.services.SearchOutcome;
import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 553 Phase C — superset conformance for the worker slice → trace projection.
 *
 * <p>Under §10.0's distributed model the canonical record is the per-process slice; for the worker
 * that is {@code (SearchDecision, SearchOutcome)}, and {@code SearchOutcome} is the richest
 * representation (it carries fine-grained timings the trace drops). The query-level {@code SearchTrace}
 * the worker projects must therefore be <b>lossy-downward</b> from that slice — it may drop facts, but
 * it must never carry a fact the slice doesn't have, and every dropped field must be a <i>deliberate</i>
 * choice, not silent divergence.
 *
 * <p>This test mechanizes the completeness of that relationship: every {@code SearchOutcome} record
 * component is classified as either REPRESENTED (reflected in the projected trace — the per-field
 * mappings themselves are asserted in {@link SearchTraceProjectorTest}) or DELIBERATELY_DROPPED (in the
 * slice, deliberately absent from the query-level trace). A newly-added {@code SearchOutcome} field that
 * is in neither set fails the test, forcing the author to decide project-vs-drop rather than silently
 * widening the slice↔trace gap (the G1 "superset is enforced, not asserted" closure). This is the
 * conformance guard the execution-surface register names for the worker-projector surface.
 */
@DisplayName("SearchTraceProjection conformance: the trace is lossy-downward from the SearchOutcome slice")
final class SearchTraceProjectionConformanceTest {

  /** Slice fields reflected in the projected worker trace (mappings pinned in SearchTraceProjectorTest). */
  private static final Set<String> REPRESENTED =
      Set.of(
          "retrievalMs", // → FUSION.ms
          "correctionApplied", // → CORRECTION.status
          "correctedQuery", // → CORRECTION.detail
          "chunkMergeApplied", // → CHUNK_MERGE.status
          "chunkMergeReason", // → CHUNK_MERGE.reason
          "chunkMergeMs", // → CHUNK_MERGE.ms
          "branchFusionStrategy", // → BRANCH_FUSION.detail
          "branchFusionContributed", // → BRANCH_FUSION.status
          "branchFusionNs", // → BRANCH_FUSION.ms (÷1e6)
          "spladeExecuted"); // → degradation.spladeExecuted

  /** Slice fields deliberately absent from the query-level trace (documented downward loss). */
  private static final Set<String> DELIBERATELY_DROPPED =
      Set.of(
          "result", // hits → per-hit HitStage, not the query-level trace
          "facets", // facet counts are not execution-trace facts
          "queryForSpans", // process-local Lucene Query (match-span computation); cannot cross the wire
          "chunkQueryText", // the chunk/corrected query text (correctedQuery is the surfaced one)
          "chunkBm25Ns", // fine-grained chunk-leg timings: in the slice, not summarized into the trace
          "chunkKnnNs",
          "chunkSpladeNs",
          "chunkRetry"); // chunk retry flag, not surfaced as a trace stage

  @Test
  @DisplayName("every SearchOutcome field is classified represented-or-dropped (no silent gap)")
  void everyOutcomeFieldIsClassified() {
    Set<String> unclassified = new TreeSet<>();
    Set<String> componentNames = new TreeSet<>();
    for (RecordComponent rc : SearchOutcome.class.getRecordComponents()) {
      String name = rc.getName();
      componentNames.add(name);
      boolean represented = REPRESENTED.contains(name);
      boolean dropped = DELIBERATELY_DROPPED.contains(name);
      if (represented == dropped) {
        // Either in neither set (unclassified) or in both (contradiction) — both are defects.
        unclassified.add(name);
      }
    }
    assertTrue(
        unclassified.isEmpty(),
        () ->
            "SearchOutcome field(s) "
                + unclassified
                + " are not classified in SearchTraceProjectionConformanceTest. SearchOutcome is the"
                + " worker superset slice; the projected SearchTrace must be lossy-DOWNWARD. Decide for"
                + " each: project it into the trace (add to REPRESENTED + assert the mapping in"
                + " SearchTraceProjectorTest) or document it as a deliberate downward loss (add to"
                + " DELIBERATELY_DROPPED).");

    // The classification sets must not name stale (removed) fields either.
    Set<String> staleClassified = new TreeSet<>();
    staleClassified.addAll(REPRESENTED);
    staleClassified.addAll(DELIBERATELY_DROPPED);
    staleClassified.removeAll(componentNames);
    assertTrue(
        staleClassified.isEmpty(),
        () -> "Classification names fields no longer on SearchOutcome: " + staleClassified);
  }
}
