package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Content Extraction Tests")
class ContentExtractionTest {

    private static Path testDocuments;
    private ContentExtractor extractor;

    @BeforeAll
    static void locateTestDocuments() {
        // Try multiple locations to be robust
        Path[] candidates = {
            Path.of("src/integrationTest/resources/test-documents"),
            Path.of("modules/system-tests/src/integrationTest/resources/test-documents"),
            Path.of("../modules/system-tests/src/integrationTest/resources/test-documents")
        };

        for (Path p : candidates) {
            if (Files.isDirectory(p)) {
                testDocuments = p;
                break;
            }
        }

        if (testDocuments == null) {
             // Fallback to finding via classpath if possible, or just fail
             // For now fail as per plan, but with clear message
             throw new IllegalStateException("Test documents not found in candidates: src/integrationTest/resources/test-documents");
        }
    }

    @BeforeEach
    void createExtractor() {
        extractor = new ContentExtractor();
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice.txt", "README.md", "Optional.java", "recipes.json", "docker-compose.yml"})
    @DisplayName("Extracts content from file")
    void extractsContentFromFile(String filename) throws Exception {
        Path file = testDocuments.resolve(filename);
        assertTrue(Files.exists(file), "Test file should exist: " + filename);

        // extract() returns ExtractionResult, not String
        ExtractionResult result = extractor.extract(file);

        assertNotNull(result, "Extraction result should not be null");
        assertNotNull(result.content(), "Extracted content should not be null");
        assertFalse(result.content().isBlank(), "Extracted content should not be blank");
        assertTrue(result.content().length() > 10, "Content should have substance");
        assertNotNull(result.mimeType(), "MIME type should be detected");
    }

    @Test
    @DisplayName("alice.txt contains expected text")
    void aliceContainsExpectedText() throws Exception {
        ExtractionResult result = extractor.extract(testDocuments.resolve("alice.txt"));
        String content = result.content();

        assertTrue(content.contains("Alice"), "Should contain 'Alice'");
        assertTrue(content.contains("Rabbit"), "Should contain 'Rabbit'");
        assertTrue(result.mimeType().startsWith("text/"), "Should be text mime type");
    }

    @Test
    @DisplayName("Optional.java contains Java keywords")
    void optionalContainsJavaKeywords() throws Exception {
        ExtractionResult result = extractor.extract(testDocuments.resolve("Optional.java"));
        String content = result.content();

        assertTrue(content.contains("class") || content.contains("public"),
            "Should contain Java keywords");
        assertTrue(content.contains("Optional"), "Should contain class name");
    }

    @Test
    @DisplayName("recipes.json extracts recipe names")
    void recipesContainsRecipeData() throws Exception {
        ExtractionResult result = extractor.extract(testDocuments.resolve("recipes.json"));
        String content = result.content();

        assertTrue(content.contains("Margherita") || content.contains("Pizza") || content.contains("recipe"),
            "Should contain recipe-related content");
        // JSON should be detected as application/json
        assertTrue(result.mimeType().contains("json"), "Should detect JSON mime type");
    }

    @Test
    @DisplayName("docker-compose.yml extracts YAML content")
    void dockerComposeExtractsYaml() throws Exception {
        ExtractionResult result = extractor.extract(testDocuments.resolve("docker-compose.yml"));
        String content = result.content();

        assertTrue(content.contains("services") || content.contains("postgres"),
            "Should contain Docker Compose content");
    }
}
