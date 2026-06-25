package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@code modules/app-api/src/main/resources/messages/errors.en.properties}.
 *
 * <p>Asserts the three invariants per tempdoc 431 §Scope #3:
 *
 * <ol>
 *   <li>Every {@link ApiErrorCode} value has a key {@code errors.<NAME>} in the properties.
 *   <li>Every {@link AgentErrorCode} value has a key {@code errors.<NAME>} in the properties.
 *   <li>Every key in the properties matches some enum value (no orphans). {@code INTERNAL_ERROR}
 *       exists in both enums; one entry serves both — the test deduplicates accordingly.
 * </ol>
 *
 * <p>This test replaces the FE-side {@link io.justsearch.ui.api.ApiErrorCodeContractTest}
 * tests #1 and #2 (catalog↔enum sync). The backend properties file is now the source of truth;
 * the FE consumes the catalog at boot via {@code GET /api/error-catalog?locale=en}.
 */
@DisplayName("errors.en.properties ↔ ApiErrorCode + AgentErrorCode contract")
final class ErrorMessagePropertiesContractTest {

  private static final String RESOURCE_PATH = "/messages/errors.en.properties";
  private static final String KEY_PREFIX = "errors.";

  @Test
  @DisplayName("every ApiErrorCode value has a key in errors.en.properties")
  void everyApiErrorCodeHasPropertyKey() throws IOException {
    Set<String> propertyNames = loadPropertyNames();
    Set<String> missing = Arrays.stream(ApiErrorCode.values())
        .map(Enum::name)
        .map(name -> KEY_PREFIX + name)
        .filter(key -> !propertyNames.contains(key))
        .collect(Collectors.toCollection(LinkedHashSet::new));

    assertTrue(
        missing.isEmpty(),
        () -> "ApiErrorCode values without a key in errors.en.properties — "
            + "add an entry for each. Missing: " + missing);
  }

  @Test
  @DisplayName("every AgentErrorCode value has a key in errors.en.properties")
  void everyAgentErrorCodeHasPropertyKey() throws IOException {
    Set<String> propertyNames = loadPropertyNames();
    Set<String> missing = Arrays.stream(AgentErrorCode.values())
        .map(Enum::name)
        .map(name -> KEY_PREFIX + name)
        .filter(key -> !propertyNames.contains(key))
        .collect(Collectors.toCollection(LinkedHashSet::new));

    assertTrue(
        missing.isEmpty(),
        () -> "AgentErrorCode values without a key in errors.en.properties — "
            + "add an entry for each. Missing: " + missing);
  }

  @Test
  @DisplayName("every key in errors.en.properties matches some enum value (no orphans)")
  void noOrphanKeys() throws IOException {
    Set<String> propertyNames = loadPropertyNames();
    Set<String> validKeys = new HashSet<>();
    Arrays.stream(ApiErrorCode.values()).forEach(c -> validKeys.add(KEY_PREFIX + c.name()));
    Arrays.stream(AgentErrorCode.values()).forEach(c -> validKeys.add(KEY_PREFIX + c.name()));

    Set<String> orphans = propertyNames.stream()
        .filter(key -> !validKeys.contains(key))
        .collect(Collectors.toCollection(LinkedHashSet::new));

    assertTrue(
        orphans.isEmpty(),
        () -> "Keys in errors.en.properties that don't match any ApiErrorCode or "
            + "AgentErrorCode value — remove these or add the matching enum value. "
            + "Orphans: " + orphans);
  }

  @Test
  @DisplayName("expected key count matches the union of both enums (sanity check)")
  void expectedKeyCount() throws IOException {
    Set<String> propertyNames = loadPropertyNames();
    Set<String> expected = new HashSet<>();
    Arrays.stream(ApiErrorCode.values()).forEach(c -> expected.add(KEY_PREFIX + c.name()));
    Arrays.stream(AgentErrorCode.values()).forEach(c -> expected.add(KEY_PREFIX + c.name()));

    assertEquals(
        expected.size(),
        propertyNames.size(),
        () -> "Distinct key count must equal |ApiErrorCode ∪ AgentErrorCode| "
            + "(INTERNAL_ERROR is shared between both enums). Expected "
            + expected.size() + ", got " + propertyNames.size() + ".");
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /**
   * Loads the properties file via UTF-8 reader so non-ASCII characters
   * (em-dashes, curly quotes) survive without ISO-8859-1 corruption.
   */
  private Set<String> loadPropertyNames() throws IOException {
    Properties props = new Properties();
    try (InputStream is = ErrorMessagePropertiesContractTest.class.getResourceAsStream(RESOURCE_PATH);
         InputStreamReader reader = new InputStreamReader(
             requireResource(is), StandardCharsets.UTF_8)) {
      props.load(reader);
    }
    return props.stringPropertyNames();
  }

  private InputStream requireResource(InputStream is) throws IOException {
    if (is == null) {
      throw new IOException("Resource not found: " + RESOURCE_PATH);
    }
    return is;
  }
}
