/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 775 §E / §C — the delivery governor: deterministic degradation of the assembled
 * {@code justsearch_search} MCP payload at the client truncation cap.
 *
 * <p>Sits <b>after</b> 770's gated {@link McpEvidenceProjection} delivery, on the already-assembled
 * {@code structuredContent} map. Tempdoc 770 §E.3 characterized the client truncation cliff: between
 * 46,617 and 52,825 chars of serialized {@code structuredContent} the whole payload is lost and
 * replaced by a fixed 2,322-char notice that delivers <b>neither</b> content tier
 * ({@code delivered_fields: null} — evidence loss, not degradation). At {@code detail:true limit:30}
 * on legal the payload (p50 56.9 KB, orchestrator live measurement 2026-07-22) sails past that band
 * with no notice.
 *
 * <p>This governor replaces that client-side neither-tier loss with a deterministic server-side
 * degradation, in the order tempdoc 775 §E fixes:
 *
 * <ol>
 *   <li><b>Numeric provenance first.</b> Strip the per-hit {@code trace} + {@code legScores} block —
 *       the {@code detail}-gated ranking-provenance tier (770: 19.9% of the delivered search payload,
 *       carrying no document content). This is exactly the block {@link
 *       McpEvidenceProjection#searchEvidence} adds only under {@code detail:true}, so stripping it
 *       degrades toward the default tier without touching any document text.
 *   <li><b>Then drop whole tail results.</b> Lowest-ranked first (the tail of the rank-ordered
 *       {@code results} list), one at a time until the payload fits — <b>never</b> truncating a
 *       result or a span mid-way. Never drops below one result: a single oversized result is
 *       delivered whole with the notice rather than split (the "never mid-payload / mid-span"
 *       guarantee is by construction).
 *   <li><b>Explicit notice.</b> When anything was degraded, a machine-readable {@code governor}
 *       object is appended naming what was dropped ({@code resultsDropped}, {@code provenanceStripped}),
 *       the budget, and the pre-degradation count.
 * </ol>
 *
 * <p>Head-side only (tempdoc 775: the governor governs the MCP delivery payload; it reads the
 * already-returned response objects and never reaches into Lucene or Worker internals — Hard
 * Invariant #1). Deterministic: the same assembled payload produces the same governed output,
 * byte-stable (rank-ordered tail drop + {@link LinkedHashMap} key order + a single serializer).
 */
final class McpDeliveryGovernor {

  private McpDeliveryGovernor() {}

  /** The two per-hit numeric-provenance keys stripped first (770's {@code detail} tier). */
  private static final List<String> PROVENANCE_KEYS = List.of("trace", "legScores");

  /**
   * Governs the assembled {@code justsearch_search} structuredContent payload in place, degrading it
   * to fit {@code budgetBytes} of serialized JSON per tempdoc 775 §E's order.
   *
   * @param payload the assembled structuredContent map ({@link McpEvidenceProjection#searchEvidence}
   *     output) — mutated in place and returned
   * @param budgetBytes the serialized-JSON budget in bytes; {@code <= 0} disables the governor
   *     entirely (escape hatch, mirrors the CE {@code DOCS_TOO_LONG} 0-disables convention)
   * @param mapper the serializer used to measure size — the same generation the delivery uses, so
   *     the measured size tracks what actually ships
   * @return {@code payload} (possibly with a stripped provenance tier, dropped tail results, and an
   *     appended {@code governor} notice)
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> govern(
      Map<String, Object> payload, int budgetBytes, ObjectMapper mapper) {
    if (payload == null || budgetBytes <= 0) {
      return payload; // disabled (0) or nothing to govern
    }
    int size = serializedBytes(payload, mapper);
    if (size < 0 || size <= budgetBytes) {
      return payload; // under budget (or unmeasurable — fail open, never break delivery)
    }

    Object resultsObj = payload.get("results");
    if (!(resultsObj instanceof List<?>)) {
      return payload; // no results to degrade — leave the payload untouched
    }
    List<Object> results = (List<Object>) resultsObj;
    int originalResultCount = results.size();

    // Step (a): strip the per-hit numeric provenance tier (no document content) from every hit.
    boolean provenanceStripped = false;
    for (Object hitObj : results) {
      if (hitObj instanceof Map<?, ?> hit) {
        Map<String, Object> h = (Map<String, Object>) hit;
        for (String key : PROVENANCE_KEYS) {
          if (h.remove(key) != null) {
            provenanceStripped = true;
          }
        }
      }
    }

    // Step (b): if still over budget, drop WHOLE tail results (lowest-ranked first), never below one.
    int resultsDropped = 0;
    while (serializedBytes(payload, mapper) > budgetBytes && results.size() > 1) {
      results.remove(results.size() - 1);
      resultsDropped++;
    }

    if (provenanceStripped || resultsDropped > 0) {
      payload.put(
          "governor",
          notice(budgetBytes, originalResultCount, results.size(), resultsDropped, provenanceStripped));
    }
    return payload;
  }

  private static Map<String, Object> notice(
      int budgetBytes,
      int originalResultCount,
      int deliveredResultCount,
      int resultsDropped,
      boolean provenanceStripped) {
    Map<String, Object> g = new LinkedHashMap<>();
    g.put("budgetBytes", budgetBytes);
    g.put("originalResultCount", originalResultCount);
    g.put("deliveredResultCount", deliveredResultCount);
    g.put("resultsDropped", resultsDropped);
    g.put("provenanceStripped", provenanceStripped);
    StringBuilder m = new StringBuilder(160);
    m.append("Response degraded to fit the ")
        .append(budgetBytes)
        .append("-byte delivery budget: ");
    List<String> parts = new ArrayList<>(2);
    if (provenanceStripped) {
      parts.add("per-hit ranking provenance (trace/legScores) stripped");
    }
    if (resultsDropped > 0) {
      parts.add(
          resultsDropped
              + " lowest-ranked result"
              + (resultsDropped == 1 ? "" : "s")
              + " dropped ("
              + deliveredResultCount
              + " of "
              + originalResultCount
              + " delivered)");
    }
    m.append(String.join("; ", parts))
        .append(". No content was truncated mid-result or mid-span.");
    g.put("notice", m.toString());
    return g;
  }

  /** Serialized size in UTF-8 bytes, or {@code -1} if the payload cannot be serialized. */
  private static int serializedBytes(Map<String, Object> payload, ObjectMapper mapper) {
    try {
      return mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
    } catch (RuntimeException e) {
      return -1;
    }
  }
}
