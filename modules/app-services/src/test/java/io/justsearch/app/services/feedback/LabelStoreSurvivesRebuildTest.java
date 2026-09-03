/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Tempdoc 915 B6b — the Head half of feedback identity survival across a full rebuild. */
class LabelStoreSurvivesRebuildTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void authoredFeedbackReprojectsToSameUidAfterDerivedStoreIsRebuilt(@TempDir Path dataDir)
      throws Exception {
    String uidPreservedByWorkerRebuild = "2c88bc44-7d55-4e72-a348-39e0de07c551";
    String uidShownAcrossRebuild = "8f97be31-42a5-409a-874c-68a9707340b1";
    var responseBeforeRebuild =
        KnowledgeSearchResponseBuilder.builder()
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/before.md")
                        .fields(Map.of("doc_uid", uidPreservedByWorkerRebuild))
                        .build(),
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/shown.md")
                        .fields(Map.of("doc_uid", uidShownAcrossRebuild))
                        .build()))
            .build();
    FeatureSnapshot snapshot =
        FeatureSnapshots.capture("iid-rebuild", "q", 1L, responseBeforeRebuild);
    assertEquals(uidPreservedByWorkerRebuild, snapshot.hits().getFirst().docId());

    Path feedback = dataDir.resolve("feedback");
    new NdjsonAppendStore<>(feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class)
        .append(snapshot);
    new NdjsonAppendStore<>(feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class)
        .append(
            new ResultDisposition(
                "iid-rebuild",
                uidPreservedByWorkerRebuild,
                ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION,
                2L));

    LabelProjection.Result first = FeedbackLabels.rebuild(dataDir);
    List<String> firstKeys = projectedDocIds(dataDir);
    Files.delete(FeedbackLabels.realLabelPath(dataDir));

    // PR-A's DocumentIdentityBootImportTest proves Blue→Green preserves this UID. PR-B composes
    // with that contract here: the authored stores outlive the index generation, and rebuilding
    // the derived label file produces the identical UID key without consulting Lucene from Head.
    LabelProjection.Result afterFullRebuild = FeedbackLabels.rebuild(dataDir);

    assertEquals(first, afterFullRebuild);
    assertEquals(List.of(uidPreservedByWorkerRebuild, uidShownAcrossRebuild), firstKeys);
    assertEquals(firstKeys, projectedDocIds(dataDir));
    assertFalse(
        Files.readString(FeedbackLabels.realLabelPath(dataDir), StandardCharsets.UTF_8)
            .contains("C:/docs/before.md"));
  }

  private static List<String> projectedDocIds(Path dataDir) throws Exception {
    return Files.readAllLines(FeedbackLabels.realLabelPath(dataDir), StandardCharsets.UTF_8).stream()
        .filter(line -> !line.isBlank())
        .map(
            line -> {
              try {
                return MAPPER.readTree(line).get("doc_id").asText();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .sorted()
        .toList();
  }
}
