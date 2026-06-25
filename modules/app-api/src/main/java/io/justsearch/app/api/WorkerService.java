/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Worker (Knowledge Server) lifecycle surface exposed to the AppFacade.
 *
 * <p>Slice 3a-1-2 closure: extracted from
 * {@code InferenceHandlers.handleRestartWorker} so the
 * {@code RestartWorkerHandler} (modules/app-services) can invoke worker restart
 * without an upward-cycling dependency on modules/ui. The implementation lives
 * in {@code modules/app-services} and wraps the existing
 * {@code KnowledgeServerBootstrap.spawner()} + {@code .client()} delegates.
 *
 * <p>{@link #unavailable()} sentinel returns when the Worker isn't configured or
 * the spawner reference is null (test paths / bare bootstraps); callers that
 * need actionable behavior should check {@link #available()} first.
 *
 * <p>Stability: stable (API contract).
 */
public interface WorkerService {

  /**
   * Restart the Worker process. Returns the new bound port on success.
   *
   * <p>Implementations should also reconnect any held gRPC client + reset
   * circuit breakers — full restart, not just process replace. Throws when the
   * service is unavailable or the underlying restart fails (network /
   * permission / spawner state).
   */
  int restart() throws Exception;

  /** Returns the current Worker process PID, or {@code 0} when not running. */
  long workerPid();

  /** Returns {@code true} when the Worker is configured and a spawner is held. */
  boolean available();

  /**
   * Null Object for environments where the Worker isn't configured. Returns {@code false} from
   * {@link #available()} and safe defaults from other accessors. Tempdoc 519 F2 (refined per
   * §22): kept as the Null Object pattern.
   */
  static WorkerService unavailable() {
    return new WorkerService() {
      @Override
      public int restart() {
        throw new UnsupportedOperationException("Worker service unavailable");
      }

      @Override
      public long workerPid() {
        return 0L;
      }

      @Override
      public boolean available() {
        return false;
      }
    };
  }
}
