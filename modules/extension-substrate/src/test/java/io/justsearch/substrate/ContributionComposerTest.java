package io.justsearch.substrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.substrate.ContributionComposer.Installation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the four shared substrates generically (tempdoc 560 §4.2/§4.3) — String-keyed here. */
class ContributionComposerTest {

  private static ContributionComposer<String, String> composer() {
    return new ContributionComposer<>(k -> k); // key id is the string itself
  }

  private static Installation<String, String> install(
      String owner, boolean isCore, boolean admissible, Map<String, String> entries) {
    return new Installation<>(owner, owner, isCore, admissible, null, entries);
  }

  @Test
  void installComposesAndDispatches() {
    ContributionComposer<String, String> c = composer();
    c.install(install("vendor.a", false, true, Map.of("vendor.a.x", "X", "vendor.a.y", "Y")));
    assertTrue(c.isInstalled("vendor.a"));
    assertEquals("X", c.get("vendor.a.x").orElse(null));
    assertEquals(2, c.values().size());
  }

  @Test
  void lifecycle_doubleInstallRejectedAndStateUnchanged() {
    ContributionComposer<String, String> c = composer();
    c.install(install("vendor.a", false, true, Map.of("vendor.a.x", "X")));
    assertThrows(
        IllegalStateException.class,
        () -> c.install(install("vendor.a", false, true, Map.of("vendor.a.z", "Z"))));
    assertEquals(1, c.values().size());
  }

  @Test
  void lifecycle_keyCollisionRejectedAtomically() {
    ContributionComposer<String, String> c = composer();
    c.install(install("vendor.a", false, true, Map.of("shared.key", "A")));
    assertThrows(
        IllegalStateException.class,
        () -> c.install(install("vendor.b", false, true, Map.of("shared.key", "B", "vendor.b.x", "X"))));
    assertFalse(c.isInstalled("vendor.b"));
    assertEquals(1, c.values().size(), "rejected install must leave the composer unchanged");
  }

  @Test
  void boundary_inadmissibleInstallRefused() {
    ContributionComposer<String, String> c = composer();
    Installation<String, String> denied =
        new Installation<>("vendor.x", "vendor.x", false, false, "no sandbox runtime", Map.of("vendor.x.a", "A"));
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> c.install(denied));
    assertTrue(ex.getMessage().contains("Boundary refused"));
    assertEquals(0, c.values().size());
  }

  @Test
  void trust_nonCoreOwnerCannotMintCoreKey() {
    ContributionComposer<String, String> c = composer();
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> c.install(install("vendor.evil", false, true, Map.of("core.search-index", "forged"))));
    assertTrue(ex.getMessage().contains("Host owns truth"));
    assertEquals(0, c.values().size());
  }

  @Test
  void trust_coreOwnerMayMintCoreKey() {
    ContributionComposer<String, String> c = composer();
    c.install(install("core", true, true, Map.of("core.search-index", "real")));
    assertEquals("real", c.get("core.search-index").orElse(null));
  }

  @Test
  void lifecycle_uninstallRevokesExactlyTheOwnersKeys() {
    ContributionComposer<String, String> c = composer();
    c.install(install("vendor.a", false, true, Map.of("vendor.a.x", "X", "vendor.a.y", "Y")));
    c.install(install("vendor.b", false, true, Map.of("vendor.b.z", "Z")));

    ContributionComposer.UninstallResult<String> r = c.uninstall("vendor.a");
    assertTrue(r.wasInstalled());
    assertEquals(2, r.removedKeys().size(), "the result names the revoked keys for the caller");
    assertFalse(c.isInstalled("vendor.a"));
    assertEquals(List.of("Z"), c.values());

    assertFalse(c.uninstall("vendor.absent").wasInstalled());
  }
}
