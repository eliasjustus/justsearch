/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Objects;

/**
 * Normalized intent envelope every entry point produces.
 *
 * <p>Per tempdoc 487 §4.1–§4.3: Java sibling of the TS
 * {@code Intent} type at {@code modules/ui-web/src/shell-v0/router/types.ts}.
 * Every {@link io.justsearch.agent.api.registry.IntentSource} produces an
 * {@code Intent} via its registered {@link io.justsearch.agent.api.registry.IntentExtractor};
 * the per-process {@code IntentRouter} (FE {@code intentRouter.ts} or backend
 * {@code BackendIntentRouter}) is the single consumer.
 *
 * <p>The {@link #address} is the structured target (Navigation surface or
 * Invocation operation, partitioned per {@link ShellAddress}). The
 * {@link #transport} is the source's wire identity (URL_BAR, LLM_EMISSION,
 * AGENT_LOOP, etc.). Provenance metadata (executor, initiator, occurredAt)
 * is carried alongside as a separate {@link InvocationProvenance} record
 * that the dispatcher consumes — keeping {@code Intent} itself minimal and
 * mirror-shape with the TS interface.
 *
 * <p>Stateless. Pure value. Safe to pass across module boundaries.
 */
public record Intent(ShellAddress address, TransportTag transport) {

  public Intent {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(transport, "transport");
  }
}
