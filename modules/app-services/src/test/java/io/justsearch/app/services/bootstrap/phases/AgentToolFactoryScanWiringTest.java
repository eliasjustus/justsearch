package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.observability.ledger.ScanRollupLedger;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.ScanProgressRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 832 (lane D) — the agent-owned adapter's scan observability. {@code IngestTool} calls
 * {@code agentSearchAdapter::scanRoot}, but only the controller-owned adapter ever had the
 * scan-progress registry / rollup ledger bound, so an agent- or MCP-driven directory ingest emitted
 * no SSE progress and left no rollup row. These assertions read the adapter's bound collaborators
 * back (the adapter exposes no getters — a reflective read keeps the fix from needing a
 * production-side test seam).
 *
 * <p>Tempdoc 832 extended this with the one-construction-authority guards: that bug was possible
 * only because the eager and late-bound paths each assembled the bundle themselves, so the tests
 * below also pin the two paths to the same composition.
 */
@DisplayName("AgentToolFactory — scan wiring and single construction authority")
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
  @DisplayName("the late-bound registration binds the adapter its IngestTool drives")
  void lateBoundRegistrationBinds(@TempDir Path dataDir) {
    // The wrong-gate this guards: on the normal boot the Worker connects asynchronously, so the
    // eager AgentToolFactory.build produces nothing and THIS registration is what builds the
    // adapter behind core.ingest-files (MCP justsearch_ingest / the agent loop).
    KnowledgeHttpApiAdapter adapter = agentAdapter();
    WorkerCapability capability = mock(WorkerCapability.class);
    when(capability.available()).thenReturn(true);

    try (ScanProgressRegistry registry = new ScanProgressRegistry()) {
      ScanRollupLedger ledger = mock(ScanRollupLedger.class);
      boolean registered =
          AgentToolHandlers.registerLateBound(
              new HandlerRegistry(),
              mock(KnowledgeServerBootstrap.class),
              mock(RemoteKnowledgeClient.class),
              capability,
              dataDir,
              mock(RemoteKnowledgeClient.class),
              OnlineAiService.unavailable(),
              null,
              adapter,
              null,
              registry,
              ledger);

      assertTrue(registered, "registration ran (prerequisites met)");
      assertSame(registry, boundField(adapter, "scanProgressRegistry"));
      assertSame(ledger, boundField(adapter, "scanRollupLedger"));
    }
  }

  /**
   * Tempdoc 832 — the two-authorities regression guard. The eager path and the late-bound path used
   * to assemble the bundle independently, so a wiring added to one silently missed the other. These
   * assert they now compose the same bundle: same tool set, same adapter-identity semantics, same
   * scan bindings.
   */
  @Test
  @DisplayName("both paths register the same operation set")
  void bothPathsRegisterTheSameOperations(@TempDir Path dataDir) {
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    WorkerCapability capability = mock(WorkerCapability.class);
    when(capability.available()).thenReturn(true);

    HandlerRegistry eager = new HandlerRegistry();
    AgentToolFactory.Output eagerTools =
        AgentToolFactory.build(
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null);
    AgentToolHandlers.registerEager(
        eager,
        eagerTools.searchTool(),
        eagerTools.browseTool(),
        eagerTools.ingestTool(),
        eagerTools.fileOperationsTool());

    HandlerRegistry lateBound = new HandlerRegistry();
    assertTrue(
        AgentToolHandlers.registerLateBound(
            lateBound,
            mock(KnowledgeServerBootstrap.class),
            client,
            capability,
            dataDir,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            null,
            null),
        "late-bound registration ran (prerequisites met)");

    assertEquals(
        eager.registeredIds(),
        lateBound.registeredIds(),
        "the eager and late-bound paths must expose the same agent tools");
  }

  @Test
  @DisplayName("adapter identity: late-bound reuses the eager adapter, and builds one when absent")
  void adapterIdentitySemantics(@TempDir Path dataDir) {
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    KnowledgeHttpApiAdapter existing = agentAdapter();

    AgentToolFactory.Output reused =
        AgentToolFactory.assemble(
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            existing,
            null,
            null);
    assertSame(existing, reused.agentSearchAdapter(), "a supplied adapter is reused, not replaced");

    AgentToolFactory.Output fresh =
        AgentToolFactory.assemble(
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null,
            null,
            null,
            null);
    assertNotNull(fresh.agentSearchAdapter(), "a fresh adapter is built when none is supplied");
    assertNotSame(existing, fresh.agentSearchAdapter());
  }

  @Test
  @DisplayName("the freshly built adapter — the normal async-Worker boot — gets the scan bindings")
  void freshAdapterGetsScanBindings(@TempDir Path dataDir) {
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    try (ScanProgressRegistry registry = new ScanProgressRegistry()) {
      ScanRollupLedger ledger = mock(ScanRollupLedger.class);
      AgentToolFactory.Output out =
          AgentToolFactory.assemble(
              dataDir,
              mock(KnowledgeServerBootstrap.class),
              client,
              client,
              OnlineAiService.unavailable(),
              null,
              null,
              registry,
              ledger);

      assertSame(registry, boundField(out.agentSearchAdapter(), "scanProgressRegistry"));
      assertSame(ledger, boundField(out.agentSearchAdapter(), "scanRollupLedger"));
    }
  }

  @Test
  @DisplayName("every bundle component is composed on both paths")
  void bundleIsFullyComposed(@TempDir Path dataDir) {
    RemoteKnowledgeClient client = mock(RemoteKnowledgeClient.class);
    AgentToolFactory.Output out =
        AgentToolFactory.build(
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            client,
            client,
            OnlineAiService.unavailable(),
            null);
    assertNotNull(out.agentSearchAdapter());
    assertNotNull(out.fileOperationLog());
    assertNotNull(out.fileOperationsTool());
    assertNotNull(out.searchTool());
    assertNotNull(out.browseTool());
    assertNotNull(out.ingestTool());
  }

  @Test
  @DisplayName("the eager guard is preserved: no knowledgeClient yields an all-null Output")
  void eagerGuardStillYieldsAllNull(@TempDir Path dataDir) {
    AgentToolFactory.Output out =
        AgentToolFactory.build(
            dataDir,
            mock(KnowledgeServerBootstrap.class),
            null,
            mock(RemoteKnowledgeClient.class),
            OnlineAiService.unavailable(),
            null);
    assertNull(out.agentSearchAdapter());
    assertNull(out.fileOperationLog());
    assertNull(out.fileOperationsTool());
    assertNull(out.searchTool());
    assertNull(out.browseTool());
    assertNull(out.ingestTool());
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
