package io.justsearch.systemtests.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendRegistry;
import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real RAG Evaluation Test ("Needle in a Haystack").
 *
 * <p>This test verifies that the LLM can correctly extract information
 * from retrieved context. It simulates the RAG (Retrieval Augmented Generation)
 * workflow by providing a "secret" fact within a larger context and asking
 * the model to retrieve it.
 *
 * <p><b>Test Strategy:</b>
 * <ol>
 *   <li>Create a document containing a unique "secret" (e.g., "BlueBanana")</li>
 *   <li>Embed the secret within distractor text</li>
 *   <li>Ask the model a question about the secret</li>
 *   <li>Assert the model's answer contains the secret</li>
 * </ol>
 */
@Tag("ai")
@DisplayName("Real RAG Evaluation")
class RealRagEvaluationTest {
  private static final Logger log = LoggerFactory.getLogger(RealRagEvaluationTest.class);

  /** The secret code that must be extracted from context. */
  private static final String SECRET_CODE = "BlueBanana42";

  /** Context document containing the secret. */
  private static final String CONTEXT_WITH_SECRET = """
      This document contains important information about our security protocols.

      Our company uses multiple layers of authentication. The first layer involves
      standard username and password verification. The second layer uses two-factor
      authentication via SMS or authenticator apps.

      For emergency access to the vault, the master passcode is: %s

      This code should only be used in case of complete system failure.
      Regular employees should never need to use this code. Contact IT support
      if you believe you need emergency access.

      Additional security measures include biometric scanning and badge access
      for physical entry to secure areas.
      """.formatted(SECRET_CODE);

  /** Distractor context (no secret). */
  private static final String DISTRACTOR_CONTEXT = """
      Company policies require all employees to complete annual security training.
      This training covers topics such as phishing prevention, password hygiene,
      and physical security awareness. Employees who do not complete training
      by the deadline may face access restrictions.
      """;

  private static AiBackend backend;

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
          .maxNewTokens(32) // Short answer expected
          .build();

      backend = new BackendRegistry().resolve("deterministic", config).orElseThrow(() -> new IllegalStateException("No backend provider available")).create(config);
      log.info("RealRagEvaluationTest initialized with model: {}", modelPath);
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

  @Test
  @DisplayName("Model extracts secret from context (Needle in Haystack)")
  void modelExtractsSecretFromContext() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      // Build a RAG-style prompt with context + question
      String ragPrompt = buildRagPrompt(
          CONTEXT_WITH_SECRET,
          "What is the master passcode for emergency vault access?"
      );

      // Use summarizeChunk as a proxy for generation
      // (The actual RAG would use a different endpoint, but this tests generation)
      AiBackend.ChunkRequest request = new AiBackend.ChunkRequest(
          UUID.randomUUID(),
          0,
          ragPrompt,
          0,
          32,
          Locale.ENGLISH.toLanguageTag()
      );

      AiBackend.ChunkResponse response = session.summarizeChunk(request);
      String answer = response.summaryText();

      assertNotNull(answer, "Answer should not be null");
      assertFalse(answer.isBlank(), "Answer should not be blank");

      log.info("Question: What is the master passcode?");
      log.info("Answer: {}", answer);

      // The answer should contain the secret code
      boolean containsSecret = answer.contains(SECRET_CODE)
          || answer.toLowerCase(Locale.ROOT).contains(SECRET_CODE.toLowerCase(Locale.ROOT));

      assertTrue(containsSecret,
          "Answer should contain the secret code '" + SECRET_CODE + "'. Got: " + answer);
    }
  }

  @Test
  @DisplayName("Model does not hallucinate secret from distractor context")
  void modelDoesNotHallucinateFromDistractor() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      // Ask about the secret using ONLY the distractor context (no secret present)
      String ragPrompt = buildRagPrompt(
          DISTRACTOR_CONTEXT,
          "What is the master passcode for emergency vault access?"
      );

      AiBackend.ChunkRequest request = new AiBackend.ChunkRequest(
          UUID.randomUUID(),
          0,
          ragPrompt,
          0,
          32,
          Locale.ENGLISH.toLanguageTag()
      );

      AiBackend.ChunkResponse response = session.summarizeChunk(request);
      String answer = response.summaryText();

      assertNotNull(answer);
      log.info("Question (distractor context): What is the master passcode?");
      log.info("Answer: {}", answer);

      // The answer should NOT contain the secret code (it wasn't in the context)
      boolean containsSecret = answer.contains(SECRET_CODE);

      assertFalse(containsSecret,
          "Model should not hallucinate the secret code when it's not in context. Got: " + answer);
    }
  }

  @Test
  @DisplayName("Model finds needle in multi-document context")
  void modelFindsNeedleInMultiDocContext() throws Exception {
    try (AiBackend.Session session = backend.createSession()) {
      // Combine multiple "documents" - the secret is in one of them
      String multiDocContext = """
          [Document 1: Employee Handbook]
          %s

          [Document 2: Security Protocols]
          %s

          [Document 3: IT Policies]
          All software installations must be approved by IT. Unauthorized software
          may be removed without notice. Contact helpdesk@company.com for requests.
          """.formatted(DISTRACTOR_CONTEXT, CONTEXT_WITH_SECRET);

      String ragPrompt = buildRagPrompt(
          multiDocContext,
          "What is the emergency vault passcode?"
      );

      AiBackend.ChunkRequest request = new AiBackend.ChunkRequest(
          UUID.randomUUID(),
          0,
          ragPrompt,
          0,
          32,
          Locale.ENGLISH.toLanguageTag()
      );

      AiBackend.ChunkResponse response = session.summarizeChunk(request);
      String answer = response.summaryText();

      assertNotNull(answer);
      log.info("Multi-doc answer: {}", answer);

      boolean containsSecret = answer.contains(SECRET_CODE)
          || answer.toLowerCase(Locale.ROOT).contains(SECRET_CODE.toLowerCase(Locale.ROOT));

      assertTrue(containsSecret,
          "Model should find the needle in multi-document context. Got: " + answer);
    }
  }

  /**
   * Builds a RAG-style prompt with context and question.
   */
  private String buildRagPrompt(String context, String question) {
    return """
        Based on the following context, answer the question.
        Only use information from the context. If the answer is not in the context, say "not found".

        Context:
        %s

        Question: %s

        Answer:""".formatted(context, question);
  }
}
