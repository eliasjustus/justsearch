/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import java.util.Objects;

/**
 * Emits {@code contract.violation} span events for {@link SampleContract}
 * sites (tempdoc 402 §3.3).
 *
 * <p>The event is attached to {@link Span#current()} — the active span on
 * the calling thread. The attribute shape is <b>consumer-locked</b>:
 * {@code scripts/jseval/jseval/projections/contract_violations.py} reads
 * the three attrs {@link #TEMPDOC_KEY}, {@link #TIER_KEY}, and
 * {@link #DESCRIPTION_KEY}. A rename on the Java side silently drops
 * events in the projection — the D-1 silent-failure class tempdoc 402
 * exists to eliminate. Reject-renames discipline: both sides must change
 * together, or neither.
 *
 * <p>When no span context is active, {@code Span.current()} returns a
 * no-op span and {@link Span#addEvent} is a no-op. That's acceptable:
 * sample sites are expected to run inside traced operations by
 * construction. A bare invocation outside a traced scope is a silent
 * drop — documented here rather than guarded, since guarding would
 * require a logger dep chain that defeats the dep-free leaf design.
 *
 * <p>Typical call-site shape (tempdoc 402 §3.3):
 *
 * <pre>{@code
 * if (ContractSampler.shouldSample(LEASE_HELD) && !lease.isHeld()) {
 *   ContractEmitter.emit("@SampleContract", "400 LR2-b", "Lease not held");
 * }
 * }</pre>
 */
public final class ContractEmitter {

  /** Event name consumed by {@code contract_violations.py}. */
  public static final String EVENT_NAME = "contract.violation";

  /** Attribute key: originating tempdoc reference. */
  public static final AttributeKey<String> TEMPDOC_KEY = AttributeKey.stringKey("contract.tempdoc");

  /** Attribute key: tier annotation name (e.g. {@code "@SampleContract"}). */
  public static final AttributeKey<String> TIER_KEY = AttributeKey.stringKey("contract.tier");

  /** Attribute key: human-readable description of the violated invariant. */
  public static final AttributeKey<String> DESCRIPTION_KEY =
      AttributeKey.stringKey("contract.description");

  private ContractEmitter() {}

  /**
   * Attach a {@code contract.violation} span event to the current span.
   * All three arguments must be non-null; an empty string is acceptable
   * (the Python consumer at {@code contract_violations.py} falls back to
   * {@code "<unknown>"} only on missing keys, not on empty values).
   *
   * <p>Nulls are rejected explicitly here rather than delegated to OTel —
   * OTel's {@code Attributes.of} silently tolerates null values on string
   * keys, which would produce a malformed {@code contract.violation} event
   * (null-valued attr) and silently degrade the Python consumer's
   * aggregation. Throwing here makes the contract breach loud at the
   * emitter layer instead of surfacing as drift in Layer 4 outputs.
   *
   * @param tier the annotation tier (e.g. {@code "@SampleContract"},
   *     {@code "@BootContract"}). Emitted as {@code contract.tier}.
   * @param tempdoc originating tempdoc reference (e.g. {@code "400 LR2-b"}).
   *     Emitted as {@code contract.tempdoc}.
   * @param description short human-readable description of the violated
   *     invariant. Emitted as {@code contract.description}.
   * @throws NullPointerException if any argument is {@code null}. Call
   *     sites should guard against null inputs at the source; a null at
   *     this layer is a programming error, not a runtime condition.
   */
  public static void emit(String tier, String tempdoc, String description) {
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(tempdoc, "tempdoc");
    Objects.requireNonNull(description, "description");
    Span.current()
        .addEvent(
            EVENT_NAME,
            Attributes.of(
                TIER_KEY, tier,
                TEMPDOC_KEY, tempdoc,
                DESCRIPTION_KEY, description));
  }
}
