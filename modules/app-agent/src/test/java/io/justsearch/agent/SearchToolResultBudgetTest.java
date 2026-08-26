/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 877 §2.2 — the assertion that would have caught the tail-death.
 *
 * <p>{@code SearchTool.formatResults} used to divide the Layer-2 cap by {@code hits.size()} and then
 * charge each hit only {@code excerpt.length()}, counting neither the {@code [n] title} header, the
 * {@code Path:} line, the carrier line's framing, nor the trailing summary. At {@code limit:20} the
 * emitted string therefore ran past the cap and {@code AgentContextCompressor.truncate} cut the
 * tail — the lower-ranked hits AND the "Found N results" line — off the message the model saw.
 *
 * <p>Lives in {@code io.justsearch.agent} rather than beside the tool because the load-bearing half
 * of the check is running the REAL package-private {@code truncate} over the output: a length
 * assertion against a copy of the constant would pass while Layer 2 still fired.
 */
final class SearchToolResultBudgetTest {

  /** Long enough that an uncharged header/framing overshoot is certain, not marginal. */
  private static final String LONG_EXCERPT = "lorem ipsum dolor sit amet ".repeat(40);

  private static final String LONG_PATH =
      "/very/deeply/nested/knowledge/base/directory/tree/segment/for/budget/pressure/doc-";

  @Test
  @DisplayName("a 20-hit response fits the Layer-2 cap by construction, so truncate never fires")
  void twentyHitsFitTheLayerTwoCap() {
    KnowledgeSearchResponse response = responseWithLongHits(20);
    SearchTool tool = new SearchTool(req -> response);

    OperationResult result = tool.execute("{\"query\":\"budget\",\"limit\":20}");
    String message = result.message();

    assertTrue(result.success(), message);
    assertTrue(
        message.length() <= ToolResultCarrier.layerTwoCapChars(),
        "formatted result is "
            + message.length()
            + " chars, over the Layer-2 cap of "
            + ToolResultCarrier.layerTwoCapChars());
    assertEquals(
        message,
        AgentContextCompressor.truncate(message),
        "Layer 2 must not fire on a result the producer already budgeted");
    assertTrue(
        message.contains("Found 20 results"),
        "the trailing summary is reserved out of the budget, so it can never be the part cut: "
            + message);
  }

  private static KnowledgeSearchResponse responseWithLongHits(int count) {
    List<KnowledgeSearchResponse.Hit> hits =
        IntStream.rangeClosed(1, count)
            .mapToObj(
                i ->
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("doc-" + i)
                        .score(1.0 - (i * 0.01))
                        .fields(
                            Map.of(
                                "title",
                                "A fairly long document title for hit number " + i,
                                "path",
                                LONG_PATH + i + ".pdf"))
                        .matchedFields(List.of("content"))
                        .excerptRegions(
                            List.of(
                                new KnowledgeSearchResponse.ExcerptRegion(
                                    LONG_EXCERPT, 0, 30, 1, List.of()),
                                new KnowledgeSearchResponse.ExcerptRegion(
                                    LONG_EXCERPT, 40, 70, 2, List.of())))
                        .build())
            .toList();
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(count)
        .tookMs(12)
        .results(hits)
        .build();
  }
}
