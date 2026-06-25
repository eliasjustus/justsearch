/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.bgem3.BgeM3Assembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.splade.SpladeAssembly;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.RerankerAssembly;
import java.util.List;
import java.util.Optional;

/**
 * Typed bundle returned by {@link InferenceCompositionRoot#compose} — the §7.6 single-entry-point
 * composition output (tempdoc 397 §14.26 T2-C1).
 *
 * <p>Every encoder is {@link Optional}: composition failures (missing model files, degraded
 * hardware, tokenizer load errors) surface as {@link Optional#empty()} rather than throwing, so a
 * single failure doesn't abort boot. The {@code policies} snapshot reflects only the roles whose
 * {@code VariantSelection} resolved — matching today's {@code SessionPoliciesController} omit-on-
 * unresolved semantic.
 *
 * <p>{@link #handles()} collects every live {@link SessionHandle}; {@link #close()} iterates and
 * closes them (best-effort). Swallows per-handle close exceptions — shutdown must never abort
 * mid-iteration.
 *
 * <p>Sparse-model selection (bge-m3 vs splade) means at most one of {@link #splade()} and
 * {@link #bgeM3()} is populated: BGE-M3 wins when {@code cfg.ai().sparseModel() == "bge-m3"} and
 * its assembly succeeds; SPLADE wins otherwise. Both empty = no sparse retrieval.
 *
 * @param embedding dense embedding encoder (skipped when BGE-M3 is active)
 * @param ner NER inference + label mapping
 * @param reranker cross-encoder reranker assembly
 * @param citation citation-scorer assembly (CPU-only; shares the reranker shape)
 * @param splade sparse encoder (SPLADE); empty when BGE-M3 is active or unavailable
 * @param bgeM3 unified dense+sparse encoder; empty unless selected + available
 * @param policies snapshot of {@link PolicySnapshot} for the roles whose variant resolved
 * @param handles every {@link SessionHandle} the surface owns; iterated for shutdown
 */
public record InferenceSurface(
    Optional<EmbeddingAssembly> embedding,
    Optional<NerAssembly> ner,
    Optional<RerankerAssembly> reranker,
    Optional<RerankerAssembly> citation,
    Optional<SpladeAssembly> splade,
    Optional<BgeM3Assembly> bgeM3,
    PolicySnapshot policies,
    List<SessionHandle> handles)
    implements AutoCloseable {

  @Override
  public void close() {
    for (SessionHandle h : handles) {
      try {
        h.close();
      } catch (RuntimeException ignored) {
        // shutdown must continue across per-handle failures
      }
    }
  }
}
