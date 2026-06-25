package io.justsearch.indexerworker.disambiguation;

import static io.justsearch.indexerworker.disambiguation.EntityNormalizer.EntityType.LOCATION;
import static io.justsearch.indexerworker.disambiguation.EntityNormalizer.EntityType.ORGANIZATION;
import static io.justsearch.indexerworker.disambiguation.EntityNormalizer.EntityType.PERSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EntityNormalizer")
class EntityNormalizerTest {

  @Nested
  @DisplayName("normalize()")
  class Normalize {

    @Test
    @DisplayName("null and blank return empty string")
    void nullAndBlank() {
      assertEquals("", EntityNormalizer.normalize(null, PERSON));
      assertEquals("", EntityNormalizer.normalize("", PERSON));
      assertEquals("", EntityNormalizer.normalize("   ", PERSON));
    }

    @Test
    @DisplayName("case folds to lowercase")
    void caseFold() {
      assertEquals("john smith", EntityNormalizer.normalize("JOHN SMITH", PERSON));
      assertEquals("new york", EntityNormalizer.normalize("NEW YORK", LOCATION));
    }

    @Test
    @DisplayName("PERSON comma reorder: 'Smith, John' -> 'john smith'")
    void commaReorder() {
      assertEquals("john smith", EntityNormalizer.normalize("Smith, John", PERSON));
    }

    @Test
    @DisplayName("PERSON multi-comma kept as-is (too ambiguous)")
    void multiCommaKeptAsIs() {
      String result = EntityNormalizer.normalize("Smith, John, Jr.", PERSON);
      // Multi-comma: no reorder, commas become spaces, "jr" stripped as suffix
      assertEquals("smith john", result);
    }

    @Test
    @DisplayName("ORGANIZATION comma NOT reordered")
    void orgCommaNotReordered() {
      assertEquals("acme inc", EntityNormalizer.normalize("Acme, Inc.", ORGANIZATION));
    }

    @Test
    @DisplayName("honorific prefix stripped for PERSON: 'Dr. John Smith' -> 'john smith'")
    void honorificPrefix() {
      assertEquals("john smith", EntityNormalizer.normalize("Dr. John Smith", PERSON));
    }

    @Test
    @DisplayName("honorific suffix stripped for PERSON: 'John Smith Jr.' -> 'john smith'")
    void honorificSuffix() {
      assertEquals("john smith", EntityNormalizer.normalize("John Smith Jr.", PERSON));
    }

    @Test
    @DisplayName("multiple honorifics stripped: 'Dr. John Smith Jr. PhD' -> 'john smith'")
    void multipleHonorifics() {
      assertEquals("john smith", EntityNormalizer.normalize("Dr. John Smith Jr. PhD", PERSON));
    }

    @Test
    @DisplayName("single-token PERSON not stripped to empty")
    void singleTokenKept() {
      assertEquals("dr", EntityNormalizer.normalize("Dr.", PERSON));
    }

    @Test
    @DisplayName("leading article stripped for ORGANIZATION: 'The Acme Company' -> 'acme company'")
    void leadingArticleStripped() {
      assertEquals("acme company", EntityNormalizer.normalize("The Acme Company", ORGANIZATION));
    }

    @Test
    @DisplayName("leading article NOT stripped for PERSON")
    void articleNotStrippedForPerson() {
      // "The" is not in honorific prefixes, so it stays
      assertEquals("the rock", EntityNormalizer.normalize("The Rock", PERSON));
    }

    @Test
    @DisplayName("LOCATION passes through with case fold and whitespace collapse")
    void locationPassthrough() {
      assertEquals("new york city", EntityNormalizer.normalize("  New   York  City  ", LOCATION));
    }

    @Test
    @DisplayName("periods stripped: 'U.S.A.' -> 'usa'")
    void periodsStripped() {
      assertEquals("usa", EntityNormalizer.normalize("U.S.A.", LOCATION));
    }

    @Test
    @DisplayName("generational suffix: 'James Brown Jr.' -> 'james brown'")
    void generationalSuffix() {
      assertEquals("james brown", EntityNormalizer.normalize("James Brown Jr.", PERSON));
    }
  }

  @Nested
  @DisplayName("tokenize()")
  class Tokenize {

    @Test
    @DisplayName("null and blank return empty list")
    void nullAndBlank() {
      assertEquals(List.of(), EntityNormalizer.tokenize(null));
      assertEquals(List.of(), EntityNormalizer.tokenize(""));
      assertEquals(List.of(), EntityNormalizer.tokenize("   "));
    }

    @Test
    @DisplayName("splits on whitespace")
    void splits() {
      assertEquals(List.of("john", "smith"), EntityNormalizer.tokenize("john smith"));
    }

    @Test
    @DisplayName("collapses multiple spaces")
    void collapsesSpaces() {
      assertEquals(List.of("a", "b", "c"), EntityNormalizer.tokenize("  a   b  c  "));
    }

    @Test
    @DisplayName("single token")
    void singleToken() {
      assertEquals(List.of("smith"), EntityNormalizer.tokenize("smith"));
    }
  }

  @Nested
  @DisplayName("honorific sets")
  class HonorificSets {

    @Test
    @DisplayName("prefix set contains military ranks")
    void militaryPrefixes() {
      assertTrue(EntityNormalizer.HONORIFIC_PREFIXES.contains("gen"));
      assertTrue(EntityNormalizer.HONORIFIC_PREFIXES.contains("col"));
      assertTrue(EntityNormalizer.HONORIFIC_PREFIXES.contains("sgt"));
    }

    @Test
    @DisplayName("suffix set contains academic degrees")
    void academicSuffixes() {
      assertTrue(EntityNormalizer.HONORIFIC_SUFFIXES.contains("phd"));
      assertTrue(EntityNormalizer.HONORIFIC_SUFFIXES.contains("md"));
      assertTrue(EntityNormalizer.HONORIFIC_SUFFIXES.contains("mba"));
    }
  }
}
