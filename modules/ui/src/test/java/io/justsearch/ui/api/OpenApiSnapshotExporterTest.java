package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class OpenApiSnapshotExporterTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path temporaryDirectory;

  @Test
  void exportsTheCommittedManifestShapeThroughTheJavaRenderer() throws Exception {
    Path input = temporaryDirectory.resolve("routes.json");
    Files.writeString(
        input,
        """
        {
          "schemaVersion": "1.0",
          "count": 1,
          "routes": [{
            "method": "GET",
            "path": "/api/things/{id}",
            "cohort": "things",
            "owningModule": null,
            "requiredCapabilities": [],
            "responseSchema": null
          }]
        }
        """);

    String first = OpenApiSnapshotExporter.renderSnapshot(input);
    String second = OpenApiSnapshotExporter.renderSnapshot(input);
    assertEquals(first, second);
    assertTrue(first.endsWith("\n"));
    assertTrue(first.contains("\"openapi\": \"3.1.0\""));
    assertTrue(first.contains("\"tags\": [\n"));
    assertTrue(!first.contains("\"openapi\" :"));

    @SuppressWarnings("unchecked")
    var document = MAPPER.readValue(first, java.util.Map.class);
    @SuppressWarnings("unchecked")
    var surface = (java.util.Map<String, Object>) document.get("x-justsearch-surface");
    assertEquals(OpenApiRenderer.CLASSIFICATION, surface.get("classification"));
    @SuppressWarnings("unchecked")
    var source = (java.util.Map<String, Object>) document.get("x-justsearch-route-source");
    assertEquals(1, source.get("routeCount"));
  }

  @Test
  void exportsVersionTwoLifecycleMetadata() throws Exception {
    Path input = temporaryDirectory.resolve("routes-v2.json");
    Files.writeString(
        input,
        """
        {
          "schemaVersion": "2.0",
          "count": 1,
          "routes": [{
            "method": "GET",
            "path": "/api/things/{id}",
            "cohort": "things",
            "owningModule": null,
            "requiredCapabilities": [],
            "responseSchema": "thing.v1.json",
            "stability": "public-contract",
            "sdkOperationId": "getThing",
            "requestSchema": null,
            "queryParameters": [],
            "responseSchemas": {"200": "thing.v1.json"},
            "lifecycle": {
              "deprecatedSince": "2026-01-01T00:00:00Z",
              "sunsetAt": "2026-05-01T00:00:00Z",
              "replacement": "GET /api/replacement/{id}",
              "documentationUri": "https://docs.justsearch.example/deprecations/things"
            }
          }]
        }
        """);

    String rendered = OpenApiSnapshotExporter.renderSnapshot(input);
    assertTrue(rendered.contains("\"operationId\": \"getThing\""));
    assertTrue(rendered.contains("\"deprecated\": true"));
    assertTrue(rendered.contains("\"x-sunset\": \"2026-05-01T00:00:00Z\""));
  }

  @Test
  void rejectsAStaleDeclaredCount() throws Exception {
    Path input = temporaryDirectory.resolve("routes.json");
    Files.writeString(input, "{\"count\":2,\"routes\":[]}");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenApiSnapshotExporter.renderSnapshot(input));
    assertTrue(error.getMessage().contains("count does not match"));
  }

  @Test
  void rejectsAHeadDigestThatNoLongerMatchesItsDescriptors() throws Exception {
    Path input = temporaryDirectory.resolve("routes.json");
    Files.writeString(
        input,
        """
        {
          "count": 1,
          "routeDigest": "sha256:stale",
          "routes": [{
            "method": "GET",
            "path": "/api/things",
            "cohort": "things",
            "owningModule": null,
            "requiredCapabilities": [],
            "responseSchema": null
          }]
        }
        """);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenApiSnapshotExporter.renderSnapshot(input));
    assertTrue(error.getMessage().contains("digest does not match its descriptors"));
  }
}
