package io.justsearch.agent.api.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 629 (#1) — the catalog is the ONE authority for store classification; this pins it so a new
 * store can't silently land unclassified (the governance gate enforces the same at build time).
 */
class StoreCatalogTest {

  @Test
  void authoredStoresAreTheThreeAuthorityOfRecordStores() {
    var authored =
        Arrays.stream(StoreCatalog.values())
            .filter(StoreCatalog::isAuthored)
            .map(StoreCatalog::dirName)
            .collect(Collectors.toSet());
    assertEquals(java.util.Set.of("conversations", "memories", "agent-runs"), authored);
  }

  @Test
  void derivedStoresAreNeverAuthored() {
    assertFalse(StoreCatalog.INDEX.isAuthored());
    assertFalse(StoreCatalog.JOBS_DB.isAuthored());
    assertEquals(StoreRecoverability.DERIVED, StoreCatalog.INDEX.recoverability());
  }

  @Test
  void everyEntryHasADistinctDirNameAndAFraming() {
    var names = Arrays.stream(StoreCatalog.values()).map(StoreCatalog::dirName).distinct().count();
    assertEquals(StoreCatalog.values().length, names, "dirNames must be unique (bundle section keys)");
    for (StoreCatalog s : StoreCatalog.values()) {
      assertTrue(s.framing() != null, s + " must declare a framing");
      // DERIVED stores are OPAQUE (FDE-only, not app-framed); AUTHORED stores are app-framed.
      if (s.isAuthored()) {
        assertFalse(
            s.framing() == StoreCatalog.Framing.OPAQUE, s + " is AUTHORED so must not be OPAQUE");
      }
    }
  }
}
