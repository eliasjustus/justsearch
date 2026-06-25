/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Migration enumerator sub-view for the debug worker state (snake_case JSON names).
 *
 * <p>Stability: internal (debug endpoint only)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DebugMigrationEnumeratorView(
    boolean running,
    boolean done,
    long rootsTotal,
    long rootsDone,
    long filesSeen,
    long filesEnqueued,
    long startedAtMs,
    long finishedAtMs,
    String lastPath) {

  public DebugMigrationEnumeratorView {
    lastPath = lastPath == null ? "" : lastPath;
  }
}
