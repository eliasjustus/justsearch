/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.inference;

import java.util.Map;

/**
 * Top-level response shape for {@code GET /api/inference/encoders} (tempdoc 422).
 *
 * <p>{@code encoders} is keyed by {@code EncoderRole.consumerName()} (lowercase short identifier:
 * {@code "embed"}, {@code "bgem3"}, {@code "splade"}, {@code "ner"}, {@code "reranker"},
 * {@code "citation"}). This matches the {@code consumer} tag values used by tempdoc 414's
 * {@code ort.session.*} metrics, so operators can correlate this endpoint's output with
 * {@code metrics-worker.ndjson} lines without an additional translation table.
 *
 * <p>Only encoders present in the active {@code PolicySnapshot} appear in the map (e.g. when
 * BGE-M3 is active, SPLADE is absent because they're mutually exclusive at runtime).
 *
 * <p>{@code snapshotStatus} values:
 * <ul>
 *   <li>{@code "ok"} — Worker reachable, policies snapshot present, encoders populated.</li>
 *   <li>{@code "worker-unreachable"} — Head couldn't reach the Worker (RPC failure, late-bind
 *       not yet flipped, etc.).</li>
 *   <li>{@code "policy-unavailable"} — Worker reachable but PolicySnapshot empty (boot
 *       in-progress).</li>
 * </ul>
 */
public record EncoderRuntimeResponse(
    Map<String, EncoderRuntimeView> encoders,
    String snapshotStatus) {

  public EncoderRuntimeResponse {
    encoders = encoders == null ? Map.of() : Map.copyOf(encoders);
    snapshotStatus = snapshotStatus == null ? "" : snapshotStatus;
  }
}
