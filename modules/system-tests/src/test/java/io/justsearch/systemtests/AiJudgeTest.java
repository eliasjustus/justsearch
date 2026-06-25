package io.justsearch.systemtests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.aijudge.JsonFormatValidator;
import io.justsearch.systemtests.aijudge.JsonFormatValidator.ValidationResult;
import io.justsearch.systemtests.aijudge.KeywordPresenceChecker;
import io.justsearch.systemtests.aijudge.KeywordPresenceChecker.KeywordResult;
import io.justsearch.systemtests.aijudge.SemanticSimilarityChecker;
import io.justsearch.systemtests.aijudge.SemanticSimilarityChecker.SimilarityResult;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AI Judge components.
 */
@DisplayName("AI Judge Components")
class AiJudgeTest {

  @Nested
  @DisplayName("KeywordPresenceChecker")
  class KeywordPresenceTests {

    private final KeywordPresenceChecker checker = new KeywordPresenceChecker();

    @Test
    @DisplayName("Finds all keywords when present")
    void findsAllKeywords() {
      String text = "The database uses connection pooling for better performance.";
      Set<String> keywords = Set.of("database", "connection", "pooling");

      KeywordResult result = checker.check(text, keywords);

      assertTrue(result.allFound());
      assertEquals(1.0, result.coverage(), 0.001);
      assertEquals(3, result.foundCount());
      assertEquals(0, result.missingCount());
    }

    @Test
    @DisplayName("Reports missing keywords")
    void reportsMissingKeywords() {
      String text = "The database stores data efficiently.";
      Set<String> keywords = Set.of("database", "connection", "pooling");

      KeywordResult result = checker.check(text, keywords);

      assertFalse(result.allFound());
      assertEquals(1, result.foundCount());
      assertEquals(2, result.missingCount());
      assertTrue(result.missing().contains("connection"));
      assertTrue(result.missing().contains("pooling"));
    }

    @Test
    @DisplayName("Case insensitive matching")
    void caseInsensitive() {
      String text = "DATABASE and CONNECTION";
      Set<String> keywords = Set.of("database", "connection");

      KeywordResult result = checker.check(text, keywords);

      assertTrue(result.allFound());
    }

    @Test
    @DisplayName("Handles empty input")
    void handlesEmptyInput() {
      KeywordResult emptyText = checker.check("", Set.of("word"));
      assertEquals(0.0, emptyText.coverage());

      KeywordResult emptyKeywords = checker.check("some text", Set.of());
      assertEquals(1.0, emptyKeywords.coverage());
    }

    @Test
    @DisplayName("Checks with variants finds derived forms")
    void checksWithVariants() {
      String text = "The databases are configured for optimization.";
      Set<String> keywords = Set.of("database", "configure", "optimize");

      KeywordResult result = checker.checkWithVariants(text, keywords);

      // Should find: databases (from database), configured (from configure)
      assertTrue(result.coverage() >= 0.66, "Should find at least 2 of 3 keywords");
    }

    @Test
    @DisplayName("Extracts keywords from text")
    void extractsKeywords() {
      String text = "Database connection pooling improves database performance. " +
          "Connection reuse reduces database load significantly.";

      Set<String> keywords = checker.extractKeywords(text, 5);

      assertNotNull(keywords);
      assertTrue(keywords.size() <= 5);
      // Most frequent non-stopwords should be extracted
      assertTrue(keywords.contains("database") || keywords.contains("connection"));
    }

    @Test
    @DisplayName("Meets threshold correctly")
    void meetsThreshold() {
      String text = "Database and connection are mentioned.";
      Set<String> keywords = Set.of("database", "connection", "pooling", "performance");

      KeywordResult result = checker.check(text, keywords);

      assertTrue(result.meetsThreshold(0.5));   // 2/4 = 0.5
      assertFalse(result.meetsThreshold(0.75)); // 2/4 < 0.75
    }
  }

  @Nested
  @DisplayName("JsonFormatValidator")
  class JsonFormatTests {

    private final JsonFormatValidator validator = new JsonFormatValidator();

    @Test
    @DisplayName("Validates correct intent JSON")
    void validatesCorrectJson() {
      String json = """
          {
            "limit": 10,
            "highlight": true,
            "clauses": [
              {"type": "text", "value": "search query"}
            ],
            "filters": {
              "language": "en"
            }
          }
          """;

      ValidationResult result = validator.validateIntent(json);

      assertTrue(result.valid());
      assertTrue(result.errors().isEmpty());
    }

    @Test
    @DisplayName("Detects missing required fields")
    void detectsMissingFields() {
      String json = """
          {
            "highlight": true
          }
          """;

      ValidationResult result = validator.validateIntent(json);

      assertFalse(result.valid());
      assertTrue(result.errors().stream().anyMatch(e -> e.contains("limit")));
      assertTrue(result.errors().stream().anyMatch(e -> e.contains("clauses")));
    }

