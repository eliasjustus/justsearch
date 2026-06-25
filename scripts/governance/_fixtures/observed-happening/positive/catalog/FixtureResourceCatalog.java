package fixture;

// POSITIVE fixture Resource catalog — text-parsed by the enforcer (never compiled). The gate reads each
// `new Resource(<id>, …)` first arg as the id (via the ResourceRef constant map) and any
// `.withOrigin(ProducerKind.X)`. No Resource here declares an origin (none is operator-trace data).
public final class FixtureResourceCatalog implements ResourceCatalog {
  private static final ResourceRef LEDGER = new ResourceRef("core.action-ledger");
  private static final ResourceRef OPHIST = new ResourceRef("core.operation-history");
  private static final ResourceRef HEALTH = new ResourceRef("core.health-events");

  static java.util.List<Resource> defs() {
    return java.util.List.of(
        new Resource(LEDGER, "schema"),
        new Resource(OPHIST, "schema"),
        new Resource(HEALTH, "schema"));
  }
}
