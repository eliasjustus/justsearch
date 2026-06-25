package io.justsearch.indexerworker.disambiguation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.disambiguation.EntityNormalizer.EntityType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("SoftTFIDF")
class SoftTFIDFTest {

  // A small corpus of normalized mentions for IDF training.
  private static final List<String> CORPUS =
      List.of(
          "john smith",
          "jane smith",
          "john johnson",
          "sarah chen",
          "acme corporation",
          "acme corp",
          "ajax corp",
          "smith industries",
          "new york",
          "new york city",
          "springfield",
          "saint louis",
          "katherine jones",
          "katharine jones",
          "james brown",
          "michael brown");

  private SoftTFIDF scorer;

  @BeforeEach
  void setUp() {
    scorer = new SoftTFIDF(CORPUS, 0.9);
  }

  @Nested
  @DisplayName("Guard clauses")
  class GuardClauses {

    @Test
    @DisplayName("null or blank inputs return 0.0")
    void nullAndBlank() {
      assertEquals(0.0, scorer.score(null, "john smith"));
      assertEquals(0.0, scorer.score("john smith", null));
      assertEquals(0.0, scorer.score("", "john smith"));
      assertEquals(0.0, scorer.score("john smith", "   "));
    }
  }

  @Nested
  @DisplayName("PERSON matches (should score high)")
  class PersonMatches {

    @Test
    @DisplayName("identical strings score 1.0")
    void identical() {
      double score = scorer.score("john smith", "john smith");
      assertEquals(1.0, score, 0.01);
    }

    @Test
    @DisplayName("typo: 'jon smith' vs 'john smith' scores high")
    void singleCharTypo() {
      double score = scorer.score("jon smith", "john smith");
      assertTrue(score > 0.7, "Expected > 0.7 but got " + score);
    }

    @Test
    @DisplayName("spelling variant: 'katherine jones' vs 'katharine jones' scores high")
    void spellingVariant() {
      double score = scorer.score("katherine jones", "katharine jones");
      assertTrue(score > 0.8, "Expected > 0.8 but got " + score);
    }

    @Test
    @DisplayName("single-token: 'smith' vs 'smyth' — JW ~0.89, below theta=0.9, no match")
    void singleTokenBelowTheta() {
      // JW("smith","smyth") ≈ 0.89, just below theta=0.9, so SoftTFIDF returns 0
      double score = scorer.score("smith", "smyth");
      assertEquals(0.0, score, 0.01);
    }
  }

  @Nested
  @DisplayName("PERSON non-matches (should score low)")
  class PersonNonMatches {

    @Test
    @DisplayName("different first name: 'john smith' vs 'jane smith'")
    void differentFirst() {
      double score = scorer.score("john smith", "jane smith");
      // "john"/"jane" JW < 0.9 (different vowels), only "smith" matches
      assertTrue(score < 0.6, "Expected < 0.6 but got " + score);
    }

    @Test
    @DisplayName("different surname: 'john smith' vs 'john johnson'")
    void differentSurname() {
      double score = scorer.score("john smith", "john johnson");
      assertTrue(score < 0.6, "Expected < 0.6 but got " + score);
    }

    @Test
    @DisplayName("completely different: 'sarah chen' vs 'james brown'")
    void completelyDifferent() {
      double score = scorer.score("sarah chen", "james brown");
      assertEquals(0.0, score, 0.01);
    }
  }

  @Nested
  @DisplayName("ORGANIZATION matches")
  class OrgMatches {

    @Test
    @DisplayName("abbreviation: 'acme corporation' vs 'acme corp' — partial match via 'acme'")
    void abbreviation() {
      // "corp"/"corporation" JW ≈ 0.87, below theta=0.9, so only "acme" matches (exact)
      double score = scorer.score("acme corporation", "acme corp");
      assertTrue(score > 0.3, "Expected > 0.3 (acme matches) but got " + score);
      assertTrue(score < 0.8, "Expected < 0.8 (corp/corporation below JW threshold) but got " + score);
    }
  }

  @Nested
  @DisplayName("ORGANIZATION non-matches")
  class OrgNonMatches {

