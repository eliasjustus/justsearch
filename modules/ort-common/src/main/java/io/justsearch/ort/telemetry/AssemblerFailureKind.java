/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

import java.util.Locale;

/**
 * Tag value classifying an {@code OrtSessionAssembler.buildManager} failure that prevented
 * a {@code SessionHandle} from being returned. Exposed via
 * {@code ort.session.assembler_failure_total{kind=...}}.
 *
 * <p>{@link #NULL_VARIANT} corresponds to the inbox observation 2026-04-24:
 * {@code ModelSessionPolicy.forFallback(...)} synthesises a null {@code variant}, and the
 * assembler dereferences {@code policy.variant().executionProvider()} at line 63. The metric
 * fires before the NPE propagates so the failure is observable in the stress-test output even
 * though the NPE itself is fixed by a separate tempdoc.
 */
public enum AssemblerFailureKind {
  NULL_VARIANT,
  MODEL_MISSING,
  CUDA_UNAVAILABLE,
  UNKNOWN;

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
