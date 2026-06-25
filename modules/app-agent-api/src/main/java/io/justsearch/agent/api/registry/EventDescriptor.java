/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Typed descriptor of one SSE event a {@link ConversationShape} emits: the wire event name plus
 * its ordered payload {@link EventField}s.
 *
 * <p>Part of tempdoc 564 (facet 4b). Replaces the name-only entry of the former
 * {@code List<String>} event schema. The set of descriptors a shape declares is the wire contract
 * for that shape's SSE stream; a conformance test binds it to the actual producer so it is a
 * projection, not a second authority that can drift.
 *
 * <p>Every event may additionally carry the shared cross-cutting {@code trace} envelope (the
 * producer appends it uniformly when the trace has identity). It is modeled once as
 * {@link #TRACE_FIELD} rather than repeated on every descriptor.
 *
 * @param name wire event name (e.g. {@code "tool_call_pending"})
 * @param fields ordered payload fields (excluding the shared {@link #TRACE_FIELD})
 */
public record EventDescriptor(String name, List<EventField> fields) {

  /**
   * The optional cross-cutting trace envelope present on every agent-loop event (appended by the
   * producer when {@code TraceContext.hasIdentity()}). FE codegen references the shared
   * {@code TracePayload} interface.
   */
  public static final EventField TRACE_FIELD = EventField.object("trace", "TracePayload").asOptional();

  public EventDescriptor {
    Objects.requireNonNull(name, "name");
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public static EventDescriptor of(String name, EventField... fields) {
    return new EventDescriptor(name, List.of(fields));
  }

  /** Just the event name with no declared payload fields (e.g. a bare signal). */
  public static EventDescriptor nameOnly(String name) {
    return new EventDescriptor(name, List.of());
  }

  /**
   * A descriptor whose fields additionally carry the shared optional {@link #TRACE_FIELD}
   * envelope. Used by agent-loop events, every one of which the producer may trace-wrap.
   */
  public static EventDescriptor ofTraced(String name, EventField... fields) {
    var all = new java.util.ArrayList<EventField>(List.of(fields));
    all.add(TRACE_FIELD);
    return new EventDescriptor(name, all);
  }

  /**
   * Maps a list of bare event names to name-only descriptors. Transitional bridge for shapes
   * whose payload fields are not yet typed (filled in tempdoc 564 Phase 2); keeps the typed
   * event-schema model uniform across all shapes from day one.
   */
  public static List<EventDescriptor> namesOnly(List<String> names) {
    return names.stream().map(EventDescriptor::nameOnly).toList();
  }
}
