/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 834 §1.5 / §2 — the run channel directory: open / find / live / retire, and the cap. */
@DisplayName("RunChannelRegistry")
final class RunChannelRegistryTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-08-18T10:00:00Z"));
  private final RunChannelRegistry registry = new RunChannelRegistry(clock);

  private static RunDescriptor descriptor(long startedAtMs) {
    return new RunDescriptor("core.rag-ask", "conv-1", startedAtMs);
  }

  private RunChannel openOneShot(String id) {
    return registry.open(
        new RunId(id), descriptor(clock.millis()), RunChannelPolicy.conversational());
  }

  // ── open / find / live ───────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("open selects the subtype from the policy — parkable ⇒ stepped, else one-shot")
  void policySelectsTheSubtype() {
    RunChannel ask = openOneShot("run-ask");
    RunChannel agent =
        registry.open(new RunId("agent-1"), descriptor(clock.millis()), RunChannelPolicy.agent());

    assertTrue(ask instanceof OneShotRunChannel, "a non-parkable policy must yield a one-shot run");
    assertFalse(ask instanceof SteppedRunChannel, "an ask must not be handed a parkable handle");
    assertTrue(agent instanceof SteppedRunChannel, "a parkable policy must yield a stepped run");
  }

  @Test
  @DisplayName("find returns the open channel; live lists in-flight runs newest first")
  void findAndLive() {
    RunChannel a = registry.open(new RunId("run-a"), descriptor(1000L), RunChannelPolicy.conversational());
    RunChannel b = registry.open(new RunId("run-b"), descriptor(3000L), RunChannelPolicy.conversational());
    registry.open(new RunId("run-c"), descriptor(2000L), RunChannelPolicy.conversational());

    assertSame(a, registry.find(new RunId("run-a")).orElseThrow());
    assertEquals(Optional.empty(), registry.find(new RunId("run-nope")));
    assertEquals(
        List.of("run-b", "run-c", "run-a"),
        registry.live().stream().map(c -> c.id().value()).toList(),
        "live() orders by startedAtEpochMs descending (§3.5)");
    assertSame(b, registry.live().get(0));
  }

  @Test
  @DisplayName("N runs on ONE conversation are all listed — never collapsed (§3.5)")
  void concurrentRunsOnOneConversationAreAllLive() {
    registry.open(
        new RunId("run-1"), new RunDescriptor("core.rag-ask", "conv-x", 1L),
        RunChannelPolicy.conversational());
    registry.open(
        new RunId("run-2"), new RunDescriptor("core.summarize", "conv-x", 2L),
        RunChannelPolicy.conversational());

    assertEquals(
        2,
        registry.live().stream().filter(c -> "conv-x".equals(c.descriptor().conversationId())).count(),
        "nothing serializes two dispatches on one conversationId, so both must be visible");
  }

  @Test
  @DisplayName("reopening a live run id is refused rather than silently replacing its journal")
  void reopeningALiveRunIsRefused() {
    openOneShot("run-dup");
    assertThrows(IllegalStateException.class, () -> openOneShot("run-dup"));
  }

  // ── the cap ──────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("the 33rd LIVE run is refused with a typed error — no live run is ever evicted")
  void capRefusesThe33rdLiveRunInsteadOfEvicting() {
    for (int i = 0; i < RunChannelRegistry.MAX_CHANNELS; i++) {
      openOneShot("run-" + i);
    }

    var refused =
        assertThrows(RunChannelCapacityExceededException.class, () -> openOneShot("run-overflow"));
    assertEquals(RunChannelRegistry.MAX_CHANNELS, refused.capacity());
    assertEquals(
        RunChannelRegistry.MAX_CHANNELS,
        registry.live().size(),
        "every one of the live runs must still be there — refusing is the honest failure");
    assertTrue(
        registry.find(new RunId("run-0")).isPresent(),
        "the OLDEST live run in particular must not have been sacrificed");
  }

  @Test
  @DisplayName("a retired-and-lingering channel is dropped oldest-first to make room")
  void lingeringChannelsAreDroppedOldestFirstToMakeRoom() {
    for (int i = 0; i < RunChannelRegistry.MAX_CHANNELS; i++) {
      openOneShot("run-" + i);
    }
    // Two lingerers, retired a second apart, both still inside a long linger window.
    registry.retire(new RunId("run-5"), Duration.ofHours(1));
    clock.advance(Duration.ofSeconds(1));
    registry.retire(new RunId("run-9"), Duration.ofHours(1));

    RunChannel fresh = openOneShot("run-new");

    assertEquals("run-new", fresh.id().value());
    assertEquals(
        RunChannelRegistry.Lookup.RETIRED,
        registry.lookup(new RunId("run-5")),
        "the OLDEST-retired lingerer is the one dropped");
    assertTrue(
        registry.find(new RunId("run-9")).isPresent(),
        "the newer lingerer keeps its readable ring — only one slot was needed");
  }

  // ── retire: the whole terminal transition ────────────────────────────────────────────────────

  @Test
  @DisplayName("retire refuses publishes, keeps the ring readable for the linger, then tombstones")
  void retireKeepsTheRingReadableForTheLingerThenDrops() {
    RunChannel run = openOneShot("run-linger");
    run.publish(new RunFrame("chunk", Map.of("text", "hello")));

    registry.retire(new RunId("run-linger"), Duration.ofSeconds(60));

    assertTrue(run.retired());
    assertFalse(
        run.publish(new RunFrame("chunk", Map.of("text", "late"))),
        "a late publish from a loop that has not noticed the terminal must be refused");
    assertEquals(
        RunChannelRegistry.Lookup.LIVE,
        registry.lookup(new RunId("run-linger")),
        "inside the linger the run is still observable — a tab reloading as the answer lands replays it");
    List<SseEnvelope> replay = run.channel().framesSince(0);
    assertEquals(1, replay.size(), "the ring stayed readable, and the refused frame never entered it");

    clock.advance(Duration.ofSeconds(61));

    assertEquals(Optional.empty(), registry.find(new RunId("run-linger")));
    assertEquals(
        RunChannelRegistry.Lookup.RETIRED,
        registry.lookup(new RunId("run-linger")),
        "past the linger it is 'this run is over, read the record' — NOT 'never heard of it'");
    assertEquals(
        "core.rag-ask",
        registry.retiredDescriptor(new RunId("run-linger")).orElseThrow().shapeId(),
        "the tombstone keeps enough to answer the 404 body's recordHint");
  }

  @Test
  @DisplayName("an id that was never opened is UNKNOWN, not RETIRED")
  void neverOpenedIsUnknown() {
    assertEquals(RunChannelRegistry.Lookup.UNKNOWN, registry.lookup(new RunId("run-ghost")));
    assertEquals(Optional.empty(), registry.retiredDescriptor(new RunId("run-ghost")));
  }

  @Test
  @DisplayName("retire fires each onRetire listener exactly once, and is idempotent")
  void retireFiresListenersOnce() {
    RunChannel run = openOneShot("run-close");
    var closes = new AtomicInteger();
    run.onRetire(closes::incrementAndGet);

    registry.retire(new RunId("run-close"), Duration.ofSeconds(60));
    registry.retire(new RunId("run-close"), Duration.ofSeconds(60));

    assertEquals(1, closes.get(), "an attached writer must be told to close once, not twice");

    // A writer attaching AFTER the run retired still needs to close, so the callback runs inline.
    var lateCloses = new AtomicInteger();
    run.onRetire(lateCloses::incrementAndGet);
    assertEquals(1, lateCloses.get(), "a late registration must not be silently parked forever");
  }

  @Test
  @DisplayName("a listener that throws while closing does not abort the other observers' retirement")
  void athrowingRetireListenerDoesNotAbortTheRest() {
    RunChannel run = openOneShot("run-throwy");
    var reached = new AtomicInteger();
    run.onRetire(
        () -> {
          throw new IllegalStateException("socket already gone");
        });
    run.onRetire(reached::incrementAndGet);

    registry.retire(new RunId("run-throwy"), Duration.ofSeconds(1));

    assertEquals(1, reached.get());
    assertTrue(run.retired());
  }

  @Test
  @DisplayName("retiring an unknown id is a no-op, not a throw")
  void retiringAnUnknownIdIsANoOp() {
    registry.retire(new RunId("run-nothing"), Duration.ofSeconds(1));
    assertEquals(0, registry.size());
  }

  // ── observation ──────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("observerCount reads the LIVE listener set — a dead observer stops being counted")
  void observerCountDropsWhenAThrowingObserverIsEvicted() {
    RunChannel run = openOneShot("run-observed");
    var alive = new ArrayList<SseEnvelope>();
    // Both handles are held, as a real writer holds them to unsubscribe on close — and the live
    // one is used at the end to show the eviction was the DEAD observer's, not a blanket drop.
    SseStreamChannel.Subscription liveSubscription = run.observe(alive::add, 0).orElseThrow();
    SseStreamChannel.Subscription deadSubscription =
        run.observe(
                frame -> {
                  throw new IllegalStateException("socket closed");
                },
                0)
            .orElseThrow();

    assertEquals(2, run.observerCount());

    run.publish(new RunFrame("chunk", Map.of("text", "x")));

    assertEquals(
        1,
        run.observerCount(),
        "the dead socket is evicted on the failed delivery, which is the precondition the "
            + "zero-observer park depends on (R4)");
    assertEquals(1, alive.size(), "the live observer is unaffected by its neighbour dying");

    // Unsubscribing the already-evicted observer is a no-op, and the survivor is still the one
    // holding the slot — so the count reaches 0 only when the LIVE observer leaves.
    deadSubscription.unsubscribe();
    assertEquals(1, run.observerCount());
    liveSubscription.unsubscribe();
    assertEquals(0, run.observerCount());
  }

  @Test
  @DisplayName("observe(listener, 0) always succeeds — the guaranteed fallback path")
  void observeFromZeroAlwaysSucceeds() {
    RunChannel run = openOneShot("run-zero");
    for (int i = 0; i < 10; i++) {
      run.publish(new RunFrame("chunk", Map.of("i", i)));
    }
    var seen = new ArrayList<SseEnvelope>();

    Optional<?> subscription = run.observe(seen::add, 0);

    assertTrue(subscription.isPresent());
    assertEquals(10, seen.size(), "an absent cursor replays everything, never snapshot-only");
  }

  @Test
  @DisplayName("a cursor outside the window returns empty AND registers nothing")
  void aWindowMissRegistersNothing() {
    RunChannel run = openOneShot("run-miss");
    run.publish(new RunFrame("chunk", Map.of("text", "a")));
    var seen = new ArrayList<SseEnvelope>();

    // A cursor ahead of the stream: a token from another server lifetime.
    Optional<?> missed = run.observe(seen::add, 99);

    assertTrue(missed.isEmpty(), "the miss must be VISIBLE — a silent empty stream is the failure mode");
    assertEquals(0, run.observerCount(), "nothing may be registered on a miss");
    run.publish(new RunFrame("chunk", Map.of("text", "b")));
    assertEquals(0, seen.size(), "and the caller must not be quietly receiving live frames either");
  }

  @Test
  @DisplayName("lifecycle frames consume a seq but never occupy a ring slot (§3.2)")
  void lifecycleFramesAreSequencedButNotRetained() {
    RunChannel run = openOneShot("run-lifecycle");
    run.publish(new RunFrame("chunk", Map.of("text", "a")));

    SseEnvelope heartbeat = run.lifecycle(RunFrame.of("heartbeat"));

    assertEquals(2, heartbeat.seq(), "the wire seq stays monotonic across lifecycle frames");
    assertEquals(
        1,
        run.channel().framesSince(0).size(),
        "a 15 s heartbeat over the life of a parked run must not evict a single narrative frame");
  }

  @Test
  @DisplayName("clear drops every channel and retires what it drops")
  void clearRetiresEverything() {
    RunChannel run = openOneShot("run-cleared");
    registry.clear();
    assertTrue(run.retired());
    assertEquals(0, registry.size());
    assertEquals(RunChannelRegistry.Lookup.UNKNOWN, registry.lookup(new RunId("run-cleared")));
  }

  /** A clock the test moves by hand, so the linger window is tested rather than waited out. */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
