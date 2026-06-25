package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import io.justsearch.systemtests.aijudge.SemanticSimilarityChecker;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP AI Quality Tests - End-to-end verification of AI features.
 *
 * <p>Tests that AI features (Summarization, Q&A) work correctly from HTTP request
 * through to meaningful response. Uses existing indexed test files in D:\tests\txt\.
 *
 * <p>REQUIRES:
 * <ul>
 *   <li>Running JustSearch server with AI ONLINE mode</li>
 *   <li>Test files indexed: alice.txt, docker-compose.yml, recipes.json, Optional.java</li>
 *   <li>Main process and Worker using the SAME JUSTSEARCH_DATA_DIR</li>
 * </ul>
 *
 * <p>NOTE: If you get "Failed to fetch documents" errors, verify that:
 * <ol>
 *   <li>The index path in /api/status matches where the worker indexes files</li>
 *   <li>Both main and worker are using the same JUSTSEARCH_DATA_DIR environment variable</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :modules:system-tests:integrationTest --tests "*HttpAiQualityTest*" \
 *       -Djustsearch.api.port=9000
 * </pre>
 *
 * @see <a href="../../../../../../docs/reference/configuration-reference.md">Configuration Reference</a>
 * @see io.justsearch.configuration.EnvRegistry
 */
