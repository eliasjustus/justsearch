/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
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
/**
 * Migration lifecycle control helper for {@link GrpcIngestService}.
 *
 * <p>Encapsulates the 6 migration control RPCs (start, cutover, pause, resume, rollback, GC) that
 * all gate on {@link IndexGenerationManager} and manage migration state transitions. Extracted to
 * reduce the size of the service class.
 */
final class MigrationControlOps {

  private final IndexGenerationManager indexGenerationManager;
  private final Runnable restartWorkerCallback;

  MigrationControlOps(IndexGenerationManager indexGenerationManager, Runnable restartWorkerCallback) {
    this.indexGenerationManager = indexGenerationManager;
    this.restartWorkerCallback = restartWorkerCallback;
  }

  void startMigration(
      MigrationStartRequest request, StreamObserver<MigrationStartResponse> responseObserver) {
    String reason = request.getReason();
    boolean restart = request.getRestartWorker();
    try {
      if (indexGenerationManager == null) {
        responseObserver.onNext(
            MigrationStartResponse.newBuilder()
                .setAccepted(false)
                .setError("Index generation manager not available")
                .build());
        responseObserver.onCompleted();
        return;
      }
      IndexGenerationManager.State next =
          indexGenerationManager.startMigration(reason.isBlank() ? "manual" : reason.trim());
      String active =
          next == null || next.active_generation() == null ? "" : next.active_generation();
      String building =
          next == null || next.building_generation() == null ? "" : next.building_generation();
      String ms = next == null || next.migration_state() == null ? "" : next.migration_state();

      responseObserver.onNext(
          MigrationStartResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setMigrationState(ms)
              .setActiveGenerationId(active)
              .setBuildingGenerationId(building)
              .setRestartScheduled(restart && restartWorkerCallback != null)
              .build());
      responseObserver.onCompleted();

      if (restart && restartWorkerCallback != null) {
        // Best-effort: restart after responding.
        new Thread(
                () -> {
                  try {
                    Thread.sleep(150);
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                  restartWorkerCallback.run();
                },
                "migration-start-restart")
            .start();
      }
    } catch (Exception e) {
      responseObserver.onNext(
          MigrationStartResponse.newBuilder()
              .setAccepted(false)
              .setError(e.getMessage() == null ? "Failed to start migration" : e.getMessage())
              .build());
      responseObserver.onCompleted();
    }
  }

  void requestCutover(
      MigrationCutoverRequest request,
      StreamObserver<MigrationCutoverResponse> responseObserver) {
    try {
      if (indexGenerationManager == null) {
        responseObserver.onNext(
            MigrationCutoverResponse.newBuilder()
                .setAccepted(false)
                .setError("Index generation manager not available")
                .build());
        responseObserver.onCompleted();
        return;
      }
      // Force SWITCHING (best-effort). Cutover monitor will handle the rest.
      if (request.getForceSwitching()) {
        indexGenerationManager.updateMigrationState(
            IndexGenerationManager.MigrationState.SWITCHING);
      }
      IndexGenerationManager.State s = indexGenerationManager.readStateBestEffort();
      responseObserver.onNext(
          MigrationCutoverResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setMigrationState(
                  s == null || s.migration_state() == null ? "" : s.migration_state())
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onNext(
          MigrationCutoverResponse.newBuilder()
              .setAccepted(false)
              .setError(
                  e.getMessage() == null ? "Failed to request cutover" : e.getMessage())
              .build());
      responseObserver.onCompleted();
    }
  }

  void pauseMigration(
      MigrationPauseRequest request, StreamObserver<MigrationPauseResponse> responseObserver) {
    try {
      if (indexGenerationManager == null) {
        responseObserver.onNext(
            MigrationPauseResponse.newBuilder()
                .setAccepted(false)
                .setError("Index generation manager not available")
                .setMigrationPaused(false)
                .build());
        responseObserver.onCompleted();
        return;
      }
      IndexGenerationManager.State s = indexGenerationManager.readStateBestEffort();
      String ms = s == null || s.migration_state() == null ? "" : s.migration_state();
      if ("SWITCHING".equalsIgnoreCase(ms)) {
        responseObserver.onNext(
            MigrationPauseResponse.newBuilder()
                .setAccepted(false)
                .setError("Cannot pause during SWITCHING")
                .setMigrationPaused(false)
                .build());
        responseObserver.onCompleted();
        return;
      }
      IndexGenerationManager.State next =
          indexGenerationManager.setMigrationPaused(
              true, request == null ? "" : request.getReason());
      responseObserver.onNext(
          MigrationPauseResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setMigrationPaused(next != null && Boolean.TRUE.equals(next.migration_paused()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onNext(
          MigrationPauseResponse.newBuilder()
              .setAccepted(false)
              .setError(
                  e.getMessage() == null ? "Failed to pause migration" : e.getMessage())
              .setMigrationPaused(false)
              .build());
      responseObserver.onCompleted();
    }
  }

  void resumeMigration(
      MigrationResumeRequest request, StreamObserver<MigrationResumeResponse> responseObserver) {
    try {
      if (indexGenerationManager == null) {
        responseObserver.onNext(
            MigrationResumeResponse.newBuilder()
                .setAccepted(false)
                .setError("Index generation manager not available")
                .setMigrationPaused(false)
                .build());
        responseObserver.onCompleted();
        return;
      }
      IndexGenerationManager.State next = indexGenerationManager.setMigrationPaused(false, null);
      responseObserver.onNext(
          MigrationResumeResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setMigrationPaused(next != null && Boolean.TRUE.equals(next.migration_paused()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onNext(
          MigrationResumeResponse.newBuilder()
              .setAccepted(false)
              .setError(
                  e.getMessage() == null ? "Failed to resume migration" : e.getMessage())
              .setMigrationPaused(false)
              .build());
      responseObserver.onCompleted();
    }
  }

  void rollbackMigration(
      MigrationRollbackRequest request,
      StreamObserver<MigrationRollbackResponse> responseObserver) {
    boolean restart = request.getRestartWorker();
    try {
      if (indexGenerationManager == null) {
        responseObserver.onNext(
            MigrationRollbackResponse.newBuilder()
                .setAccepted(false)
                .setError("Index generation manager not available")
                .build());
        responseObserver.onCompleted();
        return;
      }
      IndexGenerationManager.State next = indexGenerationManager.rollbackToPreviousGeneration();
      if (next == null) {
        responseObserver.onNext(
            MigrationRollbackResponse.newBuilder()
                .setAccepted(false)
                .setError("No index state available")
                .build());
        responseObserver.onCompleted();
        return;
      }
      responseObserver.onNext(
          MigrationRollbackResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setActiveGenerationId(
                  next.active_generation() == null ? "" : next.active_generation())
              .setPreviousGenerationId(
                  next.previous_generation() == null ? "" : next.previous_generation())
              .setRestartScheduled(restart && restartWorkerCallback != null)
              .build());
      responseObserver.onCompleted();

      if (restart && restartWorkerCallback != null) {
        new Thread(
                () -> {
                  try {
                    Thread.sleep(150);
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                  restartWorkerCallback.run();
                },
                "migration-rollback-restart")
            .start();
      }
    } catch (Exception e) {
      responseObserver.onNext(
          MigrationRollbackResponse.newBuilder()
              .setAccepted(false)
              .setError(
                  e.getMessage() == null ? "Failed to rollback migration" : e.getMessage())
              .build());
      responseObserver.onCompleted();
    }
  }

  void runIndexGc(IndexGcRequest request, StreamObserver<IndexGcResponse> responseObserver) {
    try {
      if (indexGenerationManager == null) {
        responseObserver.onNext(
            IndexGcResponse.newBuilder()
                .setAccepted(false)
                .setError("Index generation manager not available")
                .setMarkedCount(0)
                .setPrunedCount(0)
                .build());
        responseObserver.onCompleted();
        return;
      }
      int keepLatest = request == null ? 0 : request.getKeepLatest();
      boolean pruneMarkedOnly = request != null && request.getPruneMarkedOnly();
      IndexGenerationManager.GcResult r =
          indexGenerationManager.gcBestEffort(keepLatest, pruneMarkedOnly);
      responseObserver.onNext(
          IndexGcResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setMarkedCount(r == null ? 0 : r.markedCount())
              .setPrunedCount(r == null ? 0 : r.prunedCount())
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onNext(
          IndexGcResponse.newBuilder()
              .setAccepted(false)
              .setError(e.getMessage() == null ? "Failed to run GC" : e.getMessage())
              .setMarkedCount(0)
              .setPrunedCount(0)
              .build());
      responseObserver.onCompleted();
    }
  }
}
