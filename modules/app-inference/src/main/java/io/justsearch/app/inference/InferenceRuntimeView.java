/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.Mode;
import java.util.Optional;

/**
 * Immutable snapshot of the inference runtime's observed state. Tempdoc 518 P2.
 *
 * <p>Replaces the five scattered volatile / atomic fields previously held directly on {@link
 * InferenceLifecycleManager}: {@code usingExternalLlamaServer}, {@code lastKnownContextTokens},
 * {@code lastKnownModelId}, {@code currentIdentity}, {@code lastFailure}.
 *
 * <p><b>Three update tiers</b> (per the Appendix A.2 audit finding):
 * <ol>
 *   <li><b>Transition-atomic swap</b> — at the end of a successful transition or rollback,
 *       {@link TransitionRunner} swaps a fully-built {@code InferenceRuntimeView} into the
 *       holder. Happens under the orchestrator's lock; no concurrent writer.
 *   <li><b>Props-delta merge</b> — {@code /props} reads from {@code ServerPropsOps} (via the
 *       callback chain set up at {@link LlamaServerOps} construction) update only
 *       {@code lastKnownContextTokens} and {@code lastKnownModelId}. These writes are CAS-based
 *       and may race with concurrent transition swaps; the loop preserves the latest transition
 *       fields and overwrites only the props-derived fields.
 *   <li><b>Test mutation</b> — {@link InferenceLifecycleManager#setUsingExternalServerForTest}
 *       and similar test affordances install a synthetic view directly. Production code does
 *       not use these.
 * </ol>
 */
public record InferenceRuntimeView(
    Mode phase,
    /* nullable until the first transition completes */
    RuntimeIdentity identity,
    /* nullable when the runtime is healthy or no failure has occurred */
    InferenceFailure lastFailure,
    boolean usingExternalLlamaServer,
    /* nullable until /props or config supplies a value */
    Integer lastKnownContextTokens,
    /* nullable until /props or config supplies a value */
    String lastKnownModelId,
    /* -1 until the first successful startup cycle completes */
    long lastStartupDurationMs) {

  /** Initial view at process start: OFFLINE phase, all fields empty / default. */
  public static InferenceRuntimeView initial() {
    return new InferenceRuntimeView(
        Mode.OFFLINE, null, null, false, null, null, -1L);
  }

  public Optional<RuntimeIdentity> identityOptional() {
    return Optional.ofNullable(identity);
  }

  public Optional<InferenceFailure> lastFailureOptional() {
    return Optional.ofNullable(lastFailure);
  }

  public InferenceRuntimeView withPhase(Mode newPhase) {
    return new InferenceRuntimeView(
        newPhase,
        identity,
        lastFailure,
        usingExternalLlamaServer,
        lastKnownContextTokens,
        lastKnownModelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView withIdentity(RuntimeIdentity newIdentity) {
    return new InferenceRuntimeView(
        phase,
        newIdentity,
        lastFailure,
        usingExternalLlamaServer,
        lastKnownContextTokens,
        lastKnownModelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView withLastFailure(InferenceFailure failure) {
    return new InferenceRuntimeView(
        phase,
        identity,
        failure,
        usingExternalLlamaServer,
        lastKnownContextTokens,
        lastKnownModelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView clearedFailure() {
    return new InferenceRuntimeView(
        phase,
        identity,
        null,
        usingExternalLlamaServer,
        lastKnownContextTokens,
        lastKnownModelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView withExternal(boolean external) {
    return new InferenceRuntimeView(
        phase,
        identity,
        lastFailure,
        external,
        lastKnownContextTokens,
        lastKnownModelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView withContextTokens(Integer tokens) {
    return new InferenceRuntimeView(
        phase,
        identity,
        lastFailure,
        usingExternalLlamaServer,
        tokens,
        lastKnownModelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView withModelId(String modelId) {
    return new InferenceRuntimeView(
        phase,
        identity,
        lastFailure,
        usingExternalLlamaServer,
        lastKnownContextTokens,
        modelId,
        lastStartupDurationMs);
  }

  public InferenceRuntimeView withStartupDuration(long durationMs) {
    return new InferenceRuntimeView(
        phase,
        identity,
        lastFailure,
        usingExternalLlamaServer,
        lastKnownContextTokens,
        lastKnownModelId,
        durationMs);
  }
}
