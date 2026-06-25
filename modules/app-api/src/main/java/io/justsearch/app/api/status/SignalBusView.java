/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Signal bus timestamps sub-view for the debug worker state.
 *
 * <p>Stability: internal (debug endpoint only)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SignalBusView(long lastActivityTs, long lastHeartbeatTs) {}
