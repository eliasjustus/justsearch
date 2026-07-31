/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedbackCaptureSettingsTest {
  @TempDir Path tempDir;

  @Test
  void missingPreferenceDefaultsOn() {
    assertTrue(new FeedbackCaptureSettings(tempDir).isEnabled());
  }

  @Test
  void legacyV0PreferenceLoads() throws Exception {
    Files.writeString(tempDir.resolve("feedback-capture.json"), "{\"enabled\":false}");
    FeedbackCaptureSettings settings = new FeedbackCaptureSettings(tempDir);
    assertFalse(settings.isEnabled());
    assertFalse(settings.isWriteLocked());
  }

  @Test
  void saveWritesV1AndRoundTrips() throws Exception {
    FeedbackCaptureSettings settings = new FeedbackCaptureSettings(tempDir);
    settings.setEnabled(false);
    String persisted = Files.readString(tempDir.resolve("feedback-capture.json"));
    assertTrue(persisted.contains("\"schemaVersion\":1"));
    assertFalse(new FeedbackCaptureSettings(tempDir).isEnabled());
  }

  @Test
  void malformedPreferenceDisablesCaptureAndLocksWrites() throws Exception {
    Path file = tempDir.resolve("feedback-capture.json");
    String malformed = "{not-json";
    Files.writeString(file, malformed);
    FeedbackCaptureSettings settings = new FeedbackCaptureSettings(tempDir);

    assertFalse(settings.isEnabled());
    assertTrue(settings.isWriteLocked());
    assertTrue(settings.persistenceError().isPresent());
    assertThrows(CorruptDurableStoreException.class, () -> settings.setEnabled(true));
    assertEquals(malformed, Files.readString(file));
  }

  @Test
  void futurePreferenceDisablesCaptureAndLocksWrites() throws Exception {
    Files.writeString(
        tempDir.resolve("feedback-capture.json"),
        "{\"schemaVersion\":99,\"enabled\":true}");
    FeedbackCaptureSettings settings = new FeedbackCaptureSettings(tempDir);
    assertFalse(settings.isEnabled());
    assertTrue(settings.isWriteLocked());
  }
}
