/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.configuration.resolved.ConfigStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only tool for querying the knowledge index. Auto-approved (no user gate).
 *
 * <p>Returns a compact text summary of search results for LLM consumption, including titles, paths,
 * scores, and excerpt snippets.
 */
/**
 * Read-only knowledge-index search tool. Per Phase 12 of tempdoc 429: previously
 * implemented {@code ToolDefinition}; now a plain class invoked via
 * {@link io.justsearch.app.services.registry.operations.handlers.SearchOperationHandler}
 * which adapts {@code execute(String): OperationResult} to the substrate's
 * {@link io.justsearch.agent.api.registry.OperationHandler} contract.
 */
public final class SearchTool {
  private static final Logger LOG = LoggerFactory.getLogger(SearchTool.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  // Three-layer truncation for search results delivered to the LLM (see tempdocs 208, 213):
  // Layer 1 (formatResults): per-result budget = MAX_TOOL_RESULT_CHARS / hits.size()
  //   — accumulates excerpt chars per hit; breaks when budget exhausted.
  //   — 800-char per-region cap preserved as safety net for very long individual regions.
  // Layer 2 (AgentLoopService.truncateForContext): hard cut at MAX_TOOL_RESULT_CHARS.
  // Layer 3 (AgentLoopService.compressToolMessagesForContext): strips Excerpt: lines from
  //   older tool messages to free context for subsequent iterations.
  // The k=3 default was set by tempdoc 213, superseding limit-5 from tempdoc 208 compression work.
  private static final int DEFAULT_LIMIT =
      Math.max(1, Math.min(20, resolveSearchDefaultLimit()));
  private static final int MAX_LIMIT = 20;

  private static int resolveSearchDefaultLimit() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? cs.get().agent().searchDefaultLimit() : 3;
  }

