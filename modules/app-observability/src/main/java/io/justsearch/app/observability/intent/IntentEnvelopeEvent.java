/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.intent;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentSourceRef;
import io.justsearch.agent.api.registry.InvocationProvenance;
import java.util.Objects;

/**
 * Wire-format record for a single intent envelope broadcast on {@code /api/intent/stream}.
 *
 * <p>Per tempdoc 487 §4.3: the payload carried as the {@code SseEnvelope.payload} for
 * each {@code UPDATE} frame on the always-on intent stream. The FE
 * {@code EnvelopeStream} subscriber receives this, dedups by {@link #id} against a
 * bounded LRU, and dispatches the underlying {@link Intent} into the existing FE
 * {@code IntentRouter.dispatch}.
 *
 * <p>Wire payload structure (per tempdoc §4.3):
 *
 * <pre>{@code
 * {
 *   "kind": "intent.envelope",
 *   "id": "ie-7f8a2c-...",
 *   "intent": { "address": {...}, "transport": "LLM_EMISSION" },
 *   "provenance": { "transport": "...", "executor": "...", "initiator": "...", "occurredAt": "..." },
 *   "sourceId": "core.llm-chat-emission"
 * }
 * }</pre>
 *
 * <p>The {@link #id} is the load-bearing idempotency key. Replay-on-reconnect via the
 * slice-436 ring buffer means the same envelope can be re-delivered after a network
 * blip; the FE's LRU dedup gates on this stable id to prevent double-dispatch (e.g.,
 * double-firing a destructive operation).
 *
 * <p>The {@code "kind"} field is the discriminator the FE reducer reads to route the
 * payload to the intent-envelope handler. Future event-only streams on the same
 * transport (e.g., a hypothetical {@code action.envelope}) would distinguish by this
 * field — the streamId alone is not sufficient because future plugin-contributed
 * intent shapes might share the channel.
 */
public record IntentEnvelopeEvent(
    String id, Intent intent, InvocationProvenance provenance, IntentSourceRef sourceId) {

  /** Constant wire-discriminator value. */
  public static final String KIND = "intent.envelope";

  public IntentEnvelopeEvent {
    Objects.requireNonNull(id, "id");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must be non-blank");
    }
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(sourceId, "sourceId");
  }

  /**
   * Jackson-serialized {@code "kind"} field. The constant value is wire-load-bearing:
   * the FE reducer routes envelopes by this discriminator.
   */
  @JsonProperty("kind")
  public String kind() {
    return KIND;
  }
}
