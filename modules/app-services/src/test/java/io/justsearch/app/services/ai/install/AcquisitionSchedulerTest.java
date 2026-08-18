/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The download set, driven entirely through fakes.
 *
 * <p>Before {@link AcquisitionScheduler} existed this logic was a {@code for} loop inside a 265-line
 * method whose only entry point downloads ~9 GB over the network onto Windows, so none of the
 * properties below had a test: ordering, per-item state, the per-package cumulative byte accounting
 * a multi-file package needs, cancellation mid-set, whether one failure ends the run, and the
 * handoff that makes repair pass <em>n</em> meet transport tier <em>n</em>. Every one of them is
 * exercised here with no network, no filesystem and no Windows.
 */
final class AcquisitionSchedulerTest {

  private static AcquisitionScheduler.Item item(String id, String pkg, long size) {
    return new AcquisitionScheduler.Item(id, pkg, size);
  }

  private static ResumableFetch.Outcome ok() {
    return new ResumableFetch.Outcome(true, false, null, DownloadResume.Action.FRESH, 1, null);
  }

  private static ResumableFetch.Outcome resumed(DownloadResume.Action action) {
    return new ResumableFetch.Outcome(true, false, null, action, 1, null);
  }

  private static ResumableFetch.Outcome transportFailure(String message) {
    return new ResumableFetch.Outcome(
        false,
        false,
        message,
        DownloadResume.Action.FRESH,
        3,
        TransportFailure.curlExit(52, message));
  }

  /** A failure the transport does NOT classify — verification, bookkeeping, directory prep. */
  private static ResumableFetch.Outcome unclassifiedFailure(String message) {
    return new ResumableFetch.Outcome(false, false, message, null, 1, null);
  }

  private static ResumableFetch.Outcome cancelledOutcome() {
    return new ResumableFetch.Outcome(
        false, true, "Cancelled.", DownloadResume.Action.FRESH, 1, null);
  }

  /** Records every event in the order it arrived, as {@code "<event>:<itemId>"} strings. */
  private static final class RecordingListener implements AcquisitionScheduler.Listener {
    final List<String> events = new ArrayList<>();
    final Map<String, Long> lastOverall = new LinkedHashMap<>();
    final Map<String, Long> lastPackageBytes = new LinkedHashMap<>();
    final List<Integer> terminalAttemptCounts = new ArrayList<>();

    @Override
    public void onItemStarted(AcquisitionScheduler.Item item) {
      events.add("started:" + item.id());
    }

    @Override
    public void onItemVerifying(AcquisitionScheduler.Item item) {
      events.add("verifying:" + item.id());
    }

    @Override
    public void onAttempt(AcquisitionScheduler.Item item, int attempt, int maxAttempts) {
      events.add("attempt:" + item.id() + ":" + attempt + "/" + maxAttempts);
    }

    @Override
    public void onProgress(
        AcquisitionScheduler.Item item,
        long overallBytes,
        long packageBytes,
        AcquisitionRate.Estimate estimate) {
      lastOverall.put(item.id(), overallBytes);
      lastPackageBytes.put(item.packageId(), packageBytes);
    }

    @Override
    public void onItemResumed(AcquisitionScheduler.Item item) {
      events.add("resumed:" + item.id());
    }

    @Override
    public void onItemTerminal(AcquisitionScheduler.Item item, int attemptCount) {
      events.add("terminal:" + item.id());
      terminalAttemptCounts.add(attemptCount);
    }

    @Override
    public void onItemFailed(AcquisitionScheduler.Item item, String message) {
      events.add("failed:" + item.id() + ":" + message);
    }

    @Override
    public void onItemInstalled(AcquisitionScheduler.Item item) {
      events.add("installed:" + item.id());
    }
  }

  /** A ledger that remembers in memory what {@link InstallAttemptMemory} remembers on disk. */
  private static final class FakeLedger implements AcquisitionScheduler.AttemptLedger {
    final Map<String, Integer> startTiers = new LinkedHashMap<>();
    final Map<String, Integer> lifetimeAttempts = new LinkedHashMap<>();
    final List<String> terminalIds = new ArrayList<>();
    final List<String> calls = new ArrayList<>();

    @Override
    public int startTierFor(String itemId) {
      calls.add("startTierFor:" + itemId);
      return startTiers.getOrDefault(itemId, 0);
    }

    @Override
    public boolean isTerminal(String itemId) {
      return terminalIds.contains(itemId);
    }

    @Override
    public int attemptCount(String itemId) {
      return lifetimeAttempts.getOrDefault(itemId, 0);
    }

    @Override
    public void recordTransportFailure(
        String itemId, ResumableFetch.Outcome outcome, int startTier) {
      calls.add("recordTransportFailure:" + itemId + ":tier" + startTier);
    }

