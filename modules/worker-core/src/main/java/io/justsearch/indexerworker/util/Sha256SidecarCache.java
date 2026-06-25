/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches SHA-256 hashes of large files using a sidecar file to avoid re-reading the entire file on
 * every Worker boot.
 *
 * <p>The sidecar file (e.g., {@code model.onnx.sha256}) stores the hash alongside the file's mtime
 * and size. On subsequent reads, if mtime+size match, the cached hash is returned without reading
 * the file (~1ms vs 100-400ms).
 *
 * <p>Writes are atomic (temp file + rename) so a crash during write leaves the old sidecar intact.
 */
public final class Sha256SidecarCache {
  private static final Logger log = LoggerFactory.getLogger(Sha256SidecarCache.class);

  private static final int BUFFER_SIZE = 8 * 1024 * 1024;
  private static final String SIDECAR_SUFFIX = ".sha256";

  private Sha256SidecarCache() {}

  /**
   * Returns the SHA-256 hash of the given file, using a sidecar cache to avoid redundant reads.
   *
   * @param file the file to hash
   * @return the 64-character hex SHA-256 hash, or empty if the file does not exist
   */
  public static Optional<String> getOrCompute(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      long size = Files.size(file);
      long mtimeMs = Files.getLastModifiedTime(file).toMillis();

      Path sidecar = file.resolveSibling(file.getFileName() + SIDECAR_SUFFIX);
      Optional<String> cached = readSidecar(sidecar, mtimeMs, size);
      if (cached.isPresent()) {
        log.debug("Sidecar cache hit for {} (mtime={}, size={})", file.getFileName(), mtimeMs, size);
        return cached;
      }

      long startMs = System.currentTimeMillis();
      String sha256 = computeSha256(file);
      long elapsedMs = System.currentTimeMillis() - startMs;
      log.info("Computed SHA-256 for {} in {}ms: {}", file.getFileName(), elapsedMs,
          sha256.substring(0, 16) + "...");

      writeSidecar(sidecar, sha256, mtimeMs, size);
      return Optional.of(sha256);
    } catch (IOException e) {
      log.warn("Failed to compute/cache SHA-256 for {}", file, e);
      return Optional.empty();
    }
  }

  /**
   * Reads the sidecar file and validates that mtime+size match the current file.
   *
   * <p>Sidecar format: {@code sha256:<hex> mtime:<epoch-millis> size:<bytes>}
   */
  private static Optional<String> readSidecar(Path sidecar, long expectedMtime, long expectedSize) {
    if (!Files.isRegularFile(sidecar)) {
      return Optional.empty();
    }
    try {
      String content = Files.readString(sidecar).strip();
      String hash = null;
      long mtime = -1;
      long size = -1;
      for (String part : content.split("\\s+")) {
        if (part.startsWith("sha256:")) {
          hash = part.substring("sha256:".length());
        } else if (part.startsWith("mtime:")) {
          mtime = Long.parseLong(part.substring("mtime:".length()));
        } else if (part.startsWith("size:")) {
          size = Long.parseLong(part.substring("size:".length()));
        }
      }
      if (hash != null && hash.length() == 64 && mtime == expectedMtime && size == expectedSize) {
        return Optional.of(hash);
      }
      if (hash != null) {
        log.debug("Sidecar stale: mtime {}→{}, size {}→{}", mtime, expectedMtime, size, expectedSize);
      }
      return Optional.empty();
    } catch (IOException | NumberFormatException e) {
      log.debug("Failed to read sidecar {}: {}", sidecar, e.getMessage());
      return Optional.empty();
    }
  }

  /** Writes the sidecar atomically via temp file + rename. */
  private static void writeSidecar(Path sidecar, String sha256, long mtimeMs, long size) {
    try {
      String content = "sha256:" + sha256 + " mtime:" + mtimeMs + " size:" + size + "\n";
      Path temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
      Files.writeString(temp, content);
      try {
        Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException e) {
        Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      log.debug("Failed to write sidecar {} (non-fatal): {}", sidecar, e.getMessage());
    }
  }

  private static String computeSha256(Path file) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[BUFFER_SIZE];
      try (InputStream in = Files.newInputStream(file)) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    }
  }
}
