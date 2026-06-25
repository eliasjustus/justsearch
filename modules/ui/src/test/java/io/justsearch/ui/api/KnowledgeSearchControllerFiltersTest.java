/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 629 §Open issues #1/#2 — the request {@code filters} block must carry the agent-history
 * {@code collection} scope through to the search request. It was silently dropped by the inline
 * handler parsing, so the worker's default branch always fired {@code MUST_NOT collection:agent-history}
 * and 585's "search your agent history" (and restored-run searchability) returned nothing. No test
 * could catch it while the parsing was inline; {@code parseFilters} is the extracted, testable seam.
 */
@DisplayName("KnowledgeSearchController.parseFilters — collection scope")
final class KnowledgeSearchControllerFiltersTest {

  @Test
  @DisplayName("the agent-history collection scope is extracted (was silently dropped — 629 #1)")
  void extractsCollectionScope() {
    var filters =
        KnowledgeSearchController.parseFilters(Map.of("collection", List.of("agent-history")));
    assertEquals(
        List.of("agent-history"),
        filters.collection(),
        "filters.collection must reach the request, else the worker default-EXCLUDES agent-history");
  }

  @Test
  @DisplayName("no collection key → empty scope (the documents-default path), other filters still parse")
  void noCollectionKeyIsEmpty() {
    var filters = KnowledgeSearchController.parseFilters(Map.of("mime", List.of("application/pdf")));
    assertTrue(filters.collection().isEmpty(), "absent collection → empty (the default-exclude path)");
    assertEquals(List.of("application/pdf"), filters.mime(), "other filters are unaffected");
  }
}
