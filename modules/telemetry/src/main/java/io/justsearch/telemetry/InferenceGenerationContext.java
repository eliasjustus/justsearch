/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import java.util.function.LongSupplier;

/**
 * Static slot exposing the current inference-runtime generation counter to the OTel span
 * processor. Tempdoc 518 Appendix F W2.2.
 *
 * <p>The generation counter lives on {@code TransitionRunner} in the {@code app-inference}
 * module; the telemetry module cannot depend on app-inference, so the runner registers a
 * {@link LongSupplier} here at construction time.
 *
 * <p>Default supplier returns {@code -1L} meaning "no generation available"; the span processor
 * (see {@link InferenceGenerationSpanProcessor}) skips setting the attribute in that case.
 * Last-write-wins; thread-safe via {@code volatile}. Multiple registrations in a single JVM
 * (e.g., test-suite reuse) silently replace; production has exactly one writer.
 */
public final class InferenceGenerationContext {
  private InferenceGenerationContext() {}

  private static final LongSupplier UNSET = () -> -1L;

  private static volatile LongSupplier supplier = UNSET;

  /**
   * Register a supplier returning the current inference generation. Called once at
   * {@code TransitionRunner} construction time.
   */
  public static void set(LongSupplier newSupplier) {
    supplier = newSupplier == null ? UNSET : newSupplier;
  }

  /**
   * Returns the current generation, or {@code -1L} if no supplier has been registered.
   * Best-effort: a supplier that throws propagates the exception to the caller.
   */
  public static long current() {
    return supplier.getAsLong();
  }

  /** Test-only — reset to the no-op default. */
  public static void resetForTest() {
    supplier = UNSET;
  }
}
