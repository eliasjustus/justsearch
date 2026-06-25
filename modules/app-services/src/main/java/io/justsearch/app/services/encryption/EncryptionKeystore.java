/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.encryption;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 629 (LAYER) — the durable at-rest keystore file at {@code <dataDir>/encryption/keystore.json}.
 *
 * <p>Persists UNCONDITIONALLY (atomic temp+rename, mirroring {@code FileOperationLog.atomicWrite}). It
 * deliberately does NOT use {@code UiSettingsStore}/{@code DurableGrantStore}, which resolve to
 * IN_MEMORY in prod (629 §L5/§P5B — the trap that would silently drop key material). Uses the
 * codebase's Jackson 3 mapper ({@code tools.jackson}).
 */
public final class EncryptionKeystore {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private final Path file;

  public EncryptionKeystore(Path dataDir) {
    this.file = Objects.requireNonNull(dataDir, "dataDir").resolve("encryption").resolve("keystore.json");
  }

  public boolean exists() {
    return Files.exists(file);
  }

  public Optional<KeystoreRecord> load() {
    if (!Files.exists(file)) {
      return Optional.empty();
    }
    return Optional.of(MAPPER.readValue(file.toFile(), KeystoreRecord.class));
  }

  public void save(KeystoreRecord record) {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      MAPPER.writeValue(tmp.toFile(), record);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("failed to write encryption keystore", e);
    }
  }
}
