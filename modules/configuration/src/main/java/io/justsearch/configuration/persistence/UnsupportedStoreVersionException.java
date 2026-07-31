/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

/** Raised when durable state was written by a format this binary cannot safely read. */
public final class UnsupportedStoreVersionException extends IllegalStateException {
  private final String storeId;
  private final int observedVersion;
  private final int currentVersion;

  public UnsupportedStoreVersionException(
      String storeId, int observedVersion, int currentVersion) {
    super(
        "Unsupported "
            + storeId
            + " schema version "
            + observedVersion
            + " (this binary supports through "
            + currentVersion
            + ")");
    this.storeId = storeId;
    this.observedVersion = observedVersion;
    this.currentVersion = currentVersion;
  }

  public String storeId() {
    return storeId;
  }

  public int observedVersion() {
    return observedVersion;
  }

  public int currentVersion() {
    return currentVersion;
  }
}
