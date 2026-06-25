/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

import java.util.Locale;

/**
 * Tag value classifying the trigger for a CPU session recreation. Exposed via
 * {@code ort.session.recovery_total{cause=...}}. Mirrors the F-009 NaN-on-CPU-OOM pathway and
 * the BFCArena-failure recovery branch (see {@code NativeSessionHandle.reportCpuSessionFailure}).
 */
public enum CpuRecreateCause {
  /** BFCArena allocation failure detected via {@code NativeSessionHandle.isBfcArenaFailure}. */
  BFC_ARENA_FAILURE,
  /** Caller reported a CPU session failure but couldn't classify the cause. */
  REPORTED_FAILURE,
  /** Default when no cause is supplied (e.g., test harnesses). */
  UNKNOWN;

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
