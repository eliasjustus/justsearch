/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Validates commit metadata against SSOT commit-metadata.schema.json. */
public final class JsonSchemaCommitMetadataValidator implements CommitMetadataValidator {
  private static final ObjectMapper M = new ObjectMapper();
  private final Schema schema;

  public JsonSchemaCommitMetadataValidator() {
    File f = new File(repoRoot(), "SSOT/schemas/indexing/commit-metadata.schema.json");
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    JsonNode schemaNode = M.readTree(f);
    var ctx = new SchemaContext(
        registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    this.schema = ctx.newSchema(
        SchemaLocation.of(f.toURI().toString()), schemaNode, null);
  }

  @Override
  public void validate(Map<String, Object> metadata) {
    List<com.networknt.schema.Error> violations = schema.validate(M.valueToTree(metadata));
    if (!violations.isEmpty()) {
      throw new IllegalStateException("Commit metadata schema violations: " + join(violations));
    }
  }

  private static File repoRoot() {
    Path root = JustSearchConfigurationLoader.repoRootStatic();
    if (root == null) {
      throw new IllegalStateException("Repo root not found (no SSOT directory)");
    }
    return root.toFile();
  }

  private static String join(List<com.networknt.schema.Error> messages) {
    return messages.stream().map(com.networknt.schema.Error::getMessage).sorted()
        .reduce((a, b) -> a + "; " + b).orElse("");
  }
}
