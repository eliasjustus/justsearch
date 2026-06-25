package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.gpl.GplTrainingTripleStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 580 §17 P4 — guard tests for the agent-disposition listener wiring. */
class AgentDispositionWiringTest {

  @Test
  void register_persistsDispositionsOnlyOnDoneEvent(@TempDir Path dataDir) throws IOException {
    AtomicReference<BiConsumer<String, Map<String, Object>>> ref = new AtomicReference<>();
    AgentDispositionWiring.register(ref::set, dataDir);
    assertNotNull(ref.get(), "a listener must be registered");

    Map<String, Object> payload =
        Map.of(
            "sources",
                List.of(
                    Map.of("parentDocId", "d1", "chunkIndex", 0),
                    Map.of("parentDocId", "d2", "chunkIndex", 0)),
            "citations", List.of(Map.of("sourceIndex", 0, "similarity", 0.9)));

    ref.get().accept("tool_call", payload); // non-done → ignored
    ref.get().accept("done", payload); // → 2 dispositions (d1 CITED, d2 SHOWN)

    List<ResultDisposition> all =
        new NdjsonAppendStore<>(
                dataDir.resolve("feedback").resolve("result-dispositions.ndjson"),
                ResultDisposition.class)
            .readAll();
    assertEquals(2, all.size());
    assertEquals(
        ResultDisposition.Contributor.AGENT_CITATION, all.get(0).contributor());
  }

  @Test
  void agentRun_capturesSnapshot_andDispositionsJoinIt_bySessionId(@TempDir Path dataDir)
      throws IOException {
    // Fix B end-to-end: a search tool's feedbackFeatures → a FeatureSnapshot keyed by sessionId, and
    // the done-event dispositions keyed by the SAME sessionId, so they JOIN into real labels. Before
    // the fix, agent dispositions used a fresh agent-<UUID> with no snapshot and were ALL dropped.
    AtomicReference<BiConsumer<String, Map<String, Object>>> ref = new AtomicReference<>();
    AgentDispositionWiring.register(ref::set, dataDir);

    // (1) a search tool completed — carries the per-leg feedbackFeatures + the run's sessionId.
    Map<String, Object> toolDone =
        Map.of(
            "sessionId", "s1",
            "structuredData",
                Map.of(
                    "feedbackFeatures",
                    List.of(
                        Map.of("docId", "d1", "rank", 1, "sparse", 2.0f, "dense", 1.0f,
                            "splade", 0.5f, "fused", 1.5f),
                        Map.of("docId", "d2", "rank", 2, "sparse", 0.4f, "dense", 0.3f,
                            "splade", 0.1f, "fused", 0.3f))));
    ref.get().accept("tool_exec_completed", toolDone);

    // (2) the run finished — d1 cited, d2 grounding-but-not-cited; keyed by the same sessionId.
    Map<String, Object> done =
        Map.of(
            "sessionId", "s1",
            "sources",
                List.of(
                    Map.of("parentDocId", "d1", "chunkIndex", 0),
                    Map.of("parentDocId", "d2", "chunkIndex", 0)),
            "citations", List.of(Map.of("sourceIndex", 0, "similarity", 0.9)));
    ref.get().accept("done", done);

    Path feedback = dataDir.resolve("feedback");
    List<FeatureSnapshot> snaps =
        new NdjsonAppendStore<>(feedback.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class)
            .readAll();
    List<ResultDisposition> disps =
        new NdjsonAppendStore<>(
                feedback.resolve("result-dispositions.ndjson"), ResultDisposition.class)
            .readAll();

    assertEquals(1, snaps.size(), "one agent snapshot keyed by sessionId");
    assertEquals("s1", snaps.get(0).interactionId());
    assertEquals(2, snaps.get(0).hits().size());
    assertEquals(2, disps.size(), "d1 CITED + d2 SHOWN, both keyed by sessionId s1");
    assertEquals("s1", disps.get(0).interactionId());

    // THE FIX: the dispositions now JOIN their snapshot → real labels (was 0 before Fix B).
    var store = new GplTrainingTripleStore(dataDir, "feedback/real-feedback-triples.ndjson");
    LabelProjection.Result result = LabelProjection.project(disps, snaps, store);
    assertEquals(2, result.triples(), "d1 (CITED→positive) + d2 (SHOWN→negative) both join the snapshot");
    assertTrue(result.contrastGroups() >= 1, "the run is a contrast group (a positive AND a negative)");
  }
}
