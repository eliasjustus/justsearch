package io.justsearch.app.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 501 §12.3 — backward-compatibility regression guard for the runtime manifest
 * schema.
 *
 * <p>The §12.3 rule: the manifest is a projection of canonical state, schema evolution
 * is view-shape widening, and the compatibility class is *backward* — an older reader
 * tolerates fields it doesn't know about. This test asserts that property at the
 * record-API level:
 *
 * <ol>
 *   <li>A "v1-only" reader (Jackson configured to <em>fail</em> on unknown properties)
 *       refuses to parse a manifest with fields beyond schema v1. Sanity check that the
 *       guard would actually fire if the producer started writing structurally
 *       incompatible fields.
 *   <li>A "tolerant" reader (Jackson configured to ignore unknown properties — the FE
 *       and our own Jackson defaults) parses a manifest with future fields cleanly. This
 *       is the property the §12.3 widening rule depends on.
 *   <li>An older v1 manifest body (no lifecycle / worker.state / ai fields) parses into
 *       the current `RuntimeManifest` record with absent fields surfaced as null — the
 *       backward direction.
 * </ol>
 *
 * <p>If a future contributor adds a required field without a default to {@link
 * RuntimeManifest} or its sub-records, this test fails — the contributor is forced to
 * either make the field nullable, give it a default, or bump {@code CURRENT_SCHEMA_VERSION}
 * and document the break.
 */
class RuntimeManifestSchemaCompatibilityTest {

  private static final ObjectMapper TOLERANT =
      JsonMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .build();

  private static final ObjectMapper STRICT =
      JsonMapper.builder()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
          .build();

  @Test
  void v1OnlyBodyParsesIntoCurrentRecord() throws Exception {
    // Hand-written "v1 minimal" body — the manifest as it shipped in Phase 1, before
    // Phase 12 added lifecycle and Phase 13 added ai. Such a body must still parse cleanly
    // into the current `RuntimeManifest` record under the tolerant reader.
    String v1Body =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000001\",\n"
            + "  \"pid\": 1234,\n"
            + "  \"startedAt\": \"2026-01-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/v1\",\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 12345,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:12345\",\n"
            + "    \"readyAt\": \"2026-01-01T00:00:01Z\"\n"
            + "  }\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(v1Body, RuntimeManifest.class);

    assertEquals(1, parsed.schemaVersion());
    assertEquals(12345, parsed.head().apiPort());
    // The new (Phase 12+13) fields are null when an older body is read.
    assertEquals(null, parsed.lifecycle(), "older bodies have no lifecycle field");
    assertEquals(null, parsed.worker(), "older bodies have no worker sub-record");
    assertEquals(null, parsed.ai(), "older bodies have no ai sub-record");
  }

  @Test
  void futureFieldsAreToleratedByForwardCompatibleReader() throws Exception {
    // Same producer body BUT with a hypothetical future field at the top level + nested.
    // The §12.3 rule: a tolerant reader must accept these without error so older consumers
    // can keep running against newer producers.
    String futureBody =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000002\",\n"
            + "  \"pid\": 5678,\n"
            + "  \"startedAt\": \"2026-06-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/future\",\n"
            + "  \"lifecycle\": \"READY\",\n"
            + "  \"hypotheticalFutureField\": {\"shape\": \"unknown\"},\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 59999,\n"
            + "    \"apiBaseUrl\": \"http://127.0.0.1:59999\",\n"
            + "    \"readyAt\": \"2026-06-01T00:00:01Z\",\n"
            + "    \"unknownHeadField\": 42\n"
            + "  }\n"
            + "}";

    RuntimeManifest parsed = TOLERANT.readValue(futureBody, RuntimeManifest.class);

    assertNotNull(parsed.head());
    assertEquals("READY", parsed.lifecycle());
    assertEquals(59999, parsed.head().apiPort());
  }

  @Test
  void strictReaderRefusesUnknownFields() {
    // Sanity check that the guard would actually fire if a reader needed strict parsing.
    // This is the failure shape an over-conservative consumer would see.
    String body =
        "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"instanceId\": \"00000000-0000-0000-0000-000000000003\",\n"
            + "  \"pid\": 1,\n"
            + "  \"startedAt\": \"2026-01-01T00:00:00Z\",\n"
            + "  \"dataDir\": \"/tmp/strict\",\n"
            + "  \"unknownField\": true,\n"
            + "  \"head\": {\n"
            + "    \"apiPort\": 1,\n"
            + "    \"apiBaseUrl\": \"x\",\n"
            + "    \"readyAt\": \"2026-01-01T00:00:01Z\"\n"
            + "  }\n"
            + "}";

    boolean threw = false;
    try {
      STRICT.readValue(body, RuntimeManifest.class);
    } catch (Exception e) {
      threw = true;
    }
    assertTrue(
        threw,
        "Strict (FAIL_ON_UNKNOWN_PROPERTIES) reader must refuse unknown fields. "
            + "This sanity-checks that the tolerance the tempdoc §12.3 backward-compat rule "
            + "depends on is an explicit Jackson choice, not a default the codebase happens "
            + "to inherit.");
  }
}
