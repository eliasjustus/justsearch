/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Layer 2: Curated MCP tool surface.
 *
 * <p>Five eval-informed tools with hand-written descriptions, position-bias ordering,
 * direct service-layer dispatch, and response-level progressive disclosure hints.
 * Adapted from the eval-informed TypeScript MCP server (a tool-interface-design eval, tempdoc 366).
 *
 * <p>Separated from {@link McpProtocolHandler} (Layer 1: transport) per tempdoc 500's
 * three-layer architecture.
 */
public final class McpToolSurface {

  private static final Logger log = LoggerFactory.getLogger(McpToolSurface.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final long RETRIEVE_TIMEOUT_MS = 15_000;

  // --- Tool descriptions (100-300 words, adapted from old TS server) ---

  private static final String ANSWER_DESC =
      "Get evidence from your indexed documents to answer a question. This is the primary tool "
          + "for question-answering — it retrieves relevant passages from multiple documents in "
          + "one call, assembled with source attribution, ready to use as evidence for your answer. "
          + "Much more efficient than searching and reading documents individually. "
          + "The response includes facets showing top sources and entities in the index. "
          + "Use these facet values as filters to scope retrieval: "
          + "filters: {meta_source: [\"the verge\"], entity_persons: [\"Elon Musk\"]}. "
          + "For questions comparing what different sources report, call this tool once per source "
          + "with meta_source filters to get source-specific evidence, then synthesize. "
          + "Use justsearch_search only when you need to explore or discover what is in the index.";

  private static final String SEARCH_DESC =
      "Find and explore documents in the JustSearch index. Use this to discover what documents "
          + "exist, browse by source/category/author, or find specific files. Returns file paths, "
          + "relevance scores, and content previews. For answering questions, prefer "
          + "justsearch_answer — it retrieves assembled passages from multiple documents in one call. "
          + "Supports hybrid (default), text (BM25 keyword), and vector (semantic) search modes. "
          + "For exact phrase or boolean queries, set querySyntax: \"LUCENE\" with mode: \"text\". "
          + "The system automatically detects sources, authors, and entities in your query and "
          + "applies soft boosts — check the queryUnderstanding field in the response. "
          + "The first search returns top facet values (sources, categories, authors).";

  private static final String BROWSE_DESC =
      "Browse the indexed folder structure. Lists subfolders with file counts and sizes. "
          + "When a folder has no subfolders, automatically lists individual files instead. "
          + "Set list_files:true to explicitly list files in a folder. "
          + "Call with no parent_path to see top-level indexed roots.";

  private static final String INGEST_DESC =
      "Index files or directories into JustSearch. Provide absolute paths to files or folders. "
          + "Returns the number of accepted items. Folders are expanded recursively. "
          + "Use justsearch_status to check indexing progress after ingestion.";

  private static final String STATUS_DESC =
      "Get the current status of the JustSearch knowledge index. Returns document count, "
          + "queue depth, readiness state, health, and enrichment coverage "
          + "(embeddingCoveragePercent, spladeCoveragePercent, pendingNerCount, completedNerCount). "
          + "After ingesting documents, poll this to check if enrichment (embeddings, NER, SPLADE) "
          + "is complete before using entity filters or semantic search.";

  private final List<OperationCatalog> operationCatalogs;
  private final OperationDispatcher dispatcher;
  private final java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup;
  private final java.util.function.Supplier<HeadAssembly> appFacadeLookup;
  private final Clock clock;
  // Tempdoc 501 Phase 15: optional publisher lookup for the runtime_manifest tool.
  // Null when the test-only Builder path constructed the surface without one.
  private final java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
      manifestPublisherLookup;

  public McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock) {
    this(operationCatalogs, dispatcher, knowledgeLookup, appFacadeLookup, clock, () -> null);
  }

