/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;

/**
 * Typed registry for the async-loaded encoders/services that {@link
 * io.justsearch.indexerworker.loop.IndexingLoop} and {@link
 * io.justsearch.indexerworker.services.SearchOrchestrator} both consume.
 *
 * <p>Tempdoc 516 P3 / Slice 5 (W7.2) — replaces the 4 IndexingLoop volatile encoder fields
 * (plus the symmetric 2 SearchOrchestrator copies) with a single shared instance held by
 * both classes. {@link io.justsearch.indexerworker.server.DefaultWorkerAppServices} creates
 * one instance and passes it to both ctors; the {@code wireX} methods become single
 * {@code bindings.bindX(...)} calls instead of fanning out across peer setters.
 *
 * <p>Fields are volatile so the async-load thread that publishes (typically the gRPC
 * incoming-RPC thread or the deferred-init worker thread) can safely hand off to the
 * indexing-loop thread and any search-handling threads without external synchronization.
 *
 * <p>Concurrency contract — bind operations are publication actions; reads see either the
 * prior reference or the new one. Null is the unbound state and means "the corresponding
 * feature is disabled for this run" (e.g., SPLADE off ⇒ {@link #spladeEncoder()} returns
 * null). All readers are already null-tolerant.
 *
 * <p>P5 boundary: a concrete final class, not a strategy interface.
 */
public final class EncoderBindings {

  private volatile SpladeEncoder spladeEncoder;
  private volatile BgeM3Encoder bgeM3Encoder;
  private volatile NerService nerService;
  private volatile DisambiguationService disambiguationService;

  public EncoderBindings() {
    // All fields start null; bind* publishes when async load completes.
  }

  public void bindSpladeEncoder(SpladeEncoder encoder) {
    this.spladeEncoder = encoder;
  }

  public void bindBgeM3Encoder(BgeM3Encoder encoder) {
    this.bgeM3Encoder = encoder;
  }

  public void bindNerService(NerService service) {
    this.nerService = service;
  }

  public void bindDisambiguationService(DisambiguationService service) {
    this.disambiguationService = service;
  }

  public SpladeEncoder spladeEncoder() {
    return spladeEncoder;
  }

  public BgeM3Encoder bgeM3Encoder() {
    return bgeM3Encoder;
  }

  public NerService nerService() {
    return nerService;
  }

  public DisambiguationService disambiguationService() {
    return disambiguationService;
  }
}
