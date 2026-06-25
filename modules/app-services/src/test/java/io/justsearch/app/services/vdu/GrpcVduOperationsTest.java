package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * gRPC integration tests for VDU operations.
 *
 * <p>Tests the IngestService VDU-related RPCs:
 * <ul>
 *   <li>QueryPendingVdu - Query documents needing VDU processing</li>
 *   <li>MarkVduProcessing - Mark document as PROCESSING with retry count</li>
 *   <li>UpdateVduResult - Update document with VDU results</li>
 *   <li>RecoverVduProcessing - Reset stuck PROCESSING documents</li>
 * </ul>
 *
 * <p>Uses in-process gRPC server with stub service implementation.
 * No actual Lucene index - tests only the gRPC contract.
 */
@DisplayName("gRPC VDU Operations")
class GrpcVduOperationsTest {

    private String serverName;
    private Server server;
    private ManagedChannel channel;
    private IngestServiceGrpc.IngestServiceBlockingStub stub;
    private StubIngestService stubService;

    @BeforeEach
    void setUp() throws IOException {
        serverName = InProcessServerBuilder.generateName();
        stubService = new StubIngestService();
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(stubService)
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();
        stub = IngestServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("QueryPendingVdu")
    class QueryPendingVduTests {

        @Test
        @DisplayName("returns pending doc IDs")
        void returnsPendingDocIds() {
            stubService.withPendingVduDocIds(List.of("doc1", "doc2", "doc3"));
            stubService.withPendingVduTotalCount(3);

            QueryPendingVduResponse response = stub.queryPendingVdu(
                QueryPendingVduRequest.newBuilder()
                    .setLimit(100)
                    .build());

            assertEquals(3, response.getDocIdsCount());
            assertEquals(List.of("doc1", "doc2", "doc3"), response.getDocIdsList());
            assertEquals(3, response.getTotalCount());
        }

        @Test
        @DisplayName("respects limit parameter")
        void respectsLimit() {
            stubService.withPendingVduDocIds(List.of("doc1", "doc2"));
            stubService.withPendingVduTotalCount(10);  // Total is more than returned

            QueryPendingVduResponse response = stub.queryPendingVdu(
                QueryPendingVduRequest.newBuilder()
                    .setLimit(2)
                    .build());

            assertEquals(2, response.getDocIdsCount());
            assertEquals(10, response.getTotalCount());
        }

        @Test
        @DisplayName("returns empty list when no pending documents")
        void returnsEmptyWhenNoPending() {
            stubService.withPendingVduDocIds(List.of());
            stubService.withPendingVduTotalCount(0);

            QueryPendingVduResponse response = stub.queryPendingVdu(
                QueryPendingVduRequest.newBuilder().build());

            assertEquals(0, response.getDocIdsCount());
            assertEquals(0, response.getTotalCount());
        }
    }

    @Nested
    @DisplayName("MarkVduProcessing")
    class MarkVduProcessingTests {

        @Test
        @DisplayName("returns success with retry count")
        void returnsSuccessWithRetryCount() {
            stubService.withMarkVduProcessingResponse(true, 1, "");

            MarkVduProcessingResponse response = stub.markVduProcessing(
                MarkVduProcessingRequest.newBuilder()
                    .setDocId("doc1")
                    .setMaxRetries(3)
                    .build());

            assertTrue(response.getSuccess());
            assertEquals(1, response.getRetryCount());
            assertEquals("", response.getError());
        }

        @Test
        @DisplayName("returns -1 retry count when max retries exceeded")
        void returnsNegativeWhenMaxRetriesExceeded() {
            stubService.withMarkVduProcessingResponse(false, -1, "Max retries exceeded");

            MarkVduProcessingResponse response = stub.markVduProcessing(
                MarkVduProcessingRequest.newBuilder()
                    .setDocId("poison-doc")
                    .setMaxRetries(3)
                    .build());

            assertFalse(response.getSuccess());
            assertEquals(-1, response.getRetryCount());
            assertTrue(response.getError().contains("Max retries"));
        }

        @Test
        @DisplayName("records the doc ID that was marked")
        void recordsMarkedDocId() {
            stubService.withMarkVduProcessingResponse(true, 1, "");

            stub.markVduProcessing(
                MarkVduProcessingRequest.newBuilder()
                    .setDocId("test-doc-123")
                    .setMaxRetries(3)
                    .build());

            assertEquals("test-doc-123", stubService.getLastMarkedDocId());
        }
    }

    @Nested
    @DisplayName("UpdateVduResult")
    class UpdateVduResultTests {

        @Test
        @DisplayName("returns success on valid update")
        void returnsSuccessOnValidUpdate() {
            stubService.withUpdateVduResultResponse(true, "");

            UpdateVduResultResponse response = stub.updateVduResult(
                UpdateVduResultRequest.newBuilder()
                    .setDocId("doc1")
                    .setExtractedContent("Extracted text from image")
                    .setVduStatus("COMPLETED")
                    .setVduEnrichment("{\"summary\": \"test\"}")
                    .setPageCount(2)
                    .build());

            assertTrue(response.getSuccess());
            assertEquals("", response.getError());
        }

        @Test
        @DisplayName("returns failure when document not found")
        void returnsFailureWhenNotFound() {
            stubService.withUpdateVduResultResponse(false, "Document not found");

            UpdateVduResultResponse response = stub.updateVduResult(
                UpdateVduResultRequest.newBuilder()
                    .setDocId("nonexistent")
                    .setExtractedContent("")
                    .setVduStatus("FAILED")
                    .build());

            assertFalse(response.getSuccess());
            assertEquals("Document not found", response.getError());
        }

        @Test
        @DisplayName("records update details for verification")
        void recordsUpdateDetails() {
            stubService.withUpdateVduResultResponse(true, "");

            stub.updateVduResult(
                UpdateVduResultRequest.newBuilder()
                    .setDocId("test-doc")
                    .setExtractedContent("OCR text here")
                    .setVduStatus("COMPLETED")
                    .setVduEnrichment("{\"doc_type\": \"invoice\"}")
                    .setPageCount(5)
                    .build());

            var lastUpdate = stubService.getLastVduUpdate();
            assertNotNull(lastUpdate);
            assertEquals("test-doc", lastUpdate.docId());
            assertEquals("OCR text here", lastUpdate.extractedContent());
            assertEquals("COMPLETED", lastUpdate.vduStatus());
            assertEquals("{\"doc_type\": \"invoice\"}", lastUpdate.vduEnrichment());
            assertEquals(5, lastUpdate.pageCount());
        }
    }

    @Nested
    @DisplayName("RecoverVduProcessing")
    class RecoverVduProcessingTests {

        @Test
        @DisplayName("returns count of recovered documents")
        void returnsRecoveredCount() {
            stubService.withRecoverVduProcessingCount(7);

            RecoverVduProcessingResponse response = stub.recoverVduProcessing(
                RecoverVduProcessingRequest.getDefaultInstance());

            assertEquals(7, response.getRecoveredCount());
        }

        @Test
        @DisplayName("returns 0 when no documents to recover")
        void returnsZeroWhenNothingToRecover() {
            stubService.withRecoverVduProcessingCount(0);

            RecoverVduProcessingResponse response = stub.recoverVduProcessing(
                RecoverVduProcessingRequest.getDefaultInstance());

            assertEquals(0, response.getRecoveredCount());
        }

        @Test
        @DisplayName("records that recovery was called")
        void recordsRecoveryCalled() {
            stubService.withRecoverVduProcessingCount(3);

            stub.recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance());

            assertTrue(stubService.wasRecoverVduProcessingCalled());
        }
    }

