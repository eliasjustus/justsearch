package io.justsearch.app.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 412 Phase 5: validates the default {@link OnlineAiRuntimeControl#reloadRuntime()}
 * delegates to {@link OnlineAiRuntimeControl#applyRuntimeOverrides} with all-null overrides
 * and {@link OnlineAiRuntimeControl.RestartPolicy#RESTART_IF_ONLINE}, and returns elapsed ms.
 */
@DisplayName("OnlineAiRuntimeControl.reloadRuntime — default implementation")
final class OnlineAiRuntimeControlReloadTest {

  /** Test stub that records the applyRuntimeOverrides call. */
  private static final class Stub implements OnlineAiRuntimeControl {
    final AtomicReference<RestartPolicy> capturedPolicy = new AtomicReference<>();
    final AtomicReference<String> capturedModelPath = new AtomicReference<>();
    final AtomicReference<Integer> capturedContextLength = new AtomicReference<>();
    final AtomicReference<Integer> capturedGpuLayers = new AtomicReference<>();
    long sleepMillis = 0L;

    @Override
    public void applyRuntimeOverrides(
        String llmModelPath,
        Integer contextLength,
        Integer gpuLayers,
        RestartPolicy restartPolicy) {
      capturedModelPath.set(llmModelPath);
      capturedContextLength.set(contextLength);
      capturedGpuLayers.set(gpuLayers);
      capturedPolicy.set(restartPolicy);
      if (sleepMillis > 0) {
        try {
          Thread.sleep(sleepMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public DetachExternalServerResult detachExternalServer() {
      return new DetachExternalServerResult(false, 0, 0);
    }
  }

  @Test
  void reloadRuntimePassesNullOverridesAndRestartIfOnlinePolicy() {
    var stub = new Stub();
    long elapsed = stub.reloadRuntime();
    assertTrue(elapsed >= 0L, "elapsed should be non-negative; got " + elapsed);
    assertEquals(OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE, stub.capturedPolicy.get());
    org.junit.jupiter.api.Assertions.assertNull(stub.capturedModelPath.get());
    org.junit.jupiter.api.Assertions.assertNull(stub.capturedContextLength.get());
    org.junit.jupiter.api.Assertions.assertNull(stub.capturedGpuLayers.get());
  }

  @Test
  void reloadRuntimeReturnsApproxElapsedTime() {
    var stub = new Stub();
    stub.sleepMillis = 50L;
    long start = System.currentTimeMillis();
    long elapsed = stub.reloadRuntime();
    long actual = System.currentTimeMillis() - start;
    assertTrue(elapsed >= 40L, "elapsed should be ~50ms; got " + elapsed);
    // Allow some slack but ensure roughly correct
    assertTrue(elapsed <= actual + 100L, "elapsed " + elapsed + " way over actual " + actual);
  }

  @Test
  void detachExternalServerStillReachableViaSubInterface() {
    var stub = new Stub();
    var result = stub.detachExternalServer();
    assertNotNull(result);
  }
}
