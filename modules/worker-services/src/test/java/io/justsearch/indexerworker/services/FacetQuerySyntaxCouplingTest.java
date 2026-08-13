package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.stub.StreamObserver;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §L.3 — the facet scan and the headline match count must tally the SAME parse the
 * hits came from.
 *
 * <p>The multi-leg (composable) path retrieves its BM25 leg with
 * {@code TextQueryOps.MULTI_LEG_LEXICAL_SYNTAX} (SIMPLE) regardless of the request's
 * {@code query_syntax} — see {@code TextQueryOps#searchText} / {@code #searchTextWithFilter}. So the
 * facet rebuild and {@code computeMatchCount} in {@code SearchResponseBuilder} must parse with that
 * same constant. Threading the REQUEST's syntax there instead would make a LUCENE-syntax request
 * report facets and a headline over a query it never actually ran ("Top 3 of 1 matches").
 *
 * <p>The query below parses to 3 docs under SIMPLE and 1 under LUCENE, so a parse skew shows up as a
 * number rather than a shape. Each test pins a different producer, because {@code matchCount} has
 * two sources ({@code SearchResponseBuilder:170-176}):
 *
 * <ul>
 *   <li><b>with facets</b> — {@code matchCount} binds to {@code facetsResult.matchedDocs()}, so
 *       those cases pin the FACET SCAN's parse (the {@code :243} rebuild);
 *   <li><b>without facets</b> — {@code matchCount} comes from {@code computeMatchCount}, so the
 *       no-facets case is the only one that pins the {@code :308} rebuild.
 * </ul>
 *
 * <p>Each pin is bidirectional: asserting count == hits fails if the counts start honouring the
 * request's syntax while the leg does not, AND if the leg starts honouring it while the counts
 * do not.
 */
@DisplayName("Facet/matchCount parse is coupled to the retrieval leg's parse (tempdoc 821 §L.3)")
final class FacetQuerySyntaxCouplingTest {

  /**
   * SIMPLE escapes the operators (tokens {@code shared}, {@code alpha}, default-OR) → 3 docs.
   * LUCENE parses required clauses ({@code +shared +alpha}) → 1 doc. Any divergence between the
   * hits' parse and the counts' parse is therefore visible as a number, not a shape.
   */
  private static final String DIVERGING_QUERY = "+shared +alpha";

  private static final Map<String, String> CORPUS =
      Map.of(
          "doc-a", "alpha shared term",
          "doc-b", "beta shared term",
          "doc-c", "gamma shared term");

  @Test
  @DisplayName("LUCENE-syntax multi-leg request: facets + matchCount tally the leg's SIMPLE parse")
  void luceneSyntaxMultiLegCountsMatchTheRetrievedPopulation() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      GrpcSearchService service = new GrpcSearchService(lifecycle);
      SearchResponse response =
          invokeSearch(service, hybridWithMimeFacet(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));

      // The composable path is what ran (hybrid degraded to BM25 — no embedding service).
      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());

      assertEquals(
          3L,
          response.getTotalHits(),
          "precondition: the BM25 leg retrieves with a SIMPLE parse, so all 3 docs match");
      assertEquals(
          response.getTotalHits(),
          response.getMatchCount(),
          "the headline must count the population the hits came from, not a re-parse of the"
              + " request's LUCENE syntax");
      assertNotEquals(
          1L,
          response.getMatchCount(),
          "1 would mean the headline was computed with the request's LUCENE parse while the hits"
              + " came from a SIMPLE parse");
      assertEquals(
          3L,
          response.getFacetsMap().get(SchemaFields.MIME).getCountsMap().get("application/pdf"),
          "the facet scan must tally the same 3 retrieved docs, not the LUCENE parse's 1");
      assertFalse(
          response.getFacetsTruncated(), "the tiny scan completed — truncated must stay false");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  /**
   * The facets cases above never reach {@code computeMatchCount} — with facets requested,
   * {@code matchCount} binds to the facet scan's {@code matchedDocs}. Only a facet-less multi-leg
   * request exercises the {@code computeMatchCount} rebuild, so only this test can catch a parse
   * skew there.
   */
  @Test
  @DisplayName("No-facets multi-leg LUCENE request: computeMatchCount tallies the leg's parse")
  void luceneSyntaxMultiLegWithoutFacetsCountsTheRetrievedPopulation() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      GrpcSearchService service = new GrpcSearchService(lifecycle);
      SearchRequest noFacets =
          SearchRequest.newBuilder()
              .setQuery(DIVERGING_QUERY)
              .setLimit(10)
              .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
              .setPipeline(
                  PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build())
              .build();
      SearchResponse response = invokeSearch(service, noFacets);

      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      assertTrue(
          response.getFacetsMap().isEmpty(),
          "precondition: no facets requested, so matchCount must come from computeMatchCount");
      assertEquals(
          3L,
          response.getTotalHits(),
          "precondition: the BM25 leg retrieves with a SIMPLE parse, so all 3 docs match");
      assertEquals(
          response.getTotalHits(),
          response.getMatchCount(),
          "computeMatchCount must re-parse with the leg's syntax, not the request's LUCENE syntax");
      assertNotEquals(
          1L,
          response.getMatchCount(),
          "1 would mean computeMatchCount used the request's LUCENE parse");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  @Test
  @DisplayName("SIMPLE-syntax multi-leg request is byte-identical to the LUCENE-syntax one")
  void simpleSyntaxMultiLegIsUnchanged() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try (RunningRuntime lifecycle = newLifecycleWithPdfDocs(CORPUS)) {
      GrpcSearchService service = new GrpcSearchService(lifecycle);
      SearchResponse simple =
          invokeSearch(service, hybridWithMimeFacet(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE));
      SearchResponse lucene =
          invokeSearch(service, hybridWithMimeFacet(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE));

      assertEquals(3L, simple.getTotalHits(), "the default SIMPLE path is unchanged");
      assertEquals(simple.getMatchCount(), lucene.getMatchCount(), "same counts on both requests");
      assertEquals(
          simple.getFacetsMap().get(SchemaFields.MIME).getCountsMap(),
          lucene.getFacetsMap().get(SchemaFields.MIME).getCountsMap(),
          "same facet tally on both requests — the counts follow the leg, not the request");
    } finally {
      restoreProperty("justsearch.config", prevConfig);
    }
  }

  private static SearchRequest hybridWithMimeFacet(SearchQuerySyntax syntax) {
    return SearchRequest.newBuilder()
        .setQuery(DIVERGING_QUERY)
        .setLimit(10)
        .setQuerySyntax(syntax)
        // Dense enabled with no embedding service → hybrid degrades to the composable Bm25Only leg,
        // i.e. MultiLegDecision + FacetCompute.FromFreshBm25 (the rebuild path under test).
        .setPipeline(PipelineConfig.newBuilder().setSparseEnabled(true).setDenseEnabled(true).build())
        .setFacets(
            FacetSpec.newBuilder()
                .setInclude(true)
                .addFields(FacetFieldSpec.newBuilder().setField(SchemaFields.MIME).setSize(10))
                .build())
        .build();
  }

  private static SearchResponse invokeSearch(GrpcSearchService service, SearchRequest request) {
    AtomicReference<SearchResponse> responseRef = new AtomicReference<>();
    AtomicReference<Throwable> errorRef = new AtomicReference<>();
    service.search(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(SearchResponse value) {
            responseRef.set(value);
          }

          @Override
          public void onError(Throwable t) {
            errorRef.set(t);
          }

          @Override
          public void onCompleted() {}
        });
    assertNull(errorRef.get(), () -> "search() errored: " + errorRef.get());
    SearchResponse response = responseRef.get();
    assertNotNull(response);
    return response;
  }

  private static RunningRuntime newLifecycleWithPdfDocs(Map<String, String> docs) throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    Path base = Files.createTempDirectory("justsearch-facet-syntax-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());
    RunningRuntime lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    for (var entry : docs.entrySet()) {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put(SchemaFields.DOC_ID, entry.getKey());
      fields.put(SchemaFields.DOC_UID, entry.getKey() + "#0");
      fields.put(SchemaFields.PATH, entry.getKey());
      fields.put(SchemaFields.CONTENT, entry.getValue());
      fields.put(SchemaFields.MIME, "application/pdf");
      lifecycle.indexingCoordinator().indexSingle(new IndexDocument(fields));
    }
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
