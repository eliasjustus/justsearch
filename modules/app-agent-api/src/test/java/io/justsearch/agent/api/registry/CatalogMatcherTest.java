package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CatalogMatcher (tempdoc 499 §4.3)")
final class CatalogMatcherTest {

  private static final List<String> SURFACE_IDS = List.of(
      "core.library-surface",
      "core.health-surface",
      "core.search-surface",
      "core.brain-surface",
      "core.settings-surface",
      "core.browse-surface",
      "core.help-surface",
      "core.logs-surface",
      "core.ask-surface",
      "core.unified-chat-surface",
      "core.free-chat-surface",
      "core.extract-surface",
      "core.activity-surface"
  );

  private final CatalogMatcher matcher = CatalogMatcher.defaultMatcher();

  private List<ResolutionResult.Suggestion<String>> match(String query) {
    return matcher.findAlternatives(query, SURFACE_IDS, Function.identity(), 3);
  }

  @Nested
  @DisplayName("Failure mode 1: typo")
  class Typo {

    @Test
    @DisplayName("core.libary → core.library-surface as top suggestion")
    void singleCharTypo() {
      var results = match("core.libary-surface");
      assertFalse(results.isEmpty(), "should produce suggestions");
      assertEquals("core.library-surface", results.get(0).refId());
    }

    @Test
    @DisplayName("core.lirbary-surface → core.library-surface (transposition)")
    void transpositionTypo() {
      var results = match("core.lirbary-surface");
      assertFalse(results.isEmpty(), "should produce suggestions");
      assertEquals("core.library-surface", results.get(0).refId());
    }

    @Test
    @DisplayName("core.serach-surface → core.search-surface")
    void searchTypo() {
      var results = match("core.serach-surface");
      assertFalse(results.isEmpty());
      assertEquals("core.search-surface", results.get(0).refId());
    }
  }

  @Nested
  @DisplayName("Failure mode 4: renamed ID (truncated)")
  class Renamed {

    @Test
    @DisplayName("core.library → core.library-surface (prefix match)")
    void truncatedId() {
      var results = match("core.library");
      assertFalse(results.isEmpty(), "should suggest the full surface ID");
      assertEquals("core.library-surface", results.get(0).refId());
    }

    @Test
    @DisplayName("core.health → core.health-surface")
    void truncatedHealth() {
      var results = match("core.health");
      assertFalse(results.isEmpty());
      assertEquals("core.health-surface", results.get(0).refId());
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("empty query returns no suggestions")
    void emptyQuery() {
      assertTrue(match("").isEmpty());
    }

    @Test
    @DisplayName("exact match returns the entry with confidence 1.0")
    void exactMatch() {
      var results = match("core.library-surface");
      assertFalse(results.isEmpty());
      assertEquals("core.library-surface", results.get(0).refId());
      assertEquals(1.0, results.get(0).confidence(), 0.001);
    }

    @Test
    @DisplayName("completely unrelated query returns no suggestions")
    void unrelatedQuery() {
      var results = match("vendor.totally-different.thing");
      assertTrue(results.isEmpty(), "very distant IDs should be below threshold");
    }

    @Test
    @DisplayName("max 3 results returned even when more candidates match")
    void maxResults() {
      var results = match("core.surface");
      assertTrue(results.size() <= 3);
    }
  }

  @Nested
  @DisplayName("Damerau-Levenshtein distance")
  class DLDistance {

    @Test
    @DisplayName("identical strings → distance 0")
    void identical() {
      assertEquals(0, DefaultCatalogMatcher.damerauLevenshtein("abc", "abc"));
    }

    @Test
    @DisplayName("single insertion → distance 1")
    void insertion() {
      assertEquals(1, DefaultCatalogMatcher.damerauLevenshtein("abc", "abcd"));
    }

    @Test
    @DisplayName("single deletion → distance 1")
    void deletion() {
      assertEquals(1, DefaultCatalogMatcher.damerauLevenshtein("abcd", "abc"));
    }

    @Test
    @DisplayName("single substitution → distance 1")
    void substitution() {
      assertEquals(1, DefaultCatalogMatcher.damerauLevenshtein("abc", "axc"));
    }

    @Test
    @DisplayName("adjacent transposition → distance 1")
    void transposition() {
      assertEquals(1, DefaultCatalogMatcher.damerauLevenshtein("ab", "ba"));
    }

    @Test
    @DisplayName("empty vs non-empty → distance = length")
    void emptyVsNonEmpty() {
      assertEquals(3, DefaultCatalogMatcher.damerauLevenshtein("", "abc"));
      assertEquals(3, DefaultCatalogMatcher.damerauLevenshtein("abc", ""));
    }
  }

  @Nested
  @DisplayName("noop matcher")
  class Noop {

    @Test
    @DisplayName("returns empty for any input")
    void noopReturnsEmpty() {
      var noop = CatalogMatcher.noop();
      assertTrue(noop.findAlternatives(
          "core.libary", SURFACE_IDS, Function.identity(), 3).isEmpty());
    }
  }
}
