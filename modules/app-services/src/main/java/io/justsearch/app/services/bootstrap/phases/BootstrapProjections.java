/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.status.InferenceFailureView;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.LifecycleCounters;
import io.justsearch.app.api.status.RuntimeIdentityView;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.RuntimeIdentity;
import io.justsearch.app.observability.health.ConditionRecoveryIndex;
import io.justsearch.app.observability.health.ConditionRecoveryIndexBuilder;
import io.justsearch.app.observability.health.ConditionStore;

/**
 * Tempdoc 519 §10 final-push: extracted static projection helpers from
 * {@code HeadAssembly}.
 *
 * <p>Two pure functions:
 * <ul>
 *   <li>{@link #projectInferenceSnapshot} — typed projection of
 *       {@link InferenceLifecycleManager} into {@link InferenceRuntimeView} for status
 *       endpoints. OFFLINE/unavailable view when manager is null.
 *   <li>{@link #renderConditionContext} — natural-language render of the current
 *       condition-recovery snapshot, used by AgentLoopService at prompt-construction time.
 * </ul>
 */
public final class BootstrapProjections {

  private BootstrapProjections() {}

  /**
   * Tempdoc 412 Phase 3: projects the {@link InferenceLifecycleManager}'s typed accessor surface
   * into the API status record {@link InferenceRuntimeView}. Returns the OFFLINE/unavailable
   * view when {@code mgr} is null (AI disabled).
   */
  public static InferenceRuntimeView projectInferenceSnapshot(InferenceLifecycleManager mgr) {
    if (mgr == null) {
      return new InferenceRuntimeView(
          "OFFLINE", null, false, null, new LifecycleCounters(0L, 0L, 0L));
    }
    String phase = mgr.getCurrentMode().name();
    boolean usingExternal = mgr.isUsingExternalLlamaServer();
    RuntimeIdentityView identityView =
        mgr.identity()
            .map(
                id ->
                    new RuntimeIdentityView(
                        id.generationId(), id.modelId(), id.port(), id.loadedAtEpochMs()))
            .orElse(null);
    InferenceFailureView failureView =
        mgr.lastFailure()
            .map(f -> new InferenceFailureView(f.wireCode(), f.detail()))
            .orElse(null);
    long transitionsTotal = mgr.identity().map(RuntimeIdentity::generationId).orElse(0L);
    LifecycleCounters counters = new LifecycleCounters(0L, 0L, transitionsTotal);
    return new InferenceRuntimeView(phase, identityView, usingExternal, failureView, counters);
  }

  /**
   * Slice 447 §X.11.5 Phase 5: renders the current condition-recovery snapshot as a
   * natural-language block the agent can read at prompt-construction time. Empty when no
   * asserted conditions carry recoveries.
   */
  public static String renderConditionContext(ConditionStore store) {
    if (store == null) return "";
    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);
    if (index.entries().isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    sb.append("Currently asserted conditions and their recommended recovery operations:");
    for (var entry : index.entries()) {
      for (var ref : entry.conditions()) {
        sb.append("\n- ")
            .append(ref.conditionId())
            .append(" (")
            .append(ref.subject())
            .append(", severity=")
            .append(ref.severity())
            .append(") → ")
            .append(entry.target().value());
      }
    }
    sb.append(
        "\n\nWhen the user asks how to address these issues, reference the recommended"
            + " operations and offer to invoke them through the appropriate tool.");
    return sb.toString();
  }
}
