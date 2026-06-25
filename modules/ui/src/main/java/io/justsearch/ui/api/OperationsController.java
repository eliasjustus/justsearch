/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.registry.OperationInvocationRequest;
import io.justsearch.app.api.registry.OperationInvocationResponse;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP handler for {@code POST /api/operations/{id}/invoke} (slice 3a-1-2).
 *
 * <p>Routes Operation invocations from external clients (FE ActionButton, agent loops
 * once they migrate) to the substrate's {@link OperationDispatcher}. The controller
 * resolves the {@link Operation} by id across the registered catalogs, serializes the
 * caller-supplied {@code args} JSON to the string form the dispatcher expects, dispatches,
 * and maps the resulting {@link OperationResult} to wire JSON.
 *
 * <p>Wire shape per slice 3a-1-2 §A.4:
 *
 * <ul>
 *   <li>Request body: {@link OperationInvocationRequest} with {@code args},
 *       {@code idempotencyKey} (deferred per §A.5), {@code confirmationToken} (FE-trust
 *       in V1; backend enforcement is a follow-up).
 *   <li>Success response (HTTP 200): {@link OperationInvocationResponse} with
 *       {@code success=true} carrying handler {@code message} + optional
 *       {@code executionId} + {@code structuredData}.
 *   <li>Operation not found (HTTP 404): {@code errorClass=OPERATION_NOT_FOUND}.
 *   <li>Bad request (HTTP 400): {@code errorClass=BAD_REQUEST}. Surfaces JSON parse
 *       failures + missing path id.
 *   <li>Handler-returned failure (HTTP 200, body {@code success=false}): the handler
 *       successfully ran and returned a failure result; {@code errorClass=HANDLER_FAILURE}.
 *       Wire status code is 200 because the dispatch completed; the failure is
 *       application-level.
 *   <li>Handler threw uncaught (HTTP 500): {@code errorClass=HANDLER_ERROR}.
 * </ul>
 *
 * <p>HIGH-risk confirmation enforcement is the FE ActionButton's responsibility in V1
 * (per slice 3a-1-2 §A.7); the controller forwards {@code confirmationToken} unchanged
 * to the dispatcher (which currently ignores it). Backend defense-in-depth lands in a
 * future slice.
 */
public final class OperationsController {

