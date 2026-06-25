package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 550 §B.2 anti-drift CONFORMANCE — the real teeth the static {@code operation-surface}
 * gate cannot provide. §B.2 was a divergence between two projections of the one canonical record
 * (the {@code jobs} table): the COUNT projection ({@code queueDepth()} → {@code pendingJobs}) and
 * the LIST projection (the change-stream {@code subscribeWithSnapshot} that backs the rail). The
 * static gate registers surfaces but cannot prove they AGREE. This test does: it asserts, across
 * lifecycle transitions, that {@code queueDepth()} always equals the count of non-terminal
 * (PENDING+PROCESSING) rows the change-stream snapshot surfaces. If the two reads ever drift again
 * (the §B.2 class), this fails. Template: KnowledgeWireContractConformanceTest (cross-representation
 * subset assertion).
 */
@DisplayName("§B.2 conformance: queueDepth() == change-stream non-terminal row count")
final class SqliteJobQueueConformanceTest {

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;

  @BeforeEach
  void setUp() throws Exception {
    jobQueue = new SqliteJobQueue(tempDir.resolve("jobs.db"));
    jobQueue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  /** The count projection and the list projection agree at every step of a lifecycle sequence. */
  @Test
  void countAndStreamAgreeAcrossTransitions() throws Exception {
    assertCountEqualsStreamNonTerminal(); // empty: 0 == 0

    jobQueue.enqueue(
        List.of(Path.of("/tmp/a.txt"), Path.of("/tmp/b.txt"), Path.of("/tmp/c.txt")));
    assertCountEqualsStreamNonTerminal(); // 3 PENDING → depth 3

    var polled = jobQueue.pollPending(2); // 2 → PROCESSING
    assertCountEqualsStreamNonTerminal(); // 1 PENDING + 2 PROCESSING → depth 3

    jobQueue.markDone(polled.get(0).path()); // 1 → DONE (terminal)
    assertCountEqualsStreamNonTerminal(); // 1 PENDING + 1 PROCESSING + 1 DONE → depth 2
  }

  private void assertCountEqualsStreamNonTerminal() throws Exception {
    long depth = jobQueue.queueDepth();
    var snap = jobQueue.changeStream().subscribeWithSnapshot(d -> {});
    try {
      long nonTerminal =
          snap.items().stream()
              .filter(
                  r -> {
                    String s = r.state();
                    return "PENDING".equals(s) || "PROCESSING".equals(s);
                  })
              .count();
      assertEquals(
          depth,
          nonTerminal,
          "queueDepth() must equal the change-stream's non-terminal (PENDING+PROCESSING) row count"
              + " — the §B.2 count↔list invariant");
    } finally {
      snap.subscription().close();
    }
  }
}
