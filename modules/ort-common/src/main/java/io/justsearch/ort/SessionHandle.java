/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Runtime-facing wrapper over an encoder's ORT session state.
 *
 * <p>Introduced in tempdoc 397 §7.4 / Stage 2. Encoders consume this interface; the concrete
 * implementation is {@link NativeSessionHandle} (package-private construction via
 * {@link OrtSessionAssembler}). The handle surface is deliberately narrow: only what encoder
 * code actually needs (lease acquisition, environment access, input-name introspection at
 * construction time, and CUDA observability pass-throughs). Policy and construction live
 * upstream in {@link OrtSessionAssembler}; encoder classes never reference the implementation
 * directly.
 *
 * <p><strong>Admin API vs encoder API.</strong> Of the surface below:
 *
 * <ul>
 *   <li>{@link #acquire()}, {@link #environment()}, {@link #isGpuAvailable()}, {@link #status()}
 *       are called by <em>encoder</em> code during inference.
 *   <li>{@link #releaseGpu()} and {@link #close()} are called by the <em>signal-bus
 *       coordinator</em> (today's {@code IndexingLoop}-like GPU-arbitration code) when the Main
 *       process claims the GPU or the Worker shuts down. Encoder code should not call these.
 * </ul>
 *
 * <p>Stage 3 migrations may extend this interface with a {@code setLifecycleCallback} method
 * when SPLADE's pinned-memory cleanup hook needs typed wiring. NER's Stage 2 migration does not
 * require it.
 *
 * <p><strong>Reacquisition.</strong> There is no explicit {@code reacquire()} method:
 * {@link #acquire()} lazily recreates the GPU session on the next call after a prior
 * {@link #releaseGpu()}. This mirrors {@link NativeSessionHandle}'s existing semantics (see
 * {@code tryCreateGpuSession} guarded by {@code gpuSessionAttempted}).
 */
public interface SessionHandle extends AutoCloseable {

  /**
   * Acquires a session for one inference call. The returned {@link Lease} holds the selected
   * session (GPU if available and not being released, else CPU), the per-session
   * {@link ai.onnxruntime.OrtSession.RunOptions} (null for CPU sessions), and a {@code release}
   * runnable. Callers must close the lease via try-with-resources or explicit
   * {@code release.run()} to avoid holding the GPU serialisation semaphore.
   */
  Lease acquire();

  /**
   * Acquires a CPU-only lease for explicit fallback-path inference (e.g., SPLADE's
   * {@code runHeapFallback} on BFCArena failure). The returned lease is always backed by the
   * CPU session; {@link Lease#runOptions()} is always null; {@link Lease#isCpu()} is always true.
   * No GPU serialisation semaphore is held — CPU sessions are fully concurrent by ORT's own
   * design.
   *
   * <p>Tempdoc 397 §14.5 W4. Intended for the pattern
   * <pre>{@code
   * try (var lease = sessions.acquire()) {
   *     try { session.run(...); }
   *     catch (OrtException e) {
   *         if (!lease.isCpu() && isBfcArenaFailure(e)) {
   *             try (var cpuLease = sessions.acquireCpu()) {
   *                 return runHeapFallback(cpuLease.session(), ...);
   *             }
   *         }
   *     }
   * }
   * }</pre>
   *
   * <p>When the CPU session is deferred and not yet materialised, the implementation performs
   * lazy double-checked-locking construction before returning (materialisation on demand). This
   * may throw {@link ai.onnxruntime.OrtException} if the CPU model is unavailable.
   */
  Lease acquireCpu();

  /** The shared JVM-singleton {@link OrtEnvironment} used for tensor construction. */
  OrtEnvironment environment();

  /** True if the GPU session is currently available (session created + not being released). */
  boolean isGpuAvailable();

  /** Observability view of GPU state (readiness, fallback cause, etc.). */
  OrtCudaStatus status();

  /**
   * Releases the GPU session to free VRAM. Called by the signal-bus coordinator when the Main
   * process claims the GPU. Subsequent {@link #acquire()} calls return CPU sessions until
   * arbitration returns the GPU to the Worker; at that point the next {@code acquire()}
   * lazily recreates the GPU session.
   */
  void releaseGpu();

  /**
   * Signals that the CPU session has returned bad output (NaN, BFCArena failure, etc.) — see
   * F-009. The handle tears down the current CPU session; the next {@link #acquire()} call that
   * needs a CPU session will lazily recreate it. Encoders call this from their
   * {@link ai.onnxruntime.OrtException} catch blocks, gated on {@link Lease#isCpu()} so only
   * CPU failures trigger recreation (GPU failures don't corrupt the CPU session). Tempdoc 397
   * §14.5 W6.
   *
   * <p>Tempdoc 414: callers pass a typed {@link io.justsearch.ort.telemetry.CpuRecreateCause} so
   * the {@code ort.session.recovery_total{cause}} metric carries the actual cause classification
   * (e.g., {@code BFC_ARENA_FAILURE} when {@link io.justsearch.ort.NativeSessionHandle#isBfcArenaFailure}
   * returned true). Use {@link io.justsearch.ort.telemetry.CpuRecreateCause#UNKNOWN} when the
   * caller can't classify.
   *
   * <p><strong>Thread-safety (§14.7 post-review Issue 5).</strong> Implementations must handle
   * concurrent access safely: a caller may invoke this method while another thread holds an
   * in-flight {@link Lease} backed by the session about to be torn down. Today's
   * {@link NativeSessionHandle}-backed implementation guards CPU-session lifecycle with internal
   * locking; native implementations in Stage 4 must preserve this. Callers are not required to
   * coordinate — the handle absorbs the race.
   */
  void reportCpuSessionFailure(io.justsearch.ort.telemetry.CpuRecreateCause cause);

  /**
   * Registers a callback invoked before the GPU session is torn down — used by encoders that
   * own GPU-allocated resources (e.g., SPLADE's pinned-memory output buffer) and must release
   * them before the session closes. Tempdoc 397 §14.5 W5.
   *
   * <p>Calling twice replaces the previous callback (at-most-one). Pass {@code null} to clear.
   * Contract matches {@link GpuLifecycleCallback}'s documentation: invocation is synchronous on
   * the {@link #releaseGpu()} caller's thread while the handle holds the GPU inference
   * semaphore, so no concurrent GPU inference can race with the cleanup.
   */
  void setLifecycleCallback(GpuLifecycleCallback callback);

  /** Closes all sessions and releases resources. Idempotent. */
  @Override
  void close();

  /**
   * A lease on an ORT session that releases the GPU serialisation semaphore (if held) on close.
   * Use with try-with-resources.
   *
   * <p>The {@code release} runnable captures any internal cleanup logic (semaphore release,
   * state-re-check) from the underlying session manager.
   *
   * <p>Tempdoc 397 §14.5 W3: the {@code isCpu} flag is set by {@link SessionHandle#acquire()}
   * based on whether the underlying manager handed back its CPU session or its GPU session.
   * Encoders that branch on "which path am I on?" — for CPU-fallback gating or
   * {@code reportCpuSessionFailure} guards — read this flag rather than compare raw session
   * identities against {@code peekCpuSession()}.
   *
   * @param session the session to run inference on (non-null)
   * @param runOptions GPU-path {@link ai.onnxruntime.OrtSession.RunOptions}, or null for CPU
   * @param release invoked by {@link #close()} to release internal state
   * @param isCpu true if the lease is backed by the CPU session; false for the GPU session
   */
  record Lease(
      OrtSession session, OrtSession.RunOptions runOptions, Runnable release, boolean isCpu)
      implements AutoCloseable {
    @Override
    public void close() {
      release.run();
    }
  }
}
