package io.justsearch.app.services.vdu;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;

/**
 * Stub inference façade for unit tests in this package.
 *
 * <p>Tempdoc 518 Appendix G W4.2: rewritten to implement two role-typed interfaces directly
 * rather than extend the concrete {@code InferenceLifecycleManager}. The previous inheritance
 * pulled in real ILM internals (transition runner, generation-counter registration with the
 * static {@code InferenceGenerationContext} slot) for every test that constructed a stub —
 * global state pollution.
 *
 * <p>Why not {@code OnlineAiService}: the two interfaces have an irreconcilable signature
 * conflict on {@code getCurrentMode()} ({@code Mode} vs {@code String} return type). No
 * test in this package exercises the completion paths through the stub — callers that need
 * those methods inject a Mockito mock or a separate fake. Keeping the stub narrow to
 * lifecycle + introspection is the cleaner shape; widening it forces a structural
 * compromise on the interface signatures with no consumer demand.
 *
 * <p>Class name preserved for diff stability across the migration; future renames are
 * cosmetic.
 */
public class StubInferenceLifecycleManager
    implements OnlineAiRuntimeIntrospection, OnlineAiLifecycleControl {

  private Mode currentMode = Mode.OFFLINE;
  private boolean failOnlineTransition = false;
  private boolean failIndexingTransition = false;
  private int onlineSwitchCount = 0;
  private int indexingSwitchCount = 0;
  private boolean visionCapable = false;

  // ========== Test-control accessors (custom; not on any role interface) ==========

  /** Current FSM mode tracker for test orchestration. */
  public Mode getCurrentMode() {
    return currentMode;
  }

  /** Convenience accessor matching the old ILM surface used by TestableOfflineCoordinator. */
  public boolean isOffline() {
    return currentMode == Mode.OFFLINE;
  }

  /** Reset to OFFLINE — same shape as ILM.close() for diff stability. */
  public void close() {
    currentMode = Mode.OFFLINE;
  }

  // ========== OnlineAiLifecycleControl ==========

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
    onlineSwitchCount++;
    if (failOnlineTransition) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.ONLINE_START_FAILED,
          "Simulated failure switching to Online Mode");
    }
    currentMode = Mode.ONLINE;
  }

  @Override
  public void switchToIndexingMode() throws ModeTransitionException {
    indexingSwitchCount++;
    if (failIndexingTransition) {
      throw new ModeTransitionException(
          ModeTransitionException.Reason.INDEXING_START_FAILED,
          "Simulated failure switching to Indexing Mode");
    }
    currentMode = Mode.INDEXING;
  }

  @Override
  public void enterVduMode() throws ModeTransitionException {
    // Test stub: no real server work; just record by leaving mode unchanged.
  }

  @Override
  public void exitVduMode() throws ModeTransitionException {
    // Test stub: no real server work.
  }

  @Override
  public void addModeChangeListener(ModeChangeListener listener) {
    // Test stub: ignores listeners; tests assert on switch counters instead.
  }

  @Override
  public void removeModeChangeListener(ModeChangeListener listener) {
    // Test stub: no-op.
  }

  // ========== OnlineAiRuntimeIntrospection ==========

  @Override
  public RuntimeInfo runtimeInfo() {
    return null;
  }

  @Override
  public boolean hasVisionCapability() {
    return visionCapable;
  }

  // ========== Test Control Methods ==========

  public StubInferenceLifecycleManager withMode(Mode mode) {
    this.currentMode = mode;
    return this;
  }

  public StubInferenceLifecycleManager withFailOnlineTransition(boolean fail) {
    this.failOnlineTransition = fail;
    return this;
  }

  public StubInferenceLifecycleManager withFailIndexingTransition(boolean fail) {
    this.failIndexingTransition = fail;
    return this;
  }

  public StubInferenceLifecycleManager withVisionCapable(boolean capable) {
    this.visionCapable = capable;
    return this;
  }

  public int getOnlineSwitchCount() {
    return onlineSwitchCount;
  }

  public int getIndexingSwitchCount() {
    return indexingSwitchCount;
  }

  public void reset() {
    currentMode = Mode.OFFLINE;
    failOnlineTransition = false;
    failIndexingTransition = false;
    visionCapable = false;
    onlineSwitchCount = 0;
    indexingSwitchCount = 0;
  }
}
