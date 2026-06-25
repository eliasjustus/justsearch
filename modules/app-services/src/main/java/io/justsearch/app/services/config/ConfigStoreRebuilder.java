/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.config;

import io.justsearch.app.api.UiSettings;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rebuilds the {@link ResolvedConfig} in a {@link ConfigStore} after runtime sysprop changes.
 *
 * <p>Extracted from {@code SettingsController.rebuildConfigStore()} so that other services
 * (RuntimeActivationService, AiInstallService, AiPackImportService) can trigger a rebuild after
 * writing system properties that affect configuration values.
 *
 * <p>Tempdoc 519 §9 Block B3.0.e: moved from {@code io.justsearch.ui.config} to {@code app-services}
 * together with the {@code contributeUiSettings} helper formerly on {@code HeadlessApp}. The
 * helper had no {@code ui} dependencies (it operated on {@code UiSettings} from {@code app-api}
 * and {@code ResolvedConfigBuilder} from {@code configuration}), so relocation broke the soft
 * cycle without introducing a new SPI.
 */
public final class ConfigStoreRebuilder {

  private static final Logger log = LoggerFactory.getLogger(ConfigStoreRebuilder.class);
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private ConfigStoreRebuilder() {}

  /**
   * Rebuilds the ResolvedConfig from all sources and swaps it into the given ConfigStore.
   *
   * <p>Re-reads env vars, system properties, YAML, and UI settings. Notifies ConfigStore listeners
   * of any changes.
   *
   * @param store the ConfigStore to update (if null, this is a no-op)
   * @param settings current UI settings (if null, UI settings contribution is skipped)
   */
  public static void rebuild(ConfigStore store, UiSettings settings) {
    if (store == null) return;
    try {
      ResolvedConfigBuilder builder = ResolvedConfig.builder();
      builder.contributeBaseSources();
      if (settings != null) {
        contributeUiSettings(builder, settings);
      }
      store.update(builder.build());
    } catch (RuntimeException e) {
      log.warn("Failed to rebuild ConfigStore", e);
    }
  }

  /**
   * Forwards UI settings to a {@link ResolvedConfigBuilder} at ordinal 300 (settings.json).
   *
   * <p>Called during initial startup and when the user changes settings at runtime. Centralizes
   * the mapping so that both paths stay in sync (fixes M9 duplication). Relocated from
   * {@code HeadlessApp.contributeUiSettings} as part of tempdoc 519 §9 Block B3.0.e.
   */
  public static void contributeUiSettings(ResolvedConfigBuilder builder, UiSettings settings) {
    builder.putSettings("justsearch.index.base_path", settings.getIndexBasePath());
    builder.putSettings("justsearch.llm.model_path", settings.getLlmModelPath());
    builder.putSettings("justsearch.server.exe", settings.getServerExecutablePath());
    // Tempdoc 374 sandbox round 4 finding D/E: forward the per-encoder model
    // paths that AiInstallService.applyOnnxSettings persists, so the worker's
    // resolved-config snapshot gets justsearch.<feature>.model_path keys and
    // OnnxModelDiscovery resolves to the installed dirs instead of returning
    // "not found at any standard location" after Install AI completes.
    // Skip blanks so an empty UiSettings field doesn't override a YAML or
    // env-var value at lower/equal ordinals.
    putSettingIfPresent(
        builder, "justsearch.embed.onnx.model_path", settings.getEmbedOnnxModelPath());
    putSettingIfPresent(builder, "justsearch.rerank.model_path", settings.getRerankerModelPath());
    putSettingIfPresent(builder, "justsearch.ner.model_path", settings.getNerModelPath());
    putSettingIfPresent(builder, "justsearch.splade.model_path", settings.getSpladeModelPath());
    putSettingIfPresent(
        builder, "justsearch.citation.scorer.model_path", settings.getCitationScorerModelPath());
    if (settings.getGpuLayers() > 0) {
      builder.putSettings("justsearch.gpu.layers", String.valueOf(settings.getGpuLayers()));
    }
    if (settings.getContextLength() > 0) {
      builder.putSettings("justsearch.context.size", String.valueOf(settings.getContextLength()));
    }
    List<String> excludePatterns = settings.getExcludePatterns();
    if (excludePatterns != null && !excludePatterns.isEmpty()) {
      try {
        builder.putSettings(
            "justsearch.ui.exclude_patterns", JSON.writeValueAsString(excludePatterns));
      } catch (Exception ignored) {
        // Best-effort — exclude patterns serialization failure is non-fatal
      }
    }
  }

  private static void putSettingIfPresent(
      ResolvedConfigBuilder builder, String key, String value) {
    if (value != null && !value.isBlank()) {
      builder.putSettings(key, value);
    }
  }
}
