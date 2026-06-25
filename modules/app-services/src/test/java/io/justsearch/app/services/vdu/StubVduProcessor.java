package io.justsearch.app.services.vdu;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Stub VduProcessor for unit testing.
 *
 * <p>Returns configurable results or throws exceptions for testing failure paths.
 */
public class StubVduProcessor {

    private String defaultExtractedText = "Extracted text from document";
    private String defaultEnrichment = "{\"summary\": \"Test summary\", \"doc_type\": \"test\"}";
    private int defaultPageCount = 1;
    private boolean failAllProcessing = false;
    private String failureMessage = "Simulated VDU failure";
    private final Map<String, VduProcessor.VduResult> specificResults = new HashMap<>();
    private final Map<String, String> failingDocIds = new HashMap<>();
    private int processCallCount = 0;

    // ========== Simulated Methods ==========

    public VduProcessor.VduResult process(Path filePath) throws VduProcessor.VduException {
        processCallCount++;
        String docId = filePath.toString();

        // Check for specific failure
        if (failingDocIds.containsKey(docId)) {
            throw new VduProcessor.VduException(failingDocIds.get(docId), new RuntimeException("test"));
        }

        // Check for global failure
        if (failAllProcessing) {
            throw new VduProcessor.VduException(failureMessage, new RuntimeException("test"));
        }

        // Check for specific result
        if (specificResults.containsKey(docId)) {
            return specificResults.get(docId);
        }

        // Return default result
        return new VduProcessor.VduResult(defaultExtractedText, defaultEnrichment, defaultPageCount);
    }

    // ========== Configuration Methods ==========

    public StubVduProcessor withDefaultResult(String extractedText, String enrichment, int pageCount) {
        this.defaultExtractedText = extractedText;
        this.defaultEnrichment = enrichment;
        this.defaultPageCount = pageCount;
        return this;
    }

    public StubVduProcessor withSpecificResult(String docId, VduProcessor.VduResult result) {
        specificResults.put(docId, result);
        return this;
    }

    public StubVduProcessor withFailAllProcessing(boolean fail) {
        this.failAllProcessing = fail;
        return this;
    }

    public StubVduProcessor withFailureMessage(String message) {
        this.failureMessage = message;
        return this;
    }

    public StubVduProcessor withFailingDocId(String docId, String errorMessage) {
        failingDocIds.put(docId, errorMessage);
        return this;
    }

    // ========== Verification Methods ==========

    public int getProcessCallCount() {
        return processCallCount;
    }

    public void reset() {
        defaultExtractedText = "Extracted text from document";
        defaultEnrichment = "{\"summary\": \"Test summary\", \"doc_type\": \"test\"}";
        defaultPageCount = 1;
        failAllProcessing = false;
        failureMessage = "Simulated VDU failure";
        specificResults.clear();
        failingDocIds.clear();
        processCallCount = 0;
    }
}
