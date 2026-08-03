/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Loads and saves UI settings to {@code $JUSTSEARCH_HOME/ui/settings.json} (or
 * {@code ~/.config/justsearch/ui/settings.json} on Linux).
 */
public final class UiSettingsStore {

  static final int CURRENT_SCHEMA_VERSION = 1;
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private final Path settingsFile;
  private final PersistenceMode mode;

  public UiSettingsStore(PersistenceMode mode) {
    this(mode, resolveSettingsFile());
  }

  public UiSettingsStore(PersistenceMode mode, Path settingsFile) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.settingsFile = Objects.requireNonNull(settingsFile, "settingsFile");
  }

  public UiSettings load() {
    if (mode == PersistenceMode.IN_MEMORY) {
      return new UiSettings();
    }
    if (!Files.exists(settingsFile)) {
      return new UiSettings();
    }
    try {
      JsonNode root = MAPPER.readTree(settingsFile.toFile());
      if (root == null || !root.isObject()) {
        throw new CorruptDurableStoreException("ui-settings", "expected a JSON object");
      }
      boolean envelope = root.has("settings");
      JsonNode versionNode = envelope ? root.get("schemaVersion") : null;
      if (envelope && (versionNode == null || !versionNode.isIntegralNumber())) {
        throw new CorruptDurableStoreException(
            "ui-settings", "versioned envelope requires an integer schemaVersion");
      }
      Integer observedVersion =
          versionNode == null || versionNode.isNull() ? null : versionNode.asInt();
      StoreFormatVersions.requireReadable(
          "ui-settings", observedVersion, CURRENT_SCHEMA_VERSION, 0, 0);
      UiSettings settings =
          !envelope
              ? MAPPER.treeToValue(root, UiSettings.class)
              : MAPPER.treeToValue(root.get("settings"), UiSettings.class);
      if (settings == null) {
        throw new CorruptDurableStoreException("ui-settings", "settings payload is missing");
      }
      return settings;
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException(
          "ui-settings", "cannot parse " + settingsFile, e);
    }
  }

  public void save(UiSettings settings) {
    if (settings == null || mode == PersistenceMode.IN_MEMORY) {
      return;
    }
    try {
      settings.getWindow().stampLastShown();
      byte[] bytes =
          MAPPER
              .writerWithDefaultPrettyPrinter()
              .writeValueAsBytes(new PersistedSettings(CURRENT_SCHEMA_VERSION, settings));
      AtomicFileWrites.replace(settingsFile, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to persist UI settings to " + settingsFile, e);
    }
  }

  private record PersistedSettings(int schemaVersion, UiSettings settings) {}

  private static Path resolveSettingsFile() {
    // Tempdoc 519 §9 Block B3.0.d: moved from io.justsearch.ui.settings to app-services.
    // The app-services AppServicesWorkerGuardrailsTest bars ad-hoc System.getProperty /
    // System.getenv access; platform detection routes through PlatformPaths instead.
    String homeOverride = EnvRegistry.HOME.get().orElse(null);
    Path base;
    if (homeOverride != null && !homeOverride.isBlank()) {
      base = Path.of(homeOverride);
    } else {
      Path userHome = PlatformPaths.resolveUserHome();
      if (PlatformPaths.isWindows()) {
        base = userHome.resolve("AppData").resolve("Roaming").resolve("justsearch");
      } else if (PlatformPaths.isMac()) {
        base = userHome.resolve("Library").resolve("Application Support").resolve("justsearch");
      } else {
        base = userHome.resolve(".config").resolve("justsearch");
      }
    }
    return base.resolve("ui").resolve("settings.json");
  }

  public Path settingsPath() {
    return settingsFile;
  }

  public PersistenceMode mode() {
    return mode;
  }

  public enum PersistenceMode {
    READ_WRITE,
    IN_MEMORY;

    public boolean isWritable() {
      return this == READ_WRITE;
    }

    /**
     * Resolve persistence mode from its own explicit configuration.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit override via {@code justsearch.ui.settings.mode} sysprop/env
     *   <li>Read-only flags ({@code justsearch.ui.settings.readOnly})
     *   <li>Default: READ_WRITE
     * </ol>
     *
     * <p>Persistence is its own axis: {@code justsearch.prod} governs the loopback trust
     * boundary only and implies nothing here. Verification harnesses that want isolation pass
     * the explicit override themselves (tempdoc 804 §B1).
     */
    public static PersistenceMode resolveMode() {
      String override = EnvRegistry.UI_SETTINGS_MODE.get().orElse(null);
      PersistenceMode parsed = parseMode(override);
      if (parsed != null) {
        return parsed;
      }
      // Tempdoc 519 §9 Block B3.0.d: routed through EnvRegistry instead of direct sysprop/env access.
      boolean readOnly =
          EnvRegistry.UI_SETTINGS_READONLY.get().map(s -> Boolean.parseBoolean(s.trim())).orElse(false);
      if (readOnly) {
        return IN_MEMORY;
      }
      return READ_WRITE;
    }

    private static PersistenceMode parseMode(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      String normalized = raw.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "rw", "read_write", "file", "persist" -> READ_WRITE;
        case "memory", "in_memory", "mem", "readonly", "read_only" -> IN_MEMORY;
        default -> null;
      };
    }
  }

}
