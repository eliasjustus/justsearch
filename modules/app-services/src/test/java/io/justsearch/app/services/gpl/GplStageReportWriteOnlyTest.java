/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 909 items 4 and 5 — the two GPL stage-3 reports are classified in
 * {@code governance/store-recoverability.v1.json} as WRITE-ONLY diagnostics whose only recovery is
 * "the next analysis run rewrites them". The parked entry's blocker was precise about why that was
 * not yet good enough: <em>"Nothing reads it back, so the cost of a torn write is a rerun — but
 * that is a claim needing a test, not an assumption."</em>
 *
 * <p>So this pins both halves of the claim:
 *
 * <ol>
 *   <li><b>Nothing reads it back.</b> A source scan of every production source tree: the report file
 *       names appear only in the classes that write them, and the only production reference to
 *       either class is {@code GplJobCoordinator}'s {@code write(...)} call. A future reader — a
 *       status endpoint surfacing the last sweep, say — makes the register row's recoverability
 *       claim false, and fails here rather than shipping.
 *   <li><b>A torn report costs exactly a rerun.</b> Garbage at the report path is fully replaced by
 *       the next {@code write(...)}, with no merge of the old bytes and no read of them first.
 * </ol>
 */
final class GplStageReportWriteOnlyTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static final String STAGE_3A_FILE = "gpl-stage3a-analysis.json";
  private static final String STAGE_3B_FILE = "gpl-stage3b-branch-fusion.json";

  /** The classes that write the reports — the only places their file names may appear. */
  private static final Set<String> WRITERS =
      Set.of("GplStage3aAnalysisReport.java", "GplStage3bBranchFusionReport.java");

  /** The one production caller the register row names, plus the writers themselves. */
  private static final Set<String> ALLOWED_REFERENCES =
      Set.of(
          "GplStage3aAnalysisReport.java",
          "GplStage3bBranchFusionReport.java",
          "GplJobCoordinator.java");

  @TempDir Path tempDir;

  @Test
  @DisplayName("no production code reads either stage-3 report back")
  void noProductionCodeReadsTheStageReports() throws IOException {
    var fileNameHits = new LinkedHashSet<String>();
    var referenceHits = new LinkedHashSet<String>();
    for (Path source : productionJavaSources()) {
      String text = Files.readString(source, StandardCharsets.UTF_8);
      String name = source.getFileName().toString();
      if (text.contains(STAGE_3A_FILE) || text.contains(STAGE_3B_FILE)) {
        fileNameHits.add(name);
      }
      if (text.contains("GplStage3aAnalysisReport") || text.contains("GplStage3bBranchFusionReport")) {
        referenceHits.add(name);
      }
    }

    assertEquals(
        WRITERS,
        fileNameHits,
        "the report file names must appear only in the classes that write them — a second file "
            + "naming one is either a reader (which breaks the write-only classification) or a "
            + "duplicated path authority");
    assertTrue(
        ALLOWED_REFERENCES.containsAll(referenceHits),
        "an unexpected production reference to a stage-3 report class: " + referenceHits);

    // …and the one allowed caller must only WRITE. `analyze` or `reportPathFor` there would mean
    // the coordinator consumes the report, which is what the register says nothing does.
    String coordinator =
        Files.readString(
            repoRoot()
                .resolve(
                    "modules/app-services/src/main/java/io/justsearch/app/services/gpl/GplJobCoordinator.java"),
            StandardCharsets.UTF_8);
    for (String forbidden :
        List.of(
            "GplStage3aAnalysisReport.analyze",
            "GplStage3bBranchFusionReport.analyze",
            "GplStage3aAnalysisReport.reportPathFor",
            "GplStage3bBranchFusionReport.reportPathFor")) {
      assertTrue(
          !coordinator.contains(forbidden),
          "GplJobCoordinator must only write the stage-3 reports, never resolve or re-analyze "
              + "them: found " + forbidden);
    }
  }

  @Test
  @DisplayName("a torn stage-3A report is fully replaced by the next analysis run")
  void tornStage3aReportIsReplacedByTheNextRun() throws IOException {
    Path triples = writeTripleStore();
    Path report = GplStage3aAnalysisReport.reportPathFor(triples);
    Files.writeString(report, "{\"analyzedTriples\": 99999, TORN", StandardCharsets.UTF_8);

    Path written = GplStage3aAnalysisReport.write(triples);

    assertEquals(report, written);
    var node = MAPPER.readTree(Files.readString(report, StandardCharsets.UTF_8));
    assertEquals(
        GplStage3aAnalysisReport.analyze(triples).analyzedTriples(),
        node.get("analyzedTriples").asLong(),
        "the rerun must rewrite the report from the triple store, not merge the torn bytes");
  }

  @Test
  @DisplayName("a torn stage-3B report is fully replaced by the next analysis run")
  void tornStage3bReportIsReplacedByTheNextRun() throws IOException {
    Path triples = writeTripleStore();
    Path report = GplStage3bBranchFusionReport.reportPathFor(triples);
    Files.writeString(report, "{\"analyzedTriples\": 99999, TORN", StandardCharsets.UTF_8);

    Path written = GplStage3bBranchFusionReport.write(triples);

    assertEquals(report, written);
    var node = MAPPER.readTree(Files.readString(report, StandardCharsets.UTF_8));
    assertEquals(
        GplStage3bBranchFusionReport.analyze(triples).analyzedTriples(),
        node.get("analyzedTriples").asLong(),
        "the rerun must rewrite the report from the triple store, not merge the torn bytes");
  }

  /** A minimal triple store, enough for both analyses to produce a report. */
  private Path writeTripleStore() throws IOException {
    Path triples = tempDir.resolve("gpl-training-triples.ndjson");
    Files.writeString(
        triples,
        "{\"query_id\":\"q1\",\"synthetic_query\":\"q\",\"doc_id\":\"d1\","
            + "\"parent_token_count\":100,\"features\":{\"bm25\":1.0}}\n",
        StandardCharsets.UTF_8);
    return triples;
  }

  private static List<Path> productionJavaSources() throws IOException {
    Path modules = repoRoot().resolve("modules");
    try (Stream<Path> tree = Files.walk(modules)) {
      return tree.filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .toList();
    }
  }

  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve("governance/consult-register.v1.json"))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException("repo root not found from " + Paths.get("").toAbsolutePath());
  }
}
