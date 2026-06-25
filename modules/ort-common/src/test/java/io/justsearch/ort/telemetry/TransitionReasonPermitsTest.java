package io.justsearch.ort.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the {@link TransitionReason} permit set matches the documented set in tempdoc 414. If
 * a future agent adds a permit, this test fails until the documented list (and the
 * {@code OrtSessionTelemetryAdapter} exhaustive switch) are updated. Belt-and-suspenders for
 * the compile-time exhaustiveness check on the adapter side.
 */
@DisplayName("TransitionReason — permit-coverage contract")
final class TransitionReasonPermitsTest {

  private static final Set<String> DOCUMENTED_PERMITS =
      new LinkedHashSet<>(
          Arrays.asList(
              "GpuInitialized",
              "GpuInitFailed",
              "GpuReleaseCompleted",
              "GpuReleaseFailed",
              "GpuFallbackTaken",
              "CpuSessionRecreated",
              "GpuRetryAttempted"));

  @Test
  @DisplayName("permit set matches the tempdoc-414 documented list")
  void permitSetMatchesDocumented() {
    Class<?>[] permits = TransitionReason.class.getPermittedSubclasses();
    assertTrue(permits != null && permits.length > 0, "TransitionReason must be sealed with permits");
    Set<String> actual =
        Arrays.stream(permits).map(Class::getSimpleName).collect(Collectors.toCollection(LinkedHashSet::new));
    assertEquals(
        DOCUMENTED_PERMITS,
        actual,
        "TransitionReason permits must match the documented set in tempdoc 414. If you added"
            + " a permit, update DOCUMENTED_PERMITS here AND add the corresponding case in"
            + " OrtSessionTelemetryAdapter#onTransition AND a metric definition in"
            + " OrtSessionMetricCatalog.");
  }

  @Test
  @DisplayName("every permit's consumer() is non-null when constructed with a non-null name")
  void consumerAccessor() {
    String name = "embed";
    TransitionReason[] sample = {
      new TransitionReason.GpuInitialized(name),
      new TransitionReason.GpuInitFailed(name, FailureCause.UNKNOWN),
      new TransitionReason.GpuReleaseCompleted(name),
      new TransitionReason.GpuReleaseFailed(name),
      new TransitionReason.GpuFallbackTaken(name),
      new TransitionReason.CpuSessionRecreated(name, CpuRecreateCause.UNKNOWN),
      new TransitionReason.GpuRetryAttempted(name, 60_000L),
    };
    for (TransitionReason r : sample) {
      assertEquals(name, r.consumer(), "consumer() should round-trip for " + r.getClass().getSimpleName());
    }
  }
}
