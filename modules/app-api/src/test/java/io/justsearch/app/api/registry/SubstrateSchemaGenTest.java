package io.justsearch.app.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.SchemaGenerator;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Prompt;
import io.justsearch.agent.api.registry.Provenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Schema generation for substrate types (Operation, Resource, Prompt) per tempdoc 429
 * Phase 6 + §E.10 verified-empirical pattern.
 *
 * <p>Emits schemas to {@code SSOT/schemas/operation.v1.json},
 * {@code resource.v1.json}, {@code prompt.v1.json}. Uses the same victools + Jackson 3
 * configuration as {@code StatusRecordSchemaTest} (RESPECT_JSONPROPERTY_ORDER +
 * RESPECT_JSONPROPERTY_REQUIRED). Sealed-type variants (RegistryEntry, ConfirmStrategy,
 * UIHint, RequiredCapability) produce {@code anyOf} schemas with {@code const} type
 * discriminators per §A.3 + §E.10 captured findings.
 *
 * <p>Capture-or-verify mode mirrors the Phase 0 baseline test:
 *
 * <ul>
 *   <li>If a baseline doesn't exist: writes it and fails with "captured; re-run."
 *   <li>If a baseline exists: verifies the current output matches.
 * </ul>
 *
 * <p>Suppresses the {@code JacksonModule} deprecation warning per §E.11
 * (codebase-wide tech debt; not a slice-1.2 blocker).
 */
@SuppressWarnings("removal")
@DisplayName("Substrate schema generation")
final class SubstrateSchemaGenTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static Path schemasDir;

  @BeforeAll
  static void setupSchemaGenerator() {
    // Tempdoc 564: shared precise victools config (value-class string overrides + typed map-values).
    schemaGenerator = io.justsearch.app.api.schema.WireSchemaConfig.generator();
    // Walk up from the module's working dir until SSOT/schemas/ is found.
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    schemasDir =
        cursor == null
            ? Path.of("SSOT/schemas").toAbsolutePath()
            : cursor.resolve("SSOT/schemas");
  }

  @Test
  @DisplayName("Operation schema generates with anyOf discriminator")
  void operationSchema() throws Exception {
    captureOrVerify(Operation.class, "operation.v1.json");
  }

  @Test
  @DisplayName("Resource wire schema generates from the typed UIResourceView (tempdoc 560 §4c)")
  void resourceSchema() throws Exception {
    captureOrVerify(
        io.justsearch.agent.api.registry.UIResourceView.class, "resource.v1.json");
  }

  @Test
  @DisplayName("Prompt schema generates with anyOf discriminator")
  void promptSchema() throws Exception {
    captureOrVerify(Prompt.class, "prompt.v1.json");
  }

  @Test
  @DisplayName("Presentation schema generates (standalone — FE imports it by name)")
  void presentationSchema() throws Exception {
    captureOrVerify(Presentation.class, "presentation.v1.json");
  }

  @Test
  @DisplayName("Provenance schema generates (standalone — FE imports it by name)")
  void provenanceSchema() throws Exception {
    captureOrVerify(Provenance.class, "provenance.v1.json");
  }

  @Test
  @DisplayName("UIOperationView schema generates (tempdoc 560 §4c Phase B — the Operation wire)")
  void operationViewSchema() throws Exception {
    captureOrVerify(
        io.justsearch.agent.api.registry.UIOperationView.class, "operation-wire.v1.json");
  }

  @Test
  @DisplayName("DiagnosticChannel wire schema generates from the typed UIDiagnosticChannelView (tempdoc 560 §4c)")
  void diagnosticChannelSchema() throws Exception {
    captureOrVerify(
        io.justsearch.agent.api.registry.UIDiagnosticChannelView.class, "diagnostic-channel.v1.json");
  }

  private static void captureOrVerify(Class<?> type, String fileName) throws IOException {
    JsonNode current = schemaGenerator.generateSchema(type);
    String currentJson =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current);
    Path path = schemasDir.resolve(fileName);

    // Tempdoc 564: under -PupdateSchemas=true (the `:updateSchemas` task), rewrite the baseline.
    if ("true".equals(System.getProperty("updateSchemas"))) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + System.lineSeparator());
      return;
    }

    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + System.lineSeparator());
      fail(
          "Schema captured at "
              + path
              + ". Re-run to verify (this is expected on first run).");
    }

    String baselineJson = Files.readString(path);
    JsonNode baseline = MAPPER.readTree(baselineJson);
    assertEquals(
        baseline,
        current,
        "Schema for " + type.getSimpleName() + " diverged from baseline at " + path
            + ". If intended, delete the baseline and re-run to recapture.");
    assertTrue(
        baseline.has("$schema"),
        "Baseline schema should declare $schema");
  }

}
