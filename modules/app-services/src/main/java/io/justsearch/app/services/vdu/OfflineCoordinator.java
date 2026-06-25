/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates offline processing: VDU first (if needed), then embeddings.
 *
 * <p>Called when user goes idle or manually triggers "Process Now".
 * Ensures VDU runs with LLM before switching to SLM for embeddings.
 *
 * <p><b>Architecture:</b> Queries Worker (via gRPC) for pending work counts.
 * Does not access Lucene directly - Worker owns the index.
 */
public class OfflineCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(OfflineCoordinator.class);

    // Tempdoc 518 Appendix F W4.2 — role-typed interface; off the concrete ILM.
    private final OnlineAiLifecycleControl inferenceManager;
    private final VduBatchProcessor vduBatchProcessor;
    private final RemoteKnowledgeClient knowledgeClient;
    private final VduCapabilityState vduCapabilityState;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    public OfflineCoordinator(OnlineAiLifecycleControl inferenceManager,
                              VduBatchProcessor vduBatchProcessor,
                              RemoteKnowledgeClient knowledgeClient) {
        this(inferenceManager, vduBatchProcessor, knowledgeClient, new VduCapabilityState());
    }

    public OfflineCoordinator(OnlineAiLifecycleControl inferenceManager,
                              VduBatchProcessor vduBatchProcessor,
                              RemoteKnowledgeClient knowledgeClient,
                              VduCapabilityState vduCapabilityState) {
        this.inferenceManager = inferenceManager;
        this.vduBatchProcessor = vduBatchProcessor;
        this.knowledgeClient = knowledgeClient;
        this.vduCapabilityState =
            vduCapabilityState != null ? vduCapabilityState : new VduCapabilityState();
    }

    /**
     * Start offline processing. Sequences VDU then Embeddings.
     *
     * <p>Flow:
     * <ol>
     *   <li>Query pending VDU/embedding counts via gRPC</li>
     *   <li>If VDU pending: ensure LLM loaded, process VDU batch</li>
     *   <li>Switch to Indexing Mode (SLM) for embeddings</li>
     * </ol>
     *
     * <p>Thread-safe: only one processing run at a time.
     */
    public void startOfflineProcessing() {
        if (!processing.compareAndSet(false, true)) {
            LOG.info("Offline processing already in progress, skipping");
            return;
        }

        try {
            LOG.info("Starting offline processing");

            // Recover any documents stuck in PROCESSING state from previous crash
            int recovered = knowledgeClient.recoverVduProcessing();
            if (recovered > 0) {
                LOG.info("Recovered {} documents stuck in PROCESSING state", recovered);
            }

            int pendingVdu = knowledgeClient.countPendingVdu();
            int pendingEmbeddings = knowledgeClient.countPendingEmbeddings();

            LOG.info("Pending work: {} VDU files, {} embeddings", pendingVdu, pendingEmbeddings);

            // Phase A: VDU Processing (requires LLM in Online Mode)
            if (pendingVdu > 0) {
                LOG.info("Phase A: Processing {} pending VDU files", pendingVdu);
                processVduPhase();
            } else {
                vduCapabilityState.clearAll();
            }

            // Phase B: Embedding Processing (requires SLM in Indexing Mode)
            // Re-query count - VDU sets embedding_status to PENDING for re-embedding
            pendingEmbeddings = knowledgeClient.countPendingEmbeddings();
            if (pendingEmbeddings > 0) {
                LOG.info("Phase B: Switching to Indexing Mode for {} pending embeddings",
                    pendingEmbeddings);
                processEmbeddingPhase();
            } else {
                LOG.info("No pending embeddings, staying in current mode");
            }

            LOG.info("Offline processing complete");
        } finally {
            processing.set(false);
        }
    }

    private void processVduPhase() {
        // Ensure LLM is loaded (Online Mode)
        if (!inferenceManager.isOnline()) {
            try {
                LOG.info("Switching to Online Mode for VDU processing");
                inferenceManager.switchToOnlineMode();
            } catch (ModeTransitionException e) {
                LOG.error("Failed to switch to Online Mode for VDU", e);
                vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
                return;  // Skip VDU phase
            }
        }

        if (inferenceManager.isOnline()) {
            vduCapabilityState.clear(VduCapabilityState.REASON_AI_OFFLINE);
            int processed = vduBatchProcessor.processPendingFiles();
            LOG.info("VDU phase complete: {} files processed", processed);
        } else {
            LOG.warn("Skipping VDU phase: LLM not available");
            vduCapabilityState.block(VduCapabilityState.REASON_AI_OFFLINE);
        }
    }

    private void processEmbeddingPhase() {
        try {
            inferenceManager.switchToIndexingMode();
            // Worker will automatically process pending embeddings
            // because isMainGpuActive() will return false
            LOG.info("Indexing Mode active, Worker will process embeddings");
        } catch (ModeTransitionException e) {
            LOG.error("Failed to switch to Indexing Mode", e);
        }
    }

    /**
     * Check if there is any pending offline work.
     *
     * @return true if VDU or embedding work is pending
     */
    public boolean hasPendingWork() {
        return knowledgeClient.countPendingVdu() > 0
            || knowledgeClient.countPendingEmbeddings() > 0;
    }

    /**
     * Get count of pending VDU files.
     */
    public int getPendingVduCount() {
        return knowledgeClient.countPendingVdu();
    }

    /**
     * Get count of pending embeddings.
     */
    public int getPendingEmbeddingCount() {
        return knowledgeClient.countPendingEmbeddings();
    }

    /**
     * Check if offline processing is currently running.
     */
    public boolean isProcessing() {
        return processing.get();
    }

    public VduCapabilityState vduCapabilityState() {
        return vduCapabilityState;
    }
}
