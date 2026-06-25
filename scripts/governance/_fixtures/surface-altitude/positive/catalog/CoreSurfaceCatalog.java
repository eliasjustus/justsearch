package fixture;

// Fixture surface catalog (POSITIVE): every surface DERIVES a single, consistent altitude from the
// authority it consumes — a CORE Activity surface consuming the TRUST Resource (⟹ TRUST), a Health
// surface consuming a DIAGNOSTIC Resource (⟹ DIAGNOSTIC), a channel-consuming Logs surface (⟹ DIAGNOSTIC),
// and a default Search surface that consumes nothing (⟹ PRODUCT). NO altitude is DECLARED — the gate
// derives it from FixtureResourceCatalog's roles. The surface-altitude gate must PASS.
public final class CoreSurfaceCatalog {
  public static final SurfaceRef ACTIVITY = new SurfaceRef("core.activity-surface");
  public static final SurfaceRef HEALTH = new SurfaceRef("core.health-surface");
  public static final SurfaceRef LOGS = new SurfaceRef("core.logs-surface");
  public static final SurfaceRef SEARCH = new SurfaceRef("core.search-surface");

  private static final DiagnosticChannelRef DC_HEAD_LOG = new DiagnosticChannelRef("core.head-log");
  private static final ResourceRef RES_ACTION_LEDGER = new ResourceRef("core.action-ledger");
  private static final ResourceRef RES_HEALTH_EVENTS = new ResourceRef("core.health-events");

  static java.util.List<Surface> defs() {
    return java.util.List.of(
        new Surface(
            ACTIVITY,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(RES_ACTION_LEDGER),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
            "jf-activity-surface",
            Provenance.core("1.0")),
        new Surface(
            HEALTH,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(RES_HEALTH_EVENTS),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
            "jf-health-surface",
            Provenance.core("1.0")),
        new Surface(
            LOGS,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.OPERATOR,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.of(DC_HEAD_LOG)),
            "jf-log-surface",
            Provenance.core("1.0")),
        new Surface(
            SEARCH,
            Presentation.of(new I18nKey("a"), new I18nKey("b")),
            Audience.USER,
            Placement.RAIL,
            new SurfaceConsumes(
                /* resources */ Set.of(),
                /* operations */ Set.of(),
                /* prompts */ Set.of(),
                /* diagnosticChannels */ Set.<DiagnosticChannelRef>of()),
            "jf-search-surface",
            Provenance.core("1.0")));
  }
}
