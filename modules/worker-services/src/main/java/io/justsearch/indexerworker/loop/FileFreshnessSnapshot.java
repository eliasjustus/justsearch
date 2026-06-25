/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Lightweight file identity and metadata snapshot for Worker-side stale-source detection. */
record FileFreshnessSnapshot(
    Path originalPath,
    String normalizedPath,
    String pathHash,
    Object fileKey,
    long sizeBytes,
    long modifiedAtMs,
    boolean regularFile,
    long observedAtMs) {

  static FileFreshnessSnapshot capture(Path path) throws IOException {
    Objects.requireNonNull(path, "path");
    Path absolute = path.toAbsolutePath().normalize();
    BasicFileAttributes attrs =
        Files.readAttributes(absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return new FileFreshnessSnapshot(
        absolute,
        PathNormalizer.normalizePath(absolute.toString()),
        pathHash(PathNormalizer.normalizePath(absolute.toString())),
        attrs.fileKey(),
        attrs.size(),
        attrs.lastModifiedTime().toMillis(),
        attrs.isRegularFile(),
        System.currentTimeMillis());
  }

  static FileFreshnessSnapshot fromEnvelope(FileEnvelope envelope) {
    return new FileFreshnessSnapshot(
        envelope.originalPath(),
        envelope.normalizedPath(),
        envelope.pathHash(),
        envelope.fileKey(),
        envelope.sizeBytes(),
        envelope.modifiedAtMs(),
        envelope.regularFile(),
        envelope.observedAtMs());
  }

  SourceValidationResult validateNow() {
    try {
      FileFreshnessSnapshot current = capture(originalPath);
      return compare(current);
    } catch (IOException e) {
      return SourceValidationResult.DELETED;
    }
  }

  private SourceValidationResult compare(FileFreshnessSnapshot current) {
    // The pure classification law lives in FileFreshness (tempdoc 555 seam).
    return FileFreshness.classify(this, current);
  }

  enum SourceValidationResult {
    FRESH,
    DELETED,
    SIZE_CHANGED,
    MODIFIED_TIME_CHANGED,
    FILE_KEY_CHANGED,
    SOURCE_KIND_CHANGED
  }

  static String pathHash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
