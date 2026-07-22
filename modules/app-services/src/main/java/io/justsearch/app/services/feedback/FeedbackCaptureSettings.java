/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 778 — the default-on local flag governing implicit-feedback capture (the 580 §17
 * disposition stream). Loopback-privacy is the product's story AND the constraint: nothing captured
 * ever leaves the machine, and the user can turn even local capture off.
 *
 * <p>Persisted as a one-key preference file {@code <dataDir>/feedback-capture.json} — a SIBLING of the
 * AUTHORED {@code feedback/} data dir, not inside it (a preference, not captured data). Default ON: a
 * missing/blank/unreadable file reads as enabled, so day-one usage is captured without a setup step.
 * The value is cached after first read; {@link #setEnabled} updates cache + file together.
 */
public final class FeedbackCaptureSettings {

  private static final Logger log = LoggerFactory.getLogger(FeedbackCaptureSettings.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
  private static final String FILE = "feedback-capture.json";

  /** The visible privacy note rendered in the settings surface (§B work item 1). */
  public static final String PRIVACY_NOTE =
      "Feedback capture is local-only. Your clicks, opens, and dwell time on search results and chat"
          + " citations are recorded on this machine to improve ranking over time. Nothing is ever"
          + " uploaded or sent over the network. You can turn this off at any time.";

  private final Path file;
  private volatile Boolean cached;

  public FeedbackCaptureSettings(Path dataDir) {
    this.file = dataDir.resolve(FILE);
  }

  /** True unless the user explicitly turned capture off. Default-on (§C acceptance). */
  public boolean isEnabled() {
    Boolean c = cached;
    if (c != null) {
      return c;
    }
    boolean value = readEnabled();
    cached = value;
    return value;
  }

  /** Persist the flag (cache + file). Best-effort — a write failure leaves the cache authoritative. */
  public synchronized void setEnabled(boolean enabled) {
    cached = enabled;
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(
          file, MAPPER.writeValueAsString(Map.of("enabled", enabled)), StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.warn("Failed to persist feedback-capture flag: {}", e.toString());
    }
  }

  private boolean readEnabled() {
    if (!Files.exists(file)) {
      return true; // default-on
    }
    try {
      String raw = Files.readString(file, StandardCharsets.UTF_8);
      if (raw.isBlank()) {
        return true;
      }
      Object enabled = MAPPER.readValue(raw, MAP_REF).get("enabled");
      return !(enabled instanceof Boolean b) || b; // any non-false value → on
    } catch (Exception e) {
      log.warn("Failed to read feedback-capture flag ({}); defaulting on", e.toString());
      return true;
    }
  }
}
