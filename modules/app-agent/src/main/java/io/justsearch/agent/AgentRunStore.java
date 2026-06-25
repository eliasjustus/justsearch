/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.AgentProfile;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.telemetry.DiagnosticFileRetention;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Durable, append-only run store for agent session checkpoints and event replay. */
public final class AgentRunStore {
  private static final Logger LOG = LoggerFactory.getLogger(AgentRunStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

  /**
   * Schema version history:
   * <ul>
   *   <li>v0 → v1: add missing schemaVersion field
   *   <li>v1 → v2: add handoff fields (activeAgentId, handoffHistory, agentProfiles, initialAgentId)
   *   <li>v2 → v3: add terminationReason field (tempdoc 415)
   *   <li>v3 → v4: rename event payloads' {@code "safetyLevel": "READ_ONLY"|"WRITE"|"DESTRUCTIVE"}
   *       to {@code "risk": "low"|"medium"|"high"} (tempdoc 429 §F.21 C2 — substrate
   *       vocabulary on the wire; legacy {@code riskToLegacyName} shim deleted).
   * </ul>
   */
  static final int CURRENT_SCHEMA_VERSION = 4;

  private final Path rootDir; // nullable for noop

  /**
   * Tempdoc 565 §15.C — the agent run's DURABLE EVENT LOG, now the shape-agnostic {@link RunEventStore}
   * (the generic {@code {timestamp, eventType, payload}} ndjson + listener fan-out extracted so the
   * workflow run persists + projects through the SAME authority). This store keeps the AGENT-specific
   * {@code meta.json} (profiles / handoff / resume) and maps {@code AgentEvent} → {@code (eventType,
   * payload)}; the event log itself is generic. Constructed with the same root so meta + events share
   * the per-run directory.
   */
  private final RunEventStore events;
  private final StoreCipher cipher; // tempdoc 629 (LAYER) — seals meta.json when enabled

  public AgentRunStore(Path rootDir) {
    this(rootDir, StoreCipher.disabled());
  }

  /** Tempdoc 629 (LAYER) — {@code meta.json} is sealed by {@code cipher} when at-rest encryption is
   * enabled; the shared {@code cipher} also flows to the {@link RunEventStore} so events.ndjson is sealed
   * with the same key. While locked, {@link #readMeta} returns null (ledger empty until unlock). */
  public AgentRunStore(Path rootDir, StoreCipher cipher) {
    this.rootDir = rootDir;
    this.cipher = java.util.Objects.requireNonNull(cipher, "cipher");
    this.events = new RunEventStore(rootDir, cipher);
    if (rootDir != null) {
      DiagnosticFileRetention.pruneDirectoriesBefore(
          rootDir, Instant.now().minus(java.time.Duration.ofDays(30)));
    }
  }

  /** Tempdoc 565 §15.C — the shared run event log, so the workflow run can persist through it too. */
  public RunEventStore runEvents() {
    return events;
  }

  /**
   * Tempdoc 561 P-A/P-B — register a listener fired (outside the write lock) on every persisted event.
   * The ledger's agent-action rows project from this, so the unified thread and agent History derive
   * from the ONE record and cannot structurally disagree.
   */
  public void addEventListener(BiConsumer<String, Map<String, Object>> listener) {
    events.addEventListener(listener);
  }

  static AgentRunStore noop() {
    return new AgentRunStore(null);
  }

  boolean isEnabled() {
    return rootDir != null;
  }

  synchronized void startRun(
      String sessionId, AgentRequest request, List<Map<String, Object>> messages, int initialBudget) {
    if (!isEnabled()) {
      return;
    }
    try {
      Files.createDirectories(runDir(sessionId));
      var meta = new LinkedHashMap<String, Object>();
      String now = Instant.now().toString();
      meta.put("sessionId", sessionId);
      // Tempdoc 565 §15.C fix — tag the run's shape so the agent-specific views (History/Sessions/
      // presence/ledger) positively filter to agent runs now that the run-event store is multi-shape.
      meta.put("shapeId", "core.agent-run");
      // Tempdoc 561 P-A/P-B (correction): the parent chat conversationId, so the unified-thread
      // projection can find this run's events for the conversation (the §10 cross-domain join). Null
      // when the run is standalone (its own thread).
      meta.put("conversationId", request.conversationId());
      meta.put("startedAt", now);
      meta.put("updatedAt", now);
      meta.put("state", "READY_FOR_LLM");
      meta.put("resumable", true);
      meta.put("resumeNote", "");
      meta.put("selectedToolNames", request.selectedToolNames());
      meta.put("maxIterations", request.maxIterations());
      meta.put("initialBudget", initialBudget);
      meta.put("iterationsUsed", 0);
      meta.put("toolCallsExecuted", 0);
      meta.put("totalTokensUsed", 0);
      meta.put("messages", messages);
      meta.put("schemaVersion", CURRENT_SCHEMA_VERSION);
      meta.put("agentProfiles", toProfileMaps(request.agentProfiles()));
      meta.put("initialAgentId", request.initialAgentId());
      // Resolve effective initial agent ID: explicit > first profile > "primary"
      String effectiveAgentId = request.initialAgentId();
      if (effectiveAgentId == null && !request.agentProfiles().isEmpty()) {
        effectiveAgentId = request.agentProfiles().get(0).agentId();
      }
      meta.put("activeAgentId", effectiveAgentId != null ? effectiveAgentId : "primary");
      meta.put("handoffHistory", List.of());
      // Tempdoc 415: schema v3 — terminationReason is null until the session reaches a terminal
      // state, at which point AgentRunStore.setTerminationReason populates it from the session.
      meta.put("terminationReason", null);
      writeMeta(sessionId, meta);
      writeLastSessionId(sessionId);
    } catch (Exception e) {
      LOG.warn("Failed to persist run start for session {}", sessionId, e);
    }
  }

  synchronized void updateCheckpoint(
      String sessionId,
      String state,
      List<Map<String, Object>> messages,
      int iterationsUsed,
      int toolCallsExecuted,
      int totalTokensUsed,
      String resumeNote) {
    if (!isEnabled()) {
      return;
    }
    try {
      var meta = readMeta(sessionId);
      if (meta == null) {
        meta = new LinkedHashMap<>();
        meta.put("sessionId", sessionId);
        meta.put("startedAt", Instant.now().toString());
      }
      meta.put("updatedAt", Instant.now().toString());
      meta.put("state", state);
      meta.put("resumable", isResumableState(state));
      meta.put("resumeNote", resumeNote == null ? "" : resumeNote);
      meta.put("iterationsUsed", iterationsUsed);
      meta.put("toolCallsExecuted", toolCallsExecuted);
      meta.put("totalTokensUsed", totalTokensUsed);
      meta.put("messages", messages);
      meta.put("schemaVersion", CURRENT_SCHEMA_VERSION);
      writeMeta(sessionId, meta);
      writeLastSessionId(sessionId);
    } catch (Exception e) {
      LOG.warn("Failed to persist checkpoint for session {}", sessionId, e);
    }
  }

  /**
   * Patches {@code activeAgentId} and {@code handoffHistory} into the checkpoint for the given
   * session. Called from the agent loop immediately after each handoff, independently of the
   * regular {@link #updateCheckpoint} flow (which has 19+ call sites and must not be extended).
   *
   * <p>{@code handoffHistory} is snapshotted via {@link List#copyOf} — the caller may pass a live
   * unmodifiable view from {@link AgentSession#handoffHistory()} without risk of aliasing.
   */
  synchronized void setHandoffState(
      String sessionId, String activeAgentId, List<Map<String, Object>> handoffHistory) {
    if (!isEnabled()) {
      return;
    }
    try {
      var meta = readMeta(sessionId);
      if (meta == null) {
        LOG.warn("Cannot set handoff state: no checkpoint found for session {}", sessionId);
        return;
      }
      meta.put("updatedAt", Instant.now().toString());
      meta.put("activeAgentId", activeAgentId);
      meta.put("handoffHistory", List.copyOf(handoffHistory));
      writeMeta(sessionId, meta);
    } catch (Exception e) {
      LOG.warn("Failed to persist handoff state for session {}", sessionId, e);
    }
  }

  /**
   * Patches {@code terminationReason} into the session checkpoint after a terminal disposition
   * is reached. Mirrors {@link #setHandoffState}'s patch-method shape. Tempdoc 415 — called once
   * per session from {@code AgentLoopService}'s {@code finally{}} block, after
   * {@code session.markTerminated(...)}.
   *
   * <p>The {@code resumeNote} slot continues to carry the existing free-form annotation (now
   * read-only for non-terminal states). The new typed {@code terminationReason} is written as a
   * structured object with {@code disposition}, {@code errorCode} (nullable), and
   * {@code cancelTrigger} (nullable) fields.
   */
  synchronized void setTerminationReason(String sessionId, TerminationReason terminationReason) {
    if (!isEnabled()) {
      return;
    }
    try {
      var meta = readMeta(sessionId);
      if (meta == null) {
        LOG.warn(
            "Cannot set terminationReason: no checkpoint found for session {}", sessionId);
        return;
      }
      meta.put("updatedAt", Instant.now().toString());
      if (terminationReason == null) {
        meta.put("terminationReason", null);
      } else {
        var tr = new LinkedHashMap<String, Object>();
        tr.put("disposition", terminationReason.disposition().name());
        tr.put(
            "errorCode",
            terminationReason.errorCode() != null
                ? terminationReason.errorCode().name()
                : null);
        tr.put(
            "cancelTrigger",
            terminationReason.cancelTrigger() != null
                ? terminationReason.cancelTrigger().name()
                : null);
        meta.put("terminationReason", tr);
      }
      writeMeta(sessionId, meta);
    } catch (Exception e) {
      LOG.warn("Failed to persist terminationReason for session {}", sessionId, e);
    }
  }

  void appendEvent(String sessionId, AgentEvent event) {
    // Tempdoc 565 §15.C — map the AgentEvent to the generic (eventType, payload) and persist through the
    // shape-agnostic event log (which writes the record on disk before firing listeners, so the ledger
    // projection can never get ahead of the durable record). The agent vocabulary mapping stays here.
    // Tempdoc 580 §17 P4 (Fix B): stamp the run's sessionId onto the persisted + fanned-out payload so
    // the feedback listener can correlate this run's per-search snapshots with its done-event
    // dispositions. Only the persistence/listener payload carries it — the SSE wire payload (built
    // separately by AgentEventSseTranslator) is unchanged, so the event-schema conformance gate is
    // unaffected.
    Map<String, Object> payload = new LinkedHashMap<>(toPayload(event));
    payload.put("sessionId", sessionId);
    events.appendEvent(sessionId, "core.agent-run", toEventType(event), payload);
  }

  synchronized Map<String, Object> readLastSnapshot() {
    if (!isEnabled()) {
      return null;
    }
    String sessionId = readLastSessionId();
    if (sessionId == null || sessionId.isBlank()) {
      return null;
    }
    return readMeta(sessionId);
  }

  /**
   * Public read of a session's full meta (mirrors {@link #readLastSnapshot()} but addressed by
   * id). Tempdoc 415 follow-up (C20).
   */
  public synchronized Map<String, Object> readSnapshot(String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return null;
    }
    return readMeta(sessionId);
  }

