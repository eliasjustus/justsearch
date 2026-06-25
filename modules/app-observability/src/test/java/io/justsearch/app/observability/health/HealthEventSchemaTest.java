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
import io.justsearch.agent.api.registry.I18nKey;
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
 * Schema generation for the {@link HealthEvent} wire-format type.
 *
 * <p>Per tempdoc 430 §A.1 + Phase 1 of §A.10. Mirrors {@code SubstrateSchemaGenTest}'s
 * capture-or-verify pattern (RESPECT_JSONPROPERTY_ORDER + RESPECT_JSONPROPERTY_REQUIRED).
 * The {@link HealthEventBody} sealed type produces an {@code anyOf} schema with
 * {@code const}-typed {@code kind} discriminator per the verified pattern in tempdoc 429
 * §A.3 + §E.10.
 *
 * <p>Capture-or-verify mode:
 *
 * <ul>
 *   <li>If a baseline doesn't exist: writes it and fails with "captured; re-run."
 *   <li>If a baseline exists: verifies the current output matches.
 * </ul>
 *
 * <p>Suppresses the {@code JacksonModule} deprecation warning per tempdoc 429 §E.11
 * (codebase-wide tech debt; not a slice-1.1.a blocker).
 */
@SuppressWarnings("removal")
@DisplayName("HealthEvent schema generation")
final class HealthEventSchemaTest {

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
    // Per slice 438 §B.C: OperationRef is a single-field record with @JsonValue String value
    // — it serializes as a bare string at runtime. Victools' JacksonModule honors
    // @JsonValue for serialization but emits {type: "object"} for the schema. Override
    // to emit {type: "string"} with the namespace pattern, mirroring the precedent in
    // SubstrateSchemaGenTest.
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
              // Tempdoc 564 Phase 1: MetricRef.label is an Optional<I18nKey>; I18nKey is a
              // @JsonValue String value-class, so it serializes as a bare string on the wire (or
              // is absent/null when the Optional is empty) — NOT victools' default empty object.
              // Emit the true string contract so the generated FE type/Zod accept the real wire
              // value (mirrors WireSchemaConfig.valueClassDefinition; unify per observations.md).
              if (javaType.getErasedType() == I18nKey.class) {
                ObjectNode node = context.getGeneratorConfig().createObjectNode();
                node.put(SchemaKeyword.TAG_TYPE.forVersion(SchemaVersion.DRAFT_2020_12), "string");
                node.put(SchemaKeyword.TAG_LENGTH_MIN.forVersion(SchemaVersion.DRAFT_2020_12), 1);
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
  @DisplayName("HealthEvent schema generates with anyOf discriminator on body kind")
  void healthEventSchema() throws Exception {
    captureOrVerify(HealthEvent.class, "health-event.v1.json");
  }

  private static void captureOrVerify(Class<?> type, String fileName) throws IOException {
    JsonNode current = schemaGenerator.generateSchema(type);
    String currentJson =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current);
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
    assertTrue(
        baseline.has("$schema"),
        "Baseline schema should declare $schema");
  }
}
