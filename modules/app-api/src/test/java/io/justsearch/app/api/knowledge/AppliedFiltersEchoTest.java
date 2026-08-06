/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.AppliedFilters;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Human-validation finding 4 (round 14): {@code appliedFilters} was absent from every
 * {@code POST /api/knowledge/search} response despite {@code docs/reference/api-contract-map.md}
 * specifying it is echoed when filters are active (366 §1b). Together with a count drawn from a
 * different population, that gave two independent misleading signals about whether filtering
 * happened at all.
 */
@DisplayName("KnowledgeSearchResponse.appliedFilters — the filter echo (366 §1b)")
final class AppliedFiltersEchoTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("no filters sent → no echo (the field stays absent from the wire)")
  void noFiltersMeansNoEcho() {
    assertNull(AppliedFilters.of(null, null), "absent filters must not fabricate an echo");
    assertNull(
        AppliedFilters.of(emptyFilters(), emptyFilters()),
        "an all-default Filters instance is not an active filter set");
  }

  @Test
  @DisplayName("a pathPrefix filter is echoed back verbatim")
  void pathPrefixIsEchoed() throws Exception {
    var sent = filtersWithPathPrefix("C:\\corpus\\docs");
    AppliedFilters echo = AppliedFilters.of(sent, null);

    assertNotNull(echo, "an active filter set must produce an echo");
    assertEquals("C:\\corpus\\docs", echo.filters().pathPrefix());
    assertNull(echo.boostFilters(), "no boostFilters were sent, so that half stays absent");

    String json = MAPPER.writeValueAsString(echo);
    assertTrue(json.contains("\"pathPrefix\""), "the echo must carry pathPrefix on the wire: " + json);
    assertFalse(json.contains("\"boostFilters\""), "absent halves are omitted: " + json);
    assertFalse(
        json.contains("[]"),
        "the echo mirrors what was SENT — unset list filters must not appear as empty arrays: "
            + json);
  }

  @Test
  @DisplayName("boostFilters alone still produce an echo")
  void boostFiltersAloneAreEchoed() {
    var boost =
        KnowledgeSearchRequestFiltersBuilder.builder().metaSource(List.of("wiki")).build();
    AppliedFilters echo = AppliedFilters.of(null, boost);

    assertNotNull(echo);
    assertNull(echo.filters(), "no hard filters were sent");
    assertEquals(List.of("wiki"), echo.boostFilters().metaSource());
  }

  @Test
  @DisplayName("isActive recognizes every filter member (a new filter must not silently stop echoing)")
  void isActiveCoversEveryMember() {
    assertFalse(AppliedFilters.isActive(null));
    assertFalse(AppliedFilters.isActive(emptyFilters()));

    assertTrue(
        AppliedFilters.isActive(
            KnowledgeSearchRequestFiltersBuilder.builder().mime(List.of("text/plain")).build()));
    assertTrue(AppliedFilters.isActive(filtersWithPathPrefix("/corpus")));
    assertTrue(
        AppliedFilters.isActive(
            KnowledgeSearchRequestFiltersBuilder.builder().docIds(List.of("a")).build()));
    assertTrue(
        AppliedFilters.isActive(
            KnowledgeSearchRequestFiltersBuilder.builder()
                .collection(List.of("agent-history"))
                .build()));
    assertTrue(
        AppliedFilters.isActive(
            KnowledgeSearchRequestFiltersBuilder.builder()
                .modifiedAt(new KnowledgeSearchRequest.TimeRangeMs(1L, 2L))
                .build()));
    assertTrue(
        AppliedFilters.isActive(
            KnowledgeSearchRequestFiltersBuilder.builder().includeChunks(true).build()));
  }

  @Test
  @DisplayName("the echo rides on the response record, so the controller mapping is contract-guarded")
  void echoIsAResponseComponent() {
    var response =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(3)
            .matchCount(3)
            .tookMs(1)
            .results(List.of())
            .appliedFilters(AppliedFilters.of(filtersWithPathPrefix("/corpus"), null))
            .build();
    assertNotNull(response.appliedFilters());
    assertEquals("/corpus", response.appliedFilters().filters().pathPrefix());
  }

  private static KnowledgeSearchRequest.Filters emptyFilters() {
    return KnowledgeSearchRequestFiltersBuilder.builder().build();
  }

  private static KnowledgeSearchRequest.Filters filtersWithPathPrefix(String prefix) {
    return KnowledgeSearchRequestFiltersBuilder.builder().pathPrefix(prefix).build();
  }
}
