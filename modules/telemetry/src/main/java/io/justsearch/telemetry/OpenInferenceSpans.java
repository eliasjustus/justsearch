/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.List;

/**
 * Tempdoc 553 Phase A/D — the single, cross-module OpenInference projector for search-execution spans.
 *
 * <p>Both the worker (`search/*` retrieval+fusion) and the head (`search/cross_encoder`,
 * `search/lambdamart`, `search/rerank`) project the documents an operation produced onto their span
 * via this one helper, so the span tree is a governed projection of execution — not a parallel,
 * per-module hand-authored record (553 pillars b/c). It lives in {@code telemetry} (the cross-cutting
 * OTel module) so worker-services, app-services, and reranker all share it rather than forking the
 * OpenInference key vocabulary.
 *
 * <p>Callers map their own hit/result type to {@link Doc} (id + score + optional content); this class
 * owns the OpenInference attribute keys + the bounded document encoding. Document count and content
 * length are bounded ({@link #MAX_DOCUMENTS} / {@link #MAX_CONTENT_CHARS}) to keep spans within the
 * exporter budget.
 *
 * <p><b>Privacy.</b> Per the owner-amended span-privacy contract
 * ({@code docs/reference/contracts/search-execution-spans.md}), these spans carry per-document id,
 * score, and bounded content — the same per-hit facts already on the wire {@code Hit.trace}. Query
 * text and filter values are never emitted here.
 */
public final class OpenInferenceSpans {

  /** The OpenInference span-kind discriminator key + its closed value set. */
  public static final String SPAN_KIND = "openinference.span.kind";

  public static final String RETRIEVER = "RETRIEVER";
  public static final String RERANKER = "RERANKER";
  public static final String CHAIN = "CHAIN";

  /** Attribute-key prefixes the exporter + contract allowlist must permit (dynamic, indexed). */
  public static final String RETRIEVAL_DOCS_PREFIX = "retrieval.documents.";
  public static final String RERANKER_DOCS_PREFIX = "reranker.output_documents.";
  public static final String RERANKER_MODEL = "reranker.model_name";
  public static final String RERANKER_INPUT_BRANCHES = "reranker.input_branch_count";

  /** Bound the documents/content attached to a span so traces stay within the export budget. */
  public static final int MAX_DOCUMENTS = 16;
  public static final int MAX_CONTENT_CHARS = 1024;

  private OpenInferenceSpans() {}

  /** A document an operation produced: id (nullable for content-only rerankers), score, content. */
  public record Doc(String id, double score, String content) {}

  /** A structural (non-leaf) span — phase wrappers whose children carry the documents. */
  public static Attributes chain() {
    return Attributes.builder().put(SPAN_KIND, CHAIN).build();
  }

  /** A retriever span: the documents this leg produced. */
  public static Attributes retriever(List<Doc> docs) {
    AttributesBuilder b = Attributes.builder().put(SPAN_KIND, RETRIEVER);
    addDocuments(b, RETRIEVAL_DOCS_PREFIX, docs);
    return b.build();
  }

  /** A reranker span: the fusion/rerank model, the input count, and the reranked output documents. */
  public static Attributes reranker(String model, int inputCount, List<Doc> output) {
    AttributesBuilder b = Attributes.builder().put(SPAN_KIND, RERANKER);
    if (model != null && !model.isBlank()) {
      b.put(RERANKER_MODEL, model);
    }
    b.put(RERANKER_INPUT_BRANCHES, (long) inputCount);
    addDocuments(b, RERANKER_DOCS_PREFIX, output);
    return b.build();
  }

  private static void addDocuments(AttributesBuilder b, String prefix, List<Doc> docs) {
    if (docs == null) {
      return;
    }
    int n = Math.min(docs.size(), MAX_DOCUMENTS);
    for (int i = 0; i < n; i++) {
      Doc d = docs.get(i);
      if (d == null) {
        continue;
      }
      String base = prefix + i + ".document.";
      if (d.id() != null && !d.id().isEmpty()) {
        b.put(base + "id", d.id());
      }
      b.put(base + "score", d.score());
      String content = bound(d.content());
      if (content != null && !content.isEmpty()) {
        b.put(base + "content", content);
      }
    }
  }

  /** Bound content to {@link #MAX_CONTENT_CHARS}. */
  public static String bound(String content) {
    if (content == null) {
      return null;
    }
    return content.length() > MAX_CONTENT_CHARS ? content.substring(0, MAX_CONTENT_CHARS) : content;
  }
}
