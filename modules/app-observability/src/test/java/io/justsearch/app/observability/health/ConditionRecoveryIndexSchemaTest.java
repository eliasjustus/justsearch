package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import io.justsearch.agent.api.registry.NamespacedId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Schema generation for the {@link ConditionRecoveryIndex} wire-format type.
 *
 * <p>Per slice 447-impl-D + 447-followup/1.6: capture-or-verify pattern matching
 * {@code OperationHistorySchemaTest}. The baseline at
 * {@code SSOT/schemas/condition-recovery-index.v1.json} pins the wire payload shape
 * advertised by {@link ConditionRecoveryIndexCatalog#SCHEMA_URL}.
 *
 * <p>Closes the phantom-SCHEMA_URL defect: the catalog declared a schema URL pointing at
 * a file that didn't exist on disk. This test captures the schema baseline so the URL
 * resolves to a real document.
 */
@SuppressWarnings("removal")
@DisplayName("ConditionRecoveryIndex schema generation")
final class ConditionRecoveryIndexSchemaTest {

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
    // Namespaced primitive ids (OperationRef, ResourceRef, PromptRef, SurfaceRef,
    // DiagnosticChannelRef) all serialize as bare namespaced strings. Single
    // isAssignableFrom check covers all five via the NamespacedId sealed interface
    // (slice 447-followup/2.1).
    configBuilder
        .forTypesInGeneral()
        .withCustomDefinitionProvider(
            (javaType, context) -> {
              if (NamespacedId.class.isAssignableFrom(javaType.getErasedType())) {
                ObjectNode node = context.getGeneratorConfig().createObjectNode();
                node.put(
                    SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "string");
                node.put(
                    SchemaKeyword.TAG_PATTERN.forVersion(SchemaVersion.DRAFT_2020_12),
                    "^(core|vendor\\.[a-z][a-z0-9-]*)\\.[a-z][a-z0-9-]*$");
                return new CustomDefinition(node);
              }
              return null;
            });
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
  @DisplayName("ConditionRecoveryIndex schema baseline matches generated output")
  void conditionRecoveryIndexSchema() throws Exception {
    captureOrVerify(ConditionRecoveryIndex.class, "condition-recovery-index.v1.json");
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
