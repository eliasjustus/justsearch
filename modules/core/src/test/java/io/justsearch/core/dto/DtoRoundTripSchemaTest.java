package io.justsearch.core.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DtoRoundTripSchemaTest {
  private static final ObjectMapper M = new ObjectMapper();

  @Test
  void queryRoundTripAndSchema() throws Exception {
    Query q = new Query(
        10,
        0,
        true,
        new Query.Filters("application/pdf", "en",
            new Query.TimeRange(1729660800000L, null)),
        List.of("-date"),
        List.of(new Query.Clause("term", "title", "file system", List.of("file", "system"))),
        null);

    String json = M.writeValueAsString(q);

    Schema schema = schema("SSOT/schemas/domain/search-intent.schema.json");
    var node = M.readTree(json);
    var violations = schema.validate(node);
    assertEquals(0, violations.size(), () -> "schema violations: " + join(violations));

    Query again = M.readValue(json, Query.class);
    assertEquals(q, again, () -> "Round trip mismatch: " + again);
  }

  @Test
  void resultRoundTripAndSchema() throws Exception {
    Result r =
        new Result(
            List.of(new Result.Hit("A1", 12.34d, Map.of("content", List.of("...")))),
            Map.of("mime", Map.of("pdf", 42)),
            new Cursor("pit", "pit-token", 1762069200000L, Map.of("pit_ttl_ms", 300000)),
            Map.of());

    String json = M.writeValueAsString(r);

    Schema schema = schema("SSOT/schemas/domain/result.schema.json");
    var node = M.readTree(json);
    var violations = schema.validate(node);
    assertEquals(0, violations.size(), () -> "schema violations: " + join(violations));

    Result again = M.readValue(json, Result.class);
    assertEquals(r, again, () -> "Round trip mismatch: " + again);
  }

  private static Schema schema(String relative) throws Exception {
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    File f = new File(repoRoot(), relative);
    var ctx = new com.networknt.schema.SchemaContext(
        registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    var schemaNode = M.readTree(f);
    return ctx.newSchema(
        com.networknt.schema.SchemaLocation.of(f.toURI().toString()), schemaNode, null);
  }

  private static String join(java.util.List<com.networknt.schema.Error> messages) {
    return messages.stream().map(com.networknt.schema.Error::getMessage).sorted().reduce((a, b) -> a + "; " + b).orElse("");
  }

  private static File repoRoot() {
    File f = new File(".").getAbsoluteFile();
    while (f != null) {
      if (new File(f, "SSOT").isDirectory()) {
        return f;
      }
      f = f.getParentFile();
    }
    throw new IllegalStateException("Repo root not found (no SSOT directory)");
  }
}
