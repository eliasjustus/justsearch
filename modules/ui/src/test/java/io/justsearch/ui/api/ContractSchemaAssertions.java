/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared JSON-Schema assertion for executable HTTP contract tests. */
public final class ContractSchemaAssertions {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ContractSchemaAssertions() {}

  public static void assertConforms(String label, String schemaFile, String body) throws Exception {
    Path schemaPath = repoRoot().resolve("SSOT/schemas").resolve(schemaFile);
    assertTrue(Files.isRegularFile(schemaPath), () -> schemaPath + " must exist");
    JsonNode schemaNode = JSON.readTree(Files.readString(schemaPath, StandardCharsets.UTF_8));
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    SchemaContext context =
        new SchemaContext(
            registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    Schema schema =
        context.newSchema(
            SchemaLocation.of("https://ssot.justsearch/v1/schemas/" + schemaFile),
            schemaNode,
            null);
    var errors = schema.validate(JSON.readTree(body));
    assertTrue(
        errors.isEmpty(),
        () -> label + " does not conform to " + schemaFile + ": " + errors + "\nbody: " + body);
  }

  private static Path repoRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !Files.isDirectory(cursor.resolve("SSOT/schemas"))) {
      cursor = cursor.getParent();
    }
    if (cursor == null) throw new IllegalStateException("could not locate repository root");
    return cursor;
  }
}
