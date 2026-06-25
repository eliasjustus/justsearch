/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.validator;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConsumerPermission;
import io.justsearch.agent.api.registry.DiagnosticChannel;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelRef;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Role;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceAltitude;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.agent.api.registry.TrustTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Build-time validator over a {@link SurfaceCatalog} — slice 449 phase 1.
 *
 * <p>Stands parallel to {@link ResourceAreaValidator} and
 * {@link DiagnosticChannelAreaValidator}: a standalone validator with a
 * direct {@link #validate} entry point rather than a
 * {@link RegistryShapeValidator} implementation (the latter's
 * {@link ValidationContext} is OperationCatalog-specific).
 *
 * <h3>Audience composition rule (slice 449 §0 D2)</h3>
 *
 * <p>The most substantive rule this validator enforces. Per the corrected
 * §0 D2 (rework documented in slice 449 §B.A.1):
 *
 * <pre>
 * effectiveAudience = max(
 *     surface.audience,
 *     audienceFloorFromProvenance(surface.provenance.tier),
 *     audienceFloorFromConsumedChannels(surface.consumes.diagnosticChannels)
 * )
 *
 * audienceFloorFromProvenance:
 *     CORE              → USER (no lift)
 *     TRUSTED_PLUGIN    → USER (no lift; trust granted at signing)
 *     UNTRUSTED_PLUGIN  → OPERATOR (extra opt-in for unsigned plugins)
 *
 * audienceFloorFromConsumedChannels:
 *     if any channel.consumerPermission ∈ {TRUSTED_PLUGIN, OPERATOR_OVERRIDE}: OPERATOR
 *     else: USER (no lift)
 *
 * Trust ordering: USER &lt; OPERATOR &lt; DEVELOPER.
 * AGENT is excluded from the comparison — agent surfaces don't compose
 * into the human-audience ordering and are validated on a separate axis
 * (placement = HEADLESS_AGENT_TOOL, enforced in Surface's compact constructor).
 * </pre>
 *
 * <p>{@link io.justsearch.agent.api.registry.Privacy Resource.Privacy} is
 * <strong>orthogonal</strong> to audience and does NOT contribute to the
 * floor. It governs data emission, not access.
 *
 * <h3>Cross-reference resolution</h3>
 *
 * <p>Computing {@code audienceFloorFromConsumedChannels} requires looking up
 * each consumed {@link DiagnosticChannelRef}'s {@link ConsumerPermission}.
 * The validator accepts an optional list of {@link DiagnosticChannelCatalog}
 * to perform the lookup. Callers without access to channel catalogs at
 * validate-time get the audience-from-provenance floor only — channel-side
 * floor enforcement requires the catalogs.
 */
public final class SurfaceAreaValidator {

  /** A single shape violation found during validation. */
  public record Finding(String surfaceId, String issue) {

    public Finding {
      Objects.requireNonNull(surfaceId, "surfaceId");
      Objects.requireNonNull(issue, "issue");
    }
  }

  /**
   * Validates {@code catalog} without channel cross-reference enforcement
   * (audience floor uses provenance only). Useful for tests + callers that
   * don't have channel catalogs available.
   */
  public List<Finding> validate(SurfaceCatalog catalog) {
    return validate(catalog, List.of());
  }

  /**
   * Validates {@code catalog} with channel cross-reference enforcement
   * against {@code channelCatalogs}. When a Surface consumes a
   * {@link DiagnosticChannelRef} not present in any of the supplied
   * catalogs, that's a cross-reference defect (separate finding).
   */
  public List<Finding> validate(
      SurfaceCatalog catalog, List<? extends DiagnosticChannelCatalog> channelCatalogs) {
    return validate(catalog, channelCatalogs, List.of());
  }

  /**
   * Validates {@code catalog} with channel AND resource cross-reference enforcement, plus the
   * tempdoc 571 §4c altitude-derivation conflict check. Every consumed {@link ResourceRef} must
   * resolve to a Resource registered in {@code resourceCatalogs} (closes slice 3a-1-8c for the
   * Resource axis); and a surface whose consumed authority yields two distinct non-PRODUCT altitude
   * signals is a derivation conflict (the merge-foreclosure — a surface carries exactly one altitude).
   */
  public List<Finding> validate(
      SurfaceCatalog catalog,
      List<? extends DiagnosticChannelCatalog> channelCatalogs,
      List<? extends ResourceCatalog> resourceCatalogs) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(channelCatalogs, "channelCatalogs");
    Objects.requireNonNull(resourceCatalogs, "resourceCatalogs");
    final Map<DiagnosticChannelRef, DiagnosticChannel> channelIndex = indexChannels(channelCatalogs);
    final Map<ResourceRef, Role> resourceRoles = indexResourceRoles(resourceCatalogs);
    final List<Finding> findings = new ArrayList<>();
    final Set<String> seenIds = new HashSet<>();

    for (final Surface surface : catalog.definitions()) {
      final String id = surface.id().value();

      if (!seenIds.add(id)) {
        findings.add(new Finding(id, "duplicate surface id within catalog"));
      }

      // Cross-reference resolution: every consumed channel id must resolve.
      // Only enforced when channelCatalogs is non-empty (to keep the simpler
      // overload usable in isolation).
      if (!channelCatalogs.isEmpty()) {
        for (final DiagnosticChannelRef ref : surface.consumes().diagnosticChannels()) {
          if (!channelIndex.containsKey(ref)) {
            findings.add(
                new Finding(
                    id,
                    "consumes diagnosticChannel '"
                        + ref.value()
                        + "' which is not registered in any supplied DiagnosticChannelCatalog"));
          }
        }
      }

      // Tempdoc 571 §4c / slice 3a-1-8c (Resource axis): every consumed Resource must resolve. Only
      // enforced when resourceCatalogs is non-empty (keeps the channel-only overloads usable alone).
      if (!resourceCatalogs.isEmpty()) {
        for (final ResourceRef ref : surface.consumes().resources()) {
          if (!resourceRoles.containsKey(ref)) {
            findings.add(
                new Finding(
                    id,
                    "consumes resource '"
                        + ref.value()
                        + "' which is not registered in any supplied ResourceCatalog"));
          }
        }
      }

      // Tempdoc 571 §4c: altitude is DERIVED from the consumed authority. Two distinct non-PRODUCT
      // signals (e.g. a diagnostic AND a trust Resource) is a merge-foreclosure conflict — a surface
      // carries exactly one altitude. The wire falls back to PRODUCT; this finding fails the build.
      final SurfaceAltitude.Derivation derivation = SurfaceAltitude.derive(surface, resourceRoles);
      if (derivation.conflict()) {
        findings.add(
            new Finding(
                id,
                "altitude derivation conflict: consumes multiple distinct non-PRODUCT authorities "
                    + derivation.signals()
                    + " — a surface carries exactly one altitude (tempdoc 571 §4c merge-foreclosure)"));
      }

      // Audience composition rule (§0 D2 revised).
      // AGENT audience bypasses the human-audience comparison — it's
      // governed by the placement invariant in Surface's compact constructor.
      if (surface.audience() != Audience.AGENT) {
        final Audience floorFromProvenance = audienceFloorFromProvenance(surface.provenance().tier());
        final Audience floorFromChannels =
            audienceFloorFromConsumedChannels(surface.consumes().diagnosticChannels(), channelIndex);
        final Audience effectiveAudience =
            maxAudience(surface.audience(), floorFromProvenance, floorFromChannels);
        if (compareAudience(surface.audience(), effectiveAudience) < 0) {
          findings.add(
              new Finding(
                  id,
                  "declared audience "
                      + surface.audience()
                      + " is below the effective floor "
                      + effectiveAudience
                      + " (provenance="
                      + surface.provenance().tier()
                      + ", channelFloor="
                      + floorFromChannels
                      + "); surface must declare at least "
                      + effectiveAudience));
        }
      }
    }

    return List.copyOf(findings);
  }

  /**
   * Computes the audience floor implied by the surface's provenance per
   * §0 D2.
   */
  static Audience audienceFloorFromProvenance(TrustTier tier) {
    return switch (tier) {
      case CORE, TRUSTED_PLUGIN -> Audience.USER;
      case UNTRUSTED_PLUGIN -> Audience.OPERATOR;
    };
  }

  /**
   * Computes the audience floor implied by the consumed DiagnosticChannel
   * set per §0 D2. Channels not present in the supplied index do NOT
   * contribute to the floor (the cross-reference check above flags missing
   * references separately; here we conservatively skip).
   */
  static Audience audienceFloorFromConsumedChannels(
      Set<DiagnosticChannelRef> channelRefs,
      Map<DiagnosticChannelRef, DiagnosticChannel> channelIndex) {
    for (final DiagnosticChannelRef ref : channelRefs) {
      final DiagnosticChannel channel = channelIndex.get(ref);
      if (channel == null) continue; // missing-ref handled separately
      final ConsumerPermission perm = channel.consumerPermission();
      if (perm == ConsumerPermission.TRUSTED_PLUGIN
          || perm == ConsumerPermission.OPERATOR_OVERRIDE) {
        return Audience.OPERATOR;
      }
    }
    return Audience.USER;
  }

  /**
   * Returns the most-restrictive of the supplied audiences per the trust
   * ordering {@code USER < OPERATOR < DEVELOPER}.
   */
  static Audience maxAudience(Audience a, Audience b, Audience c) {
    Audience max = a;
    if (compareAudience(b, max) > 0) max = b;
    if (compareAudience(c, max) > 0) max = c;
    return max;
  }

  /**
   * Total order on the human-audience tiers. AGENT throws — agent surfaces
   * must skip the comparison entirely (callers gate on
   * {@code audience != AGENT} first).
   */
  static int compareAudience(Audience a, Audience b) {
    return tierRank(a) - tierRank(b);
  }

  private static int tierRank(Audience a) {
    return switch (a) {
      case USER -> 0;
      case OPERATOR -> 1;
      case DEVELOPER -> 2;
      case AGENT ->
          throw new IllegalArgumentException(
              "AGENT audience is excluded from the human-audience ordering; "
                  + "callers must gate on audience != AGENT before comparing");
    };
  }

  private static Map<DiagnosticChannelRef, DiagnosticChannel> indexChannels(
      List<? extends DiagnosticChannelCatalog> channelCatalogs) {
    final Map<DiagnosticChannelRef, DiagnosticChannel> index = new java.util.HashMap<>();
    for (final DiagnosticChannelCatalog cat : channelCatalogs) {
      for (final DiagnosticChannel def : cat.definitions()) {
        index.putIfAbsent(def.id(), def);
      }
    }
    return Map.copyOf(index);
  }

  /** Index every registered Resource by id → {@link Role} (tempdoc 571 §4c altitude-derivation input). */
  private static Map<ResourceRef, Role> indexResourceRoles(
      List<? extends ResourceCatalog> resourceCatalogs) {
    final Map<ResourceRef, Role> index = new LinkedHashMap<>();
    for (final ResourceCatalog cat : resourceCatalogs) {
      for (final Resource def : cat.definitions()) {
        index.putIfAbsent(def.id(), def.role());
      }
    }
    return Map.copyOf(index);
  }
}
