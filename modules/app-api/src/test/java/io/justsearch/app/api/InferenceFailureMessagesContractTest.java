package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 518 Appendix F W2.3 — pin the contract that every {@link InferenceFailure}
 * wireCode has an i18n entry in {@code messages/inference-failures.en.properties}.
 *
 * <p>The properties file is served at {@code GET /api/messages/inference-failures/en} via
 * {@code MessageCatalogController}. Frontend looks up
 * {@code "inference-failures." + failure.wireCode()} for display copy.
 *
 * <p>Contract:
 * <ul>
 *   <li>Every distinct wireCode across StartupCode / HealthCode / ConfigCode /
 *       TransitionCode must have a key {@code inference-failures.<wireCode>}.
 *   <li>No orphan keys (keys that don't match any code enum).
 * </ul>
 */
@DisplayName("inference-failures.en.properties — wireCode coverage contract")
final class InferenceFailureMessagesContractTest {

  private static final String RESOURCE = "/messages/inference-failures.en.properties";

  @Test
  @DisplayName("every wireCode across all four code enums has a properties entry")
  void everyWireCodeHasMessage() throws IOException {
    Set<String> wireCodes = collectAllWireCodes();
    Set<String> keys = loadKeys();

    Set<String> missing = new TreeSet<>();
    for (String wc : wireCodes) {
      if (!keys.contains("inference-failures." + wc)) {
        missing.add(wc);
      }
    }
    assertTrue(
        missing.isEmpty(),
        () ->
            "missing inference-failures.* entries for wireCodes: "
                + missing
                + " (file: "
                + RESOURCE
                + ")");
  }

  @Test
  @DisplayName("no orphan inference-failures.* keys (every key matches a wireCode)")
  void noOrphanKeys() throws IOException {
    Set<String> wireCodes = collectAllWireCodes();
    Set<String> keys = loadKeys();

    Set<String> orphans = new TreeSet<>();
    for (String key : keys) {
      if (!key.startsWith("inference-failures.")) {
        orphans.add(key);
        continue;
      }
      String wc = key.substring("inference-failures.".length());
      if (!wireCodes.contains(wc)) {
        orphans.add(key);
      }
    }
    assertTrue(
        orphans.isEmpty(),
        () -> "orphan keys not matching any wireCode: " + orphans + " (file: " + RESOURCE + ")");
  }

  @Test
  @DisplayName("entry count equals distinct-wireCode count")
  void countMatches() throws IOException {
    int expected = collectAllWireCodes().size();
    int actual = loadKeys().size();
    assertEquals(expected, actual, "entry count drift; check missing/orphan tests for details");
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static Set<String> collectAllWireCodes() {
    Set<String> wireCodes = new HashSet<>();
    for (StartupCode c : StartupCode.values()) wireCodes.add(c.wireValue());
    for (HealthCode c : HealthCode.values()) wireCodes.add(c.wireValue());
    for (ConfigCode c : ConfigCode.values()) wireCodes.add(c.wireValue());
    for (TransitionCode c : TransitionCode.values()) wireCodes.add(c.wireValue());
    return wireCodes;
  }

  private static Set<String> loadKeys() throws IOException {
    Properties props = new Properties();
    try (InputStream is =
            InferenceFailureMessagesContractTest.class.getResourceAsStream(RESOURCE);
        InputStreamReader reader =
            new InputStreamReader(
                requireNonNull(is, "missing resource: " + RESOURCE), StandardCharsets.UTF_8)) {
      props.load(reader);
    }
    return new HashSet<>(props.stringPropertyNames());
  }

  private static <T> T requireNonNull(T obj, String message) {
    if (obj == null) {
      throw new IllegalStateException(message);
    }
    return obj;
  }
}
