/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Phase 5a of slice 3a-1-8: per-envelope contract version tagging — opt-in
 * diagnostic helper.
 *
 * Per slice 3a-1-8e (rewrite, 2026-05-07): this helper is preserved as an
 * opt-in diagnostic affordance for streams that want self-describing frames
 * (replay logs, trace export, integration test fixtures). It is NOT the
 * substrate's primary runtime-continuous negotiation mechanism. The
 * substrate's runtime-continuous commitment is discharged at the Resource
 * layer via mid-session evolution events on `/infra/capabilities/stream`
 * (see `10-kernel/05-contract-substrate.md` §"Runtime Negotiation" +
 * `50-decisions/09-contract-substrate.md` §"Why runtime negotiation lives
 * at the Resource layer").
 *
 * Endpoints that opt in to per-frame self-description wrap their payload
 * via {@link #tagPayload(Map)} before passing to the SSE envelope writer.
 * Other endpoints' behavior is unchanged. Consumers of the diagnostic tag
 * read it via {@link #readContractVersion(Map)} for trace correlation;
 * mid-session contract evolution is handled by Resource-layer subscription,
 * not by per-envelope inspection.
 */
package io.justsearch.app.api.stream;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Static helpers for tagging SSE payloads with the wire-Category contract version.
 *
 * <p>The current contract version is sourced from {@code contracts/wire/VERSION}; this
 * class exposes it as a compile-time constant. Producers that opt in to per-envelope
 * tagging call {@link #tagPayload(Map)} to wrap their payload object with a
 * {@code "contractVersion"} field.
 *
 * <p>Consumers (FE-side or alternative-language) that need to detect cross-version
 * reads inspect the payload's {@code "contractVersion"} field and degrade per the
 * LSP soft-fail discipline ({@code 50-decisions/04-backend-owned-truth.md}).
 *
 * <p>This is an opt-in diagnostic helper, not the substrate's runtime-continuous
 * negotiation surface. The runtime-continuous surface is the Resource-layer
 * mid-session evolution channel on {@code /infra/capabilities/stream}; per slice
 * 3a-1-8e (rewrite, 2026-05-07), per-envelope tagging is preserved for diagnostic
 * uses (replay logs, trace export, integration test fixtures) but is not the
 * substrate's primary mechanism for mid-session contract negotiation.
 */
public final class WireContractVersion {

  /**
   * Current wire-Category contract version.
   *
   * <p>Sourced from {@code contracts/wire/VERSION}. Any wire-format-relevant change
   * to the contract spec advances this version per the substrate's evolution rules
   * (additive optional = patch; additive required = minor; rename/remove = major).
   * Manual CHANGELOG enforcement at {@code contracts/wire/CHANGELOG.md} is the V1
   * gate; mechanical structural-diff is the V1.5 follow-up
   * ({@code slices/3a-1-8f-governance-runtime.md}).
   */
  public static final String CURRENT = "0.2.0";

  /** Field name on the SSE envelope's payload map. */
  public static final String FIELD_NAME = "contractVersion";

  private WireContractVersion() {}

  /**
   * Wraps a payload map with the current contract version. Returns a NEW map; does
   * not mutate the input. Insertion order is preserved (LinkedHashMap), with
   * {@link #FIELD_NAME} as the first key.
   *
   * <p>Producers that opt in:
   *
   * <pre>{@code
   * Map<String, Object> tagged = WireContractVersion.tagPayload(rawPayload);
   * sseEnvelope.payload(tagged);
   * }</pre>
   *
   * <p>Producers that don't opt in: their payloads are emitted unchanged. FE
   * consumers ignore an absent {@code contractVersion} field per the substrate's
   * forward-compat passthrough rule.
   */
  public static Map<String, Object> tagPayload(Map<String, Object> payload) {
    Objects.requireNonNull(payload, "payload");
    Map<String, Object> tagged = new LinkedHashMap<>();
    tagged.put(FIELD_NAME, CURRENT);
    tagged.putAll(payload);
    return tagged;
  }

  /**
   * Reads the contract version from a payload map, if present.
   *
   * @return the version string, or {@link java.util.Optional#empty()} if the field is
   *     absent (consumer should treat as "not opted in"; falls back to handshake's
   *     reported version).
   */
  public static java.util.Optional<String> readContractVersion(Map<String, Object> payload) {
    Object value = payload.get(FIELD_NAME);
    return value instanceof String s ? java.util.Optional.of(s) : java.util.Optional.empty();
  }
}
