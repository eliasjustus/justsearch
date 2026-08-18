/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The primer a (re)attaching observer receives BEFORE the replay (tempdoc 834 §6.1).
 *
 * <p><strong>The law this type serves:</strong> every fact required to ACT on a run lives in the
 * snapshot; the ring carries narrative only. The ring evicts oldest, so a run that parks at an
 * approval gate after thousands of frames can no longer replay the {@code tool_call_pending} frame
 * that carried the {@code callId} — a reattacher would see a stopped run with no gate to answer.
 *
 * <p>This is a PROJECTION of the one canonical snapshot ({@code AgentEvent.StateSnapshot} via
 * {@code AgentEventPayloads.base}), deliberately held as its already-projected field map rather
 * than re-typed here: a second typed copy in this module would be exactly the fork
 * {@code AgentEventPayloads} exists to prevent, and this module must not grow an agent vocabulary.
 *
 * @param fields the canonical snapshot payload
 */
public record RunStateSnapshot(Map<String, Object> fields) {

  public RunStateSnapshot {
    Objects.requireNonNull(fields, "fields");
    // Insertion-ordered and null-tolerant on purpose: Map.copyOf would both scramble the wire field
    // order the payload authority establishes and reject a null-valued key it legitimately emits.
    fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
  }

  /** The snapshot as a run frame, so it rides the same wire vocabulary as everything else. */
  public RunFrame asFrame(String event) {
    return new RunFrame(event, fields);
  }
}
