/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Return value of a {@link StreamConsumer} invocation.
 *
 * <p>Per tempdoc 491 §5.1 (data-flow audit) + §C2.0 (substrate enhancement E1): a stream
 * consumer reports four kinds of outputs to the engine:
 *
 * <ul>
 *   <li>{@link #events} — SSE events to emit to the wire. These appear in the consumer's
 *       declaration order; the engine writes them to the SSE stream in order.
 *   <li>{@link #sideEffectsExecuted} — opaque records of any side effects this consumer
 *       performed (operation dispatch, approval gate registration, URL navigation, etc.).
 *       The engine treats these as audit records; it does not interpret them.
 *   <li>{@link #messageDeltas} — messages to append to the model's working message list
 *       before the next iteration. This is load-bearing: tool execution produces the
 *       tool-result messages that the next LLM call must see, otherwise the agent's
 *       closed loop breaks.
 *   <li>{@link #donePayloadEntries} — entries to merge into the substrate-emitted {@code done}
 *       SSE event's payload. Returned by the final iteration's {@code onDone} call. Merged
 *       in consumer declaration order; the substrate's defaults ({@code finalResponse},
 *       {@code iterationsUsed}) cannot be overridden — engine writes them last.
 * </ul>
 *
 * <p>When multiple stream consumers run on the same iteration, their {@code messageDeltas}
 * compose in declaration order; the engine appends them to the message list before invoking
 * {@link IterationController#next}.
 *
 * @param events SSE events to emit (in order)
 * @param sideEffectsExecuted opaque audit records of side effects performed
 * @param messageDeltas messages to append before the next iteration
 * @param donePayloadEntries entries merged into the substrate's {@code done} payload (only
 *     consulted from the final iteration's {@code onDone})
 */
public record StreamConsumerResult(
    List<SseEvent> events,
    List<Map<String, Object>> sideEffectsExecuted,
    List<Map<String, Object>> messageDeltas,
    Map<String, Object> donePayloadEntries) {

  public StreamConsumerResult {
    events = events == null ? List.of() : List.copyOf(events);
    sideEffectsExecuted =
        sideEffectsExecuted == null ? List.of() : List.copyOf(sideEffectsExecuted);
    messageDeltas = messageDeltas == null ? List.of() : List.copyOf(messageDeltas);
    donePayloadEntries =
        donePayloadEntries == null ? Map.of() : Map.copyOf(donePayloadEntries);
  }

  /** Convenience: an empty result. */
  public static StreamConsumerResult empty() {
    return new StreamConsumerResult(List.of(), List.of(), List.of(), Map.of());
  }

  /** Convenience: a result that emits events only. */
  public static StreamConsumerResult eventsOnly(List<SseEvent> events) {
    Objects.requireNonNull(events, "events");
    return new StreamConsumerResult(events, List.of(), List.of(), Map.of());
  }

  /** Convenience: a result that contributes only to the final {@code done} payload. */
  public static StreamConsumerResult donePayloadOnly(Map<String, Object> entries) {
    Objects.requireNonNull(entries, "entries");
    return new StreamConsumerResult(List.of(), List.of(), List.of(), entries);
  }
}
