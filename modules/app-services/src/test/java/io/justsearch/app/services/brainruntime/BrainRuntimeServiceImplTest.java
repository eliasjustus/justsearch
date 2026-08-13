/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.brainruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 737 fix pack (fix 4): {@code switchInferenceMode} records a chat-enabled intent through
 * the ONE runtime-intent authority (spec write + {@code reconciler.specChanged()}) and never
 * raw-switches the engine. The reconciler here is constructed but not started — the assertions only
 * need the synchronous spec write + spec-change nudge, not live convergence.
 */
final class BrainRuntimeServiceImplTest {

  @TempDir Path tmp;

  /** Records raw switch primitives so the test can assert they are never called. */
  private static final class RecordingOnlineAi implements OnlineAiService {
    final AtomicInteger switchOnline = new AtomicInteger();
    final AtomicInteger switchIndexing = new AtomicInteger();

    @Override
    public void switchToOnlineMode() {
      switchOnline.incrementAndGet();
    }

    @Override
    public void switchToIndexingMode() {
      switchIndexing.incrementAndGet();
    }

    @Override
    public String getCurrentMode() {
      return "indexing";
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.completedFuture("");
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.completedFuture("");
    }

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }
  }

  private record Fixture(
      RecordingOnlineAi onlineAi,
      RuntimeSpecStore spec,
      AtomicInteger nudged,
      BrainRuntimeServiceImpl svc) {}

  private Fixture fixture(boolean initialChatEnabled) {
    UiSettingsStore store =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    RuntimeSpecStore spec = new RuntimeSpecStore(store);
    spec.setChatEnabled(initialChatEnabled);
    // Unstarted reconciler: specChanged() is synchronous (bump version, reset flap, notify
    // spec-change listeners) and does not touch the null control.
    RuntimeReconciler reconciler =
        new RuntimeReconciler(null, () -> Mode.OFFLINE, () -> false, null, null, spec, new RuntimeGpuLease());
    AtomicInteger nudged = new AtomicInteger();
    reconciler.addSpecChangeListener(nudged::incrementAndGet);
    RecordingOnlineAi onlineAi = new RecordingOnlineAi();
    BrainRuntimeServiceImpl svc =
        new BrainRuntimeServiceImpl(onlineAi, store, null, null, spec, reconciler);
    return new Fixture(onlineAi, spec, nudged, svc);
  }

  /**
   * Tempdoc 804 §B6 re-pin: the live mode stays "indexing" here (the reconciler is not running), and
   * that is exactly the round-10 shape that used to be reported as an unqualified success. The
   * outcome must now SAY the requested mode has only been recorded, not reached.
   */
  @Test
  void switchOnline_writesSpecTrue_nudges_noRawSwitch() throws Exception {
    Fixture f = fixture(false);

    ModeTransitionOutcome outcome = f.svc().switchInferenceMode("online");

    assertEquals("online", outcome.requested());
    assertEquals(
        "indexing", outcome.mode(), "returns the live getCurrentMode() (may still be transitioning)");
    assertEquals(
        ModeTransitionOutcome.STATE_RECORDED,
        outcome.state(),
        "live mode != requested mode, so the transition is recorded — not converged");
    assertTrue(f.spec().load().chatEnabled(), "intent recorded: chatEnabled=true");
    assertEquals(1, f.nudged().get(), "reconciler nudged via specChanged()");
    assertEquals(0, f.onlineAi().switchOnline.get(), "no raw switchToOnlineMode");
    assertEquals(0, f.onlineAi().switchIndexing.get(), "no raw switchToIndexingMode");
  }

  /** The converged case: the live mode already equals what was requested. */
  @Test
  void switchIndexing_writesSpecFalse_nudges_noRawSwitch() throws Exception {
    Fixture f = fixture(true);

    ModeTransitionOutcome outcome = f.svc().switchInferenceMode("indexing");

    assertEquals("indexing", outcome.requested());
    assertEquals("indexing", outcome.mode());
    assertEquals(
        ModeTransitionOutcome.STATE_CONVERGED,
        outcome.state(),
        "live mode == requested mode, so the transition is already converged");
    assertFalse(f.spec().load().chatEnabled(), "intent recorded: chatEnabled=false");
    assertEquals(1, f.nudged().get(), "reconciler nudged via specChanged()");
    assertEquals(0, f.onlineAi().switchOnline.get(), "no raw switchToOnlineMode");
    assertEquals(0, f.onlineAi().switchIndexing.get(), "no raw switchToIndexingMode");
  }

  @Test
  void switchInvalidMode_throwsIllegalArgument() {
    Fixture f = fixture(false);
    assertThrows(IllegalArgumentException.class, () -> f.svc().switchInferenceMode("bogus"));
    assertEquals(0, f.nudged().get(), "invalid mode records no intent");
    assertEquals(0, f.onlineAi().switchOnline.get());
    assertEquals(0, f.onlineAi().switchIndexing.get());
  }
}
