/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ssot.tools;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validates SSOT artifacts against JSON Schemas, verifies analyzer fingerprints, checks
 * reason-code uniqueness, and writes the reproducibility manifest.
 *
 * <p>Replaces {@code SSOT/tools/validate.mjs}.
 */
public final class SsotValidator {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private static final HexFormat HEX = HexFormat.of();

  public static void main(String[] args) throws IOException {
    Path ssotRoot = Path.of(args.length > 0 ? args[0] : "SSOT");
    new SsotValidator(ssotRoot).run();
  }

  private final Path root;
  private final List<ArtifactEntry> results = new ArrayList<>();

  private SsotValidator(Path root) {
    this.root = root;
  }

  private void run() throws IOException {
    validateAnalyzersCatalog();
    validateFieldCatalog();
    validatePrompts();
    validateVersionsCatalog();
    validateGoldenIntents();
    writeReproManifest();
    System.out.println("SSOT validation passed (" + results.size() + " artifacts)");
  }

  private void validateAnalyzersCatalog() throws IOException {
    Path schemaPath = root.resolve("schemas/indexing/analyzers-catalog.schema.json");
    Path catalogPath = root.resolve("catalogs/analyzers.v1.json");
    if (!Files.exists(schemaPath) || !Files.exists(catalogPath)) return;

    validateAgainstSchema(catalogPath, schemaPath);

    // Fingerprint verification: canonicalize descriptor via Jackson ORDER_MAP_ENTRIES_BY_KEYS
    // + SHA-256. This is NOT JCS (RFC 8785) — it's Jackson sorted-key JSON. For descriptors
    // containing only strings and string arrays, the output is identical. If descriptors ever
    // include numbers or special Unicode, this must be revisited. See ADR 0013.
    JsonNode catalog = MAPPER.readTree(catalogPath.toFile());
    assertNoDuplicateIds(catalogPath, catalog.path("analyzers"), "analyzers");
    String zeroFp = "0".repeat(64);
    for (JsonNode analyzer : iterable(catalog.path("analyzers"))) {
      String id = analyzer.path("id").asText();
      String storedFp = analyzer.path("fingerprint").asText("");
      if (storedFp.isEmpty() || storedFp.equals(zeroFp)) continue;

      ObjectNode descriptor = MAPPER.createObjectNode();
      descriptor.put("id", id);
      descriptor.put("locale", analyzer.path("locale").asText());
      descriptor.put("provider", analyzer.path("provider").asText());
      ArrayNode componentsArray = descriptor.putArray("components");
      for (JsonNode c : iterable(analyzer.path("components"))) {
        componentsArray.add(c.asText());
      }
      String canonical = canonicalJson(descriptor);
      String expected = sha256(canonical.getBytes(StandardCharsets.UTF_8));
      if (!expected.equals(storedFp)) {
        fail("Analyzer fingerprint mismatch for '%s': expected %s but found %s", id, expected, storedFp);
      }
    }
    results.add(hashArtifact(catalogPath));
  }

  private void validateFieldCatalog() throws IOException {
    Path schemaPath = root.resolve("schemas/indexing/field-catalog.schema.json");
    Path catalogPath = root.resolve("catalogs/fields.v1.json");
    validateAgainstSchema(catalogPath, schemaPath);
    JsonNode catalog = MAPPER.readTree(catalogPath.toFile());
    assertNoDuplicateIds(catalogPath, catalog.path("fields"), "fields");
    results.add(hashArtifact(catalogPath));
  }

  /**
   * Tempdoc 393 § C.2: detect duplicate {@code id} entries within a single catalog array.
   * The JSON schema does not enforce uniqueness across array elements — a duplicate would
   * parse cleanly and silently override earlier entries at runtime. Callers fail the build
   * when the returned set is non-empty.
   *
   * <p>Package-private so {@code SsotValidatorDuplicateIdTest} can exercise the pure detection
   * function without triggering {@code fail()}'s {@code System.exit}.
   */
  static Set<String> findDuplicateIds(JsonNode array) {
    if (array == null || !array.isArray()) return Set.of();
    Set<String> seen = new HashSet<>();
    Set<String> duplicates = new TreeSet<>();
    for (JsonNode entry : iterable(array)) {
      String id = entry.path("id").asText();
      if (id.isEmpty()) continue;
      if (!seen.add(id)) {
        duplicates.add(id);
      }
    }
    return duplicates;
  }

  private static void assertNoDuplicateIds(Path catalogPath, JsonNode array, String arrayName) {
    Set<String> duplicates = findDuplicateIds(array);
    if (!duplicates.isEmpty()) {
      fail("Duplicate %s ids in %s: %s", arrayName, catalogPath.getFileName(), duplicates);
    }
  }

