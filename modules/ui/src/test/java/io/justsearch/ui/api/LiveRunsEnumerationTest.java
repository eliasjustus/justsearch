/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.app.api.run.LiveRunSummary;
import io.justsearch.app.api.run.LiveRunsResponse;
import io.justsearch.app.observability.stream.run.ParkState;
import io.justsearch.app.observability.stream.run.RunChannelPolicy;
import io.justsearch.app.observability.stream.run.RunChannelRegistry;
import io.justsearch.app.observability.stream.run.RunDescriptor;
import io.justsearch.app.observability.stream.run.RunFrame;
import io.justsearch.app.observability.stream.run.RunId;
import io.justsearch.app.observability.stream.run.RunStateSnapshot;
import io.justsearch.app.observability.stream.run.SteppedRunChannel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 834 §5.1 (S4) — {@code GET /api/chat/runs/live}, end to end through the real route, the
 * real registry and the real JSON mapper.
 *
 * <p>The endpoint is the FE's run-discovery authority, so the properties pinned here are the ones a
 * recovering client depends on: that a parked run arrives with the handle needed to ANSWER its gate
 * (§6.1's law), that two runs on one conversation are two rows (§3.5), and that a one-shot run's
 * absent park/snapshot are absent by construction rather than by accident (§3.4).
 */
@DisplayName("GET /api/chat/runs/live — projection fidelity (834 S4)")
class LiveRunsEnumerationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long T0 = 1_700_000_000_000L;

  private HttpClient client;
  private Javalin app;
  private int port;
  private RunChannelRegistry registry;
  private MutableClock clock;

  @BeforeEach
  void setup() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    clock = new MutableClock(T0);
    registry = new RunChannelRegistry(clock);

    app =
        Javalin.create(
            cfg -> {
              cfg.showJavalinBanner = false;
              cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
            });
    // The real route registration, so a wiring mistake fails here rather than in a live round.
    RunRoutes.register(app, null, new AgentSessionController(() -> null, null, registry));
    app.start("127.0.0.1", 0);
    port = app.port();
  }

  @AfterEach
  void teardown() {
    if (app != null) {
      app.stop();
      app = null;
    }
  }

  // ── The rows ─────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("a running one-shot run projects with no park and no snapshot — by construction")
  void oneShotRun() throws Exception {
    registry.open(
        new RunId("run-ask-1"),
        new RunDescriptor("core.free-chat", "conv-a", T0),
        RunChannelPolicy.conversational());

    LiveRunSummary row = only(enumerate(""));

    assertEquals("run-ask-1", row.runId());
    assertEquals("core.free-chat", row.shapeId());
    assertEquals("conv-a", row.conversationId());
    assertEquals(LiveRunSummary.STATE_RUNNING, row.state());
    assertNull(row.park(), "a one-shot pipeline has no control point to park at (3.4)");
    assertNull(row.snapshot(), "and so no stepped state to prime a reattacher with");
    assertEquals(T0, row.startedAtEpochMs());
    assertEquals(0, row.observerCount());
  }

  @Test
  @DisplayName("a parked agent run carries the callId needed to ANSWER its gate")
  void parkedAgentRunCarriesTheActionableHandle() throws Exception {
    SteppedRunChannel run =
        (SteppedRunChannel)
            registry.open(
                new RunId("sess-7"),
                new RunDescriptor("core.agent-run", "conv-a", T0),
                RunChannelPolicy.agent());
    run.setPark(new ParkState(ParkState.Kind.APPROVAL, T0 + 500, "call-42"));
    run.setSnapshotSupplier(() -> new RunStateSnapshot(snapshotFields()));

    LiveRunSummary row = only(enumerate(""));

    assertEquals(LiveRunSummary.STATE_PARKED, row.state());
    assertNotNull(row.park());
    assertEquals("approval", row.park().kind(), "the lowercase wire spelling, not the enum name");
    assertEquals(T0 + 500, row.park().sinceEpochMs());
    assertEquals("call-42", row.park().detail());

    // 6.1's law made concrete: the gate is answerable from the enumeration alone, with no
    // dependence on a `tool_call_pending` frame that a long run's ring may already have evicted.
    assertNotNull(row.snapshot());
    assertEquals(3, row.snapshot().iteration());
    assertEquals("WATCH", row.snapshot().autonomyLevel());
    assertEquals(1, row.snapshot().pendingApprovals().size());
    var gate = row.snapshot().pendingApprovals().get(0);
    assertEquals("call-42", gate.callId());
    assertEquals("core_browse_folders", gate.toolName());
    assertEquals("{\"parent_path\":\"\"}", gate.arguments(), "arguments ride as a JSON STRING");
    assertEquals("low", gate.risk());
    assertEquals("inline_confirm", gate.gateBehavior());
    assertEquals("approval", row.snapshot().park().kind());
  }

  @Test
  @DisplayName("the snapshot projects from the canonical payload, not a hand-written copy of it")
  void snapshotProjectsFromTheCanonicalPayload() throws Exception {
    // Built by the SAME authority the wire and the durable ledger use. If a field is renamed there
    // and not here, this fails — which is the point: the projection must not be a second spelling.
    Map<String, Object> canonical =
        AgentEventPayloads.base(
            new AgentEvent.StateSnapshot(
                3,
                17,
                4,
                9,
                "researcher",
                List.of(
                    new AgentEvent.PendingApproval(
                        "call-42",
                        "core_browse_folders",
                        "{\"parent_path\":\"\"}",
                        "low",
                        "inline_confirm")),
                "WATCH",
                new AgentEvent.ParkSnapshot("approval", T0 + 500, "call-42"),
                io.justsearch.agent.api.TraceContext.none()));

    SteppedRunChannel run =
        (SteppedRunChannel)
            registry.open(
                new RunId("sess-8"),
                new RunDescriptor("core.agent-run", "conv-a", T0),
                RunChannelPolicy.agent());
    run.setSnapshotSupplier(() -> new RunStateSnapshot(canonical));

    var view = only(enumerate("")).snapshot();

    assertNotNull(view, "the canonical payload must project onto the view record with no loss");
    assertEquals(3, view.iteration());
    assertEquals(17, view.budgetRemaining());
    assertEquals(4, view.toolCallsExecuted());
    assertEquals(9, view.messageCount());
    assertEquals("researcher", view.activeAgentId());
    assertEquals("WATCH", view.autonomyLevel());
    assertEquals("call-42", view.pendingApprovals().get(0).callId());
    assertEquals("approval", view.park().kind());
  }

  @Test
  @DisplayName("N runs on ONE conversation are N rows, newest first — never collapsed")
  void concurrentRunsOnOneConversationAreNotCollapsed() throws Exception {
    registry.open(
        new RunId("run-first"),
        new RunDescriptor("core.free-chat", "conv-a", T0),
        RunChannelPolicy.conversational());
    registry.open(
        new RunId("run-second"),
        new RunDescriptor("core.free-chat", "conv-a", T0 + 1_000),
        RunChannelPolicy.conversational());

    List<LiveRunSummary> rows = enumerate("?conversationId=conv-a");

    assertEquals(2, rows.size(), "nothing serializes two dispatches on one conversation (3.5)");
    assertEquals("run-second", rows.get(0).runId(), "newest first");
    assertEquals("run-first", rows.get(1).runId());
  }

  @Test
  @DisplayName("the filters narrow by conversation and by shape")
  void filters() throws Exception {
    registry.open(
        new RunId("run-ask"),
        new RunDescriptor("core.free-chat", "conv-a", T0),
        RunChannelPolicy.conversational());
    registry.open(
        new RunId("sess-agent"),
        new RunDescriptor("core.agent-run", "conv-b", T0 + 10),
        RunChannelPolicy.agent());

    assertEquals(2, enumerate("").size());
    assertEquals("run-ask", only(enumerate("?conversationId=conv-a")).runId());
    assertEquals("sess-agent", only(enumerate("?shapeId=core.agent-run")).runId());
    assertEquals(
        0,
        enumerate("?conversationId=conv-a&shapeId=core.agent-run").size(),
        "the two filters compose as AND");
  }

  @Test
  @DisplayName("a retired run leaves the enumeration — 'live' means live")
  void retiredRunIsNotEnumerated() throws Exception {
    registry.open(
        new RunId("run-done"),
        new RunDescriptor("core.free-chat", "conv-a", T0),
        RunChannelPolicy.conversational());
    assertEquals(1, enumerate("").size());

    registry.retire(new RunId("run-done"), Duration.ofSeconds(60));

    assertEquals(
        0,
        enumerate("").size(),
        "still observable inside its linger, but no longer EXECUTING — the distinction the "
            + "enumeration exists to make");
  }

  @Test
  @DisplayName("updatedAt tracks narrative output; a heartbeat does not make a parked run look busy")
  void updatedAtIsNarrativeOnly() throws Exception {
    var run =
        registry.open(
            new RunId("run-chatty"),
            new RunDescriptor("core.free-chat", "conv-a", T0),
            RunChannelPolicy.conversational());

    assertEquals(T0, only(enumerate("")).updatedAtEpochMs(), "starts at the run's start");

    clock.advanceMs(5_000);
    run.publish(new RunFrame("chunk", Map.of("text", "hello")));
    assertEquals(T0 + 5_000, only(enumerate("")).updatedAtEpochMs());

    clock.advanceMs(15_000);
    run.lifecycle(new RunFrame("heartbeat", Map.of()));
    assertEquals(
        T0 + 5_000,
        only(enumerate("")).updatedAtEpochMs(),
        "a parked run's only write is its heartbeat — counting it as activity is the one lie "
            + "this field is able to tell");
  }

  @Test
  @DisplayName("observerCount is read from the channel's listener set — attach raises it, close drops it")
  void observerCountTracksTheListenerSet() throws Exception {
    var run =
        registry.open(
            new RunId("run-watched"),
            new RunDescriptor("core.free-chat", "conv-a", T0),
            RunChannelPolicy.conversational());

    assertEquals(0, only(enumerate("")).observerCount());

    var first = run.observe(env -> {}, 0).orElseThrow();
    assertEquals(1, only(enumerate("")).observerCount());

    var second = run.observe(env -> {}, 0).orElseThrow();
    assertEquals(2, only(enumerate("")).observerCount(), "two observers on one run are two");

    // The half of §15.4 Q1 that CAN be a CI test: unsubscribing removes the observer, and the
    // enumeration is the read surface that makes it visible. The composition — a dead SOCKET being
    // noticed within one heartbeat interval, so a WATCH run parks — needs a real socket and stays a
    // direct-topology live leg.
    second.unsubscribe();
    assertEquals(1, only(enumerate("")).observerCount());

    first.unsubscribe();
    assertEquals(
        0,
        only(enumerate("")).observerCount(),
        "reaching zero is the precondition the zero-observer park depends on");
  }

  @Test
  @DisplayName("no live runs is an empty list, not an error")
  void emptyIsHonest() throws Exception {
    assertTrue(enumerate("").isEmpty());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────────────────────

  private List<LiveRunSummary> enumerate(String query) throws Exception {
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + RunRoutes.LIVE_PATH + query))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), resp.body());
    return MAPPER.readValue(resp.body(), LiveRunsResponse.class).runs();
  }

  private static LiveRunSummary only(List<LiveRunSummary> rows) {
    assertEquals(1, rows.size(), "expected exactly one row, got " + rows);
    return rows.get(0);
  }

  private static Map<String, Object> snapshotFields() {
    return AgentEventPayloads.base(
        new AgentEvent.StateSnapshot(
            3,
            17,
            4,
            9,
            "researcher",
            List.of(
                new AgentEvent.PendingApproval(
                    "call-42", "core_browse_folders", "{\"parent_path\":\"\"}", "low",
                    "inline_confirm")),
            "WATCH",
            new AgentEvent.ParkSnapshot("approval", T0 + 500, "call-42"),
            io.justsearch.agent.api.TraceContext.none()));
  }

  /** A clock the test advances, so "when did this run last say something" is asserted, not slept. */
  private static final class MutableClock extends Clock {
    private long millis;

    MutableClock(long millis) {
      this.millis = millis;
    }

    void advanceMs(long delta) {
      millis += delta;
    }

    @Override
    public long millis() {
      return millis;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
