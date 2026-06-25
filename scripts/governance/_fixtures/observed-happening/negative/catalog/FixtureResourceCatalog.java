package fixture;

// NEGATIVE fixture Resource catalog — text-parsed by the enforcer (never compiled). core.bad-log is an
// operator-trace stream MIS-MODELLED as a Resource (it declares .withOrigin(ProducerKind.IN_PROCESS_LOGBACK))
// — it should be a DiagnosticChannel. The gate's operator-trace-must-be-channel rule must fire.
public final class FixtureResourceCatalog implements ResourceCatalog {
  private static final ResourceRef LEDGER = new ResourceRef("core.action-ledger");
  private static final ResourceRef HEALTH = new ResourceRef("core.health-events");
  private static final ResourceRef BADLOG = new ResourceRef("core.bad-log");

  static java.util.List<Resource> defs() {
    return java.util.List.of(
        new Resource(LEDGER, "schema"),
        new Resource(HEALTH, "schema"),
        new Resource(BADLOG, "schema").withOrigin(ProducerKind.IN_PROCESS_LOGBACK));
  }
}
