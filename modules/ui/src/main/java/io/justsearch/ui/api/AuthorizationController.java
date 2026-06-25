/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ConsentCapsuleAuthority;
import io.justsearch.agent.api.registry.SourceTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mints {@link io.justsearch.app.services.intent.ConsentCapsuleService consent capsules}
 * on an explicit user-approval gesture — tempdoc 550 Slice A1 (Authorize face).
 *
 * <p>This is the resolution half of the gated-action recovery the spine introduces. When
 * an UNTRUSTED-source Invocation hits a non-AUTO trust gate, the dispatcher refuses with
 * {@code CONFIRMATION_REQUIRED} (HTTP 428) and the action is — pre-A1 — a dead end. With
 * A1, the user reviews the pending action and approves it; the FE calls this endpoint,
 * receives a capsule bound to exactly that {@code (operationId, args)}, and re-dispatches
 * the SAME Invocation with the capsule in its {@code confirmationToken}. The lattice then
 * verifies the capsule and permits the action — for the right reason (proof of user
 * approval), not a fabricated placeholder.
 *
 * <p>Wire: {@code POST /api/authorizations/approve} with body
 * {@code { "operationId": "...", "args": { ... } }} → {@code { "capsule": "<token>" }}.
 * The {@code args} must be byte-identical to what the re-dispatch will send (the capsule
 * binds to a hash of the serialized args).
 *
 * <p><b>FLAGGED for review (550 §Review-package C2):</b> in a loopback single-user app
 * this endpoint <i>is</i> the user-gesture boundary — it trusts that its caller is the
 * real FE acting on a human click. Hardening it against a prompt-injected agent calling
 * it to self-approve (e.g. a same-tab nonce / origin check / human-interaction proof) is
 * an open design point flagged for review, not resolved in this additive slice.
 */
public final class AuthorizationController {