  private static int resolveMaxToolResultChars() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? cs.get().agent().maxToolResultChars() : 4000;
  }

  /**
   * Resolves the default search mode from config. Returns null (meaning text/BM25) when unset,
   * or a mode string like "hybrid" or "vector" when configured via
   * JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE.
   */
  private static String resolveSearchDefaultMode() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs != null) {
      String mode = cs.get().agent().searchDefaultMode();
      if (mode != null && !mode.isBlank()) {
        return mode;
      }
    }
    return null;
  }

  /**
   * Translates a mode string to a PipelineConfig preset (256: Phase G2). Keeps the mode parameter
   * in the tool schema for backward compatibility with trained LLMs.
   */
  static PipelineConfig modeToPreset(String mode) {
    if (mode == null || mode.isBlank()) return PipelineConfig.HYBRID;
    return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
      case "text" -> PipelineConfig.TEXT;
      case "vector" -> PipelineConfig.VECTOR;
      case "hybrid" -> PipelineConfig.HYBRID;
      default -> PipelineConfig.HYBRID;
    };
  }

  /** Parses a JSON pipeline argument into a PipelineConfig (256: Phase H1). */
  static PipelineConfig parsePipelineArg(JsonNode node) {
    return new PipelineConfig(
        boolField(node, "sparseEnabled"),
        boolField(node, "denseEnabled"),
        boolField(node, "spladeEnabled"),
        node.has("fusionAlgorithm") ? node.get("fusionAlgorithm").asText("none") : "none",
        boolField(node, "lambdamartEnabled"),
        boolField(node, "crossEncoderEnabled"),
        node.has("crossEncoderWindow") ? node.get("crossEncoderWindow").asInt(0) : 0,
        boolField(node, "expansionEnabled"),
        boolField(node, "freshnessEnabled"));
  }

  private static boolean boolField(JsonNode node, String field) {
    return node.has(field) && node.get(field).asBoolean(false);
  }

  /**
   * Tempdoc 561 P-A5 — bound a rendered excerpt/preview to {@code maxLen} chars at a WORD boundary,
   * never a raw mid-word cut. Mirrors the producer-owned-boundary fix made for
   * {@code ContextCitation} in {@code RagContextOps#clampExcerptToWordBoundary}; kept local (a
   * separate module + a separate, agent-context budget — per AHA, not over-DRYed across modules).
   * Walks back to the last whitespace within a 40-char lookback so a single long token still
   * truncates; appends an ellipsis when truncated.
   */
  private static String clampToWordBoundary(String text, int maxLen) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLen || maxLen <= 0) {
      return text;
    }
    int scan = maxLen;
    while (scan > maxLen - 40 && scan > 0 && !Character.isWhitespace(text.charAt(scan))) {
      scan--;
    }
    int cut = (scan > 0 && Character.isWhitespace(text.charAt(scan))) ? scan : maxLen;
    return text.substring(0, cut).stripTrailing() + "...";
  }

  private static final String PARAMETER_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "query": {
            "type": "string",
            "description": "Search query text"
          },
          "limit": {
            "type": "integer",
            "description": "Maximum number of results (default 3, max 20)",
            "default": 3,
            "maximum": 20
          },
          "mode": {
            "type": "string",
            "enum": ["text", "vector", "hybrid"],
            "description": "Search mode preset (text, vector, or hybrid). Use pipeline for fine-grained control."
          },
          "pipeline": {
            "type": "object",
            "description": "Fine-grained pipeline control. Overrides mode when provided. Components: sparseEnabled (BM25), denseEnabled (vector KNN), spladeEnabled (learned sparse), fusionAlgorithm (rrf/none), lambdamartEnabled, crossEncoderEnabled, expansionEnabled (LLM query expansion).",
            "properties": {
              "sparseEnabled": { "type": "boolean" },
              "denseEnabled": { "type": "boolean" },
              "spladeEnabled": { "type": "boolean" },
              "fusionAlgorithm": { "type": "string", "enum": ["rrf", "none"] },
              "lambdamartEnabled": { "type": "boolean" },
              "crossEncoderEnabled": { "type": "boolean" },
              "expansionEnabled": { "type": "boolean" }
            }
          },
          "path_prefix": {
            "type": "string",
            "description": "Restrict results to files under this folder path"
          }
        },
        "required": ["query"]
      }
      """;

  private final SearchCallback searchCallback;
  private final Supplier<List<BrowseTool.RootInfo>> rootsSupplier; // nullable

  public SearchTool(SearchCallback searchCallback) {
    this(searchCallback, (Supplier<List<BrowseTool.RootInfo>>) null);
  }

  public SearchTool(
      SearchCallback searchCallback, Supplier<List<BrowseTool.RootInfo>> rootsSupplier) {
    this.searchCallback = searchCallback;
    this.rootsSupplier = rootsSupplier;
  }

  /** Per tempdoc 429 §C.G: parameter schema preserved as a constant for unit tests. */
  public static String parameterSchema() {
    return PARAMETER_SCHEMA;
  }

  public OperationResult execute(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return OperationResult.failure("No arguments provided");
    }
    try {
      JsonNode args = MAPPER.readTree(argumentsJson);

      // Extract query (required)
      String query = args.has("query") ? args.get("query").asText() : null;
      if (query == null || query.isBlank()) {
        return OperationResult.failure("Search query is required");
      }

      // Sanitize file-path queries: LLM sometimes sends a file path as query text
      // (e.g., "docs/reference/config/env-vars.md"), which causes Lucene parse errors.
      // Convert path separators to spaces and strip file extensions for keyword matching.
      query = sanitizeFilePathQuery(query);

      // Extract optional parameters
      int limit = DEFAULT_LIMIT;
      if (args.has("limit")) {
        limit = Math.min(args.get("limit").asInt(DEFAULT_LIMIT), MAX_LIMIT);
        if (limit < 1) limit = DEFAULT_LIMIT;
      }
      // 256-H1: pipeline parameter overrides mode when both are provided.
      PipelineConfig pipeline;
      if (args.has("pipeline") && args.get("pipeline").isObject()) {
        pipeline = parsePipelineArg(args.get("pipeline"));
      } else {
        String modeStr = args.has("mode") ? args.get("mode").asText() : resolveSearchDefaultMode();
        pipeline = modeToPreset(modeStr);
      }
      String pathPrefix = args.has("path_prefix") ? args.get("path_prefix").asText() : null;

      // Resolve relative path_prefix against indexed roots, then validate
      if (pathPrefix != null && !pathPrefix.isBlank()) {
        if (!AgentToolPaths.looksAbsolute(pathPrefix) && rootsSupplier != null) {
          String resolved =
              AgentToolPaths.resolveRelativePath(pathPrefix, rootsSupplier.get());
          if (resolved != null) {
            pathPrefix = resolved;
          }
        }
        String rejection = validatePathPrefix(pathPrefix);
        if (rejection != null) {
          return OperationResult.failure(rejection);
        }
      }

      // Build search request
      KnowledgeSearchRequest.Filters filters = null;
      if (pathPrefix != null && !pathPrefix.isBlank()) {
        filters =
            KnowledgeSearchRequestFiltersBuilder.builder()
                .pathPrefix(pathPrefix)
                .build();
      }

      var request =
          new KnowledgeSearchRequest(
              query, limit, null, null, null, null, filters, null, null, null, true, null, pipeline);

      // Execute search
      KnowledgeSearchResponse response = searchCallback.search(request);
      if (response == null) {
        return OperationResult.failure("Search returned no response");
      }

      // Format results for LLM consumption
      String formatted = formatResults(response);
      if (response.results().isEmpty() && pathPrefix != null && !AgentToolPaths.looksAbsolute(pathPrefix)) {
        formatted +=
            " HINT: The path_prefix \""
                + pathPrefix
                + "\" looks relative. Use an absolute path from browse_folders or the"
                + " system prompt's indexed root folders.";
      }
      // Tempdoc 561 #6: carry STRUCTURED evidence alongside the LLM-facing text so the tool card can
      // render real evidence cards (filename · location · excerpt) instead of a raw monospace dump.
      // NOTE: deliberately NO relevance score — hit.score() is the uncalibrated RANKING score, which
      // 559 §5 / §18 C-6 say must not be surfaced as a "% relevance" (that would fabricate calibration).
      return OperationResult.success(formatted, buildSearchEvidence(response));

    } catch (Exception e) {
      LOG.error("SearchTool execution failed", e);
      return OperationResult.failure("Search error: " + e.getMessage());
    }
  }

  /**
   * Tempdoc 561 #6: project the search hits into structured evidence the tool card renders (title /
   * path / excerpt / line) — the producer-owned excerpt is already word-boundary snapped. The
   * rendered {@code searchResults} carry no score (uncalibrated ranking; 559 §5).
   *
   * <p>Tempdoc 580 §17 P4 (Fix B): also emit a SEPARATE {@code feedbackFeatures} list — the per-leg
   * retrieval scores keyed by {@code parentDocId} — for the agent-citation feedback loop. This is a
   * FEEDBACK channel, NOT rendered by the tool card, so it does not surface uncalibrated scores to the
   * UI (the 559 §5 line is about <em>display</em>). app-services captures it into a {@code FeatureSnapshot}
   * from the {@code tool_exec_completed} event so agent CITED/SHOWN dispositions become joinable labels.
   * Returns {@code {"searchResults": [...], "feedbackFeatures": [...]}}.
   */
  private Map<String, Object> buildSearchEvidence(KnowledgeSearchResponse response) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<Map<String, Object>> feedback = new ArrayList<>();
    int rank = 0;
    for (KnowledgeSearchResponse.Hit hit : response.results()) {
      rank++;
      feedback.add(feedbackFeatures(hit, rank));
      var fields = hit.fields();
      var item = new LinkedHashMap<String, Object>();
      item.put("title", fields.getOrDefault("title", fields.getOrDefault("filename", "")));
      item.put("path", fields.getOrDefault("path", ""));
      String excerpt;
      int line = 0;
      if (!hit.excerptRegions().isEmpty()) {
        var region = hit.excerptRegions().get(0);
        excerpt = clampToWordBoundary(region.text().strip().replace("\r", "").replace("\n", " "), 320);
        line = region.approxLine();
      } else {
        excerpt = clampToWordBoundary(
            fields.getOrDefault("content_preview", "").strip().replace("\r", "").replace("\n", " "),
            320);
      }
      item.put("excerpt", excerpt);
      item.put("line", line);
      // Tempdoc 565 §3.A — carry the chunk identity + passage span so the answer's grounding is a
      // verifiable, clickable LOCAL-passage citation: parentDocId+chunkIndex let the answer↔source
      // matcher key the source, and path + start/end line let the FE deep-link to the exact lines.
      String parentDocId = fields.getOrDefault("parent_doc_id", "");
      if (!parentDocId.isEmpty()) {
        item.put("parentDocId", parentDocId);
        item.put("chunkIndex", parseIntOr(fields.get("chunk_index"), 0));
        item.put("startLine", parseIntOr(fields.get("chunk_start_line"), line));
        item.put("endLine", parseIntOr(fields.get("chunk_end_line"), line));
        String heading = fields.getOrDefault("chunk_heading_text", "");
        if (!heading.isEmpty()) {
          item.put("headingText", heading);
        }
      }
      out.add(Map.copyOf(item));
    }
    return Map.of("searchResults", List.copyOf(out), "feedbackFeatures", List.copyOf(feedback));
  }

  /**
   * Tempdoc 580 §17 P4 (Fix B) — the per-leg retrieval features for one hit, keyed by the
   * {@code parentDocId} that agent citations reference (same id-space as a search {@code hit.id()}).
   * Read from the hit's {@link SearchTrace.HitStage} scores, mirroring {@code FeatureSnapshots.capture};
   * the FUSION score falls back to the hit's overall score when the stage is absent.
   */
  private static Map<String, Object> feedbackFeatures(KnowledgeSearchResponse.Hit hit, int rank) {
    SearchTrace.LegScores legs = SearchTrace.legScores(hit.trace(), (float) hit.score());
    var f = new LinkedHashMap<String, Object>();
    f.put("docId", hit.fields().getOrDefault("parent_doc_id", hit.id()));
    f.put("rank", rank);
    f.put("sparse", legs.sparse());
    f.put("dense", legs.dense());
    f.put("splade", legs.splade());
    f.put("fused", legs.fused());
    return Map.copyOf(f);
  }

  /** Parse a stored string field to int, returning {@code fallback} when absent or malformed. */
  private static int parseIntOr(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private String formatResults(KnowledgeSearchResponse response) {
    List<KnowledgeSearchResponse.Hit> hits = response.results();
    if (hits.isEmpty()) {
      return "No results found (took " + response.tookMs() + "ms).";
    }

    // Per-result char budget: divide the total context budget evenly across all results so
    // later results are not starved by Layer 2 (AgentLoopService.truncateForContext) firing
    // after the first result has consumed all available chars.
    int maxToolResultChars = resolveMaxToolResultChars();
    int perResultBudget = Math.max(200, maxToolResultChars / hits.size());

    var sb = new StringBuilder();
    for (int i = 0; i < hits.size(); i++) {
      var hit = hits.get(i);
      var fields = hit.fields();

      String title = fields.getOrDefault("title", fields.getOrDefault("filename", "(untitled)"));
      String path = fields.getOrDefault("path", "");

      sb.append(String.format("[%d] %s (score: %.2f)%n", i + 1, title, hit.score()));
      if (!path.isEmpty()) {
        sb.append(String.format("    Path: %s%n", path));
      }

      // Include excerpt regions up to the per-result budget (backend computes up to 3 regions).
      // The 800-char per-region cap is a secondary guard for large-k queries where perResultBudget
      // itself would otherwise allow a single very large region to dominate.
      if (!hit.excerptRegions().isEmpty()) {
        int charsUsed = 0;
        for (int r = 0; r < hit.excerptRegions().size(); r++) {
          if (charsUsed >= perResultBudget) break;
          String excerpt = hit.excerptRegions().get(r).text().strip();
          if (!excerpt.isEmpty()) {
            excerpt = excerpt.replace("\"", "'").replace("\n", " ").replace("\r", "");
            int remaining = perResultBudget - charsUsed;
            // Tempdoc 561 P-A5: when bounding to the agent's per-result budget, snap to a word
            // boundary rather than a raw mid-word substring (the producer-owned-boundary principle
            // applied to the agent tool output — the same clip class fixed for ContextCitation).
            excerpt = clampToWordBoundary(excerpt, Math.min(remaining, 800));
            sb.append(String.format("    Excerpt: \"%s\"%n", excerpt));
            charsUsed += excerpt.length();
          }
        }
      } else {
        // Vector search fallback: use content_preview when no excerpt regions
        String preview = fields.getOrDefault("content_preview", "");
        if (!preview.isBlank()) {
          preview = preview.strip().replace("\"", "'").replace("\n", " ").replace("\r", "");
          // Tempdoc 561 P-A5: word-boundary snap (see clampToWordBoundary).
          preview = clampToWordBoundary(preview, Math.min(perResultBudget, 800));
          sb.append(String.format("    Preview: \"%s\"%n", preview));
        }
      }
    }

    sb.append(
        String.format(
            "%nFound %d results (took %dms).", response.totalHits(), response.tookMs()));
    // Tempdoc 549 Phase E4: read the correction from the unified trace's CORRECTION stage
    // (status=EXECUTED, detail=corrected query). SearchIntrospection was retired.
    String correctedQuery = correctedQueryFromTrace(response.searchTrace());
    if (correctedQuery != null && !correctedQuery.isBlank()) {
      sb.append(String.format(" (corrected to: \"%s\")", correctedQuery));
    }
    return sb.toString();
  }

  /** The corrected query from the unified trace's CORRECTION stage, or null when not applied. */
  private static String correctedQueryFromTrace(
      io.justsearch.app.api.knowledge.SearchTrace trace) {
    if (trace == null || trace.stages() == null) {
      return null;
    }
    for (var st : trace.stages()) {
      if (st.id() == io.justsearch.app.api.knowledge.SearchTrace.StageId.CORRECTION
          && st.status() == io.justsearch.app.api.knowledge.SearchTrace.StageStatus.EXECUTED) {
        return st.detail();
      }
    }
    return null;
  }

  /**
   * Detects file-path-like queries and converts them to keyword-friendly form. Queries containing
   * path separators (/ or \) have extensions stripped and separators replaced with spaces. This
   * prevents Lucene parse errors from slashes and improves keyword matching for path-based queries.
   */
  static String sanitizeFilePathQuery(String query) {
    if (!query.contains("/") && !query.contains("\\")) {
      return query;
    }
    // Strip common file extensions
    String cleaned =
        query.replaceAll("\\.(md|txt|json|yaml|yml|java|xml|html|proto|pdf|csv|toml)$", "");
    // Replace path separators with spaces
    cleaned = cleaned.replace('/', ' ').replace('\\', ' ');
    // Collapse multiple spaces
    cleaned = cleaned.replaceAll("\\s+", " ").trim();
    return cleaned.isEmpty() ? query : cleaned;
  }

  /**
   * Validates that path_prefix is an absolute path under one of the indexed roots. Returns null if
   * valid, or an error message string if rejected.
   */
  private String validatePathPrefix(String pathPrefix) {
    if (rootsSupplier == null) {
      return null; // No roots available — fall back to heuristic hint (existing behavior)
    }
    List<BrowseTool.RootInfo> rootInfos;
    try {
      rootInfos = rootsSupplier.get();
    } catch (Exception e) {
      LOG.warn("Failed to get roots for path validation", e);
      return null; // Degrade gracefully
    }
    if (rootInfos == null || rootInfos.isEmpty()) {
      return null; // No roots configured — allow any path
    }
    List<String> roots = rootInfos.stream().map(BrowseTool.RootInfo::path).toList();
    return AgentToolPaths.validateAgainstRoots(pathPrefix, roots, "path_prefix");
  }

  /** Callback for executing search queries against the knowledge index. */
  @FunctionalInterface
  public interface SearchCallback {
    KnowledgeSearchResponse search(KnowledgeSearchRequest request);
  }
}
