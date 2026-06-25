package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.QueryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("QueryClassifier — rule-based pre-retrieval query classification (306)")
class QueryClassifierTest {

  @Nested
  @DisplayName("NAVIGATIONAL — file extensions")
  class FileExtensions {
    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "notes.txt", "data.csv", "README.md", "config.yaml"})
    void fileExtensionQueries(String query) {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify(query));
    }

    @Test
    void fileExtensionWithExtraTerms() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("report.pdf quarterly"));
    }

    @Test
    void caseInsensitiveExtension() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("REPORT.PDF"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.java", "index.ts", "main.py", "server.go", "lib.rs"})
    void codeFileExtensions(String query) {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify(query));
    }

    @Test
    @DisplayName("bare extension without filename is NOT navigational")
    void bareExtensionNotNavigational() {
      // "how to write a .py script" — the .py has no chars before the dot
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("how to write a .py script"));
    }

    @Test
    @DisplayName("bare extensions in natural language are NOT navigational")
    void bareExtensionsInNaturalLanguage() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("convert .json to .csv"));
    }
  }

  @Nested
  @DisplayName("NAVIGATIONAL — path fragments")
  class PathFragments {
    @Test
    void multiSegmentForwardSlash() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("src/main/java"));
    }

    @Test
    void backslashPath() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("C:\\Users\\docs\\notes"));
    }

    @Test
    void relativePathDotSlash() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("./config"));
    }

    @Test
    void relativePathDotDotSlash() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("../parent/dir"));
    }

    @Test
    @DisplayName("single forward slash in natural language is NOT navigational")
    void singleSlashNotNavigational() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("pros/cons of SPLADE"));
    }

    @Test
    @DisplayName("and/or is NOT navigational")
    void andOrNotNavigational() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("search and/or replace"));
    }
  }

  @Nested
  @DisplayName("NAVIGATIONAL — identifier detection")
  class Identifiers {
    @ParameterizedTest
    @ValueSource(strings = {"ResolvedConfigBuilder", "SearchOrchestrator", "QueryClassifier"})
    void camelCaseIdentifiers(String query) {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify(query));
    }

    @Test
    void snakeCaseIdentifier() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("worker_config_snapshot"));
    }

    @Test
    void kebabCaseWithDigits() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("cuda-12"));
    }

    @Test
    @DisplayName("plain lowercase single word is EXPLORATORY, not NAVIGATIONAL")
    void plainWordNotIdentifier() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("optimization"));
    }

    @Test
    void shortSingleChar() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("x"));
    }
  }

  @Nested
  @DisplayName("EXACT_MATCH — quoted phrases")
  class QuotedPhrases {
    @Test
    void quotedPhrase() {
      assertEquals(QueryType.EXACT_MATCH, QueryClassifier.classify("\"exact phrase match\""));
    }

    @Test
    void singleQuotedWord() {
      assertEquals(QueryType.EXACT_MATCH, QueryClassifier.classify("\"error\""));
    }

    @Test
    void notQuotedIfOnlyOpeningQuote() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("\"partial quote"));
    }
  }

  @Nested
  @DisplayName("INFORMATIONAL — question words")
  class QuestionWords {
    @Test
    void howQuestion() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("how to configure SSL"));
    }

    @Test
    void whatQuestion() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("what is JustSearch used for"));
    }

    @Test
    void whyQuestion() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("why does search return zero"));
    }

    @Test
    void shortQuestionWordNotEnoughTerms() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("how search"));
    }
  }

  @Nested
  @DisplayName("EXPLORATORY — single plain terms")
  class SingleTerms {
    @Test
    void singleWord() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("optimization"));
    }

    @Test
    void singleWordNoExtension() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("report"));
    }

    @Test
    void singleWordSearch() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("search"));
    }
  }

  @Nested
  @DisplayName("INFORMATIONAL — default for multi-term queries")
  class DefaultMultiTerm {
    @Test
    void multiTermDefault() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("search ranking quality"));
    }

    @Test
    void twoTermDefault() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("GPU configuration"));
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {
    @Test
    void nullQuery() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify(null));
    }

    @Test
    void emptyQuery() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify(""));
    }

    @Test
    void blankQuery() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("   "));
    }
  }

  @Nested
  @DisplayName("Helper method contracts")
  class HelperMethods {
    @Test
    void hasFileExtension_positive() {
      assertTrue(QueryClassifier.hasFileExtension("report.pdf"));
    }

    @Test
    void hasFileExtension_negative() {
      assertFalse(QueryClassifier.hasFileExtension("just a query"));
    }

    @Test
    void hasFileExtension_bareExtensionNegative() {
      assertFalse(QueryClassifier.hasFileExtension(".py"));
    }

    @Test
    void hasPathFragment_multiSegment() {
      assertTrue(QueryClassifier.hasPathFragment("src/main/java"));
    }

    @Test
    void hasPathFragment_backslash() {
      assertTrue(QueryClassifier.hasPathFragment("C:\\Users"));
    }

    @Test
    void hasPathFragment_singleSlashNegative() {
      assertFalse(QueryClassifier.hasPathFragment("pros/cons"));
    }

    @Test
    void hasPathFragment_negative() {
      assertFalse(QueryClassifier.hasPathFragment("no path here"));
    }

    @Test
    void isQuotedPhrase_positive() {
      assertTrue(QueryClassifier.isQuotedPhrase("\"hello world\""));
    }

    @Test
    void isQuotedPhrase_tooShort() {
      assertFalse(QueryClassifier.isQuotedPhrase("\"\""));
    }

    @Test
    void looksLikeIdentifier_camelCase() {
      assertTrue(QueryClassifier.looksLikeIdentifier("ResolvedConfig"));
    }

    @Test
    void looksLikeIdentifier_snakeCase() {
      assertTrue(QueryClassifier.looksLikeIdentifier("worker_config"));
    }

    @Test
    void looksLikeIdentifier_plainWord() {
      assertFalse(QueryClassifier.looksLikeIdentifier("optimization"));
    }

    @Test
    void looksLikeIdentifier_singleChar() {
      assertFalse(QueryClassifier.looksLikeIdentifier("x"));
    }
  }
}
