package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

class FuzzySearchIntegrationTest extends RuntimeTestBase {

  @Test
  void buildFuzzyTextQueryMatchesTypos() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: fuzzytest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "apache lucene is a search engine library")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Exact query works
    SearchResult exactResult = runtime.textQueryOps().searchText("lucene", 10, null);
    assertTrue(exactResult.hits().size() >= 1, "Exact match should find document");

    // Typo query (edit distance 1) returns 0 hits in normal SIMPLE mode
    SearchResult typoResult = runtime.textQueryOps().searchText("lucne", 10, null);
    assertEquals(0, typoResult.hits().size(), "Typo should not match in normal text search");

    // Fuzzy query (edit distance 1) DOES find the document
    var fuzzyQueryResult = runtime.textQueryOps().buildFuzzyTextQuery("lucne", null, 1);
    assertNotNull(fuzzyQueryResult, "Fuzzy query should be non-null for valid input");
    SearchResult fuzzyResult = runtime.readPathOps().search(fuzzyQueryResult.query(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertTrue(
        fuzzyResult.hits().size() >= 1,
        "Fuzzy query with edit distance 1 should match 'lucene' from typo 'lucne'");

    runtime.close();
  }

  @Test
  void buildPerTermFuzzyQueryCorrectsMissingTokens() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: pertermtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT,
                    "apache lucene search engine index library")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Mixed query: "lucene" exists, "indx" does not -> should return non-null corrected query
    var correctedResult0 = runtime.textQueryOps().buildPerTermFuzzyQuery("lucene indx", null, 1);
    assertNotNull(
        correctedResult0,
        "Per-term query should be non-null when some tokens have zero df");
    assertNotNull(correctedResult0.correctedText(), "Corrected text should be non-null");

    // All-found query: "lucene search" both exist -> should return null (no correction needed)
    var allFoundResult = runtime.textQueryOps().buildPerTermFuzzyQuery("lucene search", null, 1);
    assertNull(
        allFoundResult,
        "Per-term query should be null when all tokens are found in index");

    // Searching with the corrected query finds the document
    SearchResult correctedResult = runtime.readPathOps().search(correctedResult0.query(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertTrue(
        correctedResult.hits().size() >= 1,
        "Per-term corrected query should find doc via fuzzy 'indx' -> 'index'");

    runtime.close();
  }

  @Test
  void resolvedFuzzyQueryProducesBm25Scores() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: bm25test\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index multiple docs so BM25 IDF differentiates terms
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT,
                    "apache lucene search engine index library")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.CONTENT,
                    "the quick brown fox jumps over the lazy dog")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.CONTENT,
                    "another document without the target term")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Build normal search query for "lucene" (goes through buildSimpleContentQuery)
    Query normalQuery =
        runtime.textQueryOps().buildTextQuery("lucene", null, LuceneRuntimeTypes.QuerySyntax.SIMPLE);
    SearchResult normalResult = runtime.readPathOps().search(normalQuery, 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertTrue(normalResult.hits().size() >= 1, "Normal query should find document");
    float normalScore = normalResult.hits().get(0).score();

    // Build resolved fuzzy query for typo "lucne" -> resolves to "lucene"
    // and pipes through buildSimpleContentQuery, producing the same query
    var fuzzyQueryResult = runtime.textQueryOps().buildFuzzyTextQuery("lucne", null, 1);
    assertNotNull(fuzzyQueryResult, "Fuzzy query should resolve 'lucne' -> 'lucene'");
    assertEquals("lucene", fuzzyQueryResult.correctedText());
    SearchResult fuzzyResult = runtime.readPathOps().search(fuzzyQueryResult.query(), 10, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertTrue(fuzzyResult.hits().size() >= 1, "Resolved fuzzy query should find document");
    float fuzzyScore = fuzzyResult.hits().get(0).score();

    // Scores should be equal -- both go through buildSimpleContentQuery("lucene")
    assertEquals(
        normalScore,
        fuzzyScore,
        0.001f,
        "Resolved fuzzy score should equal normal search score "
            + "(both go through buildSimpleContentQuery)");

    runtime.close();
  }
}
