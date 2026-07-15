/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.Mode;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase-1 tests for the single-writer {@link RuntimeReconciler} (tempdoc 737 item 7 a–e). */
final class RuntimeReconcilerTest {

  @TempDir Path tmp;

  private RuntimeSpecStore specStore(boolean chatEnabled) {
    UiSettingsStore store =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json"));
    RuntimeSpecStore spec = new RuntimeSpecStore(store);
    spec.setChatEnabled(chatEnabled);
    return spec;
  }

  private RuntimeReconciler reconciler(
      RecordingLifecycleControl control, RuntimeSpecStore spec, EnterprisePolicyService policy) {
    return new RuntimeReconciler(
        control,
        control::currentModeValue,
        control::externalValue,
        control::detach,
        policy,
        spec,
        new RuntimeGpuLease());
  }

  private static EnterprisePolicyService policyOnlineEnabled(boolean enabled) {
    EffectivePolicy p =
        new EffectivePolicy(
            true, enabled, true, false, List.of(), List.of(), "test", false, null, null, false);
    return () -> p;
  }

  // (a) spec-on + engine down → exactly one switchToOnlineMode.
  @Test
  void specOnEngineDown_switchesOnlineExactlyOnce() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS), "expected an online switch");
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.onlineSwitchCount.get(), "exactly one online switch");
    assertEquals(0, control.indexingSwitchCount.get());
    r.close();
  }

  // (b) convergence never runs on a caller/listener thread — it runs on the reconciler thread.
  @Test
  void convergenceRunsOnReconcilerThreadNotCaller() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS));
    assertNotNull(control.lastSwitchThread);
    assertNotEquals(Thread.currentThread(), control.lastSwitchThread, "must not run on the caller thread");
    assertEquals("runtime-reconciler", control.lastSwitchThread.getName());
    r.close();
  }

  // (c) a foreign mode change (e.g. VDU parking to INDEXING) updates status but does NOT re-converge.
  @Test
  void foreignModeChangeDoesNotReTriggerConvergence() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), null);
    r.start();
    r.requestBootConvergence();
    assertTrue(control.firstOnlineSwitch.await(5, TimeUnit.SECONDS));
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.onlineSwitchCount.get());

    // Another actor parks the engine into INDEXING. Phase-1: no convergence back to ONLINE.
    control.fireModeChange(Mode.ONLINE, Mode.INDEXING);
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.onlineSwitchCount.get(), "no re-convergence back to online in phase 1");
    r.close();
  }

  // (d) admin policy off is a hard ceiling that wins over spec-on.
  @Test
  void policyOffWinsOverSpecOn() throws Exception {
    RecordingLifecycleControl control = new RecordingLifecycleControl().withMode(Mode.OFFLINE);
    RuntimeReconciler r = reconciler(control, specStore(true), policyOnlineEnabled(false));
    r.start();
    r.requestBootConvergence();
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(0, control.onlineSwitchCount.get(), "policy off must block the online switch");
    r.close();
  }

  // (e) spec-write to chatEnabled=false while engine is up → stop primitive (switchToIndexingMode).
  @Test
  void specDisableWhileOnline_callsStopPrimitive() throws Exception {
    RecordingLifecycleControl control =
        new RecordingLifecycleControl().withMode(Mode.ONLINE).withExternal(false);
    RuntimeSpecStore spec = specStore(true);
    RuntimeReconciler r = reconciler(control, spec, null);
    r.start();

    // User turns chat off.
    spec.setChatEnabled(false);
    r.specChanged();

    assertTrue(control.firstIndexingSwitch.await(5, TimeUnit.SECONDS), "expected a stop (indexing) switch");
    assertTrue(r.awaitQuiescent(5_000));
    assertEquals(1, control.indexingSwitchCount.get());
    assertEquals(0, control.onlineSwitchCount.get());
    r.close();
  }
}