  /**
   * List recent persisted session summaries (newest first, sorted by directory mtime).
   * Tempdoc 415 follow-up (C20). Limit is clamped to 1..100 by the caller; this method only
   * applies the absolute upper bound implicitly via {@link Stream#limit(long)}.
   */
  public synchronized List<Map<String, Object>> listSessions(int limit) {
    if (!isEnabled()) {
      return List.of();
    }
    int safeLimit = Math.max(1, limit);
    try (var stream = Files.list(rootDir)) {
      return stream
          .filter(Files::isDirectory)
          .sorted(
              java.util.Comparator.<Path, java.nio.file.attribute.FileTime>comparing(
                      p -> {
                        try {
                          return Files.getLastModifiedTime(p);
                        } catch (IOException e) {
                          return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                      })
                  .reversed())
          .map(p -> readMeta(p.getFileName().toString()))
          .filter(java.util.Objects::nonNull)
          // Tempdoc 565 §15.C fix — the Sessions view is agent-run-only; exclude workflow runs that now
          // share the run-event root (filter BEFORE the limit so it bounds AGENT runs, not all runs).
          .filter(AgentRunStore::isAgentRun)
          .limit(safeLimit)
          .map(AgentRunStore::toSessionSummary)
          .toList();
    } catch (IOException e) {
      LOG.warn("Failed to list sessions in {}", rootDir, e);
      return List.of();
    }
  }

  /**
   * Tempdoc 561 P-D2 — mark a run as a BACKGROUND (non-interactive) run, so the presence axis can
   * distinguish work that proceeded without a watcher (the render-on-return inbox source). Patches the
   * one durable record; {@link #updateCheckpoint} reads-then-writes so it preserves this flag, and a
   * completed run is no longer checkpointed, so marking after completion is stable.
   */
  public synchronized void markBackground(String sessionId) {
    if (!isEnabled() || sessionId == null || sessionId.isBlank()) {
      return;
    }
    try {
      var meta = readMeta(sessionId);
      if (meta == null) {
        return;
      }
      meta.put("background", true);
      writeMeta(sessionId, meta);
    } catch (Exception e) {
      LOG.warn("Failed to mark run {} background", sessionId, e);
    }
  }

  /**
   * Tempdoc 561 P-D2 — the presence projection: the full meta of every BACKGROUND run whose
   * {@code updatedAt} is strictly after {@code since}. This is the render-on-return inbox's source —
   * "what completed while you were away" — a read-time projection of the ONE durable run record, never
   * a second store. {@code since == null} returns all background runs.
   */
  public synchronized List<Map<String, Object>> presenceRunsSince(Instant since) {
    if (!isEnabled()) {
      return List.of();
    }
    try (var stream = Files.list(rootDir)) {
      return stream
          .filter(Files::isDirectory)
          .map(p -> readMeta(p.getFileName().toString()))
          .filter(java.util.Objects::nonNull)
          // Tempdoc 565 §15.C fix — presence is the agent-run inbox; exclude workflow runs (defense in
          // depth — workflow also sets background=false, but the shape filter is the explicit invariant).
          .filter(AgentRunStore::isAgentRun)
          .filter(m -> Boolean.TRUE.equals(m.get("background")))
          .filter(m -> since == null || isAfter(m.get("updatedAt"), since))
          .toList();
    } catch (IOException e) {
      LOG.warn("Failed to list presence runs in {}", rootDir, e);
      return List.of();
    }
  }

  /**
   * Tempdoc 565 §15.C fix — agent-run views (Sessions / presence / ledger) filter to the agent shape now
   * that the run-event store is multi-shape. Untagged (pre-fix) runs are agent runs.
   */
  private static boolean isAgentRun(Map<String, Object> meta) {
    Object shapeId = meta.get("shapeId");
    return shapeId == null || "core.agent-run".equals(shapeId);
  }

  private static boolean isAfter(Object updatedAt, Instant since) {
    if (!(updatedAt instanceof String s) || s.isBlank()) {
      return false;
    }
    try {
      return Instant.parse(s).isAfter(since);
    } catch (java.time.format.DateTimeParseException e) {
      return false;
    }
  }

  /**
   * Tempdoc 561 P-A/P-B (correction): the run sessionIds belonging to a chat conversation, so the
   * unified-thread projection can read each run's events. Filters each run's meta by the persisted
   * parent {@code conversationId} (the §10 cross-domain join). Order is irrelevant — the projection
   * sorts all events by their own timestamps.
   */
  public synchronized List<String> listRunIdsByConversation(String conversationId) {
    if (!isEnabled() || conversationId == null || conversationId.isBlank()) {
      return List.of();
    }
    try (var stream = Files.list(rootDir)) {
      return stream
          .filter(Files::isDirectory)
          .map(p -> p.getFileName().toString())
          .filter(
              id -> {
                Map<String, Object> meta = readMeta(id);
                return meta != null && conversationId.equals(meta.get("conversationId"));
              })
          .sorted()
          .toList();
    } catch (IOException e) {
      LOG.warn("Failed to list runs for conversation {}", conversationId, e);
      return List.of();
    }
  }

  /**
   * Project a full meta.json into a list-row summary suitable for {@code GET /api/agent/sessions}.
   * Tempdoc 415 follow-up (C20). Heavy fields ({@code messages}, {@code agentProfiles},
   * {@code handoffHistory}) are intentionally dropped.
   */
  private static Map<String, Object> toSessionSummary(Map<String, Object> meta) {
    var s = new LinkedHashMap<String, Object>();
    s.put("sessionId", meta.get("sessionId"));
    s.put("startedAt", meta.get("startedAt"));
    s.put("updatedAt", meta.get("updatedAt"));
    s.put("state", meta.get("state"));
    s.put("resumable", meta.get("resumable"));
    s.put("iterationsUsed", meta.get("iterationsUsed"));
    s.put("toolCallsExecuted", meta.get("toolCallsExecuted"));
    s.put("totalTokensUsed", meta.get("totalTokensUsed"));
    s.put("activeAgentId", meta.get("activeAgentId"));
    s.put("terminationReason", meta.get("terminationReason"));
    s.put("preview", derivePreview(meta));
    return s;
  }

  private static String derivePreview(Map<String, Object> meta) {
    Object raw = meta.get("messages");
    if (!(raw instanceof List<?> messages)) {
      return "";
    }
    // Tempdoc 561 #4: prefer the USER's request as the session label — the generic system prompt is
    // only a fallback (otherwise every agent session shows the same "You are a helpful assistant…").
    String firstSystem = null;
    for (Object m : messages) {
      if (!(m instanceof Map<?, ?> msg)) {
        continue;
      }
      Object role = msg.get("role");
      Object content = msg.get("content");
      if (!(content instanceof String s) || s.isBlank()) {
        continue;
      }
      String collapsed = s.replaceAll("\\s+", " ").strip();
      String preview = collapsed.length() <= 80 ? collapsed : collapsed.substring(0, 80) + "…";
      if ("user".equals(role)) {
        return preview;
      }
      if ("system".equals(role) && firstSystem == null) {
        firstSystem = preview;
      }
    }
    return firstSystem != null ? firstSystem : "";
  }

  /** Tempdoc 565 §15.C — delegate to the shape-agnostic event log (incl. the legacy-payload upcast). */
  synchronized List<Map<String, Object>> readEvents(String sessionId) {
    return events.readEvents(sessionId);
  }

  private static boolean isResumableState(String state) {
    return "WAITING_APPROVAL".equals(state)
        || "READY_FOR_LLM".equals(state)
        || "AFTER_TOOL_RESULT".equals(state);
  }

  // Tempdoc 585 §D Phase 0: the agent event → (name, payload) mapping is the shared
  // AgentEventPayloads authority (app-agent-api), so the persisted record, the wire translator, and
  // the generated FE handler types cannot drift. The persisted payload IS the base + trace; the wire
  // adds only the tool_batch_proposed gate-prediction overlay on top of the same base.
  private static String toEventType(AgentEvent event) {
    return AgentEventPayloads.name(event);
  }

  private static Map<String, Object> toPayload(AgentEvent event) {
    return AgentEventPayloads.withTrace(AgentEventPayloads.base(event), event.trace());
  }

  private static List<Map<String, Object>> toProfileMaps(List<AgentProfile> profiles) {
    return profiles.stream()
        .map(
            p -> {
              var m = new LinkedHashMap<String, Object>();
              m.put("agentId", p.agentId());
              m.put("name", p.name());
              m.put("systemPrompt", p.systemPrompt()); // null allowed in LinkedHashMap
              m.put("toolSubset", p.toolSubset());
              return (Map<String, Object>) m;
            })
        .toList();
  }

  private void writeMeta(String sessionId, Map<String, Object> meta) throws IOException {
    Files.createDirectories(runDir(sessionId));
    String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
    Files.writeString(metaPath(sessionId), cipher.seal(json), StandardCharsets.UTF_8);
  }

  private Map<String, Object> readMeta(String sessionId) {
    if (!isEnabled()) {
      return null;
    }
    Path p = metaPath(sessionId);
    if (!Files.exists(p)) {
      return null;
    }
    if (cipher.enabled() && cipher.locked()) {
      return null; // sealed + locked: ledger empty until unlock (documented agent-run limitation, 629)
    }
    try {
      Map<String, Object> raw =
          MAPPER.readValue(cipher.open(Files.readString(p, StandardCharsets.UTF_8)), MAP_REF);
      return SchemaUpcaster.upcast(raw);
    } catch (UnsupportedOperationException e) {
      LOG.error(
          "Checkpoint schema version incompatible for session {}: {}",
          sessionId,
          e.getMessage());
      throw e;
    } catch (Exception e) {
      LOG.warn("Failed to read meta for session {}", sessionId, e);
      return null;
    }
  }

  /** Pure-function upcasters for meta.json schema migration. Never mutates stored data. */
  private static final class SchemaUpcaster {
    private static final List<Function<Map<String, Object>, Map<String, Object>>> UPCASTERS =
        List.of(
            // v0 -> v1: add schemaVersion field (absent in legacy checkpoints).
            meta -> {
              var upgraded = new LinkedHashMap<>(meta);
              upgraded.put("schemaVersion", 1);
              return upgraded;
            },
            // v1 -> v2: add handoff fields and agent profile fields (absent in single-agent
            // checkpoints). Must also update schemaVersion explicitly so that v0 fixtures going
            // through both upcasters end up at schemaVersion=2=CURRENT_SCHEMA_VERSION.
            meta -> {
              var upgraded = new LinkedHashMap<>(meta);
              upgraded.putIfAbsent("activeAgentId", "primary");
              upgraded.putIfAbsent("handoffHistory", List.of());
              upgraded.putIfAbsent("agentProfiles", List.of());
              upgraded.putIfAbsent("initialAgentId", null);
              upgraded.put("schemaVersion", 2);
              return upgraded;
            },
            // v2 -> v3 (tempdoc 415): add terminationReason field. Defaulted to null; legacy
            // sessions cannot backfill the typed reason from the existing free-form resumeNote
            // string. Must update schemaVersion explicitly so a v0 fixture going through all
            // three upcasters lands at schemaVersion=3=CURRENT_SCHEMA_VERSION.
            meta -> {
              var upgraded = new LinkedHashMap<>(meta);
              upgraded.putIfAbsent("terminationReason", null);
              upgraded.put("schemaVersion", 3);
              return upgraded;
            },
            // v3 -> v4 (tempdoc 429 §F.21 C2): substrate vocabulary on the wire — event
            // payloads now carry "risk": "low"|"medium"|"high" instead of "safetyLevel":
            // "READ_ONLY"|"WRITE"|"DESTRUCTIVE". The meta.json itself is unchanged; this
            // upcaster just bumps the schemaVersion. The matching event-payload migration
            // happens in EventPayloadUpcaster on the read path.
            meta -> {
              var upgraded = new LinkedHashMap<>(meta);
              upgraded.put("schemaVersion", 4);
              return upgraded;
            });

    static Map<String, Object> upcast(Map<String, Object> meta) {
      int version =
          meta.get("schemaVersion") instanceof Number n ? n.intValue() : 0;
      if (version > CURRENT_SCHEMA_VERSION) {
        throw new UnsupportedOperationException(
            "Checkpoint schemaVersion "
                + version
                + " is newer than supported version "
                + CURRENT_SCHEMA_VERSION
                + ". Please upgrade JustSearch.");
      }
      Map<String, Object> result = meta;
      for (int v = version; v < CURRENT_SCHEMA_VERSION; v++) {
        result = UPCASTERS.get(v).apply(result);
      }
      return result;
    }
  }

  private void writeLastSessionId(String sessionId) throws IOException {
    Files.createDirectories(rootDir);
    Files.writeString(lastSessionPath(), sessionId, StandardCharsets.UTF_8);
  }

  private String readLastSessionId() {
    if (!isEnabled() || !Files.exists(lastSessionPath())) {
      return null;
    }
    try {
      return Files.readString(lastSessionPath(), StandardCharsets.UTF_8).trim();
    } catch (Exception e) {
      LOG.warn("Failed to read last session pointer", e);
      return null;
    }
  }

  private Path runDir(String sessionId) {
    return rootDir.resolve(sessionId);
  }

  private Path metaPath(String sessionId) {
    return runDir(sessionId).resolve("meta.json");
  }

  private Path lastSessionPath() {
    return rootDir.resolve("last-session.txt");
  }
}
