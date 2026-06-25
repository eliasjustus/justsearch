package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drift gate for {@code SSOT/messages/errors.en.json}.
 *
 * <p>The JSON artifact under {@code SSOT/messages/} is a derived view of
 * {@code modules/app-api/src/main/resources/messages/errors.en.properties}. This test asserts
 * the artifact is in sync; when run with {@code -PupdateSchemas} (system property
 * {@code updateSchemas=true}), it overwrites the artifact instead — matching the discipline
 * used by {@link io.justsearch.app.api.status.StatusRecordSchemaTest} for {@code SSOT/schemas/}.
 *
 * <p>Per tempdoc 431 §Phase 1 (Scope item 5): ship the JSON artifact as part of the same
 * single source of truth, so cross-language consumers (TypeScript, tooling) can read it
 * without duplicating the properties parser.
 *
 * <p>Generated JSON shape (per tempdoc 434 §"Catalog file shape"):
 *
 * <pre>{@code
 * {
 *   "$schema": "https://ssot.justsearch/v1/schemas/i18n-catalog.json",
 *   "schemaVersion": "1.0",
 *   "locale": "en",
 *   "namespace": "errors",
 *   "messages": { "errors.<KEY>": "<MESSAGE>", ... }   // sorted alphabetically by key
 * }
 * }</pre>
 *
 * <p>Note: Jackson's {@code ORDER_MAP_ENTRIES_BY_KEYS} sorts envelope keys alphabetically;
 * {@code $schema} sorts before all other keys, matching 434's stated order.
 */
@DisplayName("SSOT/messages/errors.en.json artifact drift")
final class ErrorCatalogJsonArtifactTest {

  private static final String PROPERTIES_RESOURCE = "/messages/errors.en.properties";
  private static final String SSOT_ARTIFACT = "SSOT/messages/errors.en.json";
  private static final String SSOT_SCHEMA = "SSOT/schemas/i18n-catalog.json";
  private static final String SCHEMA_URI = "https://ssot.justsearch/v1/schemas/i18n-catalog.json";
  private static final String SCHEMA_VERSION = "1.0";
  private static final String LOCALE = "en";
  private static final String NAMESPACE = "errors";

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  @Test
  @DisplayName("SSOT/messages/errors.en.json matches the properties source (or write with -PupdateSchemas)")
  void artifactMatchesSource() throws IOException {
    String expected = buildExpectedJson();
    Path artifact = repoRoot().resolve(SSOT_ARTIFACT);

    if ("true".equals(System.getProperty("updateSchemas"))) {
      Files.createDirectories(artifact.getParent());
      Files.writeString(artifact, expected, StandardCharsets.UTF_8);
      return;
    }

    assertTrue(
        Files.isRegularFile(artifact),
        () -> "SSOT/messages/errors.en.json must exist — run "
            + "`./gradlew :modules:app-api:updateSchemas` to generate it.");

    String actual = Files.readString(artifact, StandardCharsets.UTF_8);
    assertEquals(
        expected,
        actual,
        () -> "SSOT/messages/errors.en.json is out of sync with errors.en.properties — "
            + "run `./gradlew :modules:app-api:updateSchemas` to regenerate.");
  }

  @Test
  @DisplayName("SSOT/messages/errors.en.json validates against SSOT/schemas/i18n-catalog.json")
  void artifactConformsToSchema() throws IOException {
    // Per tempdoc 431 §F.1: the catalog response carries `$schema` pointing to the
    // i18n-catalog schema. This test makes the schema real — it's loaded, parsed,
    // and used to validate the actual artifact. Closes the gap where `$schema` was
    // a decorative URL pointing at nothing.
    Path schemaPath = repoRoot().resolve(SSOT_SCHEMA);
    assertTrue(
        Files.isRegularFile(schemaPath),
        () -> "SSOT/schemas/i18n-catalog.json must exist (the schema document the "
            + "wire-emitted `$schema` URL claims).");

    Path artifactPath = repoRoot().resolve(SSOT_ARTIFACT);
    JsonNode schemaNode = MAPPER.readTree(Files.readString(schemaPath, StandardCharsets.UTF_8));
    JsonNode artifactNode = MAPPER.readTree(Files.readString(artifactPath, StandardCharsets.UTF_8));

    SchemaRegistry schemaRegistry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    SchemaContext ctx = new SchemaContext(
        schemaRegistry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()),
        schemaRegistry);
    Schema schema = ctx.newSchema(SchemaLocation.of(SCHEMA_URI), schemaNode, null);

    var errors = schema.validate(artifactNode);
    assertTrue(
        errors.isEmpty(),
        () -> "SSOT/messages/errors.en.json does not conform to "
            + "SSOT/schemas/i18n-catalog.json: " + errors);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private String buildExpectedJson() throws IOException {
    Properties props = new Properties();
    try (InputStream is = ErrorCatalogJsonArtifactTest.class.getResourceAsStream(PROPERTIES_RESOURCE);
         InputStreamReader reader = new InputStreamReader(
             requireNonNull(is, "Resource not found: " + PROPERTIES_RESOURCE),
             StandardCharsets.UTF_8)) {
      props.load(reader);
    }

    // Use TreeMap for deterministic alphabetical key order.
    Map<String, String> messages = new TreeMap<>();
    for (String name : props.stringPropertyNames()) {
      messages.put(name, props.getProperty(name));
    }

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("$schema", SCHEMA_URI);
    envelope.put("schemaVersion", SCHEMA_VERSION);
    envelope.put("locale", LOCALE);
    envelope.put("namespace", NAMESPACE);
    envelope.put("messages", messages);

    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(envelope) + "\n";
  }

  /**
   * Walks up from CWD to find the repo root (directory containing {@code settings.gradle.kts}).
   * Mirrors the helper in {@link io.justsearch.ui.api.ApiErrorCodeContractTest} (renamed).
   */
  private Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      if (Files.exists(dir.resolve("settings.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new AssertionError(
        "Cannot find repo root (no settings.gradle.kts in parent chain of "
            + Path.of("").toAbsolutePath() + ")");
  }

  private static <T> T requireNonNull(T obj, String message) throws IOException {
    if (obj == null) {
      throw new IOException(message);
    }
    return obj;
  }
}
