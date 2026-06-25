package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.javalin.http.Context;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/**
 * Tempdoc 576 §15 — GET /api/governance/state serves the committed `registry` projection (gate roster /
 * exception ceiling / strength floors / class-size debt) from the classpath resource, independent of
 * whether a SARIF run exists. Server-side proof of the projection merge (the live populated browser view
 * is served by the same controller once the code reaches a dev-runner-served checkout).
 */
class GovernanceStateControllerTest {

  @Test
  @DisplayName("handle() includes the committed registry projection even with no SARIF run")
  void servesRegistryProjection() {
    // A SARIF path that does not exist → the SARIF-absent branch; registry must still be present.
    GovernanceStateController controller =
        new GovernanceStateController(Path.of("does-not-exist-governance-report.sarif"));
    Context ctx = mock(Context.class);

    controller.handle(ctx);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> out = (Map<String, Object>) captor.getValue();

    assertTrue(out.containsKey("registry"), "response must carry the §15 registry projection");
    JsonNode registry = (JsonNode) out.get("registry");
    assertNotNull(registry, "registry projection resource must be bundled on the classpath");
    assertTrue(
        registry.path("gateCount").asInt(0) > 0,
        "the registry projection must enumerate the gate roster (gateCount > 0)");
    assertTrue(registry.has("exceptions"), "registry projection must include the exception ceiling");
    assertTrue(registry.has("strengthFloors"), "registry projection must include strength floors");
    assertTrue(registry.has("classSizeDebt"), "registry projection must include class-size debt");
  }

  @Test
  @DisplayName("handle() aggregates per-gate activation efficacy from local history + classifies status")
  void servesEfficacyProjection(@TempDir Path tmp) throws IOException {
    // Two runs of a gate the roster knows (class-size — present in the committed projection):
    // one clean pass, one with findings → totalRuns=2, runsWithFindings=1, error=3.
    // Plus one run of a gate NOT in the roster → status "orphaned".
    Path history = tmp.resolve("governance-history.ndjson");
    Files.writeString(
        history,
        """
        {"ts":"2026-06-21T05:00:00Z","gate":"class-size","verdict":"pass","findings":{"error":0,"warning":0,"note":0}}
        {"ts":"2026-06-21T06:00:00Z","gate":"class-size","verdict":"fail","findings":{"error":3,"warning":0,"note":1}}
        {"ts":"2026-06-21T07:00:00Z","gate":"retired-gate-xyz","verdict":"pass","findings":{"error":0,"warning":0,"note":0}}
        """);
    GovernanceStateController controller =
        new GovernanceStateController(Path.of("does-not-exist.sarif"), history);
    Context ctx = mock(Context.class);

    controller.handle(ctx);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> out = (Map<String, Object>) captor.getValue();

    assertTrue(out.containsKey("efficacy"), "response must carry the §17 efficacy projection");
    @SuppressWarnings("unchecked")
    Map<String, Object> efficacy = (Map<String, Object>) out.get("efficacy");
    assertEquals(true, efficacy.get("available"), "efficacy available when history exists");
    assertEquals("local", efficacy.get("scope"), "efficacy is local-runtime / dev-skewed");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> byGate = (List<Map<String, Object>>) efficacy.get("byGate");
    Map<String, Object> classSize =
        byGate.stream().filter(g -> "class-size".equals(g.get("gate"))).findFirst().orElseThrow();
    assertEquals(2, classSize.get("totalRuns"), "two runs aggregated");
    assertEquals(1, classSize.get("runsWithFindings"), "one run had findings");
    assertEquals(3, classSize.get("error"), "cumulative errors summed");
    assertEquals("fail", classSize.get("lastVerdict"), "last verdict reflects the most recent run");
    assertEquals("active", classSize.get("status"), "a roster gate with runs is active");

    Map<String, Object> orphan =
        byGate.stream().filter(g -> "retired-gate-xyz".equals(g.get("gate"))).findFirst().orElseThrow();
    assertEquals("orphaned", orphan.get("status"), "history gate not in the roster is orphaned");

    // A roster gate with no history line must appear as never-fired (0 local runs, not dead).
    boolean hasNeverFired = byGate.stream().anyMatch(g -> "never-fired".equals(g.get("status")));
    assertTrue(hasNeverFired, "roster gates absent from local history surface as never-fired");
  }
}