    @Override
    public void recordSuccess(String itemId) {
      calls.add("recordSuccess:" + itemId);
    }
  }

  @Test
  @Timeout(10)
  @DisplayName("items run in the order the set declared them, one at a time")
  void runsItemsInOrderSequentially() {
    RecordingListener listener = new RecordingListener();
    List<String> fetchOrder = new ArrayList<>();
    AtomicBoolean overlapping = new AtomicBoolean(false);
    AtomicBoolean inFlight = new AtomicBoolean(false);

    AcquisitionScheduler.Summary summary =
        new AcquisitionScheduler(
                List.of(item("a", "p1", 10), item("b", "p1", 20), item("c", "p2", 30)),
                (it, tier, progress) -> {
                  if (!inFlight.compareAndSet(false, true)) overlapping.set(true);
                  fetchOrder.add(it.id());
                  inFlight.set(false);
                  return ok();
                },
                it -> null,
                AcquisitionScheduler.AttemptLedger.none(),
                listener,
                () -> false,
                new AtomicLong()::get)
            .run();

    assertEquals(List.of("a", "b", "c"), fetchOrder, "plan order is run order");
    assertFalse(overlapping.get(), "concurrency is a later phase; nothing here may enable it");
    assertFalse(summary.cancelled());
    assertEquals(3, summary.installed());
    assertEquals(0, summary.failed());
    assertEquals(60L, summary.acquiredBytes());
    assertEquals(
        List.of("started:a", "installed:a", "started:b", "installed:b", "started:c", "installed:c"),
        listener.events);
  }

  @Test
  @Timeout(10)
  @DisplayName("each item walks PENDING -> RUNNING -> INSTALLED, and a failure lands on FAILED")
  void tracksPerItemState() {
    AcquisitionScheduler scheduler =
        new AcquisitionScheduler(
            List.of(item("a", "p1", 10), item("b", "p1", 20)),
            (it, tier, progress) ->
                "b".equals(it.id()) ? unclassifiedFailure("boom") : ok(),
            it -> null,
            AcquisitionScheduler.AttemptLedger.none(),
            new RecordingListener(),
            () -> false,
            new AtomicLong()::get);

    assertEquals(
        Map.of(
            "a", AcquisitionScheduler.ItemState.PENDING,
            "b", AcquisitionScheduler.ItemState.PENDING),
        scheduler.states(),
        "before the run, nothing has been attempted");

    scheduler.run();

    assertEquals(AcquisitionScheduler.ItemState.INSTALLED, scheduler.states().get("a"));
    assertEquals(AcquisitionScheduler.ItemState.FAILED, scheduler.states().get("b"));
  }

  @Test
  @Timeout(10)
  @DisplayName("a multi-file package accumulates bytes across its files instead of overwriting them")
  void perPackageBytesAreCumulativeAcrossAMultiFilePackage() {
    RecordingListener listener = new RecordingListener();

    // Two files in package "splade" and one in "chat". Each fetch reports progress from zero for
    // ITS OWN bytes, which is exactly the shape that used to reset the package counter.
    new AcquisitionScheduler(
            List.of(
                item("splade/model.onnx", "splade", 1000),
                item("splade/vocab.txt", "splade", 500),
                item("chat/model.gguf", "chat", 4000)),
            (it, tier, progress) -> {
              progress.onProgress(it.sizeBytes() / 2, it.sizeBytes());
              progress.onProgress(it.sizeBytes(), it.sizeBytes());
              return ok();
            },
            it -> null,
            AcquisitionScheduler.AttemptLedger.none(),
            listener,
            () -> false,
            new AtomicLong()::get)
        .run();

    assertEquals(
        1500L,
        listener.lastPackageBytes.get("splade"),
        "the second splade file must add to the first, not replace it (tempdoc 374 round 2 #7)");
    assertEquals(4000L, listener.lastPackageBytes.get("chat"));
    assertEquals(
        5500L,
        listener.lastOverall.get("chat/model.gguf"),
        "the overall counter carries every earlier item's banked bytes");
  }

  @Test
  @Timeout(10)
  @DisplayName("an item's bytes are banked only once it is actually placed")
  void placementFailureDoesNotCreditBytes() {
    RecordingListener listener = new RecordingListener();

    AcquisitionScheduler.Summary summary =
        new AcquisitionScheduler(
                List.of(item("a", "p1", 100), item("b", "p1", 200)),
                (it, tier, progress) -> {
                  progress.onProgress(it.sizeBytes(), it.sizeBytes());
                  return ok();
                },
                it -> "a".equals(it.id()) ? "Failed to finalize: disk full" : null,
                AcquisitionScheduler.AttemptLedger.none(),
                listener,
                () -> false,
                new AtomicLong()::get)
            .run();

    assertEquals(
        200L, summary.acquiredBytes(), "a file that downloaded but could not be moved is not acquired");
    assertEquals(1, summary.installed());
    assertEquals(1, summary.failed());
    assertTrue(listener.events.contains("failed:a:Failed to finalize: disk full"));
    assertTrue(listener.events.contains("installed:b"));
    assertEquals(200L, listener.lastPackageBytes.get("p1"), "b's progress starts from a's zero");
  }

