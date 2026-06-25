/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Grouped migration status for structured /api/status response.
 */
public record MigrationStatusGroup(
    long observedAtMs,
    String state,
    String buildingGenerationId,
    String previousGenerationId,
    boolean paused,
    String pauseReason,
    long switchingAgeMs,
    long switchingMaxDurationMs,
    MigrationEnumeratorView enumerator,
    String migrationSource) {

  public static MigrationStatusGroup from(WorkerOperationalView w) {
    return new MigrationStatusGroup(
        System.currentTimeMillis(),
        w.migration().migrationState(),
        w.migration().buildingGenerationId(),
        w.migration().previousGenerationId(),
        w.migration().migrationPaused(),
        w.migration().migrationPauseReason(),
        w.migration().migrationSwitchingAgeMs(),
        w.migration().migrationSwitchingMaxDurationMs(),
        w.migration().migrationEnumerator(),
        w.migration().migrationSource());
  }
}
