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
