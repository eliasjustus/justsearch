package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.OnlineAiRuntimeIntrospection.RuntimeInfo;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiService.AiUsage;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.api.Mode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class OnlineAiServiceImplTest {

  @Mock InferenceLifecycleManager manager;

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
