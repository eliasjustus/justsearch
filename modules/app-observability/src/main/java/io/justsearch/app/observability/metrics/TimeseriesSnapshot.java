/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.metrics;

import io.justsearch.agent.api.registry.ResourceRef;
import java.time.Instant;
import java.util.Objects;

/**
 * Wire payload for a sliding-window numeric metric.
 *
 * <p>Per slice 3a.1.4 §B.4 (TIMESERIES Resource Category substrate): the canonical wire
 * shape for {@link io.justsearch.agent.api.registry.Category#TIMESERIES TIMESERIES}
 * Resources. Receiver semantics is "snapshot-of-window with regular cadence" —
 * subscribers get the current N samples in one frame, not a stream of discrete events.
 * This contrasts with EVENT_STREAM (which accumulates events) and STATE (which carries a
 * single current value).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #resourceId}: matches the Resource registry entry's
 *       {@link io.justsearch.agent.api.registry.Resource#id() id}. Allows multiplexed
 *       streams to disambiguate.
 *   <li>{@link #windowMs}: total window length in milliseconds (e.g. 30 * 60 * 1000 for a
 *       30-minute trend).
 *   <li>{@link #sampleIntervalMs}: regular sample cadence in milliseconds (e.g. 30 * 1000
 *       for 30-second samples). The {@link #values} array length is implicitly
 *       {@code floor(windowMs / sampleIntervalMs)}.
 *   <li>{@link #unit}: free-form unit label ("count", "rate/s", "bytes", "%", etc.). V1
 *       does not enforce a vocabulary; FE renderers display verbatim.
 *   <li>{@link #values}: the sliding-window samples, ordered oldest-first. Defensive copy
 *       in compact constructor to immutability — but note Java arrays are not deeply
 *       immutable; consumers should not mutate.
 *   <li>{@link #startedAt} / {@link #endedAt}: window boundary timestamps.
 *   <li>{@link #catalogVersion}: monotonic version counter for SSE clients to detect
 *       missed frames (matches the {@code seq} on the universal envelope per slice 436).
 * </ul>
 *
 * <p>Wire format: camelCase Java fields, no {@code @JsonProperty} annotations. Mirrors
 * the FE-consumed record convention per ADR-08 §B.B + 3a.1.3 §B.B. typescript-generator
 * emits this as a TS interface with all-fields-optional; FE consumers narrow at the
 * boundary (e.g., {@code if (!snapshot.values || snapshot.values.length < 2) return;}).
 *
 * <p>Generator-emission realities:
 *
 * <ul>
 *   <li>{@link Instant} fields ({@code startedAt}, {@code endedAt}) emit as
 *       {@code DateAsString} (alias for {@code string}) per
 *       {@code WireTypesTsGenerationTest}'s {@code mapDate = asString} setting. FE
 *       receives ISO-8601 strings.
 *   <li>{@link ResourceRef} emits as {@code string} via {@code customTypeMappings}.
 * </ul>
 */
public record TimeseriesSnapshot(
    ResourceRef resourceId,
    long windowMs,
    long sampleIntervalMs,
    String unit,
    double[] values,
    Instant startedAt,
    Instant endedAt,
    long catalogVersion) {

  public TimeseriesSnapshot {
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(values, "values");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(endedAt, "endedAt");
    if (windowMs < 0) {
      throw new IllegalArgumentException("windowMs must be >= 0, got " + windowMs);
    }
    if (sampleIntervalMs <= 0) {
      throw new IllegalArgumentException("sampleIntervalMs must be > 0, got " + sampleIntervalMs);
    }
    // Defensive copy — Java arrays are mutable; the wire payload must not be mutable
    // post-construction.
    values = values.clone();
  }
}
