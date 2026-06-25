package io.justsearch.app.services.encryption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.encryption.KeyLockedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tempdoc 629 (LAYER) — DataKeyManager: state machine, persistence (lock-on-launch), recovery. */
final class DataKeyManagerTest {

  @TempDir Path dataDir;

  private DataKeyManager fresh() {
    return new DataKeyManager(new EncryptionKeystore(dataDir));
  }

  @Test
  void fullLifecycle() {
    DataKeyManager m = fresh();
    assertEquals(DataKeyManager.State.NOT_CONFIGURED, m.state());
    assertEquals(false, m.enabled());

    String recovery = m.setup("passphrase".toCharArray());
    assertEquals(DataKeyManager.State.UNLOCKED, m.state());
    assertTrue(m.enabled());
    byte[] dek = m.dek().clone();
    assertEquals(32, dek.length);

    m.lock();
    assertEquals(DataKeyManager.State.LOCKED, m.state());
    assertTrue(m.locked());
    assertThrows(KeyLockedException.class, m::dek);

    m.unlock("passphrase".toCharArray());
    assertEquals(DataKeyManager.State.UNLOCKED, m.state());
    assertArrayEquals(dek, m.dek());

    // Persistence: a new manager over the same dataDir starts LOCKED (lock-on-launch).
    DataKeyManager restarted = fresh();
    assertEquals(DataKeyManager.State.LOCKED, restarted.state());
    restarted.recover(recovery);
    assertArrayEquals(dek, restarted.dek(), "recovery key unlocks the same DEK after restart");
  }

  @Test
  void wrongPassphraseLeavesLocked() {
    DataKeyManager m = fresh();
    m.setup("right".toCharArray());
    m.lock();
    assertThrows(WrongPassphraseException.class, () -> m.unlock("wrong".toCharArray()));
    assertEquals(DataKeyManager.State.LOCKED, m.state(), "a failed unlock stays locked");
  }

  @Test
  void listenerFiresOnEveryTransition() {
    DataKeyManager m = fresh();
    List<String> transitions = new ArrayList<>();
    m.addListener((from, to) -> transitions.add(from + "->" + to));

    m.setup("pw".toCharArray()); // NOT_CONFIGURED -> UNLOCKED
    m.lock(); // UNLOCKED -> LOCKED
    m.unlock("pw".toCharArray()); // LOCKED -> UNLOCKED
    m.lock();
    m.lock(); // no-op (already LOCKED) — must NOT fire

    assertEquals(
        List.of(
            "NOT_CONFIGURED->UNLOCKED",
            "UNLOCKED->LOCKED",
            "LOCKED->UNLOCKED",
            "UNLOCKED->LOCKED"),
        transitions,
        "listener fires once per real transition, never on a same-state no-op");
  }

  @Test
  void faultyListenerDoesNotBreakLifecycle() {
    DataKeyManager m = fresh();
    m.addListener(
        (from, to) -> {
          throw new RuntimeException("listener boom");
        });
    m.setup("pw".toCharArray()); // must not propagate the listener fault
    assertEquals(DataKeyManager.State.UNLOCKED, m.state());
  }

  @Test
  void changePassphraseKeepsUnlocked() {
    DataKeyManager m = fresh();
    m.setup("old".toCharArray());
    byte[] dek = m.dek().clone();
    m.changePassphrase("old".toCharArray(), "new".toCharArray());
    assertEquals(DataKeyManager.State.UNLOCKED, m.state());
    assertArrayEquals(dek, m.dek());
    // restart + unlock with the new passphrase
    DataKeyManager restarted = fresh();
    restarted.unlock("new".toCharArray());
    assertArrayEquals(dek, restarted.dek());
  }

  @Test
  void regenerateRecoveryReplacesTheOldKey() {
    DataKeyManager m = fresh();
    String original = m.setup("pw".toCharArray());
    byte[] dek = m.dek().clone();

    String regenerated = m.regenerateRecovery();
    assertEquals(DataKeyManager.State.UNLOCKED, m.state(), "still unlocked after regenerate");
    org.junit.jupiter.api.Assertions.assertNotEquals(original, regenerated, "a new key is issued");
    assertArrayEquals(dek, m.dek(), "the DEK (and passphrase wrap) is unchanged");

    // The NEW recovery key works on a fresh restart; the OLD one no longer does.
    DataKeyManager r1 = fresh();
    r1.recover(regenerated);
    assertArrayEquals(dek, r1.dek());
    DataKeyManager r2 = fresh();
    assertThrows(WrongPassphraseException.class, () -> r2.recover(original));

    // Regenerate requires UNLOCKED.
    DataKeyManager locked = fresh();
    assertThrows(KeyLockedException.class, locked::regenerateRecovery);
  }
}
