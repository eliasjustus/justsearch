/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 775 §E / §C — the delivery governor: deterministic degradation of the WHOLE assembled
 * {@code justsearch_search} MCP tool result at the client truncation cap.
 *
 * <p>Tempdoc 770 §E.3 characterized the client truncation cliff: past a threshold of serialized
 * bytes the whole tool result is lost and replaced by a fixed 2,322-char notice that delivers
 * <b>neither</b> content tier ({@code delivered_fields: null} — evidence loss, not degradation).
 * The cliff operates on the <b>entire delivered tool result</b> — the human-readable
 * {@code content[].text} block(s) PLUS the {@code structuredContent} channel PLUS the envelope
 * keys — not on the structured tier alone. A live measurement (orchestrator, 2026-07-22) confirmed
 * the failure mode this governor must handle: at {@code detail:true limit:30} on legal the worst
 * response was {@code structuredContent}=35,376 B (under any structured-only budget) +
 * {@code content[0].text}=16,339 B = 52,260 B on the wire — over the cliff. Budgeting the
 * structured tier alone under-counts by the text block's size and lets the governor declare success
 * while the wire payload is still over the cliff.
 *
 * <p>So the governed quantity is the full result. Because the text block is a rendering of the same
 * surviving results the structured tier projects, both tiers must shrink together: this governor
 * drives a {@link ResultView} that re-renders the FULL result (text + structuredContent + envelope)
 * for a given surviving-result count and provenance setting, and measures that whole result each
 * step. Degradation order (tempdoc 775 §E):
 *
 * <ol>
 *   <li><b>Numeric provenance first.</b> Render without the per-hit {@code trace} + {@code legScores}
 *       block — the {@code detail}-gated ranking-provenance tier (770: 19.9% of the delivered search
 *       payload, no document content). "Strip provenance" is simply projecting with
 *       {@code includeProvenance=false}; the text block is unaffected (it never carried provenance).
 *   <li><b>Then drop whole tail results.</b> Lowest-ranked first, one at a time, re-rendering both
 *       tiers each time until the full result fits — <b>never</b> truncating a result or span
 *       mid-way, and never below one result (a single oversized result is delivered whole with the
 *       notice rather than split).
 *   <li><b>Explicit notice.</b> When anything was degraded, a machine-readable {@code governor}
 *       object is added to {@code structuredContent} naming what was dropped ({@code resultsDropped},
 *       {@code provenanceStripped}), the budget, and the pre-degradation count.
 * </ol>
 *
 * <p>Head-side only (tempdoc 775: the governor governs the MCP delivery result; it reads the
 * already-returned response objects via the view and never reaches into Lucene or Worker internals —
 * Hard Invariant #1). Deterministic: the same inputs drive the same size decisions and the same
 * re-render, byte-stable ({@link LinkedHashMap} key order + one serializer).
 */
final class McpDeliveryGovernor {

  private McpDeliveryGovernor() {}

  /**
   * Renders the full assembled tool result (content text block(s) + {@code structuredContent} +
   * envelope keys) for the top {@code keepResults} results, projecting per-hit numeric provenance
   * iff {@code includeProvenance}. The governor measures the serialized size of the returned map,
   * so it must be the exact map the tool delivers.
   */
  @FunctionalInterface
  interface ResultView {
    Map<String, Object> render(int keepResults, boolean includeProvenance);
  }

  /**
   * Governs the full {@code justsearch_search} tool result, degrading it to fit {@code budgetBytes}
   * of serialized full-result JSON per tempdoc 775 §E's order.
   *
   * @param totalResults the number of results the search returned (the pre-degradation count)
   * @param detailRequested whether the caller asked for the {@code detail} provenance tier — when
   *     true, the first degradation step renders without it
   * @param budgetBytes the serialized full-result budget in bytes; {@code <= 0} disables the
   *     governor entirely (escape hatch, mirrors the CE {@code DOCS_TOO_LONG} 0-disables convention)
   * @param mapper the serializer used to measure size — the same generation the delivery uses
   * @param view re-renders the full result for a given surviving-result count + provenance setting
   * @return the delivered full-result map, degraded and carrying a {@code governor} notice iff
   *     anything was degraded
   */
  static Map<String, Object> govern(
      int totalResults,
      boolean detailRequested,
      int budgetBytes,
      ObjectMapper mapper,
      ResultView view) {
    Map<String, Object> full = view.render(totalResults, detailRequested);
    if (budgetBytes <= 0 || totalResults == 0 || fullBytes(full, mapper) <= budgetBytes) {
      return full; // disabled, empty, or already under budget — deliver as-is
    }

    // Every candidate below is measured AS DELIVERED — i.e. with the `governor` notice already
    // attached — because the notice itself rides the wire and counts against the cliff. Fitting the
    // notice-less payload and then adding the notice would silently re-cross the budget.
    boolean provenanceStripped = detailRequested;

    // Step (a): strip numeric provenance (render without it). Only meaningful when detail was on.
    if (detailRequested) {
      Map<String, Object> stripped =
          withNotice(view.render(totalResults, false), budgetBytes, totalResults, totalResults, 0, true);
      if (fullBytes(stripped, mapper) <= budgetBytes) {
        return stripped;
      }
    }

    // Step (b): drop WHOLE tail results (lowest-ranked first), re-rendering each time, never below
    // one result and never truncating a result or span mid-way.
    Map<String, Object> candidate = null;
    int keep = totalResults;
    while (keep > 1) {
      keep--;
      candidate =
          withNotice(
              view.render(keep, false), budgetBytes, totalResults, keep, totalResults - keep,
              provenanceStripped);
      if (fullBytes(candidate, mapper) <= budgetBytes) {
        return candidate;
      }
    }
    // Floor: a single result is delivered whole (never split), even if still over budget.
    if (candidate != null) {
      return candidate;
    }
    // Reached only when totalResults == 1 (the drop loop never ran). If detail was on, provenance
    // was stripped — a real degradation, so keep the notice; otherwise nothing could be degraded
    // (a lone result can neither be dropped nor split) and the honest payload rides as-is, no notice.
    if (provenanceStripped) {
      return withNotice(view.render(1, false), budgetBytes, totalResults, 1, 0, true);
    }
    return full;
  }

  /** Adds the machine-readable {@code governor} notice into the result's {@code structuredContent}. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> withNotice(
      Map<String, Object> result,
      int budgetBytes,
      int originalResultCount,
      int deliveredResultCount,
      int resultsDropped,
      boolean provenanceStripped) {
    Object sc = result.get("structuredContent");
    if (sc instanceof Map<?, ?> structured) {
      ((Map<String, Object>) structured)
          .put(
              "governor",
              notice(
                  budgetBytes,
                  originalResultCount,
                  deliveredResultCount,
                  resultsDropped,
                  provenanceStripped));
    }
    return result;
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

  /** Serialized size of the full result in UTF-8 bytes, or {@link Integer#MAX_VALUE} on failure. */
  private static int fullBytes(Map<String, Object> result, ObjectMapper mapper) {
    try {
      return mapper.writeValueAsString(result).getBytes(StandardCharsets.UTF_8).length;
    } catch (RuntimeException e) {
      // Unmeasurable — treat as over budget so degradation proceeds, never silently over-delivering.
      return Integer.MAX_VALUE;
    }
  }
}
