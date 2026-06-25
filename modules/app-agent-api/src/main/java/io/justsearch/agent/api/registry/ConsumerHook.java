/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * Typed declaration that a live consumer reads the substrate slot this hook is
 * attached to.
 *
 * <p>Per slice 481 §3.5: substrate slots that declare consumer hooks make the
 * implicit Pass-6 procedural rule ("name a consumer slice or don't ship the
 * slot") into a typed property. The structural enforcement layer (CI gate that
 * asserts non-empty consumers + runtime-witness verification on Realized hooks)
 * is slice 485's work; this type ships the data shape the gate consumes.
 *
 * <p>Slice 484 §3.6 closure (2026-05-08): the `Promised` variant was reverted
 * per Pass-8-as-merge-gate compliance. {@link Promised} had zero production
 * construction sites and zero readers; its only meaningful reader (the
 * SliceCatalog referential-integrity gate from slice 483 §5) has not been
 * ratified. Per the slice 481 external review's load-bearing finding —
 * substrate without a real consumer reproduces C-018 — Promised was removed
 * rather than left in main as forward-compat scaffolding.
 *
 * <p>If/when slice 485 ratifies and re-introduces Promised, the variant adds
 * back in the same commit that ships its consumer (CI gate + per-actor
 * deadline disjunction per slice 483 §4.2.B). Until then, ConsumerHook is a
 * single-variant marker rather than a 2-variant disjunction.
 *
 * <p>The sealed interface is preserved (rather than collapsing to a single
 * record) so that re-introducing variants under slice 485 doesn't require
 * widening a type that other code matches on.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({@JsonSubTypes.Type(value = ConsumerHook.Realized.class, name = "realized")})
public sealed interface ConsumerHook permits ConsumerHook.Realized {

  /** Stable identifier for the consumer (renderer path, handler id, test reference). */
  String consumerId();

  /** Audience this hook serves. Drives the witness-shape dispatch. */
  Audience audience();

  /**
   * A live consumer exists today. The runtime-witness mechanism (slice 481 §3.5 rule 4)
   * verifies the consumer actually receives data during a representative test session;
   * the verification dispatch is by audience (FE/plugin = wire-delivery; AGENT =
   * prompt-construction linkage). Slice 485 designs + ships the witness mechanism.
   */
  record Realized(String consumerId, Audience audience) implements ConsumerHook {
    public Realized {
      Objects.requireNonNull(consumerId, "consumerId");
      Objects.requireNonNull(audience, "audience");
      if (consumerId.isBlank()) {
        throw new IllegalArgumentException("Realized consumerId must be non-blank");
      }
    }
  }
}
