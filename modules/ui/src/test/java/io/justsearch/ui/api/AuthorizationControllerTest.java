/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 655 fix pass — coverage for {@code execute: true} (server-side completion of an
 * approved pending using its own stored args) and {@code handlePeekPending} (the point-to-point
 * decision-content fetch that replaced the broadcast's now-removed argsSummary/rationale).
 *
 * <p>Prior to this pass, {@code execute: true} — the code that lets the server complete a
 * mutation on a human's behalf — had zero automated coverage, verified only by manual browser
 * testing.
 */
class AuthorizationControllerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneId.of("UTC"));

  private ConsentCapsuleService capsuleService;
  private PendingAuthorizationStore pendingStore;
  private List<OperationCatalog> catalogs;

  @BeforeEach
  void setUp() {
    capsuleService = new ConsentCapsuleService(FIXED_CLOCK, Duration.ofMinutes(5));
    pendingStore = new PendingAuthorizationStore(FIXED_CLOCK, Duration.ofMinutes(5));
    catalogs = List.of(new AgentToolsOperationCatalog());
  }

  private String createPending(String operationId) {
    return pendingStore.create(
        operationId,
        "{\"paths\":[\"C:/tmp\"]}",
        SourceTier.UNTRUSTED,
        RiskTier.MEDIUM,
        GateBehavior.TYPED_CONFIRM,
        "Confirmation required for operation " + operationId);
  }

  private Context mockContextWithBody(String body) {
    Context ctx = mock(Context.class);
    when(ctx.body()).thenReturn(body);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    return ctx;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> capturedJson(Context ctx) throws Exception {
    ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
    verify(ctx, atLeastOnce()).result(captor.capture());
    byte[] last = captor.getAllValues().get(captor.getAllValues().size() - 1);
    return MAPPER.readValue(last, Map.class);
  }

  @Test
  void approve_withoutExecute_behavesAsBefore_noExecutedKey() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs, FIXED_CLOCK);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"));
    assertFalse(body.containsKey("executed"), "execute omitted must not attempt server-side dispatch");
    verifyNoInteractions(dispatcher);
  }

  @Test
  void approve_withExecuteTrue_dispatchesUsingStoredArgsAndReportsSuccess() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any()))
        .thenReturn(OperationResult.success("Indexed 1 item", Map.of()));
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs, FIXED_CLOCK);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"));
    assertEquals(Boolean.TRUE, body.get("executed"));
    assertEquals(Boolean.TRUE, body.get("executeSuccess"));
    assertEquals("Indexed 1 item", body.get("executeMessage"));

    // Dispatched with the PENDING's own stored args, not anything the caller supplied.
    ArgumentCaptor<String> argsCaptor = ArgumentCaptor.forClass(String.class);
    verify(dispatcher).dispatch(any(), argsCaptor.capture(), any(), any());
    assertEquals("{\"paths\":[\"C:/tmp\"]}", argsCaptor.getValue());
  }

  @Test
  void approve_withExecuteTrue_noDispatcherWired_reportsNotExecutedWithoutThrowing() throws Exception {
    // Legacy/test-wiring constructor — no dispatcher.
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"), "approval itself must still succeed");
    assertEquals(Boolean.FALSE, body.get("executed"));
    assertNotNull(body.get("executeMessage"));
  }

  @Test
  void approve_withExecuteTrue_unresolvableOperationId_reportsNotExecuted() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs, FIXED_CLOCK);
    String pendingId = createPending("core.does-not-exist");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertNotNull(body.get("capsule"));
    assertEquals(Boolean.FALSE, body.get("executed"));
    assertTrue(((String) body.get("executeMessage")).contains("core.does-not-exist"));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void approve_withExecuteTrue_dispatchThrows_reportsFailureButApprovalStands() throws Exception {
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    when(dispatcher.dispatch(any(), any(), any(), any()))
        .thenThrow(new RuntimeException("disk full"));
    var controller =
        new AuthorizationController(
            capsuleService, pendingStore, null, dispatcher, catalogs, FIXED_CLOCK);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\",\"execute\":true}");
    controller.handleApprove(ctx);

    Map<String, Object> body = capturedJson(ctx);
    // The mint/approval already happened before dispatch was attempted — a downstream execution
    // failure doesn't retroactively undo it (there is nothing to "roll back": the pending was
    // already consumed and the capsule already minted).
    assertNotNull(body.get("capsule"));
    assertEquals(Boolean.FALSE, body.get("executed"));
    assertTrue(((String) body.get("executeMessage")).contains("disk full"));
  }

  @Test
  void peekPending_knownId_returnsDecisionContentWithoutConsuming() throws Exception {
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(pendingId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertEquals(pendingId, body.get("pendingId"));
    assertEquals("core.ingest-files", body.get("operationId"));
    assertEquals("{\"paths\":[\"C:/tmp\"]}", body.get("argsSummary"));
    assertEquals("UNTRUSTED", body.get("sourceTier"));
    assertEquals("MEDIUM", body.get("riskTier"));
    assertEquals("TYPED_CONFIRM", body.get("gateBehavior"));

    // requestedBy omitted entirely (not present as a key) when the pending has no MCP client.
    assertFalse(body.containsKey("requestedBy"));

    // Non-mutating: the SAME id can still be approved afterward (peek didn't consume it).
    Context approveCtx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(approveCtx);
    Map<String, Object> approveBody = capturedJson(approveCtx);
    assertNotNull(approveBody.get("capsule"));
  }

  @Test
  void peekPending_withRequestedBy_includesItInResponse() throws Exception {
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId =
        pendingStore.create(
            "core.ingest-files",
            "{\"paths\":[\"C:/tmp\"]}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "Confirmation required",
            "Claude Code");

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(pendingId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    Map<String, Object> body = capturedJson(ctx);
    assertEquals("Claude Code", body.get("requestedBy"));
  }

  @Test
  void peekPending_unknownOrExpiredId_returns404_notArgsContent() throws Exception {
    var controller = new AuthorizationController(capsuleService, pendingStore, null);

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn("pa-does-not-exist");
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    verify(ctx).status(404);
  }
}
