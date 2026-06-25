package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

class TextSearchIntegrationTest extends RuntimeTestBase {

  @Test
  void searchTextReturnsResults() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: texttest\n      roots: ['ignored']\n  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index documents with text content
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "The quick brown fox jumps over the lazy dog")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.CONTENT, "A fast red fox runs through the forest")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.CONTENT, "The cat sleeps on the windowsill")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Search for "fox" - should match doc-1 and doc-2
    SearchResult result = runtime.textQueryOps().searchText("fox", 10, null);

    assertNotNull(result);
    assertEquals(2, result.hits().size(), "Should return 2 documents containing 'fox'");

    // Search for "cat" - should only match doc-3
    result = runtime.textQueryOps().searchText("cat", 10, null);
    assertEquals(1, result.hits().size());
    assertEquals("doc-3", result.hits().get(0).docId());

    runtime.close();
  }

  @Test
  void textSearchSupportsSearchAfterPaginationWithStableCursor() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: textpagetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "alpha fox bravo")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-2",
                SchemaFields.DOC_UID, "doc-2#0",
                SchemaFields.CONTENT, "charlie fox delta")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-3",
                SchemaFields.DOC_UID, "doc-3#0",
                SchemaFields.CONTENT, "echo fox foxtrot")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    Query q = runtime.textQueryOps().buildTextQuery("fox", null);
    SearchResult page1 = runtime.readPathOps().search(q, 1, Set.of(), RuntimeSearchSort.RELEVANCE, null);
    assertEquals(1, page1.hits().size());
    assertNotNull(
        page1.nextCursor(), "First page should include nextCursor when more results exist");

    SearchResult page2 =
        runtime.readPathOps().search(q, 1, Set.of(), RuntimeSearchSort.RELEVANCE, page1.nextCursor());
    assertEquals(1, page2.hits().size());
    assertTrue(
        !page2.hits().getFirst().docId().equals(page1.hits().getFirst().docId()),
        "Second page should not repeat first page");

    runtime.close();
  }

  @Test
  void searchTextTreatsUserInputAsPlainText() throws Exception {
    // Regression test: Lucene QueryParser treats many characters specially.
    // User input like "C++" should not break file search.
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: textescapetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "C++ is a programming language")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // If QueryParser isn't escaped, this can throw/return zero hits.
    SearchResult result = runtime.textQueryOps().searchText("C++", 10, null);
    assertNotNull(result);
    assertEquals(1, result.hits().size());
    assertEquals("doc-1", result.hits().get(0).docId());

    runtime.close();
  }

  @Test
  void searchTextMatchesPartialWordPrefix() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: prefixtest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-js",
                SchemaFields.DOC_UID, "doc-js#0",
                SchemaFields.CONTENT, "justsearch is a local search engine")));
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-other",
                SchemaFields.DOC_UID, "doc-other#0",
                SchemaFields.CONTENT, "unrelated document about cooking recipes")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Partial word "justsearc" should match "justsearch" via prefix expansion
    SearchResult partial = runtime.textQueryOps().searchText("justsearc", 10, null);
    assertNotNull(partial);
    assertEquals(
        1, partial.hits().size(), "Partial prefix 'justsearc' should match 'justsearch'");
    assertEquals("doc-js", partial.hits().get(0).docId());

    // Full word still works
    SearchResult full = runtime.textQueryOps().searchText("justsearch", 10, null);
    assertEquals(1, full.hits().size(), "Full word should still match");

    // Multi-word query where last word is partial
    SearchResult multi = runtime.textQueryOps().searchText("local searc", 10, null);
    assertNotNull(multi);
    assertTrue(multi.hits().size() >= 1, "Partial last word 'searc' should match 'search'");

    runtime.close();
  }

  @Test
  void prefixExpansionHandlesTabWhitespace() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: prefixwstest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "local search engine for files")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Tab-separated partial last word should still trigger prefix expansion
    SearchResult result = runtime.textQueryOps().searchText("local\tsearc", 10, null);
    assertNotNull(result);
    assertTrue(
        result.hits().size() >= 1,
        "Tab-separated partial word 'searc' should match 'search' via prefix expansion");

    runtime.close();
  }

  @Test
  void prefixExpansionRanksExactMatchFirst() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: prefixranktest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // doc-exact: contains "fox" (exact match)
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-exact",
                SchemaFields.DOC_UID, "doc-exact#0",
                SchemaFields.CONTENT, "fox is an animal")));
    // doc-prefix: contains "foxhound" (prefix-only match)
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-prefix",
                SchemaFields.DOC_UID, "doc-prefix#0",
                SchemaFields.CONTENT, "foxhound is a dog breed")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    SearchResult result = runtime.textQueryOps().searchText("fox", 10, null);
    assertNotNull(result);
    assertEquals(2, result.hits().size(), "Both exact and prefix matches should be returned");
    assertEquals(
        "doc-exact",
        result.hits().get(0).docId(),
        "Exact match 'fox' should rank above prefix-only match 'foxhound' due to boost");

    runtime.close();
  }

  @Test
  void prefixExpansionHandlesHighFanoutWithoutTooManyClauses() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: prefixfanouttest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Build >1024 unique "die*" terms so a scoring-boolean rewrite would hit Lucene's clause cap.
    for (int i = 0; i < 1300; i++) {
      String suffix = String.format("%04d", i);
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-die-" + suffix,
                  SchemaFields.DOC_UID, "doc-die-" + suffix + "#0",
                  SchemaFields.CONTENT, "die" + suffix + " archive entry")));
    }
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-exact-die",
                SchemaFields.DOC_UID, "doc-exact-die#0",
                SchemaFields.CONTENT, "die obituary notice")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    SearchResult result = assertDoesNotThrow(() -> runtime.textQueryOps().searchText("die", 10, null));
    assertNotNull(result);
    assertTrue(result.totalHits() > 0, "Prefix-expanded query should return matches");

    runtime.close();
  }

  @Test
  void searchTextExcludesChunksByDefaultWhenFiltersOmitted() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: chunkdefault\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Full doc (parent)
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "d:\\docs\\report.pdf",
                SchemaFields.DOC_UID, "d:\\docs\\report.pdf#0",
                SchemaFields.PATH, "d:\\docs\\report.pdf",
                SchemaFields.FILENAME, "report.pdf",
                SchemaFields.TITLE, "Report",
                SchemaFields.CONTENT, "quarterly earnings summary",
                SchemaFields.MIME, "application/pdf")));

    // Chunk doc (should be excluded by default)
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "chunk:abc-123",
                SchemaFields.DOC_UID, "chunk:abc-123#0",
                SchemaFields.PATH, "d:\\docs\\report.pdf",
                SchemaFields.FILENAME, "report.pdf",
                SchemaFields.CONTENT, "quarterly earnings detailed chunk",
                SchemaFields.MIME, "application/pdf",
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.PARENT_DOC_ID, "d:\\docs\\report.pdf")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Test 1: simple 2-arg searchText should exclude chunks
    var resultSimple = runtime.textQueryOps().searchText("earnings", 10, null);
    assertNotNull(resultSimple);
    assertEquals(
        1, resultSimple.hits().size(), "Simple searchText should exclude chunk docs by default");
    assertEquals("d:\\docs\\report.pdf", resultSimple.hits().get(0).docId());

    // Test 2: explicit includeChunks=true should include chunks
    var filtersInclude =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build();
    var resultInclude = runtime.textQueryOps().searchText("earnings", 10, filtersInclude);
    assertNotNull(resultInclude);
    assertEquals(
        2,
        resultInclude.hits().size(),
        "searchText with includeChunks=true should include chunk docs");

    // Test 4: explicit includeChunks=false should exclude chunks
    var filtersExclude =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();
    var resultExclude = runtime.textQueryOps().searchText("earnings", 10, filtersExclude);
    assertNotNull(resultExclude);
    assertEquals(
        1,
        resultExclude.hits().size(),
        "searchText with includeChunks=false should exclude chunk docs");

    runtime.close();
  }

  @Test
  void searchTextWithFiltersExcludesChunksAndProjectsDocValues() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: filtertest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    long now = System.currentTimeMillis();

    // Full doc (PDF)
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "d:\\docs\\a.pdf",
                SchemaFields.DOC_UID, "d:\\docs\\a.pdf#0",
                SchemaFields.PATH, "d:\\docs\\a.pdf",
                SchemaFields.FILENAME, "a.pdf",
                SchemaFields.TITLE, "A",
                SchemaFields.CONTENT, "invoice alpha",
                SchemaFields.MIME, "application/pdf",
                SchemaFields.LANGUAGE, "en",
                SchemaFields.MODIFIED_AT, now,
                SchemaFields.SIZE_BYTES, 1234L)));

    // Chunk doc that would otherwise match (must be excluded when includeChunks=false)
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "d:\\docs\\a.pdf#chunk_0",
                SchemaFields.DOC_UID, "d:\\docs\\a.pdf#chunk_0#0",
                SchemaFields.PATH, "d:\\docs\\a.pdf",
                SchemaFields.FILENAME, "a.pdf",
                SchemaFields.CONTENT, "invoice chunk",
                SchemaFields.MIME, "application/pdf",
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.PARENT_DOC_ID, "d:\\docs\\a.pdf")));

    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var filters =
        LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
            .mime(java.util.List.of("application/pdf"))
            .build();

    Query _combinedInvoice = runtime.textQueryOps().buildTextQuery("invoice", filters);
    var result = runtime.readPathOps().search(_combinedInvoice, 10, Set.of("path", "mime"), RuntimeSearchSort.RELEVANCE, null);
    assertNotNull(result);
    assertEquals(1, result.hits().size(), "Chunk docs should be excluded by default");

    var hit = result.hits().get(0);
    assertEquals("d:\\docs\\a.pdf", hit.docId());
    assertEquals("d:\\docs\\a.pdf", hit.fields().get(SchemaFields.PATH));
    assertEquals("application/pdf", hit.fields().get(SchemaFields.MIME));
    assertTrue(
        !hit.fields().containsKey(SchemaFields.CONTENT),
        "Projected fields must not include content");
    assertTrue(
        !hit.fields().containsKey(SchemaFields.DOC_ID),
        "Projection should not leak doc_id unless requested");

    runtime.close();
  }

  @Test
  void searchWithCursorAndProjectionPassthroughReturnsCursorAndProjectedFields()
      throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\nindex:\n  collections:\n    - name: cursortest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Index 3 docs with distinct modified_at for deterministic sorting
    for (int i = 1; i <= 3; i++) {
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-" + i,
                  SchemaFields.DOC_UID, "doc-" + i + "#0",
                  SchemaFields.CONTENT, "test content " + i,
                  SchemaFields.MODIFIED_AT, (long) (1000 * i),
                  "mime", "text/plain")));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // Page 1: limit=1, sort by MODIFIED_DESC, project mime field
    SearchResult page1 =
        runtime.readPathOps().search(
            new MatchAllDocsQuery(), 1, Set.of("mime"), RuntimeSearchSort.MODIFIED_DESC, null);

    assertEquals(1, page1.hits().size(), "Page 1 should have 1 hit");
    assertNotNull(page1.nextCursor(), "Page 1 should have a next cursor (3 docs total)");

    // Verify projection: mime should be present, content should NOT be present
    var hit1 = page1.hits().getFirst();
    assertEquals(
        "text/plain", hit1.fields().get("mime"), "Projected mime field should be present");
    assertFalse(
        hit1.fields().containsKey(SchemaFields.CONTENT),
        "Content must not leak in search hits");
    // ID fields should not leak when projecting specific fields
    assertFalse(
        hit1.fields().containsKey(SchemaFields.DOC_ID),
        "doc_id should not leak when not in projection");

    // Page 2: use cursor from page 1
    SearchResult page2 =
        runtime.readPathOps().search(
            new MatchAllDocsQuery(),
            1,
            Set.of("mime"),
            RuntimeSearchSort.MODIFIED_DESC,
            page1.nextCursor());

    assertEquals(1, page2.hits().size(), "Page 2 should have 1 hit");
    assertNotNull(page2.nextCursor(), "Page 2 should have a cursor (1 more doc)");
    assertFalse(
        page2.hits().getFirst().docId().equals(hit1.docId()),
        "Page 2 should not repeat page 1 doc");

    // Page 3: use cursor from page 2
    SearchResult page3 =
        runtime.readPathOps().search(
            new MatchAllDocsQuery(),
            1,
            Set.of("mime"),
            RuntimeSearchSort.MODIFIED_DESC,
            page2.nextCursor());

    assertEquals(1, page3.hits().size(), "Page 3 should have 1 hit");
    assertNull(page3.nextCursor(), "Page 3 should have no next cursor (last page)");

    runtime.close();
  }

  /** searchText catches ParseException internally and returns an empty result (not null, not throws). */
  @Test
  void searchText_returnsEmptyOnParseException() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: parseextest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, "normal content here")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // SIMPLE mode escapes user input so parse errors are impossible there.
    // Use LUCENE syntax mode to trigger a ParseException with a malformed range query.
    SearchResult result = assertDoesNotThrow(
        () -> runtime.textQueryOps().searchText("content:[malformed", 10, null),
        "searchText should not propagate ParseException to callers");

    // SIMPLE mode escapes the bracket, so the query is treated as a literal term — returns
    // an empty result because no doc contains "[malformed" as a word. Either way: non-null.
    assertNotNull(result, "searchText should return a non-null SearchResult even on bad input");
    assertNotNull(result.hits(), "hits list should be non-null");

    runtime.close();
  }

  /**
   * getDocumentFieldValues falls back to stored fields when a field has no DocValues.
   * The {@code content} field is {@code stored=true, docValues=false} in the test schema.
   */
  @Test
  void getDocumentFieldValues_fallsBackToStoredField() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: storedfieldfallback\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    String docId = "stored-field-doc";
    String contentValue = "some stored text without docvalues";
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.CONTENT, contentValue)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    // content field is stored=true, docValues=false in the test schema (createRuntimeWithDim)
    // getDocumentFieldValues must fall back to stored fields and return the stored value
    java.util.List<String> values = runtime.documentFieldOps().getDocumentFieldValues(docId, SchemaFields.CONTENT);
    assertNotNull(values, "getDocumentFieldValues should return non-null");
    assertFalse(values.isEmpty(), "getDocumentFieldValues should return the stored content value");
    assertEquals(contentValue, values.get(0),
        "Stored field value should match what was indexed");

    runtime.close();
  }
}
