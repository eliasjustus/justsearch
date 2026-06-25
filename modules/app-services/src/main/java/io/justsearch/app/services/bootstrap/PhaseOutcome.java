/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import java.util.Optional;
import java.util.Set;

/**
 * Tempdoc 541 §5.3 — sealed-sum outcome for a phase invocation.
 *
 * <p>Applies tempdoc 517's "decision-as-value" pattern outside its origin domain (search
 * execution → composition lifecycle). Every phase returns a typed Ready / Degraded / Failed
 * outcome with attached reason codes (cross-referencing 529's wireCode taxonomy where
 * applicable).
 *
 * <p>Migration shape per §5.3: phases migrate one at a time, smallest first. The substrate
 * carries both shapes during transition — existing phases return product-type Outputs;
 * migrated phases return {@code PhaseOutcome<Output>}. The discipline gate (§4.3) tracks
 * migration progress (rule 4e — Eagerness conformance — and a future rule for outcome-type
 * conformance).
 *
 * <p>Downstream consumers pattern-match. {@code Failed} encodes the failure cause; {@code
 * Degraded} carries an Output but with non-empty reason codes (e.g., {@code
 * "inference.not_configured"}) for callers to introspect. {@code Ready} carries the typed
 * Output and is the "healthy boot" arm.
 *
 * <p>Composability: a future {@code OutcomeOps.zip(a, b)} helper can produce a {@code
 * PhaseOutcome<Pair>} propagating Failed if either side failed and Degraded if either is
 * Degraded. Not shipped in this iteration; downstream consumers handle Outcomes individually
 * today.
 *
 * @param <O> the phase's typed Output value type.
 */
public sealed interface PhaseOutcome<O>
    permits PhaseOutcome.Ready, PhaseOutcome.Degraded, PhaseOutcome.Failed {

  /** Returns true iff the outcome carries a non-null Output value (Ready or Degraded). */
  default boolean hasValue() {
    return this instanceof Ready<?> || this instanceof Degraded<?>;
  }

  /**
   * Returns the Output value if present, else throws an {@link IllegalStateException}.
   * Convenience for callers that want to fail fast on Failed outcomes.
   */
  default O orThrow() {
    return switch (this) {
      case Ready<O> r -> r.value();
      case Degraded<O> d -> d.value();
      case Failed<O> f ->
          throw new IllegalStateException(
              "Phase outcome is Failed: " + f.cause().getClass().getSimpleName() + " — "
                  + f.cause().getMessage(),
              f.cause());
    };
  }

  /** Returns the Output value if present, else empty. Safe alternative to {@link #orThrow}. */
  default Optional<O> optionalValue() {
    return switch (this) {
      case Ready<O> r -> Optional.ofNullable(r.value());
      case Degraded<O> d -> Optional.ofNullable(d.value());
      case Failed<O> f -> f.partial();
    };
  }

  /**
   * Returns the reason set:
   *
   * <ul>
   *   <li>{@code Ready} — empty.
   *   <li>{@code Degraded} — the typed reasons from {@link Degraded#reasons}.
   *   <li>{@code Failed} — the typed {@link Failed#reasons} if non-empty (preferred —
   *       carriers know their reason codes); otherwise falls back to the cause's
   *       class-simple-name. Fix-pass E.3: previously always returned the simple-name,
   *       which laundered typed reason codes through reflection.
   * </ul>
   */
  default Set<String> reasonCodes() {
    return switch (this) {
      case Ready<O> r -> Set.of();
      case Degraded<O> d -> d.reasons();
      case Failed<O> f ->
          f.reasons().isEmpty() ? Set.of(f.cause().getClass().getSimpleName()) : f.reasons();
    };
  }

  /** Healthy outcome: the phase produced its declared Output. */
  record Ready<O>(O value) implements PhaseOutcome<O> {}

  /**
   * Degraded outcome: the phase produced its Output but flags non-empty reason codes (e.g.,
   * dependent capability missing, optional feature disabled by config). Downstream consumers
   * may continue with the Output but should surface the reasons to the observability layer.
   */
  record Degraded<O>(O value, Set<String> reasons) implements PhaseOutcome<O> {
    public Degraded {
      reasons = reasons == null || reasons.isEmpty() ? Set.of() : Set.copyOf(reasons);
    }
  }

  /**
   * Failed outcome: the phase did not produce a usable Output. {@code cause} is the
   * underlying exception; {@code partial} may carry a partially-built value when the phase
   * supports salvage; {@code reasons} carries typed reason codes (preferred over reflecting
   * on the cause's class simple-name). Pattern-match callers must handle this arm — there
   * is no implicit Ready coercion.
   *
   * <p>Fix-pass E.3: added {@code reasons} component so failures can carry typed codes
   * (e.g., {@code "grpc.bind_failed"}, {@code "ilm.init_timeout"}) without leaking the
   * exception class name as the canonical signal.
   */
  record Failed<O>(Throwable cause, Set<String> reasons, Optional<O> partial)
      implements PhaseOutcome<O> {
    public Failed {
      if (cause == null) {
        throw new IllegalArgumentException("Failed outcome requires a non-null cause");
      }
      reasons = reasons == null || reasons.isEmpty() ? Set.of() : Set.copyOf(reasons);
      partial = partial == null ? Optional.empty() : partial;
    }

    /** Convenience: Failed with only a cause (no typed reasons, no partial value). */
    public static <O> Failed<O> of(Throwable cause) {
      return new Failed<>(cause, Set.of(), Optional.empty());
    }

    /** Convenience: Failed with typed reasons (no partial value). */
    public static <O> Failed<O> of(Throwable cause, Set<String> reasons) {
      return new Failed<>(cause, reasons, Optional.empty());
    }
  }
}
