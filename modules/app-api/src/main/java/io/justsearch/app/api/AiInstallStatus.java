/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 install progress state — package-oriented (not per-asset).
 *
 * <p>Mutable, synchronized externally by the install service. Serialized to JSON for the
 * {@code GET /api/ai/install/status} endpoint and persisted to disk for crash recovery.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.install} to {@code app-api} as part of tempdoc 519 §9
 * Block B2. The {@link AiInstallService} interface returns this type; the DTO must be reachable
 * from {@code app-services} for the interface contract to be honored across module boundaries.
 */
public final class AiInstallStatus {

  // Overall state
  public String state = "idle";
  public String phase = "idle";
  public String message = "";
  public long startedAtEpochMs;
  public long updatedAtEpochMs;
  public boolean cancelRequested;
  public String lastError = "";
  public String errorCode = "";

  // Download profile
  public String downloadProfile = "";

  // Aggregate progress
  public long totalBytes;
  public long downloadedBytes;

  /**
   * True only when state == "completed" AND no packages were skipped/failed
   * AND all required runtime config keys were written. Distinguishes
   * "installed cleanly" from "installed with limitations" without breaking
   * the existing state enum. Tempdoc 374 sandbox round 2 finding #8.
   *
   * <p>When false but state == "completed", the message field describes which
   * limitations apply (e.g. "Installed with limitations: chat (no CUDA).").
   */
  public boolean installedFully;

  // Per-package progress
  public final List<PackageStatus> packages = new ArrayList<>();

  /** Per-package progress tracking. */
  public static final class PackageStatus {
    public String packageId = "";
    public String label = "";
    /**
     * Capability-tier id (tempdoc 657): {@code retrieval-core} | {@code retrieval-enrichment} |
     * {@code llm} | {@code runtime}, or null for an untagged package. Lets the UI group the
     * download by tier (retrieval vs the optional LLM) without hardcoding the package taxonomy.
     */
    public String tier;
    public String state = "pending";
    public long bytesDownloaded;
    public long bytesTotal;
    public String skipReason = "";
    public String error = "";
  }
}