    // ========== Stub Service Implementation ==========

    /**
     * Stub IngestService for testing VDU gRPC operations.
     */
    static class StubIngestService extends IngestServiceGrpc.IngestServiceImplBase {

        // QueryPendingVdu config
        private List<String> pendingVduDocIds = new ArrayList<>();
        private int pendingVduTotalCount = 0;

        // MarkVduProcessing config
        private boolean markVduSuccess = true;
        private int markVduRetryCount = 1;
        private String markVduError = "";
        private String lastMarkedDocId = null;

        // UpdateVduResult config
        private boolean updateVduSuccess = true;
        private String updateVduError = "";
        private VduUpdateRecord lastVduUpdate = null;

        // RecoverVduProcessing config
        private int recoverCount = 0;
        private boolean recoverCalled = false;

        // ========== RPC Implementations ==========

        @Override
        public void queryPendingVdu(QueryPendingVduRequest request,
                                    StreamObserver<QueryPendingVduResponse> responseObserver) {
            responseObserver.onNext(
                QueryPendingVduResponse.newBuilder()
                    .addAllDocIds(pendingVduDocIds)
                    .setTotalCount(pendingVduTotalCount)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void markVduProcessing(MarkVduProcessingRequest request,
                                      StreamObserver<MarkVduProcessingResponse> responseObserver) {
            lastMarkedDocId = request.getDocId();
            responseObserver.onNext(
                MarkVduProcessingResponse.newBuilder()
                    .setSuccess(markVduSuccess)
                    .setRetryCount(markVduRetryCount)
                    .setError(markVduError)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void updateVduResult(UpdateVduResultRequest request,
                                    StreamObserver<UpdateVduResultResponse> responseObserver) {
            lastVduUpdate = new VduUpdateRecord(
                request.getDocId(),
                request.getExtractedContent(),
                request.getVduStatus(),
                request.getVduEnrichment(),
                request.getPageCount()
            );
            responseObserver.onNext(
                UpdateVduResultResponse.newBuilder()
                    .setSuccess(updateVduSuccess)
                    .setError(updateVduError)
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void recoverVduProcessing(RecoverVduProcessingRequest request,
                                         StreamObserver<RecoverVduProcessingResponse> responseObserver) {
            recoverCalled = true;
            responseObserver.onNext(
                RecoverVduProcessingResponse.newBuilder()
                    .setRecoveredCount(recoverCount)
                    .build());
            responseObserver.onCompleted();
        }

        // ========== Configuration Methods ==========

        StubIngestService withPendingVduDocIds(List<String> docIds) {
            this.pendingVduDocIds = new ArrayList<>(docIds);
            return this;
        }

        StubIngestService withPendingVduTotalCount(int count) {
            this.pendingVduTotalCount = count;
            return this;
        }

        StubIngestService withMarkVduProcessingResponse(boolean success, int retryCount, String error) {
            this.markVduSuccess = success;
            this.markVduRetryCount = retryCount;
            this.markVduError = error;
            return this;
        }

        StubIngestService withUpdateVduResultResponse(boolean success, String error) {
            this.updateVduSuccess = success;
            this.updateVduError = error;
            return this;
        }

        StubIngestService withRecoverVduProcessingCount(int count) {
            this.recoverCount = count;
            return this;
        }

        // ========== Verification Methods ==========

        String getLastMarkedDocId() {
            return lastMarkedDocId;
        }

        VduUpdateRecord getLastVduUpdate() {
            return lastVduUpdate;
        }

        boolean wasRecoverVduProcessingCalled() {
            return recoverCalled;
        }

        /**
         * Record of VDU update request for verification.
         */
        record VduUpdateRecord(
            String docId,
            String extractedContent,
            String vduStatus,
            String vduEnrichment,
            int pageCount
        ) {}
    }
}
