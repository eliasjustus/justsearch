package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.memory.MemoryRecord;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 561 P-E — the learned-memory canonical record: one durable authority with the
 * "what it knows" (inspect) + "forget this" (user control) projections, surviving restart.
 */
final class FileMemoryStoreTest {

  private static MemoryRecord rec(String id, String content, Instant at) {
    return new MemoryRecord(id, "fact", content, "conv-1", "primary", at);
  }

  @Test
  @DisplayName("remember -> whatItKnows (newest first) -> forget -> clear, single authority")
  void rememberInspectForget(@TempDir Path tmp) {
    var store = new FileMemoryStore(tmp);
    store.remember(rec("m1", "user prefers dark mode", Instant.parse("2026-01-01T00:00:01Z")));
    store.remember(rec("m2", "the budget doc lives in /finance", Instant.parse("2026-01-01T00:00:05Z")));

    // Inspect: what it knows, newest first.
    List<MemoryRecord> known = store.whatItKnows();
    assertEquals(2, known.size());
    assertEquals("m2", known.get(0).id(), "newest first");
    assertEquals("user prefers dark mode", known.get(1).content());

    // User control: forget one.
    store.forget("m1");
    assertEquals(1, store.whatItKnows().size());
    assertEquals("m2", store.whatItKnows().get(0).id());

    // Re-remember same id replaces (idempotent on id, single authority).
    store.remember(rec("m2", "the budget doc moved to /q2", Instant.parse("2026-01-01T00:00:09Z")));
    assertEquals(1, store.whatItKnows().size());
    assertEquals("the budget doc moved to /q2", store.whatItKnows().get(0).content());

    store.clear();
    assertTrue(store.whatItKnows().isEmpty());
  }

  @Test
  @DisplayName("memory is durable — a fresh store over the same dir reloads what it knows")
  void durableAcrossRestart(@TempDir Path tmp) {
    new FileMemoryStore(tmp).remember(rec("m9", "remembered fact", Instant.parse("2026-02-02T00:00:00Z")));
    // A fresh store instance (simulating a restart) sees the persisted memory.
    var reopened = new FileMemoryStore(tmp);
    assertEquals(1, reopened.whatItKnows().size());
    assertEquals("remembered fact", reopened.whatItKnows().get(0).content());
  }

  /** A test DataKeyState with a fixed key whose lock state the test toggles. */
  private static final class FakeKey implements io.justsearch.agent.api.encryption.DataKeyState {
    private final byte[] dek = new byte[32]; // fixed all-zero key — fine for a round-trip test
    private boolean locked;

    @Override
    public boolean enabled() {
      return true;
    }

    @Override
    public boolean locked() {
      return locked;
    }

    @Override
    public byte[] dek() {
      if (locked) throw new io.justsearch.agent.api.encryption.KeyLockedException();
      return dek;
    }
  }

  @Test
  @DisplayName("629 LAYER — sealed memory is ciphertext on disk; locked-at-launch reloads on unlock")
  void encryptedReloadsOnUnlock(@TempDir Path tmp) throws Exception {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);

    // Write a memory while unlocked → memory.json is sealed (not the plaintext content).
    new FileMemoryStore(tmp, cipher)
        .remember(rec("s1", "SECRET-memory-content", Instant.parse("2026-03-03T00:00:00Z")));
    String onDisk = java.nio.file.Files.readString(tmp.resolve("memory.json"));
    assertTrue(onDisk.startsWith("JSEv1:"), "memory.json is sealed");
    assertTrue(!onDisk.contains("SECRET-memory-content"), "plaintext content is NOT on disk");

    // Launch LOCKED: the eager constructor load is skipped → empty (NOT a false-empty that could overwrite).
    key.locked = true;
    var store = new FileMemoryStore(tmp, cipher);
    assertTrue(store.whatItKnows().isEmpty(), "locked-at-launch shows empty");

    // Unlock → reload restores the memory (the §L4 "locked must not look deleted" fix for the eager cache).
    key.locked = false;
    store.onKeyUnlocked();
    assertEquals(1, store.whatItKnows().size());
    assertEquals("SECRET-memory-content", store.whatItKnows().get(0).content());

    // Lock → drop plaintext from RAM (reads go empty until the next unlock).
    key.locked = true;
    store.onKeyLocked();
    assertTrue(store.whatItKnows().isEmpty(), "lock clears the in-memory cache");
  }

  @Test
  @DisplayName("629 LAYER — a write while locked is refused and never overwrites the ciphertext")
  void writeWhileLockedRefusedNoOverwrite(@TempDir Path tmp) throws Exception {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    new FileMemoryStore(tmp, cipher)
        .remember(rec("s1", "original", Instant.parse("2026-03-03T00:00:00Z")));
    String before = java.nio.file.Files.readString(tmp.resolve("memory.json"));

    key.locked = true;
    var store = new FileMemoryStore(tmp, cipher); // locked-at-launch → empty cache
    org.junit.jupiter.api.Assertions.assertThrows(
        io.justsearch.agent.api.encryption.KeyLockedException.class,
        () -> store.remember(rec("s2", "should-not-persist", Instant.parse("2026-03-03T00:01:00Z"))),
        "persist while locked is refused");
    assertEquals(before, java.nio.file.Files.readString(tmp.resolve("memory.json")), "ciphertext intact");
  }
}
