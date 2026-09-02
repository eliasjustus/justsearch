package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 912 item 2 — commit-reason accounting at the one commit funnel
 * ({@link CommitOps#commitAndTrack(CommitReason)}).
 *
 * <p>885's A3 arm could measure that the commit count barely moved under three multiplicative
 * cadence relaxations but could not say WHICH trigger held the floor, because no per-trigger count
 * existed. These tests pin the two properties that make the new breakdown trustworthy as
 * attribution evidence: each reason accrues to its own slot, and the total is exactly their sum —
 * so a reader can never be shown a breakdown that fails to account for every commit.
 */
class CommitReasonAccountingTest {

  @TempDir Path tempDir;

  /** Opens a real runtime with one uncommitted document pending, so each commit does real work. */
  private RunningRuntime openWithPendingDoc(String name, int docs) throws Exception {
    Path dir = tempDir.resolve(name);
    Files.createDirectories(dir);
    RunningRuntime runtime =
        IndexSchema.fromCatalog(
                FieldCatalogDef.forTesting(4),
                new SsotCommitMetadataSource(),
                new JsonSchemaCommitMetadataValidator())
            .atPath(dir)
            .open();
    for (int i = 0; i < docs; i++) {
      runtime
          .indexingCoordinator()
          .indexSingle(
              new IndexDocument(
                  Map.of(SchemaFields.DOC_ID, name + "-" + i, SchemaFields.DOC_UID, name + "-" + i + "#0")));
    }
    return runtime;
  }

  /**
   * The load-bearing property: a mixed sequence of reasons lands in per-reason slots whose sum is
   * the total. Asserted against a distribution (3/2/1) rather than one commit per reason, so a
   * counter that incremented the wrong slot, or incremented every slot, fails rather than passing
   * on symmetry.
   */
  @Test
  void eachReasonAccruesToItsOwnSlotAndTheTotalIsTheirSum() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("mixed", 6)) {
      CommitOps ops = runtime.commitOps();
      CommitCounters counters = runtime.session().commitCount;
      long before = counters.get();

      ops.commitAndTrack(CommitReason.TIMER);
      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      ops.commitAndTrack(CommitReason.TIMER);
      ops.commitAndTrack(CommitReason.BACKFILL_NER);
      ops.commitAndTrack(CommitReason.INDEXING_LOOP_IDLE);
      ops.commitAndTrack(CommitReason.TIMER);

      assertEquals(3L, counters.get(CommitReason.TIMER), "TIMER must count its own three commits");
      assertEquals(
          2L,
          counters.get(CommitReason.INDEXING_LOOP_IDLE),
          "INDEXING_LOOP_IDLE must count its own two commits");
      assertEquals(
          1L, counters.get(CommitReason.BACKFILL_NER), "BACKFILL_NER must count its one commit");
      assertEquals(
          0L,
          counters.get(CommitReason.DRAIN),
          "A reason that never fired must stay at zero, not absorb another reason's commits");
      assertEquals(before + 6L, counters.get(), "The total must be the sum of the per-reason slots");

      Map<CommitReason, Long> snapshot = counters.snapshot();
      assertEquals(
          counters.get(),
          snapshot.values().stream().mapToLong(Long::longValue).sum(),
          "The snapshot must account for every commit the total claims");
      assertFalse(
          snapshot.containsKey(CommitReason.DRAIN),
          "A reason that never fired must be absent, not reported as zero; snapshot: " + snapshot);
    }
  }

  /**
   * A null reason is attributed to UNKNOWN rather than dropped. Dropping it would break the
   * total-equals-sum property in the one case a caller is most likely to hit by accident.
   */
  @Test
  void aNullReasonIsCountedAsUnknownRatherThanDropped() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("nullreason", 2)) {
      CommitCounters counters = runtime.session().commitCount;
      runtime.commitOps().commitAndTrack((CommitReason) null);
      runtime.commitOps().commitAndTrack();

      assertEquals(
          2L, counters.get(CommitReason.UNKNOWN), "Both unattributed commits must land in UNKNOWN");
      assertEquals(
          counters.get(),
          counters.snapshot().values().stream().mapToLong(Long::longValue).sum(),
          "An unattributed commit must still be accounted for in the breakdown");
    }
  }

  /**
   * The reason reaching the counter must be the same one reaching telemetry. Pinning both from one
   * commit is what stops the breakdown and the {@code index.runtime.commit_total} counter — which
   * the live attribution run reads — from disagreeing about which trigger fired.
   */
  @Test
  void theCounterAndTheTelemetryEventSeeTheSameReason() throws Exception {
    try (RunningRuntime runtime = openWithPendingDoc("telemetry", 1)) {
      java.util.List<CommitReason> observed = new java.util.ArrayList<>();
      runtime.session().telemetryEvents =
          new LuceneRuntimeTypes.TelemetryEvents() {
            @Override
            public void onCommit(long latencyMs, CommitReason reason) {
              observed.add(reason);
            }
          };

      runtime.commitOps().commitAndTrack(CommitReason.BACKFILL_COMBINED);

      assertEquals(
          java.util.List.of(CommitReason.BACKFILL_COMBINED),
          observed,
          "Telemetry must see the caller's reason");
      assertEquals(
          1L,
          runtime.session().commitCount.get(CommitReason.BACKFILL_COMBINED),
          "The counter must see the SAME reason telemetry saw");
      assertTrue(
          runtime.session().commitCount.get(CommitReason.UNKNOWN) == 0L,
          "An attributed commit must not also land in UNKNOWN");
    }
  }
}
