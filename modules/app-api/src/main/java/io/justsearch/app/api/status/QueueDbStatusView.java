/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

public record QueueDbStatusView(
    boolean queueDbHealthy,
    long queueDbLastBackupAtMs,
    long queueDbLastQuickCheckAtMs,
    boolean queueDbLastQuickCheckOk,
    long queueDbLastErrorAtMs) {
  public static QueueDbStatusView healthy() {
    return new QueueDbStatusView(true, 0, 0, true, 0);
  }
}
