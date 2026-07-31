/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StoreFormatVersionsTest {
  @Test
  void absentVersionMapsOnlyToDeclaredLegacyVersion() {
    assertEquals(0, StoreFormatVersions.requireReadable("settings", null, 1, 0, 0));
  }

  @Test
  void currentAndDeclaredLegacyVersionsAreReadable() {
    assertEquals(3, StoreFormatVersions.requireReadable("runs", 3, 4, 0, 0, 1, 2, 3));
    assertEquals(4, StoreFormatVersions.requireReadable("runs", 4, 4, 0, 0, 1, 2, 3));
  }

  @Test
  void undeclaredOldVersionIsRejected() {
    assertThrows(
        UnsupportedStoreVersionException.class,
        () -> StoreFormatVersions.requireReadable("runs", 2, 4, 0, 0, 1, 3));
  }

  @Test
  void futureVersionIsRejectedWithEvidence() {
    UnsupportedStoreVersionException error =
        assertThrows(
            UnsupportedStoreVersionException.class,
            () -> StoreFormatVersions.requireReadable("runs", 5, 4, 0, 0, 1, 2, 3));
    assertEquals("runs", error.storeId());
    assertEquals(5, error.observedVersion());
    assertEquals(4, error.currentVersion());
  }

  @Test
  void negativeVersionIsCorrupt() {
    assertThrows(
        CorruptDurableStoreException.class,
        () -> StoreFormatVersions.requireReadable("runs", -1, 4, 0, 0, 1, 2, 3));
  }
}
