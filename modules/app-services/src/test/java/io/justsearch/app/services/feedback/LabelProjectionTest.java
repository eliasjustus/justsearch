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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 580 §17.5 P5 — guard tests for the disposition⋈snapshot label projection. */
class LabelProjectionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

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
  void project_newRowsJoinByPathAliasButWriteUidAndDeduplicateRepeatedSnapshots(
      @TempDir Path dir) throws Exception {
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-uid",
                "q1",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-opened", "C:/before.md", 1, 0.9f, 0.8f, 0.7f, 0.85f, 10L),
                    new FeatureSnapshot.HitFeatures(
                        "uid-shown", "C:/shown.md", 2, 0.4f, 0.3f, 0.2f, 0.35f, 10L))),
            // A repeated agent search may see the same stable document under a renamed path. The
            // primary UID map must emit at most one derived negative for it.
            new FeatureSnapshot(
                "iid-uid",
                "q2",
                2L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-shown", "C:/shown-renamed.md", 1, 0.5f, 0.4f, 0.3f, 0.45f, 10L))));
    List<ResultDisposition> dispositions =
        List.of(
            // Rolling-compatibility path: unchanged surface id joins through sourceDocId, while
            // the projected triple still uses the stable primary UID.
            new ResultDisposition(
                "iid-uid",
                "C:/before.md",
                ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION,
                3L));
    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");

    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(2, result.triples(), "one positive UID plus one deduplicated shown UID");
    assertEquals(1, result.contrastGroups());
    List<JsonNode> rows =
        Files.readAllLines(store.storeFile(), StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank())
            .map(
                line -> {
                  try {
                    return MAPPER.readTree(line);
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                })
            .toList();
    assertEquals(
        List.of("uid-opened", "uid-shown"),
        rows.stream().map(row -> row.get("doc_id").asText()).sorted().toList());
  }

  @Test
  void project_pathAliasesForSameUidCoalescePositiveOverNegative(@TempDir Path dir)
      throws Exception {
    List<FeatureSnapshot> snapshots = renamedAliasSnapshots();
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-rename",
                "C:/before.md",
                ResultDisposition.Kind.SHOWN,
                ResultDisposition.Contributor.AGENT_CITATION,
                3L),
            new ResultDisposition(
                "iid-rename",
                "C:/after.md",
                ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION,
                4L));
    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");

    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(1, result.triples(), "one UID cannot receive both CITED and SHOWN labels");
    JsonNode row = MAPPER.readTree(Files.readAllLines(store.storeFile()).getFirst());
    assertEquals("uid-renamed", row.get("doc_id").asText());
    assertFalse(row.get("is_negative").asBoolean());
    assertEquals(1.0f, row.get("score").floatValue());
  }

  @Test
  void project_duplicatePositiveAliasesKeepStrongestGrade(@TempDir Path dir) throws Exception {
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-rename",
                "C:/before.md",
                ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION,
                3L),
            new ResultDisposition(
                "iid-rename",
                "C:/after.md",
                ResultDisposition.Kind.DWELLED,
                ResultDisposition.Contributor.SEARCH_INTERACTION,
                4L));
    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");

    LabelProjection.Result result =
        LabelProjection.project(dispositions, renamedAliasSnapshots(), store);

    assertEquals(1, result.triples(), "duplicate positive aliases must emit one UID row");
    JsonNode row = MAPPER.readTree(Files.readAllLines(store.storeFile()).getFirst());
    assertEquals("uid-renamed", row.get("doc_id").asText());
    assertFalse(row.get("is_negative").asBoolean());
    assertEquals(0.8f, row.get("score").floatValue());
  }

  private static List<FeatureSnapshot> renamedAliasSnapshots() {
    return List.of(
        new FeatureSnapshot(
            "iid-rename",
            "q1",
            1L,
            List.of(
                new FeatureSnapshot.HitFeatures(
                    "uid-renamed", "C:/before.md", 1, 1, 1, 1, 1, null))),
        new FeatureSnapshot(
            "iid-rename",
            "q2",
            2L,
            List.of(
                new FeatureSnapshot.HitFeatures(
                    "uid-renamed", "C:/after.md", 1, 1, 1, 1, 1, null))));
  }

  @Test
  void project_conflictingPathAliasFailsClosed(@TempDir Path dir) {
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-conflict",
                "q",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/same.md", 1, 1, 1, 1, 1, null),
                    new FeatureSnapshot.HitFeatures(
                        "uid-b", "C:/same.md", 2, 1, 1, 1, 1, null))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-conflict",
                "C:/same.md",
                ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION,
                2L));
    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");

    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(0, result.triples());
    assertFalse(store.exists());
  }

  // ============ Revision staleness (tempdoc 931 §C.6) ============

  /**
   * The document was cited at revision {@code rev-old}; a later search saw {@code rev-new}. The
   * label describes text that no longer exists, so it is kept — the click really happened — but
   * down-weighted so a fresh label outweighs it.
   */
  @Test
  void project_downWeightsALabelCapturedAtASupersededRevision(@TempDir Path dir)
      throws IOException {
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-old",
                "q",
                1_000L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/a.md", 1, 1, 1, 1, 1, null, "rev-old"))),
            new FeatureSnapshot(
                "iid-new",
                "q",
                2_000L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/a.md", 1, 1, 1, 1, 1, null, "rev-new"))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-old", "uid-a", ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION, 1_100L),
            new ResultDisposition(
                "iid-new", "uid-a", ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION, 2_100L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(2, result.triples());
    assertEquals(1, result.staleTriples(), "only the pre-edit capture is stale");

    List<JsonNode> rows = readTriples(dir);
    JsonNode staleRow = rowFor(rows, "iid-old");
    JsonNode freshRow = rowFor(rows, "iid-new");
    assertEquals(
        1.0f * LabelProjection.STALE_LABEL_WEIGHT,
        (float) staleRow.get("score").asDouble(),
        1e-6f,
        "a CITED at a superseded revision is halved, not dropped");
    assertTrue(staleRow.path("stale").asBoolean(false));
    assertEquals(1.0f, (float) freshRow.get("score").asDouble(), 1e-6f);
    assertFalse(
        freshRow.has("stale"), "a current-revision row stays byte-identical to a pre-931 row");
  }

  @Test
  void project_treatsAMissingRevisionAsUnknownRatherThanStale(@TempDir Path dir)
      throws IOException {
    // A legacy row (no revision) alongside a document that DOES have one. Neither may be
    // down-weighted: null is "we do not know", and inventing a mismatch would silently devalue
    // every label authored before the field existed.
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-legacy",
                "q",
                1_000L,
                List.of(new FeatureSnapshot.HitFeatures("uid-a", "C:/a.md", 1, 1, 1, 1, 1, null))),
            new FeatureSnapshot(
                "iid-current",
                "q",
                2_000L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/a.md", 1, 1, 1, 1, 1, null, "rev-new"))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-legacy", "uid-a", ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION, 1_100L),
            new ResultDisposition(
                "iid-current", "uid-a", ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION, 2_100L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(2, result.triples());
    assertEquals(0, result.staleTriples());
    for (JsonNode row : readTriples(dir)) {
      assertEquals(1.0f, (float) row.get("score").asDouble(), 1e-6f);
      assertFalse(row.has("stale"));
    }
  }

  @Test
  void project_readsTheNewestRevisionFromTheLatestCaptureNotTheLastListed(@TempDir Path dir)
      throws IOException {
    // The OLDER capture is listed second. Latest-capture-wins is a timestamp rule, not an
    // iteration-order rule; reading the list order instead would invert which label is stale.
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-new",
                "q",
                2_000L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/a.md", 1, 1, 1, 1, 1, null, "rev-new"))),
            new FeatureSnapshot(
                "iid-old",
                "q",
                1_000L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/a.md", 1, 1, 1, 1, 1, null, "rev-old"))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-old", "uid-a", ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 1_100L),
            new ResultDisposition(
                "iid-new", "uid-a", ResultDisposition.Kind.OPENED,
                ResultDisposition.Contributor.SEARCH_INTERACTION, 2_100L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    assertEquals(1, result.staleTriples());
    assertTrue(rowFor(readTriples(dir), "iid-old").path("stale").asBoolean(false));
    assertFalse(rowFor(readTriples(dir), "iid-new").has("stale"));
  }

  @Test
  void project_leavesASingleRevisionUnstaleHoweverOldItIs(@TempDir Path dir) throws IOException {
    // One revision observed everywhere: the document has not been edited, so nothing is superseded.
    List<FeatureSnapshot> snapshots =
        List.of(
            new FeatureSnapshot(
                "iid-1",
                "q",
                1L,
                List.of(
                    new FeatureSnapshot.HitFeatures(
                        "uid-a", "C:/a.md", 1, 1, 1, 1, 1, null, "rev-only"),
                    new FeatureSnapshot.HitFeatures(
                        "uid-b", "C:/b.md", 2, 1, 1, 1, 1, null, "rev-b"))));
    List<ResultDisposition> dispositions =
        List.of(
            new ResultDisposition(
                "iid-1", "uid-a", ResultDisposition.Kind.CITED,
                ResultDisposition.Contributor.AGENT_CITATION, 2L));

    var store = new GplTrainingTripleStore(dir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(dispositions, snapshots, store);

    // uid-a explicit CITED + uid-b derived SHOWN (the contrast), neither stale.
    assertEquals(2, result.triples());
    assertEquals(0, result.staleTriples());
  }

  private static List<JsonNode> readTriples(Path dir) throws IOException {
    List<JsonNode> rows = new java.util.ArrayList<>();
    for (String line :
        Files.readAllLines(
            dir.resolve("feedback/real-feedback-triples.ndjson"), StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        rows.add(MAPPER.readTree(line));
      }
    }
    return rows;
  }

  private static JsonNode rowFor(List<JsonNode> rows, String queryId) {
    return rows.stream()
        .filter(r -> queryId.equals(r.path("query_id").asText(null)))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no triple for " + queryId + " in " + rows));
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
