/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.status.EffectiveConfigEntry;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.SystemAccess;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.SourceCandidate;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Implements GET /api/debug/effective-config (runtime grounding snapshot). */
public final class EffectiveConfigController {
  private static final int SCHEMA_VERSION = 1;
  private static final String UI_SETTINGS = "ui_settings";

  private final Supplier<Integer> apiPortSupplier;
  @SuppressWarnings("unused")
  private final UiSettingsStore settingsStore; // best-effort
  private final EnterprisePolicyService policyService; // best-effort
  private final OnlineAiService onlineAiService; // best-effort (for inference runtime introspection)
  private final Path indexBasePath; // best-effort (resolved runtime value)
  private final ConfigStore configStore; // nullable (ordinal-chain resolution)

  /** Backward-compatible constructor (no ConfigStore). */
  public EffectiveConfigController(
      Supplier<Integer> apiPortSupplier,
      UiSettingsStore settingsStore,
      EnterprisePolicyService policyService,
      OnlineAiService onlineAiService,
      Path indexBasePath) {
    this(apiPortSupplier, settingsStore, policyService, onlineAiService, indexBasePath, null);
  }

  public EffectiveConfigController(
      Supplier<Integer> apiPortSupplier,
      UiSettingsStore settingsStore,
      EnterprisePolicyService policyService,
      OnlineAiService onlineAiService,
      Path indexBasePath,
      ConfigStore configStore) {
    this.apiPortSupplier = apiPortSupplier;
    this.settingsStore = settingsStore;
    this.policyService = policyService;
    this.onlineAiService = onlineAiService;
    this.indexBasePath = indexBasePath;
    this.configStore = configStore;
  }

  public void handleGetEffectiveConfig(Context ctx) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("capturedAt", Instant.now().toString());

    Map<String, Object> process = new LinkedHashMap<>();
    process.put("pid", ProcessHandle.current().pid());
    Integer apiPort = safeGet(apiPortSupplier);
    if (apiPort != null && apiPort > 0) {
      process.put("apiPort", apiPort);
    }
    root.put("process", process);

    EffectivePolicy policy = null;
    try {
      if (policyService != null) {
        policy = policyService.snapshot();
      }
    } catch (Exception ignored) {
      policy = null;
    }

    OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo = null;
    try {
      if (onlineAiService instanceof OnlineAiRuntimeIntrospection introspection) {
        runtimeInfo = introspection.runtimeInfo();
      }
    } catch (Exception ignored) {
      runtimeInfo = null;
    }

    // Base dir for derived AI paths:
    // sysprop justsearch.home -> env JUSTSEARCH_HOME -> derived (user.dir)
    Path baseDir = resolveBaseDir();

    // Baseline inference config (env/sysprop + derived defaults). RuntimeInfo may override.
    InferenceConfig envInference;
    try {
      envInference = InferenceConfig.fromEnvironment(baseDir);
    } catch (Exception ignored) {
      envInference = null;
    }

    List<Map<String, Object>> keys = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Ports
    // -------------------------------------------------------------------------
    keys.add(keyJustsearchApiPortConfigured());
    keys.add(keyProcessApiPort(apiPort));
    keys.add(keyServerPort(runtimeInfo, envInference));

    // -------------------------------------------------------------------------
    // Paths
    // -------------------------------------------------------------------------
    keys.add(keyJustsearchHome(baseDir));
    keys.add(keyJustsearchDataDir());
    keys.add(keyIndexBasePath());
    keys.add(keyModelsDir(baseDir));
    keys.add(keyServerExe(runtimeInfo, envInference));
    keys.add(keyLlmModelPath(baseDir, runtimeInfo, envInference));

