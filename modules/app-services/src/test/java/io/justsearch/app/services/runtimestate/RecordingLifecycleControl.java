/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.runtimestate;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Recording {@link OnlineAiLifecycleControl} for {@code RuntimeReconciler} unit tests. Unlike the
 * VDU-package {@code StubInferenceLifecycleManager}, this one stores the attached listener (so a
 * test can fire a mode change) and records the THREAD each switch runs on (so a test can assert
 * convergence never runs on a caller/listener thread).
 */
final class RecordingLifecycleControl implements OnlineAiLifecycleControl {

  private volatile Mode currentMode = Mode.OFFLINE;
  private volatile boolean failOnline = false;
  private volatile boolean external = false;

  final AtomicInteger onlineSwitchCount = new AtomicInteger();
  final AtomicInteger indexingSwitchCount = new AtomicInteger();
  final AtomicInteger detachCount = new AtomicInteger();
  volatile Thread lastSwitchThread;
  final CountDownLatch firstOnlineSwitch = new CountDownLatch(1);
  final CountDownLatch firstIndexingSwitch = new CountDownLatch(1);

  private volatile ModeChangeListener listener;

  RecordingLifecycleControl withMode(Mode mode) {
    this.currentMode = mode;
    return this;
  }

  RecordingLifecycleControl withFailOnline(boolean fail) {
    this.failOnline = fail;
    return this;
  }

  RecordingLifecycleControl withExternal(boolean ext) {
    this.external = ext;
    return this;
  }

  Mode currentModeValue() {
    return currentMode;
  }

  boolean externalValue() {
    return external;
  }

  void detach() throws ModeTransitionException {
    detachCount.incrementAndGet();
    external = false;
    currentMode = Mode.INDEXING;
    fire(Mode.ONLINE, Mode.INDEXING);
  }

  /** Fire a mode change through the stored listener (simulates another actor / the FSM commit). */
  void fireModeChange(Mode from, Mode to) {
    currentMode = to;
    fire(from, to);
  }

  private void fire(Mode from, Mode to) {
    ModeChangeListener l = listener;
    if (l != null) {
      l.onModeChange(from, to);
    }
  }

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
    lastSwitchThread = Thread.currentThread();
    onlineSwitchCount.incrementAndGet();
    if (failOnline) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.ONLINE_START_FAILED, "Simulated online failure");
    }
    Mode from = currentMode;
    currentMode = Mode.ONLINE;
    fire(from, Mode.ONLINE);
    firstOnlineSwitch.countDown();
  }

  @Override
  public void switchToIndexingMode() throws ModeTransitionException {
    lastSwitchThread = Thread.currentThread();
    indexingSwitchCount.incrementAndGet();
    Mode from = currentMode;
    currentMode = Mode.INDEXING;
    fire(from, Mode.INDEXING);
    firstIndexingSwitch.countDown();
  }

  @Override
  public void enterVduMode() throws ModeTransitionException {}

  @Override
  public void exitVduMode() throws ModeTransitionException {}

  @Override
  public void addModeChangeListener(ModeChangeListener l) {
    this.listener = l;
  }

  @Override
  public void removeModeChangeListener(ModeChangeListener l) {
    if (this.listener == l) {
      this.listener = null;
    }
  }
}
