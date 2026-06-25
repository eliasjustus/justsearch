package io.justsearch.app.observability.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Schema generation for the {@link RuntimeContext} wire-format type.
 *
 * <p>Per slice 440 (Runtime mode STATE Resource): mirrors {@code HealthEventSchemaTest}'s
 * capture-or-verify pattern. The baseline at {@code SSOT/schemas/runtime-context.v1.json}
 * pins the wire payload shape advertised by {@link RuntimeContextResourceCatalog#SCHEMA_URL}.
 */
@SuppressWarnings("removal")
@DisplayName("RuntimeContext schema generation")
final class RuntimeContextSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static Path schemasDir;

  @BeforeAll
  static void setupSchemaGenerator() {
    JacksonModule jacksonModule =
        new JacksonModule(
            JacksonOption.RESPECT_JSONPROPERTY_ORDER,
            JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
    SchemaGeneratorConfigBuilder configBuilder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(jacksonModule);
    SchemaGeneratorConfig config = configBuilder.build();
    schemaGenerator = new SchemaGenerator(config);
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
  @DisplayName("RuntimeContext schema baseline matches generated output")
  void runtimeContextSchema() throws Exception {
    captureOrVerify(RuntimeContext.class, "runtime-context.v1.json");
  }

  private static void captureOrVerify(Class<?> type, String fileName) throws IOException {
    JsonNode current = schemaGenerator.generateSchema(type);
    String currentJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current);
    Path path = schemasDir.resolve(fileName);

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
        "Schema for "
            + type.getSimpleName()
            + " diverged from baseline at "
            + path
            + ". If intended, delete the baseline and re-run to recapture.");
    assertTrue(baseline.has("$schema"), "Baseline schema should declare $schema");
  }
}
