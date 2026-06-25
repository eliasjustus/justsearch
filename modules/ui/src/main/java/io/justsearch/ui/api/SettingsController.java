/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.SettingsService;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.configuration.SystemAccess;
import io.justsearch.ui.api.dto.LlmSettingsV2;
import io.justsearch.ui.api.dto.SettingsV2;
import io.justsearch.ui.api.dto.UiSettingsV2;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for settings endpoints. SettingsService interface is owned by
 * {@code io.justsearch.app.services.settings.SettingsServiceImpl} (tempdoc 519 §9 Step 3),
 * which delegates to {@link #resetToDefaults} via a method reference. The reset logic
 * stays here because its inputs ({@code SettingsV2}, {@code UiSettingsV2},
 * {@code LlmSettingsV2}) are a ui-internal DTO cluster outside §9's literal scope.
 */
public class SettingsController {
  private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private static final String SERVER_EXE_SYS_PROP = "justsearch.server.exe";
  private static final String SERVER_EXE_SOURCE_PROP = "justsearch.server.exe.source";
  private static final String EXCLUDE_PATTERNS_SYS_PROP = "justsearch.ui.exclude_patterns";
  private static final String EXCLUDE_PATTERNS_SOURCE_PROP = "justsearch.ui.exclude_patterns.source";
  private static final String GPU_LAYERS_SYS_PROP = "justsearch.gpu.layers";
  private static final String GPU_LAYERS_SOURCE_PROP = "justsearch.gpu.layers.source";
  private static final String CONTEXT_SIZE_SYS_PROP = "justsearch.context.size";
  private static final String CONTEXT_SIZE_SOURCE_PROP = "justsearch.context.size.source";
  private static final String SOURCE_UI_SETTINGS = "ui_settings";
  private final UiSettingsStore settingsStore;
  private final Path defaultIndexBasePath;
  private final Telemetry telemetry;
  private final ConfigStore configStore;

  public SettingsController(
      UiSettingsStore settingsStore,
      Path defaultIndexBasePath,
      Telemetry telemetry) {
    this(settingsStore, defaultIndexBasePath, telemetry, null);
  }

  public SettingsController(
      UiSettingsStore settingsStore,
      Path defaultIndexBasePath,
      Telemetry telemetry,
      ConfigStore configStore) {
    this.settingsStore = settingsStore;
    this.defaultIndexBasePath = defaultIndexBasePath;
    this.telemetry = telemetry;
    this.configStore = configStore;
  }

  // ==================== v2 Canonical Settings API ====================

  /**
   * GET /api/settings/v2 - Returns the canonical {@link SettingsV2} shape.
   */
  public void handleGetSettingsV2(Context ctx) {
    UiSettings settings = settingsStore.load();
    if ((settings.getIndexBasePath() == null || settings.getIndexBasePath().isBlank())
        && defaultIndexBasePath != null) {
      settings.setIndexBasePath(defaultIndexBasePath.toString());
    }
    ctx.json(toSettingsV2(settings));
  }

  /**
   * POST /api/settings/v2 - Accepts the canonical {@link SettingsV2} shape and persists it.
   */
  public void handleUpdateSettingsV2(Context ctx) {
    if (!settingsStore.mode().isWritable()) {
      ctx.status(409).json(ApiErrorHandler.toResponse(
          ApiErrorCode.SETTINGS_READ_ONLY,
          "Settings are read-only in " + settingsStore.mode().name() + " mode",
          telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    UiSettings current = settingsStore.load();
    try {
      SettingsV2 incoming = MAPPER.readValue(ctx.body(), SettingsV2.class);
      UiSettings merged = mergeV2Into(current, incoming);

      String validationError = validateIndexPath(merged.getIndexBasePath());
      if (validationError != null) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_PATH, validationError, telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }

      settingsStore.save(merged);
      maybeApplyServerExeSysProp(merged);
      maybeApplyExcludePatternsSysProp(merged);
      maybeApplyGpuLayersSysProp(merged);
      maybeApplyContextSizeSysProp(merged);
      rebuildConfigStore(merged);
      ctx.json(toSettingsV2(merged));
      log.info("Settings updated via API v2");
    } catch (Exception e) {
      log.error("Failed to update settings (v2)", e);
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Invalid settings format", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** Maps internal {@link UiSettings} to the canonical {@link SettingsV2} DTO. */
  private SettingsV2 toSettingsV2(UiSettings s) {
    UiSettingsV2 ui = new UiSettingsV2(
        s.getTheme(),
        s.isHighContrast(),
        s.getDensity(),
        s.isVimMode(),
        s.getDefaultAction(),
        s.getInspectorWidth() > 0 ? s.getInspectorWidth() : null,
        s.isPauseIndexingDuringAi(),
        s.getMode(),
        s.isTrustLoopNudgeSeen(),
        s.getExcludePatterns()
    );

    LlmSettingsV2 llm = new LlmSettingsV2(
        blankToNull(s.getServerExecutablePath()),
        s.getContextLength(),
        s.getMaxTokens(),
        s.getGpuLayers(),
        blankToNull(s.getLlmModelPath()),
        blankToNull(s.getLlamaLibPath())
    );

    List<String> indexPaths = new ArrayList<>();
    String basePath = s.getIndexBasePath();
    if (basePath != null && !basePath.isBlank()) {
      indexPaths.add(basePath);
    }

    String mode = settingsStore.mode().name().toLowerCase(Locale.ROOT);
    return new SettingsV2(ui, llm, indexPaths, mode);
  }

  /** Merges an incoming {@link SettingsV2} into the existing {@link UiSettings}. */
  private UiSettings mergeV2Into(UiSettings base, SettingsV2 incoming) {
    if (incoming == null) {
      return base;
    }

    UiSettingsV2 ui = incoming.ui();
    if (ui != null) {
      if (ui.theme() != null) base.setTheme(ui.theme());
      if (ui.highContrast() != null) base.setHighContrast(ui.highContrast());
      if (ui.density() != null) base.setDensity(ui.density());
      if (ui.vimMode() != null) base.setVimMode(ui.vimMode());
      if (ui.defaultAction() != null) base.setDefaultAction(ui.defaultAction());
      if (ui.inspectorWidth() != null) base.setInspectorWidth(ui.inspectorWidth());
      if (ui.pauseIndexingDuringAi() != null) base.setPauseIndexingDuringAi(ui.pauseIndexingDuringAi());
      if (ui.mode() != null) base.setMode(ui.mode());
      if (ui.hasSeenTrustLoopNudge() != null) base.setTrustLoopNudgeSeen(ui.hasSeenTrustLoopNudge());
      if (ui.excludePatterns() != null) base.setExcludePatterns(ui.excludePatterns());
    }

    LlmSettingsV2 llm = incoming.llm();
    if (llm != null) {
      if (llm.serverExecutable() != null) base.setServerExecutablePath(llm.serverExecutable());
      if (llm.contextWindow() != null) base.setContextLength(llm.contextWindow());
      if (llm.maxTokens() != null) base.setMaxTokens(llm.maxTokens());
      if (llm.gpuLayers() != null) base.setGpuLayers(llm.gpuLayers());
      if (llm.modelPath() != null) base.setLlmModelPath(llm.modelPath());
      if (llm.llamaLibPath() != null) base.setLlamaLibPath(llm.llamaLibPath());
    }

    List<String> indexPaths = incoming.indexPaths();
    if (indexPaths != null && !indexPaths.isEmpty()) {
      base.setIndexBasePath(indexPaths.get(0));
    }

    return base;
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s;
  }

  private static void maybeApplyServerExeSysProp(UiSettings settings) {
    if (settings == null) return;
    String exePath = settings.getServerExecutablePath();
    String source = SystemAccess.sysProp(SERVER_EXE_SOURCE_PROP, "");
    String existing = SystemAccess.sysProp(SERVER_EXE_SYS_PROP, "");

    boolean owned = SOURCE_UI_SETTINGS.equalsIgnoreCase(source);
    boolean unset = existing == null || existing.isBlank();
    if (!owned && !unset) {
      // Respect explicit operator overrides (sysprop set outside UI settings).
      return;
    }

    if (exePath == null || exePath.isBlank()) {
      SystemAccess.clearSysProp(SERVER_EXE_SYS_PROP);
      SystemAccess.clearSysProp(SERVER_EXE_SOURCE_PROP);
      return;
    }

    String normalized = PlatformPaths.expandUserHomePlaceholders(exePath.trim());
    normalized = PlatformPaths.assertNoUnexpandedPlaceholders(normalized, "ui.settings.serverExecutablePath");
    SystemAccess.setSysProp(SERVER_EXE_SYS_PROP, normalized);
    SystemAccess.setSysProp(SERVER_EXE_SOURCE_PROP, SOURCE_UI_SETTINGS);
  }

  private static void maybeApplyExcludePatternsSysProp(UiSettings settings) {
    if (settings == null) return;
    String source = SystemAccess.sysProp(EXCLUDE_PATTERNS_SOURCE_PROP, "");
    String existing = SystemAccess.sysProp(EXCLUDE_PATTERNS_SYS_PROP, "");

    boolean owned = SOURCE_UI_SETTINGS.equalsIgnoreCase(source);
    boolean unset = existing == null || existing.isBlank();
    if (!owned && !unset) {
      // Respect explicit operator overrides (sysprop set outside UI settings).
      return;
    }

    List<String> patterns = settings.getExcludePatterns();
    if (patterns == null || patterns.isEmpty()) {
      SystemAccess.clearSysProp(EXCLUDE_PATTERNS_SYS_PROP);
      SystemAccess.clearSysProp(EXCLUDE_PATTERNS_SOURCE_PROP);
      return;
    }

    try {
      String json = MAPPER.writeValueAsString(patterns);
      if (json == null || json.isBlank()) {
        SystemAccess.clearSysProp(EXCLUDE_PATTERNS_SYS_PROP);
        SystemAccess.clearSysProp(EXCLUDE_PATTERNS_SOURCE_PROP);
        return;
      }
      SystemAccess.setSysProp(EXCLUDE_PATTERNS_SYS_PROP, json);
      SystemAccess.setSysProp(EXCLUDE_PATTERNS_SOURCE_PROP, SOURCE_UI_SETTINGS);
    } catch (Exception e) {
      // Best-effort only; exclude patterns are still persisted even if sysprop mirroring fails.
      log.warn("Failed to mirror exclude patterns to sysprops", e);
    }
  }

  private static void maybeApplyGpuLayersSysProp(UiSettings settings) {
    if (settings == null) return;
    Integer gpuLayers = settings.getGpuLayers();
    String source = SystemAccess.sysProp(GPU_LAYERS_SOURCE_PROP, "");
    String existing = SystemAccess.sysProp(GPU_LAYERS_SYS_PROP, "");

    boolean owned = SOURCE_UI_SETTINGS.equalsIgnoreCase(source);
    boolean unset = existing == null || existing.isBlank();
    if (!owned && !unset) {
      // Respect explicit operator overrides (sysprop set outside UI settings).
      return;
    }

    if (gpuLayers == null || gpuLayers <= 0) {
      SystemAccess.clearSysProp(GPU_LAYERS_SYS_PROP);
      SystemAccess.clearSysProp(GPU_LAYERS_SOURCE_PROP);
      return;
    }

    SystemAccess.setSysProp(GPU_LAYERS_SYS_PROP, String.valueOf(gpuLayers));
    SystemAccess.setSysProp(GPU_LAYERS_SOURCE_PROP, SOURCE_UI_SETTINGS);
  }

  private static void maybeApplyContextSizeSysProp(UiSettings settings) {
    if (settings == null) return;
    Integer contextLength = settings.getContextLength();
    String source = SystemAccess.sysProp(CONTEXT_SIZE_SOURCE_PROP, "");
    String existing = SystemAccess.sysProp(CONTEXT_SIZE_SYS_PROP, "");

    boolean owned = SOURCE_UI_SETTINGS.equalsIgnoreCase(source);
    boolean unset = existing == null || existing.isBlank();
    if (!owned && !unset) {
      // Respect explicit operator overrides (sysprop set outside UI settings).
      return;
    }

    if (contextLength == null || contextLength <= 0) {
      SystemAccess.clearSysProp(CONTEXT_SIZE_SYS_PROP);
      SystemAccess.clearSysProp(CONTEXT_SIZE_SOURCE_PROP);
      return;
    }

    SystemAccess.setSysProp(CONTEXT_SIZE_SYS_PROP, String.valueOf(contextLength));
    SystemAccess.setSysProp(CONTEXT_SIZE_SOURCE_PROP, SOURCE_UI_SETTINGS);
  }

  /**
   * Rebuilds the ResolvedConfig with updated settings and swaps it into the ConfigStore.
   *
   * <p>This ensures ConfigStore listeners are notified of settings changes. The rebuild re-reads
   * env vars and system properties (which may have been updated by the maybeApply* methods above).
   */
  private void rebuildConfigStore(UiSettings settings) {
    ConfigStoreRebuilder.rebuild(configStore, settings);
  }

  private String validateIndexPath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(rawPath.trim());
      if (path.getParent() == null) {
        return "Index path must not be a filesystem root";
      }
      if (!Files.exists(path)) {
        return "Index path does not exist";
      }
      if (!Files.isDirectory(path)) {
        return "Index path must point to a directory";
      }
      if (!Files.isReadable(path)) {
        return "Index path is not readable";
      }
      if (!Files.isWritable(path)) {
        return "Index path is not writable";
      }

      // Allow:
      // - empty directory (new index root; Worker will initialize state.json + indices/)
      // - legacy Lucene directory (segments_N directly under the chosen path)
      // - generation-scoped JustSearch root (state.json and/or indices/ directory)
      boolean hasAnyEntries = false;
      boolean hasLuceneSegments = false;
      boolean hasStateJson = false;
      boolean hasIndicesDir = false;
      try (var stream = Files.list(path)) {
        var it = stream.iterator();
        while (it.hasNext()) {
          Path entry = it.next();
          hasAnyEntries = true;
          String name = entry.getFileName().toString().toLowerCase(Locale.ROOT);
          if (name.startsWith("segments")) {
            hasLuceneSegments = true;
            break;
          }
          if (name.equals("indices") && Files.isDirectory(entry)) {
            hasIndicesDir = true;
          }
          if (name.equals("state.json") || name.startsWith("state.json.")) {
            hasStateJson = true;
          }
        }
      }

      if (!hasAnyEntries) {
        return null;
      }
      if (hasLuceneSegments || hasStateJson || hasIndicesDir) {
        return null;
      }
      return "Index path must be empty or point to an existing JustSearch index root";
    } catch (Exception e) {
      log.warn("Index path validation failed for {}", rawPath, e);
      return "Invalid index path";
    }
  }

  // ==========================================================================
  // SettingsService impl (slice 3a-2-c continuation).
  //
  // Reset FE-controlled fields to their canonical default values while
  // preserving admin-set fields (server exe, model path, llama lib path,
  // index base path, schema/version metadata, splits, window geometry).
  // The defaults match what UiSettings's field initializers declare; we
  // explicitly enumerate the FE-controlled subset rather than copying the
  // entire object so admin-managed fields are preserved by construction.
  // ==========================================================================
  public Map<String, Object> resetToDefaults() throws Exception {
    if (!settingsStore.mode().isWritable()) {
      throw new IllegalStateException(
          "Settings are read-only in " + settingsStore.mode().name() + " mode");
    }
    UiSettings current = settingsStore.load();

    // Reset only the FE-controlled subset (matches the FE's prior
    // resetToDefaults map at modules/ui-web/src/hooks/useSettings.ts:81-97
    // pre-migration). Admin-set fields below are preserved.
    //
    // NOTE: vimMode is intentionally NOT reset here — the FE's pre-migration
    // resetToDefaults did not touch it, so a user with vimMode=true who
    // clicks Reset should keep their vim binding preference. Behavior parity
    // with the prior FE reset is the contract.
    current.setTheme("system");
    current.setHighContrast(false);
    current.setDensity("comfort");
    current.setDefaultAction("open");
    current.setPauseIndexingDuringAi(false);
    current.setMode("simple");
    current.setTrustLoopNudgeSeen(false);
    current.setExcludePatterns(new ArrayList<>());
    current.setContextLength(4096);
    current.setMaxTokens(1024);
    current.setGpuLayers(0);

    // Preserved (NOT reset by user-triggered reset-to-defaults):
    // - serverExecutablePath, llmModelPath, llamaLibPath (admin-managed paths)
    // - indexBasePath (computed at install / set by operator)
    // - schemaVersion, version (settings migration metadata)
    // - splits, window (geometry / inspector width — UX-state, but not
    //   "settings" the user thinks of when clicking Reset)
    // - inspectorWidth (UX state, persistent across reload)
    // - vimMode (user binding preference; not in the FE's prior reset map)
    settingsStore.save(current);
    maybeApplyExcludePatternsSysProp(current);
    maybeApplyGpuLayersSysProp(current);
    maybeApplyContextSizeSysProp(current);
    rebuildConfigStore(current);

    SettingsV2 v2 = toSettingsV2(current);
    @SuppressWarnings("unchecked")
    Map<String, Object> out = MAPPER.convertValue(v2, Map.class);
    return out;
  }
}
