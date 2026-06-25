package fixture;

// Fixture surface catalog (NEGATIVE): three DERIVATION foreclosure violations —
//   (1) a surface consuming the TRUST Resource with NON-CORE (plugin) provenance — it DERIVES TRUST and
//       is not CORE, so it fails surface-altitude/trust-requires-core (a forged trust surface);
//   (2) a surface consuming a TRUST Resource AND a DIAGNOSTIC Resource — two distinct non-PRODUCT
//       authorities derive an altitude CONFLICT, so it fails surface-altitude/altitude-conflict;
//   (3) a surface consuming a DIAGNOSTIC Resource with NON-CORE (plugin) provenance — it DERIVES
//       DIAGNOSTIC and is not CORE, so it fails surface-altitude/diagnostic-requires-core (a diagnostic
//       surface is plugin-ineligible until 560 §4a).
// No altitude is DECLARED — the gate derives all three violations from the consumed authority. Must FAIL.
public final class CoreSurfaceCatalog {
  public static final SurfaceRef EVIL_ACTIVITY = new SurfaceRef("vendor.evil.activity-surface");
  public static final SurfaceRef CONFLICT = new SurfaceRef("core.conflict-surface");
  public static final SurfaceRef EVIL_DIAG = new SurfaceRef("vendor.evil.diag-surface");

  private static final ResourceRef RES_ACTION_LEDGER = new ResourceRef("core.action-ledger");
  private static final ResourceRef RES_HEALTH_EVENTS = new ResourceRef("core.health-events");

  static java.util.List<Surface> defs() {
    return java.util.List.of(
        // (1) consumes the TRUST Resource + non-core provenance → derives TRUST, fails trust-requires-core.
        new Surface(
            EVIL_ACTIVITY,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(RES_ACTION_LEDGER),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
            "jf-activity-surface",
            Provenance.plugin("vendor.evil", "1.0")),
        // (2) consumes a TRUST Resource AND a DIAGNOSTIC Resource → derives an altitude conflict.
        new Surface(
            CONFLICT,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(RES_ACTION_LEDGER, RES_HEALTH_EVENTS),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
            "jf-conflict-surface",
            Provenance.core("1.0")),
        // (3) consumes a DIAGNOSTIC Resource + non-core provenance → derives DIAGNOSTIC, fails
        //     diagnostic-requires-core.
        new Surface(
            EVIL_DIAG,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(RES_HEALTH_EVENTS),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
            "jf-evil-diag-surface",
            Provenance.plugin("vendor.evil", "1.0")));
  }
}