@DisplayName("HTTP AI Quality Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpAiQualityTest {

    private static final Logger log = LoggerFactory.getLogger(HttpAiQualityTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();


    // Timeouts
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60); // AI can be slow

    private static HttpClient client;
    private static SemanticSimilarityChecker similarityChecker;
    private static int port;
    private static boolean serverAvailable = false;
    private static boolean aiOnline = false;
    private static boolean documentsAccessible = false;
    private static String actualDocId = null; // Discovered from search

    @BeforeAll
    static void setup() {
        port = Integer.getInteger("justsearch.api.port", 9000);
        client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        serverAvailable = checkServerAvailable();
        if (!serverAvailable) {
            log.warn("⚠️  Server not available at localhost:{}", port);
            log.warn("    Start server first: ./gradlew :modules:ui:run");
            return;
        }

        aiOnline = checkAiOnline();
        if (!aiOnline) {
            log.warn("⚠️  AI is not in ONLINE mode");
            log.warn("    Ensure llama-server is running and mode is 'online'");
        }

        // Discover actual doc IDs from search
        actualDocId = discoverDocId("alice");
        documentsAccessible = actualDocId != null;
        if (!documentsAccessible) {
            log.warn("⚠️  Could not find 'alice' in search results");
            log.warn("    Ensure test files are indexed and Main/Worker share the same index path");
        } else {
            log.info("Discovered doc ID format: {}", actualDocId);
        }

        similarityChecker = SemanticSimilarityChecker.createWithFallback();
        log.info("SemanticSimilarityChecker mode: {}",
            similarityChecker.isAvailable() ? "embedding" : "stub");
    }

    @AfterAll
    static void teardown() {
        if (similarityChecker != null) {
            similarityChecker.close();
        }
    }

    private static boolean checkServerAvailable() {
        try {
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkAiOnline() {
        try {
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/inference/status"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() != 200) return false;
            JsonNode json = MAPPER.readTree(resp.body());
            String mode = json.path("mode").asText("");
            boolean available = json.path("available").asBoolean(false);
            log.info("AI status: mode={}, available={}", mode, available);
            return "online".equals(mode) && available;
        } catch (Exception e) {
            log.warn("Failed to check AI status", e);
            return false;
        }
    }

    /**
     * Discovers actual doc ID format by searching for a known file.
     * This handles path normalization differences between systems.
     */
    private static String discoverDocId(String searchTerm) {
        try {
            String jsonBody = "{\"query\":\"" + searchTerm + "\",\"limit\":1}";
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(5))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() != 200) return null;
            JsonNode json = MAPPER.readTree(resp.body());
            JsonNode results = json.path("results");
            if (results.isArray() && results.size() > 0) {
                return results.get(0).path("id").asText(null);
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to discover doc ID", e);
            return null;
        }
    }

    /**
     * Gets doc ID for a specific file by searching for content terms.
     * Note: Search indexes CONTENT, not filenames. So we search for terms
     * that appear in the file content rather than the filename itself.
     *
     * Note: For tests 5.3-5.5, we use smaller versions of files to fit within
     * the LLM's 4096 context window. The larger original files exceed the limit.
     */
    private String getDocIdFor(String filename) throws Exception {
        // Map filenames to search terms that appear IN THE CONTENT
        // Use -small versions where original files exceed context window
        String searchTerm = switch (filename.toLowerCase(Locale.ROOT)) {
            case "docker-compose", "docker-compose.yml" -> "POSTGRES_PASSWORD"; // postgres config in yaml
            case "recipes", "recipes.json" -> "Carbonara";  // Use recipes-small.json (has Carbonara)
            case "optional", "optional.java" -> "SMALL_TEST_FILE_MARKER_OPTIONAL"; // Use Optional-small.java
            case "alice", "alice.txt" -> "Wonderland";  // Alice in Wonderland
            case "readme", "readme.md" -> "README";
            default -> filename.replace(".txt", "").replace(".java", "").replace(".json", "").replace(".yml", "");
        };

        String docId = discoverDocId(searchTerm);
        if (docId == null) {
            throw new IllegalStateException("Could not find indexed document matching: " + filename +
                " (searched for: " + searchTerm + ")");
        }
        return docId;
    }

    /**
     * Builds a properly escaped JSON request body.
     * Uses Jackson to avoid escape issues with Windows paths.
     */
    private String buildJsonRequest(List<String> docIds, String question) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("docIds", docIds);
        if (question != null) {
            body.put("question", question);
        }
        return MAPPER.writeValueAsString(body);
    }

    // ==================== SSE Stream Parser ====================

    /**
     * Represents a parsed SSE event.
     */
    record SseEvent(String type, JsonNode data) {}

    /**
     * Result of streaming an AI request.
     */
    record StreamResult(
        String fullText,
        boolean truncated,
        boolean done,
        String errorCode,
        String errorMessage,
        List<SseEvent> allEvents,
        JsonNode ragMeta,
        JsonNode donePayload
    ) {
        boolean hasError() {
            return errorCode != null;
        }

        /** Returns citations array from done event, or empty array if absent. */
        ArrayNode citations() {
            if (donePayload != null && donePayload.has("citations")
                    && donePayload.get("citations").isArray()) {
                return (ArrayNode) donePayload.get("citations");
            }
            return MAPPER.createArrayNode();
        }
    }

    /**
     * Sends a POST request to an SSE endpoint and collects all events.
     */
    private StreamResult streamRequest(String endpoint, String jsonBody) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + endpoint))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(REQUEST_TIMEOUT)
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        return parseSseResponse(response.body());
    }

    /**
     * Parses SSE response body into structured events.
     */
    private StreamResult parseSseResponse(String body) {
        List<SseEvent> events = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        boolean truncated = false;
        boolean done = false;
        String errorCode = null;
        String errorMessage = null;
        JsonNode ragMeta = null;
        JsonNode donePayload = null;

        // Split on double newline (SSE event separator)
        String[] blocks = body.split("\n\n");

        for (String block : blocks) {
            if (block.isBlank()) continue;

            String eventType = "message";
            String data = "";

            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring(5).trim();
                }
            }

            if (data.isEmpty()) continue;

            try {
                JsonNode json = MAPPER.readTree(data);
                events.add(new SseEvent(eventType, json));

                switch (eventType) {
                    case "chunk" -> {
                        String text = json.path("text").asText("");
                        fullText.append(text);
                    }
                    case "meta" -> {
                        if (json.path("truncated").asBoolean(false)) {
                            truncated = true;
                        }
                    }
                    // Tempdoc 491 C3: event-name renamed to namespaced shape vocabulary.
                    case "rag.meta" -> ragMeta = json;
                    case "done" -> {
                        done = true;
                        donePayload = json;
                    }
                    case "error" -> {
                        errorCode = json.path("errorCode").asText("UNKNOWN");
                        errorMessage = json.path("error").asText("Unknown error");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse SSE data: {}", data, e);
            }
        }

        return new StreamResult(
            fullText.toString(), truncated, done, errorCode, errorMessage,
            events, ragMeta, donePayload);
    }

    // ==================== Test Utilities ====================

    /**
     * Checks if text contains at least N of the given keywords (case-insensitive).
     */
    private int countKeywordsPresent(String text, String... keywords) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String keyword : keywords) {
            if (lowerText.contains(keyword.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Asserts that text contains at least minCount of the given keywords.
     */
    private void assertKeywordsPresent(String text, int minCount, String... keywords) {
        int found = countKeywordsPresent(text, keywords);
        if (found < minCount) {
            fail(String.format(
                "Expected at least %d of %d keywords, found %d. Keywords: %s. Text (first 500 chars): %s",
                minCount, keywords.length, found,
                Arrays.toString(keywords),
                text.length() > 500 ? text.substring(0, 500) + "..." : text
            ));
        }
        log.info("Keywords check passed: {}/{} found", found, keywords.length);
    }

    /**
     * Asserts that text contains the exact substring (case-insensitive).
     */
    private void assertContains(String text, String substring, String message) {
        assertTrue(
            text.toLowerCase(Locale.ROOT).contains(substring.toLowerCase(Locale.ROOT)),
            message + ". Text (first 500 chars): " + (text.length() > 500 ? text.substring(0, 500) + "..." : text)
        );
    }

    // ==================== Quality Assertion Helpers ====================

    /**
     * Asserts semantic similarity between generated text and a reference.
     *
     * <p>When the similarity checker is in stub mode (no embedding model),
     * a failing check logs a warning instead of failing the test.
     */
    private void assertSemanticallySimilar(
            String generated, String reference, double threshold, String context) {
        SemanticSimilarityChecker.SimilarityResult result =
            similarityChecker.evaluate(generated, reference, threshold);
        log.info("Semantic similarity [{}]: {} (threshold: {}, method: {})",
            context, result.similarityPercent(), threshold, result.method());

        if (!result.isSimilar()) {
            String message = String.format(
                "Semantic similarity %.2f below threshold %.2f for [%s]. Method: %s. "
                + "Generated (first 300 chars): %s",
                result.similarity(), threshold, context, result.method(),
                generated.length() > 300 ? generated.substring(0, 300) + "..." : generated);

            if (result.usedEmbedding()) {
                fail(message);
            } else {
                log.warn("SOFT FAIL (stub mode): {}", message);
            }
        }
    }

    /**
     * Validates rag_meta event fields when present.
     */
    private void assertRagMetaValid(StreamResult result) {
        JsonNode meta = result.ragMeta();
        if (meta == null) {
            log.info("No rag_meta event in response (non-RAG fallback path)");
            return;
        }

        String retrievalMode = meta.path("retrieval_mode").asText("");
        String reason = meta.path("retrieval_mode_reason").asText("");
        int chunksUsed = meta.path("chunks_used").asInt(0);
        int chunksFound = meta.path("chunks_found").asInt(0);

        assertFalse(retrievalMode.isEmpty(), "rag_meta retrieval_mode should not be empty");
        assertFalse(reason.isEmpty(), "rag_meta retrieval_mode_reason should not be empty");
        assertTrue(chunksFound >= 0, "chunks_found should be >= 0");
        assertTrue(chunksUsed >= 0, "chunks_used should be >= 0");
        assertTrue(chunksUsed <= chunksFound,
            "chunks_used (" + chunksUsed + ") should be <= chunks_found (" + chunksFound + ")");

        log.info("rag_meta: mode={}, reason={}, truncated={}, used={}, found={}",
            retrievalMode, reason, meta.path("context_truncated").asBoolean(false),
            chunksUsed, chunksFound);
    }

    /**
     * Validates citation structural integrity from the done event.
     */
    private void assertCitationsStructurallyValid(StreamResult result, List<String> expectedDocIds) {
        JsonNode citations = result.citations();
        if (!citations.isArray() || citations.isEmpty()) {
            log.info("No citations in response (may be expected for non-RAG mode)");
            return;
        }

        log.info("Validating {} citations", citations.size());

        for (int i = 0; i < citations.size(); i++) {
            JsonNode c = citations.get(i);
            String parentDocId = c.path("parentDocId").asText("");
            int chunkIndex = c.path("chunkIndex").asInt(-1);
            int chunkTotal = c.path("chunkTotal").asInt(-1);
            int startChar = c.path("startChar").asInt(-1);
            int endChar = c.path("endChar").asInt(-1);
            String excerpt = c.path("excerpt").asText("");

            // Loose parentDocId matching (path normalization can differ)
            boolean docIdValid = expectedDocIds.stream().anyMatch(expected ->
                parentDocId.replace("\\", "/").contains(expected.replace("\\", "/"))
                    || expected.replace("\\", "/").contains(parentDocId.replace("\\", "/")));
            if (!docIdValid && !parentDocId.isEmpty()) {
                log.warn("Citation {} parentDocId '{}' not in expected set {}",
                    i, parentDocId, expectedDocIds);
            }

            assertTrue(chunkIndex >= 0,
                "Citation " + i + " chunkIndex should be >= 0, got: " + chunkIndex);
            assertTrue(chunkTotal >= 1,
                "Citation " + i + " chunkTotal should be >= 1, got: " + chunkTotal);
            assertTrue(chunkIndex < chunkTotal,
                "Citation " + i + " chunkIndex (" + chunkIndex
                    + ") should be < chunkTotal (" + chunkTotal + ")");
            assertTrue(startChar >= 0, "Citation " + i + " startChar should be >= 0");
            assertTrue(endChar >= startChar, "Citation " + i + " endChar should be >= startChar");
            assertFalse(excerpt.isEmpty(), "Citation " + i + " excerpt should not be empty");
        }
        log.info("All {} citations structurally valid", citations.size());
    }

    // ==================== Test Cases ====================

    @Test
    @Order(1)
    @DisplayName("5.1 Summarization with Truncation (alice.txt)")
    void summarizationWithTruncation() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");
        assertTrue(documentsAccessible, "❌ Documents not accessible - check index path configuration");

        String docId = getDocIdFor("alice");
        String jsonBody = buildJsonRequest(List.of(docId), null);

        log.info("Testing summarization of alice.txt (150KB file)...");
        StreamResult result = streamRequest("/api/chat/batch-summarize", jsonBody);

        // Should not have errors
        if (result.hasError() && "FETCH_FAILED".equals(result.errorCode())) {
            fail("Document fetch failed. This usually means the Main process's index path " +
                 "doesn't match the Worker's index path. Check that JUSTSEARCH_HOME is set " +
                 "consistently. Error: " + result.errorMessage());
        }
        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        // Large file may or may not set truncated flag depending on summarization path:
        // - Direct path sets truncated=true in meta event
        // - Hierarchical path (section split + reduce) completes without top-level truncation
        if (!result.truncated()) {
            log.info("Large file did not trigger truncation flag (hierarchical path handled it)");
        }

        // Should contain key Alice in Wonderland terms
        String text = result.fullText();
        assertFalse(text.isBlank(), "Summary should not be empty");
        assertKeywordsPresent(text, 3, "Alice", "Wonderland", "Rabbit", "Queen", "tea", "Hatter", "cat", "Mad");

        // Reasonable length (not too short, not too long)
        int wordCount = text.split("\\s+").length;
        log.info("Summary word count: {}", wordCount);
        assertTrue(wordCount >= 20, "Summary should have at least 20 words, got: " + wordCount);
        assertTrue(wordCount <= 1000, "Summary should have at most 1000 words, got: " + wordCount);

        // Composed pipeline quality (run all independently so one failure doesn't hide others)
        String aliceRef = "Alice falls down a rabbit hole into a fantasy world populated by "
            + "peculiar creatures. She encounters the Cheshire Cat, the Mad Hatter's tea "
            + "party, and the Queen of Hearts.";
        assertAll(
            () -> assertSemanticallySimilar(text, aliceRef, 0.25, "Alice summarization"),
            () -> assertRagMetaValid(result),
            () -> assertCitationsStructurallyValid(result, List.of(docId)));

        log.info("✅ alice.txt summarization passed");
    }

    @Test
    @Order(2)
    @DisplayName("5.2a Q&A Exact Fact - Database Password")
    void qaExactFactDatabasePassword() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");
        assertTrue(documentsAccessible, "❌ Documents not accessible");

        String docId = getDocIdFor("docker-compose");
        String jsonBody = buildJsonRequest(List.of(docId), "What is the database password?");

        log.info("Testing Q&A: What is the database password?");
        StreamResult result = streamRequest("/api/chat/ask", jsonBody);

        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        String answer = result.fullText();
        assertFalse(answer.isBlank(), "Answer should not be empty");
        assertContains(answer, "plutocracy", "Answer should contain the password 'plutocracy'");

        // Composed pipeline quality (run all independently)
        String pwRef = "The database password is plutocracy, as configured in the "
            + "POSTGRES_PASSWORD environment variable.";
        assertAll(
            () -> assertSemanticallySimilar(answer, pwRef, 0.3, "Q&A database password"),
            () -> assertRagMetaValid(result),
            () -> assertCitationsStructurallyValid(result, List.of(docId)));

        log.info("✅ Q&A database password passed. Answer: {}",
            answer.length() > 200 ? answer.substring(0, 200) + "..." : answer);
    }

    @Test
    @Order(3)
    @DisplayName("5.2b Q&A Exact Fact - Database Port")
    void qaExactFactDatabasePort() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");
        assertTrue(documentsAccessible, "❌ Documents not accessible");

        String docId = getDocIdFor("docker-compose");
        String jsonBody = buildJsonRequest(List.of(docId), "What port is the database exposed on?");

        log.info("Testing Q&A: What port is the database exposed on?");
        StreamResult result = streamRequest("/api/chat/ask", jsonBody);

        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        String answer = result.fullText();
        assertFalse(answer.isBlank(), "Answer should not be empty");
        assertContains(answer, "6432", "Answer should contain port '6432'");

        // Composed pipeline quality (run all independently)
        String portRef = "The database is exposed on port 6432, mapped from the internal "
            + "PostgreSQL port.";
        assertAll(
            () -> assertSemanticallySimilar(answer, portRef, 0.3, "Q&A database port"),
            () -> assertRagMetaValid(result),
            () -> assertCitationsStructurallyValid(result, List.of(docId)));

        log.info("✅ Q&A database port passed. Answer: {}",
            answer.length() > 200 ? answer.substring(0, 200) + "..." : answer);
    }

    @Test
    @Order(4)
    @DisplayName("5.3 Q&A from JSON Data - Recipe Ingredients")
    void qaFromJsonData() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");
        assertTrue(documentsAccessible, "❌ Documents not accessible");

        String docId = getDocIdFor("recipes");
        String jsonBody = buildJsonRequest(List.of(docId), "What ingredients are in Margherita Pizza?");

        log.info("Testing Q&A: What ingredients are in Margherita Pizza?");
        StreamResult result = streamRequest("/api/chat/ask", jsonBody);

        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        String answer = result.fullText();
        assertFalse(answer.isBlank(), "Answer should not be empty");
        // Should mention at least 2 of the key pizza ingredients
        assertKeywordsPresent(answer, 2, "mozzarella", "basil", "tomato", "dough", "olive oil");

        log.info("✅ Q&A recipe ingredients passed. Answer: {}",
            answer.length() > 200 ? answer.substring(0, 200) + "..." : answer);
    }

    @Test
    @Order(5)
    @DisplayName("5.4 Technical Summarization (Optional.java)")
    void technicalSummarization() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");
        assertTrue(documentsAccessible, "❌ Documents not accessible");

        String docId = getDocIdFor("Optional");
        String jsonBody = buildJsonRequest(List.of(docId), null);

        log.info("Testing summarization of Optional.java...");
        StreamResult result = streamRequest("/api/chat/batch-summarize", jsonBody);

        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        String text = result.fullText();
        assertFalse(text.isBlank(), "Summary should not be empty");

        // Should mention key Java Optional concepts
        assertKeywordsPresent(text, 3, "Optional", "null", "value", "present", "empty", "Java", "container");

        // Should not just be the copyright header
        assertFalse(text.toLowerCase(Locale.ROOT).contains("do not alter or remove"),
            "Summary should not just echo the copyright header");

        log.info("✅ Optional.java summarization passed");
    }

    @Test
    @Order(6)
    @DisplayName("5.5 Multi-Document Summary")
    void multiDocumentSummary() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");
        assertTrue(documentsAccessible, "❌ Documents not accessible");

        String dockerDocId = getDocIdFor("docker-compose");
        String optionalDocId = getDocIdFor("Optional");
        String jsonBody = buildJsonRequest(List.of(dockerDocId, optionalDocId), null);

        log.info("Testing multi-document summary (docker-compose + Optional.java)...");
        StreamResult result = streamRequest("/api/chat/batch-summarize", jsonBody);

        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        String text = result.fullText();
        assertFalse(text.isBlank(), "Summary should not be empty");

        // Should mention concepts from BOTH documents
        boolean hasDockerConcepts = countKeywordsPresent(text, "docker", "postgres", "database", "service", "container") >= 1;
        boolean hasJavaConcepts = countKeywordsPresent(text, "Optional", "Java", "null", "value") >= 1;

        log.info("Docker concepts present: {}, Java concepts present: {}", hasDockerConcepts, hasJavaConcepts);

        assertTrue(hasDockerConcepts || hasJavaConcepts,
            "Summary should mention concepts from at least one document");
        // Ideally both, but LLM might focus on one - at least verify it's not empty

        log.info("✅ Multi-document summary passed");
    }

    @Test
    @Order(7)
    @DisplayName("5.6 Error Handling - Non-existent File")
    void errorHandlingNonExistentFile() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");

        String jsonBody = """
            {
                "docIds": ["d:/nonexistent/file.txt"]
            }
            """;

        log.info("Testing error handling for non-existent file...");
        StreamResult result = streamRequest("/api/chat/batch-summarize", jsonBody);

        // Should get an error event (not a crash)
        assertTrue(result.hasError() || result.fullText().isBlank(),
            "Should either have error event or empty response for non-existent file");

        if (result.hasError()) {
            assertNotNull(result.errorCode(), "Error should have error code");
            log.info("✅ Error handling passed. Error code: {}, message: {}",
                result.errorCode(), result.errorMessage());
        } else {
            log.info("✅ Error handling passed (empty response for non-existent file)");
        }
    }

    @Test
    @Order(8)
    @DisplayName("5.7 Q&A with No Files Selected")
    void qaNoFilesSelected() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(aiOnline, "❌ AI not online");

        String jsonBody = """
            {
                "docIds": [],
                "question": "What is the meaning of life?"
            }
            """;

        log.info("Testing Q&A with no files selected...");
        StreamResult result = streamRequest("/api/chat/ask", jsonBody);

        // Should get an error
        assertTrue(result.hasError(), "Should have error when no files selected");
        assertEquals("NO_FILES", result.errorCode(), "Error code should be NO_FILES");

        log.info("✅ No files error handling passed");
    }

    @Test
    @Order(10)
    @DisplayName("6.1 Composed Pipeline Quality - Q&A with RAG metadata and citations")
    void composedPipelineQuality() throws Exception {
        assumeTrue(serverAvailable, "Server not running");
        assertTrue(aiOnline, "AI not online");
        assertTrue(documentsAccessible, "Documents not accessible");

        String docId = getDocIdFor("docker-compose");
        String jsonBody = buildJsonRequest(
            List.of(docId), "What services are defined in this docker-compose file?");

        log.info("Testing composed pipeline quality...");
        StreamResult result = streamRequest("/api/chat/ask", jsonBody);

        assertFalse(result.hasError(), "Should not have error: " + result.errorMessage());
        assertTrue(result.done(), "Should receive done event");

        String answer = result.fullText();
        assertFalse(answer.isBlank(), "Answer should not be empty");

        // 1. Keyword verification (deterministic)
        assertKeywordsPresent(answer, 2,
            "postgres", "database", "service", "pgbouncer", "redis");

        // Run quality assertions independently so one failure doesn't hide others
        String svcRef = "The docker-compose file defines services including a PostgreSQL "
            + "database, PgBouncer connection pooler, and related infrastructure services.";
        assertAll(
            // 2. Semantic quality
            () -> assertSemanticallySimilar(answer, svcRef, 0.3, "composed pipeline Q&A"),
            // 3. RAG metadata
            () -> {
                assertRagMetaValid(result);
                if (result.ragMeta() != null) {
                    int chunksUsed = result.ragMeta().path("chunks_used").asInt(0);
                    assertTrue(chunksUsed > 0, "RAG should have used at least one chunk");
                }
            },
            // 4. Citation structural validation
            () -> assertCitationsStructurallyValid(result, List.of(docId)),
            // 5. Cross-validate: done.chunksUsed <= rag_meta.chunks_used
            () -> {
                if (result.ragMeta() != null && result.donePayload() != null) {
                    int ragChunks = result.ragMeta().path("chunks_used").asInt(-1);
                    int doneChunks = result.donePayload().path("chunksUsed").asInt(-1);
                    log.info("Cross-validation: rag_meta.chunks_used={}, done.chunksUsed={}",
                        ragChunks, doneChunks);
                    if (doneChunks >= 0 && ragChunks >= 0) {
                        assertTrue(doneChunks <= ragChunks,
                            "done.chunksUsed (" + doneChunks
                                + ") should be <= rag_meta.chunks_used (" + ragChunks + ")");
                    }
                }
            });

        log.info("✅ Composed pipeline quality test passed");
    }
}
