/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.CircuitBreakerOpenException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migration lifecycle RPCs: start, cutover, rollback, pause, resume, GC.
 *
 * <p>All methods follow the same pattern: build proto request, call RPC via {@link
 * IngestRpcExecutor}, log result, swallow circuit breaker and general errors. Extracted from {@link
 * RemoteKnowledgeClient}.
 */
final class MigrationOps {
    private static final Logger log = LoggerFactory.getLogger(MigrationOps.class);

    private final IngestRpcExecutor rpc;

    MigrationOps(IngestRpcExecutor rpc) {
        this.rpc = Objects.requireNonNull(rpc, "rpc");
    }

    boolean startMigration(String reason) {
        try {
            MigrationStartRequest req =
                    MigrationStartRequest.newBuilder()
                            .setReason(reason == null ? "" : reason)
                            .setRestartWorker(true)
                            .build();
            var resp =
                    rpc.execute(
                            "startMigration",
                            RemoteKnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.startMigration(req));
            if (!resp.getAccepted()) {
                log.warn("startMigration rejected: {}", resp.getError());
            } else {
                log.info(
                        "startMigration accepted: state={} active={} building={}"
                                + " restartScheduled={}",
                        resp.getMigrationState(),
                        resp.getActiveGenerationId(),
                        resp.getBuildingGenerationId(),
                        resp.getRestartScheduled());
            }
            return resp.getAccepted();
        } catch (CircuitBreakerOpenException e) {
            log.debug("startMigration rejected by circuit breaker");
            return false;
        } catch (Exception e) {
            log.error("startMigration RPC failed", e);
            return false;
        }
    }

    boolean requestCutover(boolean forceSwitching) {
        try {
            MigrationCutoverRequest req =
                    MigrationCutoverRequest.newBuilder()
                            .setForceSwitching(forceSwitching)
                            .build();
            var resp =
                    rpc.execute(
                            "requestCutover",
                            RemoteKnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.requestCutover(req));
            if (!resp.getAccepted()) {
                log.warn("requestCutover rejected: {}", resp.getError());
            } else {
                log.info("requestCutover accepted: state={}", resp.getMigrationState());
            }
            return resp.getAccepted();
        } catch (CircuitBreakerOpenException e) {
            log.debug("requestCutover rejected by circuit breaker");
            return false;
        } catch (Exception e) {
            log.error("requestCutover RPC failed", e);
            return false;
        }
    }

    boolean rollbackMigration() {
        try {
            MigrationRollbackRequest req =
                    MigrationRollbackRequest.newBuilder().setRestartWorker(true).build();
            var resp =
                    rpc.execute(
                            "rollbackMigration",
                            RemoteKnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.rollbackMigration(req));
            if (!resp.getAccepted()) {
                log.warn("rollbackMigration rejected: {}", resp.getError());
            } else {
                log.info(
                        "rollbackMigration accepted: active={} previous={} restartScheduled={}",
                        resp.getActiveGenerationId(),
                        resp.getPreviousGenerationId(),
                        resp.getRestartScheduled());
            }
            return resp.getAccepted();
        } catch (CircuitBreakerOpenException e) {
            log.debug("rollbackMigration rejected by circuit breaker");
            return false;
        } catch (Exception e) {
            log.error("rollbackMigration RPC failed", e);
            return false;
        }
    }

    boolean pauseMigration(String reason) {
        try {
            MigrationPauseRequest req =
                    MigrationPauseRequest.newBuilder()
                            .setReason(reason == null ? "" : reason)
                            .build();
            var resp =
                    rpc.execute(
                            "pauseMigration",
                            RemoteKnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.pauseMigration(req));
            if (!resp.getAccepted()) {
                log.warn("pauseMigration rejected: {}", resp.getError());
            } else {
                log.info("pauseMigration accepted: paused={}", resp.getMigrationPaused());
            }
            return resp.getAccepted();
        } catch (CircuitBreakerOpenException e) {
            log.debug("pauseMigration rejected by circuit breaker");
            return false;
        } catch (Exception e) {
            log.error("pauseMigration RPC failed", e);
            return false;
        }
    }

    boolean resumeMigration() {
        try {
            MigrationResumeRequest req = MigrationResumeRequest.newBuilder().build();
            var resp =
                    rpc.execute(
                            "resumeMigration",
                            RemoteKnowledgeClient.RpcDeadlineCategory.STANDARD,
                            stub -> stub.resumeMigration(req));
            if (!resp.getAccepted()) {
                log.warn("resumeMigration rejected: {}", resp.getError());
            } else {
                log.info("resumeMigration accepted: paused={}", resp.getMigrationPaused());
            }
            return resp.getAccepted();
        } catch (CircuitBreakerOpenException e) {
            log.debug("resumeMigration rejected by circuit breaker");
            return false;
        } catch (Exception e) {
            log.error("resumeMigration RPC failed", e);
            return false;
        }
    }

    /**
     * Per slice 484 §3.6 / `core.index-gc` closure: returns the worker's structured
     * outcome (markedCount + prunedCount + error) instead of dropping it at the boundary.
     * The Operation handler's structured-output map and the REST controller's response
     * body both surface these counts.
     */
    io.justsearch.app.api.IndexingService.IndexGcOutcome runIndexGc(
            int keepLatest, boolean pruneMarkedOnly) {
        try {
            IndexGcRequest req =
                    IndexGcRequest.newBuilder()
                            .setKeepLatest(Math.max(0, keepLatest))
                            .setPruneMarkedOnly(pruneMarkedOnly)
                            .build();
            var resp =
                    rpc.execute(
                            "runIndexGc",
                            RemoteKnowledgeClient.RpcDeadlineCategory.INDEX_GC,
                            stub -> stub.runIndexGc(req));
            if (!resp.getAccepted()) {
                log.warn("runIndexGc rejected: {}", resp.getError());
                return new io.justsearch.app.api.IndexingService.IndexGcOutcome(
                        false, 0, 0, resp.getError());
            }
            log.info(
                    "runIndexGc accepted: marked={} pruned={}",
                    resp.getMarkedCount(),
                    resp.getPrunedCount());
            return new io.justsearch.app.api.IndexingService.IndexGcOutcome(
                    true, resp.getMarkedCount(), resp.getPrunedCount(), "");
        } catch (CircuitBreakerOpenException e) {
            log.debug("runIndexGc rejected by circuit breaker");
            return new io.justsearch.app.api.IndexingService.IndexGcOutcome(
                    false, 0, 0, "Worker circuit breaker open");
        } catch (Exception e) {
            log.error("runIndexGc RPC failed", e);
            return new io.justsearch.app.api.IndexingService.IndexGcOutcome(
                    false, 0, 0, "RPC failed: " + e.getMessage());
        }
    }
}
