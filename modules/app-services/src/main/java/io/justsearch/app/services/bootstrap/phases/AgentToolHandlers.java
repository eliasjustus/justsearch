/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.ReadDocumentTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BrowseOperationHandler;
import io.justsearch.app.services.registry.operations.handlers.FileOperationsHandler;
import io.justsearch.app.services.registry.operations.handlers.IngestOperationHandler;
import io.justsearch.app.services.registry.operations.handlers.ReadDocumentHandler;
import io.justsearch.app.services.registry.operations.handlers.SearchOperationHandler;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 *       construction authority) and registers what it returns. Idempotent PER REF (tempdoc 876
 *       §B.5): a ref already present (typically registered by {@link #registerEager}) is left
 *       alone rather than the whole call short-circuiting on one sentinel ref — the two paths
 *       compose instead of one excluding the other.
 * </ul>
 */
public final class AgentToolHandlers {

  private static final Logger log = LoggerFactory.getLogger(AgentToolHandlers.class);

  private AgentToolHandlers() {}

  /**
   * Registers {@code handler} at {@code ref} unless {@code ref} is already registered.
   *
   * <p>Tempdoc 876 §B.5: this is the one call site where "skip if already present" belongs.
   * {@link HandlerRegistry#register} keeps its throw-on-duplicate contract for every other
   * caller — the eager and late-bound agent-tool paths are the only pair designed to both run
   * against the same registry, so the skip lives here, not in the registry itself.
   *
   * @return true if this call registered the handler; false if {@code ref} was already present.
   */
  private static boolean registerIfAbsent(
      HandlerRegistry operationHandlers, OperationRef ref, OperationHandler handler) {
    if (operationHandlers.resolve(ref).isPresent()) {
      return false;
    }
    operationHandlers.register(ref, handler);
    return true;
  }

  /**
   * True when every ref {@link #registerLateBound} could register already resolves. REMEMBER counts
   * only when a {@code memoryStore} exists to back it, and READ_DOCUMENT counts unconditionally: the
   * factory may return a null read tool, in which case there is nothing this path can add for that
   * ref either way, so treating it as outstanding would defeat the check on every call.
   */
  private static boolean allLateBoundRefsPresent(
      HandlerRegistry operationHandlers, io.justsearch.agent.api.memory.MemoryStore memoryStore) {
    List<OperationRef> required =
        new ArrayList<>(
            List.of(
                AgentToolsOperationCatalog.SEARCH_INDEX,
                AgentToolsOperationCatalog.READ_DOCUMENT,
                AgentToolsOperationCatalog.BROWSE_FOLDERS,
                AgentToolsOperationCatalog.INGEST_FILES,
                AgentToolsOperationCatalog.FILE_OPERATIONS));
    if (memoryStore != null) {
      required.add(AgentToolsOperationCatalog.REMEMBER);
    }
    for (OperationRef ref : required) {
      if (operationHandlers.resolve(ref).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /** Eager-path registration: register only the non-null tool instances, skipping any ref
   * already registered (idempotent, symmetric with {@link #registerLateBound}). */
  public static void registerEager(
      HandlerRegistry operationHandlers,
      SearchTool searchTool,
      BrowseTool browseTool,
      IngestTool ingestTool,
      FileOperationsTool fileOperationsTool,
      ReadDocumentTool readDocumentTool) {
    if (searchTool != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.SEARCH_INDEX,
          new SearchOperationHandler(searchTool));
    }
    if (readDocumentTool != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.READ_DOCUMENT,
          new ReadDocumentHandler(readDocumentTool));
    }
    if (browseTool != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.BROWSE_FOLDERS,
          new BrowseOperationHandler(browseTool));
    }
    if (ingestTool != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.INGEST_FILES,
          new IngestOperationHandler(ingestTool));
    }
    if (fileOperationsTool != null) {
      registerIfAbsent(
          operationHandlers,
          AgentToolsOperationCatalog.FILE_OPERATIONS,
          new FileOperationsHandler(fileOperationsTool));
    }
  }

  /**
   * Late-bound registration: obtain the tool instances from {@link AgentToolFactory#assemble} and
   * register them, skipping any ref already registered by {@link #registerEager} (tempdoc 876
   * §B.5 — the two paths compose, they no longer exclude each other via a single sentinel ref).
   *
   * @return true if the prerequisite guards passed and registration was attempted (refs already
   *     present are left untouched, refs not yet present are newly registered); false if a
   *     prerequisite (worker capability, knowledge server, or data dir) was missing.
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
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger,
      DocumentService documentService) {
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
    // Tempdoc 876 §B.5: nothing-to-do check over the WHOLE set this path can register, which is a
    // different thing from the sentinel it replaced — that one let SEARCH_INDEX stand proxy for the
    // rest and so permanently suppressed REMEMBER. This one skips only when every ref is already
    // handled, so the two paths still compose. It matters because assemble() is not side-effect
    // free: it constructs a FileOperationLog, whose constructor runs a diagnostic retention prune.
    if (allLateBoundRefsPresent(operationHandlers, memoryStore)) {
      return true;
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
            scanRollupLedger,
            documentService);
    List<OperationRef> registered = new ArrayList<>();
    if (registerIfAbsent(
        operationHandlers,
        AgentToolsOperationCatalog.SEARCH_INDEX,
        new SearchOperationHandler(tools.searchTool()))) {
      registered.add(AgentToolsOperationCatalog.SEARCH_INDEX);
    }
    if (tools.readDocumentTool() != null
        && registerIfAbsent(
            operationHandlers,
            AgentToolsOperationCatalog.READ_DOCUMENT,
            new ReadDocumentHandler(tools.readDocumentTool()))) {
      registered.add(AgentToolsOperationCatalog.READ_DOCUMENT);
    }
    if (registerIfAbsent(
        operationHandlers,
        AgentToolsOperationCatalog.BROWSE_FOLDERS,
        new BrowseOperationHandler(tools.browseTool()))) {
      registered.add(AgentToolsOperationCatalog.BROWSE_FOLDERS);
    }
    if (registerIfAbsent(
        operationHandlers,
        AgentToolsOperationCatalog.INGEST_FILES,
        new IngestOperationHandler(tools.ingestTool()))) {
      registered.add(AgentToolsOperationCatalog.INGEST_FILES);
    }
    if (registerIfAbsent(
        operationHandlers,
        AgentToolsOperationCatalog.FILE_OPERATIONS,
        new FileOperationsHandler(tools.fileOperationsTool()))) {
      registered.add(AgentToolsOperationCatalog.FILE_OPERATIONS);
    }
    // Tempdoc 561 P-E: the learning producer — core_remember persists durable facts into the shared
    // single-authority MemoryStore (the same instance /api/memory reads).
    if (memoryStore != null
        && registerIfAbsent(
            operationHandlers,
            AgentToolsOperationCatalog.REMEMBER,
            new io.justsearch.app.services.registry.operations.handlers.RememberFactHandler(
                memoryStore))) {
      registered.add(AgentToolsOperationCatalog.REMEMBER);
    }
    log.info("AgentTools operation handlers registered: {}", registered);
    return true;
  }
}
