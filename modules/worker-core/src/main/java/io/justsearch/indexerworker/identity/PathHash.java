/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** One implementation of the privacy-safe SHA-256 path key used by admission and identity. */
public final class PathHash {
  private PathHash() {}

  public static String sha256(String normalizedPath) {
    if (normalizedPath == null || normalizedPath.isBlank()) {
      throw new IllegalArgumentException("normalizedPath is required");
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(normalizedPath.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
