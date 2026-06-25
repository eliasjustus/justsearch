package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.services.worker.AnswerTypeClassifier.AnswerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("AnswerTypeClassifier — rule-based answer format classification (385 #8)")
class AnswerTypeClassifierTest {

  @Nested
  @DisplayName("COMPARISON patterns")
  class ComparisonPatterns {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "Does the TechCrunch article suggest X while Bloomberg reports Y?",
          "Do both sources agree on the timeline?",
          "How does this compared to the previous quarter?",
          "What is the difference between the two reports?",
          "The findings are in contrast to earlier studies",
          "On the other hand, Fortune reported different numbers",
          "TechCrunch reported X whereas Bloomberg reported Y",
          "Do they agree on the outcome?",
          "The two sources disagree on the facts"
        })
    void comparisonQueryDetected(String query) {
      assertEquals(AnswerType.COMPARISON, AnswerTypeClassifier.classify(query, 0));
    }

    @Test
    void doesWhilePatternWithSources() {
      assertEquals(
          AnswerType.COMPARISON,
          AnswerTypeClassifier.classify(
              "Does the TechCrunch article on Twitch suggest revenue decline while the Fortune"
                  + " article suggests growth?",
              0));
    }

    @Test
    void suggestWhilePattern() {
      assertEquals(
          AnswerType.COMPARISON,
          AnswerTypeClassifier.classify(
              "Does one report suggest growth while another suggests decline?", 0));
    }
  }

  @Nested
  @DisplayName("TEMPORAL from date count")
  class TemporalFromDateCount {

    @Test
    void twoOrMoreDatesClassifiesAsTemporal() {
      assertEquals(AnswerType.TEMPORAL, AnswerTypeClassifier.classify("some query", 2));
    }

    @Test
    void threeDatesClassifiesAsTemporal() {
      assertEquals(AnswerType.TEMPORAL, AnswerTypeClassifier.classify("some query", 3));
    }

    @Test
    void oneDateDoesNotClassifyAsTemporal() {
      assertEquals(AnswerType.INFERENCE, AnswerTypeClassifier.classify("some query", 1));
    }

    @Test
    void comparisonTakesPrecedenceOverTemporal() {
      assertEquals(
          AnswerType.COMPARISON,
          AnswerTypeClassifier.classify("Does X agree on something?", 3));
    }
  }

  @Nested
  @DisplayName("INFERENCE default")
  class InferenceDefault {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "What did Twitch announce about subscriptions?",
          "Who is the CEO of OpenAI?",
          "How does the search pipeline work?",
          "latest news about AI regulation"
        })
    void defaultInferenceForNonStructuredQueries(String query) {
      assertEquals(AnswerType.INFERENCE, AnswerTypeClassifier.classify(query, 0));
    }
  }

  @Nested
  @DisplayName("Null and blank handling")
  class NullAndBlankHandling {

    @Test
    void nullQuery() {
      assertEquals(AnswerType.INFERENCE, AnswerTypeClassifier.classify(null, 0));
    }

    @Test
    void blankQuery() {
      assertEquals(AnswerType.INFERENCE, AnswerTypeClassifier.classify("   ", 0));
    }
  }
}
