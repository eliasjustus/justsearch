/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.testsupport.cli;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.justsearch.testsupport.docs.SampleDoc;
import io.justsearch.testsupport.docs.SampleDocs;
import io.justsearch.testsupport.fixtures.MiniIndexFixture;
import io.justsearch.testsupport.paging.PagingDeterminismHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI entry point used by the Gradle goldenSmoke task to generate golden artifacts from the
 * deterministic fixtures.
 */
public final class GoldenSmokeRunner {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    Map<String, String> params = parseArgs(args);
    String mode = require(params, "mode");
    String sampleSet = require(params, "sample-set");
    Path simulatePath = Path.of(require(params, "simulate"));
    Path pagingPath = Path.of(require(params, "paging"));
    Path metadataPath = Path.of(require(params, "metadata"));
    int pages = Integer.parseInt(params.getOrDefault("pages", "5"));

    SampleDocs.SampleDocSet docSet = SampleDocs.byName(sampleSet);
    List<SampleDoc> docs = docSet.docs();
    Files.createDirectories(simulatePath.getParent());
    Files.createDirectories(pagingPath.getParent());
    Files.createDirectories(metadataPath.getParent());

    try (MiniIndexFixture fixture = MiniIndexFixture.builder().docs(docSet).build()) {
      writeSimulate(simulatePath, mode, sampleSet, fixture, docs);
      PagingDeterminismHarness harness = PagingDeterminismHarness.from(fixture);
      PagingDeterminismHarness.PagingTrace trace = harness.capture(sampleSet, pages);
      harness.writeJson(trace, pagingPath);
      writeMetadata(metadataPath, mode, sampleSet, fixture);
    }
  }

  private static void writeSimulate(
      Path output,
      String mode,
      String sampleSet,
      MiniIndexFixture fixture,
      List<SampleDoc> docs)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("mode", mode);
    root.put("sample_set", sampleSet);
    root.put("doc_count", docs.size());
    ArrayNode ids = root.putArray("doc_ids");
    docs.stream().map(SampleDoc::id).forEach(ids::add);
    ObjectNode handles = root.putObject("handles");
    handles.put("path", fixture.handles().pathToSampleDocs());
    handles.put("paging_config", fixture.handles().pagingConfig());
    handles.put("with_ann_vectors", fixture.handles().withAnnVectors());
    handles.put("with_indexer_worker", fixture.handles().withIndexerWorker());
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
  }

  @SuppressWarnings("PMD.UnusedFormalParameter") // fixture reserved for future metadata fields
  private static void writeMetadata(
      Path output,
      String mode,
      String sampleSet,
      MiniIndexFixture fixture)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("mode", mode);
    root.put("sample_set", sampleSet);
    MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
  }

  private static Map<String, String> parseArgs(String[] args) {
    Map<String, String> params = new HashMap<>();
    for (String arg : args) {
      if (arg.startsWith("--")) {
        int idx = arg.indexOf('=');
        if (idx > 2) {
          params.put(arg.substring(2, idx), arg.substring(idx + 1));
        }
      }
    }
    return params;
  }

  private static String require(Map<String, String> params, String key) {
    String value = params.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required argument --" + key);
    }
    return value;
  }

  private GoldenSmokeRunner() {}
}
