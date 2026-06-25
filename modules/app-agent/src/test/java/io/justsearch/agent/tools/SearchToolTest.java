package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchToolTest {

  private AtomicReference<KnowledgeSearchRequest> capturedRequest;
  private KnowledgeSearchResponse stubbedResponse;
  private SearchTool tool;

  @BeforeEach
  void setUp() {
    capturedRequest = new AtomicReference<>();
    stubbedResponse = emptyResponse();
    tool =
        new SearchTool(
            req -> {
              capturedRequest.set(req);
              return stubbedResponse;
            });
  }

  @Test
  void parameterSchemaPresent() {
    // Per Phase 12 of tempdoc 429: name/description/safetyLevel/supportsUndo moved to
    // the AgentToolsOperationCatalog Operation declaration.
    assertNotNull(SearchTool.parameterSchema());
  }

  @Test
  void executeWithValidQuery() {
    stubbedResponse = responseWithHits(1);

    OperationResult result = tool.execute("{\"query\": \"test documents\"}");

    assertTrue(result.success(), result.message());
    assertNotNull(capturedRequest.get());
    assertEquals("test documents", capturedRequest.get().query());
    assertTrue(result.message().contains("[1]"));
    assertTrue(result.message().contains("Found 1 results"));
  }

  @Test
  void executeMissingQueryReturnsFailure() {
    OperationResult result = tool.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("required"), result.message());
  }

  @Test
  void executeEmptyQueryReturnsFailure() {
    OperationResult result = tool.execute("{\"query\": \"\"}");
    assertFalse(result.success());
    assertTrue(result.message().contains("required"), result.message());
  }

  @Test
  void executeWithLimitAndMode() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"find me\", \"limit\": 5, \"mode\": \"hybrid\"}");

    var req = capturedRequest.get();
    assertNotNull(req);
    assertEquals(5, req.limit());
    // 256-G2: mode is translated to pipeline, mode field is null
    assertNull(req.mode());
    assertNotNull(req.pipeline());
    assertTrue(req.pipeline().sparseEnabled());
    assertTrue(req.pipeline().denseEnabled());
  }

  @Test
  void executeDefaultModeFromConfig() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"test query\"}");

    var req = capturedRequest.get();
    assertNotNull(req);
    // 256-G2: mode is translated to pipeline; null mode → HYBRID preset
    assertNull(req.mode());
    assertNotNull(req.pipeline());
    assertTrue(req.pipeline().sparseEnabled(), "HYBRID preset has sparse enabled");
    assertTrue(req.pipeline().denseEnabled(), "HYBRID preset has dense enabled");
  }

  @Test
  void executeLimitCappedAtMax() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"find me\", \"limit\": 100}");

    var req = capturedRequest.get();
    assertNotNull(req);
    assertEquals(20, req.limit(), "Limit should be capped at MAX_LIMIT=20");
  }

  @Test
  void executeWithPathPrefix() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"invoices\", \"path_prefix\": \"/docs/finance\"}");

    var req = capturedRequest.get();
    assertNotNull(req);
    assertNotNull(req.filters());
    assertEquals("/docs/finance", req.filters().pathPrefix());
  }

  @Test
  void executeCallbackError() {
    tool =
        new SearchTool(
            req -> {
              throw new RuntimeException("Connection refused");
            });

    OperationResult result = tool.execute("{\"query\": \"test\"}");
    assertFalse(result.success());
    assertTrue(result.message().contains("Connection refused"), result.message());
  }

  @Test
  void executeFormatsMultipleResults() {
    stubbedResponse = responseWithHits(3);

    OperationResult result = tool.execute("{\"query\": \"reports\"}");

    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("[1]"));
    assertTrue(result.message().contains("[2]"));
    assertTrue(result.message().contains("[3]"));
    assertTrue(result.message().contains("Found 3 results"));
    assertTrue(result.message().contains("Path:"));
  }

  @Test
  void executeNoResults() {
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"nonexistent\"}");

    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("No results found"), result.message());
  }

  @Test
  void executeNullArgumentsReturnsFailure() {
    OperationResult result = tool.execute(null);
    assertFalse(result.success());
    assertTrue(result.message().contains("No arguments"), result.message());
  }

  @Test
  void executeMalformedJsonReturnsFailure() {
    OperationResult result = tool.execute("not json {{{");
    assertFalse(result.success());
    assertTrue(result.message().contains("error") || result.message().contains("Search error"),
        result.message());
  }

  @Test
  void executeNullResponseReturnsFailure() {
    tool = new SearchTool(req -> null);

    OperationResult result = tool.execute("{\"query\": \"test\"}");
    assertFalse(result.success());
    assertTrue(result.message().contains("no response"), result.message());
  }

  @Test
  void executeFormatsExcerptsWithSpecialChars() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of("title", "Test Doc", "path", "/test.pdf"))
                    .excerptRegions(List.of(
                        new KnowledgeSearchResponse.ExcerptRegion(
                            "He said \"hello\" and\nnewline here", 0, 30, 1, List.of())))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"test\"}");
    assertTrue(result.success(), result.message());
    // Quotes should be replaced with apostrophes, newlines with spaces
    assertFalse(result.message().contains("\"hello\""), "Quotes should be sanitized");
    assertTrue(result.message().contains("'hello'"), "Quotes should become apostrophes");
    assertFalse(result.message().contains("and\nnewline"), "Newlines within excerpt should be replaced");
  }

  @Test
  void emitsStructuredSearchEvidenceWithoutAFabricatedScore() {
    // Tempdoc 561 #6: the tool result carries structured evidence (title/path/excerpt/line) for the
    // tool card — and deliberately NO score (the ranking score is uncalibrated; 559 §5 / §18 C-6).
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of("title", "Tax Notes", "path", "/docs/taxes.md"))
                    .excerptRegions(List.of(
                        new KnowledgeSearchResponse.ExcerptRegion(
                            "deductible limits for the year", 0, 30, 42, List.of())))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"taxes\"}");
    assertTrue(result.success(), result.message());

    Object raw = result.structuredData().get("searchResults");
    assertInstanceOf(List.class, raw, "structuredData carries a searchResults list");
    List<?> items = (List<?>) raw;
    assertEquals(1, items.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) items.get(0);
    assertEquals("Tax Notes", item.get("title"));
    assertEquals("/docs/taxes.md", item.get("path"));
    assertEquals(42, item.get("line"));
    assertTrue(((String) item.get("excerpt")).contains("deductible"), "excerpt carried");
    // Honesty floor: no relevance score is emitted (would fabricate calibration from a ranking score).
    assertFalse(item.containsKey("score"), "no uncalibrated relevance score is surfaced");
  }

  @Test
  void executeShowsQueryCorrection() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9).fields(Map.of("title", "Result")).build()))
            // Tempdoc 549 Phase E4: correction is carried by the unified trace's CORRECTION stage
            // (status=EXECUTED, detail=corrected query). SearchIntrospection was retired.
            .searchTrace(
                new io.justsearch.app.api.knowledge.SearchTrace(
                    1, null, null, null, null,
                    List.of(
                        new io.justsearch.app.api.knowledge.SearchTrace.TraceStage(
                            io.justsearch.app.api.knowledge.SearchTrace.StageId.CORRECTION,
                            io.justsearch.app.api.knowledge.SearchTrace.StageStatus.EXECUTED,
                            null, null, "corrected query", null))))
            .build();

    OperationResult result = tool.execute("{\"query\": \"test\"}");
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("corrected to"), result.message());
    assertTrue(result.message().contains("corrected query"), result.message());
  }

  @Test
  void schemaIsValidJson() {
    assertDoesNotThrow(
        () -> new tools.jackson.databind.ObjectMapper()
            .readTree(SearchTool.parameterSchema()));
  }

  // ---------------------------------------------------------------------------
  // File-path query sanitization
  // ---------------------------------------------------------------------------

  @Test
  void sanitizeFilePathQuery_convertsPathToKeywords() {
    assertEquals(
        "docs reference configuration environment-variables",
        SearchTool.sanitizeFilePathQuery("docs/reference/configuration/environment-variables.md"));
  }

  @Test
  void sanitizeFilePathQuery_preservesNonPathQuery() {
    assertEquals("inference model", SearchTool.sanitizeFilePathQuery("inference model"));
  }

  @Test
  void sanitizeFilePathQuery_handlesBackslashes() {
    assertEquals(
        "docs explanation 01-system-overview",
        SearchTool.sanitizeFilePathQuery("docs\\explanation\\01-system-overview.md"));
  }

  @Test
  void sanitizeFilePathQuery_passesQueryUsedInSearch() {
    stubbedResponse = emptyResponse();

    tool.execute(
        "{\"query\": \"docs/reference/configuration/environment-variables.md\"}");

    var req = capturedRequest.get();
    assertNotNull(req);
    assertEquals(
        "docs reference configuration environment-variables",
        req.query(),
        "File-path query should be sanitized to keywords");
  }

  @Test
  void relativePathPrefix_emptyResults_showsHint() {
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"test\", \"path_prefix\": \"docs/how-to\"}");

    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("HINT"), "Should contain HINT: " + result.message());
    assertTrue(
        result.message().contains("relative"),
        "Should mention relative: " + result.message());
    assertTrue(
        result.message().contains("docs/how-to"),
        "Should echo the path_prefix: " + result.message());
  }

  @Test
  void absolutePathPrefix_emptyResults_noHint() {
    stubbedResponse = emptyResponse();

    OperationResult result =
        tool.execute("{\"query\": \"test\", \"path_prefix\": \"D:\\\\Documents\\\\stuff\"}");

    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("No results found"),
        "Should report no results: " + result.message());
    assertFalse(result.message().contains("HINT"), "Should NOT contain HINT: " + result.message());
  }

  // ---------------------------------------------------------------------------
  // Path prefix validation against roots
  // ---------------------------------------------------------------------------

  @Test
  void pathPrefix_relativePathResolved_whenRootMatches() {
    stubbedResponse = emptyResponse();
    var toolWithRoots =
        new SearchTool(
            req -> {
              capturedRequest.set(req);
              return stubbedResponse;
            },
            () ->
                List.of(
                    new BrowseTool.RootInfo("D:\\docs", "docs"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"docs/how-to\"}");

    assertTrue(result.success(), "Relative path matching root should resolve: " + result.message());
    assertNotNull(capturedRequest.get(), "Search should have been executed");
    assertEquals(
        "D:\\docs\\how-to",
        capturedRequest.get().filters().pathPrefix(),
        "Relative path should resolve to absolute");
  }

  @Test
  void pathPrefix_relativePathNoMatch_rejected() {
    var toolWithRoots =
        new SearchTool(
            req -> stubbedResponse,
            () ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"unknown/how-to\"}");

    assertFalse(result.success(), "Relative path not matching any root should be rejected: " + result.message());
    assertTrue(result.message().contains("not an absolute path"), result.message());
  }

  @Test
  void pathPrefix_unixSlashRejected_whenRootsAvailable() {
    var toolWithRoots =
        new SearchTool(
            req -> stubbedResponse,
            () -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"/how-to\"}");

    assertFalse(result.success(), "Unix-style /path should be rejected on Windows: " + result.message());
    assertTrue(result.message().contains("not an absolute path"), result.message());
  }

  @Test
  void pathPrefix_validRootedPath_accepted() {
    stubbedResponse = emptyResponse();
    var toolWithRoots =
        new SearchTool(
            req -> {
              capturedRequest.set(req);
              return stubbedResponse;
            },
            () ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result =
        toolWithRoots.execute(
            "{\"query\": \"test\", \"path_prefix\": \"D:\\\\Documents\\\\how-to\"}");

    assertTrue(result.success(), "Valid rooted path should be accepted: " + result.message());
    assertNotNull(capturedRequest.get(), "Search should have been executed");
  }

  @Test
  void pathPrefix_absoluteButOutOfRoots_rejected() {
    var toolWithRoots =
        new SearchTool(
            req -> stubbedResponse,
            () -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result =
        toolWithRoots.execute(
            "{\"query\": \"test\", \"path_prefix\": \"C:\\\\other\\\\path\"}");

    assertFalse(result.success(), "Out-of-root path should be rejected: " + result.message());
    assertTrue(result.message().contains("not under any indexed root"), result.message());
    assertTrue(result.message().contains("D:\\Documents"), result.message());
  }

  @Test
  void pathPrefix_nullOrEmpty_acceptedWithRoots() {
    stubbedResponse = emptyResponse();
    var toolWithRoots =
        new SearchTool(
            req -> {
              capturedRequest.set(req);
              return stubbedResponse;
            },
            () -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result = toolWithRoots.execute("{\"query\": \"test\"}");
    assertTrue(result.success(), "No path_prefix should be accepted: " + result.message());
    assertNotNull(capturedRequest.get());
  }

  @Test
  void pathPrefix_noRootsSupplier_fallsBackToHeuristic() {
    // Tool without roots supplier should still work (original behavior)
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"test\", \"path_prefix\": \"/how-to\"}");
    assertTrue(result.success(), "Without roots, all paths should be allowed: " + result.message());
  }

  @Test
  void executeFormatsContentPreviewFallback_whenNoExcerpts() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of(
                        "title", "Vector Result",
                        "path", "/test.md",
                        "content_preview",
                            "This is a long preview of the document content for vector search"))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"test\"}");
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("Preview:"), "Should show content_preview fallback");
    assertTrue(result.message().contains("long preview"), result.message());
  }

  @Test
  void executeEnforcesPerResultCharBudget() {
    // Set small total budget so per-result budget = max(200, 600/3) = 200 chars
    System.setProperty("justsearch.agent.max_tool_result_chars", "600");
    ConfigStore prev = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeFromEnvironment();
    try {
      String longText = "A".repeat(500);
      stubbedResponse =
          KnowledgeSearchResponseBuilder.builder()
              .totalHits(3)
              .tookMs(12)
              .results(List.of(
                  KnowledgeSearchResponseHitBuilder.builder()
                      .id("d1").score(0.9).fields(Map.of("title", "Doc 1", "path", "/d1.pdf"))
                      .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                      .build(),
                  KnowledgeSearchResponseHitBuilder.builder()
                      .id("d2").score(0.8).fields(Map.of("title", "Doc 2", "path", "/d2.pdf"))
                      .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                      .build(),
                  KnowledgeSearchResponseHitBuilder.builder()
                      .id("d3").score(0.7).fields(Map.of("title", "Doc 3", "path", "/d3.pdf"))
                      .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                      .build()))
              .build();

      OperationResult result = tool.execute("{\"query\": \"test\"}");

      assertTrue(result.success(), result.message());
      String output = result.message();
      // All 3 results present (not starved by earlier results)
      assertTrue(output.contains("[1]"));
      assertTrue(output.contains("[2]"));
      assertTrue(output.contains("[3]"));
      // Excerpts are truncated (500 chars > 200 budget)
      assertTrue(output.contains("..."), "Excerpts should be truncated by budget");
      // The full 500-char text should NOT appear for any single result
      assertFalse(output.contains(longText), "Full excerpt should be truncated by per-result budget");
    } finally {
      System.clearProperty("justsearch.agent.max_tool_result_chars");
      TestResolvedConfigHelper.restoreGlobal(prev);
    }
  }

  // ---------------------------------------------------------------------------
  // modeToPreset() unit tests (256: Phase G2)
  // ---------------------------------------------------------------------------

  @Test
  void modeToPreset_text() {
    assertEquals(PipelineConfig.TEXT, SearchTool.modeToPreset("text"));
  }

  @Test
  void modeToPreset_vector() {
    assertEquals(PipelineConfig.VECTOR, SearchTool.modeToPreset("vector"));
  }

  @Test
  void modeToPreset_hybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("hybrid"));
  }

  @Test
  void modeToPreset_null_defaultsToHybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset(null));
  }

  @Test
  void modeToPreset_blank_defaultsToHybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset(""));
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("   "));
  }

  @Test
  void modeToPreset_unknown_defaultsToHybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("unknown"));
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("splade"));
  }

  @Test
  void modeToPreset_caseInsensitive() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("HYBRID"));
    assertEquals(PipelineConfig.TEXT, SearchTool.modeToPreset("Text"));
    assertEquals(PipelineConfig.VECTOR, SearchTool.modeToPreset("VECTOR"));
  }

  // ---------------------------------------------------------------------------
  // parsePipelineArg() unit tests (256: Phase H1)
  // ---------------------------------------------------------------------------

  private static final tools.jackson.databind.ObjectMapper OM =
      new tools.jackson.databind.ObjectMapper();

  @Test
  void parsePipelineArg_emptyObject_allDefaults() throws Exception {
    var node = OM.readTree("{}");
    PipelineConfig cfg = SearchTool.parsePipelineArg(node);
    assertFalse(cfg.sparseEnabled());
    assertFalse(cfg.denseEnabled());
    assertFalse(cfg.spladeEnabled());
    assertEquals("none", cfg.fusionAlgorithm());
    assertFalse(cfg.lambdamartEnabled());
    assertFalse(cfg.crossEncoderEnabled());
    assertEquals(0, cfg.crossEncoderWindow());
    assertFalse(cfg.expansionEnabled());
  }

  @Test
  void parsePipelineArg_fullObject() throws Exception {
    var node = OM.readTree("""
        {"sparseEnabled":true,"denseEnabled":true,"spladeEnabled":true,
         "fusionAlgorithm":"rrf","lambdamartEnabled":true,
         "crossEncoderEnabled":true,"crossEncoderWindow":10,
         "expansionEnabled":true}""");
    PipelineConfig cfg = SearchTool.parsePipelineArg(node);
    assertTrue(cfg.sparseEnabled());
    assertTrue(cfg.denseEnabled());
    assertTrue(cfg.spladeEnabled());
    assertEquals("rrf", cfg.fusionAlgorithm());
    assertTrue(cfg.lambdamartEnabled());
    assertTrue(cfg.crossEncoderEnabled());
    assertEquals(10, cfg.crossEncoderWindow());
    assertTrue(cfg.expansionEnabled());
  }

  @Test
  void parsePipelineArg_partialObject_onlySparseEnabled() throws Exception {
    var node = OM.readTree("{\"sparseEnabled\":true}");
    PipelineConfig cfg = SearchTool.parsePipelineArg(node);
    assertTrue(cfg.sparseEnabled());
    assertFalse(cfg.denseEnabled());
    assertFalse(cfg.lambdamartEnabled());
    assertFalse(cfg.expansionEnabled());
    assertEquals("none", cfg.fusionAlgorithm());
  }

  // ===== Helpers =====

  private static KnowledgeSearchResponse emptyResponse() {
    return KnowledgeSearchResponseBuilder.builder().tookMs(5).build();
  }

  private static KnowledgeSearchResponse responseWithHits(int count) {
    List<KnowledgeSearchResponse.Hit> hits =
        java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(
                i ->
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("doc-" + i)
                        .score(1.0 - (i * 0.1))
                        .fields(Map.of("title", "Document " + i, "path", "/docs/doc-" + i + ".pdf"))
                        .matchedFields(List.of("content"))
                        .excerptRegions(List.of(
                            new KnowledgeSearchResponse.ExcerptRegion(
                                "Matching excerpt for document " + i, 0, 30, 1, List.of())))
                        .build())
            .toList();
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(count)
        .tookMs(12)
        .results(hits)
        .build();
  }
}
