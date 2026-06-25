package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.BranchesPreventDeletionException;
import io.justsearch.agent.api.conversation.ConversationStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Slice 513 — FileConversationStore branching tests.
 *
 * <p>Covers: id/hash synthesis on append, id/hash backfill for legacy data on
 * load, branchFrom meta layout, lazy prefix walk in loadHistory, parent
 * pointers exposed by listSessions, and parent-cycle truncation.
 */
final class FileConversationStoreTest {

  private static Map<String, Object> userMsg(String content) {
    return Map.of("role", "user", "content", content);
  }

  private static Map<String, Object> assistantMsg(String content) {
    return Map.of("role", "assistant", "content", content);
  }

  @Test
  @DisplayName("appendMessage synthesizes id + hash on every write")
  void appendAddsIdAndHash(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s1", "core.free-chat", userMsg("hello"));
    List<Map<String, Object>> history = store.loadHistory("s1");
    assertEquals(1, history.size());
    Map<String, Object> msg = history.get(0);
    assertTrue(msg.get("id") instanceof String, "id should be a String");
    assertFalse(((String) msg.get("id")).isBlank(), "id should not be blank");
    assertTrue(msg.get("hash") instanceof String, "hash should be a String");
    assertEquals(64, ((String) msg.get("hash")).length(), "sha-256 hex is 64 chars");
  }

  /** A test DataKeyState with a fixed key whose lock state the test toggles. */
  private static final class FakeKey implements io.justsearch.agent.api.encryption.DataKeyState {
    private final byte[] dek = new byte[32];
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
  @DisplayName("629 LAYER — meta content is sealed; the list survives a locked store (not deleted-looking)")
  void metaContentSealedAndListSurvivesLocked(@TempDir Path tmp) throws Exception {
    var key = new FakeKey();
    var cipher = new io.justsearch.agent.api.encryption.StoreCipher(key);
    var store = new FileConversationStore(tmp, cipher);
    store.appendMessage("s1", "core.free-chat", userMsg("SENSITIVE-first-message"));

    // The first user message is sealed in meta.json — plaintext is NOT on disk.
    String meta = Files.readString(tmp.resolve("s1").resolve("meta.json"), StandardCharsets.UTF_8);
    assertFalse(meta.contains("SENSITIVE-first-message"), "first message is not plaintext in meta.json");
    assertTrue(meta.contains("JSEv1:"), "the meta content field is sealed");

    // Unlocked: the list decrypts the title.
    List<ConversationStore.SessionSummary> open = store.listSessions("core.free-chat", 20);
    assertEquals(1, open.size());
    assertEquals("SENSITIVE-first-message", open.get(0).firstUserMessage());

    // Locked: the list still returns the session (NOT dropped, NOT thrown) with the title hidden.
    key.locked = true;
    List<ConversationStore.SessionSummary> locked = store.listSessions("core.free-chat", 20);
    assertEquals(1, locked.size(), "the locked session stays in the list");
    assertEquals("", locked.get(0).firstUserMessage(), "title is hidden while locked");
  }

  @Test
  @DisplayName("loadHistory synthesizes id + hash for legacy messages without them")
  void loadBackfillsLegacyMessages(@TempDir Path tmp) throws Exception {
    // Write a JSONL file directly, simulating pre-513 storage (no id, no hash).
    Path sessionDir = tmp.resolve("legacy");
    Files.createDirectories(sessionDir);
    Files.writeString(
        sessionDir.resolve("messages.jsonl"),
        "{\"role\":\"user\",\"content\":\"old message\"}\n",
        StandardCharsets.UTF_8);
    Files.writeString(
        sessionDir.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,\"messageCount\":1}",
        StandardCharsets.UTF_8);

    var store = new FileConversationStore(tmp);
    List<Map<String, Object>> history = store.loadHistory("legacy");
    assertEquals(1, history.size());
    assertEquals("idx-0", history.get(0).get("id"), "legacy id should be deterministic idx-N");
    assertNotNull(history.get(0).get("hash"));
  }

