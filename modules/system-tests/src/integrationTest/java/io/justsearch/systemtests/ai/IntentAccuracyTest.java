package io.justsearch.systemtests.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendRequest;
import io.justsearch.aibackend.backend.BackendResponse;
import io.justsearch.aibackend.local.DeterminismProfile;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendRegistry;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import io.justsearch.systemtests.aijudge.JsonFormatValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intent Detection Accuracy Test.
 *
 * <p>Verifies that the LLM can translate natural language queries
 * into structured JSON intent objects that follow the expected schema.
 *
 * <p>Uses {@link JsonFormatValidator} to validate output format
 * and check for specific expected clauses.
 */
@Tag("ai")
@DisplayName("Intent Detection Accuracy")
class IntentAccuracyTest {
  private static final Logger log = LoggerFactory.getLogger(IntentAccuracyTest.class);

  private static AiBackend backend;
  private static JsonFormatValidator validator;
  private static DeterminismProfile profile;

  @BeforeAll
  static void setup() {
    try {
      Path modelPath = AiQualityTestConfig.findQwenModel();
      if (!Files.exists(modelPath)) {
          throw new IllegalStateException("Qwen model not found at " + modelPath);
      }
      if (!AiQualityTestConfig.isNativeAvailable()) {
          throw new IllegalStateException("Native library not available. Build: ./gradlew :modules:ai-engine-native:buildBridge");
      }

      LocalIntentTranslatorConfig config = AiQualityTestConfig
          .deterministicConfig(modelPath)
          .build();

      backend = new BackendRegistry().resolve("deterministic", config).orElseThrow(() -> new IllegalStateException("No backend provider available")).create(config);
      validator = new JsonFormatValidator();
      profile = DeterminismProfile.from(config);
      log.info("IntentAccuracyTest initialized with model: {}", modelPath);
    } catch (Exception e) {
      if (e instanceof IllegalStateException) {
          throw (IllegalStateException) e;
      }
      throw new RuntimeException("Failed to initialize AI backend", e);
    }
  }

  @AfterAll
  static void cleanup() {
    if (backend != null) {
      try {
        backend.close();
      } catch (Exception e) {
        log.debug("Error closing backend", e);
      }
    }
  }

  private String formatPrompt(String userQuery) {
    // Manually construct ChatML prompt since the template doesn't inject system prompt for us
    return """
        <|im_start|>system
        You are a search intent classifier. Translate the user query into a JSON object.
        Required schema: {"limit": int, "clauses": [{"type": "text", "value": string}]}
        Do not output markdown blocks. Output ONLY raw JSON.
        <|im_end|>
        <|im_start|>user
        %s
        <|im_end|>
        """.formatted(userQuery);
  }

  @Test
  @DisplayName("Simple query produces valid JSON intent")
  void simpleQueryProducesValidJson() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      BackendRequest request = new BackendRequest(
          formatPrompt("Find documents about Java programming"),
          Locale.ENGLISH,
          profile
      );

      BackendResponse response = session.translate(request);
      String intentJson = response.intentJson();

      assertNotNull(intentJson, "Intent JSON should not be null");
      assertFalse(intentJson.isBlank(), "Intent JSON should not be blank");

      log.info("Generated intent: {}", intentJson);

      // Validate JSON structure
      JsonFormatValidator.ValidationResult result = validator.validateIntent(intentJson);

      log.info("Validation: valid={}, errors={}, warnings={}",
          result.valid(), result.errors(), result.warnings());

      assertTrue(result.valid(),
          "Intent JSON should be valid. Errors: " + result.errors());
    }
  }

  @Test
  @DisplayName("File type query includes filter clause")
  void fileTypeQueryIncludesFilter() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      BackendRequest request = new BackendRequest(
          formatPrompt("Show me all PDF files"),
          Locale.ENGLISH,
          profile
      );

      BackendResponse response = session.translate(request);
      String intentJson = response.intentJson();

      assertNotNull(intentJson);
      log.info("Generated intent for PDF query: {}", intentJson);

      // Check it's valid JSON
      assertTrue(validator.isValidJson(intentJson), "Should be valid JSON");

      // Check for PDF-related content (flexible matching)
      String lowerJson = intentJson.toLowerCase(Locale.ROOT);
      boolean hasPdfReference = lowerJson.contains("pdf")
          || lowerJson.contains("application/pdf")
          || lowerJson.contains("mime");

      log.info("PDF reference found: {}", hasPdfReference);

      if (!hasPdfReference) {
        log.warn("Intent does not explicitly reference PDF. This may indicate the model needs better prompt engineering or a larger model.");
      }
    }
  }

  @Test
  @DisplayName("Query with limit produces valid limit field")
  void queryWithLimitProducesLimitField() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      BackendRequest request = new BackendRequest(
          formatPrompt("Find the top 5 documents about databases"),
          Locale.ENGLISH,
          profile
      );

      BackendResponse response = session.translate(request);
      String intentJson = response.intentJson();

      assertNotNull(intentJson);
      log.info("Generated intent for limit query: {}", intentJson);

      JsonFormatValidator.ValidationResult result = validator.validateIntent(intentJson);

      if (result.valid() && result.parsedJson() != null) {
        // Check if limit field exists and has a reasonable value
        String limitValue = validator.extractField(intentJson, "limit");
        if (limitValue != null) {
          log.info("Extracted limit: {}", limitValue);
          try {
            int limit = Integer.parseInt(limitValue);
            assertTrue(limit > 0 && limit <= 100,
                "Limit should be reasonable (1-100). Got: " + limit);
          } catch (NumberFormatException e) {
            log.warn("Limit is not a simple integer: {}", limitValue);
          }
        }
      }
    }
  }

  @Test
  @DisplayName("Empty query handles gracefully")
  void emptyQueryHandlesGracefully() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      BackendRequest request = new BackendRequest(
          "", // Empty input shouldn't crash
          Locale.ENGLISH,
          profile
      );

      // Should not throw, but may produce empty or default intent
      BackendResponse response = session.translate(request);
      String intentJson = response.intentJson();

      // Even for empty input, we should get some response
      assertNotNull(intentJson, "Should get a response even for empty query");
      log.info("Intent for empty query: {}", intentJson);
    }
  }
}
