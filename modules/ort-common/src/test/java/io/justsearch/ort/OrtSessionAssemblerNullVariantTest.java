package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.ort.telemetry.AssemblerEvent;
import io.justsearch.ort.telemetry.AssemblerFailureKind;
import io.justsearch.ort.telemetry.RecordingOrtSessionTelemetryEvents;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 414 + observations.md L68 regression: when {@code OrtSessionAssembler.buildManager}
 * is called with a {@link Composition} whose policy has a {@code null} variant (the deliberate
 * shape produced by {@link ModelSessionPolicy#forFallback}), the assembler must
 *
 * <ol>
 *   <li>emit {@link AssemblerEvent.Failed} with kind {@code NULL_VARIANT} for observability, and
 *   <li>treat the null variant as implicit-CPU (no NPE — the pre-L68 fix dereferenced
 *       {@code policy.variant().executionProvider()} on the next line and crashed).
 * </ol>
 *
 * <p>The non-existent model artifact will still cause the handle construction to fail
 * downstream (file not found / IO error from the native ORT call) — that's expected and
 * distinct from the original NPE. The test asserts the failure type is NOT
 * {@link NullPointerException}, ensuring the L68 fix sticks.
 */
@DisplayName("OrtSessionAssembler — null-variant CPU-fallback contract (L68 + tempdoc 414)")
final class OrtSessionAssemblerNullVariantTest {

  @Test
  @DisplayName("buildManager emits NULL_VARIANT event and falls back to CPU without NPE")
  void emitsAndContinuesOnNullVariant() {
    Path nonexistent = Path.of("nonexistent/model.onnx");
    ModelSessionPolicy policy =
        ModelSessionPolicy.forFallback(
            /* gpuConfig= */ null,
            /* cpuOptLevel= */ null,
            /* deferCpuSession= */ false,
            /* gpuRetryEnabled= */ false,
            NativeSessionHandle.DEFAULT_GPU_RETRY_INTERVAL_MS);
    Composition comp =
        new Composition(
            RuntimePolicy.defaults(), policy, new ModelArtifacts(nonexistent, nonexistent));
    RecordingOrtSessionTelemetryEvents recorder = new RecordingOrtSessionTelemetryEvents();

    // The handle construction will fail due to the non-existent model artifact, but the
    // failure must NOT be an NPE from the variant() deref. Any other exception is acceptable
    // here — the test isolates the L68 contract.
    Throwable thrown =
        assertThrows(
            Throwable.class,
            () ->
                OrtSessionAssembler.buildManager("test-consumer", comp, () -> false, recorder));
    if (thrown instanceof NullPointerException) {
      throw new AssertionError(
          "L68 regression: buildManager NPE'd on null variant — should fall back to CPU",
          thrown);
    }

    // Observability check: the NULL_VARIANT event must still fire so the failure class is
    // visible in stress-lane telemetry.
    assertEquals(
        1,
        recorder.assemblerEvents.size(),
        "expected exactly one AssemblerEvent (NULL_VARIANT failure), got: "
            + recorder.assemblerEvents);
    AssemblerEvent emitted = recorder.assemblerEvents.get(0);
    AssemblerEvent.Failed failed = assertInstanceOf(AssemblerEvent.Failed.class, emitted);
    assertEquals("test-consumer", failed.consumer());
    assertEquals(AssemblerFailureKind.NULL_VARIANT, failed.kind());
  }
}
