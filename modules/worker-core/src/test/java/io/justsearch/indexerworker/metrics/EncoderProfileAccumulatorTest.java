package io.justsearch.indexerworker.metrics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EncoderProfileAccumulatorTest {

  @AfterEach
  void cleanup() {
    OperationalMetrics metrics = OperationalMetrics.getInstance();
    for (String name : List.of("test-encoder", "test-embed", "test-splade")) {
      metrics.deregisterEncoder(name);
    }
  }

  @Test
  void snapshotReturnsNullWhenNoCalls() {
    var acc = new EncoderProfileAccumulator("tokenize", "ort");
    assertNull(acc.snapshot());
    assertEquals(0, acc.callCount());
  }

  @Test
  void snapshotReturnsCumulativeTotals() {
    var acc = new EncoderProfileAccumulator("tokenize", "ort");

    // Two calls: tokenize 2000ns, ort 4000ns each
    // recordOrtCall internally accumulates the "ort" phase — no separate addPhaseNs("ort") needed
    acc.addPhaseNs("tokenize", 2000_000); // 2ms in ns
    acc.recordOrtCall(4000_000);

    acc.addPhaseNs("tokenize", 3000_000); // 3ms in ns
    acc.recordOrtCall(5000_000);

    assertEquals(2, acc.callCount());

    EncoderProfileSnapshot snap = acc.snapshot();
    assertNotNull(snap);
    assertEquals(2, snap.calls());

    // phaseTotalUs should be cumulative totals in microseconds
    Map<String, Long> totals = snap.phaseTotalUs();
    assertEquals(5000, totals.get("tokenize")); // (2000000 + 3000000) / 1000
    assertEquals(9000, totals.get("ort")); // (4000000 + 5000000) / 1000

    // ORT distribution
    assertEquals(4000, snap.ortMinUs()); // 4000000 / 1000
    assertEquals(5000, snap.ortMaxUs()); // 5000000 / 1000
    assertTrue(snap.ortP50Us() > 0);
  }

  @Test
  void resetZerosAllCounters() {
    var acc = new EncoderProfileAccumulator("tokenize", "ort");
    acc.addPhaseNs("tokenize", 1000_000);
    acc.recordOrtCall(2000_000);
    assertEquals(1, acc.callCount());

    acc.reset();

    assertEquals(0, acc.callCount());
    assertNull(acc.snapshot());
  }

  @Test
  void operationalMetricsRegistration() {
    var acc = new EncoderProfileAccumulator("tokenize", "ort");
    acc.addPhaseNs("tokenize", 1000_000);
    acc.recordOrtCall(2000_000);

    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.registerEncoder("test-encoder", acc);

    Map<String, EncoderProfileSnapshot> profiles = metrics.getEncoderProfiles();
    assertTrue(profiles.containsKey("test-encoder"));

    EncoderProfileSnapshot snap = profiles.get("test-encoder");
    assertEquals(1, snap.calls());
    assertEquals(1000, snap.phaseTotalUs().get("tokenize"));

    // Clean up: reset to avoid leaking into other tests
    metrics.resetAll();
  }

  @Test
  void multipleEncodersRegistered() {
    var embedAcc = new EncoderProfileAccumulator("tokenize", "tensor", "ort", "extract");
    var spladeAcc = new EncoderProfileAccumulator("tokenize", "ort", "postProcess");

    embedAcc.recordOrtCall(1000_000);
    spladeAcc.recordOrtCall(2000_000);

    OperationalMetrics metrics = OperationalMetrics.getInstance();
    metrics.registerEncoder("test-embed", embedAcc);
    metrics.registerEncoder("test-splade", spladeAcc);

    Map<String, EncoderProfileSnapshot> profiles = metrics.getEncoderProfiles();
    assertTrue(profiles.containsKey("test-embed"));
    assertTrue(profiles.containsKey("test-splade"));
    assertEquals(1000, profiles.get("test-embed").phaseTotalUs().get("ort"));
    assertEquals(2000, profiles.get("test-splade").phaseTotalUs().get("ort"));

    // Verify reset clears accumulators but keeps registration
    metrics.resetAll();
    assertEquals(0, embedAcc.callCount());
    profiles = metrics.getEncoderProfiles();
    // After reset, no calls recorded so snapshot returns null → empty map
    assertFalse(profiles.containsKey("test-embed"));
  }

  @Test
  void addPhaseNsWorksWithUnregisteredPhase() {
    var acc = new EncoderProfileAccumulator("tokenize");
    // Add to a phase not pre-registered
    acc.addPhaseNs("newPhase", 1000_000);
    acc.addPhaseNs("tokenize", 2000_000);
    acc.recordOrtCall(500_000);

    EncoderProfileSnapshot snap = acc.snapshot();
    assertNotNull(snap);
    assertEquals(1000, snap.phaseTotalUs().get("newPhase"));
    assertEquals(2000, snap.phaseTotalUs().get("tokenize"));
  }
}
