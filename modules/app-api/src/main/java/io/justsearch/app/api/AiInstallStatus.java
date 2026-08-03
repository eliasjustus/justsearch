/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 install progress state — package-oriented (not per-asset).
 *
 * <p>Mutable, synchronized externally by the install service. Serialized to JSON for the
 * {@code GET /api/ai/install/status} endpoint. This object is <em>session-ephemeral</em> — it is
 * NOT persisted to disk. After a restart, "is installed" is recomputed from on-disk model presence
 * by {@code AiInstallService.maybeRecomputeInstalledFromDisk} (tempdoc 562): install state is a
 * function of what is on disk, not a remembered event.
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
   * Bytes an interrupted earlier run left staged in {@code .partial} files that a resume will keep.
   *
   * <p>Deliberately NOT derived from this object's own {@code state}: {@code state == "cancelled"}
   * is session-ephemeral (see the class note above) and reads {@code "idle"} again after a restart,
   * so a UI keyed on it would tell a returning user their multi-GB progress is gone. This field is
   * recomputed from DISK by {@code InstallPlanner} (which probes the {@code .partial} staging paths)
   * whenever the plan is re-derived — at boot, on cancellation, and on every plan preview — so it
   * survives a restart the way the bytes themselves do.
   */
  public long resumableBytes;

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

  /**
   * Package ids the CURRENT registry declares but the install contract that recorded this
   * installation never covered — i.e. artifacts a NEWER app version added after the user installed
   * (tempdoc 804 §B8, round-10 F2: one new cuda-runtime package made a complete 16 GB installation
   * read {@code installedFully: false, packages: []} until a full re-run).
   *
   * <p>Completeness is a claim about the contract that installed it, so a registry addition is a
   * distinct state — "extra AI components are available" — not retroactive non-installation.
   * Empty whenever the plan has nothing left to download (the ordinary complete install) and after
   * any install run terminates.
   */
  public final List<String> pendingRegistryAdditions = new ArrayList<>();

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

    /**
     * True when at least one of this package's files continued a download an earlier run had left
     * on disk (an HTTP {@code Range} resume or a resumed BITS job) rather than starting from byte
     * zero. Projects {@code ResumableFetch.Outcome.firstAction} so the UI can tell a returning user
     * their cancelled progress was kept — the reassurance the resumable-cancel work earns but which
     * is otherwise invisible.
     */
    public boolean resumed;
  }
}
