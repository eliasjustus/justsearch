/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 737 §12c item 2 (Phase 2a) — spec-aware rekey of {@link InferenceCapabilityWiring}.
 * {@code InferenceLifecycleManager} is a concrete, non-final class with non-final
 * {@code getCurrentMode()} / {@code addModeChangeListener}, so Mockito mocking it directly (rather
 * than a hand-written stub) is the lightest correct test double — no real llama-server process is
 * ever spun up.
 */
final class InferenceCapabilityWiringTest {

  @TempDir Path tmp;

  private RuntimeSpecStore specStore(boolean chatEnabled) {
    UiSettingsStore store =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    RuntimeSpecStore spec = new RuntimeSpecStore(store);
    spec.setChatEnabled(chatEnabled);
    return spec;
  }

  @Test
  void engineOnlineSpecOn_yieldsReady() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.READY, cap.health());
  }

  @Test
  void engineOnlineSpecOff_yieldsDegradedWithBackgroundReason() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(false), null);

    assertEquals(CapabilityHealth.DEGRADED, cap.health());
    assertEquals(RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND, cap.pendingReason());
  }

  @Test
  void offlineUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.OFFLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.OFFLINE, cap.health());
    assertEquals("Inference offline", cap.pendingReason());
  }

  @Test
  void indexingUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.INDEXING);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.DEGRADED, cap.health());
    assertEquals("GPU allocated to indexing", cap.pendingReason());
  }

  @Test
  void transitioningUnchanged() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.TRANSITIONING);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);

    assertEquals(CapabilityHealth.RECOVERING, cap.health());
    assertEquals("Inference transitioning", cap.pendingReason());
  }

  @Test
  void modeChangeListenerReDerivesOnTransition() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.OFFLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);
    assertEquals(CapabilityHealth.OFFLINE, cap.health(), "initial mirror");

    ArgumentCaptor<ModeChangeListener> captor = ArgumentCaptor.forClass(ModeChangeListener.class);
    verify(manager).addModeChangeListener(captor.capture());

    // Simulate the engine coming online: the callback's `to` argument drives re-derivation.
    captor.getValue().onModeChange(Mode.OFFLINE, Mode.ONLINE);

    assertEquals(CapabilityHealth.READY, cap.health(), "mode-change listener re-derived");
  }

  /**
   * The "listener contract" case the mode-change listener alone MISSES: {@code chatEnabled} flips
   * while the observed engine mode never changes (e.g. a VDU procedure holds the engine ONLINE
   * under soft-off). Proves {@link RuntimeReconciler#addSpecChangeListener} is the mechanism that
   * catches it.
   */
  @Test
  void specChangeWhileOnlineReDerivesViaReconciler() throws Exception {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);
    RuntimeSpecStore spec = specStore(false);

    FakeControl control = new FakeControl(Mode.ONLINE);
    RuntimeReconciler reconciler =
        new RuntimeReconciler(
            control, control::mode, control::external, control::detach, null, spec, new RuntimeGpuLease());
    reconciler.start();

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, spec, reconciler);
    assertEquals(CapabilityHealth.DEGRADED, cap.health(), "spec off, engine already online");
    assertEquals(RuntimeStatus.REASON_ENGINE_UP_FOR_BACKGROUND, cap.pendingReason());

    // Flip the spec WITHOUT any observed mode change — manager.getCurrentMode() still reports
    // ONLINE throughout, so no ModeChangeListener fires; only the spec-change listener can catch this.
    spec.setChatEnabled(true);
    reconciler.specChanged();

    assertEquals(CapabilityHealth.READY, cap.health(), "spec-change listener re-derived");

    reconciler.close();
  }

  @Test
  void nullReconcilerSkipsSpecChangeWiringButModeListenerStillWorks() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    // Must not throw with a null reconciler.
    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, specStore(true), null);
    assertEquals(CapabilityHealth.READY, cap.health());
  }

  @Test
  void nullSpecStoreResolvesToChatDisabled() {
    InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    InferenceCapability cap = new InferenceCapability(true);

    InferenceCapabilityWiring.attachInferenceModeListener(manager, cap, null, null);

    assertEquals(CapabilityHealth.DEGRADED, cap.health(), "unattached authority never reports READY");
  }

  /** Minimal {@link OnlineAiLifecycleControl} double for the reconciler-based test above. */
  private static final class FakeControl implements OnlineAiLifecycleControl {
    private volatile Mode currentMode;

    FakeControl(Mode initial) {
      this.currentMode = initial;
    }

    Mode mode() {
      return currentMode;
    }

    boolean external() {
      return false;
    }

    void detach() {}

    @Override
    public boolean isOnline() {
      return currentMode == Mode.ONLINE;
    }

    @Override
    public boolean isIndexing() {
      return currentMode == Mode.INDEXING;
    }

    @Override
    public void switchToOnlineMode() throws ModeTransitionException {
      currentMode = Mode.ONLINE;
    }

    @Override
    public void switchToIndexingMode() throws ModeTransitionException {
      currentMode = Mode.INDEXING;
    }

    @Override
    public void enterVduMode() throws ModeTransitionException {}

    @Override
    public void exitVduMode() throws ModeTransitionException {}

    @Override
    public void addModeChangeListener(ModeChangeListener listener) {}

    @Override
    public void removeModeChangeListener(ModeChangeListener listener) {}
  }
}