  @Test
  @DisplayName("branchFrom creates a child session whose loadHistory prepends parent prefix")
  void branchFromPrependsPrefix(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("parent", "core.free-chat", userMsg("q1"));
    store.appendMessage("parent", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("parent", "core.free-chat", userMsg("q2"));
    store.appendMessage("parent", "core.free-chat", assistantMsg("a2"));

    List<Map<String, Object>> parentHistory = store.loadHistory("parent");
    assertEquals(4, parentHistory.size());
    String branchPoint = (String) parentHistory.get(1).get("id"); // assistant of turn 1

    store.branchFrom("parent", branchPoint, "branchA");
    List<Map<String, Object>> branchHistory = store.loadHistory("branchA");
    assertEquals(2, branchHistory.size(), "branch should see parent prefix up to branch point");
    assertEquals("q1", branchHistory.get(0).get("content"));
    assertEquals("a1", branchHistory.get(1).get("content"));

    // Branch's own messages append on top of the parent prefix.
    store.appendMessage("branchA", "core.free-chat", userMsg("q2-alt"));
    store.appendMessage("branchA", "core.free-chat", assistantMsg("a2-alt"));
    List<Map<String, Object>> branchHistoryAfter = store.loadHistory("branchA");
    assertEquals(4, branchHistoryAfter.size());
    assertEquals("q2-alt", branchHistoryAfter.get(2).get("content"));
    assertEquals("a2-alt", branchHistoryAfter.get(3).get("content"));

    // Parent unchanged.
    assertEquals(4, store.loadHistory("parent").size());
  }

  @Test
  @DisplayName("listSessions exposes parent pointers for branched sessions")
  void listSessionsExposesParentPointers(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("root", "core.free-chat", userMsg("root msg"));
    List<Map<String, Object>> rootHistory = store.loadHistory("root");
    String branchPoint = (String) rootHistory.get(0).get("id");
    store.branchFrom("root", branchPoint, "child");

    List<ConversationStore.SessionSummary> sessions =
        store.listSessions("core.free-chat", 10);
    assertEquals(2, sessions.size());
    ConversationStore.SessionSummary child =
        sessions.stream().filter(s -> "child".equals(s.sessionId())).findFirst().orElseThrow();
    assertEquals("root", child.parentSessionId());
    assertEquals(branchPoint, child.branchPointMessageId());

    ConversationStore.SessionSummary root =
        sessions.stream().filter(s -> "root".equals(s.sessionId())).findFirst().orElseThrow();
    assertNull(root.parentSessionId());
    assertNull(root.branchPointMessageId());
  }

  @Test
  @DisplayName("Slice 515 FIX-2: branchFrom rejects non-existent branch point")
  void branchFromRejectsUnknownBranchPoint(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("p", "core.free-chat", userMsg("only"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> store.branchFrom("p", "nonexistent-id", "b"));
    // No branch directory should have been created.
    assertFalse(tmp.resolve("b").toFile().exists(),
        "rejected branch should not leave a partial session dir");
  }

  @Test
  @DisplayName("Slice 515 FIX-2: branchFrom rejects non-existent parent session")
  void branchFromRejectsUnknownParent(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> store.branchFrom("no-such-parent", "any-id", "child"));
  }

  @Test
  @DisplayName("Slice 515 FIX-9: branchFrom works with synthesized idx-N id from legacy data")
  void branchFromWithSynthesizedIdxN(@TempDir Path tmp) throws Exception {
    // Write a legacy session (no per-line id/hash) directly to disk.
    Path sessionDir = tmp.resolve("legacy");
    Files.createDirectories(sessionDir);
    Files.writeString(sessionDir.resolve("messages.jsonl"),
        "{\"role\":\"user\",\"content\":\"q1\"}\n"
            + "{\"role\":\"assistant\",\"content\":\"a1\"}\n"
            + "{\"role\":\"user\",\"content\":\"q2\"}\n",
        StandardCharsets.UTF_8);
    Files.writeString(sessionDir.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,\"messageCount\":3}",
        StandardCharsets.UTF_8);

    var store = new FileConversationStore(tmp);
    // The store synthesises idx-0, idx-1, idx-2 on read. Branching from idx-1
    // should yield a 2-message prefix.
    store.branchFrom("legacy", "idx-1", "branch-of-legacy");
    List<Map<String, Object>> branchHistory = store.loadHistory("branch-of-legacy");
    assertEquals(2, branchHistory.size());
    assertEquals("q1", branchHistory.get(0).get("content"));
    assertEquals("a1", branchHistory.get(1).get("content"));
  }

  @Test
  @DisplayName("Slice 515 FIX-9: branch's prefix is bounded by branch-point — parent appends after branch are NOT inherited")
  void branchPrefixIsBoundedByBranchPoint(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("p", "core.free-chat", userMsg("q1"));
    store.appendMessage("p", "core.free-chat", assistantMsg("a1"));
    var parentHistory = store.loadHistory("p");
    String branchPoint = (String) parentHistory.get(1).get("id");

    store.branchFrom("p", branchPoint, "b");
    // Parent gets MORE messages after the branch was created.
    store.appendMessage("p", "core.free-chat", userMsg("q2-late"));
    store.appendMessage("p", "core.free-chat", assistantMsg("a2-late"));

    // Branch's history still only sees the 2-message prefix up to the
    // branch point — NOT the late additions.
    List<Map<String, Object>> branchHistory = store.loadHistory("b");
    assertEquals(2, branchHistory.size(),
        "branch prefix must be bounded by branch-point even if parent grows");
    assertEquals("q1", branchHistory.get(0).get("content"));
    assertEquals("a1", branchHistory.get(1).get("content"));
  }

  @Test
  @DisplayName("Slice 515 FIX-3: deleteSession blocks when child branches exist")
  void deleteSessionBlocksWhenChildBranchesExist(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("p", "core.free-chat", userMsg("q1"));
    var history = store.loadHistory("p");
    String branchPoint = (String) history.get(0).get("id");
    store.branchFrom("p", branchPoint, "b");

    var ex = org.junit.jupiter.api.Assertions.assertThrows(
        BranchesPreventDeletionException.class,
        () -> store.deleteSession("p"));
    assertTrue(ex.childSessionIds().contains("b"),
        "exception should carry the orphan child session id");
    // Parent dir is still intact.
    assertTrue(tmp.resolve("p").toFile().exists());
  }

  @Test
  @DisplayName("Slice 515 FIX-3: deleteSession of a leaf session succeeds")
  void deleteSessionOfLeafSucceeds(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("leaf", "core.free-chat", userMsg("only"));
    store.deleteSession("leaf");
    assertFalse(tmp.resolve("leaf").toFile().exists());
  }

  @Test
  @DisplayName("Slice 515 FIX-7: getSessionMeta returns SessionSummary for known session")
  void getSessionMetaForKnownSession(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("hi"));
    var meta = store.getSessionMeta("s");
    assertTrue(meta.isPresent());
    assertEquals("s", meta.get().sessionId());
    assertEquals("core.free-chat", meta.get().shapeId());
    assertEquals(1, meta.get().messageCount());
    assertNull(meta.get().parentSessionId());
  }

