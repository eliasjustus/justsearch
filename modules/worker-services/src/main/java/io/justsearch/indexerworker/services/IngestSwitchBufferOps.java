/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SWITCHING buffer coordination for {@link GrpcIngestService}.
 *
 * <p>During migration cutover (SWITCHING state), mutation requests are durably buffered via the
 * SQLite switch-buffer instead of being applied directly to the index. This class encapsulates
 * all buffer-writing logic, the SWITCHING state guard, and related constants. Extracted to reduce
 * the size of the service class.
 */
final class IngestSwitchBufferOps {
  private static final Logger log = LoggerFactory.getLogger(IngestSwitchBufferOps.class);

  // ==================== Switch-buffer operation constants ====================

  static final String SWITCHBUF_OP_UPSERT = "UPSERT";
  static final String SWITCHBUF_OP_DELETE = "DELETE";
  static final String SWITCHBUF_OP_DELETE_PREFIX = "DELETE_PREFIX";
  static final String SWITCHBUF_OP_SYNC_ROOT = "SYNC_ROOT";
  static final String SWITCHBUF_OP_PRUNE_PREFIX = "PRUNE_PREFIX";
  static final String SWITCHBUF_OP_VDU_UPDATE = "VDU_UPDATE";
  static final String SWITCHBUF_OP_VDU_MARK_FAILED = "VDU_MARK_FAILED";
  static final String SWITCHBUF_OP_VDU_MARK_PROCESSING = "VDU_MARK_PROCESSING";
  static final String SWITCHBUF_OP_VDU_RECOVER_PROCESSING = "VDU_RECOVER_PROCESSING";

  private static final String VDU_MAX_RETRIES_EXCEEDED_ERROR = "Max retries exceeded";

  private final JobQueue jobQueue;
  private final IndexGenerationManager indexGenerationManager;
  private final OperationalMetrics metrics;

  IngestSwitchBufferOps(
      JobQueue jobQueue, IndexGenerationManager indexGenerationManager, OperationalMetrics metrics) {
    this.jobQueue = jobQueue;
    this.indexGenerationManager = indexGenerationManager;
    this.metrics = metrics;
  }

  // ==================== SWITCHING state guard ====================

  boolean isSwitching() {
    if (indexGenerationManager == null) {
      return false;
    }
    try {
      IndexGenerationManager.State s = indexGenerationManager.readStateBestEffort();
      if (s == null || s.migration_state() == null) {
        return false;
      }
      return "SWITCHING".equalsIgnoreCase(s.migration_state());
    } catch (Exception ignored) {
      return false;
    }
  }

  // ==================== Buffer infrastructure ====================

  @FunctionalInterface
  interface SwitchingBufferOp {
    boolean run(SwitchBufferCapableQueue sbq) throws Exception;
  }

