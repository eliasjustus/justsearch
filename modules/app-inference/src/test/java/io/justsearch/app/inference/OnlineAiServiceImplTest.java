package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiRuntimeIntrospection.RuntimeInfo;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiService.AiUsage;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.api.Mode;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class OnlineAiServiceImplTest {

  @Mock InferenceLifecycleManager manager;

  @TempDir Path tmp;

  private OnlineAiServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new OnlineAiServiceImpl(manager);
  }

  @Test
  void getCurrentMode_lowercasesModeName() {
    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    assertEquals("online", service.getCurrentMode());

    when(manager.getCurrentMode()).thenReturn(Mode.TRANSITIONING);
    assertEquals("transitioning", service.getCurrentMode());
  }

  @Test
  void isAvailable_delegatesToIsOnline() {
    when(manager.isOnline()).thenReturn(true);
    assertTrue(service.isAvailable());

    when(manager.isOnline()).thenReturn(false);
    assertFalse(service.isAvailable());
  }

  @Test
  void isStartingUp_trueOnlyForTransitioning() {
    when(manager.getCurrentMode()).thenReturn(Mode.TRANSITIONING);
    assertTrue(service.isStartingUp());

    when(manager.getCurrentMode()).thenReturn(Mode.ONLINE);
    assertFalse(service.isStartingUp());
  }

  @Test
  void summarize_usesDefaultTokens() {
    when(manager.summarize(anyString(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture("result"));

    var unused = service.summarize("some content");

    verify(manager).summarize("some content", OnlineAiService.DEFAULT_SUMMARY_TOKENS);
  }

  @Test
  void summarize_resolvesZeroTokensToDefault() {
    when(manager.summarize(anyString(), anyInt()))
        .thenReturn(CompletableFuture.completedFuture("result"));

    var unused = service.summarize("some content", 0);

    verify(manager).summarize("some content", OnlineAiService.DEFAULT_SUMMARY_TOKENS);
  }

  // Tempdoc 491 §C5: streamSummary + streamAnswer test coverage removed; the interface
  // methods themselves no longer exist. streamChat coverage below remains the canonical
  // path the new shapes call.

  @SuppressWarnings("unchecked")
  @Test
  void stream_resolvesNegativeTokensToDefault() {
    List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "hi"));
    Consumer<String> onChunk = mock(Consumer.class);
    Consumer<String> onComplete = mock(Consumer.class);
    Consumer<Throwable> onError = mock(Consumer.class);

    service.streamChat(messages, -1, onChunk, onComplete, onError);

    verify(manager)
        .stream(
            eq(messages),
            isNull(),
            eq(OnlineAiService.DEFAULT_QA_TOKENS),
            any(), any(), any(), any(), any(), any(),
            isNull(),
            eq(true));
  }

  @Test
  void runtimeInfo_extractsAllFields() {
    Path serverExe = Path.of("/usr/bin/llama-server");
    Path modelPath = Path.of("/models/model.gguf");
    Path mmprojPath = Path.of("/models/mmproj.gguf");

    InferenceConfig cfg =
        new InferenceConfig(serverExe, modelPath, mmprojPath, 8080, 4096, 0, false);
    when(manager.currentConfig()).thenReturn(cfg);
    when(manager.isUsingExternalLlamaServer()).thenReturn(false);

    RuntimeInfo info = service.runtimeInfo();

    assertNotNull(info);
    assertEquals(serverExe.toString(), info.serverExecutable());
    assertEquals(modelPath.toString(), info.modelPath());
    assertEquals(mmprojPath.toString(), info.mmprojPath());
    assertEquals(8080, info.serverPort());
    assertEquals(4096, info.contextSize());
    assertEquals(0, info.gpuLayers());
    assertFalse(info.usingExternalLlamaServer());
  }

  @Test
  void runtimeInfo_returnsNullWhenNoConfig() {
    when(manager.currentConfig()).thenReturn(null);

    assertNull(service.runtimeInfo());
  }

  /**
   * Tempdoc 412 Path C Bug E regression test.
   *
   * <p>{@link OnlineAiRuntimeControl#reloadRuntime()} (default impl) calls
   * {@link io.justsearch.app.api.OnlineAiRuntimeControl#applyRuntimeOverridesAdmin}, and
   * {@link OnlineAiServiceImpl#applyRuntimeOverridesAdmin} must thread
   * {@link io.justsearch.app.inference.telemetry.TransitionReason#ADMIN_TRIGGERED} through the
   * 3-arg {@link InferenceLifecycleManager#applyConfig(InferenceConfig,
   * InferenceLifecycleManager.RestartPolicy, io.justsearch.app.inference.telemetry.TransitionReason)}
   * overload. The original Bug E was: the admin path silently used the 2-arg overload that
   * hardcoded {@code CONFIG_APPLY}, so the {@code reason=admin_triggered} tag never reached the
   * metric stream. This test pins the 3-arg overload + admin-triggered reason on the admin path,
   * preventing silent regression.
   */
  @Test
  void applyRuntimeOverridesAdmin_routesAdminTriggeredReasonToManager() throws Exception {
    InferenceConfig current =
        InferenceConfig.builder()
            .serverExecutable(Path.of("test-server.exe"))
            .modelPath(Path.of("model.gguf"))
            .serverPort(8080)
            .contextSize(4096)
            .gpuLayers(0)
            .build();
    when(manager.currentConfig()).thenReturn(current);

    service.applyRuntimeOverridesAdmin(
        null, null, null, io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);

    verify(manager)
        .applyConfig(
            any(InferenceConfig.class),
            eq(InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE),
            eq(io.justsearch.app.inference.telemetry.TransitionReason.ADMIN_TRIGGERED));
  }

  // -------------------------------------------------------------------------------------------
  // Tempdoc 842 §2.3 — bare-path applies vs. atomic profile applies.
  // -------------------------------------------------------------------------------------------

  /**
   * A bare path has no known projector, so the defensive mmproj drop stays. The profile id must go
   * with it: leaving a stale "standard" claim on someone else's model file would make every
   * surface that reports realized chat identity report a model that is not running.
   */
  @Test
  @DisplayName("applyRuntimeOverrides with a new bare path clears BOTH the mmproj and the profile claim")
  void barePathApplyClearsMmprojAndProfileClaim() throws Exception {
    InferenceConfig current =
        new InferenceConfig(
            Path.of("/bin/llama-server.exe"),
            Path.of("/models/Qwen_Qwen3.5-9B-Q4_K_M.gguf"),
            Path.of("/models/mmproj-F16.gguf"),
            8080,
            4096,
            33,
            false,
            "standard");
    when(manager.currentConfig()).thenReturn(current);

    service.applyRuntimeOverrides(
        "/elsewhere/hand-picked.gguf",
        null,
        null,
        io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.APPLY_ONLY);

    ArgumentCaptor<InferenceConfig> captor = ArgumentCaptor.forClass(InferenceConfig.class);
    verify(manager).applyConfig(captor.capture(), any(), any());
    InferenceConfig next = captor.getValue();
    assertEquals(Path.of("/elsewhere/hand-picked.gguf"), next.modelPath());
    assertNull(next.mmprojPath(), "a bare path drops the projector");
    assertNull(next.chatProfileId(), "a bare path must not carry a stale profile claim");
  }

  /** An unchanged path is not a swap: the pair, and therefore the claim, survives. */
  @Test
  @DisplayName("applyRuntimeOverrides that does not change the model keeps the profile claim")
  void unchangedPathApplyKeepsProfileClaim() throws Exception {
    Path model = Path.of("/models/compact/Qwen3.5-4B-Q4_K_M.gguf");
    InferenceConfig current =
        new InferenceConfig(
            Path.of("/bin/llama-server.exe"),
            model,
            Path.of("/models/compact/mmproj-F16.gguf"),
            8080,
            4096,
            33,
            false,
            "compact");
    when(manager.currentConfig()).thenReturn(current);

    service.applyRuntimeOverrides(
        model.toString(),
        8192,
        null,
        io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.APPLY_ONLY);

    ArgumentCaptor<InferenceConfig> captor = ArgumentCaptor.forClass(InferenceConfig.class);
    verify(manager).applyConfig(captor.capture(), any(), any());
    InferenceConfig next = captor.getValue();
    assertEquals(model, next.modelPath());
    assertEquals(Path.of("/models/compact/mmproj-F16.gguf"), next.mmprojPath());
    assertEquals("compact", next.chatProfileId());
    assertEquals(8192, next.contextSize());
  }

  /**
   * The profile-driven counterpart: model, projector, and id land together, and everything the
   * profile does not name (ctx, GPU layers, port, executable) is carried over untouched.
   */
  @Test
  @DisplayName("applyChatProfile applies model + mmproj + id atomically and leaves ctx/gpuLayers alone")
  void applyChatProfileAppliesTheWholePairAtomically() throws Exception {
    Path modelsDir = tmp.resolve("models");
    Files.createDirectories(modelsDir.resolve("compact"));
    Files.writeString(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), "x");
    Files.writeString(modelsDir.resolve(ChatModelProfile.COMPACT.mmprojFile()), "x");

    InferenceConfig current =
        new InferenceConfig(
            Path.of("/bin/llama-server.exe"),
            modelsDir.resolve(ChatModelProfile.STANDARD.modelFile()),
            modelsDir.resolve(ChatModelProfile.STANDARD.mmprojFile()),
            8082,
            8192,
            33,
            false,
            "standard");
    when(manager.currentConfig()).thenReturn(current);

    ConfigStore prevStore = ConfigStore.globalOrNull();
    String prevModelsDir = System.getProperty("justsearch.models.dir");
    System.setProperty("justsearch.models.dir", modelsDir.toString());
    try {
      TestResolvedConfigHelper.storeFromEnvironment();

      service.applyChatProfile(
          ChatModelProfile.COMPACT,
          io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS);

      ArgumentCaptor<InferenceConfig> captor = ArgumentCaptor.forClass(InferenceConfig.class);
      verify(manager)
          .applyConfig(
              captor.capture(),
              eq(InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS),
              eq(io.justsearch.app.inference.telemetry.TransitionReason.CONFIG_APPLY));
      InferenceConfig next = captor.getValue();
      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), next.modelPath());
      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.mmprojFile()), next.mmprojPath());
      assertEquals("compact", next.chatProfileId());
      // Untouched carry-over — a profile names a model bundle, not a whole runtime.
      assertEquals(8192, next.contextSize());
      assertEquals(33, next.gpuLayers());
      assertEquals(8082, next.serverPort());
      assertEquals(Path.of("/bin/llama-server.exe"), next.serverExecutable());
    } finally {
      if (prevModelsDir == null) {
        System.clearProperty("justsearch.models.dir");
      } else {
        System.setProperty("justsearch.models.dir", prevModelsDir);
      }
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  /** Missing projector on disk warns and degrades to text-only rather than failing the switch. */
  @Test
  @DisplayName("applyChatProfile nulls a missing mmproj instead of failing the switch")
  void applyChatProfileDegradesToTextOnlyWhenMmprojMissing() throws Exception {
    Path modelsDir = tmp.resolve("models-nomm");
    Files.createDirectories(modelsDir.resolve("compact"));
    Files.writeString(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), "x");

    InferenceConfig current =
        new InferenceConfig(
            Path.of("/bin/llama-server.exe"),
            modelsDir.resolve(ChatModelProfile.STANDARD.modelFile()),
            null,
            8082,
            4096,
            0,
            false,
            "standard");
    when(manager.currentConfig()).thenReturn(current);

    ConfigStore prevStore = ConfigStore.globalOrNull();
    String prevModelsDir = System.getProperty("justsearch.models.dir");
    System.setProperty("justsearch.models.dir", modelsDir.toString());
    try {
      TestResolvedConfigHelper.storeFromEnvironment();

      service.applyChatProfile(
          ChatModelProfile.COMPACT,
          io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.APPLY_ONLY);

      ArgumentCaptor<InferenceConfig> captor = ArgumentCaptor.forClass(InferenceConfig.class);
      verify(manager).applyConfig(captor.capture(), any(), any());
      InferenceConfig next = captor.getValue();
      assertEquals(modelsDir.resolve(ChatModelProfile.COMPACT.modelFile()), next.modelPath());
      assertNull(next.mmprojPath());
      assertEquals("compact", next.chatProfileId());
    } finally {
      if (prevModelsDir == null) {
        System.clearProperty("justsearch.models.dir");
      } else {
        System.setProperty("justsearch.models.dir", prevModelsDir);
      }
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  /**
   * Tempdoc 842 review N4: the MODEL file is fail-closed, unlike the projector above. A missing
   * model would restart llama-server onto a path it refuses to open — an outage instead of a clean
   * error — so the throw must land BEFORE any applyConfig reaches the manager, leaving the running
   * engine exactly as it was. (The HTTP path fails earlier with a typed MODEL_NOT_FOUND; this pins
   * the verdict for direct OnlineAiRuntimeControl callers.)
   */
  @Test
  @DisplayName("applyChatProfile throws on a missing model file and never touches the engine")
  void applyChatProfileFailsClosedWhenModelMissing() throws Exception {
    Path modelsDir = tmp.resolve("models-nomodel");
    Files.createDirectories(modelsDir.resolve("compact"));
    // Only the projector is on disk — the model file is not.
    Files.writeString(modelsDir.resolve(ChatModelProfile.COMPACT.mmprojFile()), "x");
    Path expectedModel = modelsDir.resolve(ChatModelProfile.COMPACT.modelFile());

    InferenceConfig current =
        new InferenceConfig(
            Path.of("/bin/llama-server.exe"),
            modelsDir.resolve(ChatModelProfile.STANDARD.modelFile()),
            modelsDir.resolve(ChatModelProfile.STANDARD.mmprojFile()),
            8082,
            4096,
            33,
            false,
            "standard");
    when(manager.currentConfig()).thenReturn(current);

    ConfigStore prevStore = ConfigStore.globalOrNull();
    String prevModelsDir = System.getProperty("justsearch.models.dir");
    System.setProperty("justsearch.models.dir", modelsDir.toString());
    try {
      TestResolvedConfigHelper.storeFromEnvironment();

      IllegalStateException thrown =
          assertThrows(
              IllegalStateException.class,
              () ->
                  service.applyChatProfile(
                      ChatModelProfile.COMPACT,
                      io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.RESTART_ALWAYS));

      String msg = thrown.getMessage();
      assertTrue(msg.contains("compact"), "names the profile id: " + msg);
      assertTrue(msg.contains(expectedModel.toString()), "names the resolved path: " + msg);
      assertTrue(
          msg.contains("scripts/dev/fetch-compact-model.mjs"),
          "names the compact remedy, matching RuntimeActivationService: " + msg);

      verify(manager, never()).applyConfig(any(), any(), any());
      verify(manager, never()).applyConfig(any(), any());
    } finally {
      if (prevModelsDir == null) {
        System.clearProperty("justsearch.models.dir");
      } else {
        System.setProperty("justsearch.models.dir", prevModelsDir);
      }
      TestResolvedConfigHelper.restoreGlobal(prevStore);
    }
  }

  /** Counterpart pin: the non-admin path must NOT use ADMIN_TRIGGERED. */
  @Test
  void applyRuntimeOverrides_routesConfigApplyReasonToManager() throws Exception {
    InferenceConfig current =
        InferenceConfig.builder()
            .serverExecutable(Path.of("test-server.exe"))
            .modelPath(Path.of("model.gguf"))
            .serverPort(8080)
            .contextSize(4096)
            .gpuLayers(0)
            .build();
    when(manager.currentConfig()).thenReturn(current);

    service.applyRuntimeOverrides(
        null, null, null, io.justsearch.app.api.OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);

    verify(manager)
        .applyConfig(
            any(InferenceConfig.class),
            eq(InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE),
            eq(io.justsearch.app.inference.telemetry.TransitionReason.CONFIG_APPLY));
  }
}
