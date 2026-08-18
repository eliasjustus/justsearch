/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.util.Objects;

/**
 * What a run IS, independent of what it has emitted (tempdoc 834 §1.5).
 *
 * <p>Carried on the {@code run_started} lifecycle frame (§3.2) and projected by the live-run
 * enumeration (§5.1). {@code conversationId} may be blank: a run started outside a conversation
 * (an OPERATOR-audience dispatch, a workflow) belongs to no thread, and blank says so honestly
 * rather than inventing an id.
 *
 * @param shapeId the {@code ConversationShapeRef} value, or the agent shape id
 * @param conversationId the conversation this run answers into; blank when it answers into none
 * @param startedAtEpochMs when the run was opened
 */
public record RunDescriptor(String shapeId, String conversationId, long startedAtEpochMs) {

  public RunDescriptor {
    Objects.requireNonNull(shapeId, "shapeId");
    if (shapeId.isBlank()) {
      throw new IllegalArgumentException("shapeId must not be blank");
    }
    conversationId = conversationId == null ? "" : conversationId;
  }
}
