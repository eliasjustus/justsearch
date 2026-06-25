package io.justsearch.app.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.bootstrap.BootTrace;
import io.justsearch.app.services.bootstrap.PhaseRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tempdoc 541 fix-pass F.1 — BrainAssembly projection unit coverage. */
@DisplayName("BrainAssembly — tempdoc 541 §5.1 + fix-pass C.3 projection semantics")
class BrainAssemblyTest {

  @Test
  @DisplayName("project(null, t0, t1) yields Degraded ilm-construction with timing window")
  void projectNullIlm() {
    BrainAssembly b = BrainAssembly.project(null, 1000L, 1014L);
    assertNull(b.inferenceManager());
    assertFalse(b.inferenceManagerOpt().isPresent());
    BootTrace trace = b.bootTrace();
    assertEquals(BootTrace.BRAIN, trace.process());
    assertEquals(1000L, trace.bootStartedAtMs());
    assertEquals(Long.valueOf(1014L), trace.bootCompletedAtMs());
    assertEquals(1, trace.phases().size());
    PhaseRecord ilm = trace.phases().get(0);
    assertEquals("ilm-construction", ilm.name());
    assertEquals(PhaseRecord.DEGRADED, ilm.outcome());
    assertEquals("inference.not_configured", ilm.reasonCode());
    assertEquals(1000L, ilm.startedAtMs());
    assertEquals(Long.valueOf(1014L), ilm.completedAtMs());
    assertEquals(Long.valueOf(14L), ilm.durationMs());
  }

  @Test
  @DisplayName("project(ilm, t0, t1) — Ready with non-null inferenceManager")
  void projectReady() {
    // Mockito mock — we don't invoke any ILM methods; the factory only does a null check.
    io.justsearch.app.inference.InferenceLifecycleManager ilm =
        org.mockito.Mockito.mock(io.justsearch.app.inference.InferenceLifecycleManager.class);
    BrainAssembly b = BrainAssembly.project(ilm, 2000L, 2020L);
    assertTrue(b.inferenceManagerOpt().isPresent());
    PhaseRecord r = b.bootTrace().phases().get(0);
    assertEquals(PhaseRecord.READY, r.outcome());
    assertNull(r.reasonCode());
    assertEquals(Long.valueOf(20L), r.durationMs());
  }
}
