package io.justsearch.testsupport.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GoldenSmokeRunnerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void mainGeneratesGoldenArtifacts() throws Exception {
    Path simulate = tempDir.resolve("simulate").resolve("simulate.json");
    Path paging = tempDir.resolve("paging").resolve("paging.json");
    Path metadata = tempDir.resolve("metadata").resolve("metadata.json");

    String[] args = {
      "--mode=smoke",
      "--sample-set=catalog-smoke",
      "--simulate=" + simulate,
      "--paging=" + paging,
      "--metadata=" + metadata,
      "--pages=1"
    };

    GoldenSmokeRunner.main(args);

    assertTrue(Files.exists(simulate));
    assertTrue(Files.exists(paging));
    assertTrue(Files.exists(metadata));

    JsonNode simulateJson = MAPPER.readTree(simulate.toFile());
    assertEquals("smoke", simulateJson.get("mode").asText());
    assertEquals("catalog-smoke", simulateJson.get("sample_set").asText());

    JsonNode metadataJson = MAPPER.readTree(metadata.toFile());
    assertEquals("smoke", metadataJson.get("mode").asText());
    assertEquals("catalog-smoke", metadataJson.get("sample_set").asText());
  }

  @Test
  void missingRequiredArgumentFailsFast() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> GoldenSmokeRunner.main(new String[] {"--mode=smoke"}));
    assertTrue(ex.getMessage().contains("--sample-set"));
  }
}
