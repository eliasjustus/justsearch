package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.app.api.schema.WireSchemaConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 564 Phase 0 — JSON Schema for {@link KnowledgeSearchResponse}, the search wire record.
 *
 * <p>This is the *hardest faithfulness case* the de-risk (§11) identified: {@code facets}
 * ({@code Map<String,Map<String,Long>>}) and {@code entityFacetVariants}
 * ({@code Map<String,List<EntityVariantBreakdown>>}) — the map-of-map shape that proto3 cannot model
 * without a wrapper that changes the JSON. JSON Schema represents it faithfully (a bare nested
 * object), and {@link WireSchemaConfig}'s typed-map provider makes it *precise*
 * ({@code facets} → object → object → integer).
 *
 * <p>Emits {@code SSOT/schemas/knowledge-search-response.v1.json} (served to the FE by
 * {@code SchemaController}); capture-or-verify + {@code -PupdateSchemas=true} rewrite. The second
 * test is the record↔schema↔wire <b>faithfulness</b> check: the real wire fixture (captured from a
 * live backend) must validate against the generated schema.
 */
@DisplayName("KnowledgeSearchResponse JSON Schema generation + live-fixture conformance")
final class KnowledgeSearchResponseSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static SchemaRegistry schemaRegistry;
  private static Path repoRoot;

  @BeforeAll
  static void setup() {
    schemaGenerator = WireSchemaConfig.generator();
    schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    repoRoot = cursor;
  }

  @Test
  @DisplayName("knowledge-search-response.v1.json matches baseline (facets typed map-of-map)")
  void schemaMatchesBaseline() throws IOException {
    JsonNode current = schemaGenerator.generateSchema(KnowledgeSearchResponse.class);
    String currentJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current);
    Path path = repoRoot.resolve("SSOT/schemas/knowledge-search-response.v1.json");

    if ("true".equals(System.getProperty("updateSchemas"))) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + System.lineSeparator());
      return;
    }
    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + System.lineSeparator());
      fail("Schema captured at " + path + ". Re-run to verify (expected on first run).");
    }
    assertEquals(
        MAPPER.readTree(Files.readString(path)),
        current,
        "knowledge-search-response schema diverged from baseline. Regenerate with: "
            + "./gradlew.bat :modules:app-api:updateSchemas");
  }

  @Test
  @DisplayName("the real search wire fixture conforms to the generated schema (faithfulness)")
  void liveFixtureConforms() throws IOException {
    JsonNode schemaNode = schemaGenerator.generateSchema(KnowledgeSearchResponse.class);
    SchemaContext ctx =
        new SchemaContext(
            schemaRegistry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()),
            schemaRegistry);
    Schema schema =
        ctx.newSchema(SchemaLocation.of("mem://knowledge-search-response"), schemaNode, null);

    Path fixture =
        repoRoot.resolve("modules/ui-web/src/api/__fixtures__/search-response-live.json");
    JsonNode fixtureJson = MAPPER.readTree(Files.readString(fixture));
    var errors = schema.validate(fixtureJson);
    assertTrue(
        errors.isEmpty(),
        "The real search wire fixture does not conform to the generated KnowledgeSearchResponse "
            + "schema — the record↔schema↔wire faithfulness check (proves the schema describes the "
            + "actual JSON the FE receives): "
            + errors);
  }
}
