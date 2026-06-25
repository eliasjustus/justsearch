/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationLog;
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
import java.util.List;
import java.util.function.Supplier;
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
 *       up. Builds the SearchTool / BrowseTool / IngestTool / FileOperationsTool instances
 *       internally from the supplied dependencies. Idempotent: skipped if SEARCH_INDEX is
 *       already registered.
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
   * Late-bound registration: build the four tool instances from the supplied dependencies and
   * register them. Returns true if registration ran; false if it was skipped (idempotence
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
      io.justsearch.agent.api.memory.MemoryStore memoryStore) {
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
    KnowledgeHttpApiAdapter adapter =
        existingAdapter != null
            ? existingAdapter
            : new KnowledgeHttpApiAdapter(knowledgeServer, onlineAiService, lambdaMartReranker);
    FileOperationLog fileOperationLog = new FileOperationLog(dataDir.resolve("file-operations"));
    FileOperationsTool fileOperationsTool =
        new FileOperationsTool(
            indexingService::getWatchedPaths,
            knowledgeClient::updateDocumentPaths,
            fileOperationLog);
    Supplier<List<BrowseTool.RootInfo>> rootsSupplier =
        () ->
            indexingService.getWatchedRoots().stream()
                .map(
                    r ->
                        new BrowseTool.RootInfo(
                            r.path().toAbsolutePath().normalize().toString(),
                            r.path().getFileName().toString()))
                .toList();
    SearchTool searchTool = new SearchTool(adapter::search, rootsSupplier);
    BrowseTool browseTool = new BrowseTool(adapter::listFolders, adapter::listFolderFiles, rootsSupplier);
    IngestTool ingestTool =
        new IngestTool(
            adapter::ingest,
            (rootPath, excludeGlobs) -> adapter.scanRoot(rootPath, null, excludeGlobs),
            rootsSupplier);
    operationHandlers.register(
        AgentToolsOperationCatalog.SEARCH_INDEX, new SearchOperationHandler(searchTool));
    operationHandlers.register(
        AgentToolsOperationCatalog.BROWSE_FOLDERS, new BrowseOperationHandler(browseTool));
    operationHandlers.register(
        AgentToolsOperationCatalog.INGEST_FILES, new IngestOperationHandler(ingestTool));
    operationHandlers.register(
        AgentToolsOperationCatalog.FILE_OPERATIONS, new FileOperationsHandler(fileOperationsTool));
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
