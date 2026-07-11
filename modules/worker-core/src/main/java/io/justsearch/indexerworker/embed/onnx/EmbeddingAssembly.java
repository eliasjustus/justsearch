/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.ort.ModelCapabilities;
import io.justsearch.ort.SessionHandle;

/**
 * Complete pre-built inputs for {@link OnnxEmbeddingEncoder}. Produced by
 * {@code InferenceCompositionRoot.composeEmbedAssembly(...)} (tempdoc 397 §14.24 FD-Embedding).
 * Centralises all embedding metadata I/O (tokenizer, pooling config, input-name detection) at
 * boot time; encoder constructor does no filesystem I/O.
 *
 * @param sessions ORT session handle
 * @param shape model-intrinsic facts (input-name detection + max sequence length + pooling)
 * @param tokenizer pre-loaded DJL HuggingFace tokenizer (caller owns lifecycle)
 * @param capabilities the full resolved model-capability contract (tempdoc 710 Wave 2 Move 1) —
 *     {@code shape.poolingStrategy()} is already projected from {@code
 *     capabilities.poolingMode()}; this field carries the rest (prefixes, dimension, precision)
 *     for consumers downstream of the composition root (e.g. {@code EmbeddingService}'s prefixes)
 */
public record EmbeddingAssembly(
    SessionHandle sessions,
    EmbeddingShape shape,
    HuggingFaceTokenizer tokenizer,
    ModelCapabilities capabilities) {}
