package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import ai.onnxruntime.OrtException;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NativeSessionHandle}.
 *
 * <p>Tests static utilities and state-machine behavior that don't require real ORT sessions. The
 * full lifecycle (selectSession, tryCreateGpuSession, releaseGpuSession) is tested indirectly
 * through consumer integration tests in worker-core and reranker modules.
 *
 * <p>Tempdoc 397 §14.26 T1-B: migrated off the flat Builder setters ({@code .gpuConfig},
 * {@code .deferCpuSession}, etc.) to the policy-record surface
 * ({@link ModelSessionPolicy#forFallback}).
 */
@DisplayName("NativeSessionHandle")
class NativeSessionHandleTest {

  private static final RuntimePolicy DEFAULT_RUNTIME = RuntimePolicy.defaults();

  private static ModelSessionPolicy cpuOnlyDeferred() {
    return ModelSessionPolicy.forFallback(
        /* gpuConfig= */ null,
        /* cpuOptLevel= */ null,
        /* deferCpuSession= */ true,
        /* gpuRetryEnabled= */ true,
        NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
  }

  private static ModelSessionPolicy gpuDeferred() {
    return ModelSessionPolicy.forFallback(
        new GpuSessionConfig(0, 512L * 1024 * 1024),
        /* cpuOptLevel= */ null,
        /* deferCpuSession= */ true,
        /* gpuRetryEnabled= */ true,
        NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
  }

  @Nested
  @DisplayName("isBfcArenaFailure — BFC arena allocation failure detection")
  class BfcArenaFailureDetection {

    @Test
    void detectsArenaOom() {
      var e =
          new OrtException(
              "BFCArena::AllocateRawInternal: Available memory of 536870912"
                  + " is smaller than requested bytes of 1073741824");
      assertTrue(NativeSessionHandle.isBfcArenaFailure(e));
    }

    @Test
    void rejectsGenericOrtException() {
      var e = new OrtException("Session creation failed: CUDA driver version is insufficient");
      assertFalse(NativeSessionHandle.isBfcArenaFailure(e));
    }

    @Test
    void handlesNullMessage() {
      var e = new OrtException((String) null);
      assertFalse(NativeSessionHandle.isBfcArenaFailure(e));
    }

    @Test
    void rejectsPartialMatch() {
      // Only "AllocateRawInternal" without the memory message — not a BFC failure
      var e = new OrtException("AllocateRawInternal: some other error");
      assertFalse(NativeSessionHandle.isBfcArenaFailure(e));
    }
  }

  @Nested
  @DisplayName("Builder — configuration validation")
  class BuilderConfiguration {

    @Test
    void builderRejectsNullConsumerName() {
      // NPE fires at the factory entry via requireNonNull (§14.28 U5 restored the
      // NPE-specific contract that T1-B weakened to Exception.class).
      assertThrows(
          NullPointerException.class,
          () -> NativeSessionHandle.builder(null, Path.of("model.onnx")));
    }

    @Test
    void builderRejectsNullModelPath() {
      assertThrows(
          NullPointerException.class, () -> NativeSessionHandle.builder("test", null));
    }

    @Test
    void buildFailsGracefullyOnMissingModel() {
      // Building with a non-existent model path should throw OrtException
      // (from OnnxSessionCache trying to load the model), not NPE or other errors
      Path nonexistent = Path.of("nonexistent/model.onnx");
      var builder =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(
                  ModelSessionPolicy.forFallback(
                      null,
                      null,
                      /* deferCpuSession= */ false,
                      true,
                      NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS));
      assertThrows(Exception.class, () -> builder.build());
    }

    @Test
    void buildWithDeferredCpuDoesNotFailOnMissingModel() throws OrtException {
      // When CPU session is deferred and GPU is configured (but won't actually load),
      // the build itself should succeed — no session is created at construction
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(gpuDeferred())
              .build()) {
        // Build succeeds — no sessions created yet
        assertNull(manager.peekCpuSession());
        assertTrue(manager.isGpuConfigured());
        assertFalse(manager.isGpuAvailable());
      }
    }

    @Test
    void builderRejectsMissingRuntime() {
      // T1-B: runtime is a required Builder input.
      Path nonexistent = Path.of("nonexistent/model.onnx");
      assertThrows(
          NullPointerException.class,
          () ->
              NativeSessionHandle.builder("test", nonexistent)
                  .policy(cpuOnlyDeferred())
                  .build());
    }

    @Test
    void builderRejectsMissingPolicy() {
      // T1-B: policy is a required Builder input.
      Path nonexistent = Path.of("nonexistent/model.onnx");
      assertThrows(
          NullPointerException.class,
          () ->
              NativeSessionHandle.builder("test", nonexistent)
                  .runtime(DEFAULT_RUNTIME)
                  .build());
    }
  }

  @Nested
  @DisplayName("OrtCudaStatus — initial state")
  class CudaStatusInitialState {

    @Test
    void cpuOnlyReturnsNotConfigured() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        OrtCudaStatus status = manager.status();
        assertFalse(status.configured());
        assertFalse(status.attempted());
        assertFalse(status.available());
        assertEquals("GPU not configured", status.failureReason());
      }
    }

    @Test
    void gpuConfiguredReturnsPending() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(gpuDeferred())
              .build()) {
        OrtCudaStatus status = manager.status();
        assertTrue(status.configured());
        assertFalse(status.attempted());
        assertFalse(status.available());
      }
    }
  }

  @Nested
  @DisplayName("Observability — state queries")
  class ObservabilityState {

    @Test
    void isGpuConfiguredFalseByDefault() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        assertFalse(manager.isGpuConfigured());
        assertFalse(manager.isGpuAvailable());
      }
    }

    @Test
    void isGpuConfiguredTrueWhenSet() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(gpuDeferred())
              .build()) {
        assertTrue(manager.isGpuConfigured());
        // GPU is not yet available (lazy init, no real CUDA)
        assertFalse(manager.isGpuAvailable());
      }
    }

    @Test
    void peekCpuSessionNullWhenDeferred() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        assertNull(manager.peekCpuSession());
      }
    }
  }

  @Nested
  @DisplayName("reportCpuSessionFailure — deferred recreation")
  class CpuSessionFailureRecovery {

    @Test
    void reportDoesNotThrow() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      try (var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build()) {
        // Calling reportCpuSessionFailure when cpuSession is null should not throw
        assertDoesNotThrow(
            () ->
                manager.reportCpuSessionFailure(
                    io.justsearch.ort.telemetry.CpuRecreateCause.UNKNOWN));
      }
    }

    @Test
    void closeIsIdempotent() throws OrtException {
      Path nonexistent = Path.of("nonexistent/model.onnx");
      var manager =
          NativeSessionHandle.builder("test", nonexistent)
              .runtime(DEFAULT_RUNTIME)
              .policy(cpuOnlyDeferred())
              .build();
      manager.close();
      assertDoesNotThrow(manager::close); // second close is safe
    }
  }
}
