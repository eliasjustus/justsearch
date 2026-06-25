package fixture;

// Fixture Resource catalog (shared shape with the positive fixture): core.action-ledger = TRUST,
// core.health-events = DIAGNOSTIC. The negative surfaces below consume these to DERIVE the violations.
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
