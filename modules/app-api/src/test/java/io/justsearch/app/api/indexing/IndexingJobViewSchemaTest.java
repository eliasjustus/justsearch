package io.justsearch.app.api.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.victools.jsonschema.generator.SchemaGenerator;
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
 * Capture-or-verify baseline for {@code IndexingJobView}'s wire-format JSON
 * schema. Slice 445 §A.8.
 *
 * <p>Mirrors the pattern from {@code HealthEventSchemaTest}: writes the
 * baseline at {@code SSOT/schemas/indexing-job-view.v1.json} on first run;
 * fails with "diverged" if the record shape changes without a baseline
 * refresh.
 */
@SuppressWarnings("removal")
@DisplayName("IndexingJobView schema generation")
final class IndexingJobViewSchemaTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static SchemaGenerator schemaGenerator;
  private static Path schemasDir;

  @BeforeAll
  static void setupSchemaGenerator() {
    // Tempdoc 911 review S2-2: this used to build its OWN plain victools config, so
    // `IndexingJobView`'s PreciseWire marker was a no-op here and this baseline said "all optional,
    // all nullable" while the same record inlined in failed-indexing-jobs-response.v1.json said
    // "all required, non-null" — two generated schemas disagreeing about one record, i.e. a fork.
    // The shared WireSchemaConfig is the single generator authority; this file now describes what
    // both producers (RemoteIndexingJobsBridge.toView, IndexingController.toJobView) actually emit.
    schemaGenerator = WireSchemaConfig.generator();
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
  @DisplayName("IndexingJobView schema captures the 8-field wire shape")
  void indexingJobViewSchema() throws Exception {
    captureOrVerify(IndexingJobView.class, "indexing-job-view.v1.json");
  }

  private static void captureOrVerify(Class<?> type, String fileName) throws IOException {
    JsonNode current = schemaGenerator.generateSchema(type);
    // tempdoc 696: force LF so Windows System.lineSeparator() doesn't churn committed files
    String currentJson =
        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(current).replace("\r\n", "\n");
    Path path = schemasDir.resolve(fileName);

    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson + "\n");
      fail("Schema captured at " + path + ". Re-run to verify (this is expected on first run).");
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
    assertTrue(baseline.has("$schema"), "Baseline should declare $schema");
  }
}
