package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.nio.file.Files;
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

  @Test
  @DisplayName("legacy v0 array remains readable and the next write emits v1")
  void legacyV0ArrayLoads(@TempDir Path tmp) throws Exception {
    Files.writeString(
        tmp.resolve("memory.json"),
        """
        [{"id":"m0","kind":"fact","content":"legacy","sourceConversationId":"c",
          "actor":"primary","createdAt":"2026-01-01T00:00:00Z"}]
        """);
    FileMemoryStore store = new FileMemoryStore(tmp);
    assertEquals("legacy", store.whatItKnows().get(0).content());
    store.remember(rec("m1", "new", Instant.parse("2026-01-02T00:00:00Z")));
    assertTrue(Files.readString(tmp.resolve("memory.json")).contains("\"schemaVersion\" : 1"));
  }

  @Test
  void futureVersionIsRefusedWithoutOverwrite(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("memory.json");
    String future = "{\"schemaVersion\":99,\"memories\":[]}";
    Files.writeString(file, future);
    assertThrows(UnsupportedStoreVersionException.class, () -> new FileMemoryStore(tmp));
    assertEquals(future, Files.readString(file));
  }

  @Test
  void malformedStateIsRefusedWithoutOverwrite(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("memory.json");
    String malformed = "{not-json";
    Files.writeString(file, malformed);
    assertThrows(CorruptDurableStoreException.class, () -> new FileMemoryStore(tmp));
    assertEquals(malformed, Files.readString(file));
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
    String onDisk = Files.readString(tmp.resolve("memory.json"));
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
    String before = Files.readString(tmp.resolve("memory.json"));

    key.locked = true;
    var store = new FileMemoryStore(tmp, cipher); // locked-at-launch → empty cache
    assertThrows(
        io.justsearch.agent.api.encryption.KeyLockedException.class,
        () -> store.remember(rec("s2", "should-not-persist", Instant.parse("2026-03-03T00:01:00Z"))),
        "persist while locked is refused");
    assertEquals(before, Files.readString(tmp.resolve("memory.json")), "ciphertext intact");
  }

  // ── Tempdoc 806 W1 — locked-state truthfulness (round-12 finding R12-F3) ──────────────────────
  //
  // While locked the cache is EMPTY and the file is unreadable, so the store knows nothing about what
  // it holds. Every mutation must therefore refuse loudly BEFORE touching the cache: a silent no-op
  // (the id "isn't there", so nothing to persist) reports success for a change that never happened,
  // and a cache mutation that outlives a failed persist serves a record that never reached disk.

  @Test
  @DisplayName("806 W1 — a locked remember leaves NO phantom in the cache")
  void lockedRememberLeavesNoPhantom(@TempDir Path tmp) {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileMemoryStore(tmp, cipher);
    store.remember(rec("m1", "kept", Instant.parse("2026-04-01T00:00:00Z")));

    key.locked = true;
    store.onKeyLocked();
    assertThrows(
        io.justsearch.agent.api.encryption.KeyLockedException.class,
        () -> store.remember(rec("ghost", "never persisted", Instant.parse("2026-04-01T00:01:00Z"))));

    // THE phantom assertion, made WHILE STILL LOCKED — this is the exact read GET /api/memory serves.
    // Pre-806 it returned the ghost with HTTP 200: byId.put ran, persist() threw, nothing rolled it
    // back. (Checking after unlock would prove nothing: onKeyUnlocked() re-reads the file anyway.)
    assertTrue(
        store.whatItKnows().isEmpty(),
        "a write that never reached disk must not be readable — no phantom in the cache");

    key.locked = false;
    store.onKeyUnlocked();
    assertEquals(1, store.whatItKnows().size(), "the refused write left no trace");
    assertEquals("m1", store.whatItKnows().get(0).id());
  }

  @Test
  @DisplayName("806 W1 — a locked forget/clear refuses instead of reporting a change it cannot make")
  void lockedForgetAndClearRefuse(@TempDir Path tmp) {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileMemoryStore(tmp, cipher);
    store.remember(rec("m1", "on disk", Instant.parse("2026-04-02T00:00:00Z")));

    key.locked = true;
    store.onKeyLocked();
    assertTrue(store.isLocked(), "the store reports that it cannot be read");

    // The id IS on disk but NOT in the emptied cache — pre-806 this returned silently (200 ok).
    assertThrows(
        io.justsearch.agent.api.encryption.KeyLockedException.class, () -> store.forget("m1"));
    // An id that exists nowhere refuses too: while locked the store cannot tell the two apart.
    assertThrows(
        io.justsearch.agent.api.encryption.KeyLockedException.class, () -> store.forget("no-such-id"));
    // "Forget everything" on an empty-because-locked cache was the same silent no-op.
    assertThrows(io.justsearch.agent.api.encryption.KeyLockedException.class, store::clear);
  }

  @Test
  @DisplayName(
      "806 W1 headline — forget while locked never silently succeeds (the fact would return on unlock)")
  void forgetWhileLockedNeverLooksDone(@TempDir Path tmp) {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileMemoryStore(tmp, cipher);
    store.remember(rec("secret", "the fact the user wants gone", Instant.parse("2026-04-03T00:00:00Z")));

    // Every backend restart locks the key: the user opens Memory and clicks Forget in this state.
    key.locked = true;
    store.onKeyLocked();
    assertThrows(
        io.justsearch.agent.api.encryption.KeyLockedException.class,
        () -> store.forget("secret"),
        "a forget the store cannot perform must FAIL — pre-806 it returned quietly and the surface "
            + "rendered the fact as gone");

    // The disk was never touched, so the fact is still there on unlock. That is legitimate — the point
    // is that the user was told, instead of watching it vanish and silently come back.
    key.locked = false;
    store.onKeyUnlocked();
    assertEquals(1, store.whatItKnows().size());
    assertEquals("the fact the user wants gone", store.whatItKnows().get(0).content());
    assertTrue(!store.isLocked(), "unlocked stores report readable");

    // And the control works once the key is available.
    store.forget("secret");
    assertTrue(store.whatItKnows().isEmpty());
    var reopened = new FileMemoryStore(tmp, cipher);
    assertTrue(reopened.whatItKnows().isEmpty(), "the forget reached disk");
  }
}
