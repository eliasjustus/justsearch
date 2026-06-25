/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.SearchTrace;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;

/**
 * Tempdoc 553 Phase 4a — project the canonical {@link SearchTrace} onto the root {@code search}
 * OTel span as attributes.
 *
 * <p>The search path emits a {@code search/*} OTel span tree (telemetry) in parallel with the
 * unified {@link SearchTrace} (explainability) — two records of the same execution (the
 * fragmentation 553 names). This is the first, safe convergence step: the root span now carries
 * data <em>derived from the canonical record</em> (effective mode, decision kind, QPP, per-stage
 * status + timing), so an observability consumer sees the same decision the explain panel does.
 * Full OpenInference structure (per-stage retriever/reranker spans with documents) is the larger
 * follow-up tracked in {@code governance/execution-surfaces.v1.json} ({@code otel-spans}).
 *
 * <p>Pure function (trace → attributes) so it is unit-testable without a live backend — which is
 * what dissolves the "span output is unverifiable" blocker: the test asserts the projection, the
 * call site merely applies it.
 */
final class SearchTraceSpanProjection {

  /** Attribute namespace for record-derived span attributes. */
  static final String NS = "justsearch.search.";

  private SearchTraceSpanProjection() {}

  /** Project the query-level trace onto a flat attribute set. Null/absent fields are omitted. */
  static Attributes attributesOf(SearchTrace trace) {
    if (trace == null) {
      return Attributes.empty();
    }
    AttributesBuilder b = Attributes.builder();
    if (trace.effectiveMode() != null) {
      b.put(NS + "effective_mode", trace.effectiveMode());
    }
    if (trace.decisionKind() != null) {
      b.put(NS + "decision_kind", trace.decisionKind());
    }
    SearchTrace.Qpp qpp = trace.qpp();
    if (qpp != null) {
      b.put(NS + "qpp.max_idf", qpp.maxIdf());
      b.put(NS + "qpp.avg_ictf", qpp.avgIctf());
      b.put(NS + "qpp.query_scope", qpp.queryScope());
    }
    for (SearchTrace.TraceStage s : trace.stages()) {
      if (s.id() == null) {
        continue;
      }
      String key = NS + "stage." + s.id().wireId();
      if (s.status() != null) {
        b.put(key + ".status", s.status().wireValue());
      }
      if (s.ms() != null) {
        b.put(key + ".ms", (long) s.ms());
      }
    }
    return b.build();
  }
}
