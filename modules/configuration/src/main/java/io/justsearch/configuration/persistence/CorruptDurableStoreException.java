/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

/** Raised when authored durable state is present but cannot be parsed or validated safely. */
public final class CorruptDurableStoreException extends IllegalStateException {
  private final String storeId;

  public CorruptDurableStoreException(String storeId, String message) {
    super("Corrupt durable store " + storeId + ": " + message);
    this.storeId = storeId;
  }

  public CorruptDurableStoreException(String storeId, String message, Throwable cause) {
    super("Corrupt durable store " + storeId + ": " + message, cause);
    this.storeId = storeId;
  }

  public String storeId() {
    return storeId;
  }
}
