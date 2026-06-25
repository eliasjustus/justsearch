/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 561 P-E — the ONE durable authority for the agent's learned memory. A single JSON file of
 * {@link MemoryRecord}s keyed by id; {@link #whatItKnows()} and {@link #forget(String)} are read/edit
 * projections of it (single authority + user control). Modeled on the {@code FileConversationStore} /
 * {@code AgentRunStore} discipline: durable, id-keyed, no second store.
 */
public final class FileMemoryStore implements MemoryStore {

  private static final Logger LOG = LoggerFactory.getLogger(FileMemoryStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> LIST_REF = new TypeReference<>() {};

  private final Path file;
  private final StoreCipher cipher;
  private final Map<String, MemoryRecord> byId = new LinkedHashMap<>();

  public FileMemoryStore(Path rootDir) {
    this(rootDir, StoreCipher.disabled());
  }

  /**
   * Tempdoc 629 (LAYER) — {@code memory.json} is an AUTHORED store, sealed whole-file by {@code cipher}
   * when at-rest encryption is enabled. Because this store eager-loads at construction, a launch in the
   * LOCKED state skips the read (cache stays empty); the owner must re-call {@link #onKeyUnlocked()} on
   * unlock and {@link #onKeyLocked()} on lock (wired from the {@code DataKeyManager} listener).
   */
  public FileMemoryStore(Path rootDir, StoreCipher cipher) {
    this.file = rootDir.resolve("memory.json");
    this.cipher = java.util.Objects.requireNonNull(cipher, "cipher");
    load();
  }

  /** Tempdoc 629: re-read after unlock (the constructor load was skipped while locked). */
  public synchronized void onKeyUnlocked() {
    byId.clear();
    load();
  }

  /** Tempdoc 629: drop plaintext memory from RAM when the key locks (reads return empty until unlock). */
  public synchronized void onKeyLocked() {
    byId.clear();
  }

  @Override
  public synchronized void remember(MemoryRecord record) {
    if (record == null) {
      return;
    }
    byId.put(record.id(), record);
    persist();
  }

  @Override
  public synchronized List<MemoryRecord> whatItKnows() {
    // Newest first — the inspectable projection.
    List<MemoryRecord> out = new ArrayList<>(byId.values());
    out.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
    return List.copyOf(out);
  }

  @Override
  public synchronized void forget(String id) {
    if (id != null && byId.remove(id) != null) {
      persist();
    }
  }

  @Override
  public synchronized void clear() {
    if (!byId.isEmpty()) {
      byId.clear();
      persist();
    }
  }

  private void load() {
    if (!Files.exists(file)) {
      return;
    }
    if (cipher.enabled() && cipher.locked()) {
      // Sealed + locked: leave the cache empty and reload on unlock. Do NOT read-and-swallow into a
      // false-empty (the §L4 "locked must not look deleted" invariant, applied to the eager cache).
      return;
    }
    try {
      List<Map<String, Object>> rows =
          MAPPER.readValue(cipher.open(Files.readString(file, StandardCharsets.UTF_8)), LIST_REF);
      for (Map<String, Object> r : rows) {
        MemoryRecord rec = fromMap(r);
        byId.put(rec.id(), rec);
      }
    } catch (Exception e) {
      LOG.warn("Failed to load memory from {}", file, e);
    }
  }

  private void persist() {
    if (cipher.enabled() && cipher.locked()) {
      // Refuse to write while locked — we can't seal without the key, and must never overwrite the
      // ciphertext with plaintext/empty. Propagates to the caller (agent ops are gated while locked).
      throw new io.justsearch.agent.api.encryption.KeyLockedException();
    }
    try {
      Files.createDirectories(file.getParent());
      List<Map<String, Object>> rows = new ArrayList<>(byId.size());
      for (MemoryRecord r : byId.values()) {
        rows.add(toMap(r));
      }
      Files.writeString(
          file,
          cipher.seal(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(rows)),
          StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOG.warn("Failed to persist memory to {}", file, e);
    }
  }

  private static Map<String, Object> toMap(MemoryRecord r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("kind", r.kind());
    m.put("content", r.content());
    m.put("sourceConversationId", r.sourceConversationId());
    m.put("actor", r.actor());
    m.put("createdAt", r.createdAt().toString());
    return m;
  }

  private static MemoryRecord fromMap(Map<String, Object> m) {
    return new MemoryRecord(
        str(m.get("id")),
        str(m.get("kind")),
        str(m.get("content")),
        str(m.get("sourceConversationId")),
        str(m.get("actor")),
        parseTs(m.get("createdAt")));
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }

  private static Instant parseTs(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
    }
    return Instant.EPOCH;
  }
}
