/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceStage;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

/**
 * Tempdoc 553 Phase 4a (worker) — project the worker's slice of the canonical record onto the
 * active worker OTel span.
 *
 * <p>The worker's slice is the {@code ipc SearchTrace} that {@link SearchTraceProjector} projects
 * from the typed {@code (SearchDecision, SearchOutcome, SearchInputs)} value model. This helper
 * turns that projected slice into record-derived span attributes, so the worker's request span
 * carries the same execution facts the trace does — i.e. the worker telemetry PROJECTS the record
 * rather than authoring facts independently (553 pillar b). {@code SearchResponseBuilder} applies
 * the result to {@code Span.current()} right after projecting the trace.
 *
 * <p>Pure function (trace slice → attributes), so the projection is unit-tested without a live
 * backend — the call site merely applies it. Mirrors the head-side {@code SearchTraceSpanProjection}
 * (which projects the COMPOSED app-api trace onto the root span); this projects the WORKER ipc slice
 * onto the worker span. The fuller convergence — splitting the worker {@code search/*} tree into
 * per-leg OpenInference retriever spans carrying documents — is a tracked refinement
 * ({@code otel-spans-worker} in the execution-surface register); this attribute-level projection is
 * the safe, verifiable first step.
 */
final class WorkerSpanProjection {

  /** Attribute namespace for the worker slice (distinct from the head root span's namespace). */
  static final String NS = "justsearch.search.worker.";

  private WorkerSpanProjection() {}

  /** Project the worker trace slice onto a flat attribute set. Absent fields are omitted. */
  static Attributes attributesOf(SearchTrace trace) {
    if (trace == null) {
      return Attributes.empty();
    }
    AttributesBuilder b = Attributes.builder();
    if (!trace.getEffectiveMode().isEmpty()) {
      b.put(NS + "effective_mode", trace.getEffectiveMode());
    }
    if (!trace.getDecisionKind().isEmpty()) {
      b.put(NS + "decision_kind", trace.getDecisionKind());
    }
    if (trace.hasQpp()) {
      b.put(NS + "qpp.max_idf", trace.getQpp().getMaxIdf());
      b.put(NS + "qpp.avg_ictf", trace.getQpp().getAvgIctf());
      b.put(NS + "qpp.query_scope", trace.getQpp().getQueryScope());
    }
    for (TraceStage s : trace.getStagesList()) {
      if (s.getId().isEmpty()) {
        continue;
      }
      String key = NS + "stage." + s.getId();
      if (!s.getStatus().isEmpty()) {
        b.put(key + ".status", s.getStatus());
      }
      if (s.hasMs()) {
        b.put(key + ".ms", s.getMs());
      }
    }
    return b.build();
  }
}
