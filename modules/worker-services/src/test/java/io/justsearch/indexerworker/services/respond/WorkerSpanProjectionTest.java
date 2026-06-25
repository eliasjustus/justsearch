package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceQpp;
import io.justsearch.ipc.TraceStage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 553 Phase 4a (worker) — verifies the worker span projects the canonical record slice.
 * The projection is a pure function of the worker {@code ipc SearchTrace} (itself projected from
 * {@code (decision, outcome)}), so its record-derived attributes are asserted here without a live
 * backend; {@code SearchResponseBuilder} applies the result to {@code Span.current()}.
 */
@DisplayName("WorkerSpanProjection: the worker span carries data derived from the worker trace slice")
final class WorkerSpanProjectionTest {

  @Test
  @DisplayName("projects effectiveMode / decisionKind / qpp / per-stage status+ms")
  void projectsWorkerTraceOntoAttributes() {
    SearchTrace trace =
        SearchTrace.newBuilder()
            .setVersion(1)
            .setEffectiveMode("HYBRID")
            .setDecisionKind("multi_leg")
            .setQpp(TraceQpp.newBuilder().setMaxIdf(1.5f).setAvgIctf(2.0f).setQueryScope(3.0f))
            .addStages(
                TraceStage.newBuilder().setId("sparse-retrieval").setStatus("executed").setMs(5L))
            .addStages(
                TraceStage.newBuilder().setId("dense-retrieval").setStatus("skipped").setReason("dense-only"))
            .build();

    Attributes a = WorkerSpanProjection.attributesOf(trace);

    assertEquals("HYBRID", a.get(AttributeKey.stringKey("justsearch.search.worker.effective_mode")));
    assertEquals("multi_leg", a.get(AttributeKey.stringKey("justsearch.search.worker.decision_kind")));
    assertEquals(
        1.5, a.get(AttributeKey.doubleKey("justsearch.search.worker.qpp.max_idf")), 0.0001);
    assertEquals(
        "executed",
        a.get(AttributeKey.stringKey("justsearch.search.worker.stage.sparse-retrieval.status")));
    assertEquals(
        5L, a.get(AttributeKey.longKey("justsearch.search.worker.stage.sparse-retrieval.ms")));
    assertEquals(
        "skipped",
        a.get(AttributeKey.stringKey("justsearch.search.worker.stage.dense-retrieval.status")));
    // dense-retrieval carried no ms → no ms attribute.
    assertNull(a.get(AttributeKey.longKey("justsearch.search.worker.stage.dense-retrieval.ms")));
  }

  @Test
  @DisplayName("null/default trace → empty attributes (no NPE at the call site)")
  void emptyTraceIsEmpty() {
    assertTrue(WorkerSpanProjection.attributesOf(null).isEmpty());
    assertTrue(WorkerSpanProjection.attributesOf(SearchTrace.getDefaultInstance()).isEmpty());
  }
}
