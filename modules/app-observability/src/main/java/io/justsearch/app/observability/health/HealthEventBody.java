/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed body discriminator for {@link HealthEvent}.
 *
 * <p>Per tempdoc 430 §B.B: the 27 known events are structurally three different shapes —
 * lifecycle (one-shot, no resolved counterpart), condition (asserted state with
 * lastTransitionTime), threshold (magnitude-based, dwell-time-driven). Encoding the
 * shape in the type system per {@code 421-data-plane.md} §"Discriminated variants within
 * an entry": Java sealed interface + Jackson {@code @JsonTypeInfo}/{@code @JsonSubTypes}.
 *
 * <p>{@link UnknownEventBody} is the registered Jackson default subtype: when a wire
 * payload arrives with a {@code kind} value the current binary doesn't know about, it
 * deserializes into {@code UnknownEventBody} preserving the original kind string and
 * raw payload for diagnostic. This replaces rev-2's {@code agent-session-default} entry
 * (per §B.F) — forward-compat is a protocol concern, not a catalog concern.
 *
 * <p>victools schema generation honors sealed permits + Jackson annotations to produce
 * an {@code anyOf} schema with {@code const}-typed discriminator per the Operation /
 * Resource / Prompt verified pattern in tempdoc 429 §A.3 + §E.10.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "kind",
    visible = true,
    defaultImpl = UnknownEventBody.class)
@JsonSubTypes({
  @JsonSubTypes.Type(value = LifecycleEvent.class, name = "lifecycle"),
  @JsonSubTypes.Type(value = AssertedCondition.class, name = "condition"),
  @JsonSubTypes.Type(value = ThresholdState.class, name = "threshold")
})
public sealed interface HealthEventBody
    permits LifecycleEvent, AssertedCondition, ThresholdState, UnknownEventBody {}
