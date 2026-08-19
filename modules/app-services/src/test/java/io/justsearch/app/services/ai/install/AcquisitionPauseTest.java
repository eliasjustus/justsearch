/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Pause and resume — the mechanism, and what it does to a run.
 *
 * <p>Pause is deliberately NOT cancel: a paused run keeps its place in the set and continues where
 * it stopped, while a cancelled one is over. Both halves are pinned here, including the case that
 * makes them meet — a cancel raised while the run is halted must win rather than wait for a resume
 * that will never come.
 */
final class AcquisitionPauseTest {

  private static AcquisitionScheduler.Item item(String id, String pkg, long size) {
    return new AcquisitionScheduler.Item(id, pkg, size);
  }

  private static ResumableFetch.Outcome ok() {
    return new ResumableFetch.Outcome(true, false, null, DownloadResume.Action.FRESH, 1, null);
  }

  @Test
  @Timeout(10)
  @DisplayName("a run halts before its next item and continues on resume")
  void pauseHaltsBetweenItemsAndResumeContinues() throws Exception {
    AcquisitionPause pause = new AcquisitionPause(() -> false, 5L);
    List<String> fetched = new ArrayList<>();
    CountDownLatch firstFetched = new CountDownLatch(1);

    pause.pause();
    Thread run =
        new Thread(
            () ->
                new AcquisitionScheduler(
                        List.of(item("a", "p", 10), item("b", "p", 20)),
                        (it, tier, progress) -> {
                          fetched.add(it.id());
                          firstFetched.countDown();
                          return ok();
                        },
                        it -> null,
                        AcquisitionScheduler.AttemptLedger.none(),
                        new AcquisitionScheduler.Listener() {},
                        () -> false,
                        new AtomicLong()::get,
                        pause)
                    .run(),
            "paused-acquisition");
    run.start();

    assertFalse(
        firstFetched.await(200, TimeUnit.MILLISECONDS),
        "a run paused before its first item must not fetch anything");
    assertTrue(pause.isPaused());
    assertEquals(List.of(), fetched);

    pause.resume();
    run.join(5_000);
    assertFalse(run.isAlive(), "the resumed run finishes");
    assertFalse(pause.isPaused());
    assertEquals(List.of("a", "b"), fetched, "it continues with the whole set, in order");
  }

  @Test
  @Timeout(10)
  @DisplayName("a pause raised mid-set stops the NEXT item, not the one in flight")
  void pauseStopsTheNextItemNotTheOneInFlight() throws Exception {
    AcquisitionPause pause = new AcquisitionPause(() -> false, 5L);
    List<String> fetched = new ArrayList<>();
    CountDownLatch firstDone = new CountDownLatch(1);

    Thread run =
        new Thread(
            () ->
                new AcquisitionScheduler(
                        List.of(item("a", "p", 10), item("b", "p", 20)),
                        (it, tier, progress) -> {
                          if ("a".equals(it.id())) {
                            pause.pause(); // raised while item a is transferring
                          }
                          fetched.add(it.id());
                          firstDone.countDown();
                          return ok();
                        },
                        it -> null,
                        AcquisitionScheduler.AttemptLedger.none(),
                        new AcquisitionScheduler.Listener() {},
                        () -> false,
                        new AtomicLong()::get,
                        pause)
                    .run(),
            "mid-set-pause");
    run.start();

    assertTrue(firstDone.await(5, TimeUnit.SECONDS), "the item in flight still completes");
    Thread.sleep(100);
    assertEquals(List.of("a"), fetched, "and the run then halts before item b");

    pause.resume();
    run.join(5_000);
    assertEquals(List.of("a", "b"), fetched);
  }

  @Test
  @Timeout(10)
  @DisplayName("cancelling a paused run ends it — a pause is not a place to get stuck")
  void cancelWhilePausedEndsTheRun() throws Exception {
    AtomicBoolean cancelFlag = new AtomicBoolean(false);
    AcquisitionPause pause = new AcquisitionPause(cancelFlag::get, 5L);
    List<String> fetched = new ArrayList<>();
    java.util.concurrent.atomic.AtomicReference<AcquisitionScheduler.Summary> summary =
        new java.util.concurrent.atomic.AtomicReference<>();

    pause.pause();
    Thread run =
        new Thread(
            () ->
                summary.set(
                    new AcquisitionScheduler(
                            List.of(item("a", "p", 10)),
                            (it, tier, progress) -> {
                              fetched.add(it.id());
                              return ok();
                            },
                            it -> null,
                            AcquisitionScheduler.AttemptLedger.none(),
                            new AcquisitionScheduler.Listener() {},
                            cancelFlag::get,
                            new AtomicLong()::get,
                            pause)
                        .run()),
            "cancel-while-paused");
    run.start();
    Thread.sleep(100);

    cancelFlag.set(true);
    pause.wakeForCancellation();
    run.join(5_000);

    assertFalse(run.isAlive(), "the cancelled run stops waiting for a resume that will not come");
    assertEquals(List.of(), fetched);
    assertTrue(summary.get().cancelled());
    assertTrue(pause.isPaused(), "cancelling is not resuming — the pause state is left as it was");
  }

  @Test
  @Timeout(10)
  @DisplayName("an un-paused gate never blocks, and resume on a running run is a no-op")
  void unpausedGateIsTransparent() {
    AcquisitionPause pause = new AcquisitionPause(() -> false, 5L);
    assertTrue(pause.awaitRunnable());
    pause.resume();
    assertFalse(pause.isPaused());
    assertTrue(pause.awaitRunnable());
    assertTrue(AcquisitionScheduler.PauseGate.open().awaitRunnable());
  }

  @Test
  @Timeout(10)
  @DisplayName("a gate that refuses to become runnable ends the run as cancelled")
  void gateRefusalIsCancellation() {
    AcquisitionScheduler.Summary summary =
        new AcquisitionScheduler(
                List.of(item("a", "p", 10), item("b", "p", 20)),
                (it, tier, progress) -> ok(),
                it -> null,
                AcquisitionScheduler.AttemptLedger.none(),
                new AcquisitionScheduler.Listener() {},
                () -> false,
                new AtomicLong()::get,
                () -> false)
            .run();

    assertTrue(summary.cancelled());
    assertEquals(0, summary.installed());
  }
}
