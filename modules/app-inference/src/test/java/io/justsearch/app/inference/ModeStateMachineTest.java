package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.Mode;
import org.junit.jupiter.api.Test;

class ModeStateMachineTest {

  // ==================== initial state ====================

  @Test
  void initialState_isOffline() {
    ModeStateMachine sm = new ModeStateMachine();
    assertEquals(Mode.OFFLINE, sm.current());
  }

  // ==================== beginTransition ====================

  @Test
  void beginTransition_returnsPreviousModeAndSetsTransitioning() {
    ModeStateMachine sm = new ModeStateMachine();
    Mode prev = sm.beginTransition();
    assertEquals(Mode.OFFLINE, prev);
    assertEquals(Mode.TRANSITIONING, sm.current());
  }

  @Test
  void beginTransition_throwsWhenAlreadyTransitioning() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();
    assertThrows(IllegalStateException.class, sm::beginTransition);
  }

  // ==================== complete ====================

  @Test
  void complete_setsTargetMode() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();
    sm.complete(Mode.ONLINE);
    assertEquals(Mode.ONLINE, sm.current());
  }

  @Test
  void complete_throwsWhenNotTransitioning() {
    ModeStateMachine sm = new ModeStateMachine();
    assertThrows(IllegalStateException.class, () -> sm.complete(Mode.ONLINE));
  }

  @Test
  void complete_rejectsTransitioningAsTarget() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();
    assertThrows(IllegalArgumentException.class, () -> sm.complete(Mode.TRANSITIONING));
    assertEquals(Mode.TRANSITIONING, sm.current(), "State should not be corrupted");
  }

  // ==================== rollback ====================

  @Test
  void rollback_restoresPreviousMode() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition(); // OFFLINE → TRANSITIONING
    Mode restored = sm.rollback();
    assertEquals(Mode.OFFLINE, restored);
    assertEquals(Mode.OFFLINE, sm.current());
  }

  @Test
  void rollback_afterOnlineTransition() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();
    sm.complete(Mode.ONLINE); // now ONLINE

    sm.beginTransition(); // ONLINE → TRANSITIONING
    Mode restored = sm.rollback();
    assertEquals(Mode.ONLINE, restored);
    assertEquals(Mode.ONLINE, sm.current());
  }

  @Test
  void rollback_throwsWhenNotTransitioning() {
    ModeStateMachine sm = new ModeStateMachine();
    assertThrows(IllegalStateException.class, sm::rollback);
  }

  // ==================== forceOffline ====================

  @Test
  void forceOffline_fromOnline() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();
    sm.complete(Mode.ONLINE);

    Mode prev = sm.forceOffline();
    assertEquals(Mode.ONLINE, prev);
    assertEquals(Mode.OFFLINE, sm.current());
  }

  @Test
  void forceOffline_fromTransitioning() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();

    Mode prev = sm.forceOffline();
    assertEquals(Mode.TRANSITIONING, prev);
    assertEquals(Mode.OFFLINE, sm.current());
  }

  @Test
  void forceOffline_fromOffline() {
    ModeStateMachine sm = new ModeStateMachine();
    Mode prev = sm.forceOffline();
    assertEquals(Mode.OFFLINE, prev);
    assertEquals(Mode.OFFLINE, sm.current());
  }

  // ==================== full lifecycle ====================

  @Test
  void fullLifecycle_offlineToOnlineToIndexingToOffline() {
    ModeStateMachine sm = new ModeStateMachine();

    // OFFLINE → ONLINE
    assertEquals(Mode.OFFLINE, sm.beginTransition());
    sm.complete(Mode.ONLINE);
    assertEquals(Mode.ONLINE, sm.current());

    // ONLINE → INDEXING
    assertEquals(Mode.ONLINE, sm.beginTransition());
    sm.complete(Mode.INDEXING);
    assertEquals(Mode.INDEXING, sm.current());

    // INDEXING → OFFLINE (force)
    assertEquals(Mode.INDEXING, sm.forceOffline());
    assertEquals(Mode.OFFLINE, sm.current());
  }

  @Test
  void beginTransition_allowedAfterForceOffline() {
    ModeStateMachine sm = new ModeStateMachine();
    sm.beginTransition();
    sm.forceOffline(); // clears TRANSITIONING state
    // Should be able to begin a new transition now
    Mode prev = sm.beginTransition();
    assertEquals(Mode.OFFLINE, prev);
  }
}
