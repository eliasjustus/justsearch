package io.justsearch.indexerworker.util;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TextAnalysisUtils}. */
class TextAnalysisUtilsTest {

  @Nested
  class NormalizedQueryTermsTests {

    @Test
    void nullInput_returnsEmptySet() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms(null);
      assertTrue(result.isEmpty());
    }

    @Test
    void emptyString_returnsEmptySet() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("");
      assertTrue(result.isEmpty());
    }

    @Test
    void blankString_returnsEmptySet() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("   ");
      assertTrue(result.isEmpty());
    }

    @Test
    void singleWord_returnsSingleTerm() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("hello");
      assertEquals(1, result.size());
      assertTrue(result.contains("hello"));
    }

    @Test
    void mixedCase_lowercasesTerms() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("HELLO World");
      assertTrue(result.contains("hello"));
      assertTrue(result.contains("world"));
    }

    @Test
    void shortTerms_filtered() {
      // Single character terms should be filtered
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("a b cd ef");
      assertFalse(result.contains("a"));
      assertFalse(result.contains("b"));
      assertTrue(result.contains("cd"));
      assertTrue(result.contains("ef"));
    }

    @Test
    void specialCharacters_splitCorrectly() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("hello-world foo_bar");
      assertTrue(result.contains("hello"));
      assertTrue(result.contains("world"));
      assertTrue(result.contains("foo"));
      assertTrue(result.contains("bar"));
    }

    @Test
    void cappedAt10Terms() {
      String manyWords = "one two three four five six seven eight nine ten eleven twelve";
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms(manyWords);
      assertEquals(10, result.size());
    }

    @Test
    void unicodeWords_preserved() {
      Set<String> result = TextAnalysisUtils.normalizedQueryTerms("über café résumé");
      assertTrue(result.contains("über"));
      assertTrue(result.contains("café"));
      assertTrue(result.contains("résumé"));
    }
  }

  @Nested
  class AnalyzeTermsTests {

    private final Analyzer analyzer = new StandardAnalyzer();

    @Test
    void nullAnalyzer_returnsEmptySet() {
      Set<String> result = TextAnalysisUtils.analyzeTerms(null, "content", "hello world");
      assertTrue(result.isEmpty());
    }

    @Test
    void nullText_returnsEmptySet() {
      Set<String> result = TextAnalysisUtils.analyzeTerms(analyzer, "content", null);
      assertTrue(result.isEmpty());
    }

    @Test
    void blankText_returnsEmptySet() {
      Set<String> result = TextAnalysisUtils.analyzeTerms(analyzer, "content", "   ");
      assertTrue(result.isEmpty());
    }

    @Test
    void validText_extractsTerms() {
      Set<String> result = TextAnalysisUtils.analyzeTerms(analyzer, "content", "Hello World");
      assertTrue(result.contains("hello"));
      assertTrue(result.contains("world"));
    }

    @Test
    void multipleWords_extractsAllTerms() {
      Set<String> result = TextAnalysisUtils.analyzeTerms(analyzer, "content", "quick brown fox");
      assertTrue(result.contains("quick"));
      assertTrue(result.contains("brown"));
      assertTrue(result.contains("fox"));
    }

    @Test
    void cappedAt10Terms() {
      String manyWords = "apple banana cherry date elderberry fig grape honeydew ilama jackfruit kiwi lemon";
      Set<String> result = TextAnalysisUtils.analyzeTerms(analyzer, "content", manyWords);
      assertTrue(result.size() <= 10);
    }
  }

  @Nested
  class ComputeMatchedFieldsTests {

    @Test
    void vectorMode_returnsVectorField() {
      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              false, Set.of("test"), Map.of("content", "test data"));
      assertEquals(1, result.size());
      assertEquals("vector", result.get(0));
    }

    @Test
    void nullQueryTerms_returnsEmptyList() {
      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, null, Map.of("content", "test data"));
      assertTrue(result.isEmpty());
    }

    @Test
    void emptyQueryTerms_returnsEmptyList() {
      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of(), Map.of("content", "test data"));
      assertTrue(result.isEmpty());
    }

    @Test
    void nullFields_returnsEmptyList() {
      List<String> result =
          TextAnalysisUtils.computeMatchedFields(true, Set.of("test"), null);
      assertTrue(result.isEmpty());
    }

    @Test
    void emptyFields_returnsEmptyList() {
      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("test"), Map.of());
      assertTrue(result.isEmpty());
    }

    @Test
    void matchInPreview_returnsPreviewField() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.CONTENT_PREVIEW, "This is test data with matching content");
      fields.put(SchemaFields.CONTENT, "Full content here");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("test"), fields);

      assertTrue(result.contains(SchemaFields.CONTENT_PREVIEW));
      assertFalse(result.contains(SchemaFields.CONTENT));
    }

    @Test
    void noMatchInPreview_fallsBackToContent() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.CONTENT_PREVIEW, "No matching terms here");
      fields.put(SchemaFields.CONTENT, "Full test content here");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("test"), fields);

      assertTrue(result.contains(SchemaFields.CONTENT));
      assertFalse(result.contains(SchemaFields.CONTENT_PREVIEW));
    }

    @Test
    void noExplicitMatch_fallsBackToContent() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.CONTENT_PREVIEW, "No matching terms here");
      fields.put(SchemaFields.CONTENT, "Full content here");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("xyz"), fields);

      // Falls back to content since the document was returned by the search engine
      assertEquals(List.of(SchemaFields.CONTENT), result);
    }

    @Test
    void titleMatch_returnsTitleField() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.TITLE, "testing-strategy.md");
      fields.put(SchemaFields.CONTENT_PREVIEW, "No matching terms here");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("testing"), fields);

      assertTrue(result.contains(SchemaFields.TITLE));
    }

    @Test
    void pathMatch_returnsPathField() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.PATH, "docs/explanation/testing-strategy.md");
      fields.put(SchemaFields.CONTENT_PREVIEW, "No matching terms here");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("testing"), fields);

      assertTrue(result.contains(SchemaFields.PATH));
    }

    @Test
    void multipleFieldsMatch_returnsAll() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.TITLE, "testing guide");
      fields.put(SchemaFields.PATH, "docs/testing/guide.md");
      fields.put(SchemaFields.CONTENT_PREVIEW, "testing is important");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("testing"), fields);

      assertEquals(3, result.size());
      assertTrue(result.contains(SchemaFields.TITLE));
      assertTrue(result.contains(SchemaFields.PATH));
      assertTrue(result.contains(SchemaFields.CONTENT_PREVIEW));
    }

    @Test
    void titleAndPath_checkedBeforeContent() {
      Map<String, String> fields = new HashMap<>();
      fields.put(SchemaFields.TITLE, "testing guide");
      fields.put(SchemaFields.PATH, "docs/testing/guide.md");
      fields.put(SchemaFields.CONTENT_PREVIEW, "testing is important");

      List<String> result =
          TextAnalysisUtils.computeMatchedFields(
              true, Set.of("testing"), fields);

      // title and path should appear before content_preview
      assertEquals(SchemaFields.TITLE, result.get(0));
      assertEquals(SchemaFields.PATH, result.get(1));
      assertEquals(SchemaFields.CONTENT_PREVIEW, result.get(2));
    }
  }

  @Nested
  class ContainsAnyTests {

    @Test
    void nullText_returnsFalse() {
      assertFalse(TextAnalysisUtils.containsAny(null, Set.of("test")));
    }

    @Test
    void blankText_returnsFalse() {
      assertFalse(TextAnalysisUtils.containsAny("   ", Set.of("test")));
    }

    @Test
    void nullTerms_returnsFalse() {
      assertFalse(TextAnalysisUtils.containsAny("hello world", null));
    }

    @Test
    void emptyTerms_returnsFalse() {
      assertFalse(TextAnalysisUtils.containsAny("hello world", Set.of()));
    }

    @Test
    void termFound_returnsTrue() {
      assertTrue(TextAnalysisUtils.containsAny("hello world", Set.of("world")));
    }

    @Test
    void termNotFound_returnsFalse() {
      assertFalse(TextAnalysisUtils.containsAny("hello world", Set.of("foo")));
    }

    @Test
    void caseInsensitive_matchesRegardlessOfCase() {
      // Text is lowercased, so lowercase terms match uppercase text
      assertTrue(TextAnalysisUtils.containsAny("HELLO WORLD", Set.of("hello")));
      assertTrue(TextAnalysisUtils.containsAny("HELLO WORLD", Set.of("world")));
      // Mixed case text is also lowercased before comparison
      assertTrue(TextAnalysisUtils.containsAny("HeLLo WoRLd", Set.of("hello")));
    }

    @Test
    void multipleTerms_anyMatch_returnsTrue() {
      assertTrue(TextAnalysisUtils.containsAny("hello world", Set.of("foo", "bar", "world")));
    }

    @Test
    void multipleTerms_noneMatch_returnsFalse() {
      assertFalse(TextAnalysisUtils.containsAny("hello world", Set.of("foo", "bar", "baz")));
    }

    @Test
    void partialMatch_isSubstring_returnsTrue() {
      // "ell" is contained within "hello"
      assertTrue(TextAnalysisUtils.containsAny("hello", Set.of("ell")));
    }

    @Test
    void blankTerms_skipped() {
      // Even with blank terms, valid terms should still match
      assertTrue(TextAnalysisUtils.containsAny("hello world", Set.of("", "  ", "world")));
    }
  }
}
