package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 580 §17 — guard tests for the generic feedback NDJSON store (snapshots + dispositions). */
class NdjsonAppendStoreTest {

  @Test
  void featureSnapshot_roundtripsWithNullableTokenCount(@TempDir Path dir) throws IOException {
    var store =
        new NdjsonAppendStore<>(dir.resolve("feature-snapshots.ndjson"), FeatureSnapshot.class);
    store.append(
        new FeatureSnapshot(
            "iid-1",
            "the query",
            123L,
            List.of(
                new FeatureSnapshot.HitFeatures("d1", 1, 0.9f, 0.8f, 0.7f, 0.85f, 1024L),
                new FeatureSnapshot.HitFeatures("d2", 2, 0.5f, 0.4f, 0.3f, 0.45f, null))));

    List<FeatureSnapshot> all = store.readAll();
    assertEquals(1, all.size());
    FeatureSnapshot got = all.get(0);
    assertEquals("iid-1", got.interactionId());
    assertEquals(2, got.hits().size());
    assertEquals(1024L, got.hits().get(0).parentTokenCount());
    assertNull(got.hits().get(1).parentTokenCount(), "absent token count must round-trip as null");
  }

  @Test
  void resultDisposition_roundtrips(@TempDir Path dir) throws IOException {
    var store =
        new NdjsonAppendStore<>(dir.resolve("result-dispositions.ndjson"), ResultDisposition.class);
    store.append(
        new ResultDisposition(
            "iid-1",
            "d1",
            ResultDisposition.Kind.CITED,
            ResultDisposition.Contributor.AGENT_CITATION,
            42L));
    store.append(
        new ResultDisposition(
            "iid-1",
            "d2",
            ResultDisposition.Kind.REFINED_WITHOUT_OPENING,
            ResultDisposition.Contributor.SEARCH_INTERACTION,
            43L));

    List<ResultDisposition> all = store.readAll();
    assertEquals(2, all.size());
    assertEquals(ResultDisposition.Kind.CITED, all.get(0).kind());
    assertEquals(ResultDisposition.Contributor.AGENT_CITATION, all.get(0).contributor());
    assertEquals(ResultDisposition.Kind.REFINED_WITHOUT_OPENING, all.get(1).kind());
  }

  @Test
  void readAll_emptyWhenNothingWritten(@TempDir Path dir) throws IOException {
    assertEquals(
        0,
        new NdjsonAppendStore<>(dir.resolve("x.ndjson"), FeatureSnapshot.class).readAll().size());
  }
}
