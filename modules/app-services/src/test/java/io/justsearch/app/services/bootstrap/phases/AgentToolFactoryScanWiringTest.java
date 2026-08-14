package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.justsearch.app.observability.ledger.ScanRollupLedger;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.ScanProgressRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 832 (lane D) — the agent-owned adapter's scan observability. {@code IngestTool} calls
 * {@code agentSearchAdapter::scanRoot}, but only the controller-owned adapter ever had the
 * scan-progress registry / rollup ledger bound, so an agent- or MCP-driven directory ingest emitted
 * no SSE progress and left no rollup row. These assertions read the adapter's bound collaborators
 * back (the adapter exposes no getters — a reflective read keeps the fix from needing a
 * production-side test seam).
 */
@DisplayName("AgentToolFactory.bindScanObservability")
final class AgentToolFactoryScanWiringTest {

  // KnowledgeHttpApiAdapter's constructor builds a KnowledgeSearchEngine, which reads the global
  // reranker config — so the adapter cannot be constructed without a published ConfigStore.
  private ConfigStore previousConfigStore;

  @BeforeEach
  void publishConfigStore() {
    previousConfigStore = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
  }

  @AfterEach
  void restoreConfigStore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  private static Object boundField(KnowledgeHttpApiAdapter adapter, String name) {
    try {
      Field f = KnowledgeHttpApiAdapter.class.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(adapter);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(
          "KnowledgeHttpApiAdapter." + name + " is gone — re-point this wiring assertion", e);
    }
  }

  private static KnowledgeHttpApiAdapter agentAdapter() {
    return new KnowledgeHttpApiAdapter(mock(KnowledgeServerBootstrap.class));
  }

  @Test
  @DisplayName("binds registry + ledger onto the agent adapter (agent/MCP ingest emitted neither)")
  void bindsBoth() {
    KnowledgeHttpApiAdapter adapter = agentAdapter();
    assertNull(boundField(adapter, "scanProgressRegistry"), "unbound before the wiring call");
    assertNull(boundField(adapter, "scanRollupLedger"), "unbound before the wiring call");

    try (ScanProgressRegistry registry = new ScanProgressRegistry()) {
      ScanRollupLedger ledger = mock(ScanRollupLedger.class);
      AgentToolFactory.bindScanObservability(adapter, registry, ledger);

      assertSame(registry, boundField(adapter, "scanProgressRegistry"));
      assertSame(ledger, boundField(adapter, "scanRollupLedger"));
    }
  }

  @Test
  @DisplayName("a null adapter (prerequisites unmet in build) is a no-op, not an NPE")
  void nullAdapterIsNoOp() {
    try (ScanProgressRegistry registry = new ScanProgressRegistry()) {
      assertDoesNotThrow(
          () -> AgentToolFactory.bindScanObservability(null, registry, mock(ScanRollupLedger.class)));
    }
  }

  @Test
  @DisplayName("a null collaborator does not clobber an already-bound one")
  void nullCollaboratorDoesNotUnbind() {
    KnowledgeHttpApiAdapter adapter = agentAdapter();
    try (ScanProgressRegistry registry = new ScanProgressRegistry()) {
      ScanRollupLedger ledger = mock(ScanRollupLedger.class);
      AgentToolFactory.bindScanObservability(adapter, registry, ledger);
      AgentToolFactory.bindScanObservability(adapter, null, null);

      assertSame(registry, boundField(adapter, "scanProgressRegistry"));
      assertSame(ledger, boundField(adapter, "scanRollupLedger"));
    }
  }
}
