/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

/**
 * How hard a repair pass tries, as a function of how many passes already failed the same file
 * (tempdoc 824 §3.4).
 *
 * <p>{@code repair()} is literally {@code startInstall()} ({@link AiInstallService#repair}), so
 * before this every pass ran the identical transport with identical odds: round 16's user clicked
 * Repair four times and got the same BITS-then-curl pair inside the same seconds-long degraded
 * window each time. Pass <em>n</em> therefore starts at transport tier <em>n</em> — a wedged file
 * meets a different transport each pass instead of the same one.
 *
 * <p><b>Tier meaning</b> (the §3.1 ladder, owned by {@code ResumableFetch}/{@code
 * DownloadExecutor}): 0 = BITS then curl (today's behaviour), 1 = curl only, 2 = curl only with
 * {@code --http1.1}, 3 = curl only. Tiers saturate at {@link #MAX_TIER}; a fifth pass repeats the
 * last rung rather than falling off the ladder.
 *
 * <p><b>Wiring status.</b> The tier is computed, persisted ({@link InstallAttemptMemory}) and
 * logged here; the {@code int startTier} parameter on {@code ResumableFetch.fetch} that consumes it
 * lands with the transport-retry work (§3.1/§3.2). This class is that seam's adapter, so the
 * consuming change is one argument at the single call site rather than a second derivation.
 */
public final class TransportEscalation {

  /** Highest rung on the transport ladder. */
  public static final int MAX_TIER = 3;

  private TransportEscalation() {}

  /**
   * The transport tier a pass should start at, given how many earlier passes failed this file.
   *
   * @param failedPasses consecutive passes that already failed this file at transport ({@code 0}
   *     for a file with no failure history — the ordinary first install)
   * @return a tier in {@code [0, MAX_TIER]}
   */
  public static int startTier(int failedPasses) {
    if (failedPasses <= 0) {
      return 0;
    }
    return Math.min(failedPasses, MAX_TIER);
  }
}
