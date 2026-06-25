/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Return value of a {@link ContextInjector#inject} invocation.
 *
 * <p>Per tempdoc 491 §C2.0 (substrate enhancement E2): injectors do more than return data —
 * they may emit SSE events during their work (e.g., {@code rag.meta} from RAG retrieval,
 * {@code progress} from batch loading) and may signal a terminal error that aborts the
 * conversation before any LLM call (e.g., missing required body fields like {@code question}
 * or {@code docIds}).
 *
 * <p>The engine forwards {@link #events} to the sink in order, before assembling the LLM input.
 * If {@link #terminalError} is present, the engine emits the error event and returns without
 * invoking the LLM or downstream consumers.
 *
 * @param messages messages to prepend to the model's working list (OpenAI shape)
 * @param events SSE events to emit during injection (in order)
 * @param terminalError if present, the engine aborts and emits this event instead of running
 *     the LLM
 */
public record InjectorResult(
    List<Map<String, Object>> messages,
    List<SseEvent> events,
    Optional<SseEvent> terminalError) {

  public InjectorResult {
    messages = messages == null ? List.of() : List.copyOf(messages);
    events = events == null ? List.of() : List.copyOf(events);
    terminalError = terminalError == null ? Optional.empty() : terminalError;
  }

  /** Convenience: empty result — no messages, no events, no error. */
  public static InjectorResult empty() {
    return new InjectorResult(List.of(), List.of(), Optional.empty());
  }

  /** Convenience: messages only — no events, no error. */
  public static InjectorResult messagesOnly(List<Map<String, Object>> messages) {
    Objects.requireNonNull(messages, "messages");
    return new InjectorResult(messages, List.of(), Optional.empty());
  }

  /** Convenience: events alongside messages. */
  public static InjectorResult of(
      List<Map<String, Object>> messages, List<SseEvent> events) {
    Objects.requireNonNull(messages, "messages");
    Objects.requireNonNull(events, "events");
    return new InjectorResult(messages, events, Optional.empty());
  }

  /**
   * Convenience: a terminal injector failure. Engine emits the error event and aborts the
   * conversation without invoking the LLM. Used for missing required body fields and other
   * fail-fast preconditions evaluated during injection.
   */
  public static InjectorResult terminalError(SseEvent error) {
    Objects.requireNonNull(error, "error");
    return new InjectorResult(List.of(), List.of(), Optional.of(error));
  }
}
