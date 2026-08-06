/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import java.util.Arrays;
import java.util.Objects;

/** Shared validation for owner-local durable-store format readers. */
public final class StoreFormatVersions {
  private StoreFormatVersions() {}

  /**
   * Resolve an absent version to the explicitly declared legacy version and require that the result
   * is either current or one of the readable legacy versions.
   */
  public static int requireReadable(
      String storeId,
      Integer observedVersion,
      int currentVersion,
      int absentVersion,
      int... readableLegacyVersions) {
    Objects.requireNonNull(storeId, "storeId");
    int resolved = observedVersion == null ? absentVersion : observedVersion;
    if (resolved < 0) {
      throw new CorruptDurableStoreException(storeId, "negative schema version " + resolved);
    }
    if (resolved == currentVersion
        || Arrays.stream(readableLegacyVersions).anyMatch(version -> version == resolved)) {
      return resolved;
    }
    throw new UnsupportedStoreVersionException(storeId, resolved, currentVersion);
  }
}
