/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.inference;

import java.nio.file.Path;

/**
 * The chat identity the inference engine is <em>actually</em> running (tempdoc 842 §2.5).
 *
 * <p>Realized, not declared. Settings, system properties and the install contract all describe what
 * <em>should</em> be loaded; this record describes what a live llama-server process was started
 * with. 805 established why the distinction has to be representable — declared CUDA with observed
 * CPU had no field able to say so — and 657 shipped the projection shape ({@code ModeInfo{intent,
 * realized}}). This is the same move for the chat engine.
 *
 * <p>The value is produced by projecting the running {@code InferenceLifecycleManager}'s current
 * config, and it is only produced while the engine is up: an offline engine has no realized
 * identity, so the projection is {@code null} rather than a stale last-known claim.
 *
 * @param profileId id of the {@code ChatModelProfile} the engine claims, or {@code null} when the
 *     engine loaded a bare path that no profile selected. {@code null} is a distinct state from
 *     {@code "standard"} — an unattributed path is exactly the case where a profile switch would
 *     have been silently ignored, so it must not be reported as the default profile.
 * @param modelFile bare file name of the loaded model (no directory), or {@code null} if unknown.
 *     Safe for the manifest's public projection, which must not leak filesystem layout.
 * @param modelPath absolute path of the loaded model, or {@code null} if unknown. Diagnostic-only;
 *     redact before putting it in a shareable artifact.
 * @param mmprojActive whether a multimodal projector is attached to the running engine. A swap
 *     through the bare-path apply drops the projector and leaves the stack silently text-only —
 *     this field is the one that can say so.
 */
public record RealizedChatIdentity(
    String profileId, String modelFile, String modelPath, boolean mmprojActive) {

  /**
   * Builds an identity from the running engine's resolved paths.
   *
   * @param profileId the engine's chat-profile claim, or null
   * @param modelPath the loaded model path, or null when the engine has none
   * @param mmprojPath the loaded projector path, or null when no projector is attached
   */
  public static RealizedChatIdentity of(String profileId, Path modelPath, Path mmprojPath) {
    String file =
        modelPath == null || modelPath.getFileName() == null
            ? null
            : modelPath.getFileName().toString();
    return new RealizedChatIdentity(
        profileId == null || profileId.isBlank() ? null : profileId.trim(),
        file,
        modelPath == null ? null : modelPath.toString(),
        mmprojPath != null);
  }
}
