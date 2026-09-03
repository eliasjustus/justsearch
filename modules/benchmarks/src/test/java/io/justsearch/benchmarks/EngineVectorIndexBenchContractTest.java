/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class EngineVectorIndexBenchContractTest {
  private static final List<String> OVERRIDE_KEYS =
      List.of(
          "index.vector.hnsw.m",
          "index.vector.hnsw.ef_construction",
          "index.vector.ef_search",
          "index.vector.quantization.enabled");

  @TempDir Path tempDir;

  @Test
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  void resultReportsTheConfigurationResolvedByTheOpenedRuntime() throws Exception {
    List<String> previous = new ArrayList<>(OVERRIDE_KEYS.size());
    for (String key : OVERRIDE_KEYS) {
      previous.add(System.getProperty(key));
    }

    try {
      Path vectors = tempDir.resolve("vectors.ndjson");
      Path output = tempDir.resolve("output");
      Files.writeString(vectors, sentinelRow());

      EngineVectorIndexBench.main(
          new String[] {
            "--vectors=" + vectors,
            "--out-dir=" + output,
            "--query-count=0",
            "--batch-size=1",
            "--hnsw-m=17",
            "--ef-construction=41",
            "--ef-search=23",
            "--quantization-enabled=false"
          });

      JsonNode result = new ObjectMapper().readTree(output.resolve("result.json").toFile());
      JsonNode knobs = result.path("knobs");
      assertTrue(result.path("sentinel_validated").asBoolean());
      assertEquals(17, knobs.path("ann_hnsw_m").asInt());
      assertEquals(41, knobs.path("ann_ef_construction").asInt());
      assertEquals(23, knobs.path("ann_ef_search_or_null").asInt());
      assertFalse(knobs.path("ann_quantization_enabled").asBoolean());
    } finally {
      for (int i = 0; i < OVERRIDE_KEYS.size(); i++) {
        restoreProperty(OVERRIDE_KEYS.get(i), previous.get(i));
      }
    }
  }

  private static String sentinelRow() {
    StringBuilder row =
        new StringBuilder("{\"doc_id\":\"__bench_sentinel__\",\"vector\":[3.0,4.0");
    for (int i = 2; i < 768; i++) {
      row.append(",0.0");
    }
    return row.append("]}\n").toString();
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
