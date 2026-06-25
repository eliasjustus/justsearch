package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 415: covers the v0 → v3 upcaster path on {@link AgentRunStore}, plus a v3 round-trip
 * for sessions written natively at the current schema version. The store's {@code SchemaUpcaster}
 * is private; the test exercises it via {@code readLastSnapshot} after writing fixture meta.json
 * files at each historical schema version.
 */
class AgentRunStoreSchemaUpcasterTest {

  @TempDir Path tempDir;

  @Test
  void readsV0FixtureAndUpcastsToCurrent() throws Exception {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    String sessionId = "v0-session";
    Path runDir = tempDir.resolve("agent-runs").resolve(sessionId);
    Files.createDirectories(runDir);
    // Bare-minimum v0 meta — no schemaVersion, no v2 handoff fields, no v3 terminationReason.
    String v0Meta =
        """
        {
          "sessionId" : "v0-session",
          "startedAt" : "2026-04-01T00:00:00Z",
          "updatedAt" : "2026-04-01T00:00:01Z",
          "state" : "DONE",
          "resumable" : false,
          "resumeNote" : "Max iterations reached",
          "messages" : []
        }
        """;
    Files.writeString(runDir.resolve("meta.json"), v0Meta, StandardCharsets.UTF_8);
    Files.writeString(
        tempDir.resolve("agent-runs").resolve("last-session.txt"),
        sessionId,
        StandardCharsets.UTF_8);

    Map<String, Object> snapshot = store.readLastSnapshot();

    assertNotNull(snapshot);
    assertEquals(AgentRunStore.CURRENT_SCHEMA_VERSION, ((Number) snapshot.get("schemaVersion")).intValue());
    // v0 → v1 added schemaVersion; v1 → v2 added handoff fields with defaults.
    assertEquals("primary", snapshot.get("activeAgentId"));
    assertEquals(List.of(), snapshot.get("handoffHistory"));
    assertEquals(List.of(), snapshot.get("agentProfiles"));
    assertNull(snapshot.get("initialAgentId"));
    // v2 → v3 added terminationReason. Defaulted to null on legacy snapshots — no inference
    // from the existing free-form resumeNote.
    assertTrue(snapshot.containsKey("terminationReason"));
    assertNull(snapshot.get("terminationReason"));
  }

  @Test
  void readsV2FixtureAndUpcastsToCurrent() throws Exception {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    String sessionId = "v2-session";
    Path runDir = tempDir.resolve("agent-runs").resolve(sessionId);
    Files.createDirectories(runDir);
    String v2Meta =
        """
        {
          "sessionId" : "v2-session",
          "startedAt" : "2026-04-15T00:00:00Z",
          "updatedAt" : "2026-04-15T00:00:01Z",
          "state" : "DONE",
          "resumable" : false,
          "resumeNote" : "",
          "messages" : [],
          "schemaVersion" : 2,
          "activeAgentId" : "primary",
          "handoffHistory" : [],
          "agentProfiles" : [],
          "initialAgentId" : null
        }
        """;
    Files.writeString(runDir.resolve("meta.json"), v2Meta, StandardCharsets.UTF_8);
    Files.writeString(
        tempDir.resolve("agent-runs").resolve("last-session.txt"),
        sessionId,
        StandardCharsets.UTF_8);

    Map<String, Object> snapshot = store.readLastSnapshot();

    assertNotNull(snapshot);
    assertEquals(AgentRunStore.CURRENT_SCHEMA_VERSION, ((Number) snapshot.get("schemaVersion")).intValue());
    assertTrue(snapshot.containsKey("terminationReason"));
    assertNull(snapshot.get("terminationReason"));
  }

  @Test
  void writesAndReadsBackTypedTerminationReason() throws Exception {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    String sessionId = "fresh-session";
    var request =
        new io.justsearch.agent.api.AgentRequest(
            List.of(Map.of("role", "user", "content", "hi")), List.of(), 5);
    store.startRun(sessionId, request, request.messages(), 8000);

    // Mid-run: terminationReason is null (no terminal disposition yet).
    Map<String, Object> midSnapshot = store.readLastSnapshot();
    assertNotNull(midSnapshot);
    assertNull(midSnapshot.get("terminationReason"));

    // Terminal write: setTerminationReason populates the structured object.
    store.setTerminationReason(
        sessionId,
        new TerminationReason(
            TerminalDisposition.ERRORED, AgentErrorCode.BUDGET_EXHAUSTED, null));

    Map<String, Object> terminalSnapshot = store.readLastSnapshot();
    assertNotNull(terminalSnapshot);
    @SuppressWarnings("unchecked")
    Map<String, Object> tr = (Map<String, Object>) terminalSnapshot.get("terminationReason");
    assertNotNull(tr);
    assertEquals("ERRORED", tr.get("disposition"));
    assertEquals("BUDGET_EXHAUSTED", tr.get("errorCode"));
    assertNull(tr.get("cancelTrigger"));
  }

  @Test
  void writesAndReadsBackCancelledWithTrigger() throws Exception {
    var store = new AgentRunStore(tempDir.resolve("agent-runs"));
    String sessionId = "cancel-session";
    var request =
        new io.justsearch.agent.api.AgentRequest(
            List.of(Map.of("role", "user", "content", "go")), List.of(), 5);
    store.startRun(sessionId, request, request.messages(), 8000);

    store.setTerminationReason(
        sessionId,
        new TerminationReason(TerminalDisposition.CANCELLED, null, CancelTrigger.USER));

    Map<String, Object> snapshot = store.readLastSnapshot();
    assertNotNull(snapshot);
    @SuppressWarnings("unchecked")
    Map<String, Object> tr = (Map<String, Object>) snapshot.get("terminationReason");
    assertNotNull(tr);
    assertEquals("CANCELLED", tr.get("disposition"));
    assertNull(tr.get("errorCode"));
    assertEquals("USER", tr.get("cancelTrigger"));
  }
}
