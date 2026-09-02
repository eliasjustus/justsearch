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
 *   <li><b>Nothing reads it back.</b> A source scan of every production source tree
 *       ({@code modules/&#42;&#42;/src/main/java}): the report file names appear only in the classes
 *       that write them; the only production reference to either class is
 *       {@code GplJobCoordinator}'s {@code write(...)} call; and no production file READS a path it
 *       bound from one of those classes. That last clause is the one that makes the check match its
 *       claim — the coordinator does bind the returned {@code Path}, so a later
 *       {@code Files.readString(reportPath)} added beside it would otherwise have passed a scan that
 *       only looked at method names. A future reader — a status endpoint surfacing the last sweep,
 *       say — makes the register row's recoverability claim false, and fails here rather than
 *       shipping.
 *       <p>Stated limit: the walk covers {@code modules/&#42;&#42;/src/main/java}, so a reader added
 *       outside the Java production trees (a script, the Rust shell) is not caught here.
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

    // The method-name list above cannot see the case that actually matters: `write(...)` returns
    // the Path and callers BIND it (`Path reportPath = ...write(...)`), so a read added beside that
    // binding reads the report while every name above stays absent. Bind-then-read is what is
    // forbidden, so that is what is checked.
    for (Path source : productionJavaSources()) {
      String text = Files.readString(source, StandardCharsets.UTF_8);
      for (String bound : boundReportPathVariables(text)) {
        for (String reader : READ_CALLS) {
          assertTrue(
              !text.contains(reader + "(" + bound),
              source.getFileName()
                  + " binds a stage-3 report path to `"
                  + bound
                  + "` and then reads it ("
                  + reader
                  + "). The register classifies these reports as write-only — a reader makes that "
                  + "false, and changes the row's recoverability and atomicity with it.");
        }
      }
    }
  }

  /** The read calls a consumer of a report path would plausibly use. */
  private static final List<String> READ_CALLS =
      List.of(
          "Files.readString",
          "Files.readAllBytes",
          "Files.readAllLines",
          "Files.newBufferedReader",
          "Files.newInputStream",
          "Files.lines",
          "MAPPER.readValue",
          "MAPPER.readTree");

  /** Variable names a source binds from {@code …Report.write(...)} / {@code …reportPathFor(...)}. */
  private static Set<String> boundReportPathVariables(String source) {
    var names = new LinkedHashSet<String>();
    var pattern =
        java.util.regex.Pattern.compile(
            "(\\w+)\\s*=\\s*GplStage3[ab]\\w*\\.(?:write|reportPathFor)\\s*\\(");
    var matcher = pattern.matcher(source);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
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
