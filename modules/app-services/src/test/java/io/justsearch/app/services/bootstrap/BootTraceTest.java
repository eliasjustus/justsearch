package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 541 fix-pass F.1 — BootTrace + Builder unit coverage. */
@DisplayName("BootTrace — tempdoc 541 §4.2 + fix-pass thread-safety contract")
class BootTraceTest {

  @Test
  @DisplayName("Builder.record / seal / totalDurationMs happy path")
  void recordAndSeal() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.HEAD);
    b.record(PhaseRecord.ready("infra", 100, 110, null));
    b.record(PhaseRecord.ready("service", 110, 150, null));
    BootTrace sealed = b.seal();
    assertEquals(BootTrace.HEAD, sealed.process());
    assertEquals(2, sealed.phases().size());
    assertTrue(sealed.bootCompletedAtMs() != null);
    assertTrue(sealed.totalDurationMs().isPresent());
    assertTrue(sealed.totalDurationMs().get() >= 0);
  }

  @Test
  @DisplayName("seal is idempotent — repeated calls return the same instance")
  void sealIsIdempotent() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.HEAD);
    BootTrace first = b.seal();
    BootTrace second = b.seal();
    BootTrace third = b.seal();
    assertSame(first, second);
    assertSame(second, third);
  }

  @Test
  @DisplayName("record after seal is a no-op")
  void recordAfterSealNoOp() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.HEAD);
    b.record(PhaseRecord.ready("a", 1, 2, null));
    b.seal();
    int sizeBefore = b.seal().phases().size();
    b.record(PhaseRecord.ready("b", 3, 4, null));
    assertEquals(sizeBefore, b.seal().phases().size(), "post-seal records must not append");
  }

  @Test
  @DisplayName("snapshot before seal returns in-progress trace with null bootCompletedAtMs")
  void snapshotInProgress() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.WORKER);
    b.record(PhaseRecord.ready("a", 100, 110, null));
    BootTrace snap = b.snapshot();
    assertEquals(BootTrace.WORKER, snap.process());
    assertEquals(1, snap.phases().size());
    assertNull(snap.bootCompletedAtMs(), "in-progress snapshot has null completion timestamp");
    assertTrue(snap.totalDurationMs().isEmpty());
  }

  @Test
  @DisplayName("snapshot after seal returns the sealed instance")
  void snapshotAfterSeal() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.HEAD);
    BootTrace sealed = b.seal();
    assertSame(sealed, b.snapshot());
  }

  @Test
  @DisplayName("compact constructor defensively copies the phases list")
  void compactConstructorDefensiveCopy() {
    java.util.ArrayList<PhaseRecord> phases = new java.util.ArrayList<>();
    phases.add(PhaseRecord.ready("a", 1, 2, null));
    BootTrace trace = new BootTrace(BootTrace.HEAD, 0, 100L, phases);
    phases.clear();
    assertEquals(1, trace.phases().size(), "external mutation must not affect the trace");
  }

  @Test
  @DisplayName("compact constructor rejects null/blank process")
  void compactConstructorValidation() {
    assertThrows(
        IllegalArgumentException.class, () -> new BootTrace(null, 0, 100L, List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> new BootTrace("  ", 0, 100L, List.of()));
  }

  @Test
  @DisplayName("phase(name) returns the matching record or empty")
  void phaseByName() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.HEAD);
    b.record(PhaseRecord.ready("infra", 1, 2, null));
    b.record(PhaseRecord.ready("service", 2, 3, null));
    BootTrace sealed = b.seal();
    assertTrue(sealed.phase("infra").isPresent());
    assertEquals("infra", sealed.phase("infra").get().name());
    assertTrue(sealed.phase("missing").isEmpty());
    assertTrue(sealed.phase(null).isEmpty());
  }

  @Test
  @DisplayName("phasesSoFar returns a defensive copy")
  void phasesSoFarDefensive() {
    BootTrace.Builder b = new BootTrace.Builder(BootTrace.HEAD);
    b.record(PhaseRecord.ready("a", 1, 2, null));
    List<PhaseRecord> first = b.phasesSoFar();
    b.record(PhaseRecord.ready("b", 3, 4, null));
    List<PhaseRecord> second = b.phasesSoFar();
    assertEquals(1, first.size(), "first snapshot is independent of subsequent appends");
    assertEquals(2, second.size());
    assertNotNull(first);
  }
}
