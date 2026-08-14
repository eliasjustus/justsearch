/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BrowseOperationHandler;
import io.justsearch.app.services.registry.operations.handlers.FileOperationsHandler;
import io.justsearch.app.services.registry.operations.handlers.IngestOperationHandler;
import io.justsearch.app.services.registry.operations.handlers.SearchOperationHandler;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: agent-tool handler registration helpers extracted from
 * {@code HeadAssembly}.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #registerEager} — constructor-time registration when the KnowledgeServer is
 *       provided up front (typical of test paths). Registers only the tool instances supplied
 *       (each may be null).
 *   <li>{@link #registerLateBound} — connect-time registration after the worker channel comes
 *       up. Delegates composition of the tool bundle to {@link AgentToolFactory} (tempdoc 832: one
 *       construction authority) and registers what it returns. Idempotent: skipped if SEARCH_INDEX
 *       is already registered.
 * </ul>
 */
public final class AgentToolHandlers {

  private static final Logger log = LoggerFactory.getLogger(AgentToolHandlers.class);

  private AgentToolHandlers() {}

  /** Eager-path registration: register only the non-null tool instances. */
  public static void registerEager(
      HandlerRegistry operationHandlers,
      SearchTool searchTool,
      BrowseTool browseTool,
      IngestTool ingestTool,
      FileOperationsTool fileOperationsTool) {
    if (searchTool != null) {
      operationHandlers.register(
          AgentToolsOperationCatalog.SEARCH_INDEX, new SearchOperationHandler(searchTool));
    }
    if (browseTool != null) {
      operationHandlers.register(
          AgentToolsOperationCatalog.BROWSE_FOLDERS, new BrowseOperationHandler(browseTool));
    }
    if (ingestTool != null) {
      operationHandlers.register(
          AgentToolsOperationCatalog.INGEST_FILES, new IngestOperationHandler(ingestTool));
    }
    if (fileOperationsTool != null) {
      operationHandlers.register(
          AgentToolsOperationCatalog.FILE_OPERATIONS,
          new FileOperationsHandler(fileOperationsTool));
    }
  }

  /**
   * Late-bound registration: obtain the four tool instances from {@link AgentToolFactory#assemble}
   * and register them. Returns true if registration ran; false if it was skipped (idempotence
   * check or missing prerequisites).
   */
  public static boolean registerLateBound(
      HandlerRegistry operationHandlers,
      KnowledgeServerBootstrap knowledgeServer,
      RemoteKnowledgeClient knowledgeClient,
      WorkerCapability workerCapability,
      Path dataDir,
      IndexingService indexingService,
      OnlineAiService onlineAiService,
      LambdaMartReranker lambdaMartReranker,
      KnowledgeHttpApiAdapter existingAdapter,
      io.justsearch.agent.api.memory.MemoryStore memoryStore,
      io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry,
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger) {
    if (operationHandlers.resolve(AgentToolsOperationCatalog.SEARCH_INDEX).isPresent()) {
      return false;
    }
    if (knowledgeClient == null || !workerCapability.available()) {
      log.warn("registerAgentToolHandlers skipped: knowledgeClient or worker capability unavailable");
      return false;
    }
    if (knowledgeServer == null) {
      log.warn("registerAgentToolHandlers skipped: knowledgeServer is null");
      return false;
    }
    if (dataDir == null) {
      log.warn("registerAgentToolHandlers skipped: dataDir not initialized");
      return false;
    }
    // Tempdoc 832: one construction authority. This path used to duplicate AgentToolFactory's
    // assembly, which is how the lane-D scan-observability wiring reached only one of the two
    // copies. Registration is this method's job; composition is the factory's.
    AgentToolFactory.Output tools =
        AgentToolFactory.assemble(
            dataDir,
            knowledgeServer,
            knowledgeClient,
            indexingService,
            onlineAiService,
            lambdaMartReranker,
            existingAdapter,
            scanProgressRegistry,
            scanRollupLedger);
    operationHandlers.register(
        AgentToolsOperationCatalog.SEARCH_INDEX, new SearchOperationHandler(tools.searchTool()));
    operationHandlers.register(
        AgentToolsOperationCatalog.BROWSE_FOLDERS, new BrowseOperationHandler(tools.browseTool()));
    operationHandlers.register(
        AgentToolsOperationCatalog.INGEST_FILES, new IngestOperationHandler(tools.ingestTool()));
    operationHandlers.register(
        AgentToolsOperationCatalog.FILE_OPERATIONS,
        new FileOperationsHandler(tools.fileOperationsTool()));
    // Tempdoc 561 P-E: the learning producer — core_remember persists durable facts into the shared
    // single-authority MemoryStore (the same instance /api/memory reads).
    if (memoryStore != null) {
      operationHandlers.register(
          AgentToolsOperationCatalog.REMEMBER,
          new io.justsearch.app.services.registry.operations.handlers.RememberFactHandler(
              memoryStore));
    }
    log.info(
        "AgentTools operation handlers registered: SEARCH_INDEX, BROWSE_FOLDERS, INGEST_FILES, FILE_OPERATIONS, REMEMBER");
    return true;
  }
}
