package fixture;

// Fixture Resource catalog: declares the altitude ROLES the surface-altitude gate derives from
// (parsed from the `.withRole(Role.X)` declarations). core.action-ledger is the TRUST authority;
// core.health-events is a DIAGNOSTIC authority. Resources without a `.withRole` default to PRODUCT.
public final class FixtureResourceCatalog {
  private static final ResourceRef ACTION_LEDGER = new ResourceRef("core.action-ledger");
  private static final ResourceRef HEALTH_EVENTS = new ResourceRef("core.health-events");

  static java.util.List<Resource> defs() {
    return java.util.List.of(
        new Resource(
                ACTION_LEDGER,
                Presentation.of(new I18nKey("a"), new I18nKey("b")),
                "schema",
                Category.EVENT_STREAM,
                SubscriptionMode.SSE_STREAM,
                "/api/action-ledger/stream",
                "action-ledger",
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                Provenance.core("1.0"),
                Privacy.noPaths(),
                Set.of(),
                Set.of(),
                "")
            .withRole(Role.TRUST),
        new Resource(
                HEALTH_EVENTS,
                Presentation.of(new I18nKey("a"), new I18nKey("b")),
                "schema",
                Category.EVENT_STREAM,
                SubscriptionMode.SSE_STREAM,
                "/api/health/events/stream",
                "health-event-stream",
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                Provenance.core("1.0"),
                Privacy.noPaths(),
                Set.of(),
                Set.of(),
                "")
            .withRole(Role.DIAGNOSTIC));
  }
}
