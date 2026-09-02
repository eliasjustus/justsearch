/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.util.Locale;
import java.util.Set;

/**
 * Ownership markers for the stored LLM model path (tempdoc 842 §2.3 precedence rule).
 *
 * <p>The runtime stores a resolved chat-model path in {@code justsearch.llm.model_path} and, next
 * to it, a marker in {@link #SOURCE_PROP_LLM_MODEL_PATH} naming <em>who wrote it</em>. That marker
 * is the only thing separating two values that look identical on the wire:
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
 * <p>Without this distinction the compact profile would be silently inert on every installed and
 * dev data dir — each one already stores a 9B model path written by the boot-time settings
 * promotion, which as a bare "override is set" test reads exactly like an operator lock.
 *
 * <p>The marker vocabulary is shared, not private, precisely because several surfaces must agree on
 * it: {@code InferenceConfig} (bootstrap resolution), {@code RuntimeActivationService} (activation
 * writes), and {@code EffectiveConfigController} (ownership reporting). Divergent private copies of
 * this list are how "system-owned" and "operator lock" drift apart.
 */
public final class ModelPathSource {

  private ModelPathSource() {}

  /** Companion system property that records who wrote {@code justsearch.llm.model_path}. */
  public static final String SOURCE_PROP_LLM_MODEL_PATH = "justsearch.llm.model_path.source";

  /**
   * Written by {@code AiInstallService} and {@code AiPackImportService} when they save a model path
   * into {@code settings.json} and then push the same value into a system property. The value is a
   * copy of {@code settings.json}, re-derivable at every boot.
   *
   * <p>It is no longer written at boot: tempdoc 883 §C.5c retired {@code HeadlessApp.resolveConfig}'s
   * settings→sysprop promotion (as decision 4 slice 2 had already retired {@code SettingsController}'s),
   * so a GUI-chosen model path reaches the resolver at ordinal 300 and reports as
   * {@code settings.json} on its own. The marker survives for the two writers above, which write a
   * sysprop directly and would otherwise read as an operator lock.
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
