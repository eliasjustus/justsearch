/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingService;

/**
 * Typed output of the deferred model initialization phase (Phase 4).
 * Bundles all model instances resolved during initDeferredModels().
 *
 * <p>All fields are nullable — a null value means the model was not available or
 * failed to initialize. Callers must null-check before use.
 */
public record ModelContext(
    EmbeddingService embeddingService,
    EmbeddingCompatibilityController ecc,
    io.justsearch.indexerworker.ner.NerService nerService,
    io.justsearch.indexerworker.splade.SpladeEncoder spladeEncoder,
    io.justsearch.indexerworker.splade.SpladeIdfQueryEncoder spladeIdfQueryEncoder,
    io.justsearch.indexerworker.bgem3.BgeM3Encoder bgeM3Encoder,
    io.justsearch.indexerworker.disambiguation.DisambiguationService disambiguationService) {}
