/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §7 / Step 7: agent-tool factory extracted from the bootstrap's main constructor
 * body. Builds the eager-path KnowledgeHttpApiAdapter + 4 tool instances (Search/Browse/Ingest/
 * FileOperations) when both knowledgeClient and indexingService are available at construction
 * time. Returns null fields in {@link Output} when prerequisites are not met, so the caller
 * can skip registration.
 *
 * <p>Tempdoc 832: {@link #assemble} is the SINGLE construction authority for the agent tool
 * bundle. Both entry points route through it — {@link #build} for the eager path and
 * {@code AgentToolHandlers.registerLateBound} for the connect-time path — so a wiring added here
 * reaches both. It previously existed twice; the scan-observability binding (lane D / PR #453) was
 * added to one copy and silently missing from the other until a second fix caught up.
 */
public final class AgentToolFactory {

  private AgentToolFactory() {}

  /** Eager-path agent-tool bundle. Each field may be null when prerequisites are unavailable. */
  public record Output(
      KnowledgeHttpApiAdapter agentSearchAdapter,
      FileOperationLog fileOperationLog,
      FileOperationsTool fileOperationsTool,
      SearchTool searchTool,
      BrowseTool browseTool,
      IngestTool ingestTool) {}

  /**
   * Build the eager-path agent-tool instances. Returns an all-null output when either
   * {@code knowledgeClient} or {@code indexingService} is null.
   */
  public static Output build(
      Path dataDir,
      KnowledgeServerBootstrap knowledgeServer,
      RemoteKnowledgeClient knowledgeClient,
      IndexingService indexingService,
      OnlineAiService onlineAiService,
      LambdaMartReranker lambdaMartReranker) {
    if (knowledgeClient == null || indexingService == null) {
      return new Output(null, null, null, null, null, null);
    }
    return assemble(
        dataDir,
        knowledgeServer,
        knowledgeClient,
        indexingService,
        onlineAiService,
        lambdaMartReranker,
        null,
        null,
        null);
  }

  /**
   * Tempdoc 832 — the one place the agent tool bundle is composed. Callers own their own
   * prerequisite checks: this method assumes {@code knowledgeClient}, {@code indexingService} and
   * {@code dataDir} are usable and does not re-guard them (the eager guard lives in {@link #build};
   * the late-bound guards live in {@code AgentToolHandlers.registerLateBound}).
   *
   * @param existingAdapter reuse this adapter when non-null, otherwise build a fresh one. The
   *     late-bound path passes the eager-path adapter when the eager path produced one; on the
   *     normal (async-Worker) boot it is null and the adapter built here is the one
   *     {@code IngestTool} actually drives.
   * @param scanProgressRegistry scan-progress SSE registry, bound onto whichever adapter is used;
   *     null on the eager path, where neither collaborator exists yet (see
   *     {@link #bindScanObservability}).
   * @param scanRollupLedger scan-rollup ledger, same lifecycle caveat as the registry.
   */
  static Output assemble(
      Path dataDir,
      KnowledgeServerBootstrap knowledgeServer,
      RemoteKnowledgeClient knowledgeClient,
      IndexingService indexingService,
      OnlineAiService onlineAiService,
      LambdaMartReranker lambdaMartReranker,
      KnowledgeHttpApiAdapter existingAdapter,
      io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry,
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger) {
    KnowledgeHttpApiAdapter agentSearchAdapter =
        existingAdapter != null
            ? existingAdapter
            : new KnowledgeHttpApiAdapter(knowledgeServer, onlineAiService, lambdaMartReranker);
    bindScanObservability(agentSearchAdapter, scanProgressRegistry, scanRollupLedger);
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
    SearchTool searchTool = new SearchTool(agentSearchAdapter::search, rootsSupplier);
    BrowseTool browseTool =
        new BrowseTool(
            agentSearchAdapter::listFolders, agentSearchAdapter::listFolderFiles, rootsSupplier);
    IngestTool ingestTool =
        new IngestTool(
            agentSearchAdapter::ingest,
            agentSearchAdapter::scanRoot,
            rootsSupplier,
            () -> rootBindings(indexingService));
    return new Output(
        agentSearchAdapter, fileOperationLog, fileOperationsTool, searchTool, browseTool, ingestTool);
  }

  /**
   * Tempdoc 832 (lane D) — binds the scan-progress registry and scan-rollup ledger onto an
   * agent-owned adapter. Without this, a directory ingest driven through {@code IngestTool} (agent
   * loop / MCP {@code justsearch_ingest}) emitted no scan-progress SSE and left no rollup row, while
   * the byte-identical ingest through {@code /api/knowledge/ingest} did — the setters were only ever
   * called on the controller-owned adapter.
   *
   * <p>{@link #assemble} calls this for the adapter it composes the bundle around. It stays public
   * because the eager path cannot supply the collaborators at composition time — neither exists yet
   * when {@link #build} runs (the ledger is created by the substrate phase and the registry by
   * {@code LocalApiServer}, both strictly after the service phase), so {@code HeadAssembly} binds
   * the eager adapter later, out of band. The agent adapter stays its own instance — sharing the
   * controller's would couple two lifecycles.
   */
  public static void bindScanObservability(
      KnowledgeHttpApiAdapter agentSearchAdapter,
      io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry,
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger) {
    if (agentSearchAdapter == null) return;
    if (scanProgressRegistry != null) agentSearchAdapter.setScanProgressRegistry(scanProgressRegistry);
    if (scanRollupLedger != null) agentSearchAdapter.setScanRollupLedger(scanRollupLedger);
  }

  /**
   * Tempdoc 811 (C-2a) — projects the watched-root registry into the ingest-tagging authority so an
   * agent/MCP ingest of an in-root path inherits that root's collection instead of writing an
   * unlabeled document. Best-effort: a Worker-unavailable lookup yields an empty list, which makes
   * every path resolve out-of-root (`mcp-ingest`).
   */
  static List<IngestCollectionPolicy.RootBinding> rootBindings(IndexingService indexingService) {
    try {
      return indexingService.getWatchedRoots().stream()
          .filter(r -> r != null && r.path() != null)
          .map(r -> new IngestCollectionPolicy.RootBinding(r.path(), r.collection()))
          .toList();
    } catch (RuntimeException e) {
      return List.of();
    }
  }
}
