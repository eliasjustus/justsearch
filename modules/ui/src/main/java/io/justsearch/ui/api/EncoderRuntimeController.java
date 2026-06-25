/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.inference.EncoderRuntimeResponse;
import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.app.services.observability.EncoderRuntimeExplainer;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import io.justsearch.ort.EncoderRole;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Implements {@code GET /api/inference/encoders} (tempdoc 422).
 *
 * <p>Derives a structured "why is encoder X on CPU/GPU/unavailable?" answer per encoder by
 * correlating Worker's authoritative {@code PolicySnapshot} (via
 * {@link RemoteKnowledgeClient#getSessionPolicies()}) with Worker's runtime OrtCuda probe
 * snapshot (via {@link RemoteKnowledgeClient#getEncoderOrtCudaViews()}). Folds those two
 * surfaces plus tempdoc 414's metric labels into a single read-only JSON view backing the
 * Brain/Health UI panel + a future MCP tool wrapper.
 *
 * <p>Late-bind pattern mirrors {@link SessionPoliciesController}: constructed with {@code null}
 * client by {@link LocalApiServer}, then {@link #setClient} flipped from
 * {@code lateBindKnowledgeServer} once the Worker is reachable.
 */
@io.justsearch.contracts.AdvisoryContract(
    description =
        "Per-encoder runtime accelerator explainer. Iterates the active PolicySnapshot keys "
            + "and emits one EncoderRuntimeView per active encoder. RemoteKnowledgeClient is "
            + "null at LocalApiServer construction in eval mode and late-bound once Worker "
            + "boot completes — pre-late-bind requests must return snapshotStatus="
            + "worker-unreachable, never throw.",
    tempdoc = "422",
    signal = "encoder.runtime.worker_unreachable")
public final class EncoderRuntimeController {

  /**
   * Volatile so {@link #setClient(RemoteKnowledgeClient)} late-bind from the
   * {@link LocalApiServer#lateBindKnowledgeServer} path is visible to any subsequent
   * {@link #handle} invocation on the Javalin thread pool.
   */
  private volatile RemoteKnowledgeClient client;

  public EncoderRuntimeController(RemoteKnowledgeClient client) {
    this.client = client;
  }

  /** Late-binds the Worker RPC client after Worker boot completes. */
  public void setClient(RemoteKnowledgeClient client) {
    this.client = client;
  }

  /** Handler for {@code GET /api/inference/encoders}. */
  public void handle(Context ctx) {
    ctx.contentType("application/json");
    ctx.json(buildResponse());
  }

  /** Package-private for tests. Returns the typed response body (Jackson serialises). */
  EncoderRuntimeResponse buildResponse() {
    RemoteKnowledgeClient current = this.client;
    if (current == null) {
      return new EncoderRuntimeResponse(Map.of(), "worker-unreachable");
    }

    Map<String, Object> policies = current.getSessionPolicies();
    Object configStatusNode = policies.get("configStatus");
    if ("worker-unreachable".equals(configStatusNode)) {
      return new EncoderRuntimeResponse(Map.of(), "worker-unreachable");
    }

    Object modelsNode = policies.get("models");
    if (!(modelsNode instanceof Map<?, ?> modelsMap) || modelsMap.isEmpty()) {
      return new EncoderRuntimeResponse(Map.of(), "policy-unavailable");
    }

    Map<EncoderRole, OrtCudaView> views = current.getEncoderOrtCudaViews();
    Map<String, EncoderRuntimeView> encoders = new LinkedHashMap<>();

    for (Map.Entry<?, ?> entry : modelsMap.entrySet()) {
      Object roleKey = entry.getKey();
      Object policyValue = entry.getValue();
      if (roleKey == null) continue;
      EncoderRole role = parseRole(roleKey.toString());
      if (role == null) continue;
      Map<String, Object> policySubMap = coercePolicy(policyValue);
      OrtCudaView view = views.getOrDefault(role, OrtCudaView.notConfigured());
      EncoderRuntimeView runtime = EncoderRuntimeExplainer.explain(role, view, policySubMap);
      encoders.put(role.consumerName(), runtime);
    }

    return new EncoderRuntimeResponse(encoders, "ok");
  }

  /**
   * Maps the JSON policy key (uppercase enum-name shape per
   * {@code GrpcIngestService.getSessionPolicies}) to its {@link EncoderRole}; returns {@code null}
   * if the key isn't a known role (defensive — shouldn't happen given Worker's serialiser).
   */
  private static EncoderRole parseRole(String key) {
    try {
      return EncoderRole.valueOf(key.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> coercePolicy(Object node) {
    if (node instanceof Map<?, ?>) {
      return (Map<String, Object>) node;
    }
    return Map.of();
  }
}
