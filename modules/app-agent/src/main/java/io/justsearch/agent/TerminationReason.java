/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.AgentErrorCode;
import java.util.Objects;

/**
 * Typed reason a session ended. Persisted in {@code AgentRunStore} {@code meta.json} on terminal
 * checkpoints; supplies the tag schema for {@code agent.session.terminate_total}.
 *
 * <p>{@code disposition} is always present. {@code errorCode} is non-null only when {@code
 * disposition == ERRORED}. {@code cancelTrigger} is non-null only when {@code disposition ==
 * CANCELLED}.
 */
record TerminationReason(
    TerminalDisposition disposition, AgentErrorCode errorCode, CancelTrigger cancelTrigger) {

  TerminationReason {
    Objects.requireNonNull(disposition, "disposition");
  }
}
