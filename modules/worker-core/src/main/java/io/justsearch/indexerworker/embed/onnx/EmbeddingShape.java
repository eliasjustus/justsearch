/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

/**
 * Model-intrinsic facts about an embedding session. Tempdoc 397 §14.24 FD-Embedding: lets
 * {@link OnnxEmbeddingEncoder} receive input-schema + pooling metadata via typed record instead
 * of loading it from the session + filesystem at construction time.
 *
 * @param maxSequenceLength maximum tokens per inference call (typically 512–2048)
 * @param needsTokenTypeIds true if the ONNX graph declares {@code token_type_ids} among its
 *     input names — determines whether the encoder builds a {@code token_type_ids} tensor per
 *     call
 * @param poolingStrategy pooling strategy auto-detected from {@code pooling_config.json}
 *     (MEAN_POOL by default; CLS for gte-modernbert-style models)
 */
public record EmbeddingShape(
    int maxSequenceLength,
    boolean needsTokenTypeIds,
    OnnxEmbeddingEncoder.PoolingStrategy poolingStrategy) {}