  void bufferDuringSwitchingOrReplyUnavailable(
      String context, StreamObserver<?> responseObserver, SwitchingBufferOp bufferOp) {
    if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
      try {
        if (!bufferOp.run(sbq)) {
          return;
        }
        return;
      } catch (Exception e) {
        log.warn("Failed to buffer {} during SWITCHING", context, e);
      }
    }
    replySwitchingUnavailable(responseObserver);
  }

  boolean putSwitchBufferOrReplyUnavailable(
      SwitchBufferCapableQueue sbq,
      String key,
      String operation,
      String payload,
      String context,
      StreamObserver<?> responseObserver) {
    boolean buffered = sbq.putSwitchBuffer(key, operation, payload);
    if (buffered) {
      return true;
    }
    log.error("Switch buffer write failed for {} during SWITCHING", context);
    replySwitchBufferUnavailable(responseObserver);
    return false;
  }

  static void replySwitchBufferUnavailable(StreamObserver<?> responseObserver) {
    responseObserver.onError(
        io.grpc.Status.UNAVAILABLE
            .withDescription("Switch buffer write failed during migration; retry shortly")
            .asException());
  }

  static void replySwitchingUnavailable(StreamObserver<?> responseObserver) {
    responseObserver.onError(
        io.grpc.Status.UNAVAILABLE
            .withDescription("Migration is switching; retry shortly")
            .asException());
  }

  // ==================== Per-endpoint buffer methods ====================

  boolean bufferSubmitBatchDuringSwitching(
      SwitchBufferCapableQueue sbq,
      List<Path> validPaths,
      int totalFiles,
      int rejected,
      StreamObserver<BatchResponse> responseObserver) {
    int accepted = 0;
    for (Path p : validPaths) {
      String normalized = PathNormalizer.normalizePath(p.toAbsolutePath().toString());
      if (!putSwitchBufferOrReplyUnavailable(
          sbq,
          switchBufferPathKey(normalized),
          SWITCHBUF_OP_UPSERT,
          normalized,
          "submitBatch",
          responseObserver)) {
        return false;
      }
      accepted++;
    }
    metrics.recordBatchSubmitted(accepted);
    metrics.setQueueDepth(jobQueue.queueDepth());
    log.info(
        "Buffered {} of {} files for indexing (rejected {}) [SWITCHING]",
        accepted,
        totalFiles,
        rejected);
    responseObserver.onNext(batchSuccessResponse(accepted));
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferUpdateVduResultDuringSwitching(
      SwitchBufferCapableQueue sbq,
      UpdateVduResultRequest request,
      String docId,
      StreamObserver<UpdateVduResultResponse> responseObserver)
      throws Exception {
    String normalizedId = normalizeDocIdForMutation(docId);
    String payload = updateVduSwitchBufferPayload(request, normalizedId);
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        switchBufferVduUpdateKey(normalizedId),
        SWITCHBUF_OP_VDU_UPDATE,
        payload,
        "updateVduResult",
        responseObserver)) {
      return false;
    }
    responseObserver.onNext(updateVduSuccessResponse());
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferSyncDirectoryDuringSwitching(
      SwitchBufferCapableQueue sbq,
      String rootPath,
      boolean force,
      StreamObserver<SyncDirectoryResponse> responseObserver)
      throws Exception {
    String resolvedRoot = resolveNormalizedPathPrefix(rootPath);
    String payload = syncDirectorySwitchBufferPayload(resolvedRoot, force);
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        switchBufferSyncRootKey(resolvedRoot),
        SWITCHBUF_OP_SYNC_ROOT,
        payload,
        "syncDirectory",
        responseObserver)) {
      return false;
    }
    responseObserver.onNext(deferredSyncDirectoryResponse());
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferDeleteByPathDuringSwitching(
      SwitchBufferCapableQueue sbq,
      String pathPrefix,
      StreamObserver<DeleteByPathResponse> responseObserver) {
    String normalizedPrefix = normalizeDeletePrefixForMutation(pathPrefix);
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        switchBufferPrefixKey(normalizedPrefix),
        SWITCHBUF_OP_DELETE_PREFIX,
        normalizedPrefix,
        "deleteByPath",
        responseObserver)) {
      return false;
    }
    log.info("Buffered deleteByPathPrefix during SWITCHING: {}", normalizedPrefix);
    responseObserver.onNext(bufferedDeleteByPathResponse());
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferDeleteByIdDuringSwitching(
      SwitchBufferCapableQueue sbq,
      String normalizedId,
      StreamObserver<DeleteByIdResponse> responseObserver) {
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        switchBufferPathKey(normalizedId),
        SWITCHBUF_OP_DELETE,
        normalizedId,
        "deleteById",
        responseObserver)) {
      return false;
    }
    log.info("Buffered deleteById during SWITCHING: {}", normalizedId);
    responseObserver.onNext(bufferedDeleteByIdResponse());
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferPruneMissingDuringSwitching(
      SwitchBufferCapableQueue sbq, String pathPrefix, StreamObserver<PruneResponse> responseObserver) {
    String prefix = resolveNormalizedPathPrefix(pathPrefix);
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        switchBufferPrunePrefixKey(prefix),
        SWITCHBUF_OP_PRUNE_PREFIX,
        prefix,
        "pruneMissing",
        responseObserver)) {
      return false;
    }
    responseObserver.onNext(deferredPruneResponse());
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferMarkVduDuringSwitching(
      SwitchBufferCapableQueue sbq,
      String normalizedId,
      int currentCount,
      int maxRetries,
      StreamObserver<MarkVduProcessingResponse> responseObserver)
      throws Exception {
    MarkVduRetryDecision decision = decideMarkVduRetry(currentCount, maxRetries);
    if (decision.maxRetriesExceeded()) {
      String payload = vduMarkFailedSwitchBufferPayload(normalizedId, decision.retryCount());
      if (!putSwitchBufferOrReplyUnavailable(
          sbq,
          switchBufferVduMarkKey(normalizedId),
          SWITCHBUF_OP_VDU_MARK_FAILED,
          payload,
          "markVduProcessing (FAILED)",
          responseObserver)) {
        return false;
      }
      responseObserver.onNext(markVduErrorResponse(VDU_MAX_RETRIES_EXCEEDED_ERROR));
      responseObserver.onCompleted();
      return true;
    }

    String payload = vduMarkProcessingSwitchBufferPayload(normalizedId, decision.retryCount());
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        switchBufferVduMarkKey(normalizedId),
        SWITCHBUF_OP_VDU_MARK_PROCESSING,
        payload,
        "markVduProcessing (PROCESSING)",
        responseObserver)) {
      return false;
    }
    responseObserver.onNext(markVduSuccessResponse(decision.retryCount()));
    responseObserver.onCompleted();
    return true;
  }

  boolean bufferRecoverVduProcessingDuringSwitching(
      SwitchBufferCapableQueue sbq, StreamObserver<RecoverVduProcessingResponse> responseObserver)
      throws Exception {
    if (!putSwitchBufferOrReplyUnavailable(
        sbq,
        SWITCHBUF_KEY_VDU_RECOVER_PROCESSING,
        SWITCHBUF_OP_VDU_RECOVER_PROCESSING,
        "{}",
        "recoverVduProcessing",
        responseObserver)) {
      return false;
    }
    responseObserver.onNext(recoverVduCountResponse(0));
    responseObserver.onCompleted();
    return true;
  }

}
