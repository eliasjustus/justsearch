/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.util.Locale;
import java.util.Set;

/**
 * Ownership markers for the stored LLM model path (tempdoc 842 §2.3 precedence rule).
 *
 * <p>A marker system property records <em>who wrote</em> the system property beside it, separating
 * two values that look identical on the wire:
 *
 * <ul>
 *   <li><b>System-owned</b> — written by the system itself (settings promotion at boot, CUDA
 *       auto-selection, profile resolution). These values are <em>re-derivable</em>: the system can
 *       compute them again from the install contract, the registry, or the active chat profile.
 *       They may therefore be superseded by an explicit {@code justsearch.chat.profile} selection
 *       without losing operator intent, because there was none.
 *   <li><b>Operator-set</b> — an env var, an unmarked JVM flag, or a hand-edited value. These are
 *       <b>sacred</b>: a human named a specific file, and no profile, installer, or auto-selector
 *       may quietly point the engine somewhere else.
 * </ul>
 *
 * <p><b>What still writes a marker, as of tempdoc 883 §C.5c (#605).</b> Only
 * {@code justsearch.server.exe.source}, and only for the runtime GPU-variant switch
 * ({@code RuntimeActivationService}, {@code AiInstallService.applyCudaServerExe},
 * {@code HeadlessApp.maybeAutoSelectCuda12Variant}). There it is genuinely load-bearing: it is the
 * token {@code applyServerExeSysProp} reads to decide whether an existing exe override is the
 * system's own (overwritable) or an operator lock (refuse), and it is captured and restored by the
 * activation rollback bracket.
 *
 * <p><b>Nothing writes {@link #SOURCE_PROP_LLM_MODEL_PATH} any more.</b> Its writers existed only to
 * label a settings-borne value that had been promoted to a system property — the boot promotion
 * (retired by 883 §C.5c) and the installer/pack-import promotions (retired with it, #605 review S1).
 * A chat-model path now reaches the resolver once, at ordinal 300, so the ordinal chain classifies
 * it without help: {@code settings.json} is re-derivable and supersedable by an explicit profile,
 * and an unmarked {@code jvm_arg} really is an operator lock. The constant and
 * {@link #isSystemOwned} survive because tempdoc 842 forward-declared {@link #PROFILE_RESOLVED} for
 * the profile-persistence writer that has not shipped yet; if that slice is abandoned, this pair
 * goes with it.
 *
 * <p>The vocabulary is shared, not private, precisely because several surfaces must agree on it:
 * {@code InferenceConfig} (bootstrap resolution) and {@code RuntimeActivationService} (activation
 * writes). Divergent private copies are how "system-owned" and "operator lock" drift apart.
 */
public final class ModelPathSource {

  private ModelPathSource() {}

  /**
   * Companion system property that records who wrote {@code justsearch.llm.model_path}.
   *
   * <p>No writer ships today — see the class javadoc. It is read by
   * {@code InferenceConfig.classifyModelPathOwner}, where an absent marker correctly means
   * "operator", and is kept for tempdoc 842's pending profile-persistence writer.
   */
  public static final String SOURCE_PROP_LLM_MODEL_PATH = "justsearch.llm.model_path.source";

  /**
   * A value copied from {@code settings.json} into a system property — re-derivable at every boot.
   *
   * <p>Now written only for {@code justsearch.server.exe.source}, by
   * {@code RuntimeActivationService}, which needs the exe override at ordinal 500 to beat the
   * settings row it just saved. No settings→sysprop promotion writes it any more: the boot pair went
   * with tempdoc 883 §C.5c, and the installer/pack-import pair with #605 review S1.
   */
  public static final String UI_SETTINGS = "ui_settings";

  /**
   * Written by {@code HeadlessApp.maybeAutoSelectCuda12Variant} and
   * {@code AiInstallService.applyCudaServerExe} for the server-executable key. Recognized here too
   * so the ownership vocabulary stays one list rather than one-per-key (tempdoc 374 alpha.16 fix A
   * already had to re-learn this for the activation service).
   */
  public static final String AUTO_SELECTED_CUDA12 = "auto_selected_cuda12";

  /**
   * Written when a chat profile resolves the (model, mmproj) pair and persists the model path.
   * Declared now so a profile-written path is recognized as system-owned by the readers that
   * already ship; the writer lands with the persistence slice of tempdoc 842.
   */
  public static final String PROFILE_RESOLVED = "profile_resolved";

  private static final Set<String> SYSTEM_OWNED =
      Set.of(UI_SETTINGS, AUTO_SELECTED_CUDA12, PROFILE_RESOLVED);

  /**
   * Returns true when {@code marker} names a system-owned (re-derivable) stored model path.
   *
   * <p>A null, blank, or unrecognized marker is deliberately <b>not</b> system-owned: an unmarked
   * value is an operator value, and an unknown marker is treated the same way so a future writer
   * that forgets to publish its label cannot silently downgrade an operator lock.
   *
   * @param marker the raw marker value (trimmed and compared case-insensitively), may be null
   */
  public static boolean isSystemOwned(String marker) {
    if (marker == null) return false;
    String norm = marker.trim().toLowerCase(Locale.ROOT);
    return !norm.isEmpty() && SYSTEM_OWNED.contains(norm);
  }
}