    @Test
    @DisplayName("different company: 'acme corp' vs 'ajax corp'")
    void differentCompany() {
      double score = scorer.score("acme corp", "ajax corp");
      // "acme"/"ajax" JW < 0.9, only "corp" matches with low IDF (appears in multiple)
      assertTrue(score < 0.6, "Expected < 0.6 but got " + score);
    }

    @Test
    @DisplayName("shared token: 'smith industries' vs 'smith & associates'")
    void sharedToken() {
      double score = scorer.score("smith industries", "smith associates");
      assertTrue(score < 0.7, "Expected < 0.7 but got " + score);
    }
  }

  @Nested
  @DisplayName("LOCATION matches")
  class LocationMatches {

    @Test
    @DisplayName("identical: 'new york' vs 'new york'")
    void identical() {
      assertEquals(1.0, scorer.score("new york", "new york"), 0.01);
    }

    @Test
    @DisplayName("abbreviated: 'saint louis' vs 'st louis'")
    void abbreviated() {
      // "saint"/"st" JW ~ 0.73, below theta=0.9 so won't soft-match
      // Only "louis" matches → partial score
      double score = scorer.score("saint louis", "st louis");
      assertTrue(score < 0.8, "Expected < 0.8 (saint/st below JW threshold)");
      assertTrue(score > 0.2, "Expected > 0.2 (louis matches)");
    }
  }

  @Nested
  @DisplayName("LOCATION non-matches")
  class LocationNonMatches {

