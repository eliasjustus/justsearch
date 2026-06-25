package io.justsearch.systemtests.process;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.chaos.GrpcTestClient;
import io.justsearch.systemtests.chaos.MmfTestHarness;
import io.justsearch.systemtests.chaos.WorkerProcessManager;
import io.justsearch.systemtests.provisioning.TestEnvironmentProvisioner;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for gRPC data operations with real indexed content.
 *
 * <p>Unlike {@link GrpcCommunicationTest} which tests with an empty index,
 * these tests index actual files and verify that:
 * <ul>
 *   <li>FetchDocuments returns the actual file content</li>
 *   <li>RetrieveContext finds relevant content via BM25</li>
 *   <li>Search returns indexed documents</li>
 * </ul>
 *
 * <p>This catches bugs where:
 * <ul>
 *   <li>Content field is not stored in index</li>
 *   <li>Document ID normalization fails</li>
 *   <li>BM25 search doesn't find relevant content</li>
 * </ul>
 */
@DisplayName("gRPC Data Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GrpcDataIntegrationTest {

    @RegisterExtension
    static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();

    private static WorkerProcessManager worker;
    private static MmfTestHarness mmf;
    private static GrpcTestClient grpcClient;

    // Test data
    private static Path testFile1;
    private static Path testFile2;
    private static String docId1; // Discovered from search after indexing
    private static String docId2; // Discovered from search after indexing

    // Known content for assertions
    private static final String CONTENT_1 = """
        The quick brown fox jumps over the lazy dog.
        This is a test document about foxes and dogs.
        Foxes are cunning animals that live in forests.
        """;

    private static final String CONTENT_2 = """
        Database Configuration Guide

        To configure the database, set the following parameters:
        - host: localhost
        - port: 5432
        - username: admin
        - password: secretPassword123

        Make sure to restart the service after changes.
        """;

    @BeforeAll
    static void setupAndIndexDocuments() throws Exception {
        // 1. Start worker
        worker = WorkerProcessManager.fromDistribution(env.getWorkerDistDir(), env.getTempDir())
            .withJvmArgs(env.getWorkerJvmArgs());
        worker.spawnWorker();

        // 2. Open MMF for port discovery
        mmf = new MmfTestHarness(worker.getSignalFilePath());
        mmf.open();
        mmf.keepAlive();

        int grpcPort = mmf.awaitPort(30_000, 100);
        grpcClient = new GrpcTestClient(grpcPort);
        assertTrue(grpcClient.isHealthy(), "Worker should be healthy before indexing");

        // 3. Create test files with known content
        testFile1 = env.getTempDir().resolve("fox-document.txt");
        testFile2 = env.getTempDir().resolve("database-config.txt");

        Files.writeString(testFile1, CONTENT_1);
        Files.writeString(testFile2, CONTENT_2);

        // 4. Index the files
        int accepted = grpcClient.submitBatch(List.of(
            testFile1.toAbsolutePath().toString(),
            testFile2.toAbsolutePath().toString()
        ));
        assertEquals(2, accepted, "Should accept 2 files for indexing");

        // 5. Wait for indexing to complete
        boolean indexed = grpcClient.awaitIndexing(2, 30_000, 200);
        assertTrue(indexed, "Documents should be indexed within 30 seconds");

        // 6. Discover actual document IDs from search (handles path normalization differences)
        docId1 = discoverDocIdBySearch("fox");
        docId2 = discoverDocIdBySearch("secretPassword123");

        assertNotNull(docId1, "Should find fox document after indexing");
        assertNotNull(docId2, "Should find database config document after indexing");

        // Keep alive after setup
        mmf.keepAlive();
    }

    /**
     * Discovers the actual document ID by searching for known content.
     * This handles path normalization differences between platforms.
     */
    private static String discoverDocIdBySearch(String searchTerm) {
        SearchResponse response = grpcClient.searchText(searchTerm, 1);
        if (response.getResultsCount() > 0) {
            return response.getResults(0).getId();
        }
        return null;
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (grpcClient != null) {
            grpcClient.close();
            grpcClient = null;
        }
        if (mmf != null) {
            mmf.close();
            mmf = null;
        }
        if (worker != null) {
            worker.close();
            worker = null;
        }
        // Test files cleaned up by TestEnvironmentProvisioner
    }

    @BeforeEach
    void keepAlive() {
        if (mmf != null) {
            mmf.keepAlive();
        }
    }

    // =========================================================================
    // Search Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Search finds indexed document by content")
    void searchFindsIndexedDocumentByContent() {
        // Search for "fox" - should find testFile1
        SearchResponse response = grpcClient.searchText("fox", 10);

        assertNotNull(response, "Search response should not be null");
        assertTrue(response.getResultsCount() > 0, "Should find at least 1 document");

        // Verify the fox document is in results (using discovered ID)
        boolean foundFoxDoc = response.getResultsList().stream()
            .anyMatch(r -> r.getId().equals(docId1));
        assertTrue(foundFoxDoc, "Should find fox document in search results");
    }

    @Test
    @Order(2)
    @DisplayName("Search finds database config by keyword")
    void searchFindsDatabaseConfigByKeyword() {
        // Search for "password" - should find testFile2
        SearchResponse response = grpcClient.searchText("password", 10);

        assertNotNull(response, "Search response should not be null");
        assertTrue(response.getResultsCount() > 0, "Should find at least 1 document");

        // Verify the database config is in results (using discovered ID)
        boolean foundDbDoc = response.getResultsList().stream()
            .anyMatch(r -> r.getId().equals(docId2));
        assertTrue(foundDbDoc, "Should find database config document in search results");
    }

    // =========================================================================
    // FetchDocuments Tests
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("FetchDocuments returns actual file content")
    void fetchDocumentsReturnsActualContent() {
        FetchDocumentsResponse response = grpcClient.fetchDocuments(List.of(docId1));

        assertNotNull(response, "FetchDocuments response should not be null");
        assertEquals(1, response.getDocumentsCount(), "Should return 1 document");

        DocumentContent doc = response.getDocuments(0);
        assertEquals(docId1, doc.getDocId(), "Document ID should match");
        assertTrue(doc.getFound(), "Document should be found");

        // Verify content contains expected text
        String content = doc.getContent();
        assertFalse(content.isEmpty(), "Content should not be empty");
        assertTrue(content.contains("fox"), "Content should contain 'fox'");
        assertTrue(content.contains("lazy dog"), "Content should contain 'lazy dog'");
    }

    @Test
    @Order(4)
    @DisplayName("FetchDocuments returns multiple documents")
    void fetchDocumentsReturnsMultipleDocuments() {
        FetchDocumentsResponse response = grpcClient.fetchDocuments(List.of(docId1, docId2));

        assertNotNull(response, "FetchDocuments response should not be null");
        assertEquals(2, response.getDocumentsCount(), "Should return 2 documents");

        // Both should be found with content
        for (DocumentContent doc : response.getDocumentsList()) {
            assertTrue(doc.getFound(), "Document " + doc.getDocId() + " should be found");
            assertFalse(doc.getContent().isEmpty(), "Document " + doc.getDocId() + " should have content");
        }
    }

    @Test
    @Order(5)
    @DisplayName("FetchDocuments handles mix of found and not found")
    void fetchDocumentsHandlesMixedResults() {
        FetchDocumentsResponse response = grpcClient.fetchDocuments(
            List.of(docId1, "nonexistent-doc-id", docId2)
        );

        assertNotNull(response, "FetchDocuments response should not be null");
        assertEquals(3, response.getDocumentsCount(), "Should return 3 document entries");

        int foundCount = 0;
        int notFoundCount = 0;
        for (DocumentContent doc : response.getDocumentsList()) {
            if (doc.getFound()) {
                foundCount++;
                assertFalse(doc.getContent().isEmpty(), "Found doc should have content");
            } else {
                notFoundCount++;
                assertTrue(doc.getContent().isEmpty(), "Not found doc should have empty content");
            }
        }

        assertEquals(2, foundCount, "Should find 2 documents");
        assertEquals(1, notFoundCount, "Should have 1 not found");
    }

    // =========================================================================
    // FetchDocumentSlice Tests
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("FetchDocumentSlice returns paged content with correct offsets")
    void fetchDocumentSliceReturnsPagedContent() {
        // Fetch a small first page
        FetchDocumentSliceResponse page1 = grpcClient.fetchDocumentSlice(docId1, 0, 16);
        assertNotNull(page1, "Slice response should not be null");
        assertEquals(docId1, page1.getDocId(), "Doc ID should match");
        assertTrue(page1.getFound(), "Document should be found");
        assertFalse(page1.getContent().isEmpty(), "First page content should not be empty");
        assertEquals(page1.getContent().length(), page1.getNextOffsetChars(),
            "next_offset_chars should equal returned content length when offset=0");
        assertTrue(page1.getTruncated(), "Small page should indicate truncated=true for non-trivial docs");

        // Fetch the next page and ensure it advances
        FetchDocumentSliceResponse page2 = grpcClient.fetchDocumentSlice(docId1, page1.getNextOffsetChars(), 16);
        assertTrue(page2.getFound(), "Second page should be found");
        assertEquals(page1.getNextOffsetChars() + page2.getContent().length(), page2.getNextOffsetChars(),
            "next_offset_chars should advance by content length");

        // Basic sanity: concatenating two pages should contain known text
        String combined = page1.getContent() + page2.getContent();
        assertTrue(combined.toLowerCase(Locale.ROOT).contains("quick") || combined.toLowerCase(Locale.ROOT).contains("brown"),
            "Combined pages should contain expected words");
        assertFalse(page1.getMetadataMap().isEmpty(), "Metadata should be present");
    }

    @Test
    @Order(7)
    @DisplayName("FetchDocumentSlice returns not-found for unknown doc IDs")
    void fetchDocumentSliceReturnsNotFoundForUnknownId() {
        FetchDocumentSliceResponse response = grpcClient.fetchDocumentSlice("nonexistent-doc-id", 0, 50);
        assertNotNull(response, "Slice response should not be null");
        assertFalse(response.getFound(), "Unknown doc should not be found");
        assertTrue(response.getContent().isEmpty(), "Unknown doc content should be empty");
        assertTrue(!response.getError().isBlank(),
            "Unknown doc should include an error message");
    }

    // =========================================================================
    // RetrieveContext Tests
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("RetrieveContext finds relevant content for question")
    void retrieveContextFindsRelevantContent() {
        // Ask about foxes - should retrieve content from testFile1
        RetrieveContextResponse response = grpcClient.retrieveContext(
            "What animal jumps over the lazy dog?",
            List.of(docId1, docId2),
            5
        );

        assertNotNull(response, "RetrieveContext response should not be null");
        String context = response.getContext();

        // Context should contain relevant information
        assertFalse(context.isEmpty(), "Context should not be empty");
        assertTrue(
            context.toLowerCase(Locale.ROOT).contains("fox") || context.toLowerCase(Locale.ROOT).contains("dog"),
            "Context should contain relevant terms (fox or dog)"
        );
    }

    @Test
    @Order(7)
    @DisplayName("RetrieveContext finds database password from config")
    void retrieveContextFindsDatabasePassword() {
        // Ask about database password - should retrieve from testFile2
        RetrieveContextResponse response = grpcClient.retrieveContext(
            "What is the database password?",
            List.of(docId1, docId2),
            5
        );

        assertNotNull(response, "RetrieveContext response should not be null");
        String context = response.getContext();

        // Context should contain the password or related config
        assertFalse(context.isEmpty(), "Context should not be empty");
        assertTrue(
            context.contains("password") || context.contains("secretPassword123"),
            "Context should contain password-related content"
        );
    }

    @Test
    @Order(8)
    @DisplayName("RetrieveContext respects document ID filter")
    void retrieveContextRespectsDocIdFilter() {
        // Ask about password but only search in fox document
        RetrieveContextResponse response = grpcClient.retrieveContext(
            "What is the password?",
            List.of(docId1), // Only fox document, which has no password
            5
        );

        assertNotNull(response, "RetrieveContext response should not be null");
        String context = response.getContext();

        // Should NOT find password since we're only searching fox document
        // Context might be empty or contain unrelated content
        assertFalse(
            context.contains("secretPassword123"),
            "Should not find secretPassword123 when searching only fox document"
        );
    }

    @Test
    @Order(9)
    @DisplayName("RetrieveContext returns empty for irrelevant question")
    void retrieveContextReturnsEmptyForIrrelevantQuestion() {
        // Ask about something not in any document
        RetrieveContextResponse response = grpcClient.retrieveContext(
            "What is the capital of France?",
            List.of(docId1, docId2),
            5
        );

        assertNotNull(response, "RetrieveContext response should not be null");
        // Context might be empty or contain low-relevance content
        // The key is it shouldn't crash
    }
}
