/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.services.intent.IntentGateEvaluator;
import io.justsearch.app.services.registry.preview.AvailabilityEvaluator;
import io.justsearch.app.services.registry.preview.ConditionAvailabilityProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Preview face of the agent-action lifecycle spine — tempdoc 550 Slice F2.
 *
 * <p>Answers, before an action runs: what is it (id), what does it cost (risk tier), and
 * <b>can it run right now</b> — the last computed by actually EVALUATING the operation's
 * {@code availability} expression against live condition state. Pre-F2, {@code
 * Operation.availability} was a dead channel (only serialized for display by {@code
 * UIOperationEmitter}, never evaluated; 550's diagnosis). This is the first reader that
 * evaluates it.
 *
 * <p>(Named to avoid colliding with the unrelated document-text {@code PreviewController}
 * — {@code /api/preview}.)
 *
 * <p>Structural-first (550 D4): fully computable with the Brain offline.
 *
 * <p>Wire: {@code GET /api/operations/{id}/preview} →
 * {@code { operationId, riskTier, availableNow, availabilityKind, transport, sourceTier,
 * gateBehavior }}, or HTTP 404 if the operation id is unknown.
 *
 * <p>Tempdoc 550 S2 + thesis III: the preview also PREDICTS the trust gate the operation would
 * hit — {@code gateBehavior} ∈ {AUTO, INLINE_CONFIRM, TYPED_CONFIRM, DENY} — by READING the one
 * {@link IntentGateEvaluator} the dispatcher enforces with (the SAME instance), for a SourceTier
 * derived from a caller-supplied {@code ?transport=} (default {@code LLM_EMISSION} — the agent
 * path, the interesting non-user case). Because preview and enforcement share one evaluator, the
 * prediction cannot drift from what the dispatcher does (the F1 class is structurally gone). This
 * is a STRUCTURAL prediction: it has no args/token, so it does NOT evaluate the args-bound consent
 * capsule (that stays enforcement-only). It is the read-side an agent plan-preview consumes to show
 * "this step would auto-run / need your confirmation / be denied."
 *
 * <p>Most core operations declare {@code Always} availability today (the catalog does not
 * yet gate operations on conditions — 550 §Build status), so {@code availableNow} is
 * {@code true} for them; the {@code ConditionMatches} branch is exercised by
 * {@link AvailabilityEvaluator} + {@link ConditionAvailabilityProbe} unit tests and lights
 * up here as operations begin declaring conditional availability.
 */
public final class OperationPreviewController {

  private static final Logger log = LoggerFactory.getLogger(OperationPreviewController.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Default transport for the gate prediction when the caller supplies none. */
  private static final TransportTag DEFAULT_TRANSPORT = TransportTag.LLM_EMISSION;

  private final List<OperationCatalog> catalogs;
  private final ConditionAvailabilityProbe probe;
  // Tempdoc 550 thesis III: the ONE intent-gate computation, the SAME instance the enforcement
  // chokepoint (OperationExecutorImpl) uses. The preview READS the verdict the dispatcher enforces
  // — it never recomputes the source tier / lattice / hard-stop — so the prediction cannot drift
  // from enforcement (the F1 class becomes structurally impossible). The verdict already reflects
  // the live Global Hard Stop, so no separate hard-stop wiring is needed here (F1 subsumed).
  private final IntentGateEvaluator intentGateEvaluator;

  /** Canonical constructor (tempdoc 550 thesis III): the shared intent-gate evaluator. */
  public OperationPreviewController(
      List<OperationCatalog> catalogs,
      ConditionAvailabilityProbe probe,
      IntentGateEvaluator intentGateEvaluator) {
    this.catalogs = List.copyOf(Objects.requireNonNull(catalogs, "catalogs"));
    this.probe = Objects.requireNonNull(probe, "probe");
    this.intentGateEvaluator = Objects.requireNonNull(intentGateEvaluator, "intentGateEvaluator");
  }

  /** Handles {@code GET /api/operations/{id}/preview}. */
  public void handlePreview(Context ctx) {
    String idValue = ctx.pathParam("id");
    Optional<Operation> found = resolve(idValue);
    if (found.isEmpty()) {
      ctx.status(404)
          .contentType("application/json")
          .result("{\"error\":\"unknown operation\"}");
      return;
    }
    Operation op = found.get();
    // availability().expression() is Optional — an absent expression means no
    // state-dependent gating, i.e. Always available.
    boolean availableNow =
        op.availability()
            .expression()
            .map(expr -> AvailabilityEvaluator.evaluate(expr, probe.asPredicate()))
            .orElse(true);
    String availabilityKind =
        op.availability().expression().map(e -> e.getClass().getSimpleName()).orElse("Always");
    // Tempdoc 550 thesis III: READ the one verdict (do NOT recompute). The caller picks the
    // transport to ask "if THIS source invoked it, what gate?" (default LLM_EMISSION = the agent
    // path). This is the STRUCTURAL prediction — source tier + the (hard-stop-adjusted) lattice
    // gate the dispatcher would enforce. The args-bound consent-capsule check is intentionally NOT
    // predicted here (preview has no args/token); it stays enforcement-only. Because this reads the
    // same evaluator instance the executor enforces, the prediction cannot drift (F1 subsumed).
    TransportTag transport = parseTransport(ctx.queryParam("transport"));
    IntentGateEvaluator.IntentVerdict verdict =
        intentGateEvaluator.evaluate(op.policy().risk(), transport);
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("operationId", op.id().value());
      payload.put("riskTier", op.policy().risk().name());
      payload.put("availableNow", availableNow);
      payload.put("availabilityKind", availabilityKind);
      payload.put("transport", transport.name());
      payload.put("sourceTier", verdict.sourceTier().name());
      payload.put("gateBehavior", verdict.gateBehavior().name());
      payload.put("hardStopEngaged", verdict.hardStopEngaged());
      ctx.contentType("application/json").result(MAPPER.writeValueAsBytes(payload));
    } catch (Exception e) {
      log.error("Failed to serialize operation preview for {}", idValue, e);
      throw new IllegalStateException("Operation preview serialization failed", e);
    }
  }

  /** Parse {@code ?transport=}; absent or unrecognized → the default ({@code LLM_EMISSION}). */
  private static TransportTag parseTransport(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_TRANSPORT;
    }
    try {
      return TransportTag.valueOf(raw.trim());
    } catch (IllegalArgumentException unknown) {
      return DEFAULT_TRANSPORT;
    }
  }

  private Optional<Operation> resolve(String idValue) {
    if (idValue == null || idValue.isBlank()) {
      return Optional.empty();
    }
    for (OperationCatalog catalog : catalogs) {
      Optional<Operation> hit = catalog.findByIdValue(idValue);
      if (hit.isPresent()) {
        return hit;
      }
    }
    return Optional.empty();
  }
}
