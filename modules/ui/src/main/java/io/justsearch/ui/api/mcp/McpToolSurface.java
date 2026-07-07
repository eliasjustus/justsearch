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

  // Tempdoc 655: the single-sourced, COMPARATIVE tool-selection guidance — the one string that
  // states when to prefer the index over reading files directly. Consumed by both the connect-time
  // instructions() surface (MCP `initialize`) and the user-invoked prompt path (getStatusContext),
  // so the two cannot fork (654 "projection, not fork"). Kept minimal and honest (small-model
  // description bloat hurts adoption per ADR-0015 / tempdoc 366; and file tools genuinely are fine
  // for small/exact cases — no absolute "beats grep" claim).
  private static final String TOOL_SELECTION_GUIDANCE =
      "JustSearch maintains a local search index over the user's documents. Prefer justsearch_answer"
          + " — which retrieves and assembles cited passages from across many documents in a single"
          + " call — over reading files one-by-one when the corpus is large, when the question is"
          + " conceptual or semantic (no exact keyword or filename to match on), when it spans"
          + " multiple documents, or when you want to conserve context. For a small set of files or"
          + " an exact string / filename lookup, ordinary file tools are equally good. Use"
          + " justsearch_search to explore what is in the index, and justsearch_status for the live"
          + " index size and readiness.";

  private final List<OperationCatalog> operationCatalogs;
  private final OperationDispatcher dispatcher;
  private final java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup;
  private final java.util.function.Supplier<HeadAssembly> appFacadeLookup;
  private final Clock clock;
  // Tempdoc 501 Phase 15: optional publisher lookup for the runtime_manifest tool.
  // Null when the test-only Builder path constructed the surface without one.
  private final java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
      manifestPublisherLookup;
  // Tempdoc 655: the SAME store/broadcast the REST gate path (OperationsController) uses, so a
  // gate fired here is visible to the one approve endpoint and the one live shell signal —
  // nullable for legacy/test wiring, in which case the gate still fails closed, just without a
  // surfaced pending record (matches OperationsController's own null-tolerant pattern).
  private final io.justsearch.app.services.intent.PendingAuthorizationStore
      pendingAuthorizationStore;
  private final io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
      pendingAuthorizationChanges;
  // Tempdoc 655: boundary schema validation, applied uniformly to all 6 tools regardless of
  // which backend path (direct in-process call vs. Operation dispatch) ultimately serves them —
  // stateless/cache-only, so a private instance per surface is fine.
  private final io.justsearch.app.services.registry.executor.OperationInputSchemaValidator
      inputValidator =
          new io.justsearch.app.services.registry.executor.OperationInputSchemaValidator();

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
    this(
        operationCatalogs,
        dispatcher,
        knowledgeLookup,
        appFacadeLookup,
        clock,
        manifestPublisherLookup,
        null,
        null);
  }

  /** Canonical constructor (tempdoc 655): also wires the pending-authorization mechanism. */
  public McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock,
      java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
          manifestPublisherLookup,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore,
      io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
          pendingAuthorizationChanges) {
    this.operationCatalogs = List.copyOf(operationCatalogs);
    this.dispatcher = dispatcher;
    this.knowledgeLookup = knowledgeLookup;
    this.appFacadeLookup = appFacadeLookup;
    this.clock = clock;
    this.manifestPublisherLookup =
        manifestPublisherLookup != null ? manifestPublisherLookup : () -> null;
    this.pendingAuthorizationStore = pendingAuthorizationStore;
    this.pendingAuthorizationChanges = pendingAuthorizationChanges;
  }

  // =========================================================================
  // tools/list — 5 curated tools, position-bias ordered
  // =========================================================================

  public Map<String, Object> listTools() {
    return Map.of(
        "tools",
        List.of(
            tool("justsearch_answer", ANSWER_DESC, ANSWER_SCHEMA, Map.of("readOnlyHint", true)),
            tool("justsearch_search", SEARCH_DESC, SEARCH_SCHEMA, Map.of("readOnlyHint", true)),
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
            tool("justsearch_status", STATUS_DESC, STATUS_SCHEMA, Map.of("readOnlyHint", true)),
            tool(
                "justsearch_runtime_manifest",
                RUNTIME_MANIFEST_DESC,
                RUNTIME_MANIFEST_SCHEMA,
                Map.of("readOnlyHint", true))));
  }

  private static final String RUNTIME_MANIFEST_DESC =
      "Returns the redacted runtime manifest (tempdoc 501 §12.4): JSON document carrying the "
          + "current backend's identity (instanceId, pid, dataDir), lifecycle projection, "
          + "head/worker state, and AI runtime state. Same body served at "
          + "GET /api/runtime/manifest and GET /.well-known/justsearch/manifest.json — this tool "
          + "is the MCP-native surface for identity-aware caching and cross-restart detection.";

  // --- Tool schemas (tempdoc 655): named so listTools()'s visible schema and the boundary
  // validator (validateDirectDispatchArgs) provably validate against the SAME object for the 4
  // tools with no backing Operation — not two independently-authored literals that can drift. ---

  // Tempdoc 655 fix pass: the nested shape of `filters`, declared (not left as an opaque
  // "object") so a malformed nested field (e.g. path_prefix sent as a number) is caught by the
  // boundary validator instead of reaching McpToolSurface#parseFilters's unchecked casts.
  private static final Map<String, Object> FILTERS_SCHEMA = buildFiltersSchema();

  private static Map<String, Object> buildFiltersSchema() {
    var s =
        schema(
            Map.of(
                "path_prefix", prop("string", "Restrict results to paths under this prefix"),
                "meta_source", propStringArray("Filter by source"),
                "meta_author", propStringArray("Filter by author"),
                "meta_category", propStringArray("Filter by category"),
                "entity_persons", propStringArray("Filter by person entity"),
                "entity_organizations", propStringArray("Filter by organization entity"),
                "entity_locations", propStringArray("Filter by location entity")),
            List.of());
    // Keep the natural-language guidance the old opaque "object" prop carried (ADR-0015:
    // descriptions are load-bearing for small-model tool use) alongside the now-declared shape.
    s.put(
        "description",
        "Hard filters: {meta_source: [...], entity_persons: [...], path_prefix: \"...\", ...}");
    return s;
  }

  private static final Map<String, Object> ANSWER_SCHEMA =
      schema(
          Map.of(
              "query", prop("string", "The question to answer"),
              "top_k", prop("integer", "Number of passages to retrieve (default 5, max 20)"),
              "filters", FILTERS_SCHEMA),
          List.of("query"));

  private static final Map<String, Object> SEARCH_SCHEMA =
      schema(
          Map.of(
              "query", prop("string", "Search text"),
              "limit", prop("integer", "Max results (default 10, max 50)"),
              "mode", prop("string", "Search mode: hybrid (default), text, or vector"),
              "filters", FILTERS_SCHEMA),
          List.of("query"));

  private static final Map<String, Object> STATUS_SCHEMA = schema(Map.of(), List.of());

  private static final Map<String, Object> RUNTIME_MANIFEST_SCHEMA = schema(Map.of(), List.of());

  // =========================================================================
  // tools/call — route to service layer
  // =========================================================================

  @SuppressWarnings("unchecked")
  public Map<String, Object> callTool(String name, Map<String, Object> arguments, String sessionId) {
    return callTool(name, arguments, sessionId, null);
  }

  /**
   * Tempdoc 655: {@code requestedBy} is the calling MCP client's self-reported name (captured
   * from the {@code initialize} handshake's {@code clientInfo}, resolved by {@link
   * McpProtocolHandler} from its session map) — display-only, threaded through to a pending
   * authorization if this call ends up gated, so the approval ceremony can show who's asking.
   * Never used for any trust decision.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> callTool(
      String name, Map<String, Object> arguments, String sessionId, String requestedBy) {
    // Tempdoc 655: validate every tool's arguments against its declared schema at the MCP
    // boundary, before dispatch — independent of which backend path (direct in-process call vs.
    // Operation dispatch) ultimately serves the tool. Previously only browse/ingest (the two
    // Operation-backed tools) got real validation via the executor's own pipeline downstream;
    // the other 4 skipped it entirely, so a wrong-typed argument surfaced as an unhandled cast
    // exception rather than a clean schema error.
    Map<String, Object> schemaForDirectDispatch =
        switch (name) {
          case "justsearch_answer" -> ANSWER_SCHEMA;
          case "justsearch_search" -> SEARCH_SCHEMA;
          case "justsearch_status" -> STATUS_SCHEMA;
          case "justsearch_runtime_manifest" -> RUNTIME_MANIFEST_SCHEMA;
          default -> null;
        };
    if (schemaForDirectDispatch != null) {
      Map<String, Object> invalid = validateArgsOrNull(name, schemaForDirectDispatch, arguments);
      if (invalid != null) {
        return invalid;
      }
    }
    return switch (name) {
      case "justsearch_answer" -> callAnswer(arguments);
      case "justsearch_search" -> callSearch(arguments);
      case "justsearch_browse" ->
          callOperation("core.browse-folders", arguments, sessionId, requestedBy);
      case "justsearch_ingest" ->
          callOperation("core.ingest-files", arguments, sessionId, requestedBy);
      case "justsearch_status" -> callStatus();
      case "justsearch_runtime_manifest" -> callRuntimeManifest();
      default -> unknownToolWithSuggestions(name);
    };
  }

  /**
   * Tempdoc 655: validate {@code arguments} against {@code schema} using the same {@link
   * io.justsearch.app.services.registry.executor.OperationInputSchemaValidator} the Operations
   * pipeline uses. Returns a clean MCP tool error (never {@code null} on failure) instead of
   * letting a malformed argument reach a raw unchecked cast further down; returns {@code null}
   * when the args validate.
   */
  private Map<String, Object> validateArgsOrNull(
      String cacheKey, Map<String, Object> schema, Map<String, Object> arguments) {
    try {
      String schemaJson = MAPPER.writeValueAsString(schema);
      String argsJson = MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
      return inputValidator
          .validate(cacheKey, schemaJson, argsJson)
          .<Map<String, Object>>map(
              result -> errorContent("Invalid arguments for " + cacheKey + ": " + result.message()))
          .orElse(null);
    } catch (Exception e) {
      // Serialization failure on our OWN schema/args maps would be a substrate bug, not a caller
      // error — don't fail the call closed over it, just skip validation for this invocation.
      log.warn("MCP boundary validation failed to run for {}: {}", cacheKey, e.getMessage());
      return null;
    }
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

      // Tempdoc 655: comparative response hint (placed in the RESPONSE, re-read each turn, per
      // tempdoc 366's finding that descriptions are forgotten by turn 5). Counts DISTINCT cited
      // documents — see comparativeAnswerHint for why chunksFound cannot back a "multiple documents"
      // claim.
      String comparativeHint = comparativeAnswerHint(result.citations());
      if (!comparativeHint.isEmpty()) {
        sb.append(comparativeHint).append("\n");
      }

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

  /**
   * Tempdoc 655: the comparative answer hint, keyed on DISTINCT cited documents — the only honest
   * basis for a "spanning multiple documents" claim. {@code chunksFound} cannot back it: it counts
   * chunks (many per document, and includes found-but-unused chunks — e.g. the virtual-chunk fallback
   * reports every sub-chunk across every document), and it stays &gt; 1 even on a bare full-text dump
   * where no chunk assembly happened. Citations carry {@code parentDocId} and are empty unless real
   * chunk assembly occurred, so counting distinct parentDocId is correct and self-suppressing on the
   * fallback path. ({@code docsUsed} is unusable here — the rich-params retrieve path reports it as 0
   * regardless; logged to observations.) Package-private + pure so it is unit-tested directly, without
   * the live retrieve mock chain.
   */
  static String comparativeAnswerHint(List<DocumentService.ContextCitation> citations) {
    if (citations == null) return "";
    long distinctDocs =
        citations.stream().map(c -> c.parentDocId()).filter(id -> !id.isBlank()).distinct().count();
    if (distinctDocs > 1) {
      return "Assembled evidence from "
          + distinctDocs
          + " documents in a single retrieval call — for a question spanning multiple documents this"
          + " is fewer steps than locating and reading each file.";
    }
    return "";
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
      // Tempdoc 655: comparative response hint — searched the whole index in one call, which beats
      // listing-and-reading files for a topical/semantic query. Factual, only on a productive search.
      if (resp.totalHits() > 0) {
        hints.add(
            "Searched the index in one call. For conceptual or cross-document questions,"
                + " justsearch_answer returns assembled cited passages directly.");
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
    return callOperation(opIdValue, arguments, sessionId, null);
  }

  private Map<String, Object> callOperation(
      String opIdValue, Map<String, Object> arguments, String sessionId, String requestedBy) {
    try {
      Operation op = resolveOperation(opIdValue);
      if (op == null) return errorContent("Operation not available: " + opIdValue);
      String argsJson = MAPPER.writeValueAsString(arguments);
      // Tempdoc 655: validate against the Operation's OWN declared schema — the real enforcement
      // schema, not a second MCP-authored literal — before dispatch, so a malformed call gets a
      // clean MCP error here instead of surfacing however the executor happens to fail later.
      Map<String, Object> invalid =
          inputValidator
              .validate(op, argsJson)
              .<Map<String, Object>>map(
                  result ->
                      errorContent("Invalid arguments for " + opIdValue + ": " + result.message()))
              .orElse(null);
      if (invalid != null) {
        return invalid;
      }
      InvocationProvenance provenance =
          InvocationProvenance.mcp(clock.instant(), Optional.ofNullable(sessionId));
      OperationResult opResult;
      try {
        opResult = dispatcher.dispatch(op, argsJson, provenance);
      } catch (ConfirmationRequiredException e) {
        return handleConfirmationRequired(op, argsJson, e, requestedBy);
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

  /**
   * Tempdoc 655: an MCP tool call hit the trust lattice's confirmation gate. Reuses the SAME
   * mechanism the browser UI's gate path uses (via the shared {@link
   * io.justsearch.app.services.intent.PendingAuthorizationStore}) instead of the previous
   * fabricated {@code _confirmationToken} hint, which nothing in the codebase ever read — the
   * tool could never actually succeed via that path.
   *
   * <p>Design (tempdoc 655 §"Design decision made during investigation"): approval completes the
   * operation directly, server-side, when a human approves in the JustSearch app — not via a
   * later MCP retry/replay. This call returns immediately either way; it never blocks waiting for
   * human approval, sidestepping MCP hosts' inconsistent/short tool-call timeouts entirely.
   */
  private Map<String, Object> handleConfirmationRequired(
      Operation op, String argsJson, ConfirmationRequiredException e, String requestedBy) {
    String message;
    if (pendingAuthorizationStore == null) {
      // Legacy/test wiring with no store — fail closed, but say so plainly rather than
      // advertising a retry path that cannot work.
      message =
          "Operation '"
              + e.operationRef().value()
              + "' requires confirmation (gate: "
              + e.gateBehavior()
              + ") and no approval mechanism is wired in this deployment. It cannot be completed"
              + " via MCP.";
    } else {
      String pendingId =
          pendingAuthorizationStore.create(
              op.id().value(), argsJson, e.sourceTier(), op.policy().risk(), e.gateBehavior(),
              e.getMessage(), requestedBy,
              io.justsearch.agent.api.registry.TransportTag.MCP);
      if (pendingAuthorizationChanges != null) {
        // Tempdoc 655 fix pass: routing info only — no argsSummary/rationale on the broadcast
        // (see PendingAuthorizationEvent's doc comment for why). A subscriber fetches the
        // decision content itself, by id, via GET /api/authorizations/pending/{id}.
        pendingAuthorizationStore
            .peek(pendingId)
            .ifPresent(
                pending ->
                    pendingAuthorizationChanges.broadcast(
                        new io.justsearch.app.observability.operations.PendingAuthorizationEvent(
                            pending.id(),
                            pending.operationId(),
                            pending.sourceTier(),
                            pending.riskTier(),
                            pending.gateBehavior(),
                            pending.createdAt(),
                            pending.expiresAt(),
                            pending.transport())));
      }
      message =
          "Operation '"
              + e.operationRef().value()
              + "' requires your approval (gate: "
              + e.gateBehavior()
              + "). A request is now showing in the JustSearch app — once approved there, it"
              + " completes automatically. You do not need to retry this call.";
    }
    return Map.of("content", List.of(Map.of("type", "text", "text", message)), "isError", true);
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

  /**
   * Tempdoc 655: the connect-time steering surface, returned as MCP {@code initialize}'s
   * {@code instructions} field (populated by {@link McpProtocolHandler#handleInitialize}). Static,
   * comparative tool-selection guidance — deliberately does NOT call the worker for live status, so
   * the connect handshake carries no cross-process round-trip. Live index state is available to the
   * agent via {@code justsearch_status}; the user-invoked prompt path ({@link #getStatusContext})
   * prepends live counts to the SAME guidance string, so the two surfaces cannot fork.
   */
  public String instructions() {
    return TOOL_SELECTION_GUIDANCE;
  }

  private String getStatusContext() {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return "JustSearch index status unknown. " + TOOL_SELECTION_GUIDANCE;
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
      // Single-sourced comparative guidance (tempdoc 655): the feature-forward enumeration that used
      // to live here ("Use justsearch_answer for questions…") is superseded by TOOL_SELECTION_GUIDANCE
      // so the prompt path and the initialize instructions field speak with one voice.
      sb.append(" ").append(TOOL_SELECTION_GUIDANCE);
      return sb.toString();
    } catch (Exception e) {
      return "JustSearch index status unknown. " + TOOL_SELECTION_GUIDANCE;
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
        // Tempdoc 655 fix pass: defensive instanceof check (matches toStringList's existing safe
        // pattern below) as belt-and-suspenders — the boundary schema validator should already
        // reject a non-string path_prefix before this is reached, but this handler shouldn't
        // trust that unconditionally.
        raw.get("path_prefix") instanceof String pathPrefix ? pathPrefix : null,
        null, null,
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

  /** Tempdoc 655 fix pass: a declared array-of-string property (mirrors `paths` on ingest). */
  private static Map<String, Object> propStringArray(String description) {
    return Map.of(
        "type", "array", "items", Map.of("type", "string"), "description", description);
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