  private void validatePrompts() throws IOException {
    String[] promptFiles = {"prompts/en/intent.v1.json", "prompts/en/summary.v1.json"};
    for (String rel : promptFiles) {
      Path p = root.resolve(rel);
      if (!Files.exists(p)) continue;
      JsonNode obj = MAPPER.readTree(p.toFile());
      String mode = obj.path("mode").asText("");
      if (!mode.equals("JSON") && !mode.equals("TEXT")) {
        fail("prompt.mode must be JSON or TEXT in %s", rel);
      }
      JsonNode defaults = obj.get("defaults");
      if (defaults == null || !defaults.isObject()) {
        fail("prompt.defaults missing in %s", rel);
      }
      results.add(hashArtifact(p));
    }
  }

  private void validateVersionsCatalog() throws IOException {
    Path p = root.resolve("versions/catalog.json");
    if (!Files.exists(p)) return;
    JsonNode obj = MAPPER.readTree(p.toFile());
    if (obj == null || !obj.isObject()) {
      fail("versions/catalog.json must be a non-null object");
    }
    results.add(hashArtifact(p));
  }

  private void validateGoldenIntents() throws IOException {
    Path goldenPath = root.resolve("tests/golden/intent.parse.v1.jsonl");
    Path schemaPath = root.resolve("schemas/domain/search-intent.schema.json");
    if (!Files.exists(goldenPath) || !Files.exists(schemaPath)) return;

    List<String> lines = Files.readAllLines(goldenPath, StandardCharsets.UTF_8);
    int recordCount = 0;
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      try {
        JsonNode record = MAPPER.readTree(line);
        validateJsonAgainstSchema(record, schemaPath);
        recordCount++;
      } catch (Exception e) {
        fail("Golden intent validation failed at line %d: %s", i + 1, e.getMessage());
      }
    }
    System.out.printf("Golden intents validated: %d records%n", recordCount);
  }

  private void writeReproManifest() throws IOException {
    ObjectNode reproNode = MAPPER.createObjectNode();
    ArrayNode artifacts = reproNode.putArray("artifacts");
    for (ArtifactEntry entry : results) {
      ObjectNode node = artifacts.addObject();
      node.put("path", entry.path());
      node.put("sha256", entry.sha256());
    }
    String canonical = canonicalJson(reproNode);

    Path reproSchema = root.resolve("schemas/config/repro-manifest.schema.json");
    if (Files.exists(reproSchema)) {
      validateJsonAgainstSchema(MAPPER.readTree(canonical), reproSchema);
    }

    Path reproDir = root.resolve("manifests/repro");
    Files.createDirectories(reproDir);
    Files.writeString(reproDir.resolve("repro.v1.json"), canonical, StandardCharsets.UTF_8);
  }

  private void validateAgainstSchema(Path dataPath, Path schemaPath) throws IOException {
    JsonNode data = MAPPER.readTree(dataPath.toFile());
    validateJsonAgainstSchema(data, schemaPath);
  }

  private void validateJsonAgainstSchema(JsonNode data, Path schemaPath) throws IOException {
    JsonNode schemaNode = MAPPER.readTree(schemaPath.toFile());
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    var ctx = new SchemaContext(
        registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
    Schema schema = ctx.newSchema(
        SchemaLocation.of(schemaPath.toUri().toString()), schemaNode, null);
    var violations = schema.validate(data);
    if (!violations.isEmpty()) {
      StringBuilder sb = new StringBuilder("Schema validation failed for " + schemaPath.getFileName() + ":");
      for (var error : violations) {
        sb.append("\n  - ").append(error.getMessage());
      }
      fail(sb.toString());
    }
  }

  private ArtifactEntry hashArtifact(Path file) throws IOException {
    String relativePath = root.relativize(file).toString().replace('\\', '/');
    String canonical = canonicalJson(MAPPER.readTree(file.toFile()));
    String hash = sha256(canonical.getBytes(StandardCharsets.UTF_8));
    return new ArtifactEntry(relativePath, hash);
  }

  private static String canonicalJson(JsonNode node) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    MAPPER.writeValue(bos, node);
    return bos.toString(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] data) {
    try {
      return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<JsonNode> iterable(JsonNode node) {
    if (node == null || node.isMissingNode() || !node.isArray()) {
      return List.of();
    }
    List<JsonNode> list = new ArrayList<>();
    for (int i = 0; i < node.size(); i++) {
      list.add(node.get(i));
    }
    return list;
  }

  private static void fail(String format, Object... args) {
    String message = args.length > 0 ? format.formatted(args) : format;
    System.err.println("SSOT VALIDATION FAILED: " + message);
    System.exit(1);
  }

  private record ArtifactEntry(String path, String sha256) {}
}