  @Test
  @Timeout(10)
  @DisplayName("one item failing does not abort the rest of the set")
  void failureIsIsolatedToItsItem() {
    RecordingListener listener = new RecordingListener();

    AcquisitionScheduler.Summary summary =
        new AcquisitionScheduler(
                List.of(item("a", "p1", 10), item("b", "p2", 20), item("c", "p3", 30)),
                (it, tier, progress) ->
                    "b".equals(it.id()) ? transportFailure("connection reset") : ok(),
                it -> null,
                AcquisitionScheduler.AttemptLedger.none(),
                listener,
                () -> false,
                new AtomicLong()::get)
            .run();

    assertFalse(summary.cancelled(), "a failure is not a cancellation");
    assertEquals(2, summary.installed());
    assertEquals(1, summary.failed());
    assertEquals(
        List.of(
            "started:a",
            "installed:a",
            "started:b",
            "failed:b:connection reset",
            "started:c",
            "installed:c"),
        listener.events,
        "c must still be attempted after b failed");
  }

  @Test
  @Timeout(10)
  @DisplayName("cancellation before an item stops the set and leaves the rest never attempted")
  void cancellationAtTheTopOfAnIterationStopsTheSet() {
    RecordingListener listener = new RecordingListener();
    AtomicBoolean cancelled = new AtomicBoolean(false);

    AcquisitionScheduler scheduler =
        new AcquisitionScheduler(
            List.of(item("a", "p1", 10), item("b", "p1", 20), item("c", "p1", 30)),
            (it, tier, progress) -> {
              if ("a".equals(it.id())) cancelled.set(true);
              return ok();
            },
            it -> null,
            AcquisitionScheduler.AttemptLedger.none(),
            listener,
            cancelled::get,
            new AtomicLong()::get);

    AcquisitionScheduler.Summary summary = scheduler.run();

    assertTrue(summary.cancelled());
    assertEquals(1, summary.installed(), "the item already in flight still finished");
    assertEquals(10L, summary.acquiredBytes());
    assertEquals(AcquisitionScheduler.ItemState.INSTALLED, scheduler.states().get("a"));
    assertEquals(
        AcquisitionScheduler.ItemState.PENDING,
        scheduler.states().get("b"),
        "an item after the cancellation point was never started — PENDING is the truthful verdict");
    assertEquals(AcquisitionScheduler.ItemState.PENDING, scheduler.states().get("c"));
    assertFalse(
        listener.events.stream().anyMatch(e -> e.startsWith("started:b")),
        "b must not be reported as started");
  }

  @Test
  @Timeout(10)
  @DisplayName("a fetch that reports itself cancelled ends the set without failing the package")
  void cancelledOutcomeEndsTheSetWithoutAFailure() {
    RecordingListener listener = new RecordingListener();

    AcquisitionScheduler scheduler =
        new AcquisitionScheduler(
            List.of(item("a", "p1", 10), item("b", "p1", 20)),
            (it, tier, progress) -> cancelledOutcome(),
            it -> null,
            AcquisitionScheduler.AttemptLedger.none(),
            listener,
            () -> false,
            new AtomicLong()::get);

    AcquisitionScheduler.Summary summary = scheduler.run();

    assertTrue(summary.cancelled());
    assertEquals(0, summary.failed(), "a cancelled transfer is not a failed one");
    assertEquals(AcquisitionScheduler.ItemState.CANCELLED, scheduler.states().get("a"));
    assertEquals(AcquisitionScheduler.ItemState.PENDING, scheduler.states().get("b"));
    assertFalse(
        listener.events.stream().anyMatch(e -> e.startsWith("failed:")),
        "cancelling must not mark a package failed");
  }

