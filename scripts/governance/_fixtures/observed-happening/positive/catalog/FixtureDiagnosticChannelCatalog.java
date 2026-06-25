package fixture;

// POSITIVE fixture DiagnosticChannel catalog — text-parsed by the enforcer (never compiled). The gate
// reads each `new DiagnosticChannel(<id>, …)` first arg as the channel id (via the DiagnosticChannelRef
// constant map). core.head-log is the one operator-trace stream — correctly a channel, not a Resource.
public final class FixtureDiagnosticChannelCatalog implements DiagnosticChannelCatalog {
  private static final DiagnosticChannelRef HEAD_LOG = new DiagnosticChannelRef("core.head-log");

  static java.util.List<DiagnosticChannel> defs() {
    return java.util.List.of(new DiagnosticChannel(HEAD_LOG, "head-log"));
  }
}
