/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 658 — projects the canonical retrieval-evidence records onto the agent-facing MCP
 * {@code structuredContent} channel.
 *
 * <p>The evidence records — {@link SearchTrace} (ranking "why") and {@link ContextCitation}
 * (RAG-answer citations) — are already produced and projected to REST, the FE explain panel, OTel
 * spans, and eval, and are governed by the execution-surface register (tempdoc 553). The MCP tool
 * surface holds the fully-populated records but historically dropped them, formatting text only. This
 * is the missing projection: it re-authors nothing, it reads the canonical records and shapes the
 * agent-facing view.
 *
 * <p>Deliberately builds explicit {@code Map}/{@code List} structures via record accessors
 * ({@code StageId.wireId()}, {@code StageStatus.wireValue()}) rather than emitting the record objects
 * directly. This makes the wire shape independent of the MCP response serializer's Jackson-annotation
 * processing (the handler uses a different Jackson generation than the {@code com.fasterxml}
 * annotations on the records), and it is what gives the projection full control of the agent altitude.
 *
 * <p>Altitude: the structural trace (stage ids/status/reason/timing, per-hit rank/score/leg
 * participation, degradation) is always present; the numeric per-hit {@code detail} tier
 * ({@link SearchTrace.HitStage#detail()}) is populated upstream only when the request set
 * {@code debug=true} (the MCP {@code detail} tool argument), so it appears here only on request.
 *
 * <p>Registered as a {@code projection} surface in {@code governance/execution-surfaces.v1.json};
 * guarded by {@code McpEvidenceProjectionTest}.
 */
public final class McpEvidenceProjection {

  private McpEvidenceProjection() {}

  /**
   * Structured evidence for {@code justsearch_search}: the query-level {@link SearchTrace} plus per-hit
   * ranking provenance (stage participation + fusion-leg scores).
   */
  public static Map<String, Object> searchEvidence(KnowledgeSearchResponse resp) {
    Map<String, Object> out = new LinkedHashMap<>();
    SearchTrace trace = resp.searchTrace();
    if (trace != null) {
      out.put("searchTrace", projectTrace(trace));
      // Tempdoc 725 W1: a response-level summary of SearchTrace.Degradation, alongside the
      // structural detail already nested under searchTrace.degradation — the agent-legible
      // "was this degraded" answer without navigating the full trace.
      SearchTrace.Degradation degradation = trace.degradation();
      if (degradation != null) {
        out.put("degradation", projectDegradationSummary(degradation));
      }
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (KnowledgeSearchResponse.Hit hit : resp.results()) {
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("id", hit.id());
      String title = hit.fields().getOrDefault("title", "");
      if (!title.isBlank()) {
        h.put("title", title);
      }
      String path = hit.fields().getOrDefault("path", "");
      if (!path.isBlank()) {
        h.put("path", path);
      }
      h.put("score", hit.score());

      // Tempdoc 725 W1: the same informative-term filter that drives the text-block "Matched:"
      // line, projected onto structuredContent — matchedTerms/matchedFields/excerpts.
      List<KnowledgeSearchResponse.MatchSpan> informative =
          McpSearchResultFormatter.filterInformative(hit.matchSpans());
      if (!informative.isEmpty()) {
        h.put("matchedTerms", McpSearchResultFormatter.informativeTerms(informative));
      }
      if (hit.matchedFields() != null && !hit.matchedFields().isEmpty()) {
        h.put("matchedFields", hit.matchedFields());
      }
      if (hit.excerptRegions() != null && !hit.excerptRegions().isEmpty()) {
        List<Map<String, Object>> excerpts = new ArrayList<>();
        for (KnowledgeSearchResponse.ExcerptRegion region : hit.excerptRegions()) {
          Map<String, Object> e = new LinkedHashMap<>();
          e.put("text", region.text());
          e.put("startChar", region.startChar());
          e.put("endChar", region.endChar());
          excerpts.add(e);
        }
        h.put("excerpts", excerpts);
      }

      List<SearchTrace.HitStage> hitTrace = hit.trace();
      if (hitTrace != null && !hitTrace.isEmpty()) {
        h.put("trace", projectHitStages(hitTrace));
        SearchTrace.LegScores legs = SearchTrace.legScores(hitTrace, (float) hit.score());
        Map<String, Object> ls = new LinkedHashMap<>();
        ls.put("sparse", legs.sparse());
        ls.put("dense", legs.dense());
        ls.put("splade", legs.splade());
        ls.put("fused", legs.fused());
        h.put("legScores", ls);
      }
      results.add(h);
    }
    out.put("results", results);
    return out;
  }

  /**
   * Structured evidence for {@code justsearch_answer}: the {@link ContextCitation} provenance list plus
   * the retrieval-quality/degradation signals from the {@link ContextResult}.
   */
  public static Map<String, Object> answerEvidence(ContextResult r) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> citations = new ArrayList<>();
    for (ContextCitation c : r.citations()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("parentDocId", c.parentDocId());
      m.put("chunkIndex", c.chunkIndex());
      m.put("chunkTotal", c.chunkTotal());
      m.put("startChar", c.startChar());
      m.put("endChar", c.endChar());
      m.put("score", c.score());
      m.put("excerpt", c.excerpt());
      m.put("startLine", c.startLine());
      m.put("endLine", c.endLine());
      if (!c.headingText().isBlank()) {
        m.put("headingText", c.headingText());
      }
      m.put("headingLevel", c.headingLevel());
      citations.add(m);
    }
    out.put("citations", citations);

    Map<String, Object> quality = new LinkedHashMap<>();
    quality.put("chunksFound", r.chunksFound());
    quality.put("chunksUsed", r.chunksUsed());
    quality.put("retrievalMode", r.retrievalMode());
    quality.put("retrievalModeReason", r.retrievalModeReason());
    quality.put("contextTruncated", r.contextTruncated());
    // The full CRAG-style confidence signal set (tempdoc 658): all five QualitySignals fields, so an
    // agent can judge retrieval confidence, not just coverage.
    quality.put("retrievalCoverage", r.quality().retrievalCoverage());
    quality.put("bestChunkScore", r.quality().bestChunkScore());
    quality.put("scoreGap", r.quality().scoreGap());
    quality.put("chunksConsidered", r.quality().chunksConsidered());
    quality.put("chunksIncluded", r.quality().chunksIncluded());
    out.put("quality", quality);
    return out;
  }

  /**
   * Tempdoc 725 W1: response-level {vectorBlocked, hybridFallback, reasons} summary of {@link
   * SearchTrace.Degradation}. {@code reasons} collects vectorBlockedReason/hybridFallbackReason
   * only for the flags that are actually {@code true} (gated the same way as the text-block
   * degradation note in {@code McpToolSurface#appendDegradationNote}), so this summary never lists
   * a reason for a degradation mode that did not occur.
   */
  private static Map<String, Object> projectDegradationSummary(SearchTrace.Degradation d) {
    Map<String, Object> deg = new LinkedHashMap<>();
    deg.put("vectorBlocked", d.vectorBlocked());
    deg.put("hybridFallback", d.hybridFallback());
    List<String> reasons = new ArrayList<>();
    if (d.vectorBlocked() && d.vectorBlockedReason() != null && !d.vectorBlockedReason().isBlank()) {
      reasons.add(d.vectorBlockedReason());
    }
    if (d.hybridFallback()
        && d.hybridFallbackReason() != null
        && !d.hybridFallbackReason().isBlank()) {
      reasons.add(d.hybridFallbackReason());
    }
    deg.put("reasons", reasons);
    return deg;
  }

  private static Map<String, Object> projectTrace(SearchTrace t) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (t.effectiveMode() != null) {
      m.put("effectiveMode", t.effectiveMode());
    }
    if (t.decisionKind() != null) {
      m.put("decisionKind", t.decisionKind());
    }
    SearchTrace.Qpp qpp = t.qpp();
    if (qpp != null) {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("maxIdf", qpp.maxIdf());
      q.put("avgIctf", qpp.avgIctf());
      q.put("queryScope", qpp.queryScope());
      m.put("qpp", q);
    }
    SearchTrace.Degradation d = t.degradation();
    if (d != null) {
      Map<String, Object> deg = new LinkedHashMap<>();
      deg.put("vectorBlocked", d.vectorBlocked());
      if (d.vectorBlockedReason() != null) {
        deg.put("vectorBlockedReason", d.vectorBlockedReason());
      }
      deg.put("hybridFallback", d.hybridFallback());
      if (d.hybridFallbackReason() != null) {
        deg.put("hybridFallbackReason", d.hybridFallbackReason());
      }
      deg.put("spladeExecuted", d.spladeExecuted());
      if (d.spladeSkipReason() != null) {
        deg.put("spladeSkipReason", d.spladeSkipReason());
      }
      m.put("degradation", deg);
    }
    List<Map<String, Object>> stages = new ArrayList<>();
    for (SearchTrace.TraceStage s : t.stages()) {
      Map<String, Object> sm = new LinkedHashMap<>();
      sm.put("id", s.id().wireId());
      sm.put("status", s.status().wireValue());
      if (s.reason() != null) {
        sm.put("reason", s.reason());
      }
      if (s.ms() != null) {
        sm.put("ms", s.ms());
      }
      if (s.detail() != null) {
        sm.put("detail", s.detail());
      }
      if (s.cardinality() != null) {
        sm.put("cardinality", s.cardinality());
      }
      stages.add(sm);
    }
    m.put("stages", stages);
    return m;
  }

  private static List<Map<String, Object>> projectHitStages(List<SearchTrace.HitStage> trace) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (SearchTrace.HitStage hs : trace) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", hs.id().wireId());
      if (hs.rank() != null) {
        m.put("rank", hs.rank());
      }
      if (hs.score() != null) {
        m.put("score", hs.score());
      }
      // The numeric detail tier — present only when the request set debug=true (the MCP `detail` arg).
      if (hs.detail() != null) {
        m.put("detail", hs.detail());
      }
      out.add(m);
    }
    return out;
  }
}