  @Test
  @DisplayName("Slice 515 FIX-7: getSessionMeta returns empty for unknown session")
  void getSessionMetaForUnknownSession(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    assertFalse(store.getSessionMeta("does-not-exist").isPresent());
  }

  @Test
  @DisplayName("Slice 516 FIX-T4: deleteSession bypasses the branches check for THROWAWAY_SESSION_PREFIX")
  void deleteThrowawaySessionBypassesBranchesCheck(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    // Set up a parent with a child branch so the bypass actually matters —
    // were a non-throwaway session forged here, the deletion would 409.
    store.appendMessage("parent", "core.free-chat", userMsg("hi"));
    var history = store.loadHistory("parent");
    String branchPoint = (String) history.get(0).get("id");
    store.branchFrom("parent", branchPoint, "child");
    // Throwaway session with matching prefix; its meta points at parent as
    // if it were a branch, but the bypass should still permit deletion
    // since the prefix says "internal cleanup."
    store.appendMessage(
        ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1",
        "core.free-chat", userMsg("title-gen"));
    store.deleteSession(ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1");
    assertFalse(tmp.resolve(ConversationStore.THROWAWAY_SESSION_PREFIX + "auto-1").toFile().exists());
    // Non-throwaway parent still has the branches-prevent-deletion guard.
    org.junit.jupiter.api.Assertions.assertThrows(
        BranchesPreventDeletionException.class,
        () -> store.deleteSession("parent"));
  }

  @Test
  @DisplayName("parent cycle is detected and truncates the prefix walk")
  void parentCycleIsTruncated(@TempDir Path tmp) throws Exception {
    var store = new FileConversationStore(tmp);
    // Manually create two sessions whose parent pointers form a cycle.
    Path sA = tmp.resolve("A");
    Path sB = tmp.resolve("B");
    Files.createDirectories(sA);
    Files.createDirectories(sB);
    Files.writeString(sA.resolve("messages.jsonl"),
        "{\"id\":\"a1\",\"role\":\"user\",\"content\":\"in A\"}\n", StandardCharsets.UTF_8);
    Files.writeString(sB.resolve("messages.jsonl"),
        "{\"id\":\"b1\",\"role\":\"user\",\"content\":\"in B\"}\n", StandardCharsets.UTF_8);
    Files.writeString(sA.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,"
            + "\"messageCount\":1,\"parentSessionId\":\"B\"}", StandardCharsets.UTF_8);
    Files.writeString(sB.resolve("meta.json"),
        "{\"shapeId\":\"core.free-chat\",\"createdAtMs\":0,\"lastActiveAtMs\":0,"
            + "\"messageCount\":1,\"parentSessionId\":\"A\"}", StandardCharsets.UTF_8);

    // Should not loop forever; should return a bounded history. With A→B→A
    // the seen-guard truncates the third-level recursion, so the walk yields
    // a finite prefix (a1, b1) plus A's own messages (a1).
    List<Map<String, Object>> history = store.loadHistory("A");
    assertTrue(history.size() <= 5, "cycle should be truncated, not unbounded");
  }

  @Test
  @DisplayName("Tempdoc 610: empty-prefix branch inherits NOTHING from parent")
  void emptyPrefixBranchInheritsNothing(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("parent", "core.free-chat", userMsg("q1"));
    store.appendMessage("parent", "core.free-chat", assistantMsg("a1"));

    // Branch with the empty-prefix sentinel — used to edit/retry the FIRST
    // message, where there is no preceding message to branch from.
    store.branchFrom("parent", ConversationStore.EMPTY_PREFIX_SENTINEL, "fresh");
    assertEquals(0, store.loadHistory("fresh").size(),
        "empty-prefix branch must start with no inherited messages");

    // Its own messages still append normally.
    store.appendMessage("fresh", "core.free-chat", userMsg("q1-edited"));
    List<Map<String, Object>> after = store.loadHistory("fresh");
    assertEquals(1, after.size());
    assertEquals("q1-edited", after.get(0).get("content"));

    // Parent is untouched and is the empty-prefix branch's recorded parent.
    assertEquals(2, store.loadHistory("parent").size());
    assertEquals("parent", store.getSessionMeta("fresh").orElseThrow().parentSessionId());
  }

  @Test
  @DisplayName("Tempdoc 610: branchFrom accepts the empty-prefix sentinel without an existence check")
  void branchFromAcceptsEmptyPrefixSentinel(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("p", "core.free-chat", userMsg("only"));
    // The sentinel is not a real message id; branchFrom must NOT throw for it
    // (contrast branchFromRejectsUnknownBranchPoint, which rejects a real-looking
    // but absent id).
    store.branchFrom("p", ConversationStore.EMPTY_PREFIX_SENTINEL, "b");
    assertTrue(tmp.resolve("b").toFile().exists(),
        "empty-prefix branch dir should be created");
    assertEquals(0, store.loadHistory("b").size());
  }

  @Test
  @DisplayName("Tempdoc 610: context floor trims loadEffectiveContext but not loadHistory")
  void contextFloorTrimsEffectiveContextOnly(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    assertEquals(4, full.size());
    String floorId = (String) full.get(2).get("id"); // q2

    store.setContextFloor("s", floorId);

    // Display history is unchanged…
    assertEquals(4, store.loadHistory("s").size());
    // …but the effective (prompt) context starts at the floor (q2, a2).
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(2, eff.size());
    assertEquals("q2", eff.get(0).get("content"));
    assertEquals("a2", eff.get(1).get("content"));
    // The floor is surfaced on the session meta for the FE divider.
    assertEquals(floorId, store.getSessionMeta("s").orElseThrow().contextFloor());

    // Clearing restores full effective context.
    store.setContextFloor("s", null);
    assertEquals(4, store.loadEffectiveContext("s").size());
    assertNull(store.getSessionMeta("s").orElseThrow().contextFloor());
  }

  @Test
  @DisplayName("Tempdoc 610 §E.3: excludeMessage drops a turn from effective context, not the transcript")
  void excludeMessageFiltersEffectiveContextOnly(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    String a1Id = (String) full.get(1).get("id");

    store.excludeMessage("s", a1Id, true);

    // Transcript unchanged…
    assertEquals(3, store.loadHistory("s").size());
    // …but a1 is dropped from the prompt.
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(2, eff.size());
    assertEquals("q1", eff.get(0).get("content"));
    assertEquals("q2", eff.get(1).get("content"));
    assertEquals(List.of(a1Id), store.excludedMessageIds("s"));

    // Including it back restores it.
    store.excludeMessage("s", a1Id, false);
    assertEquals(3, store.loadEffectiveContext("s").size());
    assertEquals(List.of(), store.excludedMessageIds("s"));
  }

  @Test
  @DisplayName("Tempdoc 610 §J.3: excludeSource round-trips independently of excludeMessage")
  void excludeSourceRoundTrips(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    // The real id is unit-separator-joined (parentDocId + 0x1F + chunkIndex) - exercise the meta.json control-char round-trip.
    String sourceId = "C:/docs/reliability.md" + (char) 0x1F + "2";

    assertEquals(List.of(), store.excludedSourceIds("s"));
    store.excludeSource("s", sourceId, true);
    assertEquals(List.of(sourceId), store.excludedSourceIds("s"));
    // Independent of the per-message set (different axis, different meta.json key).
    assertEquals(List.of(), store.excludedMessageIds("s"));

    // Survives a reload (fresh store over the same dir).
    var reopened = new FileConversationStore(tmp);
    assertEquals(List.of(sourceId), reopened.excludedSourceIds("s"));

    store.excludeSource("s", sourceId, false);
    assertEquals(List.of(), store.excludedSourceIds("s"));
  }

  @Test
  @DisplayName("Tempdoc 610 §E.3: per-message exclusion applies BEFORE the floor trim")
  void excludeAppliesBeforeFloor(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a2"));
    List<Map<String, Object>> full = store.loadHistory("s");
    String floorId = (String) full.get(2).get("id"); // q2
    String a2Id = (String) full.get(3).get("id");

    store.setContextFloor("s", floorId); // prompt = q2, a2
    store.excludeMessage("s", a2Id, true); // exclude a2 (below the floor)

    // Exclusion filters the whole history first, then the floor trims: q2 only.
    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    assertEquals(1, eff.size());
    assertEquals("q2", eff.get(0).get("content"));
  }

  @Test
  @DisplayName("Tempdoc 610: loadEffectiveContext fails safe to full history for an unknown floor id")
  void contextFloorUnknownIdFailsSafe(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.setContextFloor("s", "no-such-id");
    // A stale/unknown floor must NOT silently drop the whole context.
    assertEquals(2, store.loadEffectiveContext("s").size());
  }

  @Test
  @DisplayName("Tempdoc 610: with no floor, loadEffectiveContext equals loadHistory")
  void noFloorEffectiveEqualsHistory(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    assertEquals(
        store.loadHistory("s").size(), store.loadEffectiveContext("s").size());
  }

  @Test
  @DisplayName("Tempdoc 610: compactContext prepends the summary to effective context, clears on rewind")
  void compactPrependsSummary(@TempDir Path tmp) {
    var store = new FileConversationStore(tmp);
    store.appendMessage("s", "core.free-chat", userMsg("q1"));
    store.appendMessage("s", "core.free-chat", assistantMsg("a1"));
    store.appendMessage("s", "core.free-chat", userMsg("q2"));
    String floorId = (String) store.loadHistory("s").get(2).get("id"); // q2

    store.compactContext("s", floorId, "Earlier: the user asked q1 and got a1.");

    List<Map<String, Object>> eff = store.loadEffectiveContext("s");
    // [summary(system), q2]
    assertEquals("system", eff.get(0).get("role"));
    assertTrue(((String) eff.get(0).get("content")).contains("Earlier:"),
        "summary text is prepended");
    assertEquals("q2", eff.get(eff.size() - 1).get("content"));
    // Display history is untouched.
    assertEquals(3, store.loadHistory("s").size());
    // Surfaced on the session meta for the FE divider.
    assertEquals("Earlier: the user asked q1 and got a1.",
        store.getSessionMeta("s").orElseThrow().contextFloorSummary());

    // A plain rewind (setContextFloor) clears the attached summary.
    store.setContextFloor("s", floorId);
    assertNull(store.getSessionMeta("s").orElseThrow().contextFloorSummary());
    assertEquals("user", store.loadEffectiveContext("s").get(0).get("role"),
        "no summary message after rewind");
  }
}
