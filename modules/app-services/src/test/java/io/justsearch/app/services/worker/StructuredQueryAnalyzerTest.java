package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.StructuredQueryAnalyzer.StructuredQueryAnalysis;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StructuredQueryAnalyzer — deterministic source extraction (385 #10)")
class StructuredQueryAnalyzerTest {

  private static final Set<String> CORPUS =
      Set.of(
          "techcrunch",
          "the verge",
          "fortune",
          "new york times",
          "wall street journal",
          "forbes",
          "nature",
          "the new yorker");

  @Nested
  @DisplayName("Multi-word source detection")
  class MultiWordDetection {

    @Test
    void detectsMultiWordCorpusSource() {
      var result = StructuredQueryAnalyzer.analyze("the new york times article on AI", CORPUS);
      assertTrue(result.detectedSources().contains("new york times"));
    }

    @Test
    void detectsMultipleMultiWordSources() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "Does the new york times agree with the wall street journal on tariffs?", CORPUS);
      assertTrue(result.detectedSources().contains("new york times"));
      assertTrue(result.detectedSources().contains("wall street journal"));
    }

    @Test
    void caseInsensitiveMatch() {
      var result =
          StructuredQueryAnalyzer.analyze("The New York Times reported on climate change", CORPUS);
      assertTrue(result.detectedSources().contains("new york times"));
    }
  }

  @Nested
  @DisplayName("Extra known sources")
  class ExtraKnownSources {

    @Test
    void detectsExtraKnownMultiWordSource() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "the financial times analysis of Brexit", Set.of("techcrunch"));
      assertTrue(result.detectedSources().contains("financial times"));
    }

    @Test
    void detectsExtraKnownSourceNotInCorpus() {
      var result =
          StructuredQueryAnalyzer.analyze("the washington post investigation", Set.of());
      assertTrue(result.detectedSources().contains("the washington post"));
    }
  }

  @Nested
  @DisplayName("Single-word context — branch 1: article/report from/by CapWord")
  class SingleWordContextBranch1 {

    @Test
    void articleFromCapWord() {
      var result = StructuredQueryAnalyzer.analyze("article from Forbes about tech", CORPUS);
      assertTrue(result.detectedSources().contains("forbes"));
    }

    @Test
    void reportByCapWord() {
      var result = StructuredQueryAnalyzer.analyze("report by Nature on gene editing", CORPUS);
      assertTrue(result.detectedSources().contains("nature"));
    }

    @Test
    void storyInCapWord() {
      var result = StructuredQueryAnalyzer.analyze("story in Fortune about CEOs", CORPUS);
      assertTrue(result.detectedSources().contains("fortune"));
    }
  }

  @Nested
  @DisplayName("Single-word context — branch 2: from/according to CapWord article")
  class SingleWordContextBranch2 {

    @Test
    void fromCapWordArticle() {
      var result = StructuredQueryAnalyzer.analyze("from Forbes article about startups", CORPUS);
      assertTrue(result.detectedSources().contains("forbes"));
    }

    @Test
    void accordingToCapWordReport() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "according to Nature report on climate", CORPUS);
      assertTrue(result.detectedSources().contains("nature"));
    }
  }

  @Nested
  @DisplayName("Single-word context — branch 3: 'Quoted Name' article")
  class SingleWordContextBranch3 {

    @Test
    void quotedNameArticle() {
      var result =
          StructuredQueryAnalyzer.analyze("'The New Yorker' article on modern art", CORPUS);
      assertTrue(result.detectedSources().contains("the new yorker"));
    }
  }

  @Nested
  @DisplayName("Single-word context — branch 5: CapWord reported/said that")
  class SingleWordContextBranch5 {

    @Test
    void reportedThat() {
      var result = StructuredQueryAnalyzer.analyze("Forbes reported that AI spending grew", CORPUS);
      assertTrue(result.detectedSources().contains("forbes"));
    }

    @Test
    void saidThat() {
      var result = StructuredQueryAnalyzer.analyze("Fortune said that the market recovered", CORPUS);
      assertTrue(result.detectedSources().contains("fortune"));
    }

    @Test
    void notedThat() {
      var result = StructuredQueryAnalyzer.analyze("Nature noted that the study was flawed", CORPUS);
      assertTrue(result.detectedSources().contains("nature"));
    }

    @Test
    void doesNotMatchCommonWords() {
      // "He reported that..." — "He" is not a source
      var result = StructuredQueryAnalyzer.analyze("He reported that things changed", CORPUS);
      assertTrue(
          result.detectedSources().isEmpty(),
          "Common pronoun 'He' should not match as a source");
    }
  }

  @Nested
  @DisplayName("False positive rejection")
  class FalsePositiveRejection {

    @Test
    void monthNameNotDetectedAsSource() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "What happened in November 2023?", Set.of("november daily"));
      // "november" alone should not match — it's a month name, not in a publication context
      assertFalse(
          result.detectedSources().stream().anyMatch(s -> s.equals("november")),
          "Month name 'november' should not be detected as a standalone source");
    }

    @Test
    void topicPhraseNotDetectedAsSource() {
      // "openai" is a topic, not a publication — no context pattern match
      var result =
          StructuredQueryAnalyzer.analyze("OpenAI research on language models", Set.of("openai"));
      // "openai" is single-word and NOT in a publication context pattern
      // However it IS in the corpus — but the single-word extraction only fires via regex context
      // The multi-word path won't match (it's single-word).
      // This should NOT match because single-word corpus sources are not substring-matched.
      assertTrue(
          result.detectedSources().isEmpty(),
          "Single-word corpus source without publication context should not be detected");
    }

    @Test
    void unknownSourceNotDetected() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "article from Xyzzyplugh about nothing", Set.of("techcrunch"));
      // "Xyzzyplugh" matches the regex but isn't in corpus or extra known sources
      assertFalse(result.detectedSources().contains("xyzzyplugh"));
    }
  }

  @Nested
  @DisplayName("Topic remainder stripping")
  class TopicRemainderStripping {

    @Test
    void stripsSourceReferencePhrase() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "Does the TechCrunch article on Twitch subscription split suggest changes?", CORPUS);
      assertTrue(result.detectedSources().contains("techcrunch"));
      assertFalse(
          result.topicRemainder().toLowerCase().contains("techcrunch"),
          "Topic remainder should not contain the source name");
      assertTrue(
          result.topicRemainder().toLowerCase().contains("twitch"),
          "Topic remainder should retain the topic content");
    }

    @Test
    void stripsMultipleSources() {
      var result =
          StructuredQueryAnalyzer.analyze(
              "Does the Fortune report about AI agree with the TechCrunch article on startups?",
              CORPUS);
      String remainder = result.topicRemainder().toLowerCase();
      assertFalse(remainder.contains("fortune"));
      assertFalse(remainder.contains("techcrunch"));
    }

    @Test
    void preservesQueryWhenNoSources() {
      String query = "What is the best programming language?";
      var result = StructuredQueryAnalyzer.analyze(query, CORPUS);
      assertEquals(query, result.topicRemainder());
    }
  }

  @Nested
  @DisplayName("Empty corpus fallback")
  class EmptyCorpusFallback {

    @Test
    void emptyCorpusMatchesOnlyMultiWordExtraKnown() {
      var result =
          StructuredQueryAnalyzer.analyze("the washington post investigation into fraud", Set.of());
      assertTrue(result.detectedSources().contains("the washington post"));
    }

    @Test
    void emptyCorpusDoesNotMatchSingleWordWithoutContext() {
      var result = StructuredQueryAnalyzer.analyze("bloomberg stock market data", Set.of());
      // "bloomberg" is single-word in EXTRA_KNOWN_SOURCES — requires context pattern
      // BUT it's actually listed as a single-word entry and won't match multi-word path
      // The single-word regex branch would need "article from Bloomberg" etc.
      assertFalse(
          result.detectedSources().contains("bloomberg"),
          "Single-word extra source without context should not match via substring");
    }
  }

  @Nested
  @DisplayName("Null and blank handling")
  class NullAndBlankHandling {

    @Test
    void nullQuery() {
      var result = StructuredQueryAnalyzer.analyze(null, CORPUS);
      assertTrue(result.detectedSources().isEmpty());
      assertEquals("", result.topicRemainder());
    }

    @Test
    void blankQuery() {
      var result = StructuredQueryAnalyzer.analyze("   ", CORPUS);
      assertTrue(result.detectedSources().isEmpty());
    }

    @Test
    void nullCorpus() {
      var result = StructuredQueryAnalyzer.analyze("the washington post article", null);
      assertTrue(result.detectedSources().contains("the washington post"));
    }
  }

  @Nested
  @DisplayName("Multi-word source detection — longer name preferred")
  class LongerNamePreferred {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "the new york times report on elections",
          "according to the New York Times, the economy grew"
        })
    void longerNameMatchedOverShorterSubstring(String query) {
      Set<String> corpus = Set.of("new york times", "new york");
      var result = StructuredQueryAnalyzer.analyze(query, corpus);
      assertTrue(result.detectedSources().contains("new york times"));
    }
  }
}
