/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and saves UI settings to {@code $JUSTSEARCH_HOME/ui/settings.json} (or
 * {@code ~/.config/justsearch/ui/settings.json} on Linux).
 */
public final class UiSettingsStore {

  private static final Logger log = LoggerFactory.getLogger(UiSettingsStore.class);
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
      return MAPPER.readValue(settingsFile.toFile(), UiSettings.class);
    } catch (Exception e) {
      log.warn("Failed to read UI settings (falling back to defaults)", e);
      return new UiSettings();
    }
  }

  public void save(UiSettings settings) {
    if (settings == null || mode == PersistenceMode.IN_MEMORY) {
      return;
    }
    try {
      Files.createDirectories(settingsFile.getParent());
      settings.getWindow().stampLastShown();
      MAPPER.writeValue(settingsFile.toFile(), settings);
    } catch (IOException e) {
      log.warn("Failed to persist UI settings", e);
    }
  }

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
     * Resolve persistence mode from overrides, read-only flags, or prod mode.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>Explicit override via {@code justsearch.ui.settings.mode} sysprop/env
     *   <li>Read-only flags ({@code justsearch.ui.settings.readOnly})
     *   <li>Prod mode ({@code justsearch.prod=true}) defaults to IN_MEMORY for isolation
     *   <li>Default: READ_WRITE
     * </ol>
     *
     * <p>The prod mode default prevents production/CI verification from being contaminated
     * by user-profile settings (e.g., dev index paths in settings.json).
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
      // Prod mode defaults to IN_MEMORY to prevent settings contamination during verification
      boolean prodMode =
          EnvRegistry.PROD_MODE.get().map(s -> Boolean.parseBoolean(s.trim())).orElse(false);
      if (prodMode) {
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
