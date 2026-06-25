/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.util.Objects;

/**
 * Immutable key identifying a {@link SampleContract} call site for
 * {@link ContractSampler} counter bookkeeping (tempdoc 402 §3.3).
 *
 * <p>Each annotated call site declares its own {@code SampleKey} as a
 * {@code private static final} constant so the {@link ContractSampler}
 * counter map keys on a stable reference, not a per-invocation allocation:
 *
 * <pre>{@code
 * private static final SampleKey ENCODER_LEASE_HELD =
 *     new SampleKey("encoder.lease.held", 1000);
 * }</pre>
 *
 * <p>Kept as a value record (not a central enum) so each downstream
 * module declares its sample keys locally. A central enum would couple
 * every sampled invariant back to core-contracts, which defeats the
 * tier-taxonomy design: sampling sites belong next to the invariant
 * they guard, not in a shared registry.
 *
 * <p><b>Always declare sample keys as {@code private static final}
 * constants.</b> {@link ContractSampler} keys its counter map on
 * {@code SampleKey} identity (via {@link #equals} / {@link #hashCode}),
 * and counter entries are never evicted — a dynamic-key pattern (e.g.
 * {@code new SampleKey("x-" + userId, 1000)} per request) leaks a
 * counter entry per distinct key forever. Static-constant usage keeps
 * the counter map bounded by the number of sample-annotated sites.
 *
 * @param name descriptive key name; used in diagnostics and never null.
 * @param sampleRate fire once every {@code sampleRate} calls; must be
 *     {@code >= 1}. Matches the {@code @SampleContract.every()} declared
 *     at the call site.
 */
public record SampleKey(String name, int sampleRate) {

  public SampleKey {
    Objects.requireNonNull(name, "name");
    if (sampleRate < 1) {
      throw new IllegalArgumentException(
          "sampleRate must be >= 1, got " + sampleRate + " for key " + name);
    }
  }
}
