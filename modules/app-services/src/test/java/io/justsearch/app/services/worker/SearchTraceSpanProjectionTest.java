package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.api.knowledge.SearchTrace.StageStatus;
import io.justsearch.app.api.knowledge.SearchTrace.TraceStage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 553 Phase 4a — verifies the OTel span projection of the canonical {@link SearchTrace}.
 * This is the test that dissolves the "span output is unverifiable" blocker: the projection is a
 * pure function, so its record-derived attributes are asserted here without a live backend (the
 * {@code search()} call site merely applies the result to the root span).
 */
@DisplayName("SearchTraceSpanProjection: the OTel span carries data derived from the canonical trace")
final class SearchTraceSpanProjectionTest {

  @Test
  @DisplayName("projects effectiveMode / decisionKind / qpp / per-stage status+ms onto attributes")
  void projectsTraceOntoAttributes() {
    SearchTrace trace =
        new SearchTrace(
            1,
            "HYBRID",
            "multi_leg",
            new SearchTrace.Qpp(1.5f, 2.0f, 3.0f),
            null,
            List.of(
                new TraceStage(StageId.SPARSE_RETRIEVAL, StageStatus.EXECUTED, null, 5L, null, null),
                new TraceStage(StageId.CROSS_ENCODER, StageStatus.SKIPPED, "disabled", null, null, null)));

    Attributes a = SearchTraceSpanProjection.attributesOf(trace);

    assertEquals("HYBRID", a.get(AttributeKey.stringKey("justsearch.search.effective_mode")));
    assertEquals("multi_leg", a.get(AttributeKey.stringKey("justsearch.search.decision_kind")));
    assertEquals(1.5, a.get(AttributeKey.doubleKey("justsearch.search.qpp.max_idf")), 0.0001);
    // Per-stage projection: status always present; ms only when the stage carried it.
    assertEquals(
        "executed",
        a.get(AttributeKey.stringKey("justsearch.search.stage.sparse-retrieval.status")));
    assertEquals(
        5L, a.get(AttributeKey.longKey("justsearch.search.stage.sparse-retrieval.ms")));
    assertEquals(
        "skipped",
        a.get(AttributeKey.stringKey("justsearch.search.stage.cross-encoder.status")));
    assertNull(
        a.get(AttributeKey.longKey("justsearch.search.stage.cross-encoder.ms")),
        "a stage with no ms must not emit an ms attribute");
  }

  @Test
  @DisplayName("null trace → empty attributes (no NPE at the call site)")
  void nullTraceIsEmpty() {
    assertTrue(SearchTraceSpanProjection.attributesOf(null).isEmpty());
  }
}
