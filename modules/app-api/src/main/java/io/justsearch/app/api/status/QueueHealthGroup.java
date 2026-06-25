/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

/**
 * Grouped queue DB health status for structured /api/status response.
 */
public record QueueHealthGroup(
    long observedAtMs,
    boolean healthy,
    long lastBackupAtMs,
    long lastQuickCheckAtMs,
    boolean lastQuickCheckOk,
    long lastErrorAtMs) {

  public static QueueHealthGroup from(WorkerOperationalView w) {
    return new QueueHealthGroup(
        System.currentTimeMillis(),
        w.queueDb().queueDbHealthy(),
        w.queueDb().queueDbLastBackupAtMs(),
        w.queueDb().queueDbLastQuickCheckAtMs(),
        w.queueDb().queueDbLastQuickCheckOk(),
        w.queueDb().queueDbLastErrorAtMs());
  }
}
