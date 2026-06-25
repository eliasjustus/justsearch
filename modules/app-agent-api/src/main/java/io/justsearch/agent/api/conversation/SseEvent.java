/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.conversation;

import java.util.Map;
import java.util.Objects;

/**
 * A typed SSE event emitted by the substrate to the response stream.
 *
 * <p>Per tempdoc 491 §5.4 SSE event vocabulary resolution:
 *
 * <ul>
 *   <li>Substrate-emitted events use unprefixed names matching {@code StreamHandlers<TDone>}:
 *       {@code chunk}, {@code meta}, {@code progress}, {@code done}, {@code error}.
 *   <li>Shape-specific events use a {@code <shape-id>.<event>} namespace, e.g.,
 *       {@code tools.tool_call_proposed}, {@code rag.citation_matches},
 *       {@code url.url_extracted}.
 * </ul>
 *
 * <p>The agent-loop's existing event vocabulary ({@code session_started},
 * {@code tool_call_proposed}, {@code budget_update}, {@code handoff_proposed}, etc.) is
 * preserved unchanged for FE compatibility under the encapsulation contract — those events
 * keep their bare names (no {@code agent.} prefix) for now to avoid breaking the FE. Future
 * shapes follow the namespacing convention.
 *
 * @param name the event name (see vocabulary rules above)
 * @param payload the event payload (serialized to the SSE {@code data:} body as JSON)
 */
public record SseEvent(String name, Map<String, Object> payload) {

  public SseEvent {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("SseEvent name must not be blank");
    }
    Objects.requireNonNull(payload, "payload");
  }

  /** Convenience factory for events with no payload data (rare; most events carry data). */
  public static SseEvent of(String name) {
    return new SseEvent(name, Map.of());
  }
}
