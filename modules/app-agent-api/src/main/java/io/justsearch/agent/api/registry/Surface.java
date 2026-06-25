/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chrome-surface manifest — a structured composition over existing primitives.
 *
 * <p>Per slice 449 §0 D1: Surface is a {@code Manifest} (the second Manifest
 * tier alongside Plugin), <strong>not</strong> a fifth primitive. Manifests
 * compose primitives into shippable units; primitives are the BE-declared
 * truth Manifests reference. The framework's primitive count stays at four
 * (Operation / Resource / Prompt / DiagnosticChannel); the Manifest count
 * grows from one (Plugin, implicit) to two (Plugin + Surface, explicit) when
 * slice 449 ships.
 *
 * <p>Surface does NOT implement {@link RegistryEntry} — that interface is the
 * sealed marker for the four primitives. Surface is its own kind of
 * registered metadata; its catalog ({@link SurfaceCatalog}) is parallel-but-
 * distinct from the per-primitive catalogs.
 *
 * <h3>Field guidance</h3>
 *
 * <ul>
 *   <li>{@link #id}: namespaced identifier; mirrors {@link OperationRef} format.
 *       See {@link SurfaceRef}.
 *   <li>{@link #presentation}: i18n-keyed labelKey + descriptionKey. Resolves
 *       through {@code /api/messages/registry-surface/{locale}}.
 *   <li>{@link #audience}: declared access-control audience. The
 *       {@link io.justsearch.app.services.registry.validator.SurfaceAreaValidator}
 *       computes the {@code effectiveAudience} per slice 449 §0 D2 and
 *       rejects the entry if {@code audience < effectiveAudience}. The
 *       chrome's {@code visibleAudienceSet} (a chrome-level state) controls
 *       which audience entries are rendered.
 *   <li>{@link #placement}: chrome-zone placement. Each chrome zone's
 *       dispatcher filters by {@code placement = ZONE && audience ∈ visibleAudienceSet}.
 *   <li>{@link #consumes}: typed cross-reference graph to backing primitives.
 *       The validator follows these references for audience composition
 *       (DiagnosticChannel.consumerPermission lifts the floor) and (V1.5)
 *       cross-reference enforcement (slice 3a-1-8c).
 *   <li>{@link #mountTag}: custom-element name the chrome dispatches to.
 *       Side-effect import registers the element; the dispatcher just mounts
 *       the resolved tag.
 *   <li>{@link #provenance}: CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN. The
 *       provenance tier is the audience floor for plugin-contributed
 *       surfaces per the §0 D2 rule.
 *   <li>{@link #stateSchema}: per slice 489 §5 — optional typed contract
 *       declaring the addressable state shape (query, filters, scroll, etc.)
 *       this surface accepts and exposes. {@link Optional#empty()} means the
 *       surface is not URL-addressable; activation falls back to a default
 *       view. Per slice 489 §17.2 resolution this field is optional, not
 *       mandatory, during the substrate's initial rollout.
 *   <li>{@link #altitude}: the governing axis (tempdoc 571) — determines the surface's home (rail
 *       band) and its core-vs-plugin eligibility, as a projection of the primary authority it
 *       carries. Declared per surface and witnessed by the {@code surface-altitude} gate
 *       ({@code TRUST ⟹ CORE}; {@code consumes-a-channel ⟹ DIAGNOSTIC}). {@link Altitude#PRODUCT}
 *       is the benign default. See {@link Altitude}.
 *   <li>{@link #members}: tempdoc 571 §11 / 578 — the declared host/member composition relationship.
 *       A host surface names the member surfaces it presents inside itself (e.g. System Health hosts
 *       Logs). Membership is the single home-authority: a member's home is its host, so it is
 *       excluded from the rail and its deep-link resolves to the host (read as-is, never derived). An
 *       empty list (the default) means the surface hosts nothing. <strong>Invariant (578 §10 U1): this
 *       field is NOT an input to {@link SurfaceAltitude#derive} — a host does not inherit or aggregate
 *       its members' altitudes; each surface (host and member) derives its own altitude from its own
 *       {@code consumes}.</strong> The {@code surface-composition} gate enforces one-home integrity
 *       (a member may not also be a RAIL surface, nor be hosted twice).
 * </ul>
 */
public record Surface(
    SurfaceRef id,
    Presentation presentation,
    Audience audience,
    Placement placement,
    SurfaceConsumes consumes,
    String mountTag,
    Provenance provenance,
    Optional<SurfaceStateSchema> stateSchema,
    RiskTier riskTier,
    Altitude altitude,
    List<SurfaceRef> members) implements Provenanced {

  public Surface {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(presentation, "presentation");
    Objects.requireNonNull(audience, "audience");
    Objects.requireNonNull(placement, "placement");
    Objects.requireNonNull(mountTag, "mountTag");
    Objects.requireNonNull(provenance, "provenance");
    Objects.requireNonNull(stateSchema, "stateSchema");
    // Tempdoc 550 WA-4: navigating TO a surface traverses the same (SourceTier × RiskTier)
    // lattice as an Operation invocation, so "all actions traverse the spine" (538) holds
    // for Navigation too. Default LOW → Navigation × any tier = AUTO (no behavior change);
    // only a surface that exposes destructive state declares higher. Backend-only (read by
    // BackendIntentRouter.forwardNavigation); not part of the FE surface wire payload.
    riskTier = riskTier == null ? RiskTier.LOW : riskTier;
    // Tempdoc 571: altitude is the governing axis (home + core-vs-plugin eligibility). PRODUCT is the
    // benign default; DIAGNOSTIC / TRUST are declared explicitly and witnessed by the surface-altitude
    // gate (TRUST ⟹ CORE provenance; consumes-a-channel ⟹ DIAGNOSTIC). See {@link Altitude}.
    altitude = altitude == null ? Altitude.PRODUCT : altitude;
    consumes = consumes == null ? SurfaceConsumes.empty() : consumes;
    // Tempdoc 571 §11 / 578: the declared host/member relationship. Defaults to no members.
    // Read as-is (never derived); excluded from SurfaceAltitude.derive by design (578 §10 U1).
    members = members == null ? List.of() : List.copyOf(members);
    if (mountTag.isBlank()) {
      throw new IllegalArgumentException(
          "Surface " + id.value() + " mountTag must be non-blank");
    }
    // Structural placement-vs-audience invariants (the SurfaceAreaValidator
    // enforces the audience-composition rule; these compact-constructor
    // checks are the local invariants the record itself can guarantee).
    if (placement == Placement.HEADLESS_AGENT_TOOL && audience != Audience.AGENT) {
      throw new IllegalArgumentException(
          "Surface " + id.value() + " has placement HEADLESS_AGENT_TOOL but audience "
              + audience + "; HEADLESS_AGENT_TOOL placement requires AGENT audience");
    }
    if (audience == Audience.AGENT && placement != Placement.HEADLESS_AGENT_TOOL) {
      throw new IllegalArgumentException(
          "Surface " + id.value() + " has audience AGENT but placement " + placement
              + "; AGENT audience requires HEADLESS_AGENT_TOOL placement");
    }
  }

  /**
   * Returns a copy of this Surface with {@code altitude} set — the injection point for the DERIVED
   * altitude (tempdoc 571 §4c). {@code RegistryController} computes the altitude from {@code consumes}
   * + the consumed Resources' {@link Role}s and stamps it onto the wire via this wither, so the
   * surfaces wire carries the derived value rather than a hand-declared one.
   */
  public Surface withAltitude(Altitude newAltitude) {
    return new Surface(
        id, presentation, audience, placement, consumes, mountTag, provenance, stateSchema, riskTier,
        newAltitude, members);
  }

  /**
   * Returns a copy of this Surface declaring {@code newMembers} as its hosted member surfaces
   * (tempdoc 571 §11 / 578). The ergonomic way for a catalog to declare a host without expanding its
   * constructor call to the full positional form — mirrors {@link #withAltitude}. Membership is the
   * single home-authority (members are excluded from the rail and resolve to this host).
   */
  public Surface withMembers(List<SurfaceRef> newMembers) {
    return new Surface(
        id, presentation, audience, placement, consumes, mountTag, provenance, stateSchema, riskTier,
        altitude, newMembers);
  }

  /**
   * Backwards-compat constructor for callsites that don't yet declare a state schema.
   *
   * <p>Per slice 489 §17.2: stateSchema is optional during the substrate's initial
   * rollout. This constructor mirrors the {@link Operation} backwards-compat
   * constructor pattern: existing 7-arg callsites continue to compile, and the
   * surface registers as "not URL-addressable" (chrome falls through to default state
   * on activation). The conversion to declaring a schema is per-surface, additive.
   */
  public Surface(
      SurfaceRef id,
      Presentation presentation,
      Audience audience,
      Placement placement,
      SurfaceConsumes consumes,
      String mountTag,
      Provenance provenance) {
    this(
        id,
        presentation,
        audience,
        placement,
        consumes,
        mountTag,
        provenance,
        Optional.empty(),
        RiskTier.LOW,
        Altitude.PRODUCT,
        List.of());
  }

  /**
   * Backwards-compat constructor for callsites that declare a state schema but not a
   * {@link RiskTier} (tempdoc 550 WA-4 added the field; existing 8-arg callsites continue to
   * compile and register as {@code RiskTier.LOW} — Navigation to them stays AUTO).
   */
  public Surface(
      SurfaceRef id,
      Presentation presentation,
      Audience audience,
      Placement placement,
      SurfaceConsumes consumes,
      String mountTag,
      Provenance provenance,
      Optional<SurfaceStateSchema> stateSchema) {
    this(
        id,
        presentation,
        audience,
        placement,
        consumes,
        mountTag,
        provenance,
        stateSchema,
        RiskTier.LOW,
        Altitude.PRODUCT,
        List.of());
  }

  /**
   * Backwards-compat constructor for callsites that declare a {@link RiskTier} but not an
   * {@link Altitude} (tempdoc 571 added the field). Existing 9-arg callsites continue to compile and
   * register as {@link Altitude#PRODUCT} — the benign default carrying no foreclosure.
   */
  public Surface(
      SurfaceRef id,
      Presentation presentation,
      Audience audience,
      Placement placement,
      SurfaceConsumes consumes,
      String mountTag,
      Provenance provenance,
      Optional<SurfaceStateSchema> stateSchema,
      RiskTier riskTier) {
    this(
        id,
        presentation,
        audience,
        placement,
        consumes,
        mountTag,
        provenance,
        stateSchema,
        riskTier,
        Altitude.PRODUCT,
        List.of());
  }

  /**
   * Backwards-compat constructor for callsites that declare an {@link Altitude} but not
   * {@link #members} (tempdoc 571 §11 / 578 added the field). Existing 10-arg callsites continue to
   * compile and register as a non-host surface ({@code members = List.of()}).
   */
  public Surface(
      SurfaceRef id,
      Presentation presentation,
      Audience audience,
      Placement placement,
      SurfaceConsumes consumes,
      String mountTag,
      Provenance provenance,
      Optional<SurfaceStateSchema> stateSchema,
      RiskTier riskTier,
      Altitude altitude) {
    this(
        id,
        presentation,
        audience,
        placement,
        consumes,
        mountTag,
        provenance,
        stateSchema,
        riskTier,
        altitude,
        List.of());
  }
}
