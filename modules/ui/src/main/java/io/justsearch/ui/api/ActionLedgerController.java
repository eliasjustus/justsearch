/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.ledger.ActionLedgerProjection;
import io.justsearch.app.observability.navigation.NavigationHistoryEntry;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Outcome face's unified read-view — tempdoc 550 Slice C1 (backend half).
 *
 * <p>550's Outcome face says the receipt, activity timeline, undo, and trust-audit should
 * all be <i>read-views over one ledger</i>, not separate stores. Per the federated-ledger
 * decision (D1), the authoritative cross-boundary records stay in their per-kind backend
 * stores ({@link OperationHistoryStore} + {@link NavigationHistoryStore}); this endpoint
 * is the unified <i>projection</i> over them — one chronological, attributed stream.
 *
 * <p>This is the backend half of C1. Re-pointing the FE Effect Journal / receipt / undo
 * onto this view (and folding FE-local Effects in as a client-side projection) is the
 * remaining FE cutover flagged in 550 §Review-package C1.
 *
 * <p>Attribution: every entry carries a coarse {@code originator} ∈
 * {@code user | agent | system}, derived from the source transport — the axis "show me
 * everything the agent did this session" / the trust-audit reads. (UNTRUSTED-tier
 * transports — LLM emission, agent loop, MCP — map to {@code agent}; system-internal to
 * {@code system}; direct user transports to {@code user}.)
 *
 * <p>Wire: {@code GET /api/action-ledger} →
 * {@code { entries: [ { kind, occurredAt, originator, transport, ... } ] }}, oldest
 * first (most recent last), merged across both stores.
 */
public final class ActionLedgerController {

