package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.agent.api.encryption.KeyLockedException;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 806 W1 (round-12 finding R12-F3) — {@code /api/memory} must answer the LOCKED store as
 * locked. Two lies lived here: a mutation refused because the data key is locked came back as an
 * untyped {@code 500} (indistinguishable from a disk failure) or — for {@code DELETE} — as
 * {@code {"ok":true}} for a deletion that never happened; and {@code GET} answered a store it could
 * not read with an empty list, which every client renders as "nothing learned".
 */
final class MemoryControllerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** A MemoryStore whose lock state the test toggles; mutations refuse while locked (806 W1). */
  private static final class FakeStore implements MemoryStore {
    private final Map<String, MemoryRecord> byId = new LinkedHashMap<>();
    private boolean locked;

    @Override
    public void remember(MemoryRecord record) {
      requireUnlocked();
      byId.put(record.id(), record);
    }

    @Override
    public List<MemoryRecord> whatItKnows() {
      // Locked stores answer empty — the collapse `locked` exists to disambiguate.
      return locked ? List.of() : List.copyOf(new ArrayList<>(byId.values()));
    }

    @Override
    public void forget(String id) {
      requireUnlocked();
      byId.remove(id);
    }

    @Override
    public void clear() {
      requireUnlocked();
      byId.clear();
    }

    @Override
    public boolean isLocked() {
      return locked;
    }

    private void requireUnlocked() {
      if (locked) {
        throw new KeyLockedException();
      }
    }
  }

  /** Captured response: the status the handler set (200 if it never called status) + the JSON body. */
  private record Response(int status, JsonNode body) {}

  private static Context mockContext(String body, String pathId, AtomicInteger status,
      AtomicReference<Object> json, AtomicReference<byte[]> raw) {
    Context ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.pathParam("id")).thenReturn(pathId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    doAnswer(inv -> {
          status.set(inv.getArgument(0, Integer.class));
          return ctx;
        })
        .when(ctx)
        .status(anyInt());
    doAnswer(inv -> {
          json.set(inv.getArgument(0));
          return ctx;
        })
        .when(ctx)
        .json(any(Object.class));
    doAnswer(inv -> {
          raw.set(inv.getArgument(0, byte[].class));
          return ctx;
        })
        .when(ctx)
        .result(any(byte[].class));
    return ctx;
  }

  private static Response invoke(java.util.function.Consumer<Context> handler, String body, String id) {
    AtomicInteger status = new AtomicInteger(200);
    AtomicReference<Object> json = new AtomicReference<>();
    AtomicReference<byte[]> raw = new AtomicReference<>();
    handler.accept(mockContext(body, id, status, json, raw));
    JsonNode parsed =
        raw.get() != null ? MAPPER.readTree(raw.get()) : MAPPER.valueToTree(json.get());
    return new Response(status.get(), parsed);
  }

  // ── mutations ────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("806 W1 — a locked POST is a typed 423 + errorCode, not an untyped 500")
  void lockedRememberIsTypedLockedNot500() {
    FakeStore store = new FakeStore();
    store.locked = true;
    Response r =
        invoke(new MemoryController(store)::handleRemember, "{\"content\":\"a durable fact\"}", null);

    assertEquals(423, r.status(), "locked is not an internal error");
    assertEquals("STORE_LOCKED", r.body().get("errorCode").asString());
    assertTrue(r.body().get("locked").asBoolean(), "the wire names the condition a client can act on");
    assertNull(r.body().get("ok"), "a refused write must not read as success");
  }

  @Test
  @DisplayName("806 W1 — a locked DELETE is a typed 423, NOT the pre-806 silent {\"ok\":true}")
  void lockedForgetIsTypedLockedNotSilentOk() {
    FakeStore store = new FakeStore();
    store.remember(rec("secret"));
    store.locked = true;

    Response r = invoke(new MemoryController(store)::handleForget, null, "secret");

    // The pre-806 shape: the emptied cache had no such id, forget() no-op'd, and this returned
    // {"ok":true} while the record sat untouched on disk waiting to reappear on unlock.
    assertEquals(423, r.status());
    assertEquals("STORE_LOCKED", r.body().get("errorCode").asString());
    assertNull(r.body().get("ok"), "a forget that did not happen must not report ok");
  }

  @Test
  @DisplayName("a genuine failure still answers 500 — the locked arm did not swallow the error arm")
  void genuineFailureStill500() {
    // A store whose write fails for a non-lock reason (disk, serialization, …).
    MemoryStore broken =
        new MemoryStore() {
          @Override
          public void remember(MemoryRecord record) {
            throw new IllegalStateException("disk on fire");
          }

          @Override
          public List<MemoryRecord> whatItKnows() {
            return List.of();
          }

          @Override
          public void forget(String id) {
            throw new IllegalStateException("disk on fire");
          }

          @Override
          public void clear() {}
        };

    Response post = invoke(new MemoryController(broken)::handleRemember, "{\"content\":\"x\"}", null);
    assertEquals(500, post.status());
    Response del = invoke(new MemoryController(broken)::handleForget, null, "x");
    assertEquals(500, del.status());
  }

  // ── the read ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("806 W1 — GET carries locked:true so 'cannot read' is distinguishable from 'nothing learned'")
  void listCarriesLockedFlag() {
    FakeStore store = new FakeStore();
    store.remember(rec("m1"));

    Response unlocked = invoke(new MemoryController(store)::handleList, null, null);
    assertEquals(1, unlocked.body().get("memories").size());
    assertTrue(!unlocked.body().get("locked").asBoolean(), "a readable store is not locked");

    store.locked = true;
    Response locked = invoke(new MemoryController(store)::handleList, null, null);
    assertEquals(0, locked.body().get("memories").size(), "locked reads are empty (plaintext is gone)");
    assertTrue(
        locked.body().get("locked").asBoolean(),
        "…but the payload says WHY, so no client can render the empty list as 'no learned memory yet'");
  }

  private static MemoryRecord rec(String id) {
    return new MemoryRecord(id, "fact", "content of " + id, "conv-1", "primary", Instant.EPOCH);
  }
}
