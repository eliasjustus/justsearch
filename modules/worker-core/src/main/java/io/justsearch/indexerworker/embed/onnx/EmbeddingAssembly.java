/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
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
 */
public record EmbeddingAssembly(
    SessionHandle sessions, EmbeddingShape shape, HuggingFaceTokenizer tokenizer) {}
