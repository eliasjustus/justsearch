/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.applauncher;

import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Minimal, SLF4J-free bootstrap entrypoint for app-launcher.
 *
 * <p>Purpose: ensure data-dir system properties exist <b>before</b> any SLF4J/logback initialization
 * (which can happen during {@link Launcher} class loading).
 */
public final class LauncherBootstrap {

  private LauncherBootstrap() {}

  public static void main(String[] args) {
    bootstrapDataDirProperties();
    Launcher.main(args);
  }

  private static void bootstrapDataDirProperties() {
    String resolved =
        firstNonBlank(
            EnvRegistry.DATA_DIR.get().orElse(null),
            System.getProperty("justsearch.data_dir"), // SYS-PROP-LEGACY-COMPAT
            System.getProperty("app.data_dir"));

    if (resolved == null || resolved.isBlank()) {
      resolved = Paths.get("build", "applauncher-data").toAbsolutePath().normalize().toString();
    }

    // Normalize/validate path and ensure it exists.
    final String normalized;
    try {
      Path p = Path.of(resolved).toAbsolutePath().normalize();
      Files.createDirectories(p);
      normalized = p.toString();
    } catch (InvalidPathException | IOException e) {
      throw new IllegalStateException("Failed to initialize data dir: " + resolved, e);
    }

    setIfBlank("justsearch.data.dir", normalized);
    setIfBlank("justsearch.data_dir", normalized); // legacy underscore alias
    setIfBlank("app.data_dir", normalized); // legacy logback alias
  }

  private static void setIfBlank(String key, String value) {
    String existing = System.getProperty(key);
    if (existing == null || existing.isBlank()) {
      System.setProperty(key, value);
    }
  }

  private static String firstNonBlank(String... candidates) {
    if (candidates == null) return null;
    for (String c : candidates) {
      if (c != null && !c.isBlank()) {
        return c;
      }
    }
    return null;
  }
}
