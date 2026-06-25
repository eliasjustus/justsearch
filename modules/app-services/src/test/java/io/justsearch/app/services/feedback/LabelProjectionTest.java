package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.gpl.GplTrainingTripleStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 580 §17.5 P5 — guard tests for the disposition⋈snapshot label projection. */
class LabelProjectionTest {

  @Test
  void project_joinsByInteractionId_dropsUnjoinable(@TempDir Path dir) throws IOException {
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-1",
                "q",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 1024L),
                    new FeatureSnapshot.HitFeatures("d2", 2, 0.5f, 0.4f, 0.3f, 0.45f, null))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-1", "d1", ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION, 2L),
            new ResultDisposition(
                "iid-1", "d2", ResultDisposition.Kind.REFINED_WITHOUT_OPENING,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 3L),
            // no snapshot for this interactionId → must be dropped (not a featured label)
            new ResultDisposition(
                "iid-MISSING", "dX", ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 4L),
            // joinable interactionId but unknown doc → dropped
            new ResultDisposition(
                "iid-1", "dUNKNOWN", ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 5L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    // Both snapshot hits (d1, d2) are explicitly disposed, so the derived-SHOWN pass adds nothing.
    assertEquals(2, result.triples(), "only d1 + d2 join a snapshot hit");
    long lines =
        Files.readAllLines(dir.resolve("feedback/real-feedback-triples.ndjson"), StandardCharsets.UTF_8)
            .stream()
            .filter(l -> !l.isBlank())
            .count();
    assertEquals(2, lines);
    // iid-1 has a positive (CITED d1) AND a negative (REFINED d2) → one contrast group.
    assertEquals(1, result.contrastGroups());
  }

  @Test
  void project_derivesShownNegatives_givingContrastToALoneOpen(@TempDir Path dir) throws IOException {
    // The Fix-A core: one OPENED on a 3-hit snapshot must yield a CONTRASTFUL group — the opened
    // doc (positive) plus the two shown-but-not-opened hits as derived SHOWN negatives.
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-A",
                "q",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 100L),
                    new FeatureSnapshot.HitFeatures("d2", 2, 0.5f, 0.4f, 0.3f, 0.45f, 100L),
                    new FeatureSnapshot.HitFeatures("d3", 3, 0.2f, 0.1f, 0.1f, 0.15f, 100L))));
    // The user opened d2 (rank 2) — d1 and d3 were shown and passed over.
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-A", "d2", ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 2L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    // 1 explicit positive (d2) + 2 derived SHOWN negatives (d1, d3) = 3 triples.
    assertEquals(3, result.triples());
    assertEquals(1, result.contrastGroups(), "the lone open now has shown-negatives for contrast");

    List<String> rows =
        Files.readAllLines(dir.resolve("feedback/real-feedback-triples.ndjson"), StandardCharsets.UTF_8)
            .stream()
            .filter(l -> !l.isBlank())
            .toList();
    assertEquals(3, rows.size());
    long negatives = rows.stream().filter(r -> r.contains("\"is_negative\":true")).count();
    assertEquals(2, negatives, "d1 and d3 are derived SHOWN negatives");
  }

  @Test
  void project_noPositive_derivesNoShownNegatives(@TempDir Path dir) throws IOException {
    // A query the user never engaged with (no positive) must not manufacture an all-negative group:
    // the derived-SHOWN pass only fires for queries with a positive.
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-B",
                "q",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 100L),
                    new FeatureSnapshot.HitFeatures("d2", 2, 0.5f, 0.4f, 0.3f, 0.45f, 100L))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-B", "d1", ResultDisposition.Kind.REFINED_WITHOUT_OPENING,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 2L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(1, result.triples(), "only the explicit REFINED negative; no derived SHOWN");
    assertEquals(0, result.contrastGroups(), "no positive → not a contrast group");
  }

  @Test
  void labelFor_gradesPositiveAndNegative() {
    assertFalse(LabelProjection.labelFor(ResultDisposition.Kind.CITED).isNegative());
    assertFalse(LabelProjection.labelFor(ResultDisposition.Kind.OPENED).isNegative());
    assertTrue(LabelProjection.labelFor(ResultDisposition.Kind.SHOWN).isNegative());
    assertTrue(
        LabelProjection.labelFor(ResultDisposition.Kind.REFINED_WITHOUT_OPENING).isNegative(),
        "the recall-failure signal must be a negative label");
    assertEquals(1.0f, LabelProjection.labelFor(ResultDisposition.Kind.CITED).score());
  }
}
