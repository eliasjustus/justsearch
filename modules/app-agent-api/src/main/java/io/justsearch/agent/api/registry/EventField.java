/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * One payload field of a {@link ConversationShape} SSE event: the wire field name, its
 * {@link EventFieldType}, and whether it may be absent on the wire.
 *
 * <p>Part of tempdoc 564 (facet 4b). The typed descriptor replaces the name-only
 * {@code List<String>} event schema so the FE handler payload type is a generated projection of
 * this declaration — not a hand-cast off {@code unknown} (the drift class measured in 563 §9 and
 * the agent-event contract de-risk pass). The declaration is bound to the actual producer by a
 * conformance test, so it cannot drift from what the backend emits.
 *
 * @param name wire field name (camelCase, matching the producer's payload key)
 * @param type the field's wire type
 * @param optional {@code true} if the field may be absent (the producer elides it conditionally)
 * @param enumValues for {@link EventFieldType#ENUM}, the closed set of lowercase string values
 * @param elementType for {@link EventFieldType#ARRAY}, the element type ({@code null} otherwise)
 * @param objectType for {@link EventFieldType#OBJECT} (or an ARRAY of objects), the name of the
 *     shared FE interface the codegen references ({@code null} otherwise)
 */
public record EventField(
    String name,
    EventFieldType type,
    boolean optional,
    List<String> enumValues,
    EventFieldType elementType,
    String objectType) {

  public EventField {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
  }

  /** A required field of a scalar type ({@code STRING} / {@code NUMBER} / {@code BOOLEAN}). */
  public static EventField of(String name, EventFieldType type) {
    return new EventField(name, type, false, List.of(), null, null);
  }

  public static EventField string(String name) {
    return of(name, EventFieldType.STRING);
  }

  public static EventField number(String name) {
    return of(name, EventFieldType.NUMBER);
  }

  public static EventField bool(String name) {
    return of(name, EventFieldType.BOOLEAN);
  }

  /** A required ENUM field constrained to {@code values} (lowercase wire strings). */
  public static EventField enumOf(String name, List<String> values) {
    return new EventField(name, EventFieldType.ENUM, false, values, null, null);
  }

  /** A required ARRAY-of-object field whose elements have FE interface {@code objectType}. */
  public static EventField arrayOfObject(String name, String objectType) {
    return new EventField(name, EventFieldType.ARRAY, false, List.of(), EventFieldType.OBJECT, objectType);
  }

  /** A required OBJECT field whose FE interface is {@code objectType}. */
  public static EventField object(String name, String objectType) {
    return new EventField(name, EventFieldType.OBJECT, false, List.of(), null, objectType);
  }

  /** This field, marked optional (may be absent on the wire). */
  public EventField asOptional() {
    return new EventField(name, type, true, enumValues, elementType, objectType);
  }
}