  private static final Logger log = LoggerFactory.getLogger(ActionLedgerController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private final OperationHistoryStore operationHistory;
  private final NavigationHistoryStore navigationHistory;
  // Tempdoc 550 Outcome face: the gate-decision ledger sibling. Nullable for legacy/test
  // wiring; when present, trust-gate firings (GATED / DENIED / APPROVED) are projected into
  // the ledger so a gated/denied action — which leaves no OperationHistoryEntry (it throws
  // before completion) — is still a visible, attributed row, and the 538 trust-firing audit
  // is a read-view of this one ledger.
  private final io.justsearch.app.observability.operations.AuthorizationOutcomeStore
      authorizationOutcome;
  // Tempdoc 550 G3/G4/G5: the unified live change-stream. Nullable for legacy/test wiring;
  // when present, GET /api/action-ledger/stream is a live read-view of the one ledger.
  private final ActionLedgerChangeRegistry changes;
  private final ScheduledExecutorService heartbeatScheduler;
  private final Clock clock;

  private static final long HEARTBEAT_SECONDS = StreamLivenessWindows.STREAM_HEARTBEAT_INTERVAL_SECONDS;

  public ActionLedgerController(
      OperationHistoryStore operationHistory, NavigationHistoryStore navigationHistory) {
    this(operationHistory, navigationHistory, null);
  }

  public ActionLedgerController(
      OperationHistoryStore operationHistory,
      NavigationHistoryStore navigationHistory,
      io.justsearch.app.observability.operations.AuthorizationOutcomeStore authorizationOutcome) {
    this(operationHistory, navigationHistory, authorizationOutcome, null, Clock.systemUTC());
  }

  /** Canonical constructor (tempdoc 550): gate-decision store + unified change-stream. */
  public ActionLedgerController(
      OperationHistoryStore operationHistory,
      NavigationHistoryStore navigationHistory,
      io.justsearch.app.observability.operations.AuthorizationOutcomeStore authorizationOutcome,
      ActionLedgerChangeRegistry changes,
      Clock clock) {
    this.operationHistory = Objects.requireNonNull(operationHistory, "operationHistory");
    this.navigationHistory = Objects.requireNonNull(navigationHistory, "navigationHistory");
    this.authorizationOutcome = authorizationOutcome;
    this.changes = changes;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.heartbeatScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "action-ledger-heartbeat");
              t.setDaemon(true);
              return t;
            });
  }

  /**
   * The current action-event log, oldest-first. Tempdoc 550 thesis I: when the change registry is
   * wired (production), read the ONE {@link ActionEventStore} it fans every event into — the action
   * ledger no longer re-projects the per-kind stores. Falls back to projecting the per-kind stores
   * only in legacy/test wiring where no change registry is present.
   */
  private List<ActionEvent> currentEvents() {
    if (changes != null) {
      return changes.store().recent();
    }
    List<ActionEvent> entries = new ArrayList<>();
    for (OperationHistoryEntry op : operationHistory.recent()) {
      entries.add(ActionLedgerProjection.projectOperation(op));
    }
    for (NavigationHistoryEntry nav : navigationHistory.recent()) {
      entries.add(ActionLedgerProjection.projectNavigation(nav));
    }
    if (authorizationOutcome != null) {
      for (var gate : authorizationOutcome.recent()) {
        entries.add(ActionLedgerProjection.projectGate(gate));
      }
    }
    return entries;
  }

  /**
   * Handles {@code POST /api/action-ledger/events} (tempdoc 550 thesis I, process-spanning): the FE
   * ingests a local Effect into the ONE authoritative log, so the receipt/timeline is one log
   * rather than a read-time client merge of the backend ledger + the FE journal. Body:
   * {@code { id, effectKind, originator?, subject?, occurredAt? }}.
   */
  public void handlePostEvent(Context ctx) {
    if (changes == null) {
      ctx.status(503).contentType("application/json").result("{\"error\":\"ledger stream unwired\"}");
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
      String id = asString(body.get("id"));
      String effectKind = asString(body.get("effectKind"));
      if (id == null || id.isBlank() || effectKind == null || effectKind.isBlank()) {
        ctx.status(400).contentType("application/json").result("{\"error\":\"id + effectKind required\"}");
        return;
      }
      String originator = asString(body.get("originator"));
      if (originator == null || originator.isBlank()) {
        originator = "user";
      }
      String subject = asString(body.get("subject"));
      String occurredRaw = asString(body.get("occurredAt"));
      Instant occurredAt;
      try {
        occurredAt = occurredRaw == null ? clock.instant() : Instant.parse(occurredRaw);
      } catch (RuntimeException badTime) {
        occurredAt = clock.instant();
      }
      changes.broadcastActionEvent(
          new ActionEvent.Effect(
              id, occurredAt, originator, "FE_EFFECT", effectKind, subject == null ? "" : subject));
      ctx.status(202).contentType("application/json").result("{}");
    } catch (Exception e) {
      ctx.status(400).contentType("application/json").result("{\"error\":\"malformed effect\"}");
    }
  }

  private static String asString(Object o) {
    return o == null ? null : o.toString();
  }

  /**
   * Handles {@code GET /api/action-ledger}.
   *
   * <p>Tempdoc 561 P-B1: two optional query filters make this one ledger a session-scoped
   * <em>governed projection</em> rather than forcing consumers to re-derive the slice client-side.
   * {@code ?correlationId=<id>} keeps only rows carrying that loop/session join key (the agent
   * loop stamps its {@code sessionId} there, P-A1); {@code ?originator=<user|agent|system>} keeps
   * only that attribution. The agent History view (P-B1) requests
   * {@code ?correlationId=<session>&originator=agent}, so its rows cannot diverge from the live
   * thread — both are projections of this single record, and "empty History while the record is
   * non-empty" becomes unrepresentable.
   */
  public void handleGet(Context ctx) {
    try {
      String correlationId = ctx.queryParam("correlationId");
      String originator = ctx.queryParam("originator");
      List<ActionEvent> entries = currentEvents();
      if (correlationId != null && !correlationId.isBlank()) {
        entries.removeIf(e -> !correlationId.equals(e.correlationId().orElse(null)));
      }
      if (originator != null && !originator.isBlank()) {
        entries.removeIf(e -> !originator.equals(e.originator()));
      }
      entries.sort(Comparator.comparing(ActionEvent::occurredAt));

      // Serialize through the one projection (the same the live /stream uses), so snapshot rows
      // and UPDATE rows are byte-identical in shape.
      List<Map<String, Object>> wire = new ArrayList<>(entries.size());
      for (ActionEvent e : entries) {
        wire.add(ActionLedgerProjection.toWireRow(e));
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", wire);
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize action ledger", e);
      throw new IllegalStateException("Action-ledger serialization failed", e);
    }
  }

  /**
   * Handles SSE {@code GET /api/action-ledger/stream} (tempdoc 550 G3/G4/G5).
   *
   * <p>The live read-view: a snapshot lifecycle frame carries the current {@code entries}; UPDATE
   * frames carry each new row (operation / navigation / gate) the moment its source fires,
   * projected through the same {@link ActionLedgerProjection} as the snapshot. The receipt,
   * timeline, undo, and trust-audit all render from this one stream. Requires the change
   * registry; throws if this controller was built without it (legacy/test wiring).
   */
  public void handleStream(SseClient sseClient) {
    if (changes == null) {
      throw new IllegalStateException(
          "ActionLedgerController has no change registry — stream wiring missing");
    }
    SseEnvelopeWriter.attach(
        sseClient,
        changes.channel(),
        this::snapshotExtras,
        clock,
        heartbeatScheduler,
        HEARTBEAT_SECONDS);
  }

  /** The snapshot payload for a newly-attached stream client: the current ledger rows. */
  private Map<String, Object> snapshotExtras() {
    List<ActionEvent> entries = currentEvents();
    entries.sort(Comparator.comparing(ActionEvent::occurredAt));
    List<Map<String, Object>> wire = new ArrayList<>(entries.size());
    for (ActionEvent e : entries) {
      wire.add(ActionLedgerProjection.toWireRow(e));
    }
    return Map.of("entries", wire);
  }

  /** Stops the heartbeat scheduler. Call on shutdown. */
  public void shutdown() {
    heartbeatScheduler.shutdownNow();
  }
}
