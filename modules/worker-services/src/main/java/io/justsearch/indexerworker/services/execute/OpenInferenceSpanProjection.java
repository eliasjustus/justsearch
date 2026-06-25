/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.execute;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchHit;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.telemetry.OpenInferenceSpans;
import io.opentelemetry.api.common.Attributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Tempdoc 553 Phase A — the worker's Lucene→OpenInference adapter for the {@code search/*} span tree.
 *
 * <p>This is a thin adapter over the cross-module {@link OpenInferenceSpans} projector (in
 * {@code telemetry}): it maps the worker's {@link SearchResult} hits to {@link OpenInferenceSpans.Doc}
 * and delegates the attribute encoding, so the OpenInference key vocabulary + document bounds live in
 * exactly one place (shared with the head spans — no per-module fork; 553 pillars b/c). The
 * {@code SearchExecutor} span sites call these and apply the result to the live span.
 *
 * <p>Pure function ({@code SearchResult} → {@code Attributes}), unit-tested without a live backend.
 */
public final class OpenInferenceSpanProjection {

  private OpenInferenceSpanProjection() {}

  /** A structural (non-leaf) span — the retrieval/chunk-merge phase wrappers. */
  public static Attributes chain() {
    return OpenInferenceSpans.chain();
  }

  /** A retriever leg span: the documents this leg produced, projected from its result. */
  public static Attributes retriever(SearchResult result) {
    return OpenInferenceSpans.retriever(docsOf(result));
  }

  /** A reranker (fusion) span: the fused output documents + the fusion model + input branch count. */
  public static Attributes reranker(String algorithm, int branchCount, SearchResult output) {
    return OpenInferenceSpans.reranker(algorithm, branchCount, docsOf(output));
  }

  /** Map a Lucene {@link SearchResult} to the shared {@link OpenInferenceSpans.Doc} list. */
  private static List<OpenInferenceSpans.Doc> docsOf(SearchResult result) {
    if (result == null || result.hits() == null) {
      return List.of();
    }
    List<SearchHit> hits = result.hits();
    List<OpenInferenceSpans.Doc> docs = new ArrayList<>(Math.min(hits.size(), OpenInferenceSpans.MAX_DOCUMENTS));
    int n = Math.min(hits.size(), OpenInferenceSpans.MAX_DOCUMENTS);
    for (int i = 0; i < n; i++) {
      SearchHit h = hits.get(i);
      if (h == null) {
        continue;
      }
      docs.add(new OpenInferenceSpans.Doc(h.docId(), h.score(), contentOf(h)));
    }
    return docs;
  }

  /** The best-available stored content field for a hit (CONTENT → CONTENT_PREVIEW → TITLE). */
  private static String contentOf(SearchHit h) {
    if (h.fields() == null || h.fields().isEmpty()) {
      return null;
    }
    String c = h.fields().get(SchemaFields.CONTENT);
    if (c == null) {
      c = h.fields().get(SchemaFields.CONTENT_PREVIEW);
    }
    if (c == null) {
      c = h.fields().get(SchemaFields.TITLE);
    }
    return c;
  }
}