    // -------------------------------------------------------------------------
    // AI model selection (filenames)
    // -------------------------------------------------------------------------
    keys.add(keySimpleString(
        "justsearch.vlm.model",
        EnvRegistry.VLM_MODEL.sysProp(),
        EnvRegistry.VLM_MODEL.envVar(),
        EnvRegistry.VLM_MODEL.getString("Qwen_Qwen3.5-9B-Q4_K_M.gguf"),
        "default"));
    keys.add(keySimpleString(
        "justsearch.mmproj.model",
        EnvRegistry.MMPROJ_MODEL.sysProp(),
        EnvRegistry.MMPROJ_MODEL.envVar(),
        EnvRegistry.MMPROJ_MODEL.getString("mmproj-F16.gguf"),
        "default"));
    // -------------------------------------------------------------------------
    // AI knobs
    // -------------------------------------------------------------------------
    keys.add(keyContextSize(runtimeInfo, envInference));
    keys.add(keyGpuLayers(runtimeInfo, envInference, policy));
    keys.add(keyAiDisabled());

    // -------------------------------------------------------------------------
    // Policy bridge keys (source of truth is effective policy)
    // -------------------------------------------------------------------------
    keys.add(keyPolicyBool("policy.gpu_acceleration_enabled", policy == null ? null : policy.gpuAccelerationEnabled()));
    keys.add(keyPolicyBool(
        "justsearch.policy.disallowExternalInferenceServers",
        policy == null ? null : policy.disallowExternalInferenceServers()));

    root.put("keys", keys);

    // -------------------------------------------------------------------------
    // Ordinal-chain resolution trace (from ConfigStore / ResolvedConfig)
    // -------------------------------------------------------------------------
    if (configStore != null) {
      root.put("resolvedConfig", buildResolvedConfigEntries());
    }

