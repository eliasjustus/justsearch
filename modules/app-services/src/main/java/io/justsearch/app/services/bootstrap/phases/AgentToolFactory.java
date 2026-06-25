/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
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
    FileOperationLog fileOperationLog = new FileOperationLog(dataDir.resolve("file-operations"));
    FileOperationsTool fileOperationsTool =
        new FileOperationsTool(
            indexingService::getWatchedPaths,
            knowledgeClient::updateDocumentPaths,
            fileOperationLog);
    KnowledgeHttpApiAdapter agentSearchAdapter =
        new KnowledgeHttpApiAdapter(knowledgeServer, onlineAiService, lambdaMartReranker);
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
            (rootPath, excludeGlobs) -> agentSearchAdapter.scanRoot(rootPath, null, excludeGlobs),
            rootsSupplier);
    return new Output(
        agentSearchAdapter, fileOperationLog, fileOperationsTool, searchTool, browseTool, ingestTool);
  }
}
