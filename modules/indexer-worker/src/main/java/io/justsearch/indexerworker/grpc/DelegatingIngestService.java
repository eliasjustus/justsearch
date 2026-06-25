/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.ClearFailedJobsRequest;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.ResetIndexRequest;
import io.justsearch.ipc.ResetIndexResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.ListFailedJobsRequest;
import io.justsearch.ipc.ListFailedJobsResponse;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import java.util.Objects;

/**
 * Delegating wrapper for IngestService that enables runtime service swapping.
 *
 * <p>Registered once with the gRPC server. All RPC calls are forwarded to a {@code volatile}
 * delegate that can be swapped without restarting the gRPC server. The delegate is typed as the
 * generated ImplBase to support cross-classloader hot-reload (Phase 2, tempdoc 305).
 *
 * <p>Non-RPC operations (model wiring, GPU diagnostics) are routed through
 * {@code WorkerAppServices}, not through this wrapper.
 */
public final class DelegatingIngestService extends IngestServiceGrpc.IngestServiceImplBase {

  private volatile IngestServiceGrpc.IngestServiceImplBase delegate;

  public DelegatingIngestService(IngestServiceGrpc.IngestServiceImplBase delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public void setDelegate(IngestServiceGrpc.IngestServiceImplBase delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  // ==================== RPC forwards ====================

  @Override
  public void submitBatch(BatchRequest req, StreamObserver<BatchResponse> obs) {
    delegate.submitBatch(req, obs);
  }

  @Override
  public void indexStatus(StatusRequest req, StreamObserver<StatusResponse> obs) {
    delegate.indexStatus(req, obs);
  }

  @Override
  public void deleteByPath(DeleteByPathRequest req, StreamObserver<DeleteByPathResponse> obs) {
    delegate.deleteByPath(req, obs);
  }

  @Override
  public void deleteById(DeleteByIdRequest req, StreamObserver<DeleteByIdResponse> obs) {
    delegate.deleteById(req, obs);
  }

  @SuppressWarnings("deprecation")
  @Override
  public void pruneMissing(PruneRequest req, StreamObserver<PruneResponse> obs) {
    delegate.pruneMissing(req, obs);
  }

  @Override
  public void syncDirectory(SyncDirectoryRequest req, StreamObserver<SyncDirectoryResponse> obs) {
    delegate.syncDirectory(req, obs);
  }

  @Override
  public void updateVduResult(
      UpdateVduResultRequest req, StreamObserver<UpdateVduResultResponse> obs) {
    delegate.updateVduResult(req, obs);
  }

  @Override
  public void queryPendingVdu(
      QueryPendingVduRequest req, StreamObserver<QueryPendingVduResponse> obs) {
    delegate.queryPendingVdu(req, obs);
  }

  @Override
  public void markVduProcessing(
      MarkVduProcessingRequest req, StreamObserver<MarkVduProcessingResponse> obs) {
    delegate.markVduProcessing(req, obs);
  }

  @Override
  public void recoverVduProcessing(
      RecoverVduProcessingRequest req, StreamObserver<RecoverVduProcessingResponse> obs) {
    delegate.recoverVduProcessing(req, obs);
  }

  @Override
  public void startMigration(
      MigrationStartRequest req, StreamObserver<MigrationStartResponse> obs) {
    delegate.startMigration(req, obs);
  }

  @Override
  public void requestCutover(
      MigrationCutoverRequest req, StreamObserver<MigrationCutoverResponse> obs) {
    delegate.requestCutover(req, obs);
  }

  @Override
  public void pauseMigration(
      MigrationPauseRequest req, StreamObserver<MigrationPauseResponse> obs) {
    delegate.pauseMigration(req, obs);
  }

  @Override
  public void resumeMigration(
      MigrationResumeRequest req, StreamObserver<MigrationResumeResponse> obs) {
    delegate.resumeMigration(req, obs);
  }

  @Override
  public void rollbackMigration(
      MigrationRollbackRequest req, StreamObserver<MigrationRollbackResponse> obs) {
    delegate.rollbackMigration(req, obs);
  }

  @Override
  public void runIndexGc(IndexGcRequest req, StreamObserver<IndexGcResponse> obs) {
    delegate.runIndexGc(req, obs);
  }

  @Override
  public void updateDocumentPaths(UpdatePathsRequest req, StreamObserver<UpdatePathsResponse> obs) {
    delegate.updateDocumentPaths(req, obs);
  }

  @Override
  public void listFailedJobs(
      ListFailedJobsRequest req, StreamObserver<ListFailedJobsResponse> obs) {
    delegate.listFailedJobs(req, obs);
  }

  @Override
  public void countJobsByPathPrefix(
      io.justsearch.ipc.CountJobsByPathPrefixRequest req,
      StreamObserver<io.justsearch.ipc.CountJobsByPathPrefixResponse> obs) {
    delegate.countJobsByPathPrefix(req, obs);
  }

  @Override
  public void listFailedJobsByPathPrefix(
      io.justsearch.ipc.ListFailedJobsByPathPrefixRequest req,
      StreamObserver<ListFailedJobsResponse> obs) {
    delegate.listFailedJobsByPathPrefix(req, obs);
  }

  @Override
  public void clearFailedJobs(
      ClearFailedJobsRequest req, StreamObserver<ClearFailedJobsResponse> obs) {
    delegate.clearFailedJobs(req, obs);
  }

  @Override
  public void resetIndex(ResetIndexRequest req, StreamObserver<ResetIndexResponse> obs) {
    delegate.resetIndex(req, obs);
  }

  @Override
  public void getSessionPolicies(
      io.justsearch.ipc.SessionPoliciesRequest req,
      StreamObserver<io.justsearch.ipc.SessionPoliciesResponse> obs) {
    delegate.getSessionPolicies(req, obs);
  }

  @Override
  public void reloadRuntime(
      io.justsearch.ipc.ReloadRuntimeRequest req,
      StreamObserver<io.justsearch.ipc.ReloadRuntimeResponse> obs) {
    delegate.reloadRuntime(req, obs);
  }

  @Override
  public void recentIngestionEvents(
      io.justsearch.ipc.RecentIngestionEventsRequest req,
      StreamObserver<io.justsearch.ipc.RecentIngestionEventsResponse> obs) {
    delegate.recentIngestionEvents(req, obs);
  }

  @Override
  public void ingestionOutcomeSummary(
      io.justsearch.ipc.IngestionOutcomeSummaryRequest req,
      StreamObserver<io.justsearch.ipc.IngestionOutcomeSummaryResponse> obs) {
    delegate.ingestionOutcomeSummary(req, obs);
  }

  // Tempdoc 419 / T5.3 (ADR-0028) — scoped reverse-lookup forward.
  @Override
  public void lookupPathByHash(
      io.justsearch.ipc.LookupPathByHashRequest req,
      StreamObserver<io.justsearch.ipc.LookupPathByHashResponse> obs) {
    delegate.lookupPathByHash(req, obs);
  }

  // Tempdoc 418 Phase A — Worker-owned filesystem traversal forwards.

  @Override
  public void scanRoot(
      io.justsearch.ipc.ScanRootRequest req,
      StreamObserver<io.justsearch.ipc.ScanRootProgress> obs) {
    delegate.scanRoot(req, obs);
  }

  @Override
  public void watchRoot(
      io.justsearch.ipc.WatchRootRequest req,
      StreamObserver<io.justsearch.ipc.WatchRootResponse> obs) {
    delegate.watchRoot(req, obs);
  }

  @Override
  public void unwatchRoot(
      io.justsearch.ipc.UnwatchRootRequest req,
      StreamObserver<io.justsearch.ipc.UnwatchRootResponse> obs) {
    delegate.unwatchRoot(req, obs);
  }

  // Slice 445 — Job-queue TABULAR Resource forwards.

  @Override
  public void subscribeIndexingJobs(
      io.justsearch.ipc.SubscribeIndexingJobsRequest req,
      StreamObserver<io.justsearch.ipc.IndexingJobsFrame> obs) {
    delegate.subscribeIndexingJobs(req, obs);
  }

  @Override
  public void cancelIndexingJob(
      io.justsearch.ipc.CancelIndexingJobRequest req,
      StreamObserver<io.justsearch.ipc.CancelIndexingJobResponse> obs) {
    delegate.cancelIndexingJob(req, obs);
  }

  @Override
  public void retryIndexingJob(
      io.justsearch.ipc.RetryIndexingJobRequest req,
      StreamObserver<io.justsearch.ipc.RetryIndexingJobResponse> obs) {
    delegate.retryIndexingJob(req, obs);
  }
}
