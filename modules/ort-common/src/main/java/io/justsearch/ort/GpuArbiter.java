/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/**
 * Typed replacement for the {@code BooleanSupplier shouldUseGpu} parameter that the legacy
 * encoder constructors accept. Tempdoc 397 §7.4: the arbitration query is a runtime dependency
 * of the {@link SessionHandle}, not a policy field — its answer can change on every invocation
 * (the Main process may claim/release the GPU at any moment).
 *
 * <p>Stage 2 (NER migration) wraps the existing
 * {@code () -> !signalBus.isMainGpuActive()} lambda in a typed {@code GpuArbiter} at the
 * composition root. Stage 3+ migrations do the same. Stage 4 may consolidate multiple
 * arbiters into a single service as the signal-bus surface grows.
 */
@FunctionalInterface
public interface GpuArbiter {
  /** Returns true if the GPU is currently available for the Worker to use. */
  boolean shouldUseGpu();
}
