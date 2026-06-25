/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * Callback invoked by the runtime immediately before the GPU session is torn down. Used by
 * encoders that own GPU-allocated resources (pinned memory, kernel-scratch buffers) and must
 * release them before the session closes.
 *
 * <p>Tempdoc 397 §14.5 W5. Originally defined in Stage 2's scaffolding (§14.3), deleted in §14.4
 * as "dead code in anticipation of a consumer," re-introduced in PR 4 (§14.9) scoped to its
 * concrete first consumer: SPLADE's {@code closePinnedOutput()} hook. Registered via
 * {@link SessionHandle#setLifecycleCallback(GpuLifecycleCallback)}.
 *
 * <p><strong>Invocation semantics.</strong> The callback runs on the thread calling
 * {@link SessionHandle#releaseGpu()}, while the handle holds the GPU inference semaphore so no
 * concurrent inference is in flight. Exceptions thrown
 * by the callback are caught and logged; they do not prevent the subsequent GPU session close.
 * Callbacks should be idempotent — {@code releaseGpu()} may fire multiple times across the
 * handle's lifetime (e.g., repeated Main-claims-GPU transitions).
 */
@FunctionalInterface
public interface GpuLifecycleCallback {
  /** Invoked once per {@link SessionHandle#releaseGpu()} call, before the session closes. */
  void onBeforeRelease();
}
