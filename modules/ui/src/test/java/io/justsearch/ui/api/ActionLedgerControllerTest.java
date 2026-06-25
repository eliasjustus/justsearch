package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.observability.ledger.ActionEvent;
import io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry;
import io.justsearch.app.observability.navigation.NavigationHistoryStore;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 561 P-B1 — the {@code GET /api/action-ledger} session/originator filter that makes the
 * one ledger a governed, session-scoped projection (the agent History view's source). Without the
 * filter every consumer would re-derive its slice client-side; with it, History cannot diverge
 * from the live thread because both project the same record.
 */
@DisplayName("ActionLedgerController — P-B1 correlationId/originator projection filter")
final class ActionLedgerControllerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static ActionEvent op(String id, String originator, String transport, String corr) {
    return new ActionEvent.Operation(
        id,
        Instant.parse("2026-05-30T00:00:00Z"),
        originator,
        transport,
        "core.search-index",
        "SUCCESS",
        Optional.empty(),
        corr == null ? Optional.empty() : Optional.of(corr));
  }

  private ActionLedgerController wiredController(ActionLedgerChangeRegistry changes) {
    return new ActionLedgerController(
        new OperationHistoryStore(), new NavigationHistoryStore(), null, changes, Clock.systemUTC());
  }

  /** Captures the byte[] the controller writes via {@code ctx.result(byte[])}. */
  private JsonNode invokeGet(
      ActionLedgerController controller, String correlationId, String originator) {
    Context ctx = mock(Context.class);
    when(ctx.queryParam("correlationId")).thenReturn(correlationId);
    when(ctx.queryParam("originator")).thenReturn(originator);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    AtomicReference<byte[]> captured = new AtomicReference<>();
    doAnswer(
            inv -> {
              captured.set(inv.getArgument(0, byte[].class));
              return ctx;
            })
        .when(ctx)
        .result(any(byte[].class));
    controller.handleGet(ctx);
    try {
      return MAPPER.readTree(captured.get());
    } catch (Exception e) {
      throw new IllegalStateException("could not parse ledger response", e);
    }
  }

  @Test
  @DisplayName("correlationId + originator=agent yields only that session's agent rows")
  void filtersToSessionAndAgent() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));
    changes.broadcastActionEvent(op("op-b", "agent", "AGENT_LOOP", "sess-B"));
    changes.broadcastActionEvent(op("op-u", "user", "BUTTON", "sess-A"));

    JsonNode body = invokeGet(wiredController(changes), "sess-A", "agent");
    JsonNode entries = body.get("entries");

    assertEquals(1, entries.size(), "only the sess-A agent row survives both filters");
    assertEquals("op-a", entries.get(0).get("id").asString());
    assertEquals("sess-A", entries.get(0).get("correlationId").asString());
  }

  @Test
  @DisplayName("no filter params returns the full ledger (back-compat)")
  void noFilterReturnsAll() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));
    changes.broadcastActionEvent(op("op-b", "agent", "AGENT_LOOP", "sess-B"));

    JsonNode body = invokeGet(wiredController(changes), null, null);
    assertEquals(2, body.get("entries").size(), "unfiltered request keeps every row");
  }

  @Test
  @DisplayName("a correlationId with no matching rows yields an empty (not absent) entries array")
  void unmatchedCorrelationYieldsEmpty() {
    ActionLedgerChangeRegistry changes = new ActionLedgerChangeRegistry();
    changes.broadcastActionEvent(op("op-a", "agent", "AGENT_LOOP", "sess-A"));

    JsonNode body = invokeGet(wiredController(changes), "sess-NONE", "agent");
    JsonNode entries = body.get("entries");
    assertEquals(List.of(), MAPPER.convertValue(entries, List.class));
  }
}