  private static final Logger log = LoggerFactory.getLogger(AuthorizationController.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final ConsentCapsuleAuthority capsuleService;

  /**
   * Tempdoc 550 C3 — nullable. The hardened approve path: the caller presents a
   * {@code pendingId} the backend issued when it gated a dispatch; this controller consumes
   * it and mints a capsule bound to the STORED {@code (operationId, argsJson)}. Because only
   * a real backend gate creates a pending, the approve gesture cannot mint a capsule for an
   * op the user never saw (WA-5). Null in legacy/test wiring → only the deprecated
   * arbitrary-{@code (operationId, args)} path is available.
   */
  private final io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore;

  /**
   * Tempdoc 550 thesis IV — nullable durable allow-always grant store. When the user approves with
   * {@code allowAlways}, the (operationId, sourceTier) is recorded here so future invocations
   * auto-approve at the gate without re-prompting. Null in legacy/test wiring.
   */
  private final io.justsearch.app.services.intent.DurableGrantStore durableGrantStore;

  public AuthorizationController(ConsentCapsuleAuthority capsuleService) {
    this(capsuleService, null, null);
  }

  public AuthorizationController(
      ConsentCapsuleAuthority capsuleService,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore) {
    this(capsuleService, pendingStore, null);
  }

  /** Canonical constructor (tempdoc 550 C3 + thesis IV): pending registry + durable grant store. */
  public AuthorizationController(
      ConsentCapsuleAuthority capsuleService,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingStore,
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore) {
    this.capsuleService = Objects.requireNonNull(capsuleService, "capsuleService");
    this.pendingStore = pendingStore;
    this.durableGrantStore = durableGrantStore;
  }

  /**
   * Handles {@code POST /api/authorizations/approve}.
   *
   * <p>Body: {@code {"pendingId": "..."}}. Consume the backend-created
   * {@link io.justsearch.app.services.intent.PendingAuthorization} and mint a capsule bound
   * to ITS stored {@code (operationId, argsJson)} — the approve caller cannot substitute the
   * op or args. Unknown / expired / already-consumed id → 410 Gone (fail closed).
   *
   * <p>Tempdoc 550 C3/WA-5: there is no arbitrary-{@code (operationId, args)} mint path. A
   * capsule can only be produced by approving a pending the backend created when it actually
   * gated a dispatch, so an in-process agent / prompt-injection cannot self-approve an op the
   * user never saw.
   */
  public void handleApprove(Context ctx) {
    try {
      if (pendingStore == null) {
        ctx.status(400)
            .contentType("application/json")
            .result("{\"error\":\"pendingId approval not available\"}");
        return;
      }
      JsonNode body = MAPPER.readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
      JsonNode pendingNode = body.get("pendingId");
      if (pendingNode == null || !pendingNode.isTextual() || pendingNode.asText().isBlank()) {
        ctx.status(400).contentType("application/json").result("{\"error\":\"missing pendingId\"}");
        return;
      }
      var pending = pendingStore.consume(pendingNode.asText());
      if (pending.isEmpty()) {
        // Unknown, already-consumed, or expired — fail closed. 410 Gone distinguishes
        // "the thing you're approving is no longer pending" from a malformed request.
        ctx.status(410)
            .contentType("application/json")
            .result("{\"error\":\"pending authorization not found or expired\"}");
        return;
      }
      // Tempdoc 550 F3: record the authorized action's source tier so an emergency Global Hard
      // Stop revokes only non-user grants — a user's own TRUSTED approval is not cancelled.
      String capsule =
          capsuleService.mint(
              pending.get().operationId(), pending.get().argsJson(), pending.get().sourceTier());
      // Tempdoc 550 thesis IV: an explicit "allow always" gesture records a durable grant for this
      // (operation, sourceTier), so future invocations auto-approve at the gate without re-prompting.
      boolean allowAlways = body.has("allowAlways") && body.get("allowAlways").asBoolean(false);
      if (allowAlways && durableGrantStore != null) {
        durableGrantStore.grantAllowAlways(pending.get().operationId(), pending.get().sourceTier());
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("capsule", capsule);
      payload.put("allowAlways", allowAlways);
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to mint consent capsule", e);
      ctx.status(400).contentType("application/json").result("{\"error\":\"bad request\"}");
    }
  }

  // ── Durable-grant management surface (tempdoc 560 §28 / 4d) ────────────────────────────────────────
  // GET /api/authorizations/grants — list; POST — grant (operation|family); DELETE — revoke. The
  // loopback-only API makes these inherently operator-local; they are the management surface for the
  // durable allow-always grants the gate honors (the per-op and the wider CapabilityFamily position).

  /** GET /api/authorizations/grants — the operator's current durable grants (operation + family). */
  public void handleListGrants(Context ctx) {
    if (durableGrantStore == null) {
      ctx.json(Map.of("grants", List.of()));
      return;
    }
    List<Map<String, Object>> grants =
        durableGrantStore.snapshot().stream()
            .map(
                g -> {
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("kind", g.kind().name());
                  m.put("target", g.target());
                  m.put("sourceTier", g.sourceTier().name());
                  return m;
                })
            .toList();
    ctx.json(Map.of("grants", grants));
  }

  /** POST /api/authorizations/grants {kind, target, sourceTier} — record a durable allow-always grant. */
  public void handleGrant(Context ctx) {
    GrantRequest req = parseGrantRequest(ctx);
    if (req == null) return; // parseGrantRequest already wrote the error
    if (req.family()) {
      durableGrantStore.grantFamilyAllowAlways(req.target(), req.tier());
    } else {
      durableGrantStore.grantAllowAlways(req.target(), req.tier());
    }
    ctx.json(Map.of("granted", true));
  }

  /** DELETE /api/authorizations/grants {kind, target, sourceTier} — revoke a durable grant. */
  public void handleRevokeGrant(Context ctx) {
    GrantRequest req = parseGrantRequest(ctx);
    if (req == null) return;
    if (req.family()) {
      durableGrantStore.revokeFamily(req.target(), req.tier());
    } else {
      durableGrantStore.revoke(req.target(), req.tier());
    }
    ctx.json(Map.of("revoked", true));
  }

  private record GrantRequest(boolean family, String target, SourceTier tier) {}

  /** Parses + validates {kind, target, sourceTier}; writes a 400 + returns null on any failure. */
  private GrantRequest parseGrantRequest(Context ctx) {
    if (durableGrantStore == null) {
      ctx.status(400).json(Map.of("error", "durable grants unavailable"));
      return null;
    }
    try {
      String raw = ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body();
      var body = MAPPER.readTree(raw);
      String kind = textField(body, "kind");
      String target = textField(body, "target");
      SourceTier tier = parseTier(textField(body, "sourceTier"));
      boolean family = "FAMILY".equalsIgnoreCase(kind);
      if (kind == null || target == null || target.isBlank() || tier == null) {
        ctx.status(400)
            .json(
                Map.of(
                    "error",
                    "kind ('OPERATION'|'FAMILY'), target, and sourceTier"
                        + " ('TRUSTED'|'MEDIUM'|'UNTRUSTED') are required"));
        return null;
      }
      return new GrantRequest(family, target, tier);
    } catch (Exception e) {
      ctx.status(400).json(Map.of("error", "malformed JSON body"));
      return null;
    }
  }

  private static String textField(JsonNode node, String field) {
    var v = node.get(field);
    return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
  }

  private static SourceTier parseTier(String raw) {
    if (raw == null) return null;
    try {
      return SourceTier.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
