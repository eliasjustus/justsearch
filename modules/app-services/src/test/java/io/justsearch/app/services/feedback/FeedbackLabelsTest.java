package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 580 §17.5 P5 — guard tests for the FeedbackLabels rebuild glue. */
class FeedbackLabelsTest {

  @Test
  void rebuild_projectsRealLabelsFromPersistedStores(@TempDir Path dataDir) throws IOException {
    var snapshots =
        new NdjsonAppendStore<>(
            dataDir.resolve("feedback").resolve("feature-snapshots.ndjson"), FeatureSnapshot.class);
    snapshots.append(
        new FeatureSnapshot(
            "iid-1", "q", 1L,
            List.of(new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 1024L))));

    var dispositions =
        new NdjsonAppendStore<>(
            dataDir.resolve("feedback").resolve("result-dispositions.ndjson"),
            ResultDisposition.class);
    dispositions.append(
        new ResultDisposition(
            "iid-1", "d1", ResultDisposition.Kind.CITED,
            ResultDisposition.Contributor.AGENT_CITATION, 2L));

    // Single-hit snapshot: the opened doc is the only hit, so there is nothing to derive a SHOWN
    // negative from → one triple, zero contrast groups (no negative).
    LabelProjection.Result result = FeedbackLabels.rebuild(dataDir);
    assertEquals(1, result.triples());
    assertEquals(0, result.contrastGroups());
    assertTrue(Files.exists(FeedbackLabels.realLabelPath(dataDir)));
    long lines =
        Files.readAllLines(FeedbackLabels.realLabelPath(dataDir), StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .count();
    assertEquals(1, lines);
  }

  @Test
  void rebuild_isIdempotent_doesNotDouble(@TempDir Path dataDir) throws IOException {
    var snapshots =
        new NdjsonAppendStore<>(
            dataDir.resolve("feedback").resolve("feature-snapshots.ndjson"), FeatureSnapshot.class);
    snapshots.append(
        new FeatureSnapshot(
            "iid-1", "q", 1L,
            List.of(new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 1024L))));
    var dispositions =
        new NdjsonAppendStore<>(
            dataDir.resolve("feedback").resolve("result-dispositions.ndjson"),
            ResultDisposition.class);
    dispositions.append(
        new ResultDisposition(
            "iid-1", "d1", ResultDisposition.Kind.OPENED,
            ResultDisposition.Contributor.SEARCH_INTERACTION, 2L));

    FeedbackLabels.rebuild(dataDir);
    FeedbackLabels.rebuild(dataDir); // second rebuild clears + reprojects
    long lines =
        Files.readAllLines(FeedbackLabels.realLabelPath(dataDir), StandardCharsets.UTF_8).stream()
            .filter(l -> !l.isBlank())
            .count();
    assertEquals(1, lines, "rebuild must clear, not append");
  }

  @Test
  void rebuild_noFeedbackYet_returnsZero(@TempDir Path dataDir) {
    assertEquals(0, FeedbackLabels.rebuild(dataDir).triples(), "cold start: no joinable feedback");
  }
}
