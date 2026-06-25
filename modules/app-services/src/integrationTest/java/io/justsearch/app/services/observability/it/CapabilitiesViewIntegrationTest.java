package io.justsearch.app.observability.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.observability.CapabilitiesController;
import io.justsearch.app.observability.CapabilitiesService;
import io.justsearch.app.util.RepoPaths;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/** Integration coverage for the capabilities HTTP endpoint and evidence capture. */
final class CapabilitiesViewIntegrationTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void exposesCapabilitiesAndWritesEvidence() throws Exception {
    CapabilitiesService service = new CapabilitiesService();
    CapabilitiesService.CapabilitiesView expectedView = service.capabilities();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/infra/capabilities", new CapabilitiesController(service));
    server.start();
    try {
      URI uri =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/infra/capabilities");
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

      HttpResponse<String> response =
          client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

      assertEquals(HttpURLConnection.HTTP_OK, response.statusCode(), "HTTP request should succeed");
      JsonNode root = JSON.readTree(response.body());
      assertSchemaVersions(root, expectedView.schemaVersions());
      assertPromptTemplates(root, expectedView.promptTemplates().size());
      assertPluginCoverage(root);
      assertSourceMetadata(root);
      assertServerCapabilities(root);

      writeEvidence(root);
    } finally {
      server.stop(0);
    }
  }

  private static void assertSchemaVersions(
      JsonNode root, CapabilitiesService.SchemaVersions expectedVersions) {
    JsonNode versions = root.path("schema_versions");
    assertEquals(expectedVersions.schemaVer(), versions.path("schema_ver").asText());
    assertEquals(expectedVersions.grammarVer(), versions.path("grammar_ver").asText());
    assertTrue(versions.hasNonNull("template_ver"));
  }

  private static void assertPromptTemplates(JsonNode root, int expectedCount) {
    JsonNode templates = root.path("prompt_templates");
    assertTrue(templates.isArray(), "prompt_templates must be array");
    assertTrue(templates.size() >= expectedCount, "prompt_templates should not be empty");
    stream(templates)
        .forEach(
            node -> {
              assertTrue(node.hasNonNull("task_id"));
              assertTrue(node.hasNonNull("template_ver"));
              String hash = node.path("hash").asText();
              assertEquals(64, hash.length(), "template hash should be 64 hex chars");
            });
  }

  private static void assertPluginCoverage(JsonNode root) {
    JsonNode plugins = root.path("plugins");
    assertTrue(plugins.isArray(), "plugins must be an array");
  }

  private static void assertSourceMetadata(JsonNode root) {
    JsonNode source = root.path("source");
    assertTrue(source.isObject(), "source metadata must exist");
    assertEquals("phase-7", source.path("phase").asText());
    assertTrue(source.hasNonNull("schema_ver"));
    assertTrue(source.hasNonNull("generated_at"));
  }

  /**
   * Per tempdoc 429 §E.15 + §F.6 closure: serverCapabilities declares the registry
   * primitive types with messageCatalogUrl per primitive (per 434 §8) plus
   * catalogVersion (per §C.E) and protocolVersion.
   */
  private static void assertServerCapabilities(JsonNode root) {
    JsonNode sc = root.path("serverCapabilities");
    assertTrue(sc.isObject(), "serverCapabilities must be an object");
    JsonNode primitives = sc.path("primitives");
    assertTrue(primitives.isObject(), "serverCapabilities.primitives must be an object");
    // Slice 448 phase 4: DiagnosticChannel is the fourth primitive (CONFLICT-LEDGER C-012).
    for (String name : java.util.List.of("Operation", "Resource", "Prompt", "DiagnosticChannel")) {
      JsonNode prim = primitives.path(name);
      assertTrue(prim.isObject(), "primitives." + name + " must exist");
      assertEquals("v1", prim.path("current").asText(), name + ".current");
      assertTrue(
          prim.path("endpoint").asText().startsWith("/api/registry/"),
          name + ".endpoint must point to /api/registry/*");
      assertTrue(
          prim.path("messageCatalogUrl").asText().contains("{locale}"),
          name + ".messageCatalogUrl must template {locale} placeholder");
      assertTrue(
          prim.path("dynamicRegistration").asBoolean(),
          name + ".dynamicRegistration must be true");
    }

    // Slice 449 phase 3: Manifest tier slot. V1 ships one entry: `Surface`.
    // Same descriptor shape as PrimitiveDescriptor; same field assertions.
    JsonNode manifests = sc.path("manifests");
    assertTrue(manifests.isObject(), "serverCapabilities.manifests must be an object");
    JsonNode surfaceManifest = manifests.path("Surface");
    assertTrue(surfaceManifest.isObject(), "manifests.Surface must exist");
    assertEquals("v1", surfaceManifest.path("current").asText());
    assertEquals("/api/registry/surfaces", surfaceManifest.path("endpoint").asText());
    assertTrue(
        surfaceManifest.path("messageCatalogUrl").asText().contains("{locale}"),
        "Surface.messageCatalogUrl must template {locale} placeholder");
    assertTrue(surfaceManifest.path("dynamicRegistration").asBoolean());
    assertTrue(
        sc.path("catalogVersion").isIntegralNumber(),
        "catalogVersion must be a long");
    assertEquals("1.0", sc.path("protocolVersion").asText());

    // Slice 443: i18n capability slot. Version pinned at 1; availableLocales lists the
    // shipping locales. Today only English; future locales add to the list.
    JsonNode i18n = sc.path("i18n");
    assertTrue(i18n.isObject(), "serverCapabilities.i18n must be an object");
    assertEquals(1, i18n.path("version").asInt(), "i18n.version must be 1");
    JsonNode locales = i18n.path("availableLocales");
    assertTrue(locales.isArray(), "i18n.availableLocales must be an array");
    assertTrue(
        stream(locales).anyMatch(n -> "en".equals(n.asText())),
        "i18n.availableLocales must include \"en\"");

    // Slice 436 §B.8: streamingEnvelope capability slot. FE consumers feature-detect the
    // universal envelope shape; absence implies bespoke per-endpoint shape.
    JsonNode envelope = sc.path("streamingEnvelope");
    assertTrue(envelope.isObject(), "serverCapabilities.streamingEnvelope must be an object");
    assertEquals(
        1,
        envelope.path("version").asInt(),
        "streamingEnvelope.version must be 1 (slice 436 envelope shape)");
  }

  private static void writeEvidence(JsonNode root) throws IOException {
    Path evidenceDir = RepoPaths.findRepoRoot().resolve("reports/phase7/capabilities");
    Files.createDirectories(evidenceDir);
    Path ndjson = evidenceDir.resolve("capabilities.ndjson");
    String jsonLine = JSON.writeValueAsString(root);
    Files.writeString(
        ndjson,
        jsonLine + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static java.util.stream.Stream<JsonNode> stream(JsonNode arrayNode) {
    return StreamSupport.stream(arrayNode.spliterator(), false);
  }
}