    @Test
    @DisplayName("shared token: 'new york' vs 'new jersey'")
    void sharedToken() {
      double score = scorer.score("new york", "new jersey");
      assertTrue(score < 0.7, "Expected < 0.7 but got " + score);
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {

    @Test
    @DisplayName("empty corpus still works (all tokens get max IDF)")
    void emptyCorpus() {
      SoftTFIDF empty = new SoftTFIDF(List.of(), 0.9);
      double score = empty.score("john smith", "john smith");
      assertEquals(1.0, score, 0.01);
    }

    @Test
    @DisplayName("score is symmetric")
    void symmetric() {
      double ab = scorer.score("acme corporation", "acme corp");
      double ba = scorer.score("acme corp", "acme corporation");
      assertEquals(ab, ba, 0.001);
    }

    @Test
    @DisplayName("score bounded to [0, 1]")
    void bounded() {
      double score = scorer.score("john smith", "john smith");
      assertTrue(score >= 0.0 && score <= 1.0, "Score should be in [0,1]: " + score);
    }
  }

  /**
   * U5 curated pairs from the Phase C research document. Tests SoftTFIDF scoring through the
   * EntityNormalizer pipeline to validate threshold calibration end-to-end.
   *
   * <p>Known limitations (nicknames like Bob/Robert, acronyms like IBM) are expected to fail and
   * are excluded from the test data — they require external knowledge tables, not string similarity.
   */
  @Nested
  @DisplayName("U5 curated pairs (threshold validation)")
  class U5CuratedPairs {

    // PERSON pairs expected to score ABOVE 0.85 threshold
    static Stream<Arguments> personMatchPairs() {
      return Stream.of(
          Arguments.of("John Smith", "JOHN SMITH", "case normalization"),
          Arguments.of("Dr. John Smith", "John Smith", "honorific strip"),
          Arguments.of("John Smith Jr.", "John Smith", "suffix strip"),
          Arguments.of("Jon Smith", "John Smith", "single-char typo"),
          Arguments.of("Katherine Jones", "Katharine Jones", "spelling variant"),
          Arguments.of("James Brown Jr.", "James Brown", "generational suffix"));
    }

    // PERSON pairs expected to score BELOW 0.85 threshold
    static Stream<Arguments> personNonMatchPairs() {
      return Stream.of(
          Arguments.of("John Smith", "Jane Smith", "different person, shared surname"),
          Arguments.of("John Smith", "John Johnson", "different person, shared first"),
          Arguments.of("Sarah Chen", "Sarah Johnson", "different person, shared first"));
    }

    // ORG pairs expected to score ABOVE 0.80 threshold
    static Stream<Arguments> orgMatchPairs() {
      return Stream.of(
          Arguments.of("Acme Corp.", "Acme Corp", "punctuation"),
          Arguments.of("ACME CORPORATION", "Acme Corporation", "case normalization"),
          Arguments.of("The Acme Company", "Acme Company", "article strip"));
    }

    // ORG pairs expected to score BELOW 0.80 threshold
    static Stream<Arguments> orgNonMatchPairs() {
      return Stream.of(
          Arguments.of("Acme Corp", "Ajax Corp", "different company"));
    }

    // LOCATION pairs expected to score ABOVE 0.90 threshold
    static Stream<Arguments> locationMatchPairs() {
      return Stream.of(
          Arguments.of("New York", "new york", "case normalization"));
    }

    // LOCATION pairs expected to score BELOW 0.90 threshold
    static Stream<Arguments> locationNonMatchPairs() {
      return Stream.of(
          Arguments.of("New York", "New Jersey", "different location, shared prefix"),
          Arguments.of("Springfield IL", "Springfield MO", "same city different state"));
    }

    @ParameterizedTest(name = "PERSON match: \"{0}\" ~ \"{1}\" ({2})")
    @MethodSource("personMatchPairs")
    void personShouldMatch(String a, String b, String category) {
      double score =
          scorer.score(
              EntityNormalizer.normalize(a, EntityType.PERSON),
              EntityNormalizer.normalize(b, EntityType.PERSON));
      assertTrue(
          score >= 0.85,
          String.format("[%s] Expected >= 0.85 but got %.4f for '%s' ~ '%s'", category, score, a, b));
    }

    @ParameterizedTest(name = "PERSON non-match: \"{0}\" !~ \"{1}\" ({2})")
    @MethodSource("personNonMatchPairs")
    void personShouldNotMatch(String a, String b, String category) {
      double score =
          scorer.score(
              EntityNormalizer.normalize(a, EntityType.PERSON),
              EntityNormalizer.normalize(b, EntityType.PERSON));
      assertTrue(
          score < 0.85,
          String.format("[%s] Expected < 0.85 but got %.4f for '%s' ~ '%s'", category, score, a, b));
    }

    @ParameterizedTest(name = "ORG match: \"{0}\" ~ \"{1}\" ({2})")
    @MethodSource("orgMatchPairs")
    void orgShouldMatch(String a, String b, String category) {
      double score =
          scorer.score(
              EntityNormalizer.normalize(a, EntityType.ORGANIZATION),
              EntityNormalizer.normalize(b, EntityType.ORGANIZATION));
      assertTrue(
          score >= 0.80,
          String.format("[%s] Expected >= 0.80 but got %.4f for '%s' ~ '%s'", category, score, a, b));
    }

    @ParameterizedTest(name = "ORG non-match: \"{0}\" !~ \"{1}\" ({2})")
    @MethodSource("orgNonMatchPairs")
    void orgShouldNotMatch(String a, String b, String category) {
      double score =
          scorer.score(
              EntityNormalizer.normalize(a, EntityType.ORGANIZATION),
              EntityNormalizer.normalize(b, EntityType.ORGANIZATION));
      assertTrue(
          score < 0.80,
          String.format("[%s] Expected < 0.80 but got %.4f for '%s' ~ '%s'", category, score, a, b));
    }

    @ParameterizedTest(name = "LOCATION match: \"{0}\" ~ \"{1}\" ({2})")
    @MethodSource("locationMatchPairs")
    void locationShouldMatch(String a, String b, String category) {
      double score =
          scorer.score(
              EntityNormalizer.normalize(a, EntityType.LOCATION),
              EntityNormalizer.normalize(b, EntityType.LOCATION));
      assertTrue(
          score >= 0.90,
          String.format("[%s] Expected >= 0.90 but got %.4f for '%s' ~ '%s'", category, score, a, b));
    }

    @ParameterizedTest(name = "LOCATION non-match: \"{0}\" !~ \"{1}\" ({2})")
    @MethodSource("locationNonMatchPairs")
    void locationShouldNotMatch(String a, String b, String category) {
      double score =
          scorer.score(
              EntityNormalizer.normalize(a, EntityType.LOCATION),
              EntityNormalizer.normalize(b, EntityType.LOCATION));
      assertTrue(
          score < 0.90,
          String.format("[%s] Expected < 0.90 but got %.4f for '%s' ~ '%s'", category, score, a, b));
    }
  }
}