    ctx.json(root);
  }

  private List<EffectiveConfigEntry> buildResolvedConfigEntries() {
    ResolvedConfig config = configStore.get();
    Map<String, ConfigResolution> resolutions = config.resolutions();
    List<EffectiveConfigEntry> entries = new ArrayList<>(resolutions.size());

    for (ConfigResolution res : resolutions.values()) {
      List<EffectiveConfigEntry.CandidateEntry> candidates = new ArrayList<>(res.considered().size());
      for (SourceCandidate sc : res.considered()) {
        candidates.add(new EffectiveConfigEntry.CandidateEntry(sc.sourceName(), sc.ordinal(), sc.rawValue()));
      }
      entries.add(new EffectiveConfigEntry(
          res.key(), res.value(), res.sourceName(), res.sourceOrdinal(), res.sourceDetail(), candidates));
    }
    return entries;
  }

  // ---------------------------------------------------------------------------
  // Key builders
  // ---------------------------------------------------------------------------

  private Map<String, Object> keyJustsearchApiPortConfigured() {
    String sys = sysProp(EnvRegistry.API_PORT.sysProp());
    String env = envVar(EnvRegistry.API_PORT.envVar());
    Integer parsed = parseInt(sys != null ? sys : env);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.API_PORT.sysProp());
    details.put("envVar", EnvRegistry.API_PORT.envVar());
    if (sys != null) details.put("syspropValue", sys);
    if (env != null) details.put("envValue", env);

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "default";

    return key("justsearch.api.port", parsed, source, details);
  }

  private Map<String, Object> keyProcessApiPort(Integer apiPort) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("note", "Actual bound port of the running Local API server.");
    return key("process.apiPort", apiPort, "runtime", details);
  }

  private Map<String, Object> keyServerPort(OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo, InferenceConfig envInference) {
    Integer effective = null;
    if (runtimeInfo != null) {
      effective = runtimeInfo.serverPort();
    } else if (envInference != null) {
      effective = envInference.serverPort();
    }

    String sys = sysProp(EnvRegistry.SERVER_PORT.sysProp());
    String env = envVar(EnvRegistry.SERVER_PORT.envVar());
    Integer baseline = parseInt(sys != null ? sys : env);
    if (baseline == null) baseline = 8080;

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.SERVER_PORT.sysProp());
    details.put("envVar", EnvRegistry.SERVER_PORT.envVar());
    details.put("baseline", baseline);
    if (runtimeInfo != null) details.put("runtime", runtimeInfo.serverPort());

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "default";

    if (effective != null && !effective.equals(baseline)) {
      // Runtime config differs from baseline; surface that honestly.
      source = "runtime";
      List<Map<String, Object>> conflicts = new ArrayList<>();
      conflicts.add(Map.of("source", sourceForBaseline(sys, env), "value", baseline));
      details.put("conflicts", conflicts);
    }

    return key("justsearch.server.port", effective != null ? effective : baseline, source, details);
  }

  private Map<String, Object> keyJustsearchHome(Path baseDir) {
    String sys = sysProp(EnvRegistry.HOME.sysProp());
    String env = envVar(EnvRegistry.HOME.envVar());
    String derived = Path.of("").toAbsolutePath().toString();
    String value = sys != null ? sys : (env != null ? env : derived);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.HOME.sysProp());
    details.put("envVar", EnvRegistry.HOME.envVar());
    details.put("derived", derived);

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "derived";

    // Normalize the value to the Path we used for other derived computations.
    return key("justsearch.home", baseDir == null ? value : baseDir.toString(), source, details);
  }

  private Map<String, Object> keyJustsearchDataDir() {
    String canonical = sysProp(EnvRegistry.DATA_DIR.sysProp());
    String legacy = sysProp("justsearch.data_dir");
    String legacyApp = sysProp("app.data_dir");
    String env = envVar(EnvRegistry.DATA_DIR.envVar());
    Path platformDefault = PlatformPaths.getPlatformDefault();

    String chosenRaw;
    String chosenSource;
    String chosenKey;

    if (canonical != null) {
      chosenRaw = canonical;
      chosenSource = "system_property";
      chosenKey = EnvRegistry.DATA_DIR.sysProp();
    } else if (legacy != null) {
      chosenRaw = legacy;
      chosenSource = "system_property";
      chosenKey = "justsearch.data_dir";
    } else if (legacyApp != null) {
      chosenRaw = legacyApp;
      chosenSource = "system_property";
      chosenKey = "app.data_dir";
    } else if (env != null) {
      chosenRaw = env;
      chosenSource = "environment_variable";
      chosenKey = EnvRegistry.DATA_DIR.envVar();
    } else {
      chosenRaw = platformDefault == null ? Path.of("").toAbsolutePath().toString() : platformDefault.toString();
      chosenSource = "derived";
      chosenKey = "platform_default";
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.DATA_DIR.sysProp());
    details.put("envVar", EnvRegistry.DATA_DIR.envVar());
    details.put("legacySyspropsChecked", List.of("justsearch.data_dir", "app.data_dir"));
    details.put("winnerKey", chosenKey);

    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", canonical, chosenRaw);
    addConflictIfDifferent(conflicts, "system_property", legacy, chosenRaw);
    addConflictIfDifferent(conflicts, "system_property", legacyApp, chosenRaw);
    addConflictIfDifferent(conflicts, "environment_variable", env, chosenRaw);
    if (!conflicts.isEmpty()) {
      details.put("conflicts", conflicts);
    }

    return key("justsearch.data.dir", chosenRaw, chosenSource, details);
  }

  private Map<String, Object> keyIndexBasePath() {
    final String sysprop = "justsearch.index.base_path";
    final String envVar = "JUSTSEARCH_INDEX_BASE_PATH";
    String sys = sysProp(sysprop);
    String env = envVar(envVar);
    String marker = sysProp("justsearch.index.base_path.source");

    String source;
    if (sys != null) source = isUiSettingsMarker(marker) ? "ui_settings" : "system_property";
    else if (env != null) source = "environment_variable";
    else source = "derived";

    String value;
    if (indexBasePath != null) {
      value = indexBasePath.toString();
    } else if (sys != null) {
      value = sys;
    } else if (env != null) {
      value = env;
    } else {
      // Best-effort default: <dataDir>/index/default
      try {
        value = PlatformPaths.resolveIndexPath("default").toString();
      } catch (Exception e) {
        value = "";
      }
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", sysprop);
    details.put("envVar", envVar);
    if (indexBasePath != null) details.put("resolved", indexBasePath.toString());
    if (sys != null) {
      details.put("syspropValue", sys);
      if (isUiSettingsMarker(marker)) {
        details.put("owner", "ui_settings");
        details.put("uiOwnershipProp", "justsearch.index.base_path.source");
        details.put("uiOwnershipValue", marker);
      } else {
        details.put("owner", "unknown");
      }
    }
    if (env != null) details.put("envValue", env);

    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", sys, value);
    addConflictIfDifferent(conflicts, "environment_variable", env, value);
    if (!conflicts.isEmpty()) details.put("conflicts", conflicts);

    return key("justsearch.index.base_path", value, source, details);
  }

  private Map<String, Object> keyModelsDir(Path baseDir) {
    String sys = sysProp(EnvRegistry.MODELS_DIR.sysProp());
    String env = envVar(EnvRegistry.MODELS_DIR.envVar());

    Path resolved = baseDir;
    try {
      String raw = EnvRegistry.MODELS_DIR.getString("models");
      resolved = baseDir == null ? Path.of(raw) : baseDir.resolve(raw);
    } catch (Exception ignored) {
      // best-effort
    }

    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = "derived";

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.MODELS_DIR.sysProp());
    details.put("envVar", EnvRegistry.MODELS_DIR.envVar());
    if (baseDir != null) details.put("baseDir", baseDir.toString());

    return key("justsearch.models.dir", resolved == null ? null : resolved.toString(), source, details);
  }

  private Map<String, Object> keyServerExe(
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference) {
    String sys = sysProp(EnvRegistry.SERVER_EXE.sysProp());
    String env = envVar(EnvRegistry.SERVER_EXE.envVar());
    String marker = sysProp("justsearch.server.exe.source");

    String effective = runtimeInfo != null ? runtimeInfo.serverExecutable()
        : (envInference != null && envInference.serverExecutable() != null ? envInference.serverExecutable().toString() : null);

    String source;
    if (sys != null && valuesMatchPath(sys, effective)) {
      source = isUiSettingsMarker(marker) ? "ui_settings" : "system_property";
    } else if (env != null && valuesMatchPath(env, effective)) {
      source = "environment_variable";
    } else if (effective != null && !effective.isBlank()) {
      source = "derived";
    } else {
      source = "default";
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.SERVER_EXE.sysProp());
    details.put("envVar", EnvRegistry.SERVER_EXE.envVar());
    if (sys != null) {
      details.put("syspropValue", sys);
      if (!isUiSettingsMarker(marker)) details.put("owner", "unknown");
      else {
        details.put("owner", "ui_settings");
        details.put("uiOwnershipProp", "justsearch.server.exe.source");
        details.put("uiOwnershipValue", marker);
      }
    }
    if (env != null) details.put("envValue", env);
    if (runtimeInfo != null) details.put("usingExternalLlamaServer", runtimeInfo.usingExternalLlamaServer());

    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", sys, effective);
    addConflictIfDifferent(conflicts, "environment_variable", env, effective);
    if (!conflicts.isEmpty()) details.put("conflicts", conflicts);

    return key("justsearch.server.exe", effective, source, details);
  }

  private Map<String, Object> keyLlmModelPath(
      Path baseDir,
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference) {
    String sys = sysProp(EnvRegistry.LLM_MODEL_PATH.sysProp());
    String env = envVar(EnvRegistry.LLM_MODEL_PATH.envVar());
    String marker = sysProp("justsearch.llm.model_path.source");

    String effective = runtimeInfo != null ? runtimeInfo.modelPath()
        : (envInference != null && envInference.modelPath() != null ? envInference.modelPath().toString() : null);

    String source;
    if (sys != null) source = isUiSettingsMarker(marker) ? "ui_settings" : "system_property";
    else if (env != null) source = "environment_variable";
    else if (effective != null && !effective.isBlank()) source = "derived";
    else source = "default";

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.LLM_MODEL_PATH.sysProp());
    details.put("envVar", EnvRegistry.LLM_MODEL_PATH.envVar());
    if (baseDir != null) details.put("baseDir", baseDir.toString());
    if (runtimeInfo != null) details.put("usingExternalLlamaServer", runtimeInfo.usingExternalLlamaServer());
    if (sys != null) {
      details.put("syspropValue", sys);
      if (isUiSettingsMarker(marker)) {
        details.put("owner", "ui_settings");
        details.put("uiOwnershipProp", "justsearch.llm.model_path.source");
        details.put("uiOwnershipValue", marker);
      } else {
        details.put("owner", "unknown");
      }
    }
    if (env != null) details.put("envValue", env);

    // If the runtime effective model path differs from the configured override, surface conflicts.
    List<Map<String, Object>> conflicts = new ArrayList<>();
    addConflictIfDifferent(conflicts, "system_property", sys, effective);
    addConflictIfDifferent(conflicts, "environment_variable", env, effective);
    if (!conflicts.isEmpty()) details.put("conflicts", conflicts);

    return key("justsearch.llm.model_path", effective != null ? effective : (sys != null ? sys : env), source, details);
  }

  private Map<String, Object> keyContextSize(
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference) {
    String sys = sysProp(EnvRegistry.CONTEXT_SIZE.sysProp());
    String env = envVar(EnvRegistry.CONTEXT_SIZE.envVar());
    String marker = sysProp("justsearch.context.size.source");
    int baseline = envInference != null ? envInference.contextSize() : 4096;
    Integer runtime = runtimeInfo != null ? runtimeInfo.contextSize() : null;

    int value = runtime != null ? runtime : baseline;

    String baselineSource = sourceForBaseline(sys, env);
    if ("system_property".equals(baselineSource) && isUiSettingsMarker(marker)) {
      baselineSource = "ui_settings";
    }
    String source = baselineSource;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.CONTEXT_SIZE.sysProp());
    details.put("envVar", EnvRegistry.CONTEXT_SIZE.envVar());
    details.put("baseline", baseline);
    if (runtime != null) details.put("runtime", runtime);
    if (sys != null) {
      details.put("syspropValue", sys);
      if (isUiSettingsMarker(marker)) {
        details.put("owner", "ui_settings");
        details.put("uiOwnershipProp", "justsearch.context.size.source");
        details.put("uiOwnershipValue", marker);
      } else {
        details.put("owner", "unknown");
      }
    }
    if (env != null) details.put("envValue", env);

    if (runtime != null && runtime != baseline) {
      source = "runtime";
      List<Map<String, Object>> conflicts = new ArrayList<>();
      conflicts.add(Map.of("source", baselineSource, "value", baseline));
      details.put("conflicts", conflicts);
    }

    return key("justsearch.context.size", value, source, details);
  }

  private Map<String, Object> keyGpuLayers(
      OnlineAiRuntimeIntrospection.RuntimeInfo runtimeInfo,
      InferenceConfig envInference,
      EffectivePolicy policy) {
    String sys = sysProp(EnvRegistry.GPU_LAYERS.sysProp());
    String env = envVar(EnvRegistry.GPU_LAYERS.envVar());
    String marker = sysProp("justsearch.gpu.layers.source");

    int baseline = envInference != null ? envInference.gpuLayers() : 0;
    Integer requested = runtimeInfo != null ? runtimeInfo.gpuLayers() : null;
    if (requested == null) requested = baseline;

    boolean policyGpuEnabled = policy == null || policy.gpuAccelerationEnabled();
    boolean usingExternal = runtimeInfo != null && runtimeInfo.usingExternalLlamaServer();

    Integer applied;
    boolean appliedKnown = true;
    if (usingExternal) {
      appliedKnown = false;
      applied = null;
    } else {
      applied = (requested > 0 && !policyGpuEnabled) ? 0 : requested;
    }

    String baselineSource = sourceForBaseline(sys, env);
    if ("system_property".equals(baselineSource) && isUiSettingsMarker(marker)) {
      baselineSource = "ui_settings";
    }
    String source = baselineSource;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.GPU_LAYERS.sysProp());
    details.put("envVar", EnvRegistry.GPU_LAYERS.envVar());
    details.put("requested", requested);
    details.put("applied", applied);
    details.put("appliedValueKnown", appliedKnown);
    details.put("policyGpuAccelerationEnabled", policyGpuEnabled);
    if (runtimeInfo != null) details.put("usingExternalLlamaServer", usingExternal);
    if (sys != null) {
      details.put("syspropValue", sys);
      if (isUiSettingsMarker(marker)) {
        details.put("owner", "ui_settings");
        details.put("uiOwnershipProp", "justsearch.gpu.layers.source");
        details.put("uiOwnershipValue", marker);
      } else {
        details.put("owner", "unknown");
      }
    }
    if (env != null) details.put("envValue", env);

    if (runtimeInfo != null && requested != baseline) {
      source = "runtime";
      List<Map<String, Object>> conflicts = new ArrayList<>();
      conflicts.add(Map.of("source", baselineSource, "value", baseline));
      details.put("conflicts", conflicts);
    }

    Object value = appliedKnown ? applied : requested;
    return key("justsearch.gpu.layers", value, source, details);
  }

  private Map<String, Object> keyAiDisabled() {
    String sys = sysProp(EnvRegistry.AI_DISABLED.sysProp());
    String env = envVar(EnvRegistry.AI_DISABLED.envVar());
    ConfigStore cs = ConfigStore.globalOrNull();
    ResolvedConfig rc = cs != null ? cs.get() : null;
    boolean value = rc != null ? rc.ai().disabled() : EnvRegistry.AI_DISABLED.getBoolean(false);
    String source = sourceForBaseline(sys, env);
    if ("default".equals(source)) source = "default";

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", EnvRegistry.AI_DISABLED.sysProp());
    details.put("envVar", EnvRegistry.AI_DISABLED.envVar());
    return key("justsearch.ai.disabled", value, source, details);
  }

  private Map<String, Object> keyPolicyBool(String key, Boolean value) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("note", "Source of truth is EnterprisePolicyService.snapshot() / GET /api/policy/effective.");
    return key(key, value, "policy_effective", details);
  }

  private Map<String, Object> keySimpleString(
      String key,
      String sysprop,
      String envVar,
      String value,
      String defaultSource) {
    String sys = sysProp(sysprop);
    String env = envVar(envVar);
    String source;
    if (sys != null) source = "system_property";
    else if (env != null) source = "environment_variable";
    else source = defaultSource;
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sysprop", sysprop);
    details.put("envVar", envVar);
    return key(key, value, source, details);
  }

  private static Map<String, Object> key(String key, Object value, String source, Map<String, Object> details) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("key", key);
    out.put("value", value);
    out.put("source", source);
    if (details != null && !details.isEmpty()) {
      out.put("details", details);
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static Integer safeGet(Supplier<Integer> supplier) {
    try {
      return supplier == null ? null : supplier.get();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String sysProp(String key) {
    try {
      String v = SystemAccess.sysProp(key);
      if (v == null) return null;
      String t = v.trim();
      return t.isBlank() ? null : t;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String envVar(String key) {
    try {
      String v = SystemAccess.envVar(key);
      if (v == null) return null;
      String t = v.trim();
      return t.isBlank() ? null : t;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Integer parseInt(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String sourceForBaseline(String sysVal, String envVal) {
    if (sysVal != null) return "system_property";
    if (envVal != null) return "environment_variable";
    return "default";
  }

  private static boolean isUiSettingsMarker(String raw) {
    if (raw == null) return false;
    return UI_SETTINGS.equalsIgnoreCase(raw.trim());
  }

  private static void addConflictIfDifferent(List<Map<String, Object>> conflicts, String source, String candidate, String winner) {
    if (candidate == null) return;
    String c = candidate.trim();
    String w = winner == null ? "" : winner.trim();
    if (!c.equals(w)) {
      conflicts.add(Map.of("source", source, "value", c));
    }
  }

  private static boolean valuesMatchPath(String candidate, String effective) {
    if (candidate == null || effective == null) return false;
    String a = candidate.trim();
    String b = effective.trim();
    if (a.equals(b)) return true;
    try {
      Path pa = Path.of(a).toAbsolutePath().normalize();
      Path pb = Path.of(b).toAbsolutePath().normalize();
      return pa.equals(pb);
    } catch (Exception ignored) {
      return false;
    }
  }

  private static Path resolveBaseDir() {
    return PlatformPaths.resolveAiHome();
  }
}
