/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single readiness component within the readiness envelope.
 *
 * <p>Stability: stable (API contract)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadinessComponentView(
    String state, String reasonCode, String source, String observedAt, boolean stale,
    long stalenessMs) {}
