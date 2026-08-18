/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.app.api.stream.SseEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One frame on a run stream: the wire-projected {@code (name, payload)} pair (tempdoc 834 §1.3.2).
 *
 * <p>The journal carries the projection of the SINGLE authority ({@code AgentEventPayloads}), never
 * a raw {@code AgentEvent} — publishing the typed event into a Jackson-serialized envelope would
 * bypass that authority and re-create the three-way drift it exists to prevent.
 *
 * <p><strong>Why the envelope payload is a Map, not this record.</strong> {@code
 * FrameRetentionSizer} walks maps / iterables / char sequences and charges a flat opaque cost for
 * anything else, so a record-shaped payload would be under-counted by orders of magnitude and the
 * §2 byte bounds would be theatre. {@link #asPayload()} is therefore what reaches the ring, and
 * {@link #from(Object)} reads it back for the evidence classifier and the writer.
 *
 * @param event the SSE event name (e.g. {@code chunk}, {@code rag.citations}, {@code done})
 * @param data the event's JSON body
 */
public record RunFrame(String event, Map<String, Object> data) {

  /** Envelope payload key holding {@link #event()}. */
  public static final String EVENT_KEY = "event";

  /** Envelope payload key holding {@link #data()}. */
  public static final String DATA_KEY = "data";

  public RunFrame {
    Objects.requireNonNull(event, "event");
    if (event.isBlank()) {
      throw new IllegalArgumentException("event must not be blank");
    }
    // Insertion-ordered and null-tolerant on purpose: Map.copyOf would both scramble the wire field
    // order the payload authority establishes and reject a null-valued key it legitimately emits.
    data =
        data == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }

  /** Convenience for a frame with no body. */
  public static RunFrame of(String event) {
    return new RunFrame(event, Map.of());
  }

  /** The map form that is retained in the ring and sized by the retention layer. */
  public Map<String, Object> asPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(EVENT_KEY, event);
    payload.put(DATA_KEY, data);
    return payload;
  }

  /** Reads a frame back out of an envelope payload; empty when the payload is not a run frame. */
  @SuppressWarnings("unchecked")
  public static Optional<RunFrame> from(Object payload) {
    if (!(payload instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    if (!(map.get(EVENT_KEY) instanceof String event) || event.isBlank()) {
      return Optional.empty();
    }
    Object data = map.get(DATA_KEY);
    return Optional.of(
        new RunFrame(event, data instanceof Map<?, ?> body ? (Map<String, Object>) body : Map.of()));
  }

  /** Reads a frame back out of a retained envelope. */
  public static Optional<RunFrame> from(SseEnvelope envelope) {
    return envelope == null ? Optional.empty() : from(envelope.payload());
  }
}
