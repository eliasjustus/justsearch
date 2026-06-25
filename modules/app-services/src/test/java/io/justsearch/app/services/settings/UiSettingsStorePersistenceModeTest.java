package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.IN_MEMORY;
import static io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode.READ_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link UiSettingsStore.PersistenceMode#resolveMode()}.
 *
 * <p>Note: Environment variable code paths ({@code JUSTSEARCH_UI_SETTINGS_MODE}, {@code
 * JUSTSEARCH_UI_SETTINGS_READONLY}) cannot be tested because {@link System#getenv()} is immutable
 * at runtime. The env fallback logic uses the same parsing as sysprops once a value is obtained.
 */
@DisplayName("UiSettingsStore.PersistenceMode.resolveMode()")
class UiSettingsStorePersistenceModeTest {

  private static final String MODE_PROP = "justsearch.ui.settings.mode";
  private static final String READONLY_PROP = "justsearch.ui.settings.readOnly";
  private static final String PROD_PROP = "justsearch.prod";

  @Nested
  @DisplayName("Explicit mode override (highest priority)")
  class ExplicitModeOverride {

    @Test
    @DisplayName("justsearch.ui.settings.mode=in_memory returns IN_MEMORY")
    void explicitModeInMemory_viaSysprop() {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "in_memory")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.ui.settings.mode=read_write returns READ_WRITE")
    void explicitModeReadWrite_viaSysprop() {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "read_write")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Explicit mode overrides prod mode")
    void explicitModeOverridesProdMode() {
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "read_write").set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Explicit mode overrides readOnly flag")
    void explicitModeOverridesReadOnly() {
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "read_write").set(READONLY_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Mode aliases (parseMode coverage)")
  class ModeAliases {

    @ParameterizedTest(name = "mode={0} → READ_WRITE")
    @ValueSource(strings = {"rw", "read_write", "file", "persist"})
    @DisplayName("READ_WRITE aliases")
    void parseModeAliases_readWrite(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @ParameterizedTest(name = "mode={0} → IN_MEMORY")
    @ValueSource(strings = {"memory", "in_memory", "mem", "readonly", "read_only"})
    @DisplayName("IN_MEMORY aliases")
    void parseModeAliases_inMemory(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Invalid mode value falls through to next check")
    void parseModeInvalid_fallsThrough() {
      // Invalid mode should fall through; with prod=true, resolves to IN_MEMORY
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "invalid").set(PROD_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Blank mode value falls through to next check")
    void parseModeBlank_fallsThrough() {
      // Blank mode should fall through; with prod=true, resolves to IN_MEMORY
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "   ").set(PROD_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @ParameterizedTest(name = "mode={0} with mixed case → READ_WRITE")
    @ValueSource(strings = {"RW", "Read_Write", "FILE", "PERSIST"})
    @DisplayName("Case insensitivity for READ_WRITE aliases")
    void parseModeAliases_caseInsensitive_readWrite(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @ParameterizedTest(name = "mode={0} with mixed case → IN_MEMORY")
    @ValueSource(strings = {"MEMORY", "In_Memory", "MEM", "ReadOnly", "READ_ONLY"})
    @DisplayName("Case insensitivity for IN_MEMORY aliases")
    void parseModeAliases_caseInsensitive_inMemory(String alias) {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, alias)) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Whitespace-padded value is trimmed before parsing")
    void parseModeTrimmed_whitespace() {
      try (var ignored = new SysProps().clearAll().set(MODE_PROP, "  rw  ")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Read-only flags (second priority)")
  class ReadOnlyFlags {

    @Test
    @DisplayName("justsearch.ui.settings.readOnly=true returns IN_MEMORY")
    void readOnlySysprop_returnsInMemory() {
      try (var ignored = new SysProps().clearAll().set(READONLY_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.ui.settings.readOnly=false falls through to next check")
    void readOnlyFalse_fallsThrough() {
      // readOnly=false should fall through; with prod=true, resolves to IN_MEMORY
      try (var ignored =
          new SysProps().clearAll().set(READONLY_PROP, "false").set(PROD_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.ui.settings.readOnly=false with no prod defaults to READ_WRITE")
    void readOnlyFalse_noProd_defaultsToReadWrite() {
      try (var ignored = new SysProps().clearAll().set(READONLY_PROP, "false")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Prod mode (third priority)")
  class ProdMode {

    @Test
    @DisplayName("justsearch.prod=true defaults to IN_MEMORY")
    void prodMode_defaultsToInMemory() {
      try (var ignored = new SysProps().clearAll().set(PROD_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("justsearch.prod=false falls to default READ_WRITE")
    void prodModeFalse_fallsToDefault() {
      try (var ignored = new SysProps().clearAll().set(PROD_PROP, "false")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Default behavior")
  class DefaultBehavior {

    @Test
    @DisplayName("No overrides defaults to READ_WRITE")
    void noOverrides_defaultsToReadWrite() {
      try (var ignored = new SysProps().clearAll()) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("Priority order verification")
  class PriorityOrder {

    @Test
    @DisplayName("Explicit mode beats readOnly flag")
    void priorityOrder_explicitBeatsReadOnly() {
      try (var ignored =
          new SysProps().clearAll().set(MODE_PROP, "rw").set(READONLY_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Explicit mode beats both readOnly and prod")
    void priorityOrder_explicitBeatsAll() {
      try (var ignored =
          new SysProps()
              .clearAll()
              .set(MODE_PROP, "rw")
              .set(READONLY_PROP, "true")
              .set(PROD_PROP, "true")) {
        assertEquals(READ_WRITE, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("readOnly flag beats prod mode (both yield IN_MEMORY, confirms order)")
    void priorityOrder_readOnlyBeatsProd() {
      // Both result in IN_MEMORY, but this tests the priority by having prod=false
      // would yield READ_WRITE if readOnly wasn't checked first
      try (var ignored =
          new SysProps().clearAll().set(READONLY_PROP, "true").set(PROD_PROP, "false")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }

    @Test
    @DisplayName("Prod mode beats default")
    void priorityOrder_prodBeatsDefault() {
      try (var ignored = new SysProps().clearAll().set(PROD_PROP, "true")) {
        assertEquals(IN_MEMORY, PersistenceMode.resolveMode());
      }
    }
  }

  @Nested
  @DisplayName("isWritable() method")
  class IsWritableMethod {

    @Test
    @DisplayName("READ_WRITE.isWritable() returns true")
    void readWriteIsWritable() {
      assertEquals(true, READ_WRITE.isWritable());
    }

    @Test
    @DisplayName("IN_MEMORY.isWritable() returns false")
    void inMemoryIsNotWritable() {
      assertEquals(false, IN_MEMORY.isWritable());
    }
  }

  @Nested
  @DisplayName("load() behavior")
  class LoadBehavior {

    @TempDir Path tempDir;

    @Test
    @DisplayName("IN_MEMORY mode returns defaults without reading disk")
    void inMemory_loadReturnsDefaults() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      Files.writeString(settingsFile, "{\"maxTokens\": 999}");

      var store = new UiSettingsStore(IN_MEMORY, settingsFile);
      UiSettings loaded = store.load();

      assertEquals(new UiSettings().getMaxTokens(), loaded.getMaxTokens());
    }

    @Test
    @DisplayName("READ_WRITE mode reads from disk")
    void readWrite_loadReadsFromDisk() throws Exception {
      Path settingsFile = tempDir.resolve("settings.json");
      Files.writeString(settingsFile, "{\"maxTokens\": 999}");

      var store = new UiSettingsStore(READ_WRITE, settingsFile);
      UiSettings loaded = store.load();

      assertEquals(999, loaded.getMaxTokens());
    }

    @Test
    @DisplayName("IN_MEMORY mode ignores missing file gracefully")
    void inMemory_missingFileReturnsDefaults() {
      Path settingsFile = tempDir.resolve("nonexistent.json");

      var store = new UiSettingsStore(IN_MEMORY, settingsFile);
      UiSettings loaded = store.load();

      assertEquals(new UiSettings().getMaxTokens(), loaded.getMaxTokens());
    }
  }

  /** Minimal sysprop helper that restores previous values on close. */
  private static final class SysProps implements AutoCloseable {
    private final java.util.Map<String, String> prev = new java.util.HashMap<>();

    SysProps set(String key, String value) {
      if (!prev.containsKey(key)) {
        prev.put(key, System.getProperty(key));
      }
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      return this;
    }

    SysProps clearAll() {
      return set(MODE_PROP, null).set(READONLY_PROP, null).set(PROD_PROP, null);
    }

    @Override
    public void close() {
      for (var e : prev.entrySet()) {
        if (e.getValue() == null) {
          System.clearProperty(e.getKey());
        } else {
          System.setProperty(e.getKey(), e.getValue());
        }
      }
    }
  }
}
