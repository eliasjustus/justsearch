/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-install, side-effect-free projection of the download plan, grouped by capability tier
 * (tempdoc 657). Served at {@code GET /api/ai/install/plan-preview} so the UI can show an honest
 * first-run weight breakdown — "Core retrieval (already installed)" vs "Chat &amp; AI answers — 6.4 GB
 * to download" — <em>before</em> the user commits, instead of a single opaque "several GB" string.
 *
 * <p>Realizes tempdoc 381 §F ("present the plan to the user before download") by reusing the pure
 * {@code InstallPlanner}; computing it runs no downloads.
 *
 * <p>Mutable plain DTO, serialized to JSON (no schema/codegen — the FE reads it untyped).
 */
public final class InstallPlanPreview {

  /** The active {@code InstallIntent} id ({@code full-desktop} | {@code headless} | {@code mcp-lite}). */
  public String intent = "";

  /** The hardware-selected {@code DownloadProfile} name (e.g. {@code GPU_FULL}). */
  public String downloadProfile = "";

  /**
   * Bytes that still have to come over the network across all wanted tiers on this hardware —
   * {@code InstallPlan.remainingBytes()}, i.e. already-complete files excluded AND bytes an earlier
   * interrupted run left staged in {@code .partial} files excluded. The number a consent surface
   * must state, because it is what the download will actually cost.
   */
  public long totalDownloadBytes;

  /**
   * Of the planned downloads, bytes already on disk from an interrupted earlier run that a resume
   * will keep. Non-zero means "there is a paused download here"; the pause promise ("everything
   * already downloaded stays on disk") is only checkable by the user if this is stated.
   */
  public long resumableBytes;

  /** Per-tier estimates, in canonical tier order. */
  public final List<TierEstimate> tiers = new ArrayList<>();

  /** One capability tier's weight estimate. */
  public static final class TierEstimate {
    /** Tier id ({@code retrieval-core} | {@code retrieval-enrichment} | {@code llm} | {@code runtime}). */
    public String tier = "";

    /** Human-readable tier label. */
    public String label = "";

    /** Whether the active intent wants this tier at all (false ⇒ excluded by mode). */
    public boolean includedByIntent;

    /** Full footprint of this tier's packages at the selected profile (informational). */
    public long totalBytes;

    /** Bytes still to download for this tier ({@code 0} ⇒ already present / not applicable). */
    public long downloadBytes;
  }
}
