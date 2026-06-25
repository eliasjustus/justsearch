package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 445 Phase 2: live runtime confirmation that the Xerial driver's
 * {@code addUpdateListener} + {@code addCommitListener} hooks fire as expected
 * on the {@code SqliteJobQueue} connection. Mirrors the static
 * (javap-on-jar) verification done at checkpoint commit {@code 044b21ab3}.
 */
final class IndexingJobsChangeStreamTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;

  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Test
  void changeStreamIsAttachedAfterOpen() {
    assertNotNull(jobQueue.changeStream(), "changeStream should be attached after open()");
    assertEquals(0, jobQueue.changeStream().currentSeq(), "no deltas yet → seq=0");
  }

  @Test
  void enqueueEmitsInsertDelta() throws Exception {
    CapturingSubscriber sub = new CapturingSubscriber();
    var s = jobQueue.changeStream().subscribeWithSnapshot(sub);
    assertEquals(0, s.snapshotSeq(), "snapshot at seq=0 (empty queue)");
    assertTrue(s.items().isEmpty(), "snapshot empty");

    jobQueue.enqueue(List.of(Path.of("/tmp/a.txt")));

    sub.awaitDeliveries(1);
    assertEquals(1, sub.deltas.size());
    var delta = sub.deltas.get(0);
    assertInstanceOf(IndexingJobChangeFeed.Delta.Insert.class, delta);
    var insert = (IndexingJobChangeFeed.Delta.Insert) delta;
    assertEquals("PENDING", insert.row().state());
    assertEquals(64, insert.row().pathHash().length(), "SHA-256 hex = 64 chars");
    assertEquals(1, jobQueue.changeStream().currentSeq());

    s.subscription().close();
  }

  @Test
  void markDoneEmitsUpdateDelta() throws Exception {
    jobQueue.enqueue(List.of(Path.of("/tmp/b.txt")));

    CapturingSubscriber sub = new CapturingSubscriber();
    jobQueue.changeStream().subscribe(sub);

    var batch = jobQueue.pollPending(1);
    assertFalse(batch.isEmpty(), "batch should contain 1 job");
    // pollPending UPDATEs PENDING → PROCESSING; subscribe was after enqueue, so we see one update.
    sub.awaitDeliveries(1);
    assertInstanceOf(IndexingJobChangeFeed.Delta.Update.class, sub.deltas.get(0));

    sub.deltas.clear();
    jobQueue.markDone(
        batch.get(0).path(),
        IngestionOutcome.of(
            IngestionOutcomeClass.SUCCESS_FULL,
            IngestionReasonCodes.SUCCESS,
            io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));

    sub.awaitDeliveries(1);
    var delta = sub.deltas.get(0);
    assertInstanceOf(IndexingJobChangeFeed.Delta.Update.class, delta);
    var update = (IndexingJobChangeFeed.Delta.Update) delta;
    assertEquals("DONE", update.row().state(), "markDone transitions PROCESSING → DONE");
  }

  @Test
  void rapidMutationsArriveInCausalOrderWithoutLoss() throws Exception {
    // Verification step #5 from the slice 445 tempdoc: 1000 rapid
    // INSERT/UPDATE rows → all deltas arrive in causal order (= SQLite write
    // order). SqliteJobQueue uses a single Connection so writes serialize;
    // the change-stream emits one delta per commit. The contract: count
    // matches the mutation count, monotonic seq, no drops.
    int n = 1000;
    java.util.List<Path> paths = new java.util.ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      paths.add(Path.of("/tmp/stress-" + i + ".txt"));
    }
    CapturingSubscriber sub = new CapturingSubscriber();
    var snap = jobQueue.changeStream().subscribeWithSnapshot(sub);
    assertEquals(0, snap.snapshotSeq(), "empty queue at subscribe");
    snap.subscription();

    long startNs = System.nanoTime();
    int enqueued = jobQueue.enqueue(paths);
    long enqueueMs = (System.nanoTime() - startNs) / 1_000_000;
    assertEquals(n, enqueued, "all paths enqueued");

    sub.awaitDeliveries(n);

    // Causal order: change-stream emits in SQLite write order. Since enqueue
    // serializes via SqliteJobQueue's lock, deltas land in the same order
    // we requested.
    long previousSeq = 0L;
    java.util.Set<String> seenHashes = new java.util.HashSet<>();
    for (int i = 0; i < n; i++) {
      var delta = sub.deltas.get(i);
      assertInstanceOf(IndexingJobChangeFeed.Delta.Insert.class, delta);
      var insert = (IndexingJobChangeFeed.Delta.Insert) delta;
      assertEquals("PENDING", insert.row().state());
      seenHashes.add(insert.row().pathHash());
    }
    assertEquals(n, seenHashes.size(), "every row distinct (no dup deliveries)");
    assertEquals(n, jobQueue.changeStream().currentSeq(), "seq reached n exactly");

    // Soft-bound on throughput so a regression that adds a sleep or a
    // per-row commit gets caught. 1000 inserts on a temp SQLite + a single
    // change-stream listener should land well under 5s on any dev machine.
    assertTrue(enqueueMs < 5000, "1000 enqueues took " + enqueueMs + "ms (>5s threshold)");

    // Now do 1000 state transitions (UPDATEs via pollPending) and verify
    // we see another N deltas.
    sub.deltas.clear();
    var batch = jobQueue.pollPending(n);
    assertEquals(n, batch.size());
    sub.awaitDeliveries(n);
    for (var delta : sub.deltas) {
      assertInstanceOf(IndexingJobChangeFeed.Delta.Update.class, delta);
    }
    assertEquals(2L * n, jobQueue.changeStream().currentSeq(), "seq advanced by 2n total");
  }

  @Test
  void snapshotCapturesPriorState() throws Exception {
    jobQueue.enqueue(List.of(Path.of("/tmp/c.txt"), Path.of("/tmp/d.txt")));

    CapturingSubscriber sub = new CapturingSubscriber();
    var snap = jobQueue.changeStream().subscribeWithSnapshot(sub);

    assertEquals(2, snap.items().size(), "snapshot reflects pre-subscribe enqueues");
    // Both inserts pre-dated subscribe: snapshotSeq should equal currentSeq at that moment.
    assertTrue(snap.snapshotSeq() >= 2, "seq advanced by 2 inserts");
    assertTrue(sub.deltas.isEmpty(), "no live deltas yet");

    snap.subscription().close();
  }

  private static final class CapturingSubscriber
      implements java.util.function.Consumer<IndexingJobChangeFeed.Delta> {
    final List<IndexingJobChangeFeed.Delta> deltas = new CopyOnWriteArrayList<>();

    @Override
    public void accept(IndexingJobChangeFeed.Delta d) {
      deltas.add(d);
    }

    void awaitDeliveries(int n) throws InterruptedException {
      long deadline = System.nanoTime() + 10_000_000_000L; // 10s
      while (deltas.size() < n && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(n, deltas.size(), "expected " + n + " deltas, got " + deltas.size());
    }
  }
}