  private static final Logger log = LoggerFactory.getLogger(OperationsController.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final List<OperationCatalog> catalogs;
  private final OperationDispatcher dispatcher;
  private final Clock clock;

  /**
   * Tempdoc 550 C3 — nullable. When present, a non-AUTO gate (428) records a
   * {@link io.justsearch.app.services.intent.PendingAuthorization} and the 428 body carries
   * its {@code pendingId}; the FE approves by that id (no arbitrary-op mint). Null in
   * legacy/test wiring → the 428 omits {@code pendingId} (prior behavior).
   */
  private final io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore;

  public OperationsController(List<OperationCatalog> catalogs, OperationDispatcher dispatcher) {
    this(catalogs, dispatcher, Clock.systemUTC(), null);
  }

  /** Test-friendly constructor allowing clock injection for {@link InvocationProvenance}. */
  public OperationsController(
      List<OperationCatalog> catalogs, OperationDispatcher dispatcher, Clock clock) {
    this(catalogs, dispatcher, clock, null);
  }

  /** Canonical constructor (tempdoc 550 C3): wires the pending-authorization registry. */
  public OperationsController(
      List<OperationCatalog> catalogs,
      OperationDispatcher dispatcher,
      Clock clock,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore) {
    this.catalogs = List.copyOf(Objects.requireNonNull(catalogs, "catalogs"));
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.pendingStore = pendingStore;
  }

  /** Handles {@code POST /api/operations/{id}/invoke}. */
  public void handleInvoke(Context ctx) {
    String idValue = ctx.pathParam("id");
    if (idValue == null || idValue.isBlank()) {
      writeError(ctx, 400, "Missing operation id in path", "BAD_REQUEST");
      return;
    }

    Optional<Operation> resolved = resolveOperation(idValue);
    if (resolved.isEmpty()) {
      // 422 (not 404) — LocalApiServer registers a global `app.error(404)` handler
      // (LocalApiServer.java:606-613) that overwrites response bodies on 404 with the
      // canonical ApiErrorHandler shape, swallowing our OPERATION_NOT_FOUND errorClass
      // tag. 422 (Unprocessable Entity) is semantically correct here ("request is
      // well-formed but references a non-existent resource") and isn't intercepted
      // by the global handler. Surfaced via live-stack smoke 2026-05-06.
      writeError(ctx, 422, "Operation not found: " + idValue, "OPERATION_NOT_FOUND");
      return;
    }

    OperationInvocationRequest request;
    try {
      request = parseRequest(ctx);
    } catch (Exception e) {
      writeError(
          ctx,
          400,
          "Invalid invocation request body: " + e.getMessage(),
          "BAD_REQUEST");
      return;
    }

    String argumentsJson;
    try {
      argumentsJson = MAPPER.writeValueAsString(request.args());
    } catch (Exception e) {
      writeError(
          ctx, 400, "Failed to serialize invocation args: " + e.getMessage(), "BAD_REQUEST");
      return;
    }

    Operation op = resolved.get();
    OperationResult result;
    // Slice 490 §4.B + slice 489 §17.5: HTTP entry-point sets the typed invocation
    // provenance. The transport hint comes from the `X-JustSearch-Transport` header
    // (set by the FE intent layer per slice 489 §9); BUTTON is the default fallback
    // when the header is absent or malformed — matching the prior behavior for
    // callers that don't yet pass a hint. The OperationExecutorImpl.validateProvenance
    // whitelist gates trust-tier mismatches (e.g., a TRUSTED_PLUGIN caller cannot
    // claim BUTTON / URL_BAR — only SYSTEM_INTERNAL / AGENT_LOOP / PLUGIN_EMITTED).
    InvocationProvenance provenance = resolveProvenance(ctx);
    // Slice 487 §4.4: thread the body's confirmationToken (when present) to the
    // dispatcher's 4-arg overload so the trust lattice sees it. Without this, the
    // lattice would treat every HTTP invocation as token-absent and throw
    // ConfirmationRequiredException on every non-AUTO gate (UNTRUSTED × MEDIUM/HIGH,
    // MEDIUM × MEDIUM/HIGH, TRUSTED × HIGH).
    Optional<String> confirmationToken =
        request.confirmationToken() == null
            ? Optional.empty()
            : Optional.of(request.confirmationToken());
    try {
      result = dispatcher.dispatch(op, argumentsJson, provenance, confirmationToken);
    } catch (io.justsearch.agent.api.registry.ConfirmationRequiredException e) {
      // Slice 487 §4.4: the lattice produced a non-AUTO gate and no token was supplied.
      // Surface the gate behavior + the destination's ConfirmStrategy so the FE can
      // render trust-aware elicitation UX and re-invoke with the token.
      writeConfirmationRequired(ctx, op, e, argumentsJson);
      return;
    } catch (io.justsearch.agent.api.registry.TrustGateDeniedException e) {
      writeError(
          ctx,
          403,
          "Trust gate denied operation " + op.id().value() + ": " + e.getMessage(),
          io.justsearch.app.api.ApiErrorCode.TRUST_DENIED.name());
      return;
    } catch (RuntimeException e) {
      log.warn("Operation handler threw for id={}", op.id().value(), e);
      writeError(
          ctx,
          500,
          "Operation handler threw: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "HANDLER_ERROR");
      return;
    }

    writeResponse(ctx, 200, OperationInvocationResponse.fromResult(result));
  }

  /**
   * Map the {@code X-JustSearch-Transport} header value to an {@link InvocationProvenance}.
   *
   * <p>Per slice 489 §17.5 resolution: the FE supplies the transport hint; the backend
   * constructs the trusted provenance shape via the factories on
   * {@link InvocationProvenance}. Unknown / blank header values fall back to
   * {@link InvocationProvenance#uiButton(java.time.Instant)} (the prior default), which
   * preserves backwards compatibility for callers that don't yet pass the header.
   *
   * <p>Header values are case-insensitive {@link TransportTag} names. Values that don't
   * resolve to a known TransportTag are silently downgraded to BUTTON; the dispatcher's
   * {@code validateProvenance} still gates trust-tier integrity, so a malformed header
   * cannot escalate privileges. A WARN log surfaces the malformed-header case for
   * operator triage.
   */
  private InvocationProvenance resolveProvenance(Context ctx) {
    String header = ctx.header("X-JustSearch-Transport");
    java.time.Instant now = clock.instant();
    if (header == null || header.isBlank()) {
      return InvocationProvenance.uiButton(now);
    }
    TransportTag tag;
    try {
      tag = TransportTag.valueOf(header.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      log.warn(
          "Unknown X-JustSearch-Transport header value '{}'; falling back to BUTTON",
          header);
      return InvocationProvenance.uiButton(now);
    }
    return InvocationProvenance.fromTransport(tag, Optional.empty(), now);
  }

  /** Handles {@code POST /api/operations/{id}/undo}. */
  public void handleUndo(Context ctx) {
    String idValue = ctx.pathParam("id");
    if (idValue == null || idValue.isBlank()) {
      writeError(ctx, 400, "Missing operation id in path", "BAD_REQUEST");
      return;
    }

    Optional<Operation> resolved = resolveOperation(idValue);
    if (resolved.isEmpty()) {
      writeError(ctx, 422, "Operation not found: " + idValue, "OPERATION_NOT_FOUND");
      return;
    }

    String executionId;
    try {
      String body = ctx.body();
      if (body == null || body.isBlank()) {
        writeError(ctx, 400, "Missing request body with executionId", "BAD_REQUEST");
        return;
      }
      var parsed = MAPPER.readTree(body);
      var eidNode = parsed.get("executionId");
      if (eidNode == null || !eidNode.isTextual() || eidNode.asText().isBlank()) {
        writeError(ctx, 400, "executionId is required and must be non-blank", "BAD_REQUEST");
        return;
      }
      executionId = eidNode.asText();
    } catch (Exception e) {
      writeError(ctx, 400, "Invalid undo request body: " + e.getMessage(), "BAD_REQUEST");
      return;
    }

    Operation op = resolved.get();
    OperationResult result;
    try {
      result = dispatcher.undo(op, executionId);
    } catch (RuntimeException e) {
      log.warn("Undo handler threw for id={}, executionId={}", op.id().value(), executionId, e);
      writeError(ctx, 500, "Undo handler threw: "
          + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
          "HANDLER_ERROR");
      return;
    }

    writeResponse(ctx, 200, OperationInvocationResponse.fromResult(result));
  }

  private Optional<Operation> resolveOperation(String idValue) {
    for (OperationCatalog catalog : catalogs) {
      // 543-fwd Fix F — resolve by agent wire-name (e.g. "core_file_operations") OR
      // canonical op-id. The "undo the AI" affordance journals the agent tool name, so
      // resolving by op-id only returned 422; findByWireName transliterates and falls
      // back to findByIdValue, matching both forms (consistent with the invoke path).
      Optional<Operation> hit = catalog.findByWireName(idValue);
      if (hit.isPresent()) {
        return hit;
      }
    }
    return Optional.empty();
  }

  private OperationInvocationRequest parseRequest(Context ctx) throws Exception {
    String body = ctx.body();
    if (body == null || body.isBlank()) {
      // Empty body is valid; treated as zero-args invocation.
      return new OperationInvocationRequest(null, null, null);
    }
    return MAPPER.readValue(body, OperationInvocationRequest.class);
  }

  private void writeResponse(Context ctx, int status, OperationInvocationResponse response) {
    try {
      byte[] body = MAPPER.writeValueAsBytes(response);
      ctx.status(status).contentType("application/json").result(body);
    } catch (Exception e) {
      log.error("Failed to serialize OperationInvocationResponse", e);
      ctx.status(500).contentType("application/json").result("{\"success\":false,\"errorClass\":\"SERIALIZATION_ERROR\"}");
    }
  }

  private void writeError(Context ctx, int status, String message, String errorClass) {
    writeResponse(ctx, status, OperationInvocationResponse.error(message, errorClass));
  }

  /**
   * Slice 487 §4.4: surface a non-AUTO gate decision as a typed HTTP response so
   * the FE can render trust-aware elicitation UX and re-invoke with a token.
   *
   * <p>HTTP status: 428 Precondition Required (RFC 6585). Body carries:
   *
   * <ul>
   *   <li>{@code errorClass}: {@code CONFIRMATION_REQUIRED}.
   *   <li>{@code gateBehavior}: {@code INLINE_CONFIRM} or {@code TYPED_CONFIRM}.
   *   <li>{@code sourceTier}: the lattice's source-side input
   *       (TRUSTED / MEDIUM / UNTRUSTED) so the FE can format trust-aware copy.
   *   <li>{@code confirmStrategy}: the destination operation's declared
   *       {@link io.justsearch.agent.api.registry.ConfirmStrategy} variant name
   *       (None / Inline / Typed) — describes the mechanism.
   *   <li>{@code operationId}: which operation the gate applies to.
   * </ul>
   */
  private void writeConfirmationRequired(
      Context ctx,
      Operation op,
      io.justsearch.agent.api.registry.ConfirmationRequiredException e,
      String argumentsJson) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("success", false);
    body.put("errorClass", io.justsearch.app.api.ApiErrorCode.CONFIRMATION_REQUIRED.name());
    body.put("message", e.getMessage());
    body.put("gateBehavior", e.gateBehavior().name());
    body.put("sourceTier", e.sourceTier().name());
    body.put("confirmStrategy", confirmStrategyName(e.declaredStrategy()));
    body.put("operationId", op.id().value());
    // Tempdoc 550 P1: surface the decision context the ceremony should show — the op's risk,
    // whether the action is reversible (undo-supported), and a short args summary — all already
    // known at gate time. Lets the prompt say "Reindex everything? (HIGH risk, can't be undone)"
    // instead of just the operation id.
    body.put("riskTier", op.policy().risk().name());
    body.put("undoSupported", op.policy().undoSupported());
    body.put("argsSummary", summarizeArgs(argumentsJson));
    // Tempdoc 550 C3: register a pending authorization and hand the FE its id. The FE
    // approves by this id (POST /api/authorizations/approve {pendingId}); the capsule is
    // then minted against the STORED (operationId, argsJson), so the approve gesture cannot
    // substitute a different op/args. Closes the "approve an un-gated op" hole (WA-5).
    if (pendingStore != null) {
      String pendingId =
          pendingStore.create(
              op.id().value(),
              argumentsJson,
              e.sourceTier(),
              op.policy().risk(),
              e.gateBehavior(),
              e.getMessage());
      body.put("pendingId", pendingId);
    }
    try {
      byte[] payload = MAPPER.writeValueAsBytes(body);
      ctx.status(428).contentType("application/json").result(payload);
    } catch (tools.jackson.core.JacksonException ser) {
      log.error("Failed to serialize confirmation-required response", ser);
      ctx.status(428)
          .contentType("application/json")
          .result("{\"success\":false,\"errorClass\":\"CONFIRMATION_REQUIRED\"}");
    }
  }

  /**
   * A short summary of the invocation args for the approval ceremony — the raw JSON, truncated.
   * Empty/{@code "{}"} args yield {@code ""} (nothing to show).
   *
   * <p><b>Privacy boundary (tempdoc 550 F3, deliberate).</b> This rides on the 428 — a
   * <i>transient</i> consent surface shown to the human who is deciding the action right now, who
   * has a legitimate need to see WHAT they are approving (e.g. which path is being removed).
   * It is NOT logging: the action ledger / {@link io.justsearch.app.observability.operations.OperationHistoryEntry}
   * still omit args, and nothing here is persisted. The 444b "never dump raw arguments without
   * consulting the audit policy" posture governs <i>logging/persistence</i> — which this respects —
   * not the live consent prompt. Bounded to 200 chars to cap exposure.
   */
  private static String summarizeArgs(String argumentsJson) {
    if (argumentsJson == null) {
      return "";
    }
    String trimmed = argumentsJson.trim();
    if (trimmed.isEmpty() || "{}".equals(trimmed)) {
      return "";
    }
    int max = 200;
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
  }

  /** Lowercase variant name for the wire payload — pattern-match over the sealed permits. */
  private static String confirmStrategyName(
      io.justsearch.agent.api.registry.ConfirmStrategy strategy) {
    return switch (strategy) {
      case io.justsearch.agent.api.registry.ConfirmStrategy.None ignored -> "None";
      case io.justsearch.agent.api.registry.ConfirmStrategy.Inline ignored -> "Inline";
      case io.justsearch.agent.api.registry.ConfirmStrategy.Typed ignored -> "Typed";
    };
  }
}
