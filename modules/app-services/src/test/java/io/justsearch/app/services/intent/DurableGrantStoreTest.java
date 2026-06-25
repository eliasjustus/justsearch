package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.observability.ledger.ActionEvent;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 550 thesis IV — the durable "allow-always" grant (the second Grant-model member). */
@DisplayName("DurableGrantStore")
class DurableGrantStoreTest {

  @Test
  @DisplayName("grant → allowed for that (op, tier) only; revoke → no longer allowed")
  void grantScopeAndRevoke() {
    DurableGrantStore store = new DurableGrantStore();
    assertFalse(store.isAllowed("core.x", SourceTier.UNTRUSTED));

    store.grantAllowAlways("core.x", SourceTier.UNTRUSTED);
    assertTrue(store.isAllowed("core.x", SourceTier.UNTRUSTED));
    assertFalse(store.isAllowed("core.x", SourceTier.TRUSTED), "scoped to the granted tier");
    assertFalse(store.isAllowed("core.other", SourceTier.UNTRUSTED), "scoped to the granted op");

    store.revoke("core.x", SourceTier.UNTRUSTED);
    assertFalse(store.isAllowed("core.x", SourceTier.UNTRUSTED));
  }

  @Test
  @DisplayName("revokeNonUser revokes only UNTRUSTED durable grants (matches the gate hard-stop)")
  void revokeNonUserScopesToUntrusted() {
    DurableGrantStore store = new DurableGrantStore();
    store.grantAllowAlways("core.agent", SourceTier.UNTRUSTED);
    store.grantAllowAlways("core.user", SourceTier.TRUSTED);

    store.revokeNonUser();

    assertFalse(store.isAllowed("core.agent", SourceTier.UNTRUSTED), "non-user durable grant revoked");
    assertTrue(store.isAllowed("core.user", SourceTier.TRUSTED), "user durable grant survives");
  }

  @Test
  @DisplayName("grant + revoke are recorded in the one action-event log (one audit)")
  void emitsLifecycleEvents() {
    DurableGrantStore store = new DurableGrantStore();
    List<ActionEvent> events = new ArrayList<>();
    store.setGrantEventSink(events::add);

    store.grantAllowAlways("core.x", SourceTier.UNTRUSTED);
    store.revoke("core.x", SourceTier.UNTRUSTED);

    List<String> actions =
        events.stream()
            .filter(e -> e instanceof ActionEvent.Grant)
            .map(e -> ((ActionEvent.Grant) e).action())
            .toList();
    assertTrue(actions.contains("GRANTED_ALWAYS"), "grant recorded");
    assertTrue(actions.contains("REVOKED"), "revoke recorded");
  }

  @Test
  @DisplayName("560 §28 (4d): a family grant auto-approves any op declaring that family; revoke clears it")
  void familyGrantCoversAnyOpInFamily() {
    DurableGrantStore store = new DurableGrantStore();
    Optional<String> family = Optional.of("file-operations");

    // No grant: an op in the family is not allowed by family.
    assertFalse(store.isAllowed("core.ingest", family, SourceTier.UNTRUSTED));

    store.grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertTrue(store.isAllowed("core.ingest", family, SourceTier.UNTRUSTED), "any op in the family");
    assertTrue(
        store.isAllowed("core.file-operations", family, SourceTier.UNTRUSTED), "a different op too");
    assertFalse(
        store.isAllowed("core.ingest", Optional.empty(), SourceTier.UNTRUSTED),
        "an op WITHOUT the family is not covered");
    assertFalse(
        store.isAllowed("core.ingest", family, SourceTier.TRUSTED), "scoped to the granted tier");

    store.revokeFamily("file-operations", SourceTier.UNTRUSTED);
    assertFalse(store.isAllowed("core.ingest", family, SourceTier.UNTRUSTED));
  }

  @Test
  @DisplayName("560 §28: durable grants (op + family) persist to disk and reload (survive a restart)")
  void persistsAndReloads(@TempDir Path dir) {
    Path file = dir.resolve("ui").resolve("durable-grants.json");
    DurableGrantStore store = new DurableGrantStore(Clock.systemUTC(), file);
    store.grantAllowAlways("core.x", SourceTier.UNTRUSTED);
    store.grantFamilyAllowAlways("file-operations", SourceTier.TRUSTED);

    // A fresh store over the same file reloads both grants.
    DurableGrantStore reopened = new DurableGrantStore(Clock.systemUTC(), file);
    assertTrue(reopened.isAllowed("core.x", SourceTier.UNTRUSTED), "operation grant survived");
    assertTrue(
        reopened.isAllowed("core.ingest", Optional.of("file-operations"), SourceTier.TRUSTED),
        "family grant survived");
    assertEquals(2, reopened.snapshot().size());
  }
}
