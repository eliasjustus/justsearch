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
import io.justsearch.app.services.intent.DurableGrantStore;
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

  /**
   * The error-body branches of {@code handleApprove} (400/410) write via the {@code
   * result(String)} overload (a hand-built JSON literal), not {@code result(byte[])} (the
   * success path's {@code ObjectMapper}-serialized payload) — a distinct overload {@link
   * #capturedJson} does not match. This captures that overload instead.
   */
  private String capturedErrorResult(Context ctx) {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(ctx, atLeastOnce()).result(captor.capture());
    return captor.getValue();
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

  // ── Smoke-round 2026-07-14 HIGH finding (734): the 410-expired approval branch had no
  // regression coverage on either side. Verified fixed at HEAD (AuthorizationHost.decide
  // closes/advances synchronously; approveByPendingId throws on non-2xx; this 410 is the
  // backend half) — these two tests pin it so a future regression fails loudly here instead
  // of only in a live GUI smoke pass.

  @Test
  void approve_unknownPendingId_returns410WithErrorBody_noCapsuleMintedNoDispatch() throws Exception {
    ConsentCapsuleService mockCapsuleService = mock(ConsentCapsuleService.class);
    DurableGrantStore durableGrantStore = mock(DurableGrantStore.class);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            mockCapsuleService, pendingStore, durableGrantStore, dispatcher, catalogs, FIXED_CLOCK);

    Context ctx = mockContextWithBody("{\"pendingId\":\"pa-does-not-exist\"}");
    controller.handleApprove(ctx);

    // If handleApprove ever regressed to returning 200 with an empty/placeholder capsule for
    // an id that doesn't resolve, this assertion fails: it pins the actual status code, not
    // just "some error happened".
    verify(ctx).status(410);
    String body = capturedErrorResult(ctx);
    assertTrue(body.contains("\"error\""), "410 body must carry an error message: " + body);
    assertFalse(body.contains("capsule"), "no capsule minted for an unknown pendingId: " + body);
    verifyNoInteractions(mockCapsuleService);
    verifyNoInteractions(dispatcher);
    verifyNoInteractions(durableGrantStore);
  }

  @Test
  void approve_expiredPendingId_returns410WithErrorBody_noCapsuleMintedNoDispatch() throws Exception {
    MutableClock mutableClock = new MutableClock(Instant.parse("2026-07-02T12:00:00Z"), ZoneId.of("UTC"));
    PendingAuthorizationStore expiringStore =
        new PendingAuthorizationStore(mutableClock, Duration.ofMinutes(5));
    String pendingId =
        expiringStore.create(
            "core.ingest-files",
            "{\"paths\":[\"C:/tmp\"]}",
            SourceTier.UNTRUSTED,
            RiskTier.MEDIUM,
            GateBehavior.TYPED_CONFIRM,
            "Confirmation required for operation core.ingest-files");
    // Advance the SAME clock instance the store consults past its 5-minute TTL — this is the
    // real expiry path (PendingAuthorizationStore#consume's isExpired check), not a stand-in
    // for "unknown id".
    mutableClock.advance(Duration.ofMinutes(6));

    ConsentCapsuleService mockCapsuleService = mock(ConsentCapsuleService.class);
    DurableGrantStore durableGrantStore = mock(DurableGrantStore.class);
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var controller =
        new AuthorizationController(
            mockCapsuleService, expiringStore, durableGrantStore, dispatcher, catalogs, mutableClock);

    Context ctx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(ctx);

    verify(ctx).status(410);
    String body = capturedErrorResult(ctx);
    assertTrue(body.contains("\"error\""), "410 body must carry an error message: " + body);
    assertFalse(body.contains("capsule"), "no capsule minted for an expired pendingId: " + body);
    verifyNoInteractions(mockCapsuleService);
    verifyNoInteractions(dispatcher);
    verifyNoInteractions(durableGrantStore);

    // A retry with the same id also 410s — the ceremony can't be "revived" by re-clicking
    // Approve on the same expired pendingId (the finding's exact undismissable-modal shape).
    Context retryCtx = mockContextWithBody("{\"pendingId\":\"" + pendingId + "\"}");
    controller.handleApprove(retryCtx);
    verify(retryCtx).status(410);
  }

  /** Mutable {@link Clock} so a test can create a pending, then advance time past its TTL. */
  private static final class MutableClock extends Clock {
    private Instant instant;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }

    void advance(Duration d) {
      instant = instant.plus(d);
    }
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
  void peekPending_exposesExpiresAt_soAClientCanSayHowLongTheRequestIsValid() throws Exception {
    // Tempdoc 807 item 3 / sandbox round 13 F3: pendings DO expire (the store's 5-minute TTL) and
    // PendingAuthorization has always carried expiresAt — but no surface put it on the wire. With
    // nothing to read, no client could tell the user how long an approval request is valid, and no
    // round could deterministically induce or verify expiry.
    var controller = new AuthorizationController(capsuleService, pendingStore, null);
    String pendingId = createPending("core.ingest-files");

    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn(pendingId);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.status(anyInt())).thenReturn(ctx);
    controller.handlePeekPending(ctx);

    Map<String, Object> body = capturedJson(ctx);
    // FIXED_CLOCK + the store's 5-minute TTL, serialized ISO-8601 UTC.
    assertEquals("2026-07-02T12:05:00Z", body.get("expiresAt"));
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
