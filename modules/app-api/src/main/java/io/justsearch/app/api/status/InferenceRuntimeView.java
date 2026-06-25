/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Inference runtime status sub-view for {@code /api/status}. Tempdoc 412 replaced the prior
 * {@code LlmStatusView} + {@code OnlineAiView} pair with this single record; the tempdoc 412
 * follow-up dropped the {@code queue} and {@code generation} sub-records (no scraper exists
 * to populate them) — they will return when a Prometheus {@code /metrics} scraper is wired
 * (the {@code --metrics} flag is already enabled on the llama-server launch).
 *
 * <h3>Phase values</h3>
 *
 * <ul>
 *   <li>{@code OFFLINE} — no llama-server is running; {@link #identity} is null.
 *   <li>{@code TRANSITIONING} — holder is mid-swap; identity is null.
 *   <li>{@code ONLINE} — llama-server is running, request lock is open. The
 *       {@code schema.vduMode} flag distinguishes normal vs VDU sub-modes.
 *   <li>{@code INDEXING} — GPU yielded to worker; no llama-server.
 * </ul>
 *
 * <p>Stability: stable (API contract)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InferenceRuntimeView(
    String phase,
    RuntimeIdentityView identity,
    boolean usingExternal,
    InferenceFailureView lastFailure,
    LifecycleCounters counters) {

  public InferenceRuntimeView {
    phase = phase == null ? "OFFLINE" : phase;
  }
}