  @Test
  @Timeout(10)
  @DisplayName("the ledger's start tier reaches the fetcher, and a transport failure is recorded back")
  void escalationHandoffRunsBothWays() {
    FakeLedger ledger = new FakeLedger();
    ledger.startTiers.put("b", 2);
    ledger.lifetimeAttempts.put("b", 7);
    ledger.terminalIds.add("b");
    RecordingListener listener = new RecordingListener();
    Map<String, Integer> tiersSeen = new LinkedHashMap<>();

    new AcquisitionScheduler(
            List.of(item("a", "p1", 10), item("b", "p2", 20)),
            (it, tier, progress) -> {
              tiersSeen.put(it.id(), tier);
              return "b".equals(it.id()) ? transportFailure("connection reset") : ok();
            },
            it -> null,
            ledger,
            listener,
            () -> false,
            new AtomicLong()::get)
        .run();

    assertEquals(0, tiersSeen.get("a"), "a file with no history starts at tier 0");
    assertEquals(2, tiersSeen.get("b"), "pass n meets tier n — the memory is what makes that true");
    assertTrue(ledger.calls.contains("recordSuccess:a"), "a success spends the file's history");
    assertTrue(
        ledger.calls.contains("recordTransportFailure:b:tier2"),
        "the failure is recorded against the tier it was suffered at, not tier 0");
    assertTrue(listener.events.contains("terminal:b"), "three failing passes stops offering repair");
    assertEquals(
        List.of(7),
        listener.terminalAttemptCounts,
        "the terminal verdict quotes the LIFETIME attempt count, not this run's");
  }

  @Test
  @Timeout(10)
  @DisplayName("only transport failures reach the ledger — a verification failure must not escalate")
  void unclassifiedFailureIsNotRecordedAsTransport() {
    FakeLedger ledger = new FakeLedger();

    new AcquisitionScheduler(
            List.of(item("a", "p1", 10)),
            (it, tier, progress) -> unclassifiedFailure("SHA-256 mismatch"),
            it -> null,
            ledger,
            new RecordingListener(),
            () -> false,
            new AtomicLong()::get)
        .run();

    assertFalse(
        ledger.calls.stream().anyMatch(c -> c.startsWith("recordTransportFailure")),
        "a SHA mismatch is a registry problem no other transport fixes — escalating it is pure latency");
    assertFalse(ledger.calls.contains("recordSuccess:a"));
  }

  @Test
  @Timeout(10)
  @DisplayName("a resumed fetch is reported once per item that resumed")
  void resumeVerdictIsProjected() {
    RecordingListener listener = new RecordingListener();

    new AcquisitionScheduler(
            List.of(item("a", "p1", 10), item("b", "p1", 20), item("c", "p1", 30)),
            (it, tier, progress) ->
                switch (it.id()) {
                  case "a" -> resumed(DownloadResume.Action.RESUME_RANGE);
                  case "b" -> resumed(DownloadResume.Action.RESUME_BITS);
                  default -> ok();
                },
            it -> null,
            AcquisitionScheduler.AttemptLedger.none(),
            listener,
            () -> false,
            new AtomicLong()::get)
        .run();

    assertEquals(
        List.of("resumed:a", "resumed:b"),
        listener.events.stream().filter(e -> e.startsWith("resumed:")).toList(),
        "a FRESH fetch must not claim it kept earlier progress");
  }

  @Test
  @Timeout(10)
  @DisplayName("the aggregate rate is fed from the same progress the listener sees")
  void aggregateRateIsMeasuredFromProgress() {
    AtomicLong clock = new AtomicLong(0L);

    AcquisitionScheduler scheduler =
        new AcquisitionScheduler(
            List.of(item("a", "p1", 10_000_000L)),
            (it, tier, progress) -> {
              for (int i = 1; i <= 6; i++) {
                clock.addAndGet(1_000_000_000L);
                progress.onProgress(i * 1_000_000L, it.sizeBytes());
              }
              return ok();
            },
            it -> null,
            AcquisitionScheduler.AttemptLedger.none(),
            new RecordingListener(),
            () -> false,
            clock::get);

    assertEquals(
        AcquisitionRate.Estimate.UNKNOWN, scheduler.estimate(), "nothing sampled, nothing to say");

    scheduler.run();

    AcquisitionRate.Estimate estimate = scheduler.estimate();
    assertTrue(estimate.rateKnown(), "six samples a second apart is a measurable rate");
    assertEquals(1_000_000d, estimate.bytesPerSecond(), 10_000d);
  }

  @Test
  @Timeout(10)
  @DisplayName("an empty set completes cleanly rather than reporting a cancellation or a failure")
  void emptySetIsANoOp() {
    AcquisitionScheduler.Summary summary =
        new AcquisitionScheduler(
                List.of(),
                (it, tier, progress) -> ok(),
                it -> null,
                AcquisitionScheduler.AttemptLedger.none(),
                new RecordingListener(),
                () -> false,
                new AtomicLong()::get)
            .run();

    assertFalse(summary.cancelled());
    assertEquals(0, summary.installed());
    assertEquals(0, summary.failed());
    assertEquals(0L, summary.acquiredBytes());
  }
}
