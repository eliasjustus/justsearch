/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

/**
 * Sealed type for events fired from {@code OrtSessionAssembler} before any
 * {@code SessionHandle} is returned. Distinct from {@link TransitionReason} — assembler-time
 * events aren't transitions of an existing handle; modeling them separately keeps the lifecycle
 * type's semantics honest.
 *
 * <p>Tempdoc 414 v2 (A2 fix).
 */
public sealed interface AssemblerEvent {

  /** The encoder name the assembler was constructing for. */
  String consumer();

  /**
   * The assembler could not return a {@code SessionHandle}. {@link AssemblerFailureKind#NULL_VARIANT}
   * corresponds to the inbox observation 2026-04-24 (stress-test bug); other kinds will fire as
   * the assembler gains broader observability for production failure modes.
   */
  record Failed(String consumer, AssemblerFailureKind kind) implements AssemblerEvent {
    public Failed {
      java.util.Objects.requireNonNull(consumer, "consumer");
      java.util.Objects.requireNonNull(kind, "kind");
    }
  }
}