    @Test
    @DisplayName("Detects invalid limit")
    void detectsInvalidLimit() {
      String jsonNegative = """
          {"limit": -5, "clauses": [{"type": "text", "value": "x"}]}
          """;

      ValidationResult result = validator.validateIntent(jsonNegative);

      assertFalse(result.valid());
      assertTrue(result.errors().stream().anyMatch(e -> e.contains("limit")));
    }

    @Test
    @DisplayName("Detects invalid JSON syntax")
    void detectsInvalidSyntax() {
      String invalidJson = "{ not valid json }";

      ValidationResult result = validator.validateIntent(invalidJson);

      assertFalse(result.valid());
      assertTrue(result.errors().stream().anyMatch(e -> e.contains("invalid JSON")));
    }

    @Test
    @DisplayName("Validates clause structure")
    void validatesClauseStructure() {
      String json = """
          {
            "limit": 10,
            "clauses": [
              {"type": "text"}
            ]
          }
          """;

      ValidationResult result = validator.validateIntent(json);

      assertFalse(result.valid());
      assertTrue(result.errors().stream().anyMatch(e -> e.contains("value")));
    }

    @Test
    @DisplayName("Warns on unknown clause types")
    void warnsOnUnknownClauseType() {
      String json = """
          {
            "limit": 10,
            "clauses": [
              {"type": "unknown_type", "value": "x"}
            ]
          }
          """;

      ValidationResult result = validator.validateIntent(json);

      assertTrue(result.valid()); // Still valid, just warning
      assertFalse(result.warnings().isEmpty());
      assertTrue(result.warnings().stream().anyMatch(w -> w.contains("unknown type")));
    }

    @Test
    @DisplayName("isValidJson performs simple check")
    void isValidJsonSimpleCheck() {
      assertTrue(validator.isValidJson("{}"));
      assertTrue(validator.isValidJson("{\"key\": \"value\"}"));
      assertFalse(validator.isValidJson("not json"));
      assertFalse(validator.isValidJson(null));
      assertFalse(validator.isValidJson(""));
    }

    @Test
    @DisplayName("extractField navigates JSON path")
    void extractsField() {
      String json = """
          {
            "filters": {
              "language": "en",
              "count": 5
            }
          }
          """;

      assertEquals("en", validator.extractField(json, "filters.language"));
      assertEquals("5", validator.extractField(json, "filters.count"));
      assertEquals(null, validator.extractField(json, "filters.missing"));
    }
  }

  @Nested
  @DisplayName("SemanticSimilarityChecker")
  class SemanticSimilarityTests {

    @Test
    @DisplayName("Stub mode works without model")
    void stubModeWorks() {
      // Use fallback mode (no model required)
      try (SemanticSimilarityChecker checker = SemanticSimilarityChecker.createWithFallback()) {
        SimilarityResult result = checker.compare(
            "The quick brown fox",
            "A fast brown fox"
        );

        assertNotNull(result);
        assertTrue(result.similarity() >= 0.0);
        assertTrue(result.similarity() <= 1.0);
      }
    }

    @Test
    @DisplayName("Identical texts have similarity 1.0")
    void identicalTexts() {
      try (SemanticSimilarityChecker checker = SemanticSimilarityChecker.createWithFallback()) {
        SimilarityResult result = checker.compare(
            "The database connection pool",
            "The database connection pool"
        );

        assertEquals(1.0, result.similarity(), 0.001);
        assertTrue(result.similar());
      }
    }

    @Test
    @DisplayName("Completely different texts have low similarity")
    void differentTexts() {
      try (SemanticSimilarityChecker checker = SemanticSimilarityChecker.createWithFallback()) {
        SimilarityResult result = checker.compare(
            "The database uses connection pooling",
            "Elephants live in Africa and Asia"
        );

        assertTrue(result.similarity() < 0.5, "Unrelated texts should have low similarity");
      }
    }

    @Test
    @DisplayName("Null and empty inputs handled")
    void handlesNullAndEmpty() {
      try (SemanticSimilarityChecker checker = SemanticSimilarityChecker.createWithFallback()) {
        SimilarityResult nullResult = checker.compare(null, "text");
        assertNotNull(nullResult);
        assertFalse(nullResult.similar());

        SimilarityResult emptyResult = checker.compare("", "text");
        assertNotNull(emptyResult);
      }
    }

    @Test
    @DisplayName("Evaluate with custom threshold")
    void evaluateWithThreshold() {
      try (SemanticSimilarityChecker checker = SemanticSimilarityChecker.createWithFallback()) {
        String text1 = "database connection pool configuration";
        String text2 = "database connection pool settings";

        SimilarityResult high = checker.evaluate(text1, text2, 0.3);
        SimilarityResult low = checker.evaluate(text1, text2, 0.99);

        assertTrue(high.similar());  // Should pass low threshold
        assertFalse(low.similar());  // Should fail very high threshold
      }
    }
  }
}
