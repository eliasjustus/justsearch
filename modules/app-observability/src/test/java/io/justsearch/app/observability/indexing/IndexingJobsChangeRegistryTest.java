package io.justsearch.app.observability.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IndexingJobsChangeRegistry")
final class IndexingJobsChangeRegistryTest {

  @Test
  @DisplayName("typed listener receives Insert / Update / Delete payloads")
  void typedFanout() {
    var registry = new IndexingJobsChangeRegistry();
    var deltas = new CopyOnWriteArrayList<IndexingJobsChangeRegistry.Delta>();
    registry.subscribeTyped(deltas::add);

    registry.broadcast(new IndexingJobsChangeRegistry.Delta.Insert(view("hash-a", "PENDING")));
    registry.broadcast(new IndexingJobsChangeRegistry.Delta.Update(view("hash-a", "PROCESSING")));
    registry.broadcast(new IndexingJobsChangeRegistry.Delta.Delete("hash-a"));

    assertEquals(3, deltas.size());
    assertInstanceOf(IndexingJobsChangeRegistry.Delta.Insert.class, deltas.get(0));
    assertInstanceOf(IndexingJobsChangeRegistry.Delta.Update.class, deltas.get(1));
    assertInstanceOf(IndexingJobsChangeRegistry.Delta.Delete.class, deltas.get(2));
  }

  @Test
  @DisplayName("envelope listener receives UPDATE frames with monotonic seq")
  void envelopeFanout() {
    var registry = new IndexingJobsChangeRegistry();
    var envelopes = new CopyOnWriteArrayList<SseEnvelope>();
    registry.subscribe(envelopes::add);

    long seqBefore = registry.currentSeq();
    registry.broadcast(new IndexingJobsChangeRegistry.Delta.Insert(view("hash-x", "PENDING")));
    registry.broadcast(new IndexingJobsChangeRegistry.Delta.Insert(view("hash-y", "PENDING")));

    assertEquals(2, envelopes.size());
    assertEquals(SseFrameKind.UPDATE, envelopes.get(0).frameKind());
    assertNotEquals(seqBefore, envelopes.get(0).seq());
    assertTrue(envelopes.get(1).seq() > envelopes.get(0).seq(), "seq is monotonic");
  }

  @Test
  @DisplayName("SnapshotReplaced delta is broadcast like any other delta")
  void snapshotReplacedDelta() {
    var registry = new IndexingJobsChangeRegistry();
    var deltas = new CopyOnWriteArrayList<IndexingJobsChangeRegistry.Delta>();
    registry.subscribeTyped(deltas::add);

    registry.broadcast(
        new IndexingJobsChangeRegistry.Delta.SnapshotReplaced(
            List.of(view("hash-q", "PENDING"))));

    assertEquals(1, deltas.size());
    var snap = (IndexingJobsChangeRegistry.Delta.SnapshotReplaced) deltas.get(0);
    assertEquals(1, snap.items().size());
    assertEquals("hash-q", snap.items().get(0).pathHash());
  }

  private static IndexingJobView view(String hash, String state) {
    return new IndexingJobView(hash, state, 0, 1L, "", 0L, "default");
  }
}
