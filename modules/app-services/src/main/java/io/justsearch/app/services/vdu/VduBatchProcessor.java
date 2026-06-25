/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.aibackend.backend.EngineCircuitBreaker;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramRequirements;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import io.justsearch.indexing.SchemaFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Batch processor for VDU files during offline time.
 *
 * <p>Called by OfflineCoordinator when there are pending VDU files.
 * Runs with LLM loaded (Online Mode).
 *
 * <p><b>Architecture:</b> Main process runs VDU (vision completion via LLM),
 * then updates index via gRPC to Worker (which owns IndexWriter).
 */
public class VduBatchProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(VduBatchProcessor.class);

    private final VduProcessor vduProcessor;
    private final GpuCapabilitiesService gpuCapabilitiesService;
    private final RemoteKnowledgeClient knowledgeClient;
    private final VduMetricCatalog catalog;
    private final VduCapabilityState vduCapabilityState;

    // Circuit breaker to prevent hammering dead LLM (5 failures, 1 minute recovery)
    private final EngineCircuitBreaker circuitBreaker = new EngineCircuitBreaker(5, Duration.ofMinutes(1));

    /**
     * Creates a VduBatchProcessor without telemetry (for backward compatibility).
     */
    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             RemoteKnowledgeClient knowledgeClient) {
        this(vduProcessor, gpuCapabilitiesService, knowledgeClient, VduMetricCatalog.noop(),
            new VduCapabilityState());
    }

    /**
     * Creates a VduBatchProcessor with observability catalog.
     *
     * <p>Tempdoc 374 alpha.27: VRAM probe routes through {@link GpuCapabilitiesService}
     * (NVML-first) instead of the legacy {@code VramDetector} (nvidia-smi only). Pre-fix,
     * cuda12 sandbox hosts where NVML works fine but nvidia-smi isn't on PATH silently
     * failed {@link #processPendingFiles}'s VRAM gate, disabling VDU even though the
     * GPU was healthy.
     *
     * @param vduProcessor processor for individual VDU files
     * @param gpuCapabilitiesService NVML-first capability snapshot service
     * @param knowledgeClient gRPC client for Worker communication
     * @param catalog VDU metric catalog
     */
    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             RemoteKnowledgeClient knowledgeClient,
                             VduMetricCatalog catalog) {
        this(vduProcessor, gpuCapabilitiesService, knowledgeClient, catalog, new VduCapabilityState());
    }

    public VduBatchProcessor(VduProcessor vduProcessor,
                             GpuCapabilitiesService gpuCapabilitiesService,
                             RemoteKnowledgeClient knowledgeClient,
                             VduMetricCatalog catalog,
                             VduCapabilityState vduCapabilityState) {
        this.vduProcessor = vduProcessor;
        this.gpuCapabilitiesService = gpuCapabilitiesService;
        this.knowledgeClient = knowledgeClient;
        this.catalog = catalog != null ? catalog : VduMetricCatalog.noop();
        this.vduCapabilityState =
            vduCapabilityState != null ? vduCapabilityState : new VduCapabilityState();
    }

    // Counter recording helpers
    private void recordCompleted() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.COMPLETED));
    }

    private void recordEmpty() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.EMPTY));
    }

    // Tempdoc 417 Phase 2e: drops the unbounded "reason" exception-message tag (cardinality bug).
    // Exception details continue to be logged via slf4j at the same callsite (see callers).
    // F10: signature simplified — `reason` parameter was dead.
    private void recordFailed() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.FAILED));
    }

    private void recordSkipped() {
        catalog.outcomeTotal.increment(VduOutcomeTags.of(VduOutcome.SKIPPED));
    }

    public int processPendingFiles() {
        int pendingCount = knowledgeClient.countPendingVdu();
        if (pendingCount == 0) {
            LOG.info("No pending VDU files");
            vduCapabilityState.clearAll();
            return 0;
        }

        // Tempdoc 374 alpha.27: NVML-first probe via GpuCapabilitiesService. Pre-fix
        // VramDetector.meetsVduRequirements() shelled out to nvidia-smi and returned
        // false on cuda12 sandbox hosts where NVML works fine — VDU was silently
        // disabled even though VRAM was 12 GB.
        Long vramBytes = gpuCapabilitiesService.snapshot().effective().totalVramBytes();
        if (!VramRequirements.meetsGgufRequirements(vramBytes)) {
            LOG.warn("VDU batch processing skipped: insufficient VRAM ({})",
                VramRequirements.describe(vramBytes));
            vduCapabilityState.block(VduCapabilityState.REASON_INSUFFICIENT_VRAM);
            return 0;
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_INSUFFICIENT_VRAM);

        if (!vduProcessor.hasVisionCapability()) {
            LOG.warn("VDU batch processing skipped: missing vision projector (mmproj)");
            vduCapabilityState.block(VduCapabilityState.REASON_MISSING_MMPROJ);
            return 0;
        }
        vduCapabilityState.clear(VduCapabilityState.REASON_MISSING_MMPROJ);

        List<String> pendingDocIds = knowledgeClient.queryPendingVduDocIds();
        if (pendingDocIds.isEmpty()) {
            LOG.info("No pending VDU doc IDs returned");
            vduCapabilityState.clearAll();
            return 0;
        }

        LOG.info("Processing {} pending VDU files", pendingDocIds.size());

        int processed = 0;
        int failed = 0;

        for (String docId : pendingDocIds) {
            // Circuit breaker check - fast-fail if LLM is repeatedly failing
            if (!circuitBreaker.isClosed()) {
                int remaining = pendingDocIds.size() - processed - failed;
                LOG.warn("VDU circuit breaker OPEN, skipping remaining {} docs (reason: {})",
                    remaining, circuitBreaker.tripReason());
                vduCapabilityState.block(VduCapabilityState.REASON_CIRCUIT_OPEN);
                break;
            }
            vduCapabilityState.clear(VduCapabilityState.REASON_CIRCUIT_OPEN);

            try {
                // Mark as PROCESSING with retry count increment (poison pill protection)
                int retryCount = knowledgeClient.markVduProcessing(docId, SchemaFields.VDU_MAX_RETRIES);
                if (retryCount < 0) {
                    LOG.warn("VDU skipped (max retries exceeded or error): {}", docId);
                    recordSkipped();
                    failed++;
                    continue;
                }

                LOG.debug("VDU processing attempt {}/{} for: {}",
                    retryCount, SchemaFields.VDU_MAX_RETRIES, docId);

                Path filePath = Path.of(docId);

                if (!Files.exists(filePath)) {
                    LOG.warn("VDU file no longer exists: {}", docId);
                    markVduFailed(docId, "File no longer exists");
                    recordFailed();
                    failed++;
                    continue;
                }

                VduProcessor.VduResult result = vduProcessor.process(filePath);
                circuitBreaker.recordSuccess();  // LLM call succeeded

                // P0.4: Use explicit VduUpdateOutcome to distinguish SUCCESS_TEXT vs SUCCESS_EMPTY vs FAILED.
                // This avoids misleading "COMPLETED but empty" states where content was never updated.
                String extractedText = result.extractedText();
                boolean hasText = extractedText != null && !extractedText.isBlank();

                io.justsearch.ipc.VduUpdateOutcome outcome;
                String enrichment;
                if (hasText) {
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT;
                    enrichment = result.enrichment();
                } else {
                    // VDU succeeded but produced no text (e.g., blank image, handwriting)
                    outcome = io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY;
                    enrichment = buildNoTextEnrichment(result.pageCount(), result.enrichment());
                    LOG.info("VDU produced no text for: {} (pageCount={})", docId, result.pageCount());
                }

                boolean updated = knowledgeClient.updateVduResult(
                    docId,
                    hasText ? extractedText : null,
                    outcome,
                    enrichment,
                    result.pageCount()
                );

                if (updated) {
                    if (hasText) {
                        recordCompleted();
                        processed++;
                        LOG.info("VDU completed ({}/{}): {}",
                            processed, pendingDocIds.size(), filePath.getFileName());
                    } else {
                        // SUCCESS_EMPTY: VDU ran successfully but no usable text; count separately
                        recordEmpty();
                        failed++;
                        LOG.info("VDU completed (no text) ({}/{}): {}",
                            failed, pendingDocIds.size(), filePath.getFileName());
                    }
                } else {
                    LOG.warn("VDU update failed for: {}", docId);
                    recordFailed();
                    failed++;
                }

            } catch (VduProcessor.VduException e) {
                LOG.error("VDU processing failed for: {}", docId, e);
                circuitBreaker.recordFailure(e);  // Track LLM failures
                markVduFailed(docId, e.getMessage());
                recordFailed();
                failed++;
            } catch (Exception e) {
                LOG.error("Unexpected error processing: {}", docId, e);
                circuitBreaker.recordFailure(e);  // Track LLM failures
                markVduFailed(docId, "Unexpected error: " + e.getMessage());
                recordFailed();
                failed++;
            }
        }

        LOG.info("VDU batch complete: {} processed, {} failed", processed, failed);
        return processed;
    }

    private void markVduFailed(String docId, String reason) {
        try {
            knowledgeClient.updateVduResult(
                docId,
                null,
                io.justsearch.ipc.VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED,
                buildErrorEnrichment(reason),
                0
            );
        } catch (Exception e) {
            LOG.warn("Failed to mark VDU failed for: {}", docId, e);
        }
    }

    /**
     * Builds a machine-readable JSON enrichment for "no text detected" VDU outcomes.
     *
     * @param pageCount the page count from VDU (may be 0 if unknown)
     * @param originalEnrichment the original enrichment from VduResult (may contain partial data)
     * @return JSON string with error code and metadata
     */
    private String buildNoTextEnrichment(int pageCount, String originalEnrichment) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.put("error", "no_text_detected");
            if (pageCount > 0) {
                node.put("pageCount", pageCount);
            }
            // Preserve any original enrichment data under "original" key
            if (originalEnrichment != null && !originalEnrichment.isBlank()) {
                try {
                    var originalNode = mapper.readTree(originalEnrichment);
                    node.set("original", originalNode);
                } catch (Exception ignored) {
                    // If original enrichment isn't valid JSON, store as string
                    node.put("originalRaw", originalEnrichment);
                }
            }
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Fallback to simple string if JSON building fails
            return "{\"error\":\"no_text_detected\",\"pageCount\":" + pageCount + "}";
        }
    }

    /**
     * Builds a machine-readable JSON enrichment for error conditions.
     *
     * @param reason the error reason
     * @return JSON string with error message
     */
    private String buildErrorEnrichment(String reason) {
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            var node = mapper.createObjectNode();
            node.put("error", reason != null ? reason : "unknown_error");
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // Fallback: escape quotes manually
            String safeReason = (reason != null ? reason : "unknown_error").replace("\"", "'");
            return "{\"error\":\"" + safeReason + "\"}";
        }
    }
}
