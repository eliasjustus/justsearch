package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.ScorerSupplier;
import org.apache.lucene.search.Weight;
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

  /**
   * Tempdoc 821 §L.3: key presence is the honest signal for "can this field facet at all?". A
   * requested field the schema cannot facet ({@code title} is a {@code text} field with no
   * DocValues) must be ABSENT from the result, while a facetable field that simply matched nothing
   * stays PRESENT — the two used to be indistinguishable (both an empty map), so a client could not
   * tell "not facetable" from "zero matches".
   */
  @Test
  void nonFacetableFieldIsOmittedWhileFacetableZeroMatchFieldStaysPresent() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: facetkeytest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    // Indexed WITHOUT a mime value, so `mime` is facetable but tallies nothing.
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-a",
                SchemaFields.DOC_UID, "doc-a#0",
                SchemaFields.PATH, "doc-a",
                SchemaFields.FILENAME, "doc-a",
                SchemaFields.CONTENT, "invoice")));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var q =
        runtime.textQueryOps().buildTextQuery(
            "invoice",
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build());

    var facets =
        runtime.facetingEngine().computeFacets(
            q, Map.of(SchemaFields.MIME, 10, SchemaFields.TITLE, 10, "not_a_field_at_all", 10), 0);
    assertNotNull(facets);

    assertTrue(
        facets.facets().containsKey(SchemaFields.MIME),
        "facetable field must keep its key even with zero tallied values");
    assertTrue(
        facets.facets().get(SchemaFields.MIME).isEmpty(),
        "no doc carried a mime value, so the present key holds zero counts");
    assertFalse(
        facets.facets().containsKey(SchemaFields.TITLE),
        "a text/no-docValues field cannot facet — its key must be ABSENT, not an empty map");
    assertFalse(
        facets.facets().containsKey("not_a_field_at_all"),
        "an unknown field cannot facet — its key must be ABSENT, not an empty map");

    // The scan still ran: matchedDocs is the true matched population regardless of which requested
    // fields were facetable (it is what the response headline binds to).
    assertEquals(1L, facets.matchedDocs(), "the scan runs even when a requested field cannot facet");
    assertTrue(!facets.truncated(), "a completed scan is not truncated");

    runtime.close();
  }

  /**
   * Tempdoc 821 §L.3: a scan that FAILS mid-flight must not report {@code truncated=false} — that
   * asserts "these counts are complete" about a partial tally. The honest shape is the counts
   * accumulated so far, flagged truncated. The failure is injected by a query whose per-segment
   * iterator throws {@link IOException} after the first matched doc.
   */
  @Test
  void failedScanReturnsPartialCountsMarkedTruncated() throws Exception {
    Path base = dataDir();
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: facetiotest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = writeConfig(yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var runtime = createRuntimeWithDim(4);

    for (String id : List.of("doc-1", "doc-2", "doc-3")) {
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, id,
                  SchemaFields.DOC_UID, id + "#0",
                  SchemaFields.PATH, id,
                  SchemaFields.FILENAME, id,
                  SchemaFields.CONTENT, "invoice",
                  SchemaFields.MIME, "application/pdf")));
    }
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();

    var healthy =
        runtime.textQueryOps().buildTextQuery(
            "invoice",
            LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().includeChunks(true).build());
    var complete = runtime.facetingEngine().computeFacets(healthy, Map.of(SchemaFields.MIME, 10), 0);
    assertEquals(3L, complete.matchedDocs(), "control: all three docs match");
    assertTrue(!complete.truncated(), "control: a completed scan reports truncated=false");

    // Same query, but the scan dies after one matched doc.
    var failing =
        runtime.facetingEngine().computeFacets(new FailAfterQuery(healthy, 1), Map.of(SchemaFields.MIME, 10), 0);
    assertNotNull(failing);
    assertTrue(
        failing.truncated(),
        "a scan that threw IOException must NOT report truncated=false — the counts are partial");
    assertTrue(
        failing.matchedDocs() < complete.matchedDocs(),
        "the failed scan saw fewer docs than the complete one (" + failing.matchedDocs() + ")");
    assertEquals(
        1L,
        failing.facets().get(SchemaFields.MIME).get("application/pdf"),
        "the partial tally accumulated before the failure is still returned");

    runtime.close();
  }

  /** Wraps a query so the first {@code failAfter} matched docs succeed and the next read throws. */
  private static final class FailAfterQuery extends Query {
    private final Query in;
    private final int failAfter;

    FailAfterQuery(Query in, int failAfter) {
      this.in = in;
      this.failAfter = failAfter;
    }

    @Override
    public Weight createWeight(IndexSearcher searcher, ScoreMode scoreMode, float boost)
        throws IOException {
      Weight delegate = searcher.createWeight(searcher.rewrite(in), scoreMode, boost);
      return new Weight(this) {
        @Override
        public Explanation explain(LeafReaderContext context, int doc) throws IOException {
          return delegate.explain(context, doc);
        }

        @Override
        public ScorerSupplier scorerSupplier(LeafReaderContext context) throws IOException {
          ScorerSupplier inner = delegate.scorerSupplier(context);
          if (inner == null) return null;
          return new ScorerSupplier() {
            @Override
            public Scorer get(long leadCost) throws IOException {
              return new FailAfterScorer(inner.get(leadCost), failAfter);
            }

            @Override
            public long cost() {
              return inner.cost();
            }
          };
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return false;
        }
      };
    }

    @Override
    public void visit(QueryVisitor visitor) {
      in.visit(visitor);
    }

    @Override
    public String toString(String field) {
      return "FailAfter(" + in.toString(field) + ", " + failAfter + ")";
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof FailAfterQuery other
          && failAfter == other.failAfter
          && in.equals(other.in);
    }

    @Override
    public int hashCode() {
      return in.hashCode() * 31 + failAfter;
    }
  }

  private static final class FailAfterScorer extends Scorer {
    private final Scorer in;
    private final DocIdSetIterator it;

    FailAfterScorer(Scorer in, int failAfter) {
      this.in = in;
      DocIdSetIterator inner = in.iterator();
      this.it =
          new DocIdSetIterator() {
            private int served = 0;

            @Override
            public int docID() {
              return inner.docID();
            }

            @Override
            public int nextDoc() throws IOException {
              if (served >= failAfter) {
                throw new IOException("simulated segment read failure during facet scan");
              }
              served++;
              return inner.nextDoc();
            }

            @Override
            public int advance(int target) throws IOException {
              if (served >= failAfter) {
                throw new IOException("simulated segment read failure during facet scan");
              }
              served++;
              return inner.advance(target);
            }

            @Override
            public long cost() {
              return inner.cost();
            }
          };
    }

    @Override
    public DocIdSetIterator iterator() {
      return it;
    }

    @Override
    public float getMaxScore(int upTo) throws IOException {
      return in.getMaxScore(upTo);
    }

    @Override
    public int docID() {
      return it.docID();
    }

    @Override
    public float score() throws IOException {
      return 0f;
    }
  }
}
