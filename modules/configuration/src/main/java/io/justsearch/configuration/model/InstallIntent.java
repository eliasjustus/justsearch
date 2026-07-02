/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Install/runtime intent — the product-shape axis, orthogonal to hardware (tempdoc 657).
 *
 * <p>Where {@link DownloadProfile} answers "what can this machine run," intent answers "what is this
 * install <em>for</em>." It selects which {@link CapabilityTier}s are wanted; the planner then includes
 * a package iff its tier is wanted <em>and</em> hardware permits its variant. Intent is a single
 * launch-time config value ({@code -Djustsearch.mode} / {@code JUSTSEARCH_MODE}, via
 * {@link EnvRegistry#MODE}) read by both the install planner and the runtime manifest publisher — one
 * source, so the advertised mode never drifts from what was installed.
 *
 * <ul>
 *   <li>{@link #FULL_DESKTOP} — the complete private assistant (all tiers). The default when unset, so
 *       existing desktop installs are unchanged.
 *   <li>{@link #HEADLESS} — the backend as a local service (all tiers, no desktop shell). Differs from
 *       Full Desktop by launcher, not by model packs.
 *   <li>{@link #MCP_LITE} — fast retrieval for agent developers: retrieval tiers only, no LLM.
 * </ul>
 *
 * <p>The retrieval tiers ({@link CapabilityTier#RETRIEVAL_CORE},
 * {@link CapabilityTier#RETRIEVAL_ENRICHMENT}) are wanted by every intent — every mode does search. The
 * only tier axis an intent gates is the LLM stack ({@link CapabilityTier#LLM} + its
 * {@link CapabilityTier#RUNTIME} support), so the distinction is captured by a single flag.
 */
public enum InstallIntent {
  FULL_DESKTOP("full-desktop", true),
  HEADLESS("headless", true),
  MCP_LITE("mcp-lite", false);

  private static final Logger log = LoggerFactory.getLogger(InstallIntent.class);

  /** The default intent when {@code -Djustsearch.mode} / {@code JUSTSEARCH_MODE} is unset. */
  public static final InstallIntent DEFAULT = FULL_DESKTOP;

  private final String id;
  private final boolean includesLlmTier;

  InstallIntent(String id, boolean includesLlmTier) {
    this.id = id;
    this.includesLlmTier = includesLlmTier;
  }

  /** The kebab-case identifier used on the wire ({@code -Djustsearch.mode}, manifest {@code mode}). */
  public String id() {
    return id;
  }

  /**
   * Whether this intent wants packages of the given tier. Retrieval tiers are wanted by every intent;
   * the LLM + runtime tiers are wanted only by intents that include the LLM stack. An untagged package
   * ({@code tier == null}) is always wanted — a tier tag can only <em>exclude</em>, never silently drop
   * an unclassified package (back-compat with pre-tier registries).
   */
  public boolean wants(CapabilityTier tier) {
    if (tier == null) {
      return true;
    }
    return switch (tier) {
      case RETRIEVAL_CORE, RETRIEVAL_ENRICHMENT -> true;
      case LLM, RUNTIME -> includesLlmTier;
    };
  }

  /**
   * Resolves an intent from its config string (kebab-case; also tolerates underscores and case).
   * Returns {@link #DEFAULT} for a null/blank/unrecognized value — a bad launch flag must not brick
   * the install, only fall back to the full experience. A non-blank but unrecognized value (a likely
   * typo, e.g. {@code headles}) is WARN-logged so the silent-fallback is discoverable — previously a
   * mistyped {@code -Djustsearch.mode} / {@code JUSTSEARCH_MODE} would install/launch as Full Desktop
   * with no signal anywhere that the requested mode was never honored.
   */
  public static InstallIntent fromConfig(String value) {
    if (value == null || value.isBlank()) {
      return DEFAULT;
    }
    String norm = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (InstallIntent m : values()) {
      if (m.id.equals(norm)) {
        return m;
      }
    }
    log.warn(
        "Unrecognized JUSTSEARCH_MODE/justsearch.mode value '{}' — falling back to {}. Valid: "
            + "full-desktop, headless, mcp-lite",
        value,
        DEFAULT.id);
    return DEFAULT;
  }
}
