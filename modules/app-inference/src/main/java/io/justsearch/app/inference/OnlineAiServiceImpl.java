/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;

import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link OnlineAiService} that delegates to {@link InferenceLifecycleManager}.
 *
 * <p>This class wraps the low-level inference manager and provides a clean API for
 * user-facing AI operations like summarization and Q&A.
 */
public final class OnlineAiServiceImpl
    implements OnlineAiService,
        OnlineAiRuntimeControl,
        OnlineAiRuntimeIntrospection,
        OnlineAiLifecycleControl {

  private static final Logger LOG = LoggerFactory.getLogger(OnlineAiServiceImpl.class);

  private final InferenceLifecycleManager manager;

  /**
   * Creates a new OnlineAiServiceImpl.
   *
   * @param manager the inference lifecycle manager to delegate to
   */
  public OnlineAiServiceImpl(InferenceLifecycleManager manager) {
    this.manager = manager;
    LOG.info("OnlineAiServiceImpl created");
  }

  @Override
  public void applyRuntimeOverrides(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    applyOverridesInternal(
        llmModelPath,
        contextLength,
        gpuLayers,
        restartPolicy,
        io.justsearch.app.inference.telemetry.TransitionReason.CONFIG_APPLY);
  }

  @Override
  public void applyRuntimeOverridesAdmin(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    applyOverridesInternal(
        llmModelPath,
        contextLength,
        gpuLayers,
        restartPolicy,
        io.justsearch.app.inference.telemetry.TransitionReason.ADMIN_TRIGGERED);
  }

  private void applyOverridesInternal(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy,
      io.justsearch.app.inference.telemetry.TransitionReason transitionReason) {
    InferenceConfig current = manager.currentConfig();
    if (current == null) {
      throw new IllegalStateException("Inference runtime not configured");
    }

    InferenceConfig next = applyOverrides(current, llmModelPath, contextLength, gpuLayers);
    InferenceLifecycleManager.RestartPolicy managerPolicy = mapRestartPolicy(restartPolicy);
    try {
      manager.applyConfig(next, managerPolicy, transitionReason);
    } catch (ModeTransitionException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  @Override
  public DetachExternalServerResult detachExternalServer() {
    try {
      var r = manager.detachExternalServer();
      return new DetachExternalServerResult(r.detached(), r.previousPort(), r.newPort());
    } catch (ModeTransitionException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  @Override
  public RuntimeInfo runtimeInfo() {
    InferenceConfig cfg = manager.currentConfig();
    if (cfg == null) {
      return null;
    }
    return new RuntimeInfo(
        cfg.serverExecutable() == null ? null : cfg.serverExecutable().toString(),
        cfg.modelPath() == null ? null : cfg.modelPath().toString(),
        cfg.mmprojPath() == null ? null : cfg.mmprojPath().toString(),
        cfg.serverPort(),
        cfg.contextSize(),
        cfg.gpuLayers(),
        manager.isUsingExternalLlamaServer());
  }

  @Override
  public ExternalServerStatus externalServerStatus() {
    var d = manager.externalServerDiagnostics();
    return new ExternalServerStatus(
        d.usingExternalLlamaServer(),
        d.verified(),
        d.verificationError(),
        d.modelId(),
        d.contextTokens(),
        d.modelMismatch(),
        d.contextTooSmall(),
        d.adoptedAtMs(),
        d.lastHealthOkAtMs(),
        d.lastHealthError(),
        d.consecutiveHealthFailures());
  }

  @Override
  public String cudaRuntimeWarning() {
    return manager.getCudaRuntimeWarning();
  }

  @Override
  public long lastStartupDurationMs() {
    return manager.getLastStartupDurationMs();
  }

  @Override
  public boolean hasVisionCapability() {
    return manager.hasVisionCapability();
  }

  @Override
  public CompletableFuture<String> visionCompletion(
      String prompt, byte[] imageBytes, int maxTokens) {
    return manager.visionCompletion(prompt, imageBytes, maxTokens);
  }

  @Override
  public String activeModelId() {
    return manager.lastKnownModelId();
  }

  @Override
  public List<FailureRecord>
      recentFailures(int limit) {
    return manager.recentFailures(limit);
  }

  @Override
  public long currentGeneration() {
    return manager.currentGeneration();
  }

  @Override
  public boolean isOnline() {
    return manager.isOnline();
  }

  @Override
  public boolean isIndexing() {
    return manager.isIndexing();
  }

  @Override
  public List<TransitionRecord>
      recentTransitions(int limit) {
    return manager.recentTransitions(limit);
  }

  private static InferenceLifecycleManager.RestartPolicy mapRestartPolicy(RestartPolicy policy) {
    if (policy == null) {
      return InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE;
    }
    return switch (policy) {
      case APPLY_ONLY -> InferenceLifecycleManager.RestartPolicy.APPLY_ONLY;
      case RESTART_ALWAYS -> InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS;
      case RESTART_IF_ONLINE -> InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE;
    };
  }

  private static InferenceConfig applyOverrides(
      InferenceConfig base,
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers) {
    Path serverExe = base.serverExecutable();
    Path modelPath = base.modelPath();
    Path mmprojPath = base.mmprojPath();

    // Allow out-of-band override for BYO llama-server path (set via UI settings -> sysprop).
    ConfigStore cs = ConfigStore.globalOrNull();
    String serverOverride = cs != null && cs.get().ai().serverExe() != null
        ? cs.get().ai().serverExe().toString() : null;
    if (serverOverride != null && !serverOverride.isBlank()) {
      try {
        Path p = Path.of(serverOverride.trim());
        serverExe = p;
      } catch (Exception ignored) {
        // Keep existing.
      }
    }

    if (llmModelPath != null && !llmModelPath.isBlank()) {
      Path resolved = resolveOptionalPath(llmModelPath, base.modelPath().getParent());
      boolean changed = !resolved.toAbsolutePath().normalize().equals(base.modelPath().toAbsolutePath().normalize());
      modelPath = resolved;
      // Defensive: mmproj/model mismatches can fail llama-server startup. When the user explicitly changes
      // the model file path, prefer text-only mode unless mmproj is independently configured elsewhere.
      if (changed) {
        mmprojPath = null;
      }
    }

    int ctx = contextLength != null && contextLength > 0 ? contextLength : base.contextSize();
    // Tempdoc 374 alpha.13 fix A2: 0 from UiSettings means "unset" — defer to
    // base.gpuLayers() which already reflects the resolved config (env vars,
    // sysprops, auto-detection at ordinal 150). The previous `>= 0` check
    // treated UiSettings.gpuLayers default 0 as an explicit override, so every
    // Install AI completion silently clobbered a correctly-resolved 99 with 0
    // — defeating both the auto-detect path and the JUSTSEARCH_LLM_GPU_LAYERS
    // env-var workaround. Explicit user overrides (>0) still take precedence.
    int layers = gpuLayers != null && gpuLayers > 0 ? gpuLayers : base.gpuLayers();

    return new InferenceConfig(
        serverExe,
        modelPath,
        mmprojPath,
        base.serverPort(),
        ctx,
        layers,
        base.vduMode());
  }

  private static Path resolveOptionalPath(String raw, Path fallbackDir) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isBlank()) {
      return fallbackDir;
    }
    Path p = Path.of(trimmed);
    if (p.isAbsolute()) {
      return p;
    }
    if (Files.exists(p)) {
      return p;
    }
    return (fallbackDir == null ? Path.of(System.getProperty("user.dir")) : fallbackDir).resolve(p);
  }

  // ==================== Streaming Methods ====================

  // Tempdoc 499: All streamChat/streamChatWithTools overrides removed. The interface
  // default methods delegate to stream(), which is the single implementation entry point.
  // This eliminates the channel-lossy path — all callers route through StreamSink which
  // exposes all channels (content, reasoning, tools, usage).

  @Override
  public void stream(StreamRequest request, StreamSink sink) {
    int resolved = request.maxTokens() <= 0 ? DEFAULT_QA_TOKENS : request.maxTokens();
    LOG.debug(
        "stream(maxTokens={}, tools={}, sentinel={}) called, messages={}",
        resolved,
        request.tools() != null ? request.tools().size() : 0,
        request.requireSentinel(),
        request.messages() != null ? request.messages().size() : 0);
    manager.stream(request.messages(), request.tools(), resolved,
        sink.onContent(), sink.onReasoning(),
        node -> {
          try {
            sink.onToolCallDelta().accept(node.toString());
          } catch (Exception e) {
            LOG.debug("Tool call delta callback error", e);
          }
        },
        sink.onUsage(), sink.onComplete(), sink.onError(),
        request.sampling(), request.requireSentinel());
  }

  // ==================== Non-Streaming Methods ====================

  @Override
  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    int resolved = maxTokens <= 0 ? DEFAULT_QA_TOKENS : maxTokens;
    LOG.debug(
        "chatCompletion(maxTokens={}, sampling={}) called, messages={}",
        resolved,
        sampling,
        messages != null ? messages.size() : 0);
    return manager.chatCompletion(messages, resolved, sampling);
  }

  @Override
  public CompletableFuture<String> summarize(String content) {
    LOG.debug("summarize called, content length: {}", content != null ? content.length() : 0);
    return manager.summarize(content, DEFAULT_SUMMARY_TOKENS);
  }

  @Override
  public CompletableFuture<String> summarize(String content, int maxTokens) {
    int resolved = maxTokens <= 0 ? DEFAULT_SUMMARY_TOKENS : maxTokens;
    LOG.debug(
        "summarize(maxTokens={}) called, content length: {}",
        resolved,
        content != null ? content.length() : 0);
    return manager.summarize(content, resolved);
  }

  @Override
  public CompletableFuture<String> askQuestion(String question, String context) {
    LOG.debug("askQuestion called, question: {}, context length: {}",
        question, context != null ? context.length() : 0);
    return manager.askQuestion(context, question, DEFAULT_QA_TOKENS);
  }

  // ==================== Status Methods ====================

  @Override
  public boolean isAvailable() {
    return manager.isOnline();
  }

  @Override
  public boolean isStartingUp() {
    return manager.getCurrentMode() == io.justsearch.app.api.Mode.TRANSITIONING;
  }

  @Override
  public Integer llmContextTokens() {
    return manager.lastKnownContextTokens();
  }

  @Override
  public Integer configuredContextTokens() {
    return manager.configuredContextTokens();
  }

  @Override
  public java.util.Optional<Integer> countTokens(String text) {
    return manager.countTokens(text);
  }

  @Override
  public java.util.Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
    return manager.countPromptTokens(messages);
  }

  // ==================== Mode Control Methods ====================

  @Override
  public String getCurrentMode() {
    return manager.getCurrentMode().name().toLowerCase(java.util.Locale.ROOT);
  }

  @Override
  public void switchToOnlineMode() {
    LOG.info("Switching to Online Mode...");
    try {
      manager.switchToOnlineMode();
      LOG.info("Switched to Online Mode");
    } catch (ModeTransitionException e) {
      LOG.error("Failed to switch to Online Mode", e);
      throw new RuntimeException("Failed to switch to Online Mode: " + e.getMessage(), e);
    }
  }

  @Override
  public void switchToIndexingMode() {
    LOG.info("Switching to Indexing Mode...");
    try {
      manager.switchToIndexingMode();
      LOG.info("Switched to Indexing Mode");
    } catch (ModeTransitionException e) {
      LOG.error("Failed to switch to Indexing Mode", e);
      throw new RuntimeException("Failed to switch to Indexing Mode: " + e.getMessage(), e);
    }
  }

  // ==================== OnlineAiLifecycleControl (tempdoc 518 P4) ====================

  @Override
  public void enterVduMode() throws ModeTransitionException {
    manager.enterVduMode();
  }

  @Override
  public void exitVduMode() throws ModeTransitionException {
    manager.exitVduMode();
  }

  @Override
  public void addModeChangeListener(ModeChangeListener listener) {
    manager.addModeChangeListener(listener);
  }

  @Override
  public void removeModeChangeListener(ModeChangeListener listener) {
    manager.removeModeChangeListener(listener);
  }
}
