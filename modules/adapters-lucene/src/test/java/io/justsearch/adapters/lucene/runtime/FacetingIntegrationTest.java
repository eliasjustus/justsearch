package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FacetingIntegrationTest extends RuntimeTestBase {

  @Test
  void computeFacetsCountsDocValuesAndCanTruncate() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: facettest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-pdf",
                SchemaFields.DOC_UID, "doc-pdf#0",
                SchemaFields.PATH, "doc-pdf",
                SchemaFields.FILENAME, "doc-pdf",
                SchemaFields.CONTENT, "invoice",
                SchemaFields.MIME, "application/pdf")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-md",
                SchemaFields.DOC_UID, "doc-md#0",
                SchemaFields.PATH, "doc-md",
                SchemaFields.FILENAME, "doc-md",
                SchemaFields.CONTENT, "invoice",
                SchemaFields.MIME, "text/markdown")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var q =
        runtime.textQueryOps().buildTextQuery(
            "invoice",
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build());
    var facets = runtime.facetingEngine().computeFacets(q, Map.of("mime", 10), 0);
    assertNotNull(facets);
    assertTrue(!facets.truncated(), "Default cap should not truncate tiny test index");
    assertEquals(1L, facets.facets().get("mime").get("application/pdf"));
    assertEquals(1L, facets.facets().get("mime").get("text/markdown"));

    var truncated = runtime.facetingEngine().computeFacets(q, Map.of("mime", 10), 1);
    assertTrue(truncated.truncated(), "Cap=1 should truncate when >=2 docs match");

    runtime.close();
  }

  @Test
  void computeFacetsRewritesMultiTermQueriesFromFilters() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: facetrewritetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-a",
                SchemaFields.DOC_UID, "doc-a#0",
                SchemaFields.PATH, "d:\\\\docs\\\\doc-a",
                SchemaFields.FILENAME, "doc-a",
                SchemaFields.CONTENT, "invoice",
                SchemaFields.MIME, "application/pdf")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Use a pathPrefix that yields zero hits, but still injects a PrefixQuery (MultiTermQuery)
    // into the query.
    var q =
        runtime.textQueryOps().buildTextQuery(
            "invoice",
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
                .pathPrefix("z:\\\\__nope__\\\\")
                .includeChunks(true)
                .build());
    var facets = runtime.facetingEngine().computeFacets(q, Map.of("mime", 10), 0);
    assertNotNull(facets);
    assertTrue(!facets.truncated());
    assertTrue(facets.facets().containsKey("mime"));

    runtime.close();
  }

  @Test
  void computeFacetsHandlesMultiValuedFields() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: mvfacettest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDimAndMultiValued(4);

    // doc1 has persons [Alice, Bob]
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.PATH, "doc-1",
                SchemaFields.FILENAME, "doc-1",
                SchemaFields.CONTENT, "report",
                SchemaFields.ENTITY_PERSONS_RAW, List.of("Alice", "Bob"))));
    // doc2 has persons [Bob, Carol]
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.PATH, "doc-2",
                SchemaFields.FILENAME, "doc-2",
                SchemaFields.CONTENT, "report",
                SchemaFields.ENTITY_PERSONS_RAW, List.of("Bob", "Carol"))));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var q =
        runtime.textQueryOps().buildTextQuery(
            "report",
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build());
    var facets =
        runtime.facetingEngine().computeFacets(q, Map.of(SchemaFields.ENTITY_PERSONS_RAW, 10), 0);
    assertNotNull(facets);
    assertEquals(1L, facets.facets().get(SchemaFields.ENTITY_PERSONS_RAW).get("Alice"));
    assertEquals(2L, facets.facets().get(SchemaFields.ENTITY_PERSONS_RAW).get("Bob"));
    assertEquals(1L, facets.facets().get(SchemaFields.ENTITY_PERSONS_RAW).get("Carol"));

    runtime.close();
  }

  @Test
  void computeFacetsMixesSingleAndMultiValuedFields() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: mixfacettest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDimAndMultiValued(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.PATH, "doc-1",
                SchemaFields.FILENAME, "doc-1",
                SchemaFields.CONTENT, "report",
                SchemaFields.MIME, "application/pdf",
                SchemaFields.ENTITY_PERSONS_RAW, List.of("Alice", "Bob"))));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.PATH, "doc-2",
                SchemaFields.FILENAME, "doc-2",
                SchemaFields.CONTENT, "report",
                SchemaFields.MIME, "application/pdf",
                SchemaFields.ENTITY_PERSONS_RAW, List.of("Bob"))));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var q =
        runtime.textQueryOps().buildTextQuery(
            "report",
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build());
    // Request both single-valued (mime) and multi-valued (entity_persons_raw) facets
    var facets =
        runtime.facetingEngine().computeFacets(
            q, Map.of(SchemaFields.MIME, 10, SchemaFields.ENTITY_PERSONS_RAW, 10), 0);
    assertNotNull(facets);
    // Single-valued: both docs have same mime
    assertEquals(2L, facets.facets().get(SchemaFields.MIME).get("application/pdf"));
    // Multi-valued: Alice in 1 doc, Bob in 2 docs
    assertEquals(1L, facets.facets().get(SchemaFields.ENTITY_PERSONS_RAW).get("Alice"));
    assertEquals(2L, facets.facets().get(SchemaFields.ENTITY_PERSONS_RAW).get("Bob"));

    runtime.close();
  }
}
