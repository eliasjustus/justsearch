/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists the operator plugin-trust allowlist — the set of SHA-256 hexes of plugin artifacts an
 * operator has explicitly trusted — to {@code $JUSTSEARCH_HOME/ui/plugin-allowlist.json}, a sibling
 * of {@code settings.json}.
 *
 * <p>Tempdoc 560 §28 (the delivery slice): an operator-approval allowlist is the production-real
 * trust path for a URL-loaded third-party plugin (short of full Sigstore, which stays dep-gated).
 * Without persistence an approval would not survive a restart, so a trusted plugin would silently
 * fall back to UNTRUSTED. This store gives the allowlist durability with the same shape as
 * {@link UiSettingsStore} (same {@link PersistenceMode}, same base directory), so the two stay
 * consistent across modes (IN_MEMORY for prod/CI isolation; READ_WRITE for real operator use).
 */
public final class PluginAllowlistStore {

  private static final Logger log = LoggerFactory.getLogger(PluginAllowlistStore.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(SerializationFeature.INDENT_OUTPUT)
          .build();

  private final PersistenceMode mode;
  private final Path file;

  public PluginAllowlistStore(PersistenceMode mode, Path file) {
    this.mode = Objects.requireNonNull(mode, "mode");
    this.file = Objects.requireNonNull(file, "file");
  }

  /** Loads the persisted allowlist. Returns an empty set in IN_MEMORY mode or when no file exists. */
  public Set<String> load() {
    if (mode == PersistenceMode.IN_MEMORY || !Files.exists(file)) {
      return new LinkedHashSet<>();
    }
    try {
      String[] entries = MAPPER.readValue(file.toFile(), String[].class);
      return entries == null ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(entries));
    } catch (Exception e) {
      log.warn("Failed to read plugin allowlist (starting empty)", e);
      return new LinkedHashSet<>();
    }
  }

  /** Persists the allowlist. No-op in IN_MEMORY mode. Best-effort: a write failure is logged, not thrown. */
  public void save(Set<String> entries) {
    if (mode == PersistenceMode.IN_MEMORY) {
      return;
    }
    try {
      Files.createDirectories(file.getParent());
      MAPPER.writeValue(file.toFile(), entries == null ? Set.of() : entries);
    } catch (IOException e) {
      log.warn("Failed to persist plugin allowlist", e);
    }
  }

  public Path path() {
    return file;
  }

  public PersistenceMode mode() {
    return mode;
  }
}
