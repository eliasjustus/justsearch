package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 560 §28 — persistence round-trip for the operator plugin-trust allowlist. */
final class PluginAllowlistStoreTest {

  private static final String A =
      "abc123def456abc123def456abc123def456abc123def456abc123def4567890";
  private static final String B =
      "0000111122223333444455556666777788889999aaaabbbbccccddddeeeeffff";

  @Test
  void roundTrips_through_disk(@TempDir Path dir) {
    Path file = dir.resolve("ui").resolve("plugin-allowlist.json");
    PluginAllowlistStore store = new PluginAllowlistStore(PersistenceMode.READ_WRITE, file);
    Set<String> entries = new LinkedHashSet<>();
    entries.add(A);
    entries.add(B);
    store.save(entries);

    // A fresh store over the same file reads the entries back (survives a restart).
    PluginAllowlistStore reopened = new PluginAllowlistStore(PersistenceMode.READ_WRITE, file);
    Set<String> loaded = reopened.load();
    assertEquals(2, loaded.size());
    assertTrue(loaded.contains(A));
    assertTrue(loaded.contains(B));
  }

  @Test
  void inMemory_does_not_persist(@TempDir Path dir) {
    Path file = dir.resolve("ui").resolve("plugin-allowlist.json");
    PluginAllowlistStore store = new PluginAllowlistStore(PersistenceMode.IN_MEMORY, file);
    store.save(Set.of(A));
    assertFalse(Files.exists(file));
    assertTrue(store.load().isEmpty());
  }

  @Test
  void missing_file_loads_empty(@TempDir Path dir) {
    PluginAllowlistStore store =
        new PluginAllowlistStore(PersistenceMode.READ_WRITE, dir.resolve("nope.json"));
    assertTrue(store.load().isEmpty());
  }

  @Test
  void malformed_file_loads_empty(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("plugin-allowlist.json");
    Files.writeString(file, "{ not a valid json array ");
    PluginAllowlistStore store = new PluginAllowlistStore(PersistenceMode.READ_WRITE, file);
    assertTrue(store.load().isEmpty());
  }
}