  public McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock,
      java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
          manifestPublisherLookup) {
    this.operationCatalogs = List.copyOf(operationCatalogs);
    this.dispatcher = dispatcher;
    this.knowledgeLookup = knowledgeLookup;
    this.appFacadeLookup = appFacadeLookup;
    this.clock = clock;
    this.manifestPublisherLookup =
        manifestPublisherLookup != null ? manifestPublisherLookup : () -> null;
  }

  // =========================================================================
  // tools/list — 5 curated tools, position-bias ordered
  // =========================================================================

  public Map<String, Object> listTools() {
    return Map.of(
        "tools",
        List.of(
            tool(
                "justsearch_answer",
                ANSWER_DESC,
                schema(
                    Map.of(
                        "query", prop("string", "The question to answer"),
                        "top_k",
                            prop("integer", "Number of passages to retrieve (default 5, max 20)"),
                        "filters",
                            prop(
                                "object",
                                "Hard filters: {meta_source: [...], entity_persons: [...],"
                                    + " path_prefix: \"...\", ...}")),
                    List.of("query")),
                Map.of("readOnlyHint", true)),
            tool(
                "justsearch_search",
                SEARCH_DESC,
                schema(
                    Map.of(
                        "query", prop("string", "Search text"),
                        "limit", prop("integer", "Max results (default 10, max 50)"),
                        "mode", prop("string", "Search mode: hybrid (default), text, or vector"),
                        "filters",
                            prop(
                                "object",
                                "Hard filters: {meta_source: [...], entity_persons: [...],"
                                    + " path_prefix: \"...\", ...}")),
                    List.of("query")),
                Map.of("readOnlyHint", true)),
            tool(
                "justsearch_browse",
                BROWSE_DESC,
                schema(
                    Map.of(
                        "parent_path",
                            prop("string", "Folder path to browse (empty for top-level roots)"),
                        "list_files",
                            prop("boolean", "List individual files instead of subfolders")),
                    List.of()),
                Map.of("readOnlyHint", true)),
            tool(
                "justsearch_ingest",
                INGEST_DESC,
                schema(
                    Map.of(
                        "paths",
                            Map.of(
                                "type",
                                "array",
                                "items",
                                Map.of("type", "string"),
                                "description",
                                "Absolute file or folder paths to index")),
                    List.of("paths")),
                Map.of("readOnlyHint", false, "idempotentHint", true)),
            tool(
                "justsearch_status",
                STATUS_DESC,
                schema(Map.of(), List.of()),
                Map.of("readOnlyHint", true)),
            tool(
                "justsearch_runtime_manifest",
                RUNTIME_MANIFEST_DESC,
                schema(Map.of(), List.of()),
                Map.of("readOnlyHint", true))));
  }

  private static final String RUNTIME_MANIFEST_DESC =
      "Returns the redacted runtime manifest (tempdoc 501 §12.4): JSON document carrying the "
          + "current backend's identity (instanceId, pid, dataDir), lifecycle projection, "
          + "head/worker state, and AI runtime state. Same body served at "
          + "GET /api/runtime/manifest and GET /.well-known/justsearch/manifest.json — this tool "
          + "is the MCP-native surface for identity-aware caching and cross-restart detection.";

  // =========================================================================
  // tools/call — route to service layer
  // =========================================================================

  @SuppressWarnings("unchecked")
  public Map<String, Object> callTool(String name, Map<String, Object> arguments, String sessionId) {
    return switch (name) {
      case "justsearch_answer" -> callAnswer(arguments);
      case "justsearch_search" -> callSearch(arguments);
      case "justsearch_browse" -> callOperation("core.browse-folders", arguments, sessionId);
      case "justsearch_ingest" -> callOperation("core.ingest-files", arguments, sessionId);
      case "justsearch_status" -> callStatus();
      case "justsearch_runtime_manifest" -> callRuntimeManifest();
      default -> unknownToolWithSuggestions(name);
    };
  }

  private static final List<String> KNOWN_TOOLS = List.of(
      "justsearch_answer", "justsearch_search", "justsearch_browse",
      "justsearch_ingest", "justsearch_status", "justsearch_runtime_manifest");

  // =========================================================================
  // RuntimeManifest: redacted JSON snapshot of the producer-published manifest
  // (tempdoc 501 Phase 15). Returns the same shape served at
  // GET /api/runtime/manifest — sessionToken stripped.
  // =========================================================================

  private Map<String, Object> callRuntimeManifest() {
    io.justsearch.ui.runtime.RuntimeManifestPublisher publisher = manifestPublisherLookup.get();
    if (publisher == null) {
      return errorContent("Runtime manifest publisher not wired");
    }
    io.justsearch.app.api.runtime.RuntimeManifest current = publisher.current();
    if (current == null) {
      return errorContent("Runtime manifest not yet published");
    }
    io.justsearch.app.api.runtime.RuntimeManifest publicView = current.publicProjection();
    try {
      String json =
          new ObjectMapper()
              .writerWithDefaultPrettyPrinter()
              .writeValueAsString(publicView);
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("content", List.of(Map.of("type", "text", "text", json)));
      content.put("structuredContent", publicView);
      return content;
    } catch (Exception e) {
      return errorContent("Failed to serialize runtime manifest: " + e.getMessage());
    }
  }

  private Map<String, Object> unknownToolWithSuggestions(String name) {
    var alts = io.justsearch.agent.api.registry.CatalogMatcher.defaultMatcher()
        .findAlternatives(name, KNOWN_TOOLS, s -> s, 3);
    if (alts.isEmpty()) return errorContent("Unknown tool: " + name);
    var suggestions = alts.stream().map(a -> a.refId()).toList();
    var msg = "Unknown tool: " + name
        + ". Did you mean: " + String.join(", ", suggestions) + "?";
    var content = new LinkedHashMap<String, Object>();
    content.put("content", List.of(Map.of("type", "text", "text", msg)));
    content.put("isError", true);
    content.put("suggestions", suggestions);
    return content;
  }

  // =========================================================================
  // Answer: direct in-process via DocumentService.retrieveContext()
  // =========================================================================

  @SuppressWarnings("unchecked")
  private Map<String, Object> callAnswer(Map<String, Object> args) {
    HeadAssembly facade = appFacadeLookup.get();
    if (facade == null || facade.workers().documents() == null) {
      return errorContent("Knowledge server not available");
    }
    try {
      String query = (String) args.getOrDefault("query", "");
      int topK = ((Number) args.getOrDefault("top_k", 5)).intValue();
      Map<String, Object> rawFilters = (Map<String, Object>) args.get("filters");

      RetrieveContextParams params =
          new RetrieveContextParams(
              query,
              Set.of(),
              Math.min(topK, 20),
              4096,
              toStringList(rawFilters, "entity_persons"),
              toStringList(rawFilters, "entity_organizations"),
              toStringList(rawFilters, "entity_locations"),
              RetrieveContextParams.TimeRange.UNSET,
              false,
              rawFilters != null ? (String) rawFilters.getOrDefault("path_prefix", "") : "",
              List.of(),
              true,
              RetrieveContextParams.ContextFormat.XML,
              toStringList(rawFilters, "meta_source"),
              toStringList(rawFilters, "meta_author"),
              toStringList(rawFilters, "meta_category"),
              RetrieveContextParams.TimeRange.UNSET,
              false,
              List.of());

      DocumentService.ContextResult result =
          facade
              .workers()
              .documents()
              .retrieveContext(params)
              .toCompletableFuture()
              .get(RETRIEVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

      var sb = new StringBuilder();
      if (result.context() != null && !result.context().isBlank()) {
        sb.append(result.context());
      } else {
        sb.append("No relevant passages found for: ").append(query);
      }

      // Quality signals
      var q = result.quality();
      sb.append("\n\n--- Quality ---\n");
      sb.append("Sources found: ").append(result.chunksFound()).append("\n");
      sb.append("Coverage: ").append(String.format("%.2f", q.retrievalCoverage())).append("\n");
      sb.append("Retrieval mode: ").append(result.retrievalMode()).append("\n");
      if (result.contextTruncated()) sb.append("Note: context was truncated to fit token budget.\n");

      // Facet sidecar (parallel discovery)
      appendFacetSidecar(sb, query);

      // Enrichment hint
      appendEnrichmentHint(sb);

      // Zero-result hint
      if (result.chunksFound() == 0) {
        sb.append("\nHint: No results. Try different terms or check justsearch_status.");
      }

      return Map.of(
          "content", List.of(Map.of("type", "text", "text", sb.toString())), "isError", false);
    } catch (Exception e) {
      log.warn("MCP answer failed", e);
      return errorContent("Answer retrieval failed: " + e.getMessage());
    }
  }

  // =========================================================================
  // Search: direct via KnowledgeHttpApiAdapter
  // =========================================================================

  @SuppressWarnings("unchecked")
  private Map<String, Object> callSearch(Map<String, Object> args) {
    KnowledgeSearchController ctrl = knowledgeLookup.get();
    if (ctrl == null) return errorContent("Knowledge server not available");
    try {
      KnowledgeHttpApiAdapter adapter = ctrl.getAdapter();
      String query = (String) args.getOrDefault("query", "");
      int limit = ((Number) args.getOrDefault("limit", 10)).intValue();
      String mode = (String) args.getOrDefault("mode", "hybrid");

      KnowledgeSearchRequest.Filters filters =
          parseFilters((Map<String, Object>) args.get("filters"));

      var defaultFacetFields =
          List.of(
              new KnowledgeSearchRequest.FieldSpec("meta_source", 5),
              new KnowledgeSearchRequest.FieldSpec("meta_category", 5),
              new KnowledgeSearchRequest.FieldSpec("meta_author", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_persons_raw", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_organizations_raw", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_locations_raw", 5));
      var facets = new KnowledgeSearchRequest.Facets(true, null, defaultFacetFields);

      KnowledgeSearchRequest req =
          new KnowledgeSearchRequest(
              query, Math.min(limit, 50), mode, null, null, null, filters, null, facets, null,
              null, null, null);
      KnowledgeSearchResponse resp = adapter.search(req);

      var sb = new StringBuilder();
      if (resp.results() != null) {
        int rank = 1;
        for (var hit : resp.results()) {
          String title = hit.fields().getOrDefault("title", "");
          String path = hit.fields().getOrDefault("path", "");
          String preview = hit.fields().getOrDefault("content_preview", "");
          sb.append("[")
              .append(rank++)
              .append("] ")
              .append(!title.isBlank() ? title : path)
              .append(" (score: ")
              .append(String.format("%.2f", hit.score()))
              .append(")\n");
          if (!path.isBlank()) sb.append("    Path: ").append(path).append("\n");
          if (!preview.isBlank()) {
            if (preview.length() > 200) preview = preview.substring(0, 200) + "...";
            sb.append("    Preview: ").append(preview).append("\n");
          }
          sb.append("\n");
        }
      }

      sb.append("Found ").append(resp.totalHits()).append(" results");
      if (resp.tookMs() > 0) sb.append(" (took ").append(resp.tookMs()).append("ms)");
      sb.append(".");

      // Facets
      if (resp.facets() != null && !resp.facets().isEmpty()) {
        sb.append("\n\nFacets (use as filter values):\n");
        for (var entry : resp.facets().entrySet()) {
          String facetName = entry.getKey().replace("_raw", "");
          sb.append("  ").append(facetName).append(": ");
          if (entry.getValue() instanceof Map<?, ?> facetMap) {
            var topEntries =
                facetMap.entrySet().stream()
                    .limit(5)
                    .map(e -> e.getKey() + " (" + e.getValue() + ")")
                    .toList();
            sb.append(String.join(", ", topEntries));
          }
          sb.append("\n");
        }
      }

      // Hints
      var hints = new ArrayList<String>();
      if (resp.totalHits() == 0) {
        hints.add(
            "No results found. Try broader terms, or use justsearch_status to check what's"
                + " indexed.");
      } else if (resp.totalHits() > 100 && args.get("filters") == null) {
        hints.add("Many results. Use the facet values above as filters to narrow down.");
      }
      appendEnrichmentHintToList(hints);
      if (!hints.isEmpty()) {
        sb.append("\nHints:\n");
        for (String hint : hints) sb.append("- ").append(hint).append("\n");
      }

      return Map.of(
          "content", List.of(Map.of("type", "text", "text", sb.toString())), "isError", false);
    } catch (Exception e) {
      log.warn("MCP search failed", e);
      return errorContent("Search failed: " + e.getMessage());
    }
  }

  // =========================================================================
  // Status: formatted text
  // =========================================================================

  private Map<String, Object> callStatus() {
    KnowledgeSearchController ctrl = knowledgeLookup.get();
    if (ctrl == null) return errorContent("Knowledge server not available");
    try {
      KnowledgeHttpApiAdapter adapter = ctrl.getAdapter();
      var status = adapter.status();
      var sb = new StringBuilder();
      sb.append("state: ").append(status.state()).append("\n");
      sb.append("ready: ").append(status.ready()).append("\n");
      sb.append("documents: ").append(status.docCount()).append("\n");
      sb.append("queueDepth: ").append(status.queueDepth()).append("\n");
      sb.append("healthy: ").append(status.healthy()).append("\n");
      sb.append("indexState: ").append(status.indexState()).append("\n");
      Map<String, Object> extras = status.extras();
      if (extras.get("embeddingCoveragePercent") instanceof Number n) {
        sb.append("embeddingCoverage: ")
            .append(String.format("%.1f%%", n.doubleValue()))
            .append("\n");
      }
      if (extras.get("spladeCoveragePercent") instanceof Number n) {
        sb.append("spladeCoverage: ")
            .append(String.format("%.1f%%", n.doubleValue()))
            .append("\n");
      }
      if (extras.get("completedNerCount") instanceof Number nd) {
        sb.append("nerCompleted: ").append(nd.intValue());
        if (extras.get("pendingNerCount") instanceof Number np) {
          sb.append(" (").append(np.intValue()).append(" pending)");
        }
        sb.append("\n");
      }
      return Map.of(
          "content", List.of(Map.of("type", "text", "text", sb.toString())), "isError", false);
    } catch (Exception e) {
      log.warn("MCP status failed", e);
      return errorContent("Status unavailable: " + e.getMessage());
    }
  }

  // =========================================================================
  // Operation dispatch (browse + ingest)
  // =========================================================================

  private Map<String, Object> callOperation(
      String opIdValue, Map<String, Object> arguments, String sessionId) {
    try {
      Operation op = resolveOperation(opIdValue);
      if (op == null) return errorContent("Operation not available: " + opIdValue);
      String argsJson = MAPPER.writeValueAsString(arguments);
      InvocationProvenance provenance =
          InvocationProvenance.mcp(clock.instant(), Optional.ofNullable(sessionId));
      OperationResult opResult;
      try {
        opResult = dispatcher.dispatch(op, argsJson, provenance);
      } catch (ConfirmationRequiredException e) {
        return Map.of(
            "content",
            List.of(
                Map.of(
                    "type",
                    "text",
                    "text",
                    "Operation '"
                        + e.operationRef().value()
                        + "' requires confirmation (gate: "
                        + e.gateBehavior()
                        + "). Re-invoke with \"_confirmationToken\": \"confirm\" in arguments.")),
            "isError",
            true);
      }
      if (opResult.success()) {
        var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", opResult.message()));
        if (!opResult.structuredData().isEmpty()) {
          content.add(
              Map.of("type", "text", "text", MAPPER.writeValueAsString(opResult.structuredData())));
        }
        return Map.of("content", content, "isError", false);
      } else {
        return Map.of(
            "content", List.of(Map.of("type", "text", "text", opResult.message())), "isError",
            true);
      }
    } catch (Exception e) {
      log.warn("MCP operation dispatch error for {}", opIdValue, e);
      return errorContent("Operation failed: " + e.getMessage());
    }
  }

  // =========================================================================
  // Prompts
  // =========================================================================

  public Map<String, Object> listPrompts() {
    return Map.of(
        "prompts",
        List.of(
            Map.of(
                "name", "search_files",
                "description", "Search your local knowledge base for a topic",
                "arguments",
                    List.of(
                        Map.of(
                            "name", "topic", "description", "What to search for", "required",
                            true))),
            Map.of(
                "name", "answer_question",
                "description", "Get an answer from your indexed documents",
                "arguments",
                    List.of(
                        Map.of(
                            "name", "question", "description", "The question to answer", "required",
                            true))),
            Map.of(
                "name", "index_folder",
                "description", "Add a folder to your knowledge base",
                "arguments",
                    List.of(
                        Map.of(
                            "name", "path", "description", "Absolute path to the folder",
                            "required", true)))));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getPrompt(String name, Map<String, String> arguments) {
    String statusContext = getStatusContext();
    return switch (name != null ? name : "") {
      case "search_files" ->
          promptMessages(
              statusContext,
              "Search my local files for: " + arguments.getOrDefault("topic", ""));
      case "answer_question" ->
          promptMessages(
              statusContext,
              "Using my indexed documents, answer: " + arguments.getOrDefault("question", ""));
      case "index_folder" ->
          promptMessages(
              statusContext,
              "Index this folder into my knowledge base: "
                  + arguments.getOrDefault("path", ""));
      default -> Map.of("messages", List.of());
    };
  }

  private String getStatusContext() {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return "JustSearch index status unknown.";
      var status = ctrl.getAdapter().status();
      var sb = new StringBuilder("JustSearch has ");
      sb.append(status.docCount()).append(" documents indexed.");
      Map<String, Object> extras = status.extras();
      if (extras.get("embeddingCoveragePercent") instanceof Number n) {
        sb.append(" Embeddings: ").append(String.format("%.0f%%", n.doubleValue())).append(".");
      }
      if (extras.get("spladeCoveragePercent") instanceof Number n) {
        sb.append(" SPLADE: ").append(String.format("%.0f%%", n.doubleValue())).append(".");
      }
      sb.append(
          " Use justsearch_answer for questions, justsearch_search for exploration,"
              + " justsearch_status for detailed health.");
      return sb.toString();
    } catch (Exception e) {
      return "JustSearch index status unknown.";
    }
  }

  private static Map<String, Object> promptMessages(String systemContext, String userMessage) {
    return Map.of(
        "messages",
        List.of(
            Map.of("role", "assistant", "content", Map.of("type", "text", "text", systemContext)),
            Map.of("role", "user", "content", Map.of("type", "text", "text", userMessage))));
  }

  // =========================================================================
  // Resources (proposed URIs + catalog-driven)
  // =========================================================================

  public Map<String, Object> listResources(
      List<io.justsearch.agent.api.registry.ResourceCatalog> resourceCatalogs) {
    var resources = new ArrayList<Map<String, Object>>();

    // Proposed URIs per tempdoc 500
    resources.add(
        resource(
            "justsearch://index/summary",
            "Index Summary",
            "Document count, enrichment coverage, readiness state"));
    resources.add(
        resource(
            "justsearch://index/roots",
            "Indexed Roots",
            "List of indexed folder paths"));
    resources.add(
        resource(
            "justsearch://index/top-sources",
            "Top Sources",
            "Most common document sources (meta_source facet values)"));
    resources.add(
        resource(
            "justsearch://index/top-entities",
            "Top Entities",
            "Most common persons and organizations (entity facet values)"));

    // Catalog-driven resources (for subscription support)
    for (var catalog : resourceCatalogs) {
      for (var r : catalog.definitions()) {
        if (!Set.of(io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Audience.AGENT).contains(r.audience())) continue;
        resources.add(
            resource(
                "justsearch://resource/" + r.id().value(),
                r.presentation().labelKey().value(),
                r.presentation().descriptionKey().value()));
      }
    }

    return Map.of("resources", resources);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> readResource(String uri) {
    if (uri == null) return Map.of("contents", List.of());
    return switch (uri) {
      case "justsearch://index/summary" -> readIndexSummary(uri);
      case "justsearch://index/roots" -> readIndexRoots(uri);
      case "justsearch://index/top-sources" -> readTopFacet(uri, "meta_source", 10);
      case "justsearch://index/top-entities" ->
          readTopEntities(uri);
      default -> {
        if (uri.startsWith("justsearch://resource/")) {
          yield readIndexSummary(uri);
        }
        yield Map.of("contents", List.of());
      }
    };
  }

  private Map<String, Object> readIndexSummary(String uri) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return resourceError(uri, "Knowledge server not available");
      var status = ctrl.getAdapter().status();
      var sb = new StringBuilder();
      sb.append("documents: ").append(status.docCount()).append("\n");
      sb.append("ready: ").append(status.ready()).append("\n");
      sb.append("healthy: ").append(status.healthy()).append("\n");
      sb.append("state: ").append(status.state()).append("\n");
      Map<String, Object> extras = status.extras();
      if (extras.get("embeddingCoveragePercent") instanceof Number n)
        sb.append("embeddingCoverage: ").append(String.format("%.1f%%", n.doubleValue())).append("\n");
      if (extras.get("spladeCoveragePercent") instanceof Number n)
        sb.append("spladeCoverage: ").append(String.format("%.1f%%", n.doubleValue())).append("\n");
      return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "text/plain", "text", sb.toString())));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  private Map<String, Object> readIndexRoots(String uri) {
    try {
      // Use the browse tool to list roots
      var result = callOperation("core.browse-folders", Map.of(), null);
      @SuppressWarnings("unchecked")
      var content = (List<Map<String, Object>>) result.get("content");
      String text = content != null && !content.isEmpty() ? (String) content.get(0).get("text") : "No roots";
      return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "text/plain", "text", text)));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  private Map<String, Object> readTopFacet(String uri, String field, int size) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return resourceError(uri, "Knowledge server not available");
      var req = new KnowledgeSearchRequest(
          "", 0, "hybrid", null, null, null, null, null,
          new KnowledgeSearchRequest.Facets(true, null,
              List.of(new KnowledgeSearchRequest.FieldSpec(field, size))),
          null, null, null, null);
      var resp = ctrl.getAdapter().search(req);
      String text = resp.facets() != null ? MAPPER.writeValueAsString(resp.facets()) : "{}";
      return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "application/json", "text", text)));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  private Map<String, Object> readTopEntities(String uri) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return resourceError(uri, "Knowledge server not available");
      var req = new KnowledgeSearchRequest(
          "", 0, "hybrid", null, null, null, null, null,
          new KnowledgeSearchRequest.Facets(true, null, List.of(
              new KnowledgeSearchRequest.FieldSpec("entity_persons_raw", 10),
              new KnowledgeSearchRequest.FieldSpec("entity_organizations_raw", 10),
              new KnowledgeSearchRequest.FieldSpec("entity_locations_raw", 10))),
          null, null, null, null);
      var resp = ctrl.getAdapter().search(req);
      String text = resp.facets() != null ? MAPPER.writeValueAsString(resp.facets()) : "{}";
      return Map.of("contents", List.of(Map.of("uri", uri, "mimeType", "application/json", "text", text)));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private void appendFacetSidecar(StringBuilder sb, String query) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return;
      var facetReq = new KnowledgeSearchRequest(
          query, 0, "hybrid", null, null, null, null, null,
          new KnowledgeSearchRequest.Facets(true, null, List.of(
              new KnowledgeSearchRequest.FieldSpec("meta_source", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_persons_raw", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_organizations_raw", 5))),
          null, null, null, null);
      var resp = ctrl.getAdapter().search(facetReq);
      if (resp.facets() != null && !resp.facets().isEmpty()) {
        sb.append("\n--- Top sources & entities ---\n");
        for (var entry : resp.facets().entrySet()) {
          String name = entry.getKey().replace("_raw", "");
          if (entry.getValue() instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("  ").append(name).append(": ");
            sb.append(
                m.entrySet().stream()
                    .limit(5)
                    .map(e -> String.valueOf(e.getKey()))
                    .toList());
            sb.append("\n");
          }
        }
      }
    } catch (Exception e) {
      log.debug("Facet sidecar failed: {}", e.getMessage());
    }
  }

  private void appendEnrichmentHint(StringBuilder sb) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return;
      var status = ctrl.getAdapter().status();
      Map<String, Object> extras = status.extras();
      boolean lowEmbedding = extras.get("embeddingCoveragePercent") instanceof Number n && n.doubleValue() < 100;
      boolean lowSplade = extras.get("spladeCoveragePercent") instanceof Number n && n.doubleValue() < 100;
      if (lowEmbedding || lowSplade) {
        sb.append("\nHint: Enrichment in progress — semantic search and entity filters may be limited until complete. Check justsearch_status.\n");
      }
    } catch (Exception e) {
      // silent
    }
  }

  private void appendEnrichmentHintToList(List<String> hints) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return;
      var status = ctrl.getAdapter().status();
      Map<String, Object> extras = status.extras();
      boolean lowEmbedding = extras.get("embeddingCoveragePercent") instanceof Number n && n.doubleValue() < 100;
      boolean lowSplade = extras.get("spladeCoveragePercent") instanceof Number n && n.doubleValue() < 100;
      if (lowEmbedding || lowSplade) {
        hints.add("Enrichment in progress — semantic search and entity filters may be limited. Check justsearch_status.");
      }
    } catch (Exception e) {
      // silent
    }
  }

  private Operation resolveOperation(String idValue) {
    for (OperationCatalog catalog : operationCatalogs) {
      var found = catalog.definitions().stream()
          .filter(op -> op.id().value().equals(idValue))
          .findFirst();
      if (found.isPresent()) return found.get();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static KnowledgeSearchRequest.Filters parseFilters(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) return null;
    return new KnowledgeSearchRequest.Filters(
        null, null, null, null,
        (String) raw.get("path_prefix"), null, null,
        toStringList(raw, "entity_persons"),
        toStringList(raw, "entity_organizations"),
        toStringList(raw, "entity_locations"),
        toStringList(raw, "meta_source"),
        toStringList(raw, "meta_author"),
        toStringList(raw, "meta_category"),
        null, null,
        // Tempdoc 585 §D Phase 4 (D4b) — collection scope (unused by the MCP search surface).
        toStringList(raw, "collection"));
  }

  @SuppressWarnings("unchecked")
  private static List<String> toStringList(Map<String, Object> map, String key) {
    if (map == null) return List.of();
    Object val = map.get(key);
    if (val instanceof List<?> list) return list.stream().map(String::valueOf).toList();
    if (val instanceof String s) return List.of(s);
    return List.of();
  }

  private static Map<String, Object> tool(
      String name, String description, Map<String, Object> inputSchema,
      Map<String, Object> annotations) {
    var t = new LinkedHashMap<String, Object>();
    t.put("name", name);
    t.put("description", description);
    t.put("inputSchema", inputSchema);
    if (!annotations.isEmpty()) t.put("annotations", annotations);
    return t;
  }

  private static Map<String, Object> schema(
      Map<String, Object> properties, List<String> required) {
    var s = new LinkedHashMap<String, Object>();
    s.put("type", "object");
    s.put("properties", properties);
    if (!required.isEmpty()) s.put("required", required);
    return s;
  }

  private static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  private static Map<String, Object> resource(String uri, String name, String description) {
    return Map.of("uri", uri, "name", name, "description", description, "mimeType", "application/json");
  }

  private static Map<String, Object> resourceError(String uri, String message) {
    return Map.of("contents", List.of(Map.of(
        "uri", uri, "mimeType", "text/plain", "text", "Error: " + message)));
  }

  static Map<String, Object> errorContent(String message) {
    return Map.of("content", List.of(Map.of("type", "text", "text", message)), "isError", true);
  }
}
