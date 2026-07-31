/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.InstalledPacksRecord;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledPacksStoreTest {
  @TempDir Path tempDir;

  @Test
  void missingRecordIsAnEmptyDerivedProjection() {
    InstalledPacksRecord record = new InstalledPacksStore(tempDir).load();
    assertNotNull(record);
    assertTrue(record.packs.isEmpty());
  }

  @Test
  void roundTripWritesSchemaV1Atomically() throws Exception {
    InstalledPacksStore store = new InstalledPacksStore(tempDir);
    InstalledPacksRecord record = new InstalledPacksRecord();
    InstalledPacksRecord.InstalledPack pack = new InstalledPacksRecord.InstalledPack();
    pack.packId = "core";
    pack.packVersion = "1.0.0";
    record.packs.add(pack);

    store.save(record);

    assertTrue(Files.readString(store.recordPath()).contains("\"schemaVersion\" : 1"));
    assertEquals("core", new InstalledPacksStore(tempDir).load().packs.get(0).packId);
  }

  @Test
  void futureVersionIsRefusedWithoutOverwrite() throws Exception {
    Path file = tempDir.resolve("installed-packs.v1.json");
    String future = "{\"schemaVersion\":99,\"packs\":[]}";
    Files.writeString(file, future);
    InstalledPacksStore store = new InstalledPacksStore(tempDir);
    assertThrows(UnsupportedStoreVersionException.class, store::load);
    assertEquals(future, Files.readString(file));
  }

  @Test
  void malformedRecordIsRefusedWithoutOverwrite() throws Exception {
    Path file = tempDir.resolve("installed-packs.v1.json");
    String malformed = "{not-json";
    Files.writeString(file, malformed);
    InstalledPacksStore store = new InstalledPacksStore(tempDir);
    assertThrows(CorruptDurableStoreException.class, store::load);
    assertEquals(malformed, Files.readString(file));
  }

  @Test
  void incompatibleDerivedProjectionCanBeRetainedAndRegenerated() throws Exception {
    Path file = tempDir.resolve("installed-packs.v1.json");
    String future = "{\"schemaVersion\":99,\"packs\":[]}";
    Files.writeString(file, future);
    InstalledPacksStore store = new InstalledPacksStore(tempDir);

    Path retained = store.reconcileIncompatibleProjection();

    assertEquals(future, Files.readString(retained));
    assertTrue(store.load().packs.isEmpty());
    assertTrue(Files.readString(file).contains("\"schemaVersion\" : 1"));
  }
}
