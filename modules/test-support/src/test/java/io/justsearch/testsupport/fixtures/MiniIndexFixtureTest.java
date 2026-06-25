package io.justsearch.testsupport.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.testsupport.docs.SampleDocs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MiniIndexFixtureTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void buildsSnapshotAndHandlesMetadata() throws Exception {
    try (MiniIndexFixture fixture =
        MiniIndexFixture.builder().docs(SampleDocs.catalogSmoke()).build()) {
      assertTrue(Files.exists(fixture.snapshotDir()));
      assertTrue(Files.exists(fixture.snapshotDir().resolve("docs.json")));
      assertEquals(
          "test-support/sample-docs/catalog-smoke.json", fixture.handles().pathToSampleDocs());
    }
  }

  @Test
  void builderOverridesOptionalFields() throws Exception {
    try (MiniIndexFixture fixture =
        MiniIndexFixture.builder()
            .docs(SampleDocs.catalogSmoke())
            .seed(1234L)
            .withAnnVectors(false)
            .withIndexerWorker(true)
            .build()) {
      assertFalse(fixture.handles().withAnnVectors());
      assertTrue(fixture.handles().withIndexerWorker());
      Path metadataPath = fixture.snapshotDir().resolve("metadata.json");
      JsonNode metadata = MAPPER.readTree(metadataPath.toFile());
      assertEquals(1234L, metadata.get("seed").asLong());
    }
  }
}
